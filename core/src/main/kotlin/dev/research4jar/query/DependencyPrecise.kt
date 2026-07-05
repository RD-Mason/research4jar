package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * dep precise / artifact / class origin queries, ported from
 * querier/internal/query/dependency_precise.go. JSON key sets, SQL, and the
 * bounded source-grep budgets must stay identical to the Go querier.
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
            val scan = findSourceUsages(projectDir, usageQuery, pageSize)
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
    val byShard = manifestByShard(sources)
    val origins = session.query(
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
            val fqn = it.getString(1)
            val shardId = it.getString(2)
            val matchReason = it.getString(3)
            originFromSource(byShard[shardId] ?: emptyManifestSource, matchReason)
                .copy(fqn = fqn, shardId = shardId)
        }
    }
    dedupeOrigins(origins)
}

private fun packageOrigins(
    pointer: ProjectPointerData,
    sources: List<CachedManifestRow>,
    packageName: String,
    limit: Int,
): List<DependencyOrigin> = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    val byShard = manifestByShard(sources)
    val origins = session.query(
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
            val matchedPackage = it.getString(1)
            val shardId = it.getString(2)
            originFromSource(byShard[shardId] ?: emptyManifestSource, "package_import")
                .copy(packageName = matchedPackage, shardId = shardId)
        }
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

private fun sourceMatchesArtifact(source: CachedManifestRow, term: String): Boolean {
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

private const val MAX_SOURCE_USAGE_FILE_BYTES = 2L * 1024 * 1024
private const val SOURCE_USAGE_FILE_BUDGET = 2000
private const val SOURCE_USAGE_TIME_BUDGET_NANOS = 1500L * 1_000_000

private class SourceUsageScan(
    val usages: List<SourceUsage>,
    val hasMore: Boolean,
    val truncatedReason: String,
)

private fun findSourceUsages(
    projectDir: String,
    query: SourceUsageQuery,
    limit: Int,
): SourceUsageScan {
    if (projectDir.isEmpty()) {
        return SourceUsageScan(emptyList(), false, "")
    }
    val state = SourceUsageScanState(
        projectDir = projectDir,
        limit = limitOrDefault(limit),
        deadlineNanos = System.nanoTime() + SOURCE_USAGE_TIME_BUDGET_NANOS,
    )
    for (phase in listOf(
        SourceUsagePhase(name = "high_signal", terms = query.highSignal),
        SourceUsagePhase(name = "broad", terms = query.broad),
    )) {
        if (state.usages.size > state.limit || state.truncatedReason.isNotEmpty()) {
            break
        }
        if (phase.terms.isEmpty()) {
            continue
        }
        scanSourceUsagePhase(state, phase)
    }
    val hasMore = state.usages.size > state.limit
    val usages: List<SourceUsage> = if (hasMore) state.usages.take(state.limit) else state.usages
    return SourceUsageScan(usages, hasMore, state.truncatedReason)
}

private class SourceUsagePhase(
    val name: String,
    val terms: List<String>,
)

private class SourceUsageScanState(
    val projectDir: String,
    val limit: Int,
    val deadlineNanos: Long,
) {
    var filesVisited = 0
    val seen = mutableSetOf<String>()
    val usages = mutableListOf<SourceUsage>()
    var truncatedReason = ""
}

// Go walks with filepath.WalkDir and a single callback; here the leading
// budget checks run in both preVisitDirectory and visitFile, fs.SkipAll maps
// to TERMINATE, and filepath.SkipDir maps to SKIP_SUBTREE.
private fun scanSourceUsagePhase(state: SourceUsageScanState, phase: SourceUsagePhase) {
    val terms = dedupeNonEmptyStrings(phase.terms)
    if (terms.isEmpty()) {
        return
    }
    Files.walkFileTree(
        Paths.get(state.projectDir),
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                sourceUsageBudgetStop(state)?.let { return it }
                if (shouldSkipSourceUsageDir(dir.fileName?.toString() ?: "")) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                sourceUsageBudgetStop(state)?.let { return it }
                if (!sourceUsageFile(file)) {
                    return FileVisitResult.CONTINUE
                }
                state.filesVisited++
                if (state.filesVisited > SOURCE_USAGE_FILE_BUDGET) {
                    state.truncatedReason = "file_budget"
                    return FileVisitResult.TERMINATE
                }
                if (attrs.size() > MAX_SOURCE_USAGE_FILE_BYTES) {
                    return FileVisitResult.CONTINUE
                }
                state.usages += scanSourceUsageFile(
                    state.projectDir,
                    file,
                    terms,
                    state.limit + 1 - state.usages.size,
                    state.seen,
                )
                if (state.usages.size > state.limit) {
                    return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private fun sourceUsageBudgetStop(state: SourceUsageScanState): FileVisitResult? {
    if (state.truncatedReason.isNotEmpty() || state.usages.size > state.limit) {
        return FileVisitResult.TERMINATE
    }
    if (System.nanoTime() - state.deadlineNanos > 0) {
        state.truncatedReason = "time_budget"
        return FileVisitResult.TERMINATE
    }
    return null
}

// Go prefilters on raw bytes; decoding once as UTF-8 keeps the prefilter and
// the per-line matches consistent, and byte-level vs code-unit-level contains
// agree on valid UTF-8 because the encoding is self-synchronizing.
private fun scanSourceUsageFile(
    projectDir: String,
    path: Path,
    terms: List<String>,
    limit: Int,
    seen: MutableSet<String>,
): List<SourceUsage> {
    if (limit <= 0) {
        return emptyList()
    }
    val content = Files.readAllBytes(path)
    if (content.contains(0.toByte())) {
        return emptyList()
    }
    val text = String(content, Charsets.UTF_8)
    if (!contentContainsAny(text, terms)) {
        return emptyList()
    }
    val lines = text.split("\n")
    val relative = try {
        Paths.get(projectDir).relativize(path).toString()
    } catch (_: IllegalArgumentException) {
        path.toString()
    }.replace(File.separatorChar, '/')
    val usages = mutableListOf<SourceUsage>()
    for ((index, line) in lines.withIndex()) {
        val match = firstLineMatch(line, terms)
        if (match.isEmpty()) {
            continue
        }
        val key = relative + "\u0000" + (index + 1)
        if (!seen.add(key)) {
            continue
        }
        usages += SourceUsage(
            path = relative,
            line = index + 1,
            match = match,
            text = trimUsageLine(line),
        )
        if (usages.size >= limit) {
            break
        }
    }
    return usages
}

private fun contentContainsAny(content: String, terms: List<String>): Boolean {
    for (term in terms) {
        if (term.isNotEmpty() && content.contains(term)) {
            return true
        }
    }
    return false
}

private fun firstLineMatch(line: String, terms: List<String>): String {
    for (term in terms) {
        if (term.isNotEmpty() && line.contains(term)) {
            return term
        }
    }
    return ""
}

// Go slices the first 240 bytes; slicing the UTF-8 bytes and re-decoding
// reproduces the U+FFFD replacement Go's JSON encoder applies to a cut that
// lands inside a multi-byte code point.
private fun trimUsageLine(line: String): String {
    val text = line.trim()
    val maxLine = 240
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxLine) {
        return text
    }
    return String(bytes, 0, maxLine, Charsets.UTF_8)
}

private val sourceUsageSkipDirs = setOf(
    ".git", ".gradle", ".idea", ".mvn", ".research4jar", ".settings", ".vscode",
    "build", "coverage", "dist", "generated", "generated-sources",
    "generated-test-sources", "node_modules", "out", "target",
)

private fun shouldSkipSourceUsageDir(name: String): Boolean = name in sourceUsageSkipDirs

private val sourceUsageExtensions = setOf(
    ".java", ".kt", ".kts", ".groovy", ".scala", ".xml", ".gradle", ".properties", ".yml", ".yaml",
)

/** Go filepath.Ext: the suffix from the final dot in the last path element. */
private fun sourceUsageFile(path: Path): Boolean {
    val name = path.fileName?.toString() ?: return false
    val index = name.lastIndexOf('.')
    return index >= 0 && name.substring(index) in sourceUsageExtensions
}

private fun manifestByShard(sources: List<CachedManifestRow>): Map<String, CachedManifestRow> =
    sources.associateBy { it.shardId }

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
