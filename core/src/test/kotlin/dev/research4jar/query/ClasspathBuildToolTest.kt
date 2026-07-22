package dev.research4jar.query

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises build-tool invocation shapes against fake wrapper scripts: the
 * wrapper records its argv into calls.log and serves canned payloads from the
 * project directory, so these tests pin the exact command lines without
 * needing Maven or Gradle installed.
 */
class ClasspathBuildToolTest {

    private fun mavenProject(dir: Path) {
        Files.writeString(dir.resolve("pom.xml"), "<project/>\n")
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
            if [ -f exitcode ]; then exit "$(cat exitcode)"; fi
            exit 0
            """.trimIndent() + "\n",
        )
        wrapper.toFile().setExecutable(true)
    }

    private fun calls(dir: Path): List<String> =
        dir.resolve("calls.log").takeIf(Files::exists)?.let(Files::readAllLines) ?: emptyList()

    private fun fakeJar(dir: Path, name: String): String {
        val jar = dir.resolve(name)
        Files.write(jar, byteArrayOf())
        return jar.toString()
    }

    @Test
    fun `maven discovery passes the snapshot flag and build args through`() {
        val dir = Files.createTempDirectory("r4j-classpath-")
        mavenProject(dir)
        val jar = fakeJar(dir, "dep.jar")
        Files.writeString(dir.resolve("classpath.payload"), jar)

        val jars = Classpath.discover(dir.toString(), listOf("-Pfast", "-o"), true)

        assertEquals(listOf(jar), jars)
        val recorded = calls(dir)
        assertEquals(1, recorded.size)
        assertTrue(recorded[0].contains("dependency:build-classpath"), recorded[0])
        assertTrue(recorded[0].contains("--no-snapshot-updates"), recorded[0])
        assertTrue(recorded[0].contains("-Pfast -o"), recorded[0])
    }

    @Test
    fun `merged discovery answers classpath and tree from one maven run`() {
        val dir = Files.createTempDirectory("r4j-classpath-")
        mavenProject(dir)
        val jar = fakeJar(dir, "dep.jar")
        Files.writeString(dir.resolve("classpath.payload"), jar)
        val tgf = "1 com.example:app:jar:1.0\n2 org.demo:lib:jar:2.0:compile\n#\n1 2\n"
        Files.writeString(dir.resolve("tree.tgf"), tgf)

        val merged = Classpath.discoverMavenWithTree(dir.toString())

        assertEquals(listOf(jar), merged.jars)
        assertEquals(tgf, merged.tgf)
        assertEquals("", merged.treeFailure)
        val recorded = calls(dir)
        assertEquals(1, recorded.size)
        assertTrue(recorded[0].contains("dependency:build-classpath"), recorded[0])
        assertTrue(recorded[0].contains("dependency:tree"), recorded[0])
    }

    @Test
    fun `merged discovery degrades to classpath-only when the tree half fails`() {
        val dir = Files.createTempDirectory("r4j-classpath-")
        mavenProject(dir)
        val jar = fakeJar(dir, "dep.jar")
        Files.writeString(dir.resolve("classpath.payload"), jar)
        Files.writeString(dir.resolve("exitcode"), "1")

        val merged = Classpath.discoverMavenWithTree(dir.toString())

        assertEquals(listOf(jar), merged.jars)
        assertEquals("", merged.tgf)
        assertTrue(
            merged.treeFailure.startsWith("maven dependency tree failed: exit status 1"),
            merged.treeFailure,
        )
    }

    @Test
    fun `merged discovery fails loudly when the classpath half fails`() {
        val dir = Files.createTempDirectory("r4j-classpath-")
        mavenProject(dir)
        Files.writeString(dir.resolve("exitcode"), "1")

        val exception = assertFailsWith<RuntimeException> {
            Classpath.discoverMavenWithTree(dir.toString())
        }
        assertTrue(
            exception.message!!.startsWith("maven classpath resolution failed: exit status 1"),
            exception.message,
        )
    }

    @Test
    fun `gradle discovery appends build args to the invocation`() {
        val dir = Files.createTempDirectory("r4j-classpath-")
        Files.writeString(dir.resolve("build.gradle"), "// test\n")
        val jar = fakeJar(dir, "dep.jar")
        val wrapper = dir.resolve("gradlew")
        Files.writeString(
            wrapper,
            """
            #!/bin/sh
            printf '%s\n' "$*" >> calls.log
            printf 'RESEARCH4JAR_JAR:%s\n' "$jar"
            """.trimIndent() + "\n",
        )
        wrapper.toFile().setExecutable(true)

        val jars = Classpath.discover(dir.toString(), listOf("--offline"))

        assertEquals(listOf(jar), jars)
        val recorded = calls(dir)
        assertEquals(1, recorded.size)
        assertTrue(recorded[0].contains("research4jarClasspath --offline"), recorded[0])
    }

    @Test
    fun `multi-entry classpath payload survives path separators`() {
        val dir = Files.createTempDirectory("r4j-classpath-")
        mavenProject(dir)
        val first = fakeJar(dir, "a.jar")
        val second = fakeJar(dir, "b.jar")
        Files.writeString(
            dir.resolve("classpath.payload"),
            first + File.pathSeparator + second,
        )

        assertEquals(listOf(first, second), Classpath.discover(dir.toString()))
    }
}
