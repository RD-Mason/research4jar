package dev.research4jar.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DaemonTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `daemon resolves default project and relative project-dir and home from client cwd`() {
        val root = Files.createTempDirectory("r4j-daemon-cwd")
        val project = root.resolve("project")
        val nested = project.resolve("nested/work")
        val session = root.resolve("data/sessions/test.db")
        Files.createDirectories(nested)
        Files.createDirectories(session.parent)
        Files.createFile(session)
        Files.createDirectories(project.resolve(".research4jar"))
        Files.writeString(
            project.resolve(".research4jar/project.json"),
            """
            {
              "schema_version": 2,
              "extractor_version": 2,
              "classpath_fingerprint": "test",
              "session_db_path": ${mapper.writeValueAsString(session.toString())},
              "built_at": 0,
              "coverage": {"jars_total": 0, "jars_indexed": 0, "jars_missing": []}
            }
            """.trimIndent(),
        )

        val config = config(root.resolve("daemon"), "cwd-build")
        val server = startServer(config) { argv, out, err -> runCli(argv, out, err) }
        awaitEndpoint(config)

        val implicit = request(config, nested, "status", "--format", "json")
        assertEquals(0, implicit.code)
        assertEquals(project.toString(), implicit.json()["project_dir"].asText())

        val relativeProject = request(
            config,
            nested,
            "status",
            "--project-dir",
            "../..",
            "--format",
            "json",
        )
        assertEquals(0, relativeProject.code)
        assertEquals(project.toString(), relativeProject.json()["project_dir"].asText())

        val relativeHome = request(
            config,
            nested,
            "status",
            "--home",
            "relative-home",
            "--format",
            "json",
        )
        assertEquals(0, relativeHome.code)
        assertEquals(
            nested.resolve("relative-home/manifest.db").normalize().toString(),
            relativeHome.json()["manifest_path"].asText(),
        )

        server.awaitCleanExit()
    }

    @Test
    fun `malformed and unreachable endpoint files are removed and a replacement can serve`() {
        val root = Files.createTempDirectory("r4j-daemon-stale")
        val config = config(root.resolve("daemon"), "recovery-build")
        Files.createDirectories(config.directory)

        Files.writeString(config.endpointFile, "not-an-endpoint")
        assertNull(requestOrNull(config, root, "status"))
        assertFalse(Files.exists(config.endpointFile))

        val closedPort = ServerSocket(0).use { it.localPort }
        writeTestEndpoint(config, closedPort, "stale-token-12345678901234567890")
        assertNull(requestOrNull(config, root, "status"))
        assertFalse(Files.exists(config.endpointFile))

        val startLease = Daemon.acquireStartLease(config)
            ?: error("replacement start lease was not acquired")
        val server = startServer(config, startLease.token) { _, out, _ ->
            out.print("recovered")
            0
        }
        awaitEndpoint(config)
        assertFalse(Files.exists(config.startingFile))
        val recovered = request(config, root, "status")
        assertEquals(0, recovered.code)
        assertEquals("recovered", recovered.stdout)
        server.awaitCleanExit()
    }

    @Test
    fun `transient burst refusal recovered by liveness probe keeps endpoint without replacement`() {
        val root = Files.createTempDirectory("r4j-daemon-refusal-probe")
        val closedPort = ServerSocket(0).use { it.localPort }
        val listener = AtomicReference<ServerSocket?>()
        val probeAccepted = CountDownLatch(1)
        val config = config(root.resolve("daemon"), "probe-build").copy(
            refusalProbeDelaysMs = listOf(0),
            beforeRefusalProbe = {
                if (listener.get() == null) {
                    val replacementListener = ServerSocket()
                    replacementListener.reuseAddress = true
                    replacementListener.bind(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), closedPort),
                        1,
                    )
                    if (listener.compareAndSet(null, replacementListener)) {
                        thread(start = true, isDaemon = true, name = "r4j-refusal-probe") {
                            replacementListener.use {
                                it.accept().use { }
                                probeAccepted.countDown()
                            }
                        }
                    } else {
                        replacementListener.close()
                    }
                }
            },
        )
        writeTestEndpoint(config, closedPort)
        val originalEndpoint = Files.readString(config.endpointFile)

        assertNull(requestOrNull(config, root, "status"))
        assertTrue(probeAccepted.await(1, TimeUnit.SECONDS))
        assertEquals(originalEndpoint, Files.readString(config.endpointFile))
    }

    @Test
    fun `busy command ACKs concurrent client before request queue and keeps endpoint`() {
        val root = Files.createTempDirectory("r4j-daemon-busy")
        val config = config(root.resolve("daemon"), "busy-build").copy(
            handshakeTimeoutMs = 100,
            idleLimitMs = 250,
        )
        val slowStarted = CountDownLatch(1)
        val activeCommands = AtomicInteger()
        val maxActiveCommands = AtomicInteger()
        val server = startServer(config) { argv, out, _ ->
            val active = activeCommands.incrementAndGet()
            maxActiveCommands.updateAndGet { previous -> maxOf(previous, active) }
            try {
                if (argv.getOrNull(1) == "slow") {
                    slowStarted.countDown()
                    Thread.sleep(500)
                }
                out.print(argv.getOrNull(1) ?: "ok")
                0
            } finally {
                activeCommands.decrementAndGet()
            }
        }
        awaitEndpoint(config)

        val first = AtomicReference<Response?>()
        val second = AtomicReference<Response?>()
        val failure = AtomicReference<Throwable?>()
        val firstThread = thread(start = true, name = "r4j-slow-client") {
            try {
                first.set(request(config, root, "status", "slow"))
            } catch (throwable: Throwable) {
                failure.compareAndSet(null, throwable)
            }
        }
        assertTrue(slowStarted.await(1, TimeUnit.SECONDS))
        val secondThread = thread(start = true, name = "r4j-queued-client") {
            try {
                second.set(request(config, root, "status", "second"))
            } catch (throwable: Throwable) {
                failure.compareAndSet(null, throwable)
            }
        }

        // With the former synchronous accept loop this client's 100 ms
        // handshake timeout deleted the healthy endpoint while the first
        // command slept. The connection worker now ACKs before requestLock.
        Thread.sleep(150)
        assertTrue(Files.isRegularFile(config.endpointFile))
        secondThread.join(2_000)
        firstThread.join(2_000)
        failure.get()?.let { throw AssertionError("concurrent daemon request failed", it) }
        assertEquals("slow", first.get()?.stdout)
        assertEquals("second", second.get()?.stdout)
        assertEquals(1, maxActiveCommands.get(), "runCli must remain serialized")

        assertEquals("after", request(config, root, "status", "after").stdout)
        server.awaitCleanExit()
    }

    @Test
    fun `handshake timeout and EOF do not remove a healthy-looking endpoint`() {
        val root = Files.createTempDirectory("r4j-daemon-transient")
        val config = config(root.resolve("daemon"), "transient-build").copy(
            handshakeTimeoutMs = 75,
        )

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            writeTestEndpoint(config, listener.localPort)
            val fake = thread(start = true, isDaemon = true) {
                listener.accept().use { Thread.sleep(200) }
            }
            assertNull(requestOrNull(config, root, "status"))
            assertTrue(Files.isRegularFile(config.endpointFile))
            fake.join(1_000)
        }

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            writeTestEndpoint(config, listener.localPort)
            val fake = thread(start = true, isDaemon = true) {
                listener.accept().close()
            }
            assertNull(requestOrNull(config, root, "status"))
            assertTrue(Files.isRegularFile(config.endpointFile))
            fake.join(1_000)
        }
    }

    @Test
    fun `stale-port impostor cannot learn request or forge authenticated output`() {
        val root = Files.createTempDirectory("r4j-daemon-impostor")
        val config = config(root.resolve("daemon"), "impostor-build").copy(
            handshakeTimeoutMs = 300,
        )
        val observedPrelude = AtomicReference<List<String>>()
        val leakedByte = AtomicReference<Int?>(null)
        val failure = AtomicReference<Throwable?>()

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            writeTestEndpoint(config, listener.localPort)
            val fake = thread(start = true, isDaemon = true, name = "r4j-impostor") {
                try {
                    listener.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream().buffered())
                        val magic = readAsciiLine(input) ?: error("missing protocol magic")
                        val clientNonce = readAsciiLine(input) ?: error("missing client nonce")
                        observedPrelude.set(listOf(magic, clientNonce))

                        // Before proving possession, the real client must wait:
                        // no token-derived MAC, build, cwd, or argv may follow.
                        socket.soTimeout = 75
                        leakedByte.set(
                            try {
                                input.read().takeIf { it >= 0 }
                            } catch (_: java.net.SocketTimeoutException) {
                                null
                            },
                        )

                        val forgedNonce = "forged-server-nonce-1234567890"
                        val forgedMac = encodeTestMac(ByteArray(32))
                        val out = DataOutputStream(socket.getOutputStream().buffered())
                        out.write(
                            (
                                "S $forgedNonce $forgedMac\n" +
                                    "A ${config.buildIdentity}\nO 6\nforgedX 0\n"
                            ).toByteArray(StandardCharsets.US_ASCII),
                        )
                        out.flush()
                    }
                } catch (throwable: Throwable) {
                    failure.set(throwable)
                }
            }

            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            assertNull(Daemon.tryServe(arrayOf("status"), root, stdout, stderr, config))
            fake.join(1_000)
            failure.get()?.let { throw AssertionError("impostor failed", it) }
            assertEquals("R4JD3", observedPrelude.get()?.get(0))
            assertTrue((observedPrelude.get()?.get(1)?.length ?: 0) >= 20)
            assertNull(leakedByte.get())
            assertEquals(0, stdout.size())
            assertEquals(0, stderr.size())
            assertTrue(Files.isRegularFile(config.endpointFile))
        }
    }

    @Test
    fun `client challenge deadline is total despite a byte-dripping impostor`() {
        val root = Files.createTempDirectory("r4j-daemon-impostor-drip")
        val config = config(root.resolve("daemon"), "impostor-drip-build").copy(
            handshakeTimeoutMs = 100,
        )

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            writeTestEndpoint(config, listener.localPort)
            val fake = thread(start = true, isDaemon = true, name = "r4j-impostor-drip") {
                try {
                    listener.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream().buffered())
                        readAsciiLine(input)
                        readAsciiLine(input)
                        val forged = (
                            "S forged-server-nonce-1234567890 ${encodeTestMac(ByteArray(32))}\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                        for (byte in forged) {
                            socket.getOutputStream().write(byte.toInt())
                            socket.getOutputStream().flush()
                            Thread.sleep(25) // below SO_TIMEOUT; whole line takes > 1 second
                        }
                    }
                } catch (_: Exception) {
                    // Expected once the absolute client deadline closes the socket.
                }
            }

            val started = System.nanoTime()
            assertNull(requestOrNull(config, root, "status"))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(elapsedMs < 600, "client took ${elapsedMs}ms instead of its 100ms deadline")
            assertTrue(Files.isRegularFile(config.endpointFile))
            fake.join(1_000)
        }
    }

    @Test
    fun `partial and oversized responses never leak output before terminal frame`() {
        val root = Files.createTempDirectory("r4j-daemon-response-buffer")
        val base = config(root.resolve("daemon"), "response-build")

        val partial = requestAgainstFake(
            base,
            root,
            "A ${base.buildIdentity}\nO 7\npartial".toByteArray(StandardCharsets.US_ASCII),
        )
        assertNull(partial.code)
        assertEquals(0, partial.stdout.size())
        assertEquals(0, partial.stderr.size())
        assertTrue(Files.isRegularFile(base.endpointFile))

        val bounded = base.copy(maxFrameBytes = 16, maxResponseBytes = 24)
        val oversized = requestAgainstFake(
            bounded,
            root,
            "A ${bounded.buildIdentity}\nO 2147483647\n".toByteArray(StandardCharsets.US_ASCII),
        )
        assertNull(oversized.code)
        assertEquals(0, oversized.stdout.size())
        assertEquals(0, oversized.stderr.size())

        val aggregateOverflow = requestAgainstFake(
            bounded,
            root,
            (
                "A ${bounded.buildIdentity}\n" +
                    "O 16\nabcdefghijklmnop" +
                    "E 9\n123456789" +
                    "X 0\n"
            ).toByteArray(StandardCharsets.US_ASCII),
        )
        assertNull(aggregateOverflow.code)
        assertEquals(0, aggregateOverflow.stdout.size())
        assertEquals(0, aggregateOverflow.stderr.size())
        assertTrue(Files.isRegularFile(bounded.endpointFile))
    }

    @Test
    fun `server response capture is shared bounded and survives handler overflow`() {
        val root = Files.createTempDirectory("r4j-daemon-server-response-cap")
        val config = config(root.resolve("daemon"), "server-cap-build").copy(
            maxFrameBytes = 16,
            maxResponseBytes = 32,
            idleLimitMs = 400,
        )
        val server = startServer(config) { argv, out, err ->
            if (argv.getOrNull(1) == "overflow") {
                out.print("o".repeat(20))
                err.print("e".repeat(20))
            } else {
                out.print("healthy")
            }
            0
        }
        awaitEndpoint(config)

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        assertNull(
            Daemon.tryServe(
                arrayOf("status", "overflow"),
                root,
                stdout,
                stderr,
                config,
            ),
        )
        assertEquals(0, stdout.size())
        assertEquals(0, stderr.size())
        assertTrue(Files.isRegularFile(config.endpointFile))

        assertEquals("healthy", request(config, root, "status", "after-overflow").stdout)
        server.awaitCleanExit()
    }

    @Test
    fun `old daemon cleanup cannot delete atomically replaced endpoint`() {
        val root = Files.createTempDirectory("r4j-daemon-endpoint-cas")
        val oldConfig = config(root.resolve("daemon"), "cas-old").copy(idleLimitMs = 600)
        val oldServer = startServer(oldConfig) { _, out, _ ->
            out.print("old")
            0
        }
        awaitEndpoint(oldConfig)
        val oldEndpoint = Files.readString(oldConfig.endpointFile)

        val newConfig = oldConfig.copy(buildIdentity = "cas-new", idleLimitMs = 1_200)
        val newServer = startServer(newConfig) { _, out, _ ->
            out.print("new")
            0
        }
        var newEndpoint: String? = null
        for (attempt in 0 until 200) {
            val current = Files.readString(newConfig.endpointFile)
            if (current != oldEndpoint) {
                newEndpoint = current
                break
            }
            Thread.sleep(5)
        }
        assertTrue(newEndpoint != null, "replacement endpoint was not atomically published")

        oldServer.awaitCleanExit(1_500)
        assertEquals(newEndpoint, Files.readString(newConfig.endpointFile))
        assertEquals("new", request(newConfig, root, "status").stdout)
        newServer.awaitCleanExit(2_000)
    }

    @Test
    fun `oversized pre-authentication line is rejected immediately without retaining a worker`() {
        val root = Files.createTempDirectory("r4j-daemon-line-limit")
        val config = config(root.resolve("daemon"), "limit-build").copy(
            requestReadTimeoutMs = 1_000,
            idleLimitMs = 300,
        )
        val server = startServer(config) { _, out, _ ->
            out.print("healthy")
            0
        }
        awaitEndpoint(config)
        val endpoint = readTestEndpoint(config)

        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port), 200)
            socket.soTimeout = 500
            socket.getOutputStream().write(
                ("R4JD3\n" + "x".repeat(4_096)).toByteArray(StandardCharsets.US_ASCII),
            )
            socket.getOutputStream().flush()
            val response = socket.getInputStream()
                .bufferedReader(StandardCharsets.US_ASCII)
                .readLine()
            assertEquals("M PROTOCOL", response)
        }

        assertTrue(Files.isRegularFile(config.endpointFile))
        assertEquals("healthy", request(config, root, "status").stdout)
        server.awaitCleanExit()
    }

    @Test
    fun `total request deadline evicts byte-dripping unauthenticated clients`() {
        val root = Files.createTempDirectory("r4j-daemon-request-deadline")
        val config = config(root.resolve("daemon"), "deadline-build").copy(
            requestReadTimeoutMs = 100,
            connectionWorkers = 2,
            connectionQueueCapacity = 2,
            idleLimitMs = 500,
        )
        val server = startServer(config) { _, out, _ ->
            out.print("healthy")
            0
        }
        awaitEndpoint(config)
        val endpoint = readTestEndpoint(config)
        val drips = List(config.connectionWorkers) {
            Socket().apply {
                connect(InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port), 200)
            }
        }
        val dripThreads = drips.mapIndexed { index, socket ->
            thread(start = true, isDaemon = true, name = "r4j-byte-drip-$index") {
                repeat(20) {
                    try {
                        socket.getOutputStream().write('R'.code)
                        socket.getOutputStream().flush()
                    } catch (_: Exception) {
                        return@thread
                    }
                    Thread.sleep(25) // safely below the 100 ms SO_TIMEOUT
                }
            }
        }

        Thread.sleep(225)
        assertEquals("healthy", request(config, root, "status").stdout)
        drips.forEach(Socket::close)
        dripThreads.forEach { it.join(500) }
        server.awaitCleanExit()
    }

    @Test
    fun `authenticated server rejects non-query and environment-sensitive commands`() {
        val root = Files.createTempDirectory("r4j-daemon-server-allowlist")
        val config = config(root.resolve("daemon"), "allowlist-build").copy(idleLimitMs = 500)
        val calls = AtomicInteger()
        val server = startServer(config) { _, out, _ ->
            calls.incrementAndGet()
            out.print("healthy")
            0
        }
        awaitEndpoint(config)
        val endpoint = readTestEndpoint(config)

        for (argv in listOf(
            arrayOf("index"),
            arrayOf("cache", "gc"),
            arrayOf("registry", "seed"),
            arrayOf("mcp"),
            arrayOf("status", "--check-classpath"),
            arrayOf("find-class", "Thing", "--page-size", "101"),
        )) {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port), 200)
                socket.soTimeout = 500
                val input = sendAuthenticatedRequest(socket, config, endpoint, root, argv = argv)
                assertEquals(
                    "M COMMAND",
                    readAsciiLine(input),
                )
            }
        }
        assertEquals(0, calls.get())
        assertEquals("healthy", request(config, root, "status").stdout)
        assertEquals(1, calls.get())
        server.awaitCleanExit()
    }

    @Test
    fun `server rejects a request changed after the client MAC`() {
        val root = Files.createTempDirectory("r4j-daemon-request-mac")
        val config = config(root.resolve("daemon"), "request-mac-build").copy(idleLimitMs = 500)
        val calls = AtomicInteger()
        val server = startServer(config) { _, out, _ ->
            calls.incrementAndGet()
            out.print("healthy")
            0
        }
        awaitEndpoint(config)
        val endpoint = readTestEndpoint(config)

        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port), 200)
            socket.soTimeout = 500
            val input = sendAuthenticatedRequest(
                socket,
                config,
                endpoint,
                root,
                argv = arrayOf("status"),
                tamperRequest = true,
            )
            assertEquals(-1, input.read())
        }
        assertEquals(0, calls.get())
        assertEquals("healthy", request(config, root, "status").stdout)
        assertEquals(1, calls.get())
        server.awaitCleanExit()
    }

    @Test
    fun `paused response readers cannot exhaust aggregate daemon buffers`() {
        val root = Files.createTempDirectory("r4j-daemon-response-slots")
        val captured = CountDownLatch(1)
        val config = config(root.resolve("daemon"), "slot-build").copy(
            maxFrameBytes = 1_024 * 1_024,
            maxResponseBytes = 8 * 1_024 * 1_024,
            bufferedResponseSlots = 1,
            responseWriteTimeoutMs = 150,
            handshakeTimeoutMs = 300,
            idleLimitMs = 800,
        )
        val server = startServer(config) { argv, out, _ ->
            if (argv.getOrNull(1) == "block") {
                val chunk = ByteArray(1_024 * 1_024) { 'x'.code.toByte() }
                repeat(7) { out.write(chunk) }
                captured.countDown()
            } else {
                out.print("healthy")
            }
            0
        }
        awaitEndpoint(config)
        val endpoint = readTestEndpoint(config)
        val paused = Socket().apply {
            receiveBufferSize = 1_024
            connect(InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port), 200)
            soTimeout = 500
            val input = sendAuthenticatedRequest(
                this,
                config,
                endpoint,
                root,
                argv = arrayOf("status", "block"),
            )
            assertEquals(
                "A ${config.buildIdentity}",
                readAsciiLine(input),
            )
        }
        assertTrue(captured.await(1, TimeUnit.SECONDS))

        // The sole aggregate response permit is retained by the paused writer,
        // so a second client gets a clean cold-fallback signal without running.
        assertNull(requestOrNull(config, root, "status", "while-blocked"))

        var recovered: Response? = null
        for (attempt in 0 until 20) {
            Thread.sleep(25)
            recovered = requestOrNull(config, root, "status", "after-deadline")
            if (recovered != null) break
        }
        assertEquals("healthy", recovered?.stdout)
        paused.close()
        server.awaitCleanExit(2_000)
    }

    @Test
    fun `build mismatch invalidates old endpoint and new daemon takes ownership`() {
        val root = Files.createTempDirectory("r4j-daemon-build")
        val oldConfig = config(root.resolve("daemon"), "old-build")
        val oldServer = startServer(oldConfig) { _, out, _ ->
            out.print("old")
            0
        }
        awaitEndpoint(oldConfig)

        val newConfig = oldConfig.copy(buildIdentity = "new-build")
        sendBuildMismatch(oldConfig, "new-build", "wrong-token")
        assertEquals("old", request(oldConfig, root, "status").stdout)
        // Normal upgrade discovery is protocol-keyed, so the new client finds
        // the old build and retires it after token authentication.
        assertNull(requestOrNull(newConfig, root, "status"))
        assertFalse(Files.exists(newConfig.endpointFile))

        val newServer = startServer(newConfig) { _, out, _ ->
            out.print("new")
            0
        }
        awaitEndpoint(newConfig)
        oldServer.awaitCleanExit(1_000)
        val response = request(newConfig, root, "status")
        assertEquals(0, response.code)
        assertEquals("new", response.stdout)

        // The old daemon's eventual cleanup is token/port guarded and must not
        // remove the replacement endpoint while the replacement owns it.
        if (newServer.thread.isAlive) {
            assertTrue(Files.isRegularFile(newConfig.endpointFile))
        }
        newServer.awaitCleanExit()
    }

    @Test
    fun `only one concurrent cold starter owns the atomic lease and stale leases recover`() {
        val root = Files.createTempDirectory("r4j-daemon-lease")
        val config = config(root.resolve("daemon"), "lease-build")
        val contenders = 12
        val ready = CountDownLatch(contenders)
        val start = CountDownLatch(1)
        val leases = Collections.synchronizedList(mutableListOf<Daemon.StartLease?>())
        val threads = (1..contenders).map { index ->
            thread(start = true, name = "r4j-lease-$index") {
                ready.countDown()
                start.await()
                leases += Daemon.acquireStartLease(config)
            }
        }
        ready.await()
        start.countDown()
        threads.forEach { it.join(2_000) }

        val owner = leases.filterNotNull().single()
        assertEquals(owner.token, Files.readString(config.startingFile))

        // A crashed starter cannot block launches forever. Race stale recovery
        // against the old owner's late release: the file lock must leave one
        // replacement owner, and the old token must never delete it.
        Files.setLastModifiedTime(config.startingFile, FileTime.fromMillis(1_000))
        val recoveryReady = CountDownLatch(contenders + 1)
        val recoveryStart = CountDownLatch(1)
        val recovered = Collections.synchronizedList(mutableListOf<Daemon.StartLease?>())
        val recoveryThreads = (1..contenders).map { index ->
            thread(start = true, name = "r4j-stale-lease-$index") {
                recoveryReady.countDown()
                recoveryStart.await()
                recovered += Daemon.acquireStartLease(
                    config,
                    nowMs = 1_000 + config.startLeaseTimeoutMs,
                )
            }
        }
        val lateRelease = thread(start = true, name = "r4j-late-lease-release") {
            recoveryReady.countDown()
            recoveryStart.await()
            Daemon.releaseStartLease(config, owner.token)
        }
        recoveryReady.await()
        recoveryStart.countDown()
        recoveryThreads.forEach { it.join(2_000) }
        lateRelease.join(2_000)

        val replacement = recovered.filterNotNull().single()
        assertTrue(replacement.token != owner.token)
        assertEquals(replacement.token, Files.readString(config.startingFile))
        Daemon.releaseStartLease(config, owner.token)
        assertEquals(replacement.token, Files.readString(config.startingFile))
        Daemon.releaseStartLease(config, replacement.token)
        assertFalse(Files.exists(config.startingFile))
    }

    @Test
    fun `delayed starter that lost stale lease cannot overwrite takeover endpoint`() {
        val root = Files.createTempDirectory("r4j-daemon-lease-aba")
        val delayedAtPublish = CountDownLatch(1)
        val resumeDelayed = CountDownLatch(1)
        val delayedConfig = config(root.resolve("daemon"), "delayed-build").copy(
            startLeaseTimeoutMs = 50,
            idleLimitMs = 500,
            beforeEndpointPublish = {
                delayedAtPublish.countDown()
                resumeDelayed.await()
            },
        )
        val delayedLease = Daemon.acquireStartLease(delayedConfig)
            ?: error("delayed starter did not acquire its lease")
        val delayedServer = startServer(delayedConfig, delayedLease.token) { _, out, _ ->
            out.print("delayed")
            0
        }
        assertTrue(delayedAtPublish.await(1, TimeUnit.SECONDS))
        assertFalse(Files.exists(delayedConfig.endpointFile))

        Files.setLastModifiedTime(delayedConfig.startingFile, FileTime.fromMillis(1_000))
        val takeoverConfig = delayedConfig.copy(
            buildIdentity = "takeover-build",
            idleLimitMs = 1_000,
            beforeEndpointPublish = null,
        )
        val takeoverLease = Daemon.acquireStartLease(takeoverConfig, nowMs = 1_050)
            ?: error("takeover starter did not replace the expired lease")
        val takeoverServer = startServer(takeoverConfig, takeoverLease.token) { _, out, _ ->
            out.print("takeover")
            0
        }
        awaitEndpoint(takeoverConfig)
        assertFalse(Files.exists(takeoverConfig.startingFile))
        val takeoverEndpoint = Files.readString(takeoverConfig.endpointFile)

        resumeDelayed.countDown()
        delayedServer.awaitCleanExit(1_000)
        assertEquals(takeoverEndpoint, Files.readString(takeoverConfig.endpointFile))
        assertEquals("takeover", request(takeoverConfig, root, "status").stdout)
        takeoverServer.awaitCleanExit(2_000)
    }

    @Test
    fun `only explicit no-daemon environment disables the fast path`() {
        val root = Files.createTempDirectory("r4j-daemon-env")
        val base = config(root.resolve("daemon"), "env-build")
        assertTrue(Daemon.daemonApplies(arrayOf("status"), base))
        assertTrue(
            Daemon.daemonApplies(
                arrayOf("status"),
                base.copy(
                    environment = mapOf(
                        "RESEARCH4JAR_INDEX" to "/tmp/index",
                        "RESEARCH4JAR_QUERY" to "/tmp/query",
                        "RESEARCH4JAR_REGISTRY" to "https://registry.invalid",
                        "RESEARCH4JAR_SESSION_MAX_AGE" to "off",
                    ),
                ),
            ),
        )
        assertTrue(
            Daemon.daemonApplies(
                arrayOf("status"),
                base.copy(environment = mapOf("RESEARCH4JAR_HOME" to "/tmp/home")),
            ),
        )
        assertFalse(
            Daemon.daemonApplies(
                arrayOf("status"),
                base.copy(environment = mapOf("RESEARCH4JAR_NO_DAEMON" to "1")),
            ),
        )
        assertFalse(Daemon.daemonApplies(arrayOf("status", "--check-classpath"), base))
        assertFalse(
            Daemon.daemonApplies(
                arrayOf("status", "--format", "json", "--check-classpath"),
                base,
            ),
        )
        assertTrue(Daemon.daemonApplies(arrayOf("find-class", "Thing", "--page-size", "100"), base))
        assertFalse(Daemon.daemonApplies(arrayOf("find-class", "Thing", "--page-size", "101"), base))
        assertFalse(Daemon.daemonApplies(arrayOf("find-class", "Thing", "--page-size"), base))
        assertFalse(Daemon.daemonApplies(arrayOf("find-class", "Thing", "--page-size", "many"), base))
        assertFalse(Daemon.daemonApplies(arrayOf("index"), base))
    }

    @Test
    fun `build identity changes when archive content changes under preserved metadata`() {
        val root = Files.createTempDirectory("r4j-daemon-build-identity")
        val jar = root.resolve("research4jar.jar")
        val fixedTime = FileTime.fromMillis(1_700_000_000_000L)
        fun write(payload: String) {
            ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
                val entry = ZipEntry("payload.txt").apply { time = 0L }
                zip.putNextEntry(entry)
                zip.write(payload.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            Files.setLastModifiedTime(jar, fixedTime)
        }

        write("aaaa")
        val size = Files.size(jar)
        val first = Daemon.artifactBuildIdentity(jar, "same-version")
        write("bbbb")
        assertEquals(size, Files.size(jar))
        assertEquals(fixedTime, Files.getLastModifiedTime(jar))
        val second = Daemon.artifactBuildIdentity(jar, "same-version")
        assertTrue(first != second)
    }

    @Test
    fun `different data homes discover independent daemons`() {
        val root = Files.createTempDirectory("r4j-daemon-homes")
        val firstConfig = config(root.resolve("home-a/daemon"), "same-build")
        val secondConfig = config(root.resolve("home-b/daemon"), "same-build")
        val firstServer = startServer(firstConfig) { _, out, _ ->
            out.print("home-a")
            0
        }
        val secondServer = startServer(secondConfig) { _, out, _ ->
            out.print("home-b")
            0
        }
        awaitEndpoint(firstConfig)
        awaitEndpoint(secondConfig)

        assertEquals("home-a", request(firstConfig, root, "status").stdout)
        assertEquals("home-b", request(secondConfig, root, "status").stdout)

        firstServer.awaitCleanExit()
        secondServer.awaitCleanExit()
    }

    private fun config(directory: Path, buildIdentity: String) = Daemon.RuntimeConfig(
        directory = directory,
        buildIdentity = buildIdentity,
        idleLimitMs = 250,
        acceptTimeoutMs = 25,
        connectTimeoutMs = 200,
        handshakeTimeoutMs = 200,
        startLeaseTimeoutMs = 2_000,
        refusalProbeDelaysMs = listOf(5, 10),
        environment = emptyMap(),
        installShutdownHook = false,
    )

    private data class Response(
        val code: Int,
        val stdout: String,
        val stderr: String,
    ) {
        fun json(): JsonNode = jacksonObjectMapper().readTree(stdout)
    }

    private data class BufferedAttempt(
        val code: Int?,
        val stdout: ByteArrayOutputStream,
        val stderr: ByteArrayOutputStream,
    )

    private fun requestAgainstFake(
        config: Daemon.RuntimeConfig,
        cwd: Path,
        response: ByteArray,
    ): BufferedAttempt {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val endpoint = TestEndpoint(
                port = listener.localPort,
                token = "test-token-1234567890123456789012",
                generation = "test-generation-12345678901234567890",
            )
            writeTestEndpoint(config, endpoint.port, endpoint.token, endpoint.generation)
            val fakeFailure = AtomicReference<Throwable?>()
            val fake = thread(start = true, isDaemon = true, name = "r4j-fake-daemon") {
                try {
                    listener.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream().buffered())
                        check(readAsciiLine(input) == "R4JD3")
                        val clientNonce = readAsciiLine(input) ?: error("missing client nonce")
                        val serverNonce = "test-server-nonce-123456789012"
                        val serverMac = encodeTestMac(
                            testServerMac(
                                endpoint.token,
                                endpoint.generation,
                                config.buildIdentity,
                                clientNonce,
                                serverNonce,
                            ),
                        )
                        val output = DataOutputStream(socket.getOutputStream().buffered())
                        output.write(
                            "S $serverNonce $serverMac\n".toByteArray(StandardCharsets.US_ASCII),
                        )
                        output.flush()

                        val proof = readAsciiLine(input)?.split(" ")
                            ?: error("missing client proof")
                        check(proof.size == 2 && proof[0] == "C")
                        val requestLines = ArrayList<String>()
                        requestLines += readAsciiLine(input) ?: error("missing client build")
                        requestLines += readAsciiLine(input) ?: error("missing client cwd")
                        val argcText = readAsciiLine(input) ?: error("missing client argc")
                        requestLines += argcText
                        val argc = argcText.toIntOrNull() ?: error("invalid client argc")
                        repeat(argc) {
                            requestLines += readAsciiLine(input) ?: error("missing client argument")
                        }
                        val request = requestLines.joinToString(separator = "\n", postfix = "\n")
                            .toByteArray(StandardCharsets.US_ASCII)
                        val receivedMac = Base64.getUrlDecoder().decode(proof[1])
                        check(
                            MessageDigest.isEqual(
                                testClientMac(
                                    endpoint.token,
                                    endpoint.generation,
                                    config.buildIdentity,
                                    clientNonce,
                                    serverNonce,
                                    request,
                                ),
                                receivedMac,
                            ),
                        )
                        output.write(response)
                        output.flush()
                        socket.shutdownOutput()
                    }
                } catch (throwable: Throwable) {
                    fakeFailure.set(throwable)
                }
            }
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val code = Daemon.tryServe(arrayOf("status"), cwd, stdout, stderr, config)
            fake.join(1_000)
            assertFalse(fake.isAlive, "fake daemon did not finish the authenticated exchange")
            fakeFailure.get()?.let { throw AssertionError("fake daemon failed", it) }
            return BufferedAttempt(code, stdout, stderr)
        }
    }

    private fun request(config: Daemon.RuntimeConfig, cwd: Path, vararg argv: String): Response =
        requestOrNull(config, cwd, *argv)
            ?: error("daemon did not serve ${argv.joinToString(" ")}")

    private fun requestOrNull(
        config: Daemon.RuntimeConfig,
        cwd: Path,
        vararg argv: String,
    ): Response? {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val code = Daemon.tryServe(argv.toList().toTypedArray(), cwd, stdout, stderr, config) ?: return null
        return Response(
            code,
            stdout.toString(Charsets.UTF_8),
            stderr.toString(Charsets.UTF_8),
        )
    }

    private data class ServerHandle(
        val thread: Thread,
        val failure: AtomicReference<Throwable?>,
    ) {
        fun awaitCleanExit(timeoutMs: Long = 3_000) {
            thread.join(timeoutMs)
            assertFalse(thread.isAlive, "daemon test server did not idle-exit")
            failure.get()?.let { throw AssertionError("daemon test server failed", it) }
        }
    }

    private fun startServer(
        config: Daemon.RuntimeConfig,
        startLeaseToken: String? = null,
        command: (Array<String>, PrintStream, PrintStream) -> Int,
    ): ServerHandle {
        val failure = AtomicReference<Throwable?>()
        val serverThread = thread(start = true, isDaemon = true, name = "r4j-daemon-test") {
            try {
                Daemon.runServer(command, config, startLeaseToken)
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }
        return ServerHandle(serverThread, failure)
    }

    private fun awaitEndpoint(config: Daemon.RuntimeConfig, server: ServerHandle? = null) {
        // Generous budget: loaded CI runners (windows-latest especially) can
        // take multiple seconds between thread start and endpoint publish.
        repeat(2000) {
            if (Files.isRegularFile(config.endpointFile)) return
            server?.failure?.get()?.let { throw IllegalStateException("daemon server died before publishing", it) }
            Thread.sleep(5)
        }
        error("daemon endpoint was not published" + (server?.failure?.get()?.let { ": $it" } ?: ""))
    }

    private fun sendBuildMismatch(
        config: Daemon.RuntimeConfig,
        buildIdentity: String,
        token: String,
    ) {
        val endpoint = readTestEndpoint(config)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port), 200)
            socket.soTimeout = 200
            val input = sendAuthenticatedRequest(
                socket,
                config,
                endpoint,
                Paths.get("").toAbsolutePath(),
                argv = arrayOf("status"),
                clientBuild = buildIdentity,
                macToken = token,
            )
            assertEquals(-1, input.read())
        }
    }

    private data class TestEndpoint(val port: Int, val token: String, val generation: String)

    private fun sendAuthenticatedRequest(
        socket: Socket,
        config: Daemon.RuntimeConfig,
        endpoint: TestEndpoint,
        cwd: Path,
        argv: Array<out String>,
        clientBuild: String = config.buildIdentity,
        macToken: String = endpoint.token,
        tamperRequest: Boolean = false,
    ): DataInputStream {
        val clientNonce = "test-client-nonce-${System.nanoTime()}"
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())
        output.write("R4JD3\n$clientNonce\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        val challenge = readAsciiLine(input)?.split(" ") ?: error("missing server challenge")
        assertEquals(3, challenge.size)
        assertEquals("S", challenge[0])
        val serverNonce = challenge[1]
        val serverMac = Base64.getUrlDecoder().decode(challenge[2])
        assertTrue(
            MessageDigest.isEqual(
                testServerMac(
                    endpoint.token,
                    endpoint.generation,
                    config.buildIdentity,
                    clientNonce,
                    serverNonce,
                ),
                serverMac,
            ),
            "server challenge must prove endpoint-token possession",
        )

        val body = testRequestBody(clientBuild, cwd, argv)
        val clientMac = encodeTestMac(
            testClientMac(
                macToken,
                endpoint.generation,
                config.buildIdentity,
                clientNonce,
                serverNonce,
                body,
            ),
        )
        val wireBody = if (tamperRequest) {
            body.clone().also { bytes ->
                val lastValue = bytes.indexOfLast { it != '\n'.code.toByte() }
                check(lastValue >= 0)
                bytes[lastValue] = if (bytes[lastValue] == 'A'.code.toByte()) {
                    'B'.code.toByte()
                } else {
                    'A'.code.toByte()
                }
            }
        } else {
            body
        }
        output.write("C $clientMac\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(wireBody)
        output.flush()
        return input
    }

    private fun testRequestBody(
        clientBuild: String,
        cwd: Path,
        argv: Array<out String>,
    ): ByteArray {
        val encoder = Base64.getEncoder()
        return buildList {
            add(clientBuild)
            add(encoder.encodeToString(cwd.toString().toByteArray(StandardCharsets.UTF_8)))
            add(argv.size.toString())
            argv.forEach { add(encoder.encodeToString(it.toByteArray(StandardCharsets.UTF_8))) }
        }.joinToString(separator = "\n", postfix = "\n")
            .toByteArray(StandardCharsets.US_ASCII)
    }

    private fun readTestEndpoint(config: Daemon.RuntimeConfig): TestEndpoint {
        val lines = Files.readAllLines(config.endpointFile, StandardCharsets.US_ASCII)
        assertEquals("R4JE2", lines[0])
        return TestEndpoint(lines[2].toInt(), lines[3], lines[1])
    }

    private fun writeTestEndpoint(
        config: Daemon.RuntimeConfig,
        port: Int,
        token: String = "test-token-1234567890123456789012",
        generation: String = "test-generation-12345678901234567890",
    ) {
        Files.createDirectories(config.directory)
        Files.writeString(
            config.endpointFile,
            "R4JE2\n$generation\n$port\n$token\n${config.buildIdentity}\n",
            StandardCharsets.US_ASCII,
        )
    }

    private fun testServerMac(
        token: String,
        generation: String,
        serverBuild: String,
        clientNonce: String,
        serverNonce: String,
    ): ByteArray = testAuthenticationMac(
        token,
        "research4jar-daemon-v3/server",
        generation.toByteArray(StandardCharsets.US_ASCII),
        serverBuild.toByteArray(StandardCharsets.US_ASCII),
        clientNonce.toByteArray(StandardCharsets.US_ASCII),
        serverNonce.toByteArray(StandardCharsets.US_ASCII),
    )

    private fun testClientMac(
        token: String,
        generation: String,
        serverBuild: String,
        clientNonce: String,
        serverNonce: String,
        request: ByteArray,
    ): ByteArray = testAuthenticationMac(
        token,
        "research4jar-daemon-v3/client",
        generation.toByteArray(StandardCharsets.US_ASCII),
        serverBuild.toByteArray(StandardCharsets.US_ASCII),
        clientNonce.toByteArray(StandardCharsets.US_ASCII),
        serverNonce.toByteArray(StandardCharsets.US_ASCII),
        request,
    )

    private fun testAuthenticationMac(
        token: String,
        domain: String,
        vararg fields: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(StandardCharsets.US_ASCII), "HmacSHA256"))
        mac.updateTestField(domain.toByteArray(StandardCharsets.US_ASCII))
        fields.forEach { field -> mac.updateTestField(field) }
        return mac.doFinal()
    }

    private fun Mac.updateTestField(bytes: ByteArray) {
        update((bytes.size ushr 24).toByte())
        update((bytes.size ushr 16).toByte())
        update((bytes.size ushr 8).toByte())
        update(bytes.size.toByte())
        update(bytes)
    }

    private fun encodeTestMac(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun readAsciiLine(input: DataInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.US_ASCII)
            if (byte == '\n'.code) return bytes.toString(StandardCharsets.US_ASCII)
            bytes.write(byte)
        }
    }
}
