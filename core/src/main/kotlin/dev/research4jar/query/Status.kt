package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import dev.research4jar.indexer.Research4JarVersions
import dev.research4jar.runtime.WorkingDirectoryContext
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class DependencyProvenanceStatus(
    @JsonProperty("available") val available: Boolean,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("path") val path: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("build_tool") val buildTool: String = "",
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("artifacts") val artifacts: Int = 0,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("generated_at") val generatedAt: Long = 0,
)

data class ClasspathCheckStatus(
    @JsonProperty("checked") val checked: Boolean,
    @JsonProperty("up_to_date") val upToDate: Boolean = false,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("current_fingerprint") val currentFingerprint: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("indexed_fingerprint") val indexedFingerprint: String = "",
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("jars_resolved") val jarsResolved: Int = 0,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("jars_unique") val jarsUnique: Int = 0,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("jars_missing") val jarsMissing: List<String> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("extractor_version") val extractorVersion: Int = 0,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("dependency_resolution") val dependencyResolution: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("error") val error: String = "",
)

data class ProjectStatusResponse(
    @JsonProperty("indexed") val indexed: Boolean = false,
    @JsonProperty("project_dir") val projectDir: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("project_index_path") val projectIndexPath: String = "",
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("schema_version") val schemaVersion: Int = 0,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("extractor_version") val extractorVersion: Int = 0,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("classpath_fingerprint") val classpathFingerprint: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("session_db_path") val sessionDbPath: String = "",
    @JsonProperty("session_db_exists") val sessionDbExists: Boolean = false,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("built_at") val builtAt: Long = 0,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("built_at_utc") val builtAtUtc: String = "",
    @JsonProperty("coverage") val coverage: Coverage,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("manifest_path") val manifestPath: String = "",
    @JsonProperty("manifest_exists") val manifestExists: Boolean = false,
    @JsonProperty("dependency_provenance") val dependencyProvenance: DependencyProvenanceStatus,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("classpath_check") val classpathCheck: ClasspathCheckStatus? = null,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("next_steps") val nextSteps: List<String> = emptyList(),
)

fun projectStatus(
    projectDir: String?,
    home: String?,
    checkClasspath: Boolean = false,
    buildArgs: List<String> = emptyList(),
    noSnapshotUpdates: Boolean = false,
): ProjectStatusResponse {
    val projectPath = try {
        ProjectIndex.locate(projectDir)
    } catch (_: ProjectNotFoundException) {
        val root = statusProjectDir(projectDir)
        var status = ProjectStatusResponse(
            projectDir = root,
            coverage = Coverage(0, 0, emptyList(), 0),
            dependencyProvenance = dependencyProvenanceStatus(root),
            classpathCheck = if (checkClasspath) {
                checkClasspathStatus(root, "", buildArgs, noSnapshotUpdates)
            } else {
                null
            },
        )
        status = status.copy(nextSteps = nextStepsForStatus(status, home ?: ""))
        return status
    }

    val pointer = ProjectIndex.load(projectPath)
    val root = projectPath.parent.parent.toString()
    var manifestPath = ProjectIndex.inferManifestPath(pointer.sessionDbPath)
    if (!home.isNullOrEmpty()) {
        manifestPath = dev.research4jar.indexer.Research4JarPaths.resolve(home).manifest.toString()
    }

    var status = ProjectStatusResponse(
        indexed = true,
        projectDir = root,
        projectIndexPath = projectPath.toString(),
        schemaVersion = pointer.schemaVersion,
        extractorVersion = pointer.extractorVersion,
        classpathFingerprint = pointer.classpathFingerprint,
        sessionDbPath = pointer.sessionDbPath,
        sessionDbExists = regularFile(pointer.sessionDbPath),
        builtAt = pointer.builtAt,
        builtAtUtc = if (pointer.builtAt > 0) {
            DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochSecond(pointer.builtAt))
                .replace("+00:00", "Z")
        } else {
            ""
        },
        coverage = coverageFrom(pointer),
        manifestPath = manifestPath,
        manifestExists = regularFile(manifestPath),
        dependencyProvenance = dependencyProvenanceStatus(root),
        classpathCheck = if (checkClasspath) {
            checkClasspathStatus(root, pointer.classpathFingerprint, buildArgs, noSnapshotUpdates)
        } else {
            null
        },
    )
    status = status.copy(nextSteps = nextStepsForStatus(status, home ?: ""))
    return status
}

private fun statusProjectDir(projectDir: String?): String =
    if (!projectDir.isNullOrEmpty()) {
        WorkingDirectoryContext.resolve(projectDir).toString()
    } else {
        WorkingDirectoryContext.current().toString()
    }

private fun dependencyProvenanceStatus(projectDir: String): DependencyProvenanceStatus {
    val path = DepGraphFile.path(projectDir)
    val graph = try {
        DepGraphFile.load(projectDir)
    } catch (_: Exception) {
        return DependencyProvenanceStatus(available = false, path = path)
    }
    return DependencyProvenanceStatus(
        available = true,
        path = path,
        buildTool = graph.buildTool,
        artifacts = graph.artifacts.size,
        generatedAt = graph.generatedAt,
    )
}

private fun nextStepsForStatus(status: ProjectStatusResponse, home: String): List<String> {
    val indexCommand = statusCommand("research4jar index", status.projectDir, home)
    val doctorCommand = statusCommand("research4jar doctor", status.projectDir, "")
    if (!status.indexed) {
        return listOf(
            "Create the project index: $indexCommand",
            "If indexing fails, check the environment: $doctorCommand",
        )
    }

    val steps = mutableListOf<String>()
    val missingStores = mutableListOf<String>()
    if (!status.sessionDbExists) missingStores += "session database"
    if (!status.manifestExists) missingStores += "manifest database"
    if (missingStores.isNotEmpty()) {
        steps += "Rebuild the index because the " + missingStores.joinToString(" and ") +
            " is missing: " + indexCommand
    }
    if (status.coverage.jarsTotal > 0 && status.coverage.jarsIndexed < status.coverage.jarsTotal) {
        steps += "Some jars were not indexed; inspect coverage.jars_missing, " +
            "fix unreadable jars, then rerun: " + indexCommand
    }
    if (!status.dependencyProvenance.available) {
        steps += "Dependency provenance is unavailable; Maven projects can recreate it with: " +
            indexCommand
    }
    val check = status.classpathCheck
    if (check != null && check.checked) {
        if (check.error.isNotEmpty()) {
            steps += "Classpath freshness check failed: " + check.error
        } else if (!check.upToDate) {
            steps += "Runtime classpath changed since the last index; refresh it with: " +
                indexCommand
        }
    }
    if (steps.isEmpty()) {
        steps += "Index is ready; try: research4jar dep precise '<import|class|coordinate|jar>'"
    }
    return steps
}

private fun statusCommand(command: String, projectDir: String, home: String): String {
    var result = command
    if (projectDir.isNotEmpty()) result += " --project-dir \"$projectDir\""
    if (home.isNotEmpty()) result += " --home \"$home\""
    return result
}

private fun checkClasspathStatus(
    projectDir: String,
    indexedFingerprint: String,
    buildArgs: List<String> = emptyList(),
    noSnapshotUpdates: Boolean = false,
): ClasspathCheckStatus {
    val base = ClasspathCheckStatus(
        checked = true,
        indexedFingerprint = indexedFingerprint,
        extractorVersion = Research4JarVersions.EXTRACTOR,
    )
    val jars = try {
        Classpath.discover(projectDir, buildArgs, noSnapshotUpdates)
    } catch (exception: RuntimeException) {
        return base.copy(error = exception.message ?: "classpath discovery failed")
    }
    if (jars.isEmpty()) {
        return base.copy(
            dependencyResolution = "build_tool",
            error = "build tool resolved an empty runtime classpath",
        )
    }

    val seenHashes = HashSet<String>()
    val shardIds = mutableListOf<String>()
    val missing = mutableListOf<String>()
    for (jar in jars) {
        val digest = try {
            fileSha256(jar)
        } catch (_: java.io.IOException) {
            missing += Paths.get(jar).fileName.toString()
            continue
        }
        if (!seenHashes.add(digest)) continue
        shardIds += "$digest@${Research4JarVersions.EXTRACTOR}"
    }
    if (missing.isNotEmpty()) {
        return base.copy(
            dependencyResolution = "build_tool",
            jarsResolved = jars.size,
            jarsUnique = shardIds.size,
            jarsMissing = missing,
            error = "some runtime classpath jars could not be read",
        )
    }
    val current = sessionFingerprint(shardIds)
    return base.copy(
        upToDate = indexedFingerprint.isNotEmpty() && current == indexedFingerprint,
        currentFingerprint = current,
        dependencyResolution = "build_tool",
        jarsResolved = jars.size,
        jarsUnique = shardIds.size,
    )
}

/**
 * The classpath fingerprint contract shared with both session writers:
 * sha256 over the sorted shard ids joined by newlines, first 16 hex chars.
 */
fun sessionFingerprint(shardIds: List<String>): String {
    val joined = shardIds.sorted().joinToString("\n")
    return sha256Hex(joined.toByteArray(Charsets.UTF_8)).substring(0, 16)
}

fun fileSha256(path: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(Paths.get(path)).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

private fun regularFile(path: String): Boolean =
    path.isNotEmpty() && Files.isRegularFile(Paths.get(path))
