package dev.research4jar.cli

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * CLI daemon: a warm JVM serving query commands over TCP loopback so
 * one-shot CLI calls skip JVM+driver startup (~700ms → ~50-80ms measured).
 * Gradle-daemon/mvnd-proven lifecycle: version-keyed port/token files under
 * the daemon directory, 0600 token auth, 30-minute idle exit, cold-run
 * fallback always correct.
 *
 * Protocol (client → daemon), line-oriented with base64 payloads so argv
 * bytes survive untouched:
 *   R4JD1\n<token>\n<cwd-b64>\n<argc>\n<arg-b64>...\n
 * Daemon → client frames:
 *   O <len>\n<raw bytes>   stdout chunk
 *   E <len>\n<raw bytes>   stderr chunk
 *   X <code>\n             exit (terminal)
 *
 * The client half deliberately touches only java.io/java.net/java.util
 * classes — jackson/sqlite never load on the fast path.
 */
object Daemon {
    const val VERSION = "1"
    private val IDLE_LIMIT_MS = TimeUnit.MINUTES.toMillis(30)

    /** Commands safe to serve warm: read-only, no env-sensitive side effects. */
    private val DAEMONABLE = setOf(
        "find-config-properties", "find-implementations", "find-by-annotation",
        "get-class", "get-bean-definitions", "explain-conditional", "find-string",
        "list-extension-points", "find-class", "find-method", "list-packages",
        "search-symbol", "open-symbol", "why-dependency", "dep", "artifact",
        "class", "method", "status",
    )

    private fun daemonDir(): Path =
        dev.research4jar.indexer.Research4JarPaths.resolve(null).home.resolve("daemon")

    private fun portFile(): Path = daemonDir().resolve("$VERSION.port")

    private fun tokenFile(): Path = daemonDir().resolve("$VERSION.token")

    // ---------------------------------------------------------------- client

    /**
     * Serves [argv] through a running daemon. Returns the exit code, or null
     * when the daemon path does not apply (not a daemonable command, env
     * overrides present, no live daemon) — callers then run in-process.
     * Never throws: any failure returns null and the cold path takes over.
     */
    fun tryServe(argv: Array<String>): Int? {
        if (argv.isEmpty() || argv[0] !in DAEMONABLE) return null
        if (System.getenv("RESEARCH4JAR_NO_DAEMON") != null) return null
        // Env overrides change what the ported code reads via System.getenv;
        // the daemon's environment is frozen at spawn, so bypass it.
        if (System.getenv().keys.any { it.startsWith("RESEARCH4JAR_") }) return null
        return try {
            serveThroughDaemon(argv)
        } catch (_: Exception) {
            null
        }
    }

    private fun serveThroughDaemon(argv: Array<String>): Int? {
        val port = String(Files.readAllBytes(portFile()), StandardCharsets.US_ASCII)
            .trim().toIntOrNull() ?: return null
        val token = String(Files.readAllBytes(tokenFile()), StandardCharsets.US_ASCII).trim()
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300)
            socket.soTimeout = 0
            val out = DataOutputStream(socket.getOutputStream().buffered())
            val encoder = Base64.getEncoder()
            val cwd = System.getProperty("user.dir")
            out.write("R4JD1\n".toByteArray(StandardCharsets.US_ASCII))
            out.write((token + "\n").toByteArray(StandardCharsets.US_ASCII))
            out.write((encoder.encodeToString(cwd.toByteArray(StandardCharsets.UTF_8)) + "\n").toByteArray(StandardCharsets.US_ASCII))
            out.write(("${argv.size}\n").toByteArray(StandardCharsets.US_ASCII))
            for (argument in argv) {
                out.write(
                    (encoder.encodeToString(argument.toByteArray(StandardCharsets.UTF_8)) + "\n")
                        .toByteArray(StandardCharsets.US_ASCII),
                )
            }
            out.flush()

            val input = DataInputStream(socket.getInputStream().buffered())
            while (true) {
                val header = readLine(input) ?: return null
                val parts = header.split(" ")
                when (parts[0]) {
                    "O", "E" -> {
                        val length = parts.getOrNull(1)?.toIntOrNull() ?: return null
                        val payload = ByteArray(length)
                        input.readFully(payload)
                        (if (parts[0] == "O") System.out else System.err).write(payload)
                    }

                    "X" -> {
                        System.out.flush()
                        System.err.flush()
                        return parts.getOrNull(1)?.toIntOrNull() ?: 1
                    }

                    else -> return null
                }
            }
        }
    }

    private fun readLine(input: DataInputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (byte == '\n'.code) return buffer.toString()
            buffer.append(byte.toChar())
        }
    }

    /** Best-effort background daemon spawn for the NEXT invocation. */
    fun spawnInBackground() {
        if (System.getenv("RESEARCH4JAR_NO_DAEMON") != null) return
        if (System.getenv().keys.any { it.startsWith("RESEARCH4JAR_") }) return
        if (Files.isRegularFile(portFile())) return
        try {
            val jar = Paths.get(
                Daemon::class.java.protectionDomain.codeSource.location.toURI(),
            )
            if (!Files.isRegularFile(jar) || !jar.toString().endsWith(".jar")) return
            val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
            val log = daemonDir().resolve("daemon.log").toFile()
            Files.createDirectories(daemonDir())
            ProcessBuilder(javaBin, "-Xmx256m", "-jar", jar.toString(), "daemon")
                .redirectOutput(log)
                .redirectError(log)
                .start()
        } catch (_: Exception) {
            // The cold path stays fully functional without a daemon.
        }
    }

    // ---------------------------------------------------------------- server

    /** Runs the daemon loop until idle timeout. Invoked as `research4jar daemon`. */
    fun runServer(runCommand: (Array<String>, PrintStream, PrintStream) -> Int): Int {
        Files.createDirectories(daemonDir())
        val token = randomToken()
        ServerSocket(0, 16, InetAddress.getLoopbackAddress()).use { server ->
            writePrivate(tokenFile(), token)
            writePrivate(portFile(), server.localPort.toString())
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    Files.deleteIfExists(portFile())
                    Files.deleteIfExists(tokenFile())
                },
            )
            val lastActivity = AtomicLong(System.currentTimeMillis())
            server.soTimeout = 30_000
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    if (System.currentTimeMillis() - lastActivity.get() > IDLE_LIMIT_MS) return 0
                    continue
                }
                lastActivity.set(System.currentTimeMillis())
                try {
                    socket.use { handle(it, token, runCommand) }
                } catch (_: Exception) {
                    // One bad connection must not kill the daemon.
                }
                lastActivity.set(System.currentTimeMillis())
            }
        }
    }

    private val requestLock = Any()

    private fun handle(
        socket: Socket,
        expectedToken: String,
        runCommand: (Array<String>, PrintStream, PrintStream) -> Int,
    ) {
        socket.soTimeout = 10_000
        val input = DataInputStream(socket.getInputStream().buffered())
        if (readLine(input) != "R4JD1") return
        if (readLine(input) != expectedToken) return
        val decoder = Base64.getDecoder()
        val cwd = String(decoder.decode(readLine(input) ?: return), StandardCharsets.UTF_8)
        val argc = readLine(input)?.toIntOrNull() ?: return
        if (argc < 0 || argc > 1024) return
        val argv = Array(argc) {
            String(decoder.decode(readLine(input) ?: return), StandardCharsets.UTF_8)
        }

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        // One request at a time: user.dir is JVM-global state.
        val code = synchronized(requestLock) {
            val previousCwd = System.getProperty("user.dir")
            try {
                System.setProperty("user.dir", cwd)
                runCommand(
                    argv,
                    PrintStream(stdout, true, "UTF-8"),
                    PrintStream(stderr, true, "UTF-8"),
                )
            } catch (exception: Exception) {
                PrintStream(stderr, true, "UTF-8")
                    .println("research4jar daemon: ${exception.message}")
                1
            } finally {
                System.setProperty("user.dir", previousCwd)
            }
        }

        val out = DataOutputStream(socket.getOutputStream().buffered())
        writeFrame(out, "O", stdout.toByteArray())
        writeFrame(out, "E", stderr.toByteArray())
        out.write("X $code\n".toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    private fun writeFrame(out: DataOutputStream, kind: String, payload: ByteArray) {
        if (payload.isEmpty()) return
        out.write("$kind ${payload.size}\n".toByteArray(StandardCharsets.US_ASCII))
        out.write(payload)
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun writePrivate(path: Path, content: String) {
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        Files.write(temporary, content.toByteArray(StandardCharsets.US_ASCII))
        try {
            Files.setPosixFilePermissions(
                temporary,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows: loopback bind + random token remain the auth boundary.
        }
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    }
}
