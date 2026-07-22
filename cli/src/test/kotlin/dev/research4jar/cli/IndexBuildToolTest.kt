package dev.research4jar.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the full `index` command against a fake Maven wrapper to pin the
 * build-tool contract: ONE Maven run answers both the classpath and the
 * dependency tree on a first index, re-runs skip the tree, explicit
 * --no-snapshot-updates/--build-arg reach the invocation, and the stats JSON
 * carries the phase timings.
 */
class IndexBuildToolTest {

    private val tgf = "1 com.example:app:jar:1.0\n2 org.demo:lib:jar:2.0:compile\n#\n1 2\n"

    private fun mavenProject(dir: Path, jar: Path, tgfText: String = tgf) {
        Files.writeString(dir.resolve("pom.xml"), "<project/>\n")
        Files.writeString(dir.resolve("classpath.payload"), jar.toString())
        Files.writeString(dir.resolve("tree.tgf"), tgfText)
        val wrapper = dir.resolve("mvnw")
        Files.writeString(
            wrapper,
            """
            #!/bin/sh
            printf '%s\n' "$*" >> calls.log
            cp_out=""; tree_out=""
            for a in "$@"; do
              case "${'$'}a" in
                -Dmdep.outputFile=*) cp_out="${'$'}{a#-Dmdep.outputFile=}" ;;
                -DoutputFile=*) tree_out="${'$'}{a#-DoutputFile=}" ;;
              esac
            done
            if [ -n "${'$'}cp_out" ] && [ -f classpath.payload ]; then cp classpath.payload "${'$'}cp_out"; fi
            if [ -n "${'$'}tree_out" ] && [ -f tree.tgf ]; then cp tree.tgf "${'$'}tree_out"; fi
            exit 0
            """.trimIndent() + "\n",
        )
        wrapper.toFile().setExecutable(true)
    }

    private fun tinyJar(dir: Path): Path {
        val jar = dir.resolve("dep.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("hello.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        return jar
    }

    private fun calls(dir: Path): List<String> =
        dir.resolve("calls.log").takeIf(Files::exists)?.let(Files::readAllLines) ?: emptyList()

    private fun runIndex(vararg extra: String): Triple<Int, String, String> {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val code = runCli(
            arrayOf("index") + extra,
            PrintStream(stdout),
            PrintStream(stderr),
        )
        return Triple(code, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
    }

    @Test
    fun `first maven index answers classpath and provenance from one build tool run`() {
        val work = Files.createTempDirectory("r4j-index-")
        val project = Files.createDirectory(work.resolve("project"))
        val home = Files.createDirectory(work.resolve("home"))
        mavenProject(project, tinyJar(work))

        val (code, stdout, stderr) = runIndex(
            "--project-dir", project.toString(), "--home", home.toString(),
        )

        assertEquals(0, code, stderr)
        val recorded = calls(project)
        assertEquals(1, recorded.size, recorded.joinToString("\n"))
        assertTrue(recorded[0].contains("dependency:build-classpath"), recorded[0])
        assertTrue(recorded[0].contains("dependency:tree"), recorded[0])

        val stats = jacksonObjectMapper().readTree(stdout)
        assertEquals(1, stats["jars_total"].intValue(), stdout)
        assertEquals(1, stats["jars_indexed"].intValue(), stdout)
        for (field in listOf("classpath_ms", "extract_ms", "provenance_ms", "total_ms")) {
            assertTrue(stats.has(field), "missing $field in $stdout")
            assertTrue(stats[field].longValue() >= 0, "$field negative in $stdout")
        }
        assertEquals(stats["duration_ms"].longValue(), stats["extract_ms"].longValue(), stdout)
        assertTrue(
            stats["total_ms"].longValue() >= stats["extract_ms"].longValue(),
            stdout,
        )

        val provenance = project.resolve(".research4jar").resolve("dependencies.json")
        assertTrue(Files.exists(provenance))
        assertTrue(Files.readString(provenance).contains("org.demo:lib"), "provenance content")

        // Unchanged re-index: classpath resolution only, provenance reused.
        val (secondCode, _, secondErr) = runIndex(
            "--project-dir", project.toString(), "--home", home.toString(),
        )
        assertEquals(0, secondCode, secondErr)
        val afterSecond = calls(project)
        assertEquals(2, afterSecond.size, afterSecond.joinToString("\n"))
        assertTrue(afterSecond[1].contains("dependency:build-classpath"), afterSecond[1])
        assertFalse(afterSecond[1].contains("dependency:tree"), afterSecond[1])
        assertTrue(
            secondErr.contains("classpath unchanged; reusing dependency provenance"),
            secondErr,
        )
    }

    @Test
    fun `snapshot and build args reach the maven invocation`() {
        val work = Files.createTempDirectory("r4j-index-")
        val project = Files.createDirectory(work.resolve("project"))
        val home = Files.createDirectory(work.resolve("home"))
        mavenProject(project, tinyJar(work))

        val (code, _, stderr) = runIndex(
            "--project-dir", project.toString(), "--home", home.toString(),
            "--no-snapshot-updates", "--build-arg", "-Pfast",
        )

        assertEquals(0, code, stderr)
        val recorded = calls(project)
        assertEquals(1, recorded.size)
        assertTrue(recorded[0].contains("--no-snapshot-updates"), recorded[0])
        assertTrue(recorded[0].contains("-Pfast"), recorded[0])
    }

    @Test
    fun `tree parse failure degrades to a warning and indexing succeeds`() {
        val work = Files.createTempDirectory("r4j-index-")
        val project = Files.createDirectory(work.resolve("project"))
        val home = Files.createDirectory(work.resolve("home"))
        mavenProject(project, tinyJar(work), tgfText = "junk\n#\n")

        val (code, stdout, stderr) = runIndex(
            "--project-dir", project.toString(), "--home", home.toString(),
        )

        assertEquals(0, code, stderr)
        assertTrue(
            stderr.contains("warning: dependency provenance unavailable:"),
            stderr,
        )
        assertFalse(Files.exists(project.resolve(".research4jar").resolve("dependencies.json")))
        val stats = jacksonObjectMapper().readTree(stdout)
        assertEquals(1, stats["jars_indexed"].intValue(), stdout)
        // The failed tree half must not trigger a second Maven run.
        assertEquals(1, calls(project).size)
    }
}
