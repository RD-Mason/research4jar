package dev.research4jar.query

import dev.research4jar.indexer.store.AtomicFiles
import dev.research4jar.registry.GoJson
import dev.research4jar.registry.goQuote
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

/**
 * Captures and stores Maven dependency provenance for a project, ported from
 * the capture/write half of querier/internal/depgraph (Go); the read side
 * lives in [DepGraphFile]. The index facts still come from jar bytes; this
 * file explains why a jar is present on the classpath. Error strings must
 * stay byte-identical to the Go implementation (including Go's %q quoting),
 * except where a message embeds a JVM-specific underlying error.
 */
object DepGraphCapture {

    /**
     * Runs Maven's dependency tree in TGF form and returns a graph. It is
     * best-effort for callers: unsupported build tools raise
     * [DepGraphUnsupportedException] so indexing can continue without
     * provenance (Go depgraph.Capture returning ErrUnsupported).
     */
    fun capture(
        projectDir: String,
        buildArgs: List<String> = emptyList(),
        noSnapshotUpdates: Boolean = false,
    ): Graph {
        val absolute = Paths.get(projectDir).toAbsolutePath().normalize()
        if (!Files.isRegularFile(absolute.resolve("pom.xml"))) {
            throw DepGraphUnsupportedException()
        }
        val outputPath = Files.createTempFile("research4jar-dependency-tree-", ".tgf")
        try {
            val result = try {
                Classpath.runBuildCommand(
                    absolute, "mvnw", "mvn",
                    listOf(
                        "-q", "-Dscope=runtime", "dependency:tree",
                        "-DoutputType=tgf", "-DoutputFile=$outputPath",
                    ) + Classpath.mavenSnapshotArgs(noSnapshotUpdates) + buildArgs,
                )
            } catch (exception: Exception) {
                // Command could not start (e.g. mvn missing); Go reports the
                // exec error with an empty combined-output tail.
                throw RuntimeException(
                    "maven dependency tree failed: ${exception.message ?: exception}\n",
                )
            }
            if (result.exitCode != 0) {
                throw RuntimeException(
                    "maven dependency tree failed: exit status ${result.exitCode}\n" +
                        Classpath.tail(result.output),
                )
            }
            val reader = try {
                Files.newBufferedReader(outputPath, Charsets.UTF_8)
            } catch (exception: Exception) {
                throw RuntimeException(
                    "read maven dependency tree: ${exception.message ?: exception}",
                )
            }
            return reader.use { graphFromTgf(it, absolute.toString()) }
        } finally {
            Files.deleteIfExists(outputPath)
        }
    }

    /** Parses TGF text into a project-stamped graph (shared with the merged
     *  classpath+tree discovery, which already holds the TGF in memory). */
    fun graphFromTgf(reader: Reader, projectRoot: String): Graph =
        parseTGF(reader).copy(
            projectRoot = projectRoot,
            generatedAt = Instant.now().epochSecond,
        )

    /**
     * One graph from one-TGF-per-reactor-module sections: artifacts are
     * deduplicated by coordinate keeping the shallowest occurrence, so each
     * module coordinate stays a depth-0 root even where sibling modules
     * also list it as a dependency, and shared external artifacts keep
     * their shortest path. Section order is reactor order; ties keep the
     * first section's entry. Sorting matches [parseTGF].
     */
    fun graphFromTgfSections(sections: List<String>, projectRoot: String): Graph {
        require(sections.isNotEmpty()) { "maven dependency tree was empty" }
        val graphs = sections.map { parseTGF(java.io.StringReader(it)) }
        if (graphs.size == 1) {
            return graphs[0].copy(
                projectRoot = projectRoot,
                generatedAt = Instant.now().epochSecond,
            )
        }
        val byCoordinate = LinkedHashMap<String, Artifact>()
        for (graph in graphs) {
            for (artifact in graph.artifacts) {
                val existing = byCoordinate[artifact.coordinate]
                if (existing == null || artifact.depth < existing.depth) {
                    byCoordinate[artifact.coordinate] = artifact
                }
            }
        }
        val artifacts = byCoordinate.values
            .sortedWith(compareBy({ it.depth }, { it.coordinate }))
        return Graph(
            schemaVersion = DepGraphFile.SCHEMA_VERSION,
            buildTool = "maven",
            generatedAt = Instant.now().epochSecond,
            projectRoot = projectRoot,
            artifacts = artifacts,
        )
    }

    /**
     * Stores the graph under .research4jar/dependencies.json: Go
     * json.MarshalIndent (two-space, HTML-escaped) plus a trailing newline,
     * written to a same-directory temp file and atomically renamed.
     */
    fun write(projectDir: String, graph: Graph) {
        val target = Paths.get(DepGraphFile.path(projectDir))
        val directory = target.parent
        Files.createDirectories(directory)
        var stamped = graph.copy(schemaVersion = DepGraphFile.SCHEMA_VERSION)
        if (stamped.generatedAt == 0L) {
            stamped = stamped.copy(generatedAt = Instant.now().epochSecond)
        }
        val formatted = GoJson.marshalIndent(stamped) + "\n"
        val temporary = Files.createTempFile(directory, ".dependencies.json.", ".tmp")
        try {
            Files.write(temporary, formatted.toByteArray(Charsets.UTF_8))
            // fsync + atomic rename, mirroring Go's temp+Sync+Close+Rename.
            AtomicFiles.commit(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private class Node(val id: String, val artifact: Artifact)

    /** Parses Maven dependency:tree -DoutputType=tgf output (Go ParseTGF). */
    fun parseTGF(reader: Reader): Graph {
        val nodes = HashMap<String, Node>()
        val parents = HashMap<String, String>()
        var line = 0
        var inEdges = false
        reader.buffered().forEachLine { rawLine ->
            line++
            val text = rawLine.trim()
            if (text.isEmpty()) return@forEachLine
            if (text == "#") {
                inEdges = true
                return@forEachLine
            }
            if (!inEdges) {
                val cut = text.indexOf(' ')
                val id = if (cut < 0) "" else text.substring(0, cut)
                val raw = if (cut < 0) "" else text.substring(cut + 1)
                if (cut < 0 || id.isEmpty() || raw.isEmpty()) {
                    throw RuntimeException("line $line: malformed TGF node ${goQuote(text)}")
                }
                val artifact = try {
                    parseMavenCoordinate(raw)
                } catch (exception: IllegalArgumentException) {
                    throw RuntimeException("line $line: ${exception.message}")
                }
                nodes[id] = Node(id, artifact)
                return@forEachLine
            }
            val parts = text.split(WHITESPACE).filter(String::isNotEmpty)
            if (parts.size < 2) {
                throw RuntimeException("line $line: malformed TGF edge ${goQuote(text)}")
            }
            val (parent, child) = parts
            if (parents[child].isNullOrEmpty()) {
                parents[child] = parent
            }
        }
        if (nodes.isEmpty()) {
            throw RuntimeException("maven dependency tree was empty")
        }

        val rootSet = nodes.keys.filterTo(HashSet()) { parents[it].isNullOrEmpty() }

        val artifacts = ArrayList<Artifact>(nodes.size)
        for ((id, item) in nodes) {
            var artifact = item.artifact
            val parentId = parents[id] ?: ""
            if (parentId.isNotEmpty()) {
                artifact = artifact.copy(parent = nodes[parentId]?.artifact?.coordinate ?: "")
            }
            val path = pathFor(id, parents, nodes, rootSet)
            artifact = artifact.copy(
                direct = parentId.isNotEmpty() && parentId in rootSet,
                path = path,
                depth = path.size,
            )
            artifacts += artifact
        }
        artifacts.sortWith(compareBy({ it.depth }, { it.coordinate }))
        return Graph(
            schemaVersion = DepGraphFile.SCHEMA_VERSION,
            buildTool = "maven",
            artifacts = artifacts,
        )
    }

    /**
     * Accepts the 4/5/6-part TGF node coordinates Maven emits:
     * group:name:type:version[,:scope] and group:name:type:classifier:version:scope.
     */
    private fun parseMavenCoordinate(raw: String): Artifact {
        val parts = raw.trim().split(":")
        if (parts.size != 4 && parts.size != 5 && parts.size != 6) {
            throw IllegalArgumentException("${goQuote(raw)} is not a Maven coordinate")
        }
        var artifact = Artifact(
            group = parts[0],
            name = parts[1],
            type = parts[2],
        )
        artifact = when (parts.size) {
            4 -> artifact.copy(version = parts[3])
            5 -> artifact.copy(version = parts[3], scope = parts[4])
            else -> artifact.copy(classifier = parts[3], version = parts[4], scope = parts[5])
        }
        if (artifact.group.isEmpty() || artifact.name.isEmpty() || artifact.version.isEmpty()) {
            throw IllegalArgumentException("${goQuote(raw)} is not a Maven coordinate")
        }
        val artifactId = "${artifact.group}:${artifact.name}"
        return artifact.copy(
            artifact = artifactId,
            coordinate = "$artifactId:${artifact.version}",
        )
    }

    // Walks parent edges from the node up to (exclusive) the root, returning
    // the root-to-node coordinate path. Roots themselves get an empty path.
    private fun pathFor(
        id: String,
        parents: Map<String, String>,
        nodes: Map<String, Node>,
        rootSet: Set<String>,
    ): List<String> {
        val reversed = mutableListOf<String>()
        var current = id
        while (current.isNotEmpty()) {
            if (current in rootSet) break
            val item = nodes[current] ?: break
            reversed += item.artifact.coordinate
            current = parents[current] ?: ""
        }
        return reversed.asReversed().toList()
    }

    private val WHITESPACE = Regex("\\s+")
}
