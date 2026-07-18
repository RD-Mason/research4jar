package dev.research4jar.cli

import dev.research4jar.runtime.WorkingDirectoryContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * CLI daemon: a warm JVM serving query commands over TCP loopback so
 * one-shot CLI calls skip JVM+driver startup (~700ms -> ~50-80ms measured).
 * The endpoint is authenticated, build-identified, self-healing after stale
 * files, and idle-exits after 30 minutes.
 *
 * Protocol (line-oriented except framed response payloads):
 *   C -> S  R4JD3\n<client-nonce>\n
 *   S -> C  S <server-nonce> <server-mac>\n
 *   C -> S  C <request-mac>\n<build-id>\n<cwd-b64>\n<argc>\n<arg-b64>...\n
 *   S -> C  A <build-id>\n        mutually authenticated handshake
 *           M BUILD <build-id>\n authenticated build mismatch
 *           M PROTOCOL\n          protocol mismatch
 *   O <len>\n<raw bytes>  stdout chunk
 *   E <len>\n<raw bytes>  stderr chunk
 *   X <code>\n            exit (terminal)
 *
 * The client half deliberately touches only JDK classes; jackson/sqlite never
 * load on the fast path.
 */
object Daemon {
    /** Wire protocol and endpoint-layout version. */
    const val VERSION = "3"
    private const val MAGIC = "R4JD3"
    private const val ENDPOINT_MAGIC = "R4JE2"
    private const val BUILD_MISMATCH = "M BUILD"
    private const val PROTOCOL_MISMATCH = "M PROTOCOL"
    private const val COMMAND_REJECTED = "M COMMAND"
    private const val SERVER_PROOF = "S"
    private const val CLIENT_PROOF = "C"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val SERVER_AUTH_DOMAIN = "research4jar-daemon-v3/server"
    private const val CLIENT_AUTH_DOMAIN = "research4jar-daemon-v3/client"
    private const val START_LEASE_PROPERTY = "research4jar.daemon.startLease"
    private const val MAX_MAGIC_LINE_BYTES = 16
    private const val MAX_BUILD_LINE_BYTES = 128
    private const val MAX_NONCE_LINE_BYTES = 64
    private const val MAX_MAC_LINE_BYTES = 64
    private const val MAX_CWD_LINE_BYTES = 65_536
    private const val MAX_COUNT_LINE_BYTES = 16
    private const val MAX_ARG_LINE_BYTES = 262_144
    private const val MAX_REQUEST_BYTES = 1_048_576
    private const val MAX_RESPONSE_HEADER_BYTES = 256
    private const val MAX_FRAME_BYTES = 1 * 1_024 * 1_024
    private const val MAX_RESPONSE_BYTES = 8 * 1_024 * 1_024
    private const val MAX_DAEMON_PAGE_SIZE = 100
    private val DEFAULT_IDLE_LIMIT_MS = TimeUnit.MINUTES.toMillis(30)
    private val DEFAULT_START_LEASE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10)
    // Lazy: computing the identity walks every fat-jar zip entry (~40ms).
    // Only the daemon handshake needs it — version/NO_DAEMON/non-daemonable
    // invocations must not pay for it at class-load time.
    private val BUILD_ID: String by lazy { currentBuildIdentity() }

    /** Commands safe to serve warm: read-only, no env-sensitive side effects. */
    private val DAEMONABLE = setOf(
        "find-config-properties", "find-implementations", "find-by-annotation",
        "get-class", "get-bean-definitions", "explain-conditional", "find-string",
        "list-extension-points", "find-class", "find-method", "list-packages",
        "search-symbol", "open-symbol", "why-dependency", "dep", "artifact",
        "class", "method", "status",
    )

    /** Injectable daemon runtime used by the loopback integration tests. */
    internal data class RuntimeConfig(
        val directory: Path,
        val buildIdentity: String,
        val idleLimitMs: Long = DEFAULT_IDLE_LIMIT_MS,
        val acceptTimeoutMs: Int = 30_000,
        val connectTimeoutMs: Int = 300,
        val handshakeTimeoutMs: Int = 1_000,
        val requestReadTimeoutMs: Int = 10_000,
        val responseWriteTimeoutMs: Int = 10_000,
        val startLeaseTimeoutMs: Long = DEFAULT_START_LEASE_TIMEOUT_MS,
        val connectionWorkers: Int = 16,
        val connectionQueueCapacity: Int = 64,
        val bufferedResponseSlots: Int = 2,
        val maxFrameBytes: Int = MAX_FRAME_BYTES,
        val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
        val refusalProbeDelaysMs: List<Long> = listOf(25, 50, 100),
        val environment: Map<String, String> = System.getenv(),
        val installShutdownHook: Boolean = true,
        val beforeEndpointPublish: (() -> Unit)? = null,
        val beforeRefusalProbe: ((Int) -> Unit)? = null,
    ) {
        init {
            require(isWireIdentifier(buildIdentity, 1, MAX_BUILD_LINE_BYTES)) {
                "invalid daemon build identity"
            }
            require(idleLimitMs >= 0) { "idle limit must not be negative" }
            require(acceptTimeoutMs > 0) { "accept timeout must be positive" }
            require(connectTimeoutMs > 0) { "connect timeout must be positive" }
            require(handshakeTimeoutMs > 0) { "handshake timeout must be positive" }
            require(requestReadTimeoutMs > 0) { "request read timeout must be positive" }
            require(responseWriteTimeoutMs > 0) { "response write timeout must be positive" }
            require(startLeaseTimeoutMs > 0) { "start lease timeout must be positive" }
            require(connectionWorkers > 0) { "connection workers must be positive" }
            require(connectionQueueCapacity > 0) { "connection queue capacity must be positive" }
            require(bufferedResponseSlots > 0) { "buffered response slots must be positive" }
            require(maxFrameBytes > 0) { "maximum frame size must be positive" }
            require(maxResponseBytes >= maxFrameBytes) {
                "maximum response size must cover one frame"
            }
            require(maxResponseBytes < Int.MAX_VALUE) {
                "maximum response size must leave room for an overflow sentinel"
            }
            require(refusalProbeDelaysMs.isNotEmpty() && refusalProbeDelaysMs.all { it >= 0 }) {
                "refusal probes require non-negative backoff delays"
            }
        }

        // Discovery is protocol-keyed, not build-keyed: a new client must find
        // the old daemon, authenticate with its token, and retire it through
        // the build handshake instead of leaving one heap per local rebuild.
        val endpointFile: Path get() = directory.resolve("$VERSION.endpoint")
        val endpointLockFile: Path get() = directory.resolve("$VERSION.endpoint.lock")
        val startingFile: Path get() = directory.resolve("$VERSION.starting")
        val startingLockFile: Path get() = directory.resolve("$VERSION.starting.lock")
    }

    internal data class StartLease(val token: String)

    private data class EndpointSnapshot(val raw: String, val endpoint: Endpoint?)

    private data class Endpoint(
        val generation: String,
        val port: Int,
        val token: String,
        val buildIdentity: String,
    )

    private data class ServerChallenge(val serverNonce: String)

    private class ProtocolLimitException : Exception()

    private class LimitedLineReader(
        private val input: DataInputStream,
        private val totalLimit: Int,
        private val deadlineNanos: Long? = null,
    ) {
        private var totalBytes = 0

        fun readLine(lineLimit: Int): String? {
            val bytes = ByteArray(lineLimit)
            var length = 0
            while (true) {
                checkDeadline()
                val byte = input.read()
                checkDeadline()
                if (byte < 0) {
                    return if (length == 0) null else String(bytes, 0, length, StandardCharsets.US_ASCII)
                }
                totalBytes++
                if (totalBytes > totalLimit) throw ProtocolLimitException()
                if (byte == '\n'.code) {
                    return String(bytes, 0, length, StandardCharsets.US_ASCII)
                }
                if (length >= lineLimit) throw ProtocolLimitException()
                bytes[length++] = byte.toByte()
            }
        }

        private fun checkDeadline() {
            val deadline = deadlineNanos ?: return
            if (System.nanoTime() - deadline >= 0) {
                throw SocketTimeoutException("protocol deadline exceeded")
            }
        }
    }

    /** Shared stdout/stderr capture budget with one overflow sentinel byte. */
    private class SharedResponseBudget(private val maxBytes: Int) {
        private val captureLimit = maxBytes.toLong() + 1L
        private var capturedBytes = 0L
        private var sealed = false

        @Volatile
        var overflowed: Boolean = false
            private set

        @Synchronized
        fun captureByte(target: BoundedCaptureOutputStream, value: Int) {
            if (sealed) return
            if (capturedBytes < maxBytes) {
                target.appendByte(value)
            }
            if (capturedBytes < captureLimit) {
                capturedBytes++
            }
            if (capturedBytes > maxBytes || capturedBytes >= captureLimit) overflowed = true
        }

        @Synchronized
        fun capture(
            target: BoundedCaptureOutputStream,
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            if (sealed || length == 0) return
            val observable = minOf(
                length.toLong(),
                maxOf(0L, captureLimit - capturedBytes),
            ).toInt()
            val storable = minOf(
                observable.toLong(),
                maxOf(0L, maxBytes.toLong() - capturedBytes),
            ).toInt()
            if (storable > 0) {
                target.append(bytes, offset, storable)
            }
            capturedBytes += observable.toLong()
            if (observable < length || capturedBytes > maxBytes) overflowed = true
        }

        @Synchronized
        fun seal(): Boolean {
            sealed = true
            return overflowed
        }
    }

    private class BoundedCaptureOutputStream(
        private val budget: SharedResponseBudget,
    ) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            budget.captureByte(this, value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) {
                throw IndexOutOfBoundsException()
            }
            budget.capture(this, bytes, offset, length)
        }

        fun appendByte(value: Int) {
            super.write(value)
        }

        fun append(bytes: ByteArray, offset: Int, length: Int) {
            super.write(bytes, offset, length)
        }

        fun writeFrames(out: DataOutputStream, kind: String, maxFrameBytes: Int) {
            var offset = 0
            while (offset < count) {
                val length = minOf(maxFrameBytes, count - offset)
                out.write("$kind $length\n".toByteArray(StandardCharsets.US_ASCII))
                out.write(buf, offset, length)
                offset += length
            }
        }
    }

    private fun daemonDir(): Path =
        dev.research4jar.indexer.Research4JarPaths.resolve(null).home.resolve("daemon")

    private fun defaultConfig(): RuntimeConfig = RuntimeConfig(daemonDir(), BUILD_ID)

    // ---------------------------------------------------------------- client

    /**
     * Serves [argv] through a running daemon. Returns the exit code, or null
     * when the daemon path does not apply (not a daemonable command, env
     * overrides present, no live matching daemon). Malformed endpoints,
     * connection refusals, and explicit build/protocol mismatches are removed
     * if the generation is unchanged. Timeouts and EOF leave a potentially
     * busy healthy daemon discoverable.
     */
    fun tryServe(argv: Array<String>): Int? = try {
        // Cheap pre-checks before defaultConfig(): building a RuntimeConfig
        // forces BUILD_ID, which walks every fat-jar zip entry (~40ms) — a
        // cost only actual daemon traffic may pay, never version/NO_DAEMON
        // or non-daemonable commands.
        if (!daemonCommandIsSafe(argv) || System.getenv("RESEARCH4JAR_NO_DAEMON") != null) {
            null
        } else {
            tryServe(
                argv,
                WorkingDirectoryContext.current(),
                System.out,
                System.err,
                defaultConfig(),
            )
        }
    } catch (_: Exception) {
        null
    }

    internal fun tryServe(
        argv: Array<String>,
        cwd: Path,
        stdout: OutputStream,
        stderr: OutputStream,
        config: RuntimeConfig,
    ): Int? {
        if (!daemonApplies(argv, config)) return null
        return try {
            serveThroughDaemon(argv, cwd, stdout, stderr, config)
        } catch (_: Exception) {
            // Preserve the daemon's never-break-the-cold-path rule. Only the
            // classified cases inside serveThroughDaemon invalidate discovery.
            null
        }
    }

    internal fun daemonApplies(argv: Array<String>, config: RuntimeConfig): Boolean {
        if (!daemonCommandIsSafe(argv)) return false
        if (config.environment["RESEARCH4JAR_NO_DAEMON"] != null) return false
        // Every other setting is either irrelevant to daemonable read-only
        // queries or requestized. RESEARCH4JAR_HOME is safe too: it selects the
        // endpoint directory itself, and the spawned child inherits it, so
        // different homes naturally discover different daemons. In particular,
        // the e2e harness's RESEARCH4JAR_INDEX/QUERY must not disable this path.
        return true
    }

    private fun daemonCommandIsSafe(argv: Array<String>): Boolean {
        if (argv.isEmpty() || argv[0] !in DAEMONABLE) return false
        // This status mode launches the project's build tool and must observe
        // this invocation's PATH/JAVA_HOME/GRADLE_USER_HOME/MAVEN_OPTS rather
        // than the daemon process's startup environment.
        if (argv[0] == "status" && argv.drop(1).contains("--check-classpath")) return false
        // A 256 MiB warm host must not materialize the largest legal 1000-row
        // models. Missing/non-numeric values also stay cold so the ordinary CLI
        // parser remains the sole source of their user-facing error messages.
        for (index in 1 until argv.size) {
            if (argv[index] != "--page-size") continue
            val pageSize = argv.getOrNull(index + 1)?.toIntOrNull() ?: return false
            if (pageSize > MAX_DAEMON_PAGE_SIZE) return false
        }
        return true
    }

    private fun serveThroughDaemon(
        argv: Array<String>,
        cwd: Path,
        stdout: OutputStream,
        stderr: OutputStream,
        config: RuntimeConfig,
    ): Int? {
        val snapshot = readEndpoint(config) ?: return null
        val endpoint = snapshot.endpoint
        if (endpoint == null) {
            removeEndpointIfUnchanged(config, snapshot)
            return null
        }
        val request = encodeRequest(argv, cwd, config.buildIdentity) ?: return null
        var connected = false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port), config.connectTimeoutMs)
                connected = true
                socket.soTimeout = config.handshakeTimeoutMs
                val handshakeDeadlineNanos = System.nanoTime() +
                    TimeUnit.MILLISECONDS.toNanos(config.handshakeTimeoutMs.toLong())
                val out = DataOutputStream(socket.getOutputStream().buffered())
                val clientNonce = randomToken()
                out.write("$MAGIC\n$clientNonce\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()

                val input = DataInputStream(socket.getInputStream().buffered())
                val challenge = readServerChallenge(
                    input,
                    endpoint,
                    clientNonce,
                    handshakeDeadlineNanos,
                ) ?: return null
                val clientMac = encodeMac(
                    clientAuthenticationMac(
                        endpoint.token,
                        endpoint.generation,
                        endpoint.buildIdentity,
                        clientNonce,
                        challenge.serverNonce,
                        request,
                    ),
                )
                out.write("$CLIENT_PROOF $clientMac\n".toByteArray(StandardCharsets.US_ASCII))
                out.write(request)
                out.flush()
                readDaemonResponse(
                    socket,
                    input,
                    stdout,
                    stderr,
                    config,
                    snapshot,
                    handshakeDeadlineNanos,
                )
            }
        } catch (_: ConnectException) {
            // A single ECONNREFUSED can be transient under accept-backlog or
            // file-descriptor pressure. Remove discovery only after the same
            // generation refuses every short-backoff liveness probe.
            if (!connected && repeatedlyRefused(config, snapshot, endpoint)) {
                removeEndpointIfUnchanged(config, snapshot)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun repeatedlyRefused(
        config: RuntimeConfig,
        snapshot: EndpointSnapshot,
        endpoint: Endpoint,
    ): Boolean {
        for ((index, delayMs) in config.refusalProbeDelaysMs.withIndex()) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            if (readEndpoint(config)?.raw != snapshot.raw) return false
            config.beforeRefusalProbe?.invoke(index)
            try {
                Socket().use { probe ->
                    probe.connect(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port),
                        config.connectTimeoutMs,
                    )
                }
                return false
            } catch (_: ConnectException) {
                // Keep probing the unchanged generation.
            } catch (_: Exception) {
                // Timeout/resource pressure is not proof that the listener died.
                return false
            }
        }
        return readEndpoint(config)?.raw == snapshot.raw
    }

    private fun encodeRequest(
        argv: Array<String>,
        cwd: Path,
        buildIdentity: String,
    ): ByteArray? {
        if (argv.size > 1024) return null
        val encoder = Base64.getEncoder()
        val lines = ArrayList<String>(argv.size + 3)
        lines += buildIdentity
        lines += encoder.encodeToString(
            WorkingDirectoryContext.resolve(cwd).toString().toByteArray(StandardCharsets.UTF_8),
        )
        lines += argv.size.toString()
        for (argument in argv) {
            lines += encoder.encodeToString(argument.toByteArray(StandardCharsets.UTF_8))
        }
        val limits = intArrayOf(
            MAX_BUILD_LINE_BYTES,
            MAX_CWD_LINE_BYTES,
            MAX_COUNT_LINE_BYTES,
        )
        // Reserve the maximum authentication prelude so the server's single
        // MAX_REQUEST_BYTES reader bound covers both handshake and payload.
        var total = MAGIC.length + 1 + MAX_NONCE_LINE_BYTES + 1 +
            CLIENT_PROOF.length + 1 + MAX_MAC_LINE_BYTES + 1
        for ((index, line) in lines.withIndex()) {
            val limit = if (index < limits.size) limits[index] else MAX_ARG_LINE_BYTES
            if (line.length > limit) return null
            total += line.length + 1
            if (total > MAX_REQUEST_BYTES) return null
        }
        return lines.joinToString(separator = "\n", postfix = "\n")
            .toByteArray(StandardCharsets.US_ASCII)
    }

    private fun readServerChallenge(
        input: DataInputStream,
        endpoint: Endpoint,
        clientNonce: String,
        deadlineNanos: Long,
    ): ServerChallenge? {
        val parts = readLine(input, MAX_RESPONSE_HEADER_BYTES, deadlineNanos)?.split(" ") ?: return null
        if (parts.size != 3 || parts[0] != SERVER_PROOF) return null
        val serverNonce = parts[1]
        if (!isWireIdentifier(serverNonce, 20, MAX_NONCE_LINE_BYTES)) return null
        val receivedMac = decodeMac(parts[2]) ?: return null
        val expectedMac = serverAuthenticationMac(
            endpoint.token,
            endpoint.generation,
            endpoint.buildIdentity,
            clientNonce,
            serverNonce,
        )
        if (!MessageDigest.isEqual(expectedMac, receivedMac)) return null
        return ServerChallenge(serverNonce)
    }

    private fun serverAuthenticationMac(
        token: String,
        generation: String,
        serverBuild: String,
        clientNonce: String,
        serverNonce: String,
    ): ByteArray = authenticationMac(
        token,
        SERVER_AUTH_DOMAIN,
        generation.toByteArray(StandardCharsets.US_ASCII),
        serverBuild.toByteArray(StandardCharsets.US_ASCII),
        clientNonce.toByteArray(StandardCharsets.US_ASCII),
        serverNonce.toByteArray(StandardCharsets.US_ASCII),
    )

    private fun clientAuthenticationMac(
        token: String,
        generation: String,
        serverBuild: String,
        clientNonce: String,
        serverNonce: String,
        request: ByteArray,
    ): ByteArray = authenticationMac(
        token,
        CLIENT_AUTH_DOMAIN,
        generation.toByteArray(StandardCharsets.US_ASCII),
        serverBuild.toByteArray(StandardCharsets.US_ASCII),
        clientNonce.toByteArray(StandardCharsets.US_ASCII),
        serverNonce.toByteArray(StandardCharsets.US_ASCII),
        request,
    )

    private fun authenticationMac(
        token: String,
        domain: String,
        vararg fields: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(token.toByteArray(StandardCharsets.US_ASCII), HMAC_ALGORITHM))
        mac.updateField(domain.toByteArray(StandardCharsets.US_ASCII))
        fields.forEach { field -> mac.updateField(field) }
        return mac.doFinal()
    }

    private fun Mac.updateField(bytes: ByteArray) {
        update((bytes.size ushr 24).toByte())
        update((bytes.size ushr 16).toByte())
        update((bytes.size ushr 8).toByte())
        update(bytes.size.toByte())
        update(bytes)
    }

    private fun encodeMac(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decodeMac(value: String): ByteArray? {
        if (!isWireIdentifier(value, 43, MAX_MAC_LINE_BYTES)) return null
        return try {
            Base64.getUrlDecoder().decode(value).takeIf { it.size == 32 }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readDaemonResponse(
        socket: Socket,
        input: DataInputStream,
        stdout: OutputStream,
        stderr: OutputStream,
        config: RuntimeConfig,
        snapshot: EndpointSnapshot,
        handshakeDeadlineNanos: Long,
    ): Int? {
        when (val handshake = readLine(input, MAX_RESPONSE_HEADER_BYTES, handshakeDeadlineNanos)) {
            "A ${config.buildIdentity}" -> Unit
            null -> return null
            else -> {
                val serverBuild = snapshot.endpoint?.buildIdentity
                if (
                    handshake == PROTOCOL_MISMATCH ||
                    (serverBuild != null && handshake == "$BUILD_MISMATCH $serverBuild")
                ) {
                    removeEndpointIfUnchanged(config, snapshot)
                }
                return null
            }
        }
        socket.soTimeout = 0
        val pendingStdout = ByteArrayOutputStream()
        val pendingStderr = ByteArrayOutputStream()
        var responseBytes = 0L
        while (true) {
            val header = readLine(input, MAX_RESPONSE_HEADER_BYTES) ?: return null
            val parts = header.split(" ", limit = 2)
            when (parts[0]) {
                "O", "E" -> {
                    val length = parts.getOrNull(1)?.toIntOrNull()
                    if (
                        length == null || length < 0 || length > config.maxFrameBytes ||
                        responseBytes > config.maxResponseBytes.toLong() - length.toLong()
                    ) {
                        return null
                    }
                    responseBytes += length.toLong()
                    readFramePayload(
                        input,
                        length,
                        if (parts[0] == "O") pendingStdout else pendingStderr,
                    )
                }

                "X" -> {
                    val code = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    // Commit only after a valid terminal frame. If the daemon
                    // disconnects or emits a bad/oversized frame, callers get
                    // a clean cold fallback with no duplicated partial output.
                    stdout.write(pendingStdout.toByteArray())
                    stderr.write(pendingStderr.toByteArray())
                    stdout.flush()
                    stderr.flush()
                    return code
                }

                else -> return null
            }
        }
    }

    private fun readFramePayload(
        input: DataInputStream,
        length: Int,
        destination: ByteArrayOutputStream,
    ) {
        val buffer = ByteArray(minOf(8_192, maxOf(1, length)))
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(buffer.size, remaining)
            input.readFully(buffer, 0, chunk)
            destination.write(buffer, 0, chunk)
            remaining -= chunk
        }
    }

    private fun readLine(
        input: DataInputStream,
        maxBytes: Int,
        deadlineNanos: Long? = null,
    ): String? = LimitedLineReader(input, maxBytes + 1, deadlineNanos).readLine(maxBytes)

    /**
     * Best-effort background daemon spawn after a cold run of a daemonable
     * command, so the NEXT invocation hits the warm path.
     */
    fun spawnAfterColdRun(argv: Array<String>) {
        try {
            spawnAfterColdRunUnchecked(argv)
        } catch (_: Exception) {
            // Spawning is an optimization and must never change command success.
        }
    }

    private fun spawnAfterColdRunUnchecked(argv: Array<String>) {
        if (!daemonCommandIsSafe(argv) || System.getenv("RESEARCH4JAR_NO_DAEMON") != null) return
        val config = defaultConfig()
        if (!daemonApplies(argv, config)) return
        if (hasValidEndpoint(config)) return
        val lease = acquireStartLease(config) ?: return
        var started = false
        try {
            val jar = Paths.get(
                Daemon::class.java.protectionDomain.codeSource.location.toURI(),
            )
            if (!Files.isRegularFile(jar) || !jar.toString().endsWith(".jar")) return
            val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
            val log = config.directory.resolve("daemon.log").toFile()
            Files.createDirectories(config.directory)
            ProcessBuilder(
                javaBin,
                "-Xmx256m",
                "-D$START_LEASE_PROPERTY=${lease.token}",
                "-jar",
                jar.toString(),
                "daemon",
            )
                .redirectOutput(log)
                .redirectError(log)
                .start()
            started = true
        } catch (_: Exception) {
            // The cold path stays fully functional without a daemon.
        } finally {
            // A successfully launched server releases the lease immediately
            // after publishing its endpoint. Failed launches release here;
            // crashes between launch and publish self-heal after the timeout.
            if (!started) releaseStartLease(config, lease.token)
        }
    }

    private fun hasValidEndpoint(config: RuntimeConfig): Boolean {
        val snapshot = readEndpoint(config) ?: return false
        if (snapshot.endpoint != null) return true
        removeEndpointIfUnchanged(config, snapshot)
        return false
    }

    /** Atomic cross-process lease preventing duplicate cold-start daemons. */
    internal fun acquireStartLease(
        config: RuntimeConfig,
        nowMs: Long = System.currentTimeMillis(),
    ): StartLease? = withExclusiveFileLock(config.startingLockFile) {
        Files.createDirectories(config.directory)
        if (Files.exists(config.startingFile) && !startLeaseIsStale(config, nowMs)) {
            return@withExclusiveFileLock null
        }
        if (Files.exists(config.startingFile)) {
            try {
                Files.delete(config.startingFile)
            } catch (_: Exception) {
                return@withExclusiveFileLock null
            }
        }
        val lease = StartLease(randomToken())
        try {
            Files.write(
                config.startingFile,
                lease.token.toByteArray(StandardCharsets.US_ASCII),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            setPrivatePermissions(config.startingFile)
            lease
        } catch (_: Exception) {
            try {
                Files.deleteIfExists(config.startingFile)
            } catch (_: Exception) {
                // The lock prevents a competing owner until this block ends.
            }
            null
        }
    }

    private fun startLeaseIsStale(config: RuntimeConfig, nowMs: Long): Boolean =
        try {
            nowMs - Files.getLastModifiedTime(config.startingFile).toMillis() >=
                config.startLeaseTimeoutMs
        } catch (_: Exception) {
            false
        }

    internal fun releaseStartLease(config: RuntimeConfig, token: String) {
        withExclusiveFileLock(config.startingLockFile) {
            if (readAscii(config.startingFile) != token) return@withExclusiveFileLock
            try {
                Files.deleteIfExists(config.startingFile)
            } catch (_: Exception) {
                // The bounded lease timeout remains the final recovery path.
            }
        }
    }

    // ---------------------------------------------------------------- server

    /** Runs the daemon loop until idle timeout. Invoked as `research4jar daemon`. */
    fun runServer(runCommand: (Array<String>, PrintStream, PrintStream) -> Int): Int =
        runServer(
            runCommand,
            defaultConfig(),
            System.getProperty(START_LEASE_PROPERTY),
        )

    internal fun runServer(
        runCommand: (Array<String>, PrintStream, PrintStream) -> Int,
        config: RuntimeConfig,
        startLeaseToken: String? = null,
    ): Int = try {
        runServerLoop(runCommand, config, startLeaseToken)
    } finally {
        if (startLeaseToken != null) releaseStartLease(config, startLeaseToken)
    }

    private fun runServerLoop(
        runCommand: (Array<String>, PrintStream, PrintStream) -> Int,
        config: RuntimeConfig,
        startLeaseToken: String?,
    ): Int {
        Files.createDirectories(config.directory)
        val token = randomToken()
        val generation = randomToken()
        var shutdownHook: Thread? = null
        val lastActivity = AtomicLong(System.currentTimeMillis())
        val activeConnections = AtomicInteger()
        val stopping = AtomicBoolean()
        val connections = ConcurrentHashMap.newKeySet<Socket>()
        val requestLock = Any()
        val responseSlots = Semaphore(config.bufferedResponseSlots, true)
        val deadlines = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "r4j-daemon-deadlines").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
        val workerSequence = AtomicInteger()
        val workers = ThreadPoolExecutor(
            config.connectionWorkers,
            config.connectionWorkers,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(config.connectionQueueCapacity),
            { runnable ->
                Thread(runnable, "r4j-daemon-connection-${workerSequence.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        )
        ServerSocket(0, 16, InetAddress.getLoopbackAddress()).use { server ->
            val endpoint = Endpoint(
                generation = generation,
                port = server.localPort,
                token = token,
                buildIdentity = config.buildIdentity,
            )
            val ownedEndpoint = EndpointSnapshot(formatEndpoint(endpoint), endpoint)
            try {
                config.beforeEndpointPublish?.invoke()
                if (!publishEndpointIfLeaseOwned(config, ownedEndpoint, startLeaseToken)) return 0
                if (config.installShutdownHook) {
                    val hook = Thread {
                        removeEndpointIfUnchanged(config, ownedEndpoint)
                    }
                    shutdownHook = hook
                    Runtime.getRuntime().addShutdownHook(hook)
                }
                server.soTimeout = config.acceptTimeoutMs
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        if (
                            activeConnections.get() == 0 &&
                            System.currentTimeMillis() - lastActivity.get() >= config.idleLimitMs
                        ) {
                            return 0
                        }
                        continue
                    } catch (exception: SocketException) {
                        if (stopping.get() || server.isClosed) return 0
                        throw exception
                    }
                    lastActivity.set(System.currentTimeMillis())
                    activeConnections.incrementAndGet()
                    connections += socket
                    // SO_TIMEOUT is a timeout between reads, not a total
                    // request deadline. Closing from one shared scheduler
                    // bounds even a slowloris that drips bytes forever, and
                    // starts the clock while a connection is still queued.
                    val requestOpen = AtomicBoolean(true)
                    val requestDeadlineNanos = System.nanoTime() +
                        TimeUnit.MILLISECONDS.toNanos(config.requestReadTimeoutMs.toLong())
                    val requestDeadline = deadlines.schedule(
                        {
                            if (requestOpen.compareAndSet(true, false)) closeQuietly(socket)
                        },
                        config.requestReadTimeoutMs.toLong(),
                        TimeUnit.MILLISECONDS,
                    )
                    try {
                        workers.execute {
                            try {
                                val outcome = socket.use {
                                    handle(
                                        it,
                                        token,
                                        generation,
                                        config,
                                        requestLock,
                                        responseSlots,
                                        deadlines,
                                        requestDeadline,
                                        requestOpen,
                                        requestDeadlineNanos,
                                        runCommand,
                                    )
                                }
                                if (outcome == HandleOutcome.STOP && stopping.compareAndSet(false, true)) {
                                    try {
                                        server.close()
                                    } catch (_: Exception) {
                                        // The accept loop may already be closing.
                                    }
                                }
                            } catch (_: Exception) {
                                // One bad connection must not kill the daemon.
                            } finally {
                                requestOpen.set(false)
                                requestDeadline.cancel(false)
                                connections -= socket
                                activeConnections.decrementAndGet()
                                lastActivity.set(System.currentTimeMillis())
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        requestOpen.set(false)
                        requestDeadline.cancel(false)
                        connections -= socket
                        activeConnections.decrementAndGet()
                        try {
                            socket.close()
                        } catch (_: Exception) {
                            // A saturated pool rejects only this connection.
                        }
                        lastActivity.set(System.currentTimeMillis())
                    }
                }
            } finally {
                stopping.set(true)
                for (connection in connections) {
                    try {
                        connection.close()
                    } catch (_: Exception) {
                        // Closing the listener owns best-effort connection teardown.
                    }
                }
                workers.shutdownNow()
                deadlines.shutdownNow()
                removeEndpointIfUnchanged(config, ownedEndpoint)
                shutdownHook?.let {
                    try {
                        Runtime.getRuntime().removeShutdownHook(it)
                    } catch (_: IllegalStateException) {
                        // JVM shutdown is already in progress; the hook owns cleanup.
                    }
                }
            }
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
            // Deadline/shutdown closes are best effort and idempotent.
        }
    }

    private enum class HandleOutcome { CONTINUE, STOP }

    private fun handle(
        socket: Socket,
        expectedToken: String,
        generation: String,
        config: RuntimeConfig,
        requestLock: Any,
        responseSlots: Semaphore,
        deadlines: ScheduledThreadPoolExecutor,
        requestDeadline: ScheduledFuture<*>,
        requestOpen: AtomicBoolean,
        requestDeadlineNanos: Long,
        runCommand: (Array<String>, PrintStream, PrintStream) -> Int,
    ): HandleOutcome {
        socket.soTimeout = config.requestReadTimeoutMs
        val input = DataInputStream(socket.getInputStream().buffered())
        val out = DataOutputStream(socket.getOutputStream().buffered())
        val reader = LimitedLineReader(input, MAX_REQUEST_BYTES, requestDeadlineNanos)
        val clientNonce: String
        try {
            val requestMagic = reader.readLine(MAX_MAGIC_LINE_BYTES)
                ?: return HandleOutcome.CONTINUE
            if (requestMagic != MAGIC) {
                writeHandshake(out, PROTOCOL_MISMATCH)
                return HandleOutcome.CONTINUE
            }
            clientNonce = reader.readLine(MAX_NONCE_LINE_BYTES) ?: return HandleOutcome.CONTINUE
            if (!isWireIdentifier(clientNonce, 20, MAX_NONCE_LINE_BYTES)) {
                writeHandshake(out, PROTOCOL_MISMATCH)
                return HandleOutcome.CONTINUE
            }
        } catch (_: ProtocolLimitException) {
            writeHandshake(out, PROTOCOL_MISMATCH)
            return HandleOutcome.CONTINUE
        }

        // Prove possession before the client sends its build, cwd, argv, or any
        // token-derived value. A process that merely wins a stale loopback port
        // therefore cannot harvest the endpoint secret and impersonate output.
        val serverNonce = randomToken()
        val serverMac = encodeMac(
            serverAuthenticationMac(
                expectedToken,
                generation,
                config.buildIdentity,
                clientNonce,
                serverNonce,
            ),
        )
        writeHandshake(out, "$SERVER_PROOF $serverNonce $serverMac")

        val receivedClientMac: ByteArray
        val clientBuild: String
        val cwd: Path
        val argv: Array<String>
        val requestLines = ArrayList<String>()
        try {
            val proofParts = reader.readLine(MAX_RESPONSE_HEADER_BYTES)?.split(" ")
                ?: return HandleOutcome.CONTINUE
            if (proofParts.size != 2 || proofParts[0] != CLIENT_PROOF) {
                writeHandshake(out, PROTOCOL_MISMATCH)
                return HandleOutcome.CONTINUE
            }
            receivedClientMac = decodeMac(proofParts[1]) ?: return HandleOutcome.CONTINUE
            val decoder = Base64.getDecoder()
            clientBuild = reader.readLine(MAX_BUILD_LINE_BYTES) ?: return HandleOutcome.CONTINUE
            requestLines += clientBuild
            val encodedCwd = reader.readLine(MAX_CWD_LINE_BYTES) ?: return HandleOutcome.CONTINUE
            requestLines += encodedCwd
            cwd = Paths.get(
                String(decoder.decode(encodedCwd), StandardCharsets.UTF_8),
            ).normalize()
            val argcText = reader.readLine(MAX_COUNT_LINE_BYTES) ?: return HandleOutcome.CONTINUE
            requestLines += argcText
            val argc = argcText.toIntOrNull()
            if (!cwd.isAbsolute || argc == null || argc < 0 || argc > 1024) {
                writeHandshake(out, PROTOCOL_MISMATCH)
                return HandleOutcome.CONTINUE
            }
            argv = Array(argc) {
                val encoded = reader.readLine(MAX_ARG_LINE_BYTES)
                    ?: return HandleOutcome.CONTINUE
                requestLines += encoded
                String(decoder.decode(encoded), StandardCharsets.UTF_8)
            }
        } catch (_: ProtocolLimitException) {
            writeHandshake(out, PROTOCOL_MISMATCH)
            return HandleOutcome.CONTINUE
        } catch (_: IllegalArgumentException) {
            writeHandshake(out, PROTOCOL_MISMATCH)
            return HandleOutcome.CONTINUE
        }
        val request = requestLines.joinToString(separator = "\n", postfix = "\n")
            .toByteArray(StandardCharsets.US_ASCII)
        val expectedClientMac = clientAuthenticationMac(
            expectedToken,
            generation,
            config.buildIdentity,
            clientNonce,
            serverNonce,
            request,
        )
        if (!MessageDigest.isEqual(expectedClientMac, receivedClientMac)) {
            return HandleOutcome.CONTINUE
        }
        // cancel(false) alone races a deadline task that has begun running.
        // The CAS gives parsing and expiry one winner; the monotonic timestamp
        // also rejects a request when the single scheduler happens to run late.
        if (!requestOpen.compareAndSet(true, false)) return HandleOutcome.CONTINUE
        requestDeadline.cancel(false)
        if (System.nanoTime() - requestDeadlineNanos >= 0 || socket.isClosed) {
            return HandleOutcome.CONTINUE
        }
        // Only a mutually authenticated client may retire an old build.
        if (clientBuild != config.buildIdentity) {
            writeHandshake(out, "$BUILD_MISMATCH ${config.buildIdentity}")
            return HandleOutcome.STOP
        }
        // The thin client filters this too, but the authenticated server is
        // the actual trust boundary. A hand-crafted local protocol request
        // must not turn the warm read-only host into index/gc/registry/mcp or
        // run an environment-sensitive classpath probe.
        if (!daemonCommandIsSafe(argv)) {
            writeHandshake(out, COMMAND_REJECTED)
            return HandleOutcome.CONTINUE
        }
        // Bound aggregate retained response memory, not merely each client.
        // A paused reader keeps its permit until the write deadline closes it;
        // excess clients fall back cold without ever running the command.
        if (!responseSlots.tryAcquire()) return HandleOutcome.CONTINUE

        try {
            // ACK before this connection joins the serialized
            // command queue. A busy command therefore cannot make another healthy
            // client time out its handshake and invalidate the endpoint.
            writeHandshake(out, "A ${config.buildIdentity}")

            val responseBudget = SharedResponseBudget(config.maxResponseBytes)
            val stdout = BoundedCaptureOutputStream(responseBudget)
            val stderr = BoundedCaptureOutputStream(responseBudget)
            // Query execution stays serialized, while path resolution uses an
            // explicit request context instead of the ineffective user.dir hack.
            val code = synchronized(requestLock) {
                try {
                    WorkingDirectoryContext.withDirectory(cwd) {
                        runCommand(
                            argv,
                            PrintStream(stdout, true, "UTF-8"),
                            PrintStream(stderr, true, "UTF-8"),
                        )
                    }
                } catch (exception: Exception) {
                    PrintStream(stderr, true, "UTF-8")
                        .println("research4jar daemon: ${exception.message}")
                    1
                }
            }

            // PrintStream deliberately swallows IOExceptions, so overflow is an
            // explicit shared flag rather than an exception. No toByteArray copy is
            // attempted on this path; closing after ACK makes the client discard
            // its private frame buffers and run the cold fallback atomically.
            if (responseBudget.seal()) return HandleOutcome.CONTINUE

            val responseDeadline = deadlines.schedule(
                { closeQuietly(socket) },
                config.responseWriteTimeoutMs.toLong(),
                TimeUnit.MILLISECONDS,
            )
            try {
                stdout.writeFrames(out, "O", config.maxFrameBytes)
                stderr.writeFrames(out, "E", config.maxFrameBytes)
                out.write("X $code\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()
            } finally {
                responseDeadline.cancel(false)
            }
            return HandleOutcome.CONTINUE
        } finally {
            responseSlots.release()
        }
    }

    private fun writeHandshake(out: DataOutputStream, line: String) {
        out.write("$line\n".toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    private fun formatEndpoint(endpoint: Endpoint): String = buildString {
        append(ENDPOINT_MAGIC).append('\n')
        append(endpoint.generation).append('\n')
        append(endpoint.port).append('\n')
        append(endpoint.token).append('\n')
        append(endpoint.buildIdentity).append('\n')
    }

    private fun parseEndpoint(raw: String): Endpoint? {
        val lines = raw.split('\n')
        if (lines.size != 6 || lines.last().isNotEmpty() || lines[0] != ENDPOINT_MAGIC) return null
        val generation = lines[1]
        val port = lines[2].toIntOrNull() ?: return null
        val token = lines[3]
        val buildIdentity = lines[4]
        if (port !in 1..65535) return null
        if (!isWireIdentifier(generation, 20, 128)) return null
        if (!isWireIdentifier(token, 20, 128)) return null
        if (!isWireIdentifier(buildIdentity, 1, MAX_BUILD_LINE_BYTES)) return null
        return Endpoint(generation, port, token, buildIdentity)
    }

    private fun isWireIdentifier(value: String, minLength: Int, maxLength: Int): Boolean =
        value.length in minLength..maxLength && value.all { character ->
            character in 'a'..'z' || character in 'A'..'Z' ||
                character in '0'..'9' || character == '_' || character == '-'
        }

    private fun readEndpoint(config: RuntimeConfig): EndpointSnapshot? =
        withExclusiveFileLock(config.endpointLockFile) {
            readEndpointUnlocked(config)
        }

    private fun readEndpointUnlocked(config: RuntimeConfig): EndpointSnapshot? {
        if (!Files.isRegularFile(config.endpointFile)) return null
        if (Files.size(config.endpointFile) > 1_024) {
            Files.deleteIfExists(config.endpointFile)
            return null
        }
        val raw = String(Files.readAllBytes(config.endpointFile), StandardCharsets.US_ASCII)
        return EndpointSnapshot(raw, parseEndpoint(raw))
    }

    private fun readAscii(path: Path): String? = try {
        String(Files.readAllBytes(path), StandardCharsets.US_ASCII).trim()
    } catch (_: Exception) {
        null
    }

    /**
     * Delete only the exact endpoint observed. Publish and conditional removal
     * share an OS file lock, closing the compare/delete window in which an old
     * daemon could otherwise remove a replacement endpoint.
     */
    private fun removeEndpointIfUnchanged(config: RuntimeConfig, expected: EndpointSnapshot) {
        withExclusiveFileLock(config.endpointLockFile) {
            val current = try {
                readEndpointUnlocked(config)
            } catch (_: Exception) {
                null
            }
            if (current?.raw != expected.raw) return@withExclusiveFileLock
            try {
                Files.deleteIfExists(config.endpointFile)
            } catch (_: Exception) {
                // A later refused connection can retry conditional cleanup.
            }
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun publishEndpointIfLeaseOwned(
        config: RuntimeConfig,
        snapshot: EndpointSnapshot,
        startLeaseToken: String?,
    ): Boolean {
        if (startLeaseToken == null) {
            withExclusiveFileLock(config.endpointLockFile) {
                writePrivateAtomic(config.endpointFile, snapshot.raw)
            }
            return true
        }
        // Fixed nested lock order: starting -> endpoint. Ownership validation,
        // endpoint publication, and lease deletion form one cross-process
        // critical section. A delayed starter that lost an expired lease can
        // therefore never clobber the takeover daemon's endpoint (ABA).
        return withExclusiveFileLock(config.startingLockFile) {
            if (readAscii(config.startingFile) != startLeaseToken) {
                return@withExclusiveFileLock false
            }
            withExclusiveFileLock(config.endpointLockFile) {
                writePrivateAtomic(config.endpointFile, snapshot.raw)
                Files.delete(config.startingFile)
            }
            true
        }
    }

    private fun writePrivateAtomic(path: Path, content: String) {
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.write(temporary, content.toByteArray(StandardCharsets.US_ASCII))
            setPrivatePermissions(temporary)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Readers take the same lock, so replacement remains indivisible
                // even on filesystems without ATOMIC_MOVE support.
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private val localFileLocks = ConcurrentHashMap<Path, Any>()

    private fun <T> withExclusiveFileLock(path: Path, action: () -> T): T {
        val normalized = path.toAbsolutePath().normalize()
        val monitor = localFileLocks.computeIfAbsent(normalized) { Any() }
        return synchronized(monitor) {
            Files.createDirectories(normalized.parent)
            FileChannel.open(
                normalized,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                try {
                    setPrivatePermissions(normalized)
                } catch (_: Exception) {
                    // Loopback auth still protects platforms without POSIX mode bits.
                }
                channel.lock().use { action() }
            }
        }
    }

    private fun setPrivatePermissions(path: Path) {
        try {
            Files.setPosixFilePermissions(
                path,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows: loopback bind + random token remain the auth boundary.
        }
    }

    /** Content-derived per-artifact identity without reading/decompressing the fat jar. */
    private fun currentBuildIdentity(): String {
        val version = Daemon::class.java.`package`?.implementationVersion ?: "dev"
        val location = try {
            Paths.get(Daemon::class.java.protectionDomain.codeSource.location.toURI())
                .toAbsolutePath().normalize()
        } catch (_: Exception) {
            Paths.get("unknown").toAbsolutePath().normalize()
        }
        return artifactBuildIdentity(location, version)
    }

    /**
     * The ZIP central directory carries every entry's name, size, method, and
     * content CRC. Folding it into the identity catches a same-version jar
     * replacement even when tooling preserves the outer file size and mtime,
     * while avoiding a 20+ MiB payload read on every thin-client invocation.
     */
    internal fun artifactBuildIdentity(location: Path, version: String): String {
        val size = try {
            Files.size(location)
        } catch (_: Exception) {
            -1L
        }
        val modified = try {
            Files.getLastModifiedTime(location).toMillis()
        } catch (_: Exception) {
            -1L
        }
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: Any?) {
            digest.update(value.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
        }
        update(version)
        update(location.toAbsolutePath().normalize())
        update(size)
        update(modified)
        if (Files.isRegularFile(location) && location.fileName.toString().endsWith(".jar")) {
            try {
                ZipFile(location.toFile()).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        update(entry.name)
                        update(entry.crc)
                        update(entry.size)
                        update(entry.compressedSize)
                        update(entry.method)
                    }
                }
            } catch (exception: Exception) {
                // Preserve a deterministic fallback for a partially replaced
                // artifact; the normal command can still run cold and repair.
                update(exception.javaClass.name)
            }
        }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(digest.digest())
            .take(22)
    }
}
