package dev.research4jar.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

@DisabledOnOs(OS.WINDOWS)
class BuildProcessTest {
    @TempDir lateinit var root: Path

    private fun wrapper(body: String): List<String> {
        val path = root.resolve("wrapper")
        Files.writeString(path, "#!/bin/sh\n$body\n")
        check(path.toFile().setExecutable(true))
        return listOf(path.toString())
    }

    @Test
    fun `timeout applies while a wrapper keeps stdout open`() {
        val command = wrapper("printf 'started\\n'\nexec sleep 30")
        val start = System.nanoTime()
        val result = BuildProcess.run(command, root, 300)
        assertEquals(-1, result.exitCode)
        assertTrue(result.output.contains("timed out"))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000)
    }

    @Test
    fun `cancellation terminates the wrapper and its child process`() {
        val command = wrapper("sleep 30 &\nprintf '%s' \"${'$'}!\" > child.pid\nwait")
        val context = OperationContext()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val work = executor.submit<BuildProcess.Result> { context.run { BuildProcess.run(command, root, 30_000) } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!Files.exists(root.resolve("child.pid")) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.exists(root.resolve("child.pid")))
            val pid = Files.readString(root.resolve("child.pid")).toLong()
            context.cancel()
            val exception = assertFailsWith<ExecutionException> { work.get(5, TimeUnit.SECONDS) }
            assertTrue(exception.cause is CancellationException, exception.toString())
            val child = ProcessHandle.of(pid)
            if (child.isPresent) child.get().onExit().get(5, TimeUnit.SECONDS)
        } finally {
            context.cancel()
            executor.shutdownNow()
        }
    }

    @Test
    fun `verbose build logs remain bounded without dropping early Gradle classpath markers`() {
        val command = wrapper(
            "printf 'RESEARCH4JAR_JAR:/early.jar\\n'\n" +
                "head -c 2200000 /dev/zero | tr '\\000' x\n" +
                "printf '\\nRESEARCH4JAR_JAR:/late.jar\\nfinished\\n'",
        )
        val result = BuildProcess.run(command, root, 10_000)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.startsWith("[build log truncated]"))
        assertTrue(result.output.contains("RESEARCH4JAR_JAR:/early.jar\n"))
        assertTrue(result.output.contains("RESEARCH4JAR_JAR:/late.jar\n"))
        assertTrue(result.output.endsWith("finished\n"))
        assertTrue(result.output.length < 2 * 1024 * 1024)
    }
}
