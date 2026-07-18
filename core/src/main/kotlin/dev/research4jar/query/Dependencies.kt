package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * why-dependency: explains which resolved Maven dependency owns an argument
 * (class FQN, jar filename, artifact id, or coordinate). Ported from
 * querier/internal/query/dependencies.go; JSON key sets and SQL must stay
 * identical to the Go querier.
 */
data class DependencyWhyResult(
    @JsonProperty("coordinate") val coordinate: String,
    @JsonProperty("artifact") val artifact: String,
    @JsonProperty("version") val version: String,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("scope") val scope: String = "",
    @JsonProperty("direct") val direct: Boolean,
    @JsonProperty("depth") val depth: Int,
    @JsonProperty("path") val path: List<String>,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("parent") val parent: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("direct_dependency") val directDependency: String = "",
    @JsonProperty("matched_by") val matchedBy: String,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("source_jar") val sourceJar: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("source_class") val sourceClass: String = "",
)

data class DependencyWhyResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("results") val results: List<DependencyWhyResult>,
    @JsonProperty("total") val total: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

fun whyDependency(
    pointer: ProjectPointerData,
    manifestPath: String,
    projectDir: String,
    arg: String,
): DependencyWhyResponse {
    val graph = try {
        DepGraphFile.load(projectDir)
    } catch (_: DepGraphUnsupportedException) {
        throw RuntimeException(
            "no dependency provenance found; run research4jar index in a Maven project" +
                " to create .research4jar/dependencies.json",
        )
    } catch (exception: Exception) {
        throw RuntimeException(
            "load dependency provenance: ${exception.message ?: exception}",
            exception,
        )
    }
    val sources = loadManifestSources(manifestPath)
    val targets = dependencyTargets(pointer, sources, arg)
    val results = dependencyWhyResults(graph, targets)
    return DependencyWhyResponse(
        query = SymbolRequest(command = "why-dependency", arg = arg),
        results = results,
        total = results.size,
        coverage = coverageFrom(pointer),
    )
}

// Go dependencyWhyResults: dedupes matches into a map keyed by
// coordinate/matchedBy/sourceClass, then sorts. Kotlin keeps insertion order
// for equal sort keys where Go map iteration is unordered; the comparator
// covers the full dedupe key minus sourceClass, so ties are rare.
internal fun dependencyWhyResults(
    graph: Graph,
    targets: List<DependencyTarget>,
): List<DependencyWhyResult> {
    val seen = LinkedHashMap<String, DependencyWhyResult>()
    for (target in targets) {
        for (artifact in graph.artifacts) {
            if (!artifactMatchesTarget(artifact, target.coordinate)) {
                continue
            }
            val result = DependencyWhyResult(
                coordinate = artifact.coordinate,
                artifact = artifact.artifact,
                version = artifact.version,
                scope = artifact.scope,
                direct = artifact.direct,
                depth = artifact.depth,
                // Go wraps with nonNil (configprops.go); the parsed
                // Artifact.path is already never null here.
                path = artifact.path,
                parent = artifact.parent,
                directDependency = directDependencyFor(artifact),
                matchedBy = target.matchedBy,
                sourceJar = target.sourceJar,
                sourceClass = target.sourceClass,
            )
            val key = result.coordinate + "\u0000" + result.matchedBy +
                "\u0000" + result.sourceClass
            seen[key] = result
        }
    }
    return seen.values.sortedWith(dependencyWhyComparator)
}

internal data class DependencyTarget(
    val coordinate: String,
    val matchedBy: String,
    val sourceJar: String = "",
    val sourceClass: String = "",
)

internal fun dependencyTargets(
    pointer: ProjectPointerData,
    sources: List<CachedManifestRow>,
    arg: String,
): List<DependencyTarget> {
    val targets = mutableListOf<DependencyTarget>()
    if (arg.contains(".") && !arg.contains(":") && !arg.endsWith(".jar")) {
        targets += classDependencyTargets(pointer, sources, arg)
    }
    for (source in sources) {
        when {
            source.coordinate.isNotEmpty() &&
                (
                    artifactMatchesArg(source.coordinate, arg) ||
                        coordinateArtifactId(source.coordinate) == arg
                    ) -> {
                targets += DependencyTarget(
                    coordinate = source.coordinate,
                    matchedBy = coordinateMatchReason(source.coordinate, arg),
                    sourceJar = source.source,
                )
            }
            source.filename == arg || filepathBase(source.filename) == arg -> {
                targets += DependencyTarget(
                    coordinate = source.coordinate,
                    matchedBy = "jar_filename",
                    sourceJar = source.source,
                )
            }
        }
    }
    if (targets.isEmpty() && arg.contains(":")) {
        targets += DependencyTarget(coordinate = arg, matchedBy = "coordinate")
    }
    return dedupeDependencyTargets(targets)
}

private fun classDependencyTargets(
    pointer: ProjectPointerData,
    sources: List<CachedManifestRow>,
    classFqn: String,
): List<DependencyTarget> = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    val shardIds = session.query(
        "SELECT source_shard_id FROM classes WHERE fqn = ? ORDER BY source_shard_id",
        listOf(classFqn),
    ) { rows -> rows.mapRows { it.getString(1) } }
    val byShard = ManifestCache.mapSessionRows(session, sources, shardIds)
    val targets = mutableListOf<DependencyTarget>()
    for (shardId in shardIds) {
        val source = byShard[shardId] ?: continue
        if (source.coordinate.isEmpty()) {
            continue
        }
        targets += DependencyTarget(
            coordinate = source.coordinate,
            matchedBy = "class",
            sourceJar = source.source,
            sourceClass = classFqn,
        )
    }
    targets
}

/** Go manifestSource carries the same four columns as [CachedManifestRow]. */
internal fun loadManifestSources(manifestPath: String): List<CachedManifestRow> =
    ManifestCache.loadRows(manifestPath)

private fun artifactMatchesTarget(artifact: Artifact, target: String): Boolean {
    if (target.isEmpty()) {
        return false
    }
    return artifactMatchesArg(artifact.coordinate, target) ||
        artifact.artifact == target ||
        artifact.name == target ||
        artifact.coordinate == target
}

internal fun artifactMatchesArg(coordinate: String, arg: String): Boolean {
    if (coordinate == arg) {
        return true
    }
    val parts = coordinate.split(":")
    return parts.size >= 2 && parts[0] + ":" + parts[1] == arg
}

internal fun coordinateArtifactId(coordinate: String): String {
    val parts = coordinate.split(":")
    if (parts.size < 2) {
        return ""
    }
    return parts[1]
}

private fun coordinateMatchReason(coordinate: String, arg: String): String =
    if (coordinateArtifactId(coordinate) == arg) "artifact" else "coordinate"

private fun directDependencyFor(artifact: Artifact): String {
    if (artifact.path.isNotEmpty()) {
        return artifact.path[0]
    }
    if (artifact.direct) {
        return artifact.coordinate
    }
    return ""
}

internal fun dedupeDependencyTargets(targets: List<DependencyTarget>): List<DependencyTarget> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<DependencyTarget>()
    for (target in targets) {
        val key = target.coordinate + "\u0000" + target.matchedBy + "\u0000" + target.sourceClass
        if (!seen.add(key)) {
            continue
        }
        result += target
    }
    return result
}

private val dependencyWhyComparator =
    compareBy<DependencyWhyResult>({ it.depth }, { it.coordinate }, { it.matchedBy })

/**
 * Go filepath.Base for slash-separated paths: manifest jar filenames and walk
 * paths on the platforms the e2e suite runs on. "" maps to "." exactly as in
 * Go, which downstream term lists rely on.
 */
internal fun filepathBase(path: String): String {
    if (path.isEmpty()) {
        return "."
    }
    var trimmed = path
    while (trimmed.isNotEmpty() && trimmed.endsWith('/')) {
        trimmed = trimmed.dropLast(1)
    }
    val index = trimmed.lastIndexOf('/')
    val base = if (index >= 0) trimmed.substring(index + 1) else trimmed
    return if (base.isEmpty()) "/" else base
}
