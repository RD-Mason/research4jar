package dev.research4jar.envcheck

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * Environment doctor ported from querier/internal/envcheck (Go): reports the
 * local runtime and build tools Research4Jar needs, including concrete
 * installation guidance for users and agents. Agents consume
 * `research4jar doctor --format json`, so check ids, JSON key sets, and every
 * message/guidance string must stay identical to the Go querier. Go
 * `omitempty` fields map to explicit [JsonInclude] annotations here;
 * everything else is always emitted.
 */
enum class Status(@get:JsonValue val text: String) {
    OK("ok"),
    MISSING("missing"),
    WARNING("warning"),
}

data class Options(
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("project_dir") val projectDir: String = "",
    @JsonProperty("source_build") val sourceBuild: Boolean = false,
)

data class Report(
    @JsonProperty("ok") val ok: Boolean,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("project_dir") val projectDir: String = "",
    @JsonProperty("checks") val checks: List<Check>,
)

data class Check(
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("status") val status: Status,
    @JsonProperty("required_for") val requiredFor: List<String>,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("found") val found: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("version") val version: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("minimum") val minimum: String = "",
    @JsonProperty("message") val message: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("user_install") val userInstall: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("agent_install") val agentInstall: List<String> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("verify") val verify: List<String> = emptyList(),
)

object EnvCheck {
    /** Runs every doctor check against the host, mirroring envcheck.Run. */
    fun run(options: Options = Options()): Report = Inspector().run(options)
}

/**
 * The doctor's probes behind injectable seams (PATH lookup, indexer locate,
 * process execution), mirroring the Go inspector struct so tests can pin
 * check outcomes without touching the host.
 */
internal class Inspector(
    private val goos: String = currentGoos(),
    private val lookup: (String) -> String? = { name -> lookPath(name, goos) },
    private val locateIndexer: () -> String? = {
        try {
            IndexerLocate.locate()
        } catch (_: IndexerLocate.NotFoundException) {
            null
        }
    },
    private val execute: (List<String>) -> CommandResult = ::runCommand,
) {
    fun run(options: Options): Report {
        var projectDir = options.projectDir
        if (projectDir.isEmpty()) {
            projectDir = System.getProperty("user.dir").orEmpty()
        }
        projectDir = try {
            Paths.get(projectDir).toAbsolutePath().normalize().toString()
        } catch (_: InvalidPathException) {
            projectDir
        }

        val checks = mutableListOf(
            checkIndexer(),
            checkJava(),
            checkProjectBuildTool(projectDir),
        )
        if (options.sourceBuild) {
            checks += checkBuildJava()
            checks += checkJavac()
            checks += checkGo()
            checks += checkCommand(
                "make",
                "Make",
                listOf("source build via make build/make install"),
                makeGuide(goos),
            )
            checks += checkCommand(
                "bash",
                "Bash",
                listOf("install.sh and end-to-end scripts"),
                InstallGuide(
                    user = "Install Bash or run from an environment that provides /usr/bin/env bash.",
                    agent = packageInstallFor(goos, "bash"),
                    verify = listOf("bash --version"),
                ),
            )
        }
        val ok = checks.none { it.status == Status.MISSING }
        return Report(ok = ok, projectDir = projectDir, checks = checks)
    }

    private fun checkIndexer(): Check {
        val guide = InstallGuide(
            user = "Install a Research4Jar release archive and put bin/ on PATH, or build from source with ./install.sh.",
            agent = listOf(
                "if [ -x ./install.sh ]; then ./install.sh; else echo 'Install a Research4Jar release archive and put bin/ on PATH' >&2; exit 1; fi",
                "PATH=\"\$HOME/.local/bin:\$PATH\" research4jar doctor --source-build",
            ),
            verify = listOf("PATH=\"\$HOME/.local/bin:\$PATH\" research4jar-index --help"),
        )
        val check = Check(
            id = "research4jar-index",
            name = "Research4Jar JVM indexer launcher",
            status = Status.MISSING,
            requiredFor = listOf("local jar extraction", "registry misses"),
            minimum = "bundled with Research4Jar",
            userInstall = guide.user,
            agentInstall = guide.agent,
            verify = guide.verify,
        )
        val found = locateIndexer()
        if (found != null) {
            return check.copy(
                status = Status.OK,
                found = found,
                message = "research4jar-index is available.",
            )
        }
        return check.copy(
            message = "research4jar-index was not found next to the CLI or on PATH. " +
                "A fully covered registry can avoid local extraction, but registry misses need this launcher.",
        )
    }

    private fun checkJava(): Check {
        val guide = javaRuntimeGuide(goos)
        val check = Check(
            id = "java",
            name = "Java runtime 8+",
            status = Status.MISSING,
            requiredFor = listOf("running the JVM indexer", "local jar extraction", "registry misses"),
            minimum = "8",
            userInstall = guide.user,
            agentInstall = guide.agent,
            verify = guide.verify,
        )
        val found = lookup("java")
            ?: return check.copy(message = "java was not found on PATH.")
        val probe = execute(listOf("java", "-version"))
        if (!probe.success) {
            return check.copy(
                found = found,
                message = "java exists but java -version failed: " + oneLine(probe.output),
            )
        }
        val major = parseJavaMajor(probe.output)
        if (major != null && major >= 8) {
            return check.copy(
                status = Status.OK,
                found = found,
                version = firstLine(probe.output),
                message = "Java is new enough.",
            )
        }
        return check.copy(
            found = found,
            version = firstLine(probe.output),
            message = "Java is installed but version 8 or newer is required.",
        )
    }

    private fun checkBuildJava(): Check {
        val guide = javaBuildGuide(goos)
        val check = Check(
            id = "java-source-build",
            name = "Java runtime 17+ for source builds",
            status = Status.MISSING,
            requiredFor = listOf("running Gradle and Kotlin build tools"),
            minimum = "17",
            userInstall = guide.user,
            agentInstall = guide.agent,
            verify = listOf("java -version"),
        )
        val found = lookup("java")
            ?: return check.copy(message = "java was not found on PATH.")
        val probe = execute(listOf("java", "-version"))
        if (!probe.success) {
            return check.copy(
                found = found,
                message = "java exists but java -version failed: " + oneLine(probe.output),
            )
        }
        val major = parseJavaMajor(probe.output)
        if (major != null && major >= 17) {
            return check.copy(
                status = Status.OK,
                found = found,
                version = firstLine(probe.output),
                message = "Java is new enough for source builds.",
            )
        }
        return check.copy(
            found = found,
            version = firstLine(probe.output),
            message = "Source builds require Java 17 or newer, even though the released indexer runtime supports Java 11.",
        )
    }

    private fun checkJavac(): Check {
        val guide = javaBuildGuide(goos)
        val check = Check(
            id = "javac",
            name = "JDK compiler 17+",
            status = Status.MISSING,
            requiredFor = listOf("source build", "full verification"),
            minimum = "17",
            userInstall = guide.user,
            agentInstall = guide.agent,
            verify = listOf("javac -version"),
        )
        val found = lookup("javac")
            ?: return check.copy(message = "javac was not found on PATH; install a JDK, not only a JRE.")
        val probe = execute(listOf("javac", "-version"))
        if (!probe.success) {
            return check.copy(
                found = found,
                message = "javac exists but javac -version failed: " + oneLine(probe.output),
            )
        }
        val major = parseJavaMajor(probe.output)
        if (major != null && major >= 17) {
            return check.copy(
                status = Status.OK,
                found = found,
                version = firstLine(probe.output),
                message = "JDK compiler is new enough.",
            )
        }
        return check.copy(
            found = found,
            version = firstLine(probe.output),
            message = "javac is installed but version 17 or newer is required.",
        )
    }

    private fun checkGo(): Check {
        val guide = goGuide(goos)
        val check = Check(
            id = "go",
            name = "Go 1.23+",
            status = Status.MISSING,
            requiredFor = listOf("source build", "querier tests"),
            minimum = "1.23",
            userInstall = guide.user,
            agentInstall = guide.agent,
            verify = guide.verify,
        )
        val found = lookup("go")
            ?: return check.copy(message = "go was not found on PATH.")
        val probe = execute(listOf("go", "version"))
        if (!probe.success) {
            return check.copy(
                found = found,
                message = "go exists but go version failed: " + oneLine(probe.output),
            )
        }
        val version = parseGoVersion(probe.output)
        if (version != null && (version.first > 1 || version.first == 1 && version.second >= 23)) {
            return check.copy(
                status = Status.OK,
                found = found,
                version = firstLine(probe.output),
                message = "Go is new enough.",
            )
        }
        return check.copy(
            found = found,
            version = firstLine(probe.output),
            message = "Go is installed but version 1.23 or newer is required.",
        )
    }

    private fun checkProjectBuildTool(projectDir: String): Check {
        val requiredFor = listOf("research4jar index without --jars", "MCP index_project without jars")
        return when {
            fileExists(Paths.get(projectDir, "pom.xml")) -> checkBuildTool(
                id = "maven",
                name = "Maven wrapper or Maven",
                projectDir = projectDir,
                wrappers = listOf("mvnw", "mvnw.cmd", "mvnw.bat"),
                fallback = "mvn",
                requiredFor = requiredFor,
                guide = mavenGuide(goos),
            )

            listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                .any { fileExists(Paths.get(projectDir, it)) } -> checkBuildTool(
                id = "gradle",
                name = "Gradle wrapper or Gradle",
                projectDir = projectDir,
                wrappers = listOf("gradlew", "gradlew.cmd", "gradlew.bat"),
                fallback = "gradle",
                requiredFor = requiredFor,
                guide = gradleGuide(goos),
            )

            else -> Check(
                id = "project-build-tool",
                name = "Project build tool",
                status = Status.WARNING,
                requiredFor = requiredFor,
                message = "No pom.xml or Gradle build file found. Pass --jars with a jar directory, glob, or comma-separated list.",
                userInstall = "No build tool is required when you pass --jars explicitly.",
                verify = listOf("research4jar index --jars <DIR|GLOB|LIST>"),
            )
        }
    }

    private fun checkBuildTool(
        id: String,
        name: String,
        projectDir: String,
        wrappers: List<String>,
        fallback: String,
        requiredFor: List<String>,
        guide: InstallGuide,
    ): Check {
        val check = Check(
            id = id,
            name = name,
            status = Status.MISSING,
            requiredFor = requiredFor,
            userInstall = guide.user,
            agentInstall = guide.agent,
            verify = guide.verify,
        )
        for (wrapper in wrappers) {
            val path = Paths.get(projectDir, wrapper)
            if (!fileExists(path)) continue
            if (isRunnableScript(path, goos)) {
                return check.copy(
                    status = Status.OK,
                    found = path.toString(),
                    message = "Project wrapper is available.",
                )
            }
            return check.copy(
                found = path.toString(),
                message = "Project wrapper exists but is not executable. Run chmod +x ${path.fileName}.",
                agentInstall = listOf("chmod +x " + shellQuote(path.toString())),
                verify = listOf("$path --version"),
            )
        }
        val found = lookup(fallback)
        if (found != null) {
            return check.copy(
                status = Status.OK,
                found = found,
                message = "$fallback is available on PATH.",
            )
        }
        return check.copy(
            message = "No project wrapper or $fallback executable was found. " +
                "Pass --jars to avoid build-tool resolution, or install $fallback.",
        )
    }

    private fun checkCommand(
        id: String,
        name: String,
        requiredFor: List<String>,
        guide: InstallGuide,
    ): Check {
        val check = Check(
            id = id,
            name = name,
            status = Status.MISSING,
            requiredFor = requiredFor,
            userInstall = guide.user,
            agentInstall = guide.agent,
            verify = guide.verify,
        )
        val found = lookup(id)
        if (found != null) {
            return check.copy(status = Status.OK, found = found, message = "$name is available.")
        }
        return check.copy(message = "$id was not found on PATH.")
    }
}

internal data class CommandResult(val output: String, val success: Boolean)

// Version banners go wherever the tool likes (java prints to stderr), so the
// probe merges stderr into stdout exactly like Go's CombinedOutput and trims.
internal fun runCommand(command: List<String>): CommandResult = try {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    CommandResult(output = output, success = process.waitFor() == 0)
} catch (_: IOException) {
    CommandResult(output = "", success = false)
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    CommandResult(output = "", success = false)
}

private val quotedJavaVersion = Regex("version \"([^\"]+)\"")
private val plainJavaVersion = Regex("""\b(?:java|javac|openjdk)\s+([0-9]+(?:\.[0-9]+)*)""")
private val goVersionPattern = Regex("""go([0-9]+)\.([0-9]+)""")

/** `version "1.8.0_412"` → 8, `javac 21.0.2` → 21; null when unparseable. */
internal fun parseJavaMajor(output: String): Int? {
    val version = quotedJavaVersion.find(output)?.groupValues?.get(1)
        ?: plainJavaVersion.find(output)?.groupValues?.get(1)
        ?: return null
    val parts = version.split(".")
    return if (parts[0] == "1" && parts.size > 1) parts[1].toIntOrNull() else parts[0].toIntOrNull()
}

/** `go version go1.23.4 darwin/arm64` → (1, 23); null when unparseable. */
internal fun parseGoVersion(output: String): Pair<Int, Int>? {
    val match = goVersionPattern.find(output) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    return major to minor
}

private fun firstLine(output: String): String {
    val trimmed = output.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.substringBefore('\n').trim()
}

private fun oneLine(output: String): String {
    val line = firstLine(output)
    return if (line.isEmpty()) "no output" else line
}

/** Maps `os.name` onto the Go GOOS values the guides switch on. */
internal fun currentGoos(): String {
    val name = System.getProperty("os.name")?.lowercase() ?: return "linux"
    return when {
        name.startsWith("mac") || name.contains("darwin") -> "darwin"
        name.startsWith("windows") -> "windows"
        else -> "linux"
    }
}

/**
 * exec.LookPath analog: the first regular, executable candidate on PATH.
 * Windows also tries the .exe/.cmd/.bat launchers and skips the exec test.
 */
internal fun lookPath(name: String, goos: String): String? {
    val pathValue = System.getenv("PATH") ?: return null
    val names = if (goos == "windows") {
        listOf("$name.exe", "$name.cmd", "$name.bat", name)
    } else {
        listOf(name)
    }
    for (dir in pathValue.split(File.pathSeparator)) {
        for (candidate in names) {
            val path = try {
                Paths.get(dir, candidate)
            } catch (_: InvalidPathException) {
                null
            } ?: continue
            if (Files.isRegularFile(path) && (goos == "windows" || Files.isExecutable(path))) {
                return path.toString()
            }
        }
    }
    return null
}

private fun fileExists(path: Path): Boolean = Files.isRegularFile(path)

// Mirrors Go's perm&0111 test on the wrapper so the chmod +x guidance
// triggers on exactly the same files; Windows scripts always count.
internal fun isRunnableScript(path: Path, goos: String): Boolean =
    goos == "windows" || hasExecuteBit(path)

/** Any execute bit set (owner/group/other), not just effective access. */
internal fun hasExecuteBit(path: Path): Boolean = try {
    Files.getPosixFilePermissions(path).any {
        it == PosixFilePermission.OWNER_EXECUTE ||
            it == PosixFilePermission.GROUP_EXECUTE ||
            it == PosixFilePermission.OTHERS_EXECUTE
    }
} catch (_: UnsupportedOperationException) {
    // Non-POSIX filesystem off Windows: settle for an effective-access check.
    Files.isExecutable(path)
} catch (_: IOException) {
    false
}

private fun shellQuote(value: String): String =
    if (value.isEmpty()) "''" else "'" + value.replace("'", "'\"'\"'") + "'"
