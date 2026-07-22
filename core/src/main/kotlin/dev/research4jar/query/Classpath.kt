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
     * One Maven run answering both questions the first index needs: the
     * runtime classpath and the dependency tree (provenance). Merging them
     * shares the JVM startup, dependency resolution, and any SNAPSHOT
     * metadata checks that a separate `dependency:tree` run would repeat.
     * The tree half is best-effort: [tgf] is empty and [treeFailure]
     * explains why when only the classpath came back.
     */
    class MavenDiscoveryWithTree(
        val jars: List<String>,
        val tgf: String,
        val treeFailure: String,
    )

    fun discoverMavenWithTree(
        projectDir: String,
        buildArgs: List<String> = emptyList(),
        noSnapshotUpdates: Boolean = false,
    ): MavenDiscoveryWithTree {
        val absolute = Paths.get(projectDir).toAbsolutePath().normalize()
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
                    tgf = "",
                    treeFailure = "maven dependency tree failed: exit status ${result.exitCode}\n" +
                        tail(result.output),
                )
            }
            val tgf = String(Files.readAllBytes(treeOutput), Charsets.UTF_8)
            return MavenDiscoveryWithTree(jars = jars, tgf = tgf, treeFailure = "")
        } finally {
            Files.deleteIfExists(classpathOutput)
            Files.deleteIfExists(treeOutput)
        }
    }

    private fun discoverMaven(
        projectDir: Path,
        buildArgs: List<String>,
        noSnapshotUpdates: Boolean,
    ): List<String> {
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
