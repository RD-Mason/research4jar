package dev.research4jar.maven

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.research4jar.indexer.runIndexPipeline
import dev.research4jar.query.Artifact
import dev.research4jar.query.DepGraphFile
import dev.research4jar.query.Graph
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject

/**
 * `mvn dev.research4jar:research4jar-maven-plugin:index` — zero-install
 * onboarding: Maven pulls the plugin from the repository, the runtime
 * classpath and dependency provenance come straight from the already-resolved
 * project (no second Maven invocation, no dependency:tree run), and jar
 * extraction runs in-process.
 */
@Mojo(
    name = "index",
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    defaultPhase = LifecyclePhase.NONE,
    threadSafe = true,
)
class IndexMojo : AbstractMojo() {
    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    private lateinit var project: MavenProject

    /** Override the Research4Jar data home (defaults to the user data dir). */
    @Parameter(property = "research4jar.home")
    private var home: String? = null

    override fun execute() {
        val runtimeScopes = setOf(org.apache.maven.artifact.Artifact.SCOPE_COMPILE, org.apache.maven.artifact.Artifact.SCOPE_RUNTIME)
        val artifacts = project.artifacts
            .filter { it.scope in runtimeScopes }
            .filter { it.file != null && it.file.name.endsWith(".jar") }
        if (artifacts.isEmpty()) {
            throw MojoExecutionException(
                "resolved an empty runtime classpath; nothing to index",
            )
        }

        val projectDir = project.basedir.toPath()
        val statistics = try {
            runIndexPipeline(
                jars = artifacts.joinToString(",") { it.file.absolutePath },
                projectDir = projectDir,
                home = home,
            )
        } catch (exception: Exception) {
            throw MojoExecutionException("research4jar index failed: ${exception.message}", exception)
        }

        writeProvenance(artifacts)

        log.info(
            "research4jar: indexed ${statistics.jars_indexed}/${statistics.jars_total} jars " +
                "(${statistics.jars_newly_indexed} new, ${statistics.jars_skipped} cached)" +
                if (statistics.jars_missing.isEmpty()) "" else ", missing: ${statistics.jars_missing}",
        )
        log.info(
            "research4jar: MCP setup: claude mcp add research4jar -- research4jar mcp " +
                "(CLI install: https://github.com/RD-Mason/research4jar)",
        )
    }

    /**
     * Builds the dependencies.json graph from Maven's resolved artifacts.
     * Artifact.dependencyTrail is root → ... → artifact as
     * groupId:artifactId:type[:classifier]:version strings; the path drops
     * the root project node, matching the TGF capture semantics.
     */
    private fun writeProvenance(artifacts: List<org.apache.maven.artifact.Artifact>) {
        val rootCoordinate = "${project.groupId}:${project.artifactId}:${project.version}"
        val graphArtifacts = mutableListOf(
            Artifact(
                coordinate = rootCoordinate,
                artifact = "${project.groupId}:${project.artifactId}",
                group = project.groupId,
                name = project.artifactId,
                version = project.version,
                direct = false,
                depth = 0,
                path = emptyList(),
            ),
        )
        for (artifact in artifacts) {
            val trail = artifact.dependencyTrail ?: emptyList()
            // trail[0] is the root project; convert intermediate entries to
            // group:artifact:version coordinates.
            val path = trail.drop(1).map { trailCoordinate(it) }
            val coordinate = "${artifact.groupId}:${artifact.artifactId}:${artifact.version}"
            graphArtifacts += Artifact(
                coordinate = coordinate,
                artifact = "${artifact.groupId}:${artifact.artifactId}",
                group = artifact.groupId,
                name = artifact.artifactId,
                version = artifact.version,
                type = artifact.type ?: "",
                classifier = artifact.classifier ?: "",
                scope = artifact.scope ?: "",
                parent = if (path.size >= 2) path[path.size - 2] else rootCoordinate,
                direct = path.size == 1,
                depth = path.size,
                path = path,
            )
        }
        graphArtifacts.sortWith(compareBy({ it.depth }, { it.coordinate }))
        val graph = Graph(
            schemaVersion = DepGraphFile.SCHEMA_VERSION,
            buildTool = "maven",
            artifacts = graphArtifacts,
        )

        val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
        val directory = projectDirDotResearch4jar()
        Files.createDirectories(directory)
        val stamped = if (graph.generatedAt == 0L) {
            graph.copy(generatedAt = System.currentTimeMillis() / 1000)
        } else {
            graph
        }
        val formatted = mapper.writeValueAsBytes(stamped) + '\n'.code.toByte()
        val temporary = Files.createTempFile(directory, ".dependencies.json.", ".tmp")
        try {
            Files.write(temporary, formatted)
            Files.move(
                temporary, directory.resolve("dependencies.json"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun projectDirDotResearch4jar() =
        Paths.get(project.basedir.absolutePath, ".research4jar")

    /** groupId:artifactId:type[:classifier]:version → group:artifact:version. */
    private fun trailCoordinate(entry: String): String {
        val parts = entry.split(":")
        return if (parts.size >= 4) {
            "${parts[0]}:${parts[1]}:${parts[parts.size - 1]}"
        } else {
            entry
        }
    }
}
