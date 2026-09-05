package dev.research4jar.runtime

import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.stream.Stream

/** Drains output concurrently so the deadline applies even when a wrapper keeps stdout open. */
internal object BuildProcess {
    data class Result(val exitCode: Int, val output: String)

    fun run(command: List<String>, directory: Path, timeoutMs: Long): Result {
        require(timeoutMs > 0) { "build timeout must be positive" }
        OperationContext.checkCancelled()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val process = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = BuildOutput()
        val drain = FutureTask {
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    output.append(buffer, count)
                }
            }
        }
        Thread(drain, "research4jar-build-output").apply { isDaemon = true; start() }
        try {
            OperationContext.onCancel { terminate(process) }.use {
                if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                    return Result(-1, output.text() + "\nbuild command timed out after ${timeoutMs}ms")
                }
                try {
                    drain.get(remaining(deadline), TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                    return Result(-1, output.text() + "\nbuild output did not close before the timeout")
                }
                OperationContext.checkCancelled()
                return Result(process.exitValue(), output.text())
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } finally {
            // Also closes descendants on cancellation; reflection keeps the shipped Java 8 API baseline.
            if (process.isAlive || !drain.isDone) terminate(process)
            drain.cancel(true)
            // A reader blocked in a native pipe read may hold the stream's monitor.
            // Never close it on the deadline/control thread; the daemon reader closes it on EOF.
            runCatching { process.outputStream.close() }
        }
    }

    private fun remaining(deadline: Long): Long = (deadline - System.nanoTime()).coerceAtLeast(0)

    private fun terminate(process: Process) {
        runCatching {
            val type = Class.forName("java.lang.ProcessHandle")
            val handle = Process::class.java.getMethod("toHandle").invoke(process)
            @Suppress("UNCHECKED_CAST")
            val descendants = type.getMethod("descendants").invoke(handle) as Stream<Any>
            val children = descendants.use { it.toArray().toList().asReversed() }
            children.forEach { child -> runCatching { type.getMethod("destroyForcibly").invoke(child) } }
        }
        runCatching { process.destroyForcibly() }
    }

    /** Bounded diagnostic tail, retaining Gradle's machine-readable classpath even in verbose builds. */
    private class BuildOutput {
        private val tail = StringBuilder()
        private val line = StringBuilder()
        private val jars = StringBuilder()
        private var truncated = false
        private var longLine = false
        private var failure: String? = null

        @Synchronized
        fun append(chars: CharArray, count: Int) {
            tail.append(chars, 0, count)
            if (tail.length > MAX_CHARS) {
                tail.delete(0, tail.length - MAX_CHARS)
                truncated = true
            }
            for (i in 0 until count) {
                val char = chars[i]
                if (char == '\n') {
                    keepJarLine()
                    line.setLength(0)
                    longLine = false
                } else if (line.length < MAX_LINE_CHARS) {
                    line.append(char)
                } else {
                    longLine = true
                }
            }
        }

        private fun keepJarLine() {
            if (!line.toString().trimStart().startsWith("RESEARCH4JAR_JAR:")) return
            if (longLine || jars.length + line.length >= MAX_CHARS) {
                failure = "build classpath output exceeds the capture limit; pass --jars explicitly"
                return // Keep draining so the subprocess cannot block on a full pipe.
            }
            jars.append(line).append('\n')
        }

        @Synchronized
        fun text(): String {
            check(failure == null) { failure!! }
            if (!truncated) return tail.toString()
            // Drop the tail's first partial line. Markers are preserved in original order and de-duplicated
            // by Classpath.filterJars, so a noisy build cannot silently omit an early dependency.
            return "[build log truncated]\n" + jars + tail.substring(tail.indexOf("\n") + 1)
        }

        companion object {
            private const val MAX_CHARS = 1024 * 1024
            private const val MAX_LINE_CHARS = 64 * 1024
        }
    }
}
