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
        assertEquals(listOf(tgf), merged.tgfs)
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
        assertEquals(emptyList(), merged.tgfs)
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

    private fun reactorProject(dir: Path) {
        Files.writeString(
            dir.resolve("pom.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
              <modules><module>core</module><module>app</module></modules>
            </project>
            """.trimIndent() + "\n",
        )
        Files.createDirectories(dir.resolve("core"))
        Files.createDirectories(dir.resolve("app"))
        Files.writeString(dir.resolve("core/pom.xml"), "<project/>\n")
        Files.writeString(dir.resolve("app/pom.xml"), "<project/>\n")
        val wrapper = dir.resolve("mvnw")
        Files.writeString(
            wrapper,
            """
            #!/bin/sh
            printf '%s\n' "$*" >> calls.log
            for d in . core app; do
              if [ -f "${'$'}d/classpath.payload" ]; then
                mkdir -p "${'$'}d/target"
                cp "${'$'}d/classpath.payload" "${'$'}d/target/research4jar-classpath.txt"
              fi
              if [ -f "${'$'}d/tree.payload" ]; then
                mkdir -p "${'$'}d/target"
                cp "${'$'}d/tree.payload" "${'$'}d/target/research4jar-tree.tgf"
              fi
            done
            exit 0
            """.trimIndent() + "\n",
        )
        wrapper.toFile().setExecutable(true)
    }

    @Test
    fun `reactor modules are parsed recursively from the pom`() {
        val dir = Files.createTempDirectory("r4j-classpath-")
        Files.writeString(
            dir.resolve("pom.xml"),
            "<project><modules><module>core</module><module>nested</module></modules></project>\n",
        )
        Files.createDirectories(dir.resolve("core"))
        Files.writeString(dir.resolve("core/pom.xml"), "<project/>\n")
        Files.createDirectories(dir.resolve("nested"))
        Files.writeString(
            dir.resolve("nested/pom.xml"),
            "<project><modules><module>deep</module></modules></project>\n",
        )
        val moduleDirs = Classpath.mavenModuleDirs(dir)
        assertEquals(
            listOf(dir.resolve("core"), dir.resolve("nested"), dir.resolve("nested/deep")),
            moduleDirs.map { it },
        )
        assertTrue(Classpath.isMavenReactor(dir.toString()))
    }

    @Test
    fun `reactor discovery unions module classpaths and drops class directories`() {
        val dir = Files.createTempDirectory("r4j-classpath-")
        reactorProject(dir)
        val shared = fakeJar(dir, "shared.jar")
        val gson = fakeJar(dir, "gson.jar")
        // core: one external jar; app: sibling target/classes + shared + another jar
        Files.writeString(dir.resolve("core/classpath.payload"), shared)
        Files.writeString(
            dir.resolve("app/classpath.payload"),
            dir.resolve("core/target/classes").toString() + File.pathSeparator +
                shared + File.pathSeparator + gson,
        )

        val jars = Classpath.discover(dir.toString())

        assertEquals(listOf(shared, gson), jars)
        val recorded = calls(dir)
        assertEquals(1, recorded.size)
        assertTrue(recorded[0].contains(" compile dependency:build-classpath"), recorded[0])
        assertTrue(
            recorded[0].contains("-Dmdep.outputFile=target/research4jar-classpath.txt"),
            recorded[0],
        )
        // The per-module capture files must not survive discovery.
        assertTrue(Files.notExists(dir.resolve("core/target/research4jar-classpath.txt")))
        assertTrue(Files.notExists(dir.resolve("app/target/research4jar-classpath.txt")))
    }

    @Test
    fun `reactor merged discovery returns one tree section per module`() {
        val dir = Files.createTempDirectory("r4j-classpath-")
        reactorProject(dir)
        val jar = fakeJar(dir, "dep.jar")
        Files.writeString(dir.resolve("core/classpath.payload"), jar)
        val coreTree = "1 com.fixture:core:jar:1.0\n2 org.demo:lib:jar:2.0:compile\n#\n1 2\n"
        val appTree = "1 com.fixture:app:jar:1.0\n2 com.fixture:core:jar:1.0:compile\n#\n1 2\n"
        Files.writeString(dir.resolve("core/tree.payload"), coreTree)
        Files.writeString(dir.resolve("app/tree.payload"), appTree)

        val merged = Classpath.discoverMavenWithTree(dir.toString())

        assertEquals(listOf(jar), merged.jars)
        assertEquals(listOf(coreTree, appTree), merged.tgfs)
        assertEquals("", merged.treeFailure)
        assertEquals(1, calls(dir).size)
    }

    @Test
    fun `tgf sections merge dedupes by shallowest coordinate`() {
        val coreTree = "1 com.fixture:core:jar:1.0\n2 org.demo:lib:jar:2.0:compile\n#\n1 2\n"
        val appTree = "9 com.fixture:app:jar:1.0\n8 com.fixture:core:jar:1.0:compile\n" +
            "7 org.demo:lib:jar:2.0:compile\n#\n9 8\n8 7\n"
        val graph = DepGraphCapture.graphFromTgfSections(listOf(coreTree, appTree), "/root")

        val byCoordinate = graph.artifacts.associateBy { it.coordinate }
        assertEquals(3, graph.artifacts.size)
        // Both module coordinates stay depth-0 roots even though app lists core.
        assertEquals(0, byCoordinate.getValue("com.fixture:core:1.0").depth)
        assertEquals(0, byCoordinate.getValue("com.fixture:app:1.0").depth)
        // The shared external keeps its shortest path (depth 1 via core).
        assertEquals(1, byCoordinate.getValue("org.demo:lib:2.0").depth)
        assertEquals("/root", graph.projectRoot)
        assertEquals("maven", graph.buildTool)
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
