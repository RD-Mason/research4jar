package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * dep precise / artifact / class origin queries, ported from
 * querier/internal/query/dependency_precise.go. JSON key sets and SQL retain
 * compatibility; source usages use a bounded incremental in-process index.
 */
data class DependencyOrigin(
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("fqn") val fqn: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("package") val packageName: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("coordinate") val coordinate: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("jar_filename") val jarFilename: String = "",
    @JsonProperty("source_jar") val sourceJar: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("shard_id") val shardId: String = "",
    @JsonProperty("match_reason") val matchReason: String = "",
)

data class SourceUsage(
    @JsonProperty("path") val path: String,
    @JsonProperty("line") val line: Int,
    @JsonProperty("match") val match: String,
    @JsonProperty("text") val text: String,
)

data class DependencyPreciseResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("input_kind") val inputKind: String,
    @JsonProperty("normalized") val normalized: String,
    @JsonProperty("origins") val origins: List<DependencyOrigin>,
    @JsonProperty("total") val total: Int,
    /** Go leaves this nil (JSON null) when the graph could not be loaded. */
    @JsonProperty("dependencies") val dependencies: List<DependencyWhyResult>? = null,
    @JsonProperty("dependencies_total") val dependenciesTotal: Int = 0,
    @JsonProperty("dependency_graph_available") val dependencyGraphAvailable: Boolean = true,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("dependency_graph_error") val dependencyGraphError: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("source_usage_terms") val sourceUsageTerms: List<String> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("source_usages") val sourceUsages: List<SourceUsage> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("source_usages_has_more") val sourceUsagesHasMore: Boolean = false,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("source_usages_truncated_reason") val sourceUsagesTruncatedReason: String = "",
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("source_usage_error") val sourceUsageError: String = "",
    @JsonProperty("coverage") val coverage: Coverage,
)

private data class DependencyLookup(
    val original: String,
    val command: String,
    val kind: String,
    val normalized: String,
    val classTerm: String = "",
    val packageTerm: String = "",
    val artifactTerm: String = "",
    val fallbackArtifact: Boolean = false,
    val usageTerms: List<String> = emptyList(),
)

/**
 * Resolves a user-facing dependency question, such as "which jar owns this
 * import?", "which dependency brought this jar in?", and "where does this
 * project consume it?". It combines the jar fact index, dependency
 * provenance, and a bounded source/build-file grep.
 */
fun dependencyPrecise(
    pointer: ProjectPointerData,
    manifestPath: String,
    projectDir: String,
    arg: String,
    pageSize: Int,
    includeSourceUsages: Boolean,
): DependencyPreciseResponse {
    val lookup = parseDependencyLookup(arg)
    return dependencyPrecise(pointer, manifestPath, projectDir, lookup, pageSize, includeSourceUsages)
}

fun artifactPrecise(
    pointer: ProjectPointerData,
    manifestPath: String,
    projectDir: String,
    arg: String,
    pageSize: Int,
    includeSourceUsages: Boolean,
): DependencyPreciseResponse {
    val parsed = parseDependencyLookup(arg)
    val lookup = parsed.copy(
        command = "artifact",
        kind = "artifact",
        artifactTerm = parsed.normalized,
        classTerm = "",
        packageTerm = "",
        fallbackArtifact = false,
    )
    return dependencyPrecise(pointer, manifestPath, projectDir, lookup, pageSize, includeSourceUsages)
}

fun classPrecise(
    pointer: ProjectPointerData,
    manifestPath: String,
    projectDir: String,
    arg: String,
    pageSize: Int,
    includeSourceUsages: Boolean,
): DependencyPreciseResponse {
    val parsed = parseDependencyLookup(arg)
    val lookup = parsed.copy(
        command = "class",
        kind = "class",
        classTerm = parsed.classTerm.ifEmpty { parsed.normalized },
        artifactTerm = "",
        packageTerm = "",
        fallbackArtifact = false,
    )
    return dependencyPrecise(pointer, manifestPath, projectDir, lookup, pageSize, includeSourceUsages)
}

private fun dependencyPrecise(
    pointer: ProjectPointerData,
    manifestPath: String,
    projectDir: String,
    lookup: DependencyLookup,
    pageSize: Int,
    includeSourceUsages: Boolean,
): DependencyPreciseResponse {
    requirePageSize(pageSize)
    val sources = loadManifestSources(manifestPath)
    val origins = dependencyOrigins(pointer, sources, lookup, pageSize)

    var dependencies: List<DependencyWhyResult>? = null
    var dependencyGraphAvailable = true
    var dependencyGraphError = ""
    val graph = try {
        DepGraphFile.load(projectDir)
    } catch (exception: Exception) {
        dependencyGraphAvailable = false
        val message = exception.message ?: exception.toString()
        dependencyGraphError = if (message.contains(DEP_GRAPH_UNSUPPORTED_MESSAGE)) {
            "no dependency provenance found; run research4jar index in a Maven project" +
                " to create .research4jar/dependencies.json"
        } else {
            message
        }
        null
    }
    if (graph != null) {
        var targets = dependencyTargetsFromOrigins(origins)
        if (targets.isEmpty() && (lookup.fallbackArtifact || lookup.artifactTerm.isNotEmpty())) {
            targets = dependencyTargets(pointer, sources, lookup.normalized)
        }
        dependencies = dependencyWhyResults(graph, targets)
    }

    var sourceUsageTerms: List<String> = emptyList()
    var sourceUsages: List<SourceUsage> = emptyList()
    var sourceUsagesHasMore = false
    var sourceUsagesTruncatedReason = ""
    var sourceUsageError = ""
    val usageQuery = sourceUsageQueryFor(lookup, origins)
    if (includeSourceUsages && usageQuery.all.isNotEmpty()) {
        try {
            val scan = SourceUsageIndexes.find(projectDir, usageQuery.highSignal, usageQuery.broad, pageSize)
            sourceUsageTerms = usageQuery.all
            sourceUsages = scan.usages
            sourceUsagesHasMore = scan.hasMore
            sourceUsagesTruncatedReason = scan.truncatedReason
        } catch (exception: Exception) {
            sourceUsageError = exception.message ?: exception.toString()
        }
    }

    return DependencyPreciseResponse(
        query = SymbolRequest(command = lookup.command, arg = lookup.original),
        inputKind = lookup.kind,
        normalized = lookup.normalized,
        origins = origins,
        total = origins.size,
        dependencies = dependencies,
        dependenciesTotal = dependencies?.size ?: 0,
        dependencyGraphAvailable = dependencyGraphAvailable,
        dependencyGraphError = dependencyGraphError,
        sourceUsageTerms = sourceUsageTerms,
        sourceUsages = sourceUsages,
        sourceUsagesHasMore = sourceUsagesHasMore,
        sourceUsagesTruncatedReason = sourceUsagesTruncatedReason,
        sourceUsageError = sourceUsageError,
        coverage = coverageFrom(pointer),
    )
}

private fun parseDependencyLookup(arg: String): DependencyLookup {
    val original = arg.trim()
    var normalized = original.removeSuffix(";").trim()
    normalized = normalized.removePrefix("import ").trim()

    var staticImport = false
    if (normalized.startsWith("static ")) {
        staticImport = true
        normalized = normalized.removePrefix("static ").trim()
    }
    if (normalized.startsWith("import static ")) {
        staticImport = true
        normalized = normalized.removePrefix("import static ").trim()
    }

    var kind = "class"
    var classTerm = ""
    var packageTerm = ""
    var artifactTerm = ""
    when {
        normalized.isEmpty() -> kind = "unknown"
        normalized.endsWith(".*") -> {
            kind = "package_import"
            packageTerm = normalized.removeSuffix(".*")
        }
        staticImport -> {
            kind = "static_import"
            classTerm = beforeLastDot(normalized).removeSuffix(".*")
        }
        normalized.contains("#") -> {
            kind = "method"
            classTerm = normalized.substringBefore("#")
        }
        normalized.contains(":") || normalized.endsWith(".jar") -> {
            kind = "artifact"
            artifactTerm = normalized
        }
        normalized.contains(".") -> {
            kind = "import"
            classTerm = normalized
        }
        else -> {
            kind = "class"
            classTerm = normalized
        }
    }

    val usageTerms = mutableListOf(original, normalized)
    if (classTerm.isNotEmpty()) {
        usageTerms += classTerm
        usageTerms += "import $classTerm"
        val simple = splitFqn(classTerm).second
        if (simple.isNotEmpty()) {
            usageTerms += simple
        }
    }
    if (packageTerm.isNotEmpty()) {
        usageTerms += packageTerm
        usageTerms += "import $packageTerm.*"
    }
    if (artifactTerm.isNotEmpty()) {
        usageTerms += artifactTerm
        val artifactId = coordinateArtifactId(artifactTerm)
        if (artifactId.isNotEmpty()) {
            usageTerms += artifactId
        }
    }
    return DependencyLookup(
        original = original,
        command = "dep-precise",
        kind = kind,
        normalized = normalized,
        classTerm = classTerm,
        packageTerm = packageTerm,
        artifactTerm = artifactTerm,
        fallbackArtifact = true,
        usageTerms = usageTerms,
    )
}

private fun dependencyOrigins(
    pointer: ProjectPointerData,
    sources: List<CachedManifestRow>,
    lookup: DependencyLookup,
    limit: Int,
): List<DependencyOrigin> = when {
    lookup.packageTerm.isNotEmpty() -> packageOrigins(pointer, sources, lookup.packageTerm, limit)
    lookup.artifactTerm.isNotEmpty() -> artifactOrigins(sources, lookup.artifactTerm)
    lookup.classTerm.isNotEmpty() -> {
        val origins = classOrigins(pointer, sources, lookup.classTerm, limit)
        if (origins.isNotEmpty() || lookup.classTerm.contains(".") || !lookup.fallbackArtifact) {
            origins
        } else {
            artifactOrigins(sources, lookup.classTerm)
        }
    }
    else -> emptyList()
}

private fun classOrigins(
    pointer: ProjectPointerData,
    sources: List<CachedManifestRow>,
    term: String,
    limit: Int,
): List<DependencyOrigin> = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    var simple = splitFqn(term).second
    if (simple.isEmpty()) {
        simple = term
    }
    data class PendingOrigin(val fqn: String, val shardKey: String, val matchReason: String)
    val pending = session.query(
        """
        SELECT fqn, source_shard_id,
               CASE
                 WHEN fqn = ? THEN 'exact_fqn'
                 WHEN simple_name = ? THEN 'simple_name'
               END AS match_reason
        FROM classes
        WHERE fqn = ? OR simple_name = ?
        ORDER BY
          CASE
            WHEN fqn = ? THEN 0
            WHEN simple_name = ? THEN 1
          END,
          fqn, source_shard_id
        LIMIT ?
        """.trimIndent(),
        listOf(term, simple, term, simple, term, simple, limitOrDefault(limit)),
    ) { rows ->
        rows.mapRows {
            PendingOrigin(it.getString(1), it.getString(2), it.getString(3))
        }
    }
    val byShard = ManifestCache.mapSessionRows(session, sources, pending.map { it.shardKey })
    val origins = pending.map { row ->
        originFromSource(byShard[row.shardKey] ?: emptyManifestSource, row.matchReason)
            .copy(fqn = row.fqn)
    }
    dedupeOrigins(origins)
}

private fun packageOrigins(
    pointer: ProjectPointerData,
    sources: List<CachedManifestRow>,
    packageName: String,
    limit: Int,
): List<DependencyOrigin> = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    data class PendingOrigin(val packageName: String, val shardKey: String)
    val pending = session.query(
        """
        SELECT package_name, source_shard_id
        FROM classes
        WHERE package_name = ?
        GROUP BY package_name, source_shard_id
        ORDER BY package_name, source_shard_id
        LIMIT ?
        """.trimIndent(),
        listOf(packageName, limitOrDefault(limit)),
    ) { rows ->
        rows.mapRows {
            PendingOrigin(it.getString(1), it.getString(2))
        }
    }
    val byShard = ManifestCache.mapSessionRows(session, sources, pending.map { it.shardKey })
    val origins = pending.map { row ->
        originFromSource(byShard[row.shardKey] ?: emptyManifestSource, "package_import")
            .copy(packageName = row.packageName)
    }
    dedupeOrigins(origins)
}

private fun artifactOrigins(sources: List<CachedManifestRow>, term: String): List<DependencyOrigin> {
    val origins = mutableListOf<DependencyOrigin>()
    for (source in sources) {
        if (!sourceMatchesArtifact(source, term)) {
            continue
        }
        origins += originFromSource(source, artifactOriginReason(source, term))
    }
    return dedupeOrigins(origins)
}

// Shared with search-source's --in resolution; the matching rules must stay
// identical to the artifact command so agents can reuse the same spellings.
internal fun sourceMatchesArtifact(source: CachedManifestRow, term: String): Boolean {
    if (source.coordinate.isNotEmpty() &&
        (
            artifactMatchesArg(source.coordinate, term) ||
                coordinateArtifactId(source.coordinate) == term
            )
    ) {
        return true
    }
    val filename = filepathBase(source.filename)
    val stem = filename.removeSuffix(".jar")
    return source.filename == term ||
        filename == term ||
        stem == term ||
        (term.isNotEmpty() && stem.contains(term))
}

private fun artifactOriginReason(source: CachedManifestRow, term: String): String = when {
    source.coordinate.isNotEmpty() && artifactMatchesArg(source.coordinate, term) -> "coordinate"
    source.coordinate.isNotEmpty() && coordinateArtifactId(source.coordinate) == term -> "artifact"
    else -> "jar_filename"
}

private fun originFromSource(source: CachedManifestRow, matchReason: String): DependencyOrigin {
    val origin = DependencyOrigin(
        coordinate = source.coordinate,
        jarFilename = source.filename,
        sourceJar = source.source,
        shardId = source.shardId,
        matchReason = matchReason,
    )
    if (origin.sourceJar.isEmpty()) {
        return origin.copy(sourceJar = source.shardId)
    }
    return origin
}

/** Zero-value manifest row, mirroring Go's map lookup of a missing shard id. */
private val emptyManifestSource = CachedManifestRow(
    shardId = "",
    coordinate = "",
    filename = "",
    source = "",
)

private fun dependencyTargetsFromOrigins(origins: List<DependencyOrigin>): List<DependencyTarget> {
    val targets = mutableListOf<DependencyTarget>()
    for (origin in origins) {
        if (origin.coordinate.isEmpty()) {
            continue
        }
        targets += DependencyTarget(
            coordinate = origin.coordinate,
            matchedBy = origin.matchReason,
            sourceJar = origin.sourceJar,
            sourceClass = origin.fqn,
        )
    }
    return dedupeDependencyTargets(targets)
}

private class SourceUsageQuery(
    val highSignal: List<String>,
    val broad: List<String>,
    val all: List<String>,
)

private fun sourceUsageQueryFor(
    lookup: DependencyLookup,
    origins: List<DependencyOrigin>,
): SourceUsageQuery {
    val highSignal = highSignalSourceUsageTerms(lookup, origins)
    val broad = broadSourceUsageTerms(lookup, origins)
    return SourceUsageQuery(
        highSignal = highSignal,
        broad = broad,
        all = dedupeNonEmptyStrings(highSignal + broad),
    )
}

private fun highSignalSourceUsageTerms(
    lookup: DependencyLookup,
    origins: List<DependencyOrigin>,
): List<String> {
    val terms = mutableListOf<String>()
    if (lookup.original.startsWith("import ")) {
        terms += lookup.original
    }
    if (lookup.classTerm.isNotEmpty()) {
        terms += "import ${lookup.classTerm}"
        terms += "import static ${lookup.classTerm}."
    }
    if (lookup.packageTerm.isNotEmpty()) {
        terms += "import ${lookup.packageTerm}.*"
    }
    if (lookup.artifactTerm.isNotEmpty()) {
        terms += lookup.artifactTerm
        val artifactId = coordinateArtifactId(lookup.artifactTerm)
        if (artifactId.isNotEmpty()) {
            terms += artifactId
        }
    }
    for (origin in origins) {
        if (origin.fqn.isNotEmpty()) {
            terms += "import ${origin.fqn}"
            terms += "import static ${origin.fqn}."
        }
        terms += origin.coordinate
        terms += filepathBase(origin.jarFilename)
        val artifactId = coordinateArtifactId(origin.coordinate)
        if (artifactId.isNotEmpty()) {
            terms += artifactId
        }
    }
    return dedupeNonEmptyStrings(terms)
}

private fun broadSourceUsageTerms(
    lookup: DependencyLookup,
    origins: List<DependencyOrigin>,
): List<String> {
    val terms = lookup.usageTerms.toMutableList()
    for (origin in origins) {
        terms += origin.fqn
        terms += origin.coordinate
        terms += filepathBase(origin.jarFilename)
        if (origin.fqn.isNotEmpty()) {
            val simple = splitFqn(origin.fqn).second
            terms += simple
            terms += "import ${origin.fqn}"
        }
        val artifactId = coordinateArtifactId(origin.coordinate)
        if (artifactId.isNotEmpty()) {
            terms += artifactId
        }
    }
    return dedupeNonEmptyStrings(terms)
}

private fun dedupeOrigins(origins: List<DependencyOrigin>): List<DependencyOrigin> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<DependencyOrigin>()
    for (origin in origins) {
        val key = origin.fqn + "\u0000" + origin.packageName + "\u0000" + origin.coordinate +
            "\u0000" + origin.jarFilename + "\u0000" + origin.shardId
        if (!seen.add(key)) {
            continue
        }
        result += origin
    }
    return result
}

private fun dedupeNonEmptyStrings(values: List<String>): List<String> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<String>()
    for (value in values) {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || !seen.add(trimmed)) {
            continue
        }
        result += trimmed
    }
    return result
}

private fun beforeLastDot(value: String): String {
    val index = value.lastIndexOf('.')
    return if (index < 0) value else value.substring(0, index)
}

private fun limitOrDefault(limit: Int): Int = if (limit < 1) 20 else limit
