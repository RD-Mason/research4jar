package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Raised when no .research4jar/project.json is reachable. */
class ProjectNotFoundException : RuntimeException("no project index")

@JsonIgnoreProperties(ignoreUnknown = true)
data class PointerCoverage(
    @JsonProperty("jars_total") val jarsTotal: Int = 0,
    @JsonProperty("jars_indexed") val jarsIndexed: Int = 0,
    @JsonProperty("jars_missing") val jarsMissing: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProjectPointerData(
    @JsonProperty("schema_version") val schemaVersion: Int = 0,
    @JsonProperty("extractor_version") val extractorVersion: Int = 0,
    @JsonProperty("classpath_fingerprint") val classpathFingerprint: String = "",
    @JsonProperty("session_db_path") val sessionDbPath: String = "",
    @JsonProperty("built_at") val builtAt: Long = 0,
    @JsonProperty("coverage") val coverage: PointerCoverage = PointerCoverage(),
)

/**
 * Locates and loads .research4jar/project.json, mirroring the Go
 * querier/internal/project package: an explicit projectDir is checked
 * directly; otherwise the search walks up from the working directory.
 */
object ProjectIndex {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    fun locate(projectDir: String?): Path {
        if (!projectDir.isNullOrEmpty()) {
            val candidate = Paths.get(projectDir).toAbsolutePath().normalize()
                .resolve(".research4jar").resolve("project.json")
            if (Files.isRegularFile(candidate)) return candidate
            throw ProjectNotFoundException()
        }
        var current = Paths.get("").toAbsolutePath()
        while (true) {
            val candidate = current.resolve(".research4jar").resolve("project.json")
            if (Files.isRegularFile(candidate)) return candidate
            current = current.parent ?: throw ProjectNotFoundException()
        }
    }

    /** The project root that owns .research4jar/project.json. */
    fun root(projectDir: String?): Path = locate(projectDir).parent.parent

    fun load(path: Path): ProjectPointerData {
        val pointer = try {
            mapper.readValue(Files.newInputStream(path), ProjectPointerData::class.java)
        } catch (exception: java.io.IOException) {
            throw IllegalStateException("decode project index: ${exception.message}", exception)
        }
        require(pointer.schemaVersion == 1 || pointer.schemaVersion == 2) {
            "unsupported project schema version ${pointer.schemaVersion}"
        }
        check(pointer.sessionDbPath.isNotEmpty()) { "project index has no session_db_path" }
        return pointer
    }

    /** Locate + load + manifest inference, mirroring query.ResolveProject. */
    fun resolve(projectDir: String?, home: String?): Pair<ProjectPointerData, String> {
        val pointer = load(locate(projectDir))
        var manifestPath = inferManifestPath(pointer.sessionDbPath)
        if (!home.isNullOrEmpty()) {
            manifestPath =
                dev.research4jar.indexer.Research4JarPaths.resolve(home).manifest.toString()
        }
        return pointer to manifestPath
    }

    fun inferManifestPath(sessionDbPath: String): String {
        val sessionsDir = Paths.get(sessionDbPath).parent ?: return ""
        if (sessionsDir.fileName?.toString() == "sessions") {
            return sessionsDir.parent.resolve("manifest.db").toString()
        }
        return ""
    }
}
