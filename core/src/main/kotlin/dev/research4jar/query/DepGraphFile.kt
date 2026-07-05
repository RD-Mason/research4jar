package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths

/**
 * Reads Maven dependency provenance captured for a project, ported from the
 * file-reading side of querier/internal/depgraph (Go). The index facts still
 * come from jar bytes; this file explains why a jar is present on the
 * classpath. Capturing (mvn dependency:tree + TGF parsing) stays with the Go
 * indexer for now and is not ported here.
 */

/** Message of the Go depgraph.ErrUnsupported sentinel error. */
const val DEP_GRAPH_UNSUPPORTED_MESSAGE =
    "dependency provenance is currently only available for Maven projects"

/** Raised where the Go code returns depgraph.ErrUnsupported. */
class DepGraphUnsupportedException : RuntimeException(DEP_GRAPH_UNSUPPORTED_MESSAGE)

/** Describes one resolved Maven node and its path from the project. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Artifact(
    @JsonProperty("coordinate") val coordinate: String = "",
    @JsonProperty("artifact") val artifact: String = "",
    @JsonProperty("group") val group: String = "",
    @JsonProperty("name") val name: String = "",
    @JsonProperty("version") val version: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("type") val type: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("classifier") val classifier: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("scope") val scope: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("parent") val parent: String = "",
    @JsonProperty("direct") val direct: Boolean = false,
    @JsonProperty("depth") val depth: Int = 0,
    /** Go nil decodes to an empty list; consumers treat both the same. */
    @JsonProperty("path") val path: List<String> = emptyList(),
)

/** The graph stored at .research4jar/dependencies.json. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Graph(
    @JsonProperty("schema_version") val schemaVersion: Int = 0,
    @JsonProperty("build_tool") val buildTool: String = "",
    @JsonProperty("generated_at") val generatedAt: Long = 0,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("project_root") val projectRoot: String = "",
    @JsonProperty("artifacts") val artifacts: List<Artifact> = emptyList(),
)

object DepGraphFile {
    const val SCHEMA_VERSION = 1
    private const val FILE_NAME = "dependencies.json"

    private val mapper: ObjectMapper = jacksonObjectMapper()

    /** The dependency provenance file for a project root. */
    fun path(projectDir: String): String =
        Paths.get(projectDir, ".research4jar", FILE_NAME).toString()

    /** Whether a captured provenance file is present. */
    fun exists(projectDir: String): Boolean =
        Files.isRegularFile(Paths.get(path(projectDir)))

    /**
     * Reads a previously captured graph. A missing file raises
     * [DepGraphUnsupportedException], mirroring the Go Load returning
     * ErrUnsupported on os.IsNotExist.
     */
    fun load(projectDir: String): Graph {
        val input = try {
            Files.newInputStream(Paths.get(path(projectDir)))
        } catch (_: NoSuchFileException) {
            throw DepGraphUnsupportedException()
        }
        val graph = input.use { mapper.readValue(it, Graph::class.java) }
        if (graph.schemaVersion != SCHEMA_VERSION) {
            throw RuntimeException(
                "unsupported dependency graph schema version ${graph.schemaVersion}",
            )
        }
        return graph
    }
}
