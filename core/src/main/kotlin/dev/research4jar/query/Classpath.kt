package dev.research4jar.query

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Discovers a project's runtime dependency jars by asking its own build tool
 * (preferring the wrapper), ported from querier/internal/classpath. Error
 * messages stay identical to the Go implementation.
 */
object Classpath {
    class NoBuildToolException : RuntimeException(
        "no pom.xml or build.gradle(.kts) found; pass --jars with a jar directory, glob, or list",
    )

    private const val GRADLE_MARKER = "RESEARCH4JAR_JAR:"

    private val gradleInitScript = """
        allprojects { project ->
            project.tasks.register("research4jarClasspath") {
                doLast {
                    def cfg = project.configurations.findByName("runtimeClasspath")
                    if (cfg != null && cfg.canBeResolved) {
                        cfg.files.each { println("$GRADLE_MARKER" + it.absolutePath) }
                    }
                }
            }
        }
    """.trimIndent() + "\n"

    fun discover(
        projectDir: String,
        buildArgs: List<String> = emptyList(),
        noSnapshotUpdates: Boolean = false,
    ): List<String> {
        val absolute = Paths.get(projectDir).toAbsolutePath().normalize()
        return when {
            Files.isRegularFile(absolute.resolve("pom.xml")) ->
                discoverMaven(absolute, buildArgs, noSnapshotUpdates)
            listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                .any { Files.isRegularFile(absolute.resolve(it)) } ->
                discoverGradle(absolute, buildArgs)

            else -> throw NoBuildToolException()
        }
    }

    fun isMavenProject(projectDir: String): Boolean =
        Files.isRegularFile(Paths.get(projectDir).toAbsolutePath().normalize().resolve("pom.xml"))

    /**
     * Whether the Maven project at [projectDir] aggregates modules — the
     * shape whose classpath resolution needs the reactor flow below.
     */
    fun isMavenReactor(projectDir: String): Boolean {
        val absolute = Paths.get(projectDir).toAbsolutePath().normalize()
        return Files.isRegularFile(absolute.resolve("pom.xml")) &&
            mavenModuleDirs(absolute).isNotEmpty()
    }

    /**
     * All module directories declared by the pom at [root], recursively
     * (nested aggregators included), in reactor declaration order. Modules
     * activated only by profiles are included when their `<module>` entries
     * are present anywhere in the pom (a superset is harmless: absent
     * output files are simply skipped). A malformed pom yields no modules.
     */
    fun mavenModuleDirs(root: Path): List<Path> {
        val seen = LinkedHashSet<Path>()
        fun visit(dir: Path) {
            val pom = dir.resolve("pom.xml")
            if (!Files.isRegularFile(pom)) return
            val modules = try {
                val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                val document = factory.newDocumentBuilder().parse(pom.toFile())
                val nodes = document.getElementsByTagName("module")
                (0 until nodes.length).map { nodes.item(it).textContent.trim() }
            } catch (_: Exception) {
                emptyList()
            }
            for (module in modules) {
                if (module.isEmpty()) continue
                val moduleDir = dir.resolve(module).normalize()
                if (seen.add(moduleDir)) visit(moduleDir)
            }
        }
        visit(root)
        return seen.toList()
    }

    /**
     * One Maven run answering both questions the first index needs: the
     * runtime classpath and the dependency tree (provenance). Merging them
     * shares the JVM startup, dependency resolution, and any SNAPSHOT
     * metadata checks that a separate `dependency:tree` run would repeat.
     * The tree half is best-effort: [tgfs] is empty and [treeFailure]
     * explains why when only the classpath came back. Multi-module
     * projects return one TGF section per reactor module.
     */
    class MavenDiscoveryWithTree(
        val jars: List<String>,
        val tgfs: List<String>,
        val treeFailure: String,
    )

    fun discoverMavenWithTree(
        projectDir: String,
        buildArgs: List<String> = emptyList(),
        noSnapshotUpdates: Boolean = false,
    ): MavenDiscoveryWithTree {
        val absolute = Paths.get(projectDir).toAbsolutePath().normalize()
        val modules = mavenModuleDirs(absolute)
        if (modules.isNotEmpty()) {
            return discoverMavenReactor(absolute, modules, buildArgs, noSnapshotUpdates, withTree = true)
        }
        requireMaven(absolute)
        val classpathOutput = Files.createTempFile("research4jar-classpath-", ".txt")
        val treeOutput = Files.createTempFile("research4jar-dependency-tree-", ".tgf")
        try {
            val result = runBuildCommand(
                absolute, "mvnw", "mvn",
                listOf(
                    "-q", "-DincludeScope=runtime",
                    "dependency:build-classpath",
                    "-Dmdep.outputFile=$classpathOutput",
                    "-Dscope=runtime", "dependency:tree",
                    "-DoutputType=tgf", "-DoutputFile=$treeOutput",
                ) + mavenSnapshotArgs(noSnapshotUpdates) + buildArgs,
            )
            val raw = String(Files.readAllBytes(classpathOutput), Charsets.UTF_8).trim()
            val jars = filterJars(raw.split(File.pathSeparator))
            if (result.exitCode != 0) {
                // Goals run in order, so a populated classpath file means only
                // the tree half failed; provenance degrades, indexing goes on.
                if (jars.isEmpty()) {
                    throw RuntimeException(
                        "maven classpath resolution failed: exit status ${result.exitCode}\n" +
                            tail(result.output),
                    )
                }
                return MavenDiscoveryWithTree(
                    jars = jars,
                    tgfs = emptyList(),
                    treeFailure = "maven dependency tree failed: exit status ${result.exitCode}\n" +
                        tail(result.output),
                )
            }
            val tgf = String(Files.readAllBytes(treeOutput), Charsets.UTF_8)
            return MavenDiscoveryWithTree(jars = jars, tgfs = listOf(tgf), treeFailure = "")
        } finally {
            Files.deleteIfExists(classpathOutput)
            Files.deleteIfExists(treeOutput)
        }
    }

    // Per-module output files for the reactor flow: relative paths resolve
    // against each module's basedir, so one invocation captures every
    // module without cross-module overwrites (the plugin's outputFile is
    // last-write-wins for absolute paths, and appendOutput does not append
    // across modules in maven-dependency-plugin 3.7.0 — verified).
    private const val REACTOR_CLASSPATH_FILE = "target/research4jar-classpath.txt"
    private const val REACTOR_TREE_FILE = "target/research4jar-tree.tgf"

    /**
     * Classpath (and optionally tree) discovery for a multi-module reactor,
     * WITHOUT requiring a prior `mvn install`: running the `compile` phase
     * first lets Maven's reactor reader satisfy sibling-module dependencies
     * from their `target/classes` directories, which the `.jar` filter then
     * naturally excludes — the index covers external dependencies only,
     * exactly as for single-module projects. External jars come back as
     * Maven's own resolved absolute paths (SNAPSHOT/classifier safe).
     */
    private fun discoverMavenReactor(
        projectDir: Path,
        modules: List<Path>,
        buildArgs: List<String>,
        noSnapshotUpdates: Boolean,
        withTree: Boolean,
    ): MavenDiscoveryWithTree {
        requireMaven(projectDir)
        val moduleDirs = listOf(projectDir) + modules
        fun cleanup() {
            for (dir in moduleDirs) {
                try {
                    Files.deleteIfExists(dir.resolve(REACTOR_CLASSPATH_FILE))
                    Files.deleteIfExists(dir.resolve(REACTOR_TREE_FILE))
                } catch (_: Exception) {
                    // Best-effort: a leftover file inside target/ is inert.
                }
            }
        }
        cleanup() // stale files from an aborted earlier run must not leak in
        try {
            val args = mutableListOf(
                "-q", "-DincludeScope=runtime", "compile",
                "dependency:build-classpath",
                "-Dmdep.outputFile=$REACTOR_CLASSPATH_FILE",
            )
            if (withTree) {
                args += listOf(
                    "-Dscope=runtime", "dependency:tree",
                    "-DoutputType=tgf", "-DoutputFile=$REACTOR_TREE_FILE",
                )
            }
            args += mavenSnapshotArgs(noSnapshotUpdates) + buildArgs
            val result = runBuildCommand(projectDir, "mvnw", "mvn", args)
            if (result.exitCode != 0) {
                throw RuntimeException(
                    "maven classpath resolution failed: exit status ${result.exitCode}\n" +
                        tail(result.output),
                )
            }
            val jars = LinkedHashSet<String>()
            for (dir in moduleDirs) {
                val file = dir.resolve(REACTOR_CLASSPATH_FILE)
                if (!Files.isRegularFile(file)) continue
                val raw = String(Files.readAllBytes(file), Charsets.UTF_8).trim()
                filterJars(raw.split(File.pathSeparator)).forEach { jars += it }
            }
            val tgfs = if (withTree) {
                moduleDirs.mapNotNull { dir ->
                    val file = dir.resolve(REACTOR_TREE_FILE)
                    if (Files.isRegularFile(file)) {
                        String(Files.readAllBytes(file), Charsets.UTF_8).takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
            } else {
                emptyList()
            }
            val treeFailure = if (withTree && tgfs.isEmpty()) {
                "maven dependency tree produced no output"
            } else {
                ""
            }
            return MavenDiscoveryWithTree(jars.toList(), tgfs, treeFailure)
        } finally {
            cleanup()
        }
    }

    private fun discoverMaven(
        projectDir: Path,
        buildArgs: List<String>,
        noSnapshotUpdates: Boolean,
    ): List<String> {
        val modules = mavenModuleDirs(projectDir)
        if (modules.isNotEmpty()) {
            return discoverMavenReactor(projectDir, modules, buildArgs, noSnapshotUpdates, withTree = false).jars
        }
        requireMaven(projectDir)
        val output = Files.createTempFile("research4jar-classpath-", ".txt")
        try {
            val result = runBuildCommand(
                projectDir, "mvnw", "mvn",
                listOf(
                    "-q", "-DincludeScope=runtime",
                    "dependency:build-classpath",
                    "-Dmdep.outputFile=$output",
                ) + mavenSnapshotArgs(noSnapshotUpdates) + buildArgs,
            )
            if (result.exitCode != 0) {
                throw RuntimeException(
                    "maven classpath resolution failed: exit status ${result.exitCode}\n" +
                        tail(result.output),
                )
            }
            val raw = String(Files.readAllBytes(output), Charsets.UTF_8).trim()
            return filterJars(raw.split(File.pathSeparator))
        } finally {
            Files.deleteIfExists(output)
        }
    }

    /** `--no-snapshot-updates` is Maven-only; Gradle has no equivalent flag. */
    fun mavenSnapshotArgs(noSnapshotUpdates: Boolean): List<String> =
        if (noSnapshotUpdates) listOf("--no-snapshot-updates") else emptyList()

    private fun requireMaven(projectDir: Path) = requireWrapperOrTool(
        projectDir, listOf("mvnw", "mvnw.cmd", "mvnw.bat"), "mvn",
        "maven classpath resolution needs ./mvnw or mvn on PATH; " +
            "pass --jars explicitly or run research4jar doctor --project-dir $projectDir",
    )

    private fun discoverGradle(projectDir: Path, buildArgs: List<String>): List<String> {
        requireWrapperOrTool(
            projectDir, listOf("gradlew", "gradlew.cmd", "gradlew.bat"), "gradle",
            "gradle classpath resolution needs ./gradlew or gradle on PATH; " +
                "pass --jars explicitly or run research4jar doctor --project-dir $projectDir",
        )
        val script = Files.createTempFile("research4jar-init-", ".gradle")
        try {
            Files.write(script, gradleInitScript.toByteArray(Charsets.UTF_8))
            val result = runBuildCommand(
                projectDir, "gradlew", "gradle",
                listOf("--init-script", script.toString(), "-q", "research4jarClasspath") + buildArgs,
            )
            if (result.exitCode != 0) {
                throw RuntimeException(
                    "gradle classpath resolution failed: exit status ${result.exitCode}\n" +
                        tail(result.output),
                )
            }
            return filterJars(
                result.output.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith(GRADLE_MARKER) }
                    .map { it.removePrefix(GRADLE_MARKER) }
                    .toList(),
            )
        } finally {
            Files.deleteIfExists(script)
        }
    }

    data class CommandResult(val exitCode: Int, val output: String)

    /** Prefers the project's wrapper script, falling back to the tool on PATH. */
    fun runBuildCommand(
        projectDir: Path,
        wrapper: String,
        fallback: String,
        args: List<String>,
    ): CommandResult {
        val windows = System.getProperty("os.name").lowercase().startsWith("windows")
        val command = if (windows) {
            val bat = projectDir.resolve("$wrapper.bat")
            val cmd = projectDir.resolve("$wrapper.cmd")
            when {
                Files.isRegularFile(bat) -> listOf("cmd", "/c", bat.toString()) + args
                Files.isRegularFile(cmd) -> listOf("cmd", "/c", cmd.toString()) + args
                else -> listOf(fallback) + args
            }
        } else {
            val wrapperPath = projectDir.resolve(wrapper)
            if (Files.isRegularFile(wrapperPath)) {
                listOf(wrapperPath.toString()) + args
            } else {
                listOf(fallback) + args
            }
        }
        val process = ProcessBuilder(command)
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        // The build tool owns its own timeouts; this guard only prevents a
        // wedged wrapper from hanging the CLI forever.
        if (!process.waitFor(30, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return CommandResult(exitCode = -1, output = output)
        }
        return CommandResult(process.exitValue(), output)
    }

    private fun requireWrapperOrTool(
        projectDir: Path,
        wrappers: List<String>,
        tool: String,
        message: String,
    ) {
        if (wrappers.any { Files.isRegularFile(projectDir.resolve(it)) }) return
        if (findOnPath(tool)) return
        throw RuntimeException(message)
    }

    private fun findOnPath(tool: String): Boolean {
        val pathValue = System.getenv("PATH") ?: return false
        val names = if (System.getProperty("os.name").lowercase().startsWith("windows")) {
            listOf("$tool.exe", "$tool.cmd", "$tool.bat", tool)
        } else {
            listOf(tool)
        }
        return pathValue.split(File.pathSeparator).any { dir ->
            names.any { name ->
                val candidate = Paths.get(dir, name)
                Files.isRegularFile(candidate) && (
                    System.getProperty("os.name").lowercase().startsWith("windows") ||
                        Files.isExecutable(candidate)
                    )
            }
        }
    }

    fun filterJars(entries: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (entry in entries) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty() || !trimmed.endsWith(".jar")) continue
            seen += trimmed
        }
        return seen.toList()
    }

    fun tail(output: String): String {
        val lines = output.trim().split("\n")
        return if (lines.size > 20) lines.takeLast(20).joinToString("\n") else lines.joinToString("\n")
    }
}
