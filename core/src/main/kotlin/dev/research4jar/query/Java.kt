package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonInclude

data class ClassSearchResult(
    @JsonProperty("fqn") val fqn: String,
    @JsonProperty("simple_name") val simpleName: String,
    @JsonProperty("package") val packageName: String,
    @JsonProperty("kind") val kind: String?,
    @JsonProperty("super") val superFqn: String?,
    @JsonProperty("source_jar") val sourceJar: String,
    @JsonProperty("score") val score: Int,
    @JsonProperty("match_reason") val matchReason: String,
)

data class ClassSearchResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("results") val results: List<ClassSearchResult>,
    @JsonProperty("total") val total: Int,
    @JsonProperty("has_more") val hasMore: Boolean,
    @JsonProperty("page") val page: Int,
    @JsonProperty("page_size") val pageSize: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

data class MethodSearchResult(
    @JsonProperty("class_fqn") val classFqn: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("descriptor") val descriptor: String,
    @JsonProperty("return") val returnFqn: String?,
    @JsonProperty("modifiers") val modifiers: Int,
    @JsonProperty("source_jar") val sourceJar: String,
    @JsonProperty("score") val score: Int,
    @JsonProperty("match_reason") val matchReason: String,
)

data class MethodSearchResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("results") val results: List<MethodSearchResult>,
    @JsonProperty("total") val total: Int,
    @JsonProperty("has_more") val hasMore: Boolean,
    @JsonProperty("page") val page: Int,
    @JsonProperty("page_size") val pageSize: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

data class PackageSummary(
    @JsonProperty("package") val packageName: String,
    @JsonProperty("source_jar") val sourceJar: String,
    @JsonProperty("classes") val classes: Int,
)

data class PackageListResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("results") val results: List<PackageSummary>,
    @JsonProperty("total") val total: Int,
    @JsonProperty("has_more") val hasMore: Boolean,
    @JsonProperty("page") val page: Int,
    @JsonProperty("page_size") val pageSize: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

data class SearchSymbolResult(
    @JsonProperty("kind") val kind: String,
    @JsonProperty("name") val name: String,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("owner") val owner: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("detail") val detail: String? = null,
    @JsonProperty("source_jar") val sourceJar: String,
    @JsonProperty("score") val score: Int,
    @JsonProperty("match_reason") val matchReason: String,
)

data class SearchSymbolResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("results") val results: List<SearchSymbolResult>,
    @JsonProperty("total") val total: Int,
    @JsonProperty("has_more") val hasMore: Boolean,
    @JsonProperty("page") val page: Int,
    @JsonProperty("page_size") val pageSize: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

// matchArgs binds the shared params row (term, simple, prefix, contains, hi).
// hi turns "starts with term" into an index-usable range: default-collation
// LIKE is case-insensitive and cannot use the session's BINARY indexes, so
// prefix tiers match case-sensitively via ranges; case-insensitive prefix
// hits still surface through the contains tier of the legacy query.
internal fun matchArgs(term: String): List<Any?> {
    val escaped = escapeLike(term)
    return listOf(term, term, "$escaped%", "%$escaped%", prefixUpperBound(term))
}

// The dotted split (cls, mname) lets `a.b.C.method` exact lookups probe the
// classes/methods indexes instead of evaluating string concat per row.
internal fun methodMatchArgs(term: String): List<Any?> {
    val (cls, mname) = splitFqn(term)
    return matchArgs(term) + listOf(cls, mname)
}

// Raw '%term%' for the fts arms. No escapeLike and no ESCAPE clause: LIKE
// with an ESCAPE argument never reaches fts5's xBestIndex (see Strings.kt),
// so only terms whose pattern is literal-safe route to the fts queries —
// trigramSearchable guarantees escaped == raw.
private fun containsPattern(term: String): String = "%$term%"

private enum class BoundaryAnchor { OWNER, METHOD }

private data class MethodSymbolBoundary(
    val ownerSuffix: String,
    val methodPrefix: String,
    val anchor: BoundaryAnchor,
)

/**
 * For a literal single-# term A#B and resolved symbol O#N, a match crossing
 * the real delimiter is exactly O LIKE '%A' AND N LIKE 'B%'. Anchor that
 * intersection from whichever side has a usable trigram; otherwise retain
 * the legacy scan. NUL terms stay legacy because SQLite LIKE truncates its
 * pattern there, invalidating the decomposition.
 */
private fun methodSymbolBoundary(term: String): MethodSymbolBoundary? {
    if ('\u0000' in term) return null
    val hash = term.indexOf('#')
    if (hash < 0 || term.indexOf('#', hash + 1) >= 0) return null
    val ownerSuffix = term.substring(0, hash)
    val methodPrefix = term.substring(hash + 1)
    val anchor = when {
        trigramSearchable(ownerSuffix) -> BoundaryAnchor.OWNER
        trigramSearchable(methodPrefix) -> BoundaryAnchor.METHOD
        else -> return null
    }
    return MethodSymbolBoundary(ownerSuffix, methodPrefix, anchor)
}

// FTS wins decisively for selective substring probes, but a postings list
// covering a large fraction of a table is slower than the legacy scan: the
// rewrites must de-duplicate candidate rowids and sort the complete hit set
// before LIMIT can return a page. Probe at most one quarter of the base-table
// population without materializing rows; reaching that cap selects the scan.
// The choice is performance-only — both paths are parity-tested — and any
// missing/corrupt FTS structure safely selects the legacy query.
private const val FTS_DENSITY_DIVISOR = 4L
private const val FTS_DENSITY_MIN_POPULATION = 16_384L

internal data class FtsDensityProbe(
    val sql: String,
    val args: List<Any?>,
)

internal data class FtsDensityPlan(
    val populationTable: String,
    val populationIndex: String,
    val probes: List<FtsDensityProbe>,
)

private fun tablePopulation(session: java.sql.Connection, plan: FtsDensityPlan): Long {
    val analyzed = session.query(
        "SELECT CAST(stat AS INTEGER) FROM sqlite_stat1 WHERE tbl = ? AND idx = ?",
        listOf(plan.populationTable, plan.populationIndex),
    ) { rows -> if (rows.next()) rows.getLong(1) else 0L }
    if (analyzed > 0L) return analyzed
    // Unit fixtures may populate a fully-built empty session after ANALYZE.
    // MAX(id) is an O(log n) defensive estimate on the INTEGER PRIMARY KEY;
    // delta holes only overestimate density's denominator, which can miss an
    // optimization but cannot change results or force a costly table count.
    return session.query("SELECT COALESCE(MAX(id), 0) FROM ${plan.populationTable}", emptyList()) { rows ->
        rows.next()
        rows.getLong(1)
    }
}

internal fun preferLegacyForDenseFts(
    session: java.sql.Connection,
    plan: FtsDensityPlan,
): Boolean = try {
    val population = tablePopulation(session, plan)
    if (population < FTS_DENSITY_MIN_POPULATION) {
        false
    } else {
        val threshold = (population - 1L) / FTS_DENSITY_DIVISOR + 1L
        var remaining = threshold
        var dense = false
        for (probe in plan.probes) {
            // COUNT wraps the capped, order-free probe so JDBC receives one
            // scalar instead of crossing the native boundary once per hit.
            // Accumulating raw work across arms is intentional: the FTS
            // rewrite must read those postings before UNION can de-duplicate
            // them, even when several arms ultimately name the same rowid.
            val hits = session.query(
                "SELECT COUNT(*) FROM (${probe.sql})",
                probe.args + listOf(remaining),
            ) { rows ->
                rows.next()
                rows.getLong(1)
            }
            remaining -= hits
            if (remaining <= 0L) {
                dense = true
                break
            }
        }
        dense
    }
} catch (_: java.sql.SQLException) {
    // A pre-v6 session (or a missing/incompatible optional shadow) must not
    // pay for a guaranteed failed FTS execution before taking the compatible
    // scan. Current sessions validate the exact shadow schema before reuse.
    true
}

// Two-stage search shared by find-class/find-method/search-symbol: the fast
// query probes only equality and range predicates (index-backed); its every
// match outscores every contains-tier match, so a full fast page equals the
// legacy page. Underfilled pages fall back to the contains scan: [legacies]
// is tried in order, with every candidate but the last allowed to fail —
// the trigram-served rewrite runs first and a session built before
// classes_fts/methods_fts existed falls through to the original scan, the
// same fallback pattern as find-string.
private fun <T> twoStage(
    session: java.sql.Connection,
    fastSql: String,
    fastArgs: List<Any?>,
    legacies: List<Pair<String, List<Any?>>>,
    densityPlan: FtsDensityPlan?,
    page: Int,
    pageSize: Int,
    scan: (java.sql.ResultSet) -> T,
): List<T> {
    val window = pageWindow(page, pageSize)
    val limits = listOf(window.limitPlusOne, window.offset)
    val fast = session.query(fastSql, fastArgs + limits) { it.mapRows(scan) }
    if (fast.size > pageSize) return fast
    val candidates = if (densityPlan != null && preferLegacyForDenseFts(session, densityPlan)) {
        legacies.takeLast(1)
    } else {
        legacies
    }
    for ((sql, args) in candidates.dropLast(1)) {
        try {
            return session.query(sql, args + limits) { it.mapRows(scan) }
        } catch (_: java.sql.SQLException) {
            // Session predates the trigram tables; the next candidate answers.
        }
    }
    val (sql, args) = candidates.last()
    return session.query(sql, args + limits) { it.mapRows(scan) }
}

internal fun classFtsDensityPlan(term: String): FtsDensityPlan {
    val pattern = containsPattern(term)
    return FtsDensityPlan(
        populationTable = "classes",
        populationIndex = "idx_s_classes_fqn",
        probes = listOf(FtsDensityProbe(CLASS_FTS_FQN_PROBE_SQL, listOf(pattern))),
    )
}

internal fun methodFtsDensityPlan(term: String): FtsDensityPlan {
    val pattern = containsPattern(term)
    return FtsDensityPlan(
        populationTable = "methods",
        populationIndex = "idx_s_methods_name",
        probes = listOf(
            FtsDensityProbe(METHOD_FTS_NAME_PROBE_SQL, listOf(pattern)),
            FtsDensityProbe(METHOD_FTS_OWNER_PROBE_SQL, listOf(pattern)),
        ),
    )
}

// The contains-scan candidates for find-class, ordered by preference: the
// classes_fts rewrite for trigram-eligible terms, then the original scan.
internal fun classContainsQueries(term: String): List<Pair<String, List<Any?>>> {
    val legacy = CLASS_SEARCH_SQL to matchArgs(term)
    if (!trigramSearchable(term)) return listOf(legacy)
    return listOf(
        CLASS_SEARCH_FTS_SQL to matchArgs(term) + listOf(containsPattern(term)),
        legacy,
    )
}

// Likewise for find-method. Exact owner#name is handled by the class/name
// indexes; the remaining contains arms each read one stored owner or name
// column, so '#' is no reason to fall back to a computed-symbol table scan.
internal fun methodContainsQueries(term: String): List<Pair<String, List<Any?>>> {
    val legacySql = if ('#' in term) {
        routeMethodExactMatch(METHOD_SEARCH_SQL, term)
    } else {
        routeMethodExactMatch(METHOD_SEARCH_NO_HASH_SQL, term)
    }
    val legacy = legacySql to methodMatchArgs(term)
    if (!trigramSearchable(term)) return listOf(legacy)
    val pattern = containsPattern(term)
    return listOf(
        routeMethodExactMatch(METHOD_SEARCH_FTS_SQL, term) to
            methodMatchArgs(term) + listOf(pattern, pattern),
        legacy,
    )
}

fun findClass(
    pointer: ProjectPointerData,
    manifestPath: String,
    term: String,
    page: Int,
    pageSize: Int,
): ClassSearchResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    data class Pending(val result: ClassSearchResult, val shardId: String)
    var pending = twoStage(
        session, CLASS_SEARCH_FAST_SQL, matchArgs(term), classContainsQueries(term),
        if (trigramSearchable(term)) classFtsDensityPlan(term) else null,
        page, pageSize,
    ) {
        val fqn = it.getString(1)
        val split = splitFqn(fqn)
        Pending(
            ClassSearchResult(
                fqn = fqn,
                simpleName = it.getString(2) ?: split.second,
                packageName = it.getString(3) ?: split.first,
                kind = it.getString(4),
                superFqn = it.getString(5),
                sourceJar = "",
                score = it.getInt(7),
                matchReason = it.getString(8),
            ),
            it.getString(6),
        )
    }
    val hasMore = pending.size > pageSize
    if (hasMore) pending = pending.subList(0, pageSize)

    val sources = if (pending.isNotEmpty()) {
        ManifestCache.loadSourceJars(session, manifestPath, pending.map { it.shardId })
    } else {
        null
    }
    val results = pending.map { it.result.copy(sourceJar = sourceJarName(sources, it.shardId)) }
    ClassSearchResponse(
        query = SymbolRequest(command = "find-class", arg = term),
        results = results,
        total = results.size,
        hasMore = hasMore,
        page = page,
        pageSize = pageSize,
        coverage = coverageFrom(pointer),
    )
}

fun findMethod(
    pointer: ProjectPointerData,
    manifestPath: String,
    term: String,
    page: Int,
    pageSize: Int,
): MethodSearchResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    data class Pending(val result: MethodSearchResult, val shardId: String)
    var pending = twoStage(
        session, routeMethodExactMatch(METHOD_SEARCH_FAST_SQL, term),
        methodMatchArgs(term), methodContainsQueries(term),
        if (trigramSearchable(term)) methodFtsDensityPlan(term) else null,
        page, pageSize,
    ) {
        Pending(
            MethodSearchResult(
                classFqn = it.getString(1),
                name = it.getString(2),
                descriptor = it.getString(3),
                returnFqn = it.getString(4),
                modifiers = it.getInt(5),
                sourceJar = "",
                score = it.getInt(7),
                matchReason = it.getString(8),
            ),
            it.getString(6),
        )
    }
    val hasMore = pending.size > pageSize
    if (hasMore) pending = pending.subList(0, pageSize)

    val sources = if (pending.isNotEmpty()) {
        ManifestCache.loadSourceJars(session, manifestPath, pending.map { it.shardId })
    } else {
        null
    }
    val results = pending.map { it.result.copy(sourceJar = sourceJarName(sources, it.shardId)) }
    MethodSearchResponse(
        query = SymbolRequest(command = "find-method", arg = term),
        results = results,
        total = results.size,
        hasMore = hasMore,
        page = page,
        pageSize = pageSize,
        coverage = coverageFrom(pointer),
    )
}

fun listPackages(
    pointer: ProjectPointerData,
    manifestPath: String,
    prefix: String,
    page: Int,
    pageSize: Int,
): PackageListResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    val window = pageWindow(page, pageSize)
    val likePrefix = if (prefix.isEmpty()) "" else escapeLike(prefix) + ".%"
    data class Pending(val result: PackageSummary, val shardId: String)
    var pending = session.query(
        """
        SELECT package_name, source_shard_id, COUNT(*) AS classes
        FROM classes
        WHERE ? = '' OR package_name = ? OR package_name LIKE ? ESCAPE '\'
        GROUP BY package_name, source_shard_id
        ORDER BY package_name, source_shard_id
        LIMIT ? OFFSET ?
        """.trimIndent(),
        listOf(prefix, prefix, likePrefix, window.limitPlusOne, window.offset),
    ) { rows ->
        rows.mapRows {
            Pending(
                PackageSummary(
                    packageName = it.getString(1),
                    sourceJar = "",
                    classes = it.getInt(3),
                ),
                it.getString(2),
            )
        }
    }
    val hasMore = pending.size > pageSize
    if (hasMore) pending = pending.subList(0, pageSize)

    val sources = if (pending.isNotEmpty()) {
        ManifestCache.loadSourceJars(session, manifestPath, pending.map { it.shardId })
    } else {
        null
    }
    val results = pending.map { it.result.copy(sourceJar = sourceJarName(sources, it.shardId)) }
    PackageListResponse(
        query = SymbolRequest(command = "list-packages", arg = prefix),
        results = results,
        total = results.size,
        hasMore = hasMore,
        page = page,
        pageSize = pageSize,
        coverage = coverageFrom(pointer),
    )
}

// One raw cascade row. The name is nullable because an orphaned method (NULL
// symbol) surfaces with a NULL name; searchSymbol's response mapping rejects
// such rows exactly as it always has (real merges never produce them), while
// SearchSymbolParityTest compares them oracle-vs-cascade at this level.
internal data class SymbolRow(
    val kind: String,
    val name: String?,
    val owner: String?,
    val detail: String?,
    val shardId: String,
    val score: Int,
    val matchReason: String,
)

// One contains tier of the cascade: the trigram-served rewrite when the term
// routes to it (null otherwise), and the original scan it falls back to when
// the session predates classes_fts/methods_fts — the find-string pattern.
internal class ContainsTier(
    val legacySql: String,
    val ftsSql: String?,
    val ftsArgs: List<Any?>?,
    val densityPlan: FtsDensityPlan? = null,
)

internal fun symbolClassFtsDensityPlan(term: String): FtsDensityPlan {
    val pattern = containsPattern(term)
    return FtsDensityPlan(
        populationTable = "classes",
        populationIndex = "idx_s_classes_fqn",
        probes = listOf(
            FtsDensityProbe(CLASS_FTS_FQN_PROBE_SQL, listOf(pattern)),
            FtsDensityProbe(CLASS_FTS_KIND_PROBE_SQL, listOf(pattern)),
        ),
    )
}

internal fun symbolMethodFtsDensityPlan(term: String): FtsDensityPlan {
    val pattern = containsPattern(term)
    val probes = mutableListOf(
        FtsDensityProbe(METHOD_FTS_NAME_PROBE_SQL, listOf(pattern)),
        FtsDensityProbe(METHOD_FTS_DESCRIPTOR_PROBE_SQL, listOf(pattern)),
        FtsDensityProbe(METHOD_FTS_OWNER_PROBE_SQL, listOf(pattern)),
    )
    methodSymbolBoundary(term)?.let { boundary ->
        probes += when (boundary.anchor) {
            BoundaryAnchor.OWNER -> FtsDensityProbe(
                METHOD_FTS_OWNER_BOUNDARY_PROBE_SQL,
                listOf("%${boundary.ownerSuffix}", "${boundary.methodPrefix}%"),
            )
            BoundaryAnchor.METHOD -> FtsDensityProbe(
                METHOD_FTS_NAME_BOUNDARY_PROBE_SQL,
                listOf("${boundary.methodPrefix}%", "%${boundary.ownerSuffix}"),
            )
        }
    }
    return FtsDensityPlan(
        populationTable = "methods",
        populationIndex = "idx_s_methods_name",
        probes = probes,
    )
}

internal fun symbolStringFtsDensityPlan(term: String): FtsDensityPlan {
    val pattern = containsPattern(term)
    return FtsDensityPlan(
        populationTable = "string_constants",
        populationIndex = "idx_s_strconst_value",
        probes = listOf(
            FtsDensityProbe(STRING_FTS_VALUE_PROBE_SQL, listOf(pattern)),
            FtsDensityProbe(STRING_FTS_OWNER_PROBE_SQL, listOf(pattern)),
            FtsDensityProbe(STRING_FTS_METHOD_NAME_PROBE_SQL, listOf(pattern, pattern)),
            FtsDensityProbe(STRING_FTS_METHOD_DESCRIPTOR_PROBE_SQL, listOf(pattern)),
        ),
    )
}

// Per-term tier selection. Class/method/string tiers serve trigram-eligible
// terms (>= 3 codepoints, no LIKE metacharacters — same routing rule as
// find-string) through the fts rewrites. A safe single-# method term adds an
// indexed owner-suffix/name-prefix intersection for delimiter-spanning hits;
// ambiguous, too-short, or NUL-bearing boundaries retain legacy. The string tier sends
// '('-containing terms to the legacy scan, because its detail column is the
// computed m.name || m.descriptor and an occurrence spanning that boundary
// always covers the descriptor's leading '(' — such terms cannot be
// decomposed into per-column matches. Annotation, spi and config-property
// tiers always run the original scans (measured negligible).
internal fun symbolContainsTiers(term: String): List<ContainsTier> {
    if (!trigramSearchable(term)) {
        return SEARCH_SYMBOL_CONTAINS_TIERS.mapIndexed { index, sql ->
            ContainsTier(
                if (index == 1) routeMethodExactMatch(sql, term) else sql,
                null,
                null,
            )
        }
    }
    val pattern = containsPattern(term)
    val args = matchArgs(term)
    val boundary = methodSymbolBoundary(term)
    val methodTier = when {
        '#' !in term -> ContainsTier(
            SEARCH_SYMBOL_METHOD_NO_HASH_SQL,
            routeMethodExactMatch(SEARCH_SYMBOL_METHOD_FTS_SQL, term),
            args + listOf(pattern, pattern, pattern),
            symbolMethodFtsDensityPlan(term),
        )
        boundary != null -> {
            val (ftsSql, boundaryArgs) = when (boundary.anchor) {
                BoundaryAnchor.OWNER -> SEARCH_SYMBOL_METHOD_OWNER_BOUNDARY_FTS_SQL to
                    listOf("%${boundary.ownerSuffix}", "${boundary.methodPrefix}%")
                BoundaryAnchor.METHOD -> SEARCH_SYMBOL_METHOD_NAME_BOUNDARY_FTS_SQL to
                    listOf("${boundary.methodPrefix}%", "%${boundary.ownerSuffix}")
            }
            ContainsTier(
                routeMethodExactMatch(SEARCH_SYMBOL_CONTAINS_TIERS[1], term),
                routeMethodExactMatch(ftsSql, term),
                args + listOf(pattern, pattern, pattern) + boundaryArgs,
                symbolMethodFtsDensityPlan(term),
            )
        }
        else -> ContainsTier(
            routeMethodExactMatch(SEARCH_SYMBOL_CONTAINS_TIERS[1], term),
            null,
            null,
        )
    }
    return listOf(
        ContainsTier(
            SEARCH_SYMBOL_CONTAINS_TIERS[0],
            SEARCH_SYMBOL_CLASS_FTS_SQL,
            args + listOf(pattern, pattern),
            symbolClassFtsDensityPlan(term),
        ),
        methodTier,
        ContainsTier(SEARCH_SYMBOL_CONTAINS_TIERS[2], null, null),
        ContainsTier(SEARCH_SYMBOL_CONTAINS_TIERS[3], null, null),
        ContainsTier(SEARCH_SYMBOL_CONTAINS_TIERS[4], null, null),
        if ('(' in term) {
            ContainsTier(SEARCH_SYMBOL_CONTAINS_TIERS[5], null, null)
        } else {
            ContainsTier(
                SEARCH_SYMBOL_CONTAINS_TIERS[5],
                SEARCH_SYMBOL_STRING_FTS_SQL,
                args + listOf(pattern, pattern, pattern, pattern),
                symbolStringFtsDensityPlan(term),
            )
        },
    )
}

private fun runContainsTier(
    session: java.sql.Connection,
    tier: ContainsTier,
    term: String,
    limit: Int,
    scan: (java.sql.ResultSet) -> SymbolRow,
): List<SymbolRow> {
    val preferLegacy = tier.densityPlan?.let { preferLegacyForDenseFts(session, it) } == true
    if (tier.ftsSql != null && !preferLegacy) {
        try {
            return session.query(tier.ftsSql, tier.ftsArgs!! + listOf(limit)) { it.mapRows(scan) }
        } catch (_: java.sql.SQLException) {
            // Session predates classes_fts/methods_fts; the legacy tier answers.
        }
    }
    return session.query(tier.legacySql, matchArgs(term) + listOf(limit)) { it.mapRows(scan) }
}

private fun runFastSymbolTiers(
    session: java.sql.Connection,
    term: String,
    limit: Int,
    scan: (java.sql.ResultSet) -> SymbolRow,
): List<SymbolRow> {
    val rows = ArrayList<SymbolRow>(limit)
    val args = matchArgs(term)
    for (sql in searchSymbolFastTiers(term)) {
        val remaining = limit - rows.size
        if (remaining <= 0) break
        rows += session.query(sql, args + listOf(remaining)) { it.mapRows(scan) }
    }
    return rows
}

// The raw cascade behind searchSymbol, exposed for SearchSymbolParityTest.
// Underfilled fast pages cascade through the per-kind contains tiers instead
// of scanning the whole search_symbols view (the view expands to millions of
// joined rows; the cascade usually stops after the classes tier). Ordering
// stays identical to SEARCH_SYMBOL_SQL because every fast-tier score outranks
// every contains score and the contains scores are strictly ordered by kind.
internal fun searchSymbolRows(
    session: java.sql.Connection,
    term: String,
    page: Int,
    pageSize: Int,
): Pair<List<SymbolRow>, Boolean> {
    val scan: (java.sql.ResultSet) -> SymbolRow = {
        SymbolRow(
            kind = it.getString(1),
            name = it.getString(2),
            owner = it.getString(3),
            detail = it.getString(4),
            shardId = it.getString(5),
            score = it.getInt(6),
            matchReason = it.getString(7),
        )
    }
    val window = pageWindow(page, pageSize)
    val needed = window.needed.toInt()
    if (page > 1) {
        // Most deep pages still lie wholly inside the fast prefix tiers. Ask
        // SQLite for that slice first; when it is full, avoid materializing
        // every preceding row in JDBC merely to drop it below.
        val direct = session.query(
            routeMethodExactMatch(SEARCH_SYMBOL_FAST_SQL, term),
            matchArgs(term) + listOf(window.limitPlusOne, window.offset),
        ) { it.mapRows(scan) }
        if (direct.size > pageSize) {
            return direct.subList(0, pageSize) to true
        }
    }
    var rows = if (page == 1) {
        runFastSymbolTiers(session, term, window.limitPlusOne, scan)
    } else {
        session.query(
            routeMethodExactMatch(SEARCH_SYMBOL_FAST_SQL, term),
            matchArgs(term) + listOf(needed, 0L),
        ) {
            it.mapRows(scan)
        }
    }
    for (tier in symbolContainsTiers(term)) {
        if (rows.size >= needed) break
        rows = rows + runContainsTier(session, tier, term, needed - rows.size, scan)
    }
    var pending = rows.drop(window.offset.toInt())
    val hasMore = pending.size > pageSize
    if (hasMore) pending = pending.subList(0, pageSize)
    return pending to hasMore
}

fun searchSymbol(
    pointer: ProjectPointerData,
    manifestPath: String,
    term: String,
    page: Int,
    pageSize: Int,
): SearchSymbolResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    val (pending, hasMore) = searchSymbolRows(session, term, page, pageSize)

    val sources = if (pending.isNotEmpty()) {
        ManifestCache.loadSourceJars(session, manifestPath, pending.map { it.shardId })
    } else {
        null
    }
    val results = pending.map { row ->
        SearchSymbolResult(
            kind = row.kind,
            name = row.name!!,
            owner = row.owner,
            detail = row.detail,
            sourceJar = sourceJarName(sources, row.shardId),
            score = row.score,
            matchReason = row.matchReason,
        )
    }
    SearchSymbolResponse(
        query = SymbolRequest(command = "search-symbol", arg = term),
        results = results,
        total = results.size,
        hasMore = hasMore,
        page = page,
        pageSize = pageSize,
        coverage = coverageFrom(pointer),
    )
}

// The scoring CASE is shared verbatim with the Go querier (java.go) so a page
// served by either implementation ranks identically. See that file for the
// tier design: fast-path tiers score >= 60, contains-tier rows score <= 55.

// Session v8 no longer repeats owner#name on every method row. A single '#'
// is the public, unambiguous Class#method shape and can probe the compact
// class/name indexes. Inputs with another '#' may represent a legal but
// ambiguous bytecode name, so they retain exact semantics through the
// computed expression. owner_resolved preserves v7's NULL-symbol behavior
// for a malformed shard row even if its numeric class id later collides.
private const val METHOD_SYMBOL_SQL =
    "CASE WHEN m.owner_resolved = 0 THEN NULL ELSE c.fqn || '#' || m.name END"
private const val METHOD_SINGLE_HASH_MATCH_SQL =
    "m.owner_resolved <> 0 AND instr(p.term, '#') > 0 " +
        "AND instr(substr(p.term, instr(p.term, '#') + 1), '#') = 0 " +
        "AND c.fqn = substr(p.term, 1, instr(p.term, '#') - 1) " +
        "AND m.name = substr(p.term, instr(p.term, '#') + 1)"
private const val METHOD_COMPUTED_MATCH_SQL =
    "m.owner_resolved <> 0 AND c.fqn || '#' || m.name = p.term"
// The unresolved template remains the semantically complete v7-style symbol
// equality. Every production query specializes it before prepare; retaining a
// correct raw form also keeps diagnostic/oracle SQL honest for NUL bytecode
// names instead of embedding SQLite's NUL-truncating substr decomposition.
private const val METHOD_EXACT_MATCH_SQL = METHOD_COMPUTED_MATCH_SQL
private const val ANNOTATION_OWNER_SQL =
    "CASE WHEN a.target_kind = 'class' THEN c.fqn " +
        "WHEN a.target_kind = 'method' AND m.owner_resolved <> 0 " +
        "THEN mc.fqn || '#' || m.name ELSE NULL END"

/**
 * Specialize exact method-symbol probes before SQLite prepares the statement.
 * The planner does not reliably prune the multi-# concatenation branch merely
 * because its parameter guard is false; leaving that branch in a common query
 * turned ordinary page-1 searches into a full methods scan.
 */
private fun routedMethodExactMatch(term: String): String {
    val firstHash = term.indexOf('#')
    return when {
        firstHash < 0 -> "0"
        // SQLite's TEXT length/substr stop at U+0000. Legal hand-written
        // bytecode names containing NUL therefore cannot use the indexed
        // owner/name split without changing exact-match semantics. Keep this
        // vanishingly rare input on the computed equality fallback.
        '\u0000' in term -> METHOD_COMPUTED_MATCH_SQL
        term.indexOf('#', firstHash + 1) < 0 -> METHOD_SINGLE_HASH_MATCH_SQL
        else -> METHOD_COMPUTED_MATCH_SQL
    }
}

internal fun routeMethodExactMatch(sql: String, term: String): String =
    sql.replace(METHOD_EXACT_MATCH_SQL, routedMethodExactMatch(term))

private const val CLASS_SEARCH_SCORE_SQL = """
         CASE
           WHEN c.fqn = p.term THEN 100
           WHEN c.simple_name = p.simple THEN 90
           WHEN c.simple_name >= p.simple AND c.simple_name < p.hi THEN 82
           WHEN c.fqn >= p.term AND c.fqn < p.hi THEN 80
           ELSE 50
         END AS score,
         CASE
           WHEN c.fqn = p.term THEN 'exact_fqn'
           WHEN c.simple_name = p.simple THEN 'simple_name'
           WHEN c.simple_name >= p.simple AND c.simple_name < p.hi THEN 'simple_prefix'
           WHEN c.fqn >= p.term AND c.fqn < p.hi THEN 'prefix'
           ELSE 'contains'
         END AS match_reason"""

private const val CLASS_SEARCH_SELECT_SQL = """
SELECT fqn, simple_name, package_name, kind, super_fqn, source_shard_id, score, match_reason
FROM matches
ORDER BY score DESC, fqn, source_shard_id
LIMIT ? OFFSET ?"""

internal const val CLASS_SEARCH_SQL = """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?)),
matches AS (
  SELECT c.fqn, c.simple_name, c.package_name, c.kind, c.super_fqn, c.source_shard_id,$CLASS_SEARCH_SCORE_SQL
  FROM classes c, params p
  WHERE c.fqn = p.term
     OR c.simple_name = p.simple
     OR c.simple_name LIKE p.prefix ESCAPE '\'
     OR c.fqn LIKE p.prefix ESCAPE '\'
     OR c.fqn LIKE p.contains ESCAPE '\'
)$CLASS_SEARCH_SELECT_SQL"""

internal const val CLASS_SEARCH_FAST_SQL = """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?)),
matches AS (
  SELECT c.fqn, c.simple_name, c.package_name, c.kind, c.super_fqn, c.source_shard_id,$CLASS_SEARCH_SCORE_SQL
  FROM classes c, params p
  WHERE c.fqn = p.term
     OR c.simple_name = p.simple
     OR (p.hi <> '' AND c.simple_name >= p.simple AND c.simple_name < p.hi)
     OR (p.hi <> '' AND c.fqn >= p.term AND c.fqn < p.hi)
)$CLASS_SEARCH_SELECT_SQL"""

private const val METHOD_SEARCH_SCORE_SQL = """
         CASE
           WHEN $METHOD_EXACT_MATCH_SQL THEN 100
           WHEN c.fqn = p.cls AND m.name = p.mname THEN 100
           WHEN m.name = p.term THEN 95
           WHEN m.name >= p.simple AND m.name < p.hi THEN 75
           ELSE 50
         END AS score,
         CASE
           WHEN $METHOD_EXACT_MATCH_SQL THEN 'exact_method'
           WHEN c.fqn = p.cls AND m.name = p.mname THEN 'exact_method'
           WHEN m.name = p.term THEN 'method_name'
           WHEN m.name >= p.simple AND m.name < p.hi THEN 'method_prefix'
           ELSE 'contains'
         END AS match_reason"""

private const val METHOD_SEARCH_SELECT_SQL = """
SELECT class_fqn, name, descriptor, return_fqn, modifiers, source_shard_id, score, match_reason
FROM matches
ORDER BY score DESC, class_fqn, name, descriptor, source_shard_id
LIMIT ? OFFSET ?"""

internal const val METHOD_SEARCH_SQL = """
WITH params(term, simple, prefix, contains, hi, cls, mname) AS (VALUES (?, ?, ?, ?, ?, ?, ?)),
matches AS (
  SELECT c.fqn AS class_fqn, m.name, m.descriptor, m.return_fqn, m.modifiers,
         m.source_shard_id,$METHOD_SEARCH_SCORE_SQL
  FROM methods m
  JOIN classes c ON c.id = m.class_id
  , params p
  WHERE ($METHOD_EXACT_MATCH_SQL)
     OR (c.fqn = p.cls AND m.name = p.mname)
     OR m.name = p.term
     OR m.name LIKE p.prefix ESCAPE '\'
     OR m.name LIKE p.contains ESCAPE '\'
     OR c.fqn LIKE p.contains ESCAPE '\'
)$METHOD_SEARCH_SELECT_SQL"""

// Exact equivalent for terms without '#'. In owner#name, such a substring
// lies wholly in owner or name, both of which already have their own arms;
// avoiding a per-row concatenation keeps the dense legacy route cheap.
internal const val METHOD_SEARCH_NO_HASH_SQL = """
WITH params(term, simple, prefix, contains, hi, cls, mname) AS (VALUES (?, ?, ?, ?, ?, ?, ?)),
matches AS (
  SELECT c.fqn AS class_fqn, m.name, m.descriptor, m.return_fqn, m.modifiers,
         m.source_shard_id,$METHOD_SEARCH_SCORE_SQL
  FROM methods m
  JOIN classes c ON c.id = m.class_id
  , params p
  WHERE (c.fqn = p.cls AND m.name = p.mname)
     OR m.name = p.term
     OR m.name LIKE p.prefix ESCAPE '\'
     OR m.name LIKE p.contains ESCAPE '\'
     OR c.fqn LIKE p.contains ESCAPE '\'
)$METHOD_SEARCH_SELECT_SQL"""

internal const val METHOD_SEARCH_FAST_SQL = """
WITH params(term, simple, prefix, contains, hi, cls, mname) AS (VALUES (?, ?, ?, ?, ?, ?, ?)),
hits(id) AS (
  SELECT m.id
  FROM params p
  CROSS JOIN classes c INDEXED BY idx_s_classes_fqn
  CROSS JOIN methods m INDEXED BY idx_s_methods_class ON m.class_id = c.id
  WHERE $METHOD_EXACT_MATCH_SQL
  UNION
  SELECT m.id
  FROM params p
  CROSS JOIN classes c INDEXED BY idx_s_classes_fqn
  CROSS JOIN methods m INDEXED BY idx_s_methods_class ON m.class_id = c.id
  WHERE p.cls <> '' AND c.fqn = p.cls AND m.name = p.mname
  UNION
  SELECT m.id FROM methods m, params p WHERE m.name = p.term
  UNION
  SELECT m.id FROM methods m, params p
  WHERE p.hi <> '' AND m.name >= p.simple AND m.name < p.hi
),
matches AS (
  SELECT c.fqn AS class_fqn, m.name, m.descriptor, m.return_fqn, m.modifiers,
         m.source_shard_id,$METHOD_SEARCH_SCORE_SQL
  FROM hits h
  CROSS JOIN methods m ON m.id = h.id
  JOIN classes c ON c.id = m.class_id
  , params p
)$METHOD_SEARCH_SELECT_SQL"""

private const val SEARCH_SYMBOL_SCORE_SQL = """
         CASE
           WHEN s.kind = 'class' AND s.name = p.term THEN 100
           WHEN s.kind = 'method' AND s.name = p.term THEN 98
           WHEN s.kind = 'method' AND s.simple_name = p.simple THEN 92
           WHEN s.kind = 'class' AND s.simple_name = p.simple THEN 90
           WHEN s.kind = 'annotation' AND s.name = p.term THEN 88
           WHEN s.kind = 'spi' AND (s.name = p.term OR s.owner = p.term) THEN 86
           WHEN s.kind = 'config-property' AND s.name = p.term THEN 84
           WHEN s.kind = 'class' AND s.simple_name >= p.simple AND s.simple_name < p.hi THEN 82
           WHEN s.kind = 'class' AND s.name >= p.term AND s.name < p.hi THEN 80
           WHEN s.kind = 'annotation' AND s.name >= p.term AND s.name < p.hi THEN 78
           WHEN s.kind = 'spi' AND s.name >= p.term AND s.name < p.hi THEN 76
           WHEN s.kind = 'method' AND s.simple_name >= p.simple AND s.simple_name < p.hi THEN 75
           WHEN s.kind = 'config-property' AND s.name >= p.term AND s.name < p.hi THEN 70
           WHEN s.kind = 'string' AND s.name = p.term THEN 65
           WHEN s.kind = 'string' AND s.name >= p.term AND s.name < p.hi THEN 60
           ELSE s.score_hint
         END AS score,
         CASE
           WHEN s.kind = 'class' AND s.name = p.term THEN 'exact_fqn'
           WHEN s.kind = 'method' AND s.name = p.term THEN 'exact_method'
           WHEN s.kind = 'method' AND s.simple_name = p.simple THEN 'method_name'
           WHEN s.kind = 'class' AND s.simple_name = p.simple THEN 'simple_name'
           WHEN s.kind = 'annotation' AND s.name = p.term THEN 'annotation_fqn'
           WHEN s.kind = 'spi' AND s.name = p.term THEN 'spi_impl'
           WHEN s.kind = 'spi' AND s.owner = p.term THEN 'spi_key'
           WHEN s.kind = 'config-property' AND s.name = p.term THEN 'config_property'
           WHEN s.kind = 'class' AND s.simple_name >= p.simple AND s.simple_name < p.hi THEN 'simple_prefix'
           WHEN s.kind = 'class' AND s.name >= p.term AND s.name < p.hi THEN 'prefix'
           WHEN s.kind = 'annotation' AND s.name >= p.term AND s.name < p.hi THEN 'annotation_prefix'
           WHEN s.kind = 'spi' AND s.name >= p.term AND s.name < p.hi THEN 'spi_prefix'
           WHEN s.kind = 'method' AND s.simple_name >= p.simple AND s.simple_name < p.hi THEN 'method_prefix'
           WHEN s.kind = 'config-property' AND s.name >= p.term AND s.name < p.hi THEN 'config_prefix'
           WHEN s.kind = 'string' AND s.name = p.term THEN 'string_constant'
           WHEN s.kind = 'string' AND s.name >= p.term AND s.name < p.hi THEN 'string_prefix'
           ELSE s.kind || '_contains'
         END AS match_reason"""

// No longer executed in production: searchSymbol serves underfilled pages via
// SEARCH_SYMBOL_CONTAINS_TIERS below. Kept verbatim as the ordering oracle —
// SearchSymbolParityTest proves the cascade returns exactly these pages.
internal const val SEARCH_SYMBOL_SQL = """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?)),
matches AS (
  SELECT s.kind, s.name, s.owner, s.detail, s.source_shard_id,$SEARCH_SYMBOL_SCORE_SQL
  FROM search_symbols s, params p
  WHERE s.name = p.term
     OR s.simple_name = p.simple
     OR s.owner = p.term
     OR s.detail = p.term
     OR s.name LIKE p.prefix ESCAPE '\'
     OR s.simple_name LIKE p.prefix ESCAPE '\'
     OR s.name LIKE p.contains ESCAPE '\'
     OR s.owner LIKE p.contains ESCAPE '\'
     OR s.detail LIKE p.contains ESCAPE '\'
)
SELECT kind, name, owner, detail, source_shard_id, score, match_reason
FROM matches
ORDER BY score DESC, kind, name, source_shard_id, owner, detail
LIMIT ? OFFSET ?"""

// Strict, mutually-exclusive fast score tiers. Page 1 consumes these in
// descending score order and stops as soon as it has pageSize+1 rows, avoiding
// the mega-UNION below sorting every low-score method/string prefix merely to
// return a handful of exact hits. Each predicate mirrors the CASE priority in
// SEARCH_SYMBOL_SCORE_SQL; within one constant-score/kind tier the global
// ordering reduces to the payload-complete ORDER BY shown here.
internal val SEARCH_SYMBOL_FAST_TIERS: List<String> = listOf(
    // 100: exact class FQN
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'class' AS kind, c.fqn AS name, NULL AS owner, c.kind AS detail,
           c.source_shard_id, 100 AS score, 'exact_fqn' AS match_reason
    FROM classes c, params p
    WHERE c.fqn = p.term
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 98: exact method symbol
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?)),
    hits(id) AS (
      SELECT m.id
      FROM params p
      CROSS JOIN classes c INDEXED BY idx_s_classes_fqn
      CROSS JOIN methods m INDEXED BY idx_s_methods_class ON m.class_id = c.id
      WHERE $METHOD_EXACT_MATCH_SQL
    )
    SELECT 'method' AS kind, $METHOD_SYMBOL_SQL AS name,
           c.fqn AS owner, m.descriptor AS detail,
           m.source_shard_id, 98 AS score, 'exact_method' AS match_reason
    FROM hits h CROSS JOIN methods m ON m.id = h.id
    JOIN classes c ON c.id = m.class_id
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 92: exact method name, excluding score 98
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'method' AS kind, $METHOD_SYMBOL_SQL AS name,
           c.fqn AS owner, m.descriptor AS detail,
           m.source_shard_id, 92 AS score, 'method_name' AS match_reason
    FROM methods m JOIN classes c ON c.id = m.class_id, params p
    WHERE m.name = p.simple AND NOT COALESCE($METHOD_EXACT_MATCH_SQL, 0)
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 90: exact class simple name, excluding score 100
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'class' AS kind, c.fqn AS name, NULL AS owner, c.kind AS detail,
           c.source_shard_id, 90 AS score, 'simple_name' AS match_reason
    FROM classes c, params p
    WHERE c.simple_name = p.simple AND NOT COALESCE(c.fqn = p.term, 0)
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 88: exact annotation FQN
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'annotation' AS kind, a.annotation_fqn AS name,
           CASE WHEN a.target_kind = 'class' THEN c.fqn
                WHEN a.target_kind = 'method' AND m.owner_resolved <> 0
                  THEN mc.fqn || '#' || m.name ELSE NULL END AS owner,
           a.attributes AS detail, a.source_shard_id, 88 AS score,
           'annotation_fqn' AS match_reason
    FROM annotations a
    LEFT JOIN classes c ON a.target_kind = 'class' AND c.id = a.target_id
    LEFT JOIN methods m ON a.target_kind = 'method' AND m.id = a.target_id
    LEFT JOIN classes mc ON mc.id = m.class_id
    , params p
    WHERE a.annotation_fqn = p.term
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 86: exact SPI implementation or key
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'spi' AS kind, s.impl_fqn AS name, s.key AS owner, s.mechanism AS detail,
           s.source_shard_id, 86 AS score,
           CASE WHEN s.impl_fqn = p.term THEN 'spi_impl' ELSE 'spi_key' END AS match_reason
    FROM spi_registrations s, params p
    WHERE s.impl_fqn = p.term OR s.key = p.term
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 84: exact configuration property
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'config-property' AS kind, cp.name, cp.source_fqn AS owner,
           cp.type_fqn AS detail, cp.source_shard_id, 84 AS score,
           'config_property' AS match_reason
    FROM config_properties cp, params p
    WHERE cp.name = p.term
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 82: class simple-name prefix
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'class' AS kind, c.fqn AS name, NULL AS owner, c.kind AS detail,
           c.source_shard_id, 82 AS score, 'simple_prefix' AS match_reason
    FROM classes c, params p
    WHERE p.hi <> '' AND c.simple_name >= p.simple AND c.simple_name < p.hi
      AND NOT COALESCE(c.fqn = p.term OR c.simple_name = p.simple, 0)
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 80: class FQN prefix, excluding higher class tiers
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'class' AS kind, c.fqn AS name, NULL AS owner, c.kind AS detail,
           c.source_shard_id, 80 AS score, 'prefix' AS match_reason
    FROM classes c, params p
    WHERE p.hi <> '' AND c.fqn >= p.term AND c.fqn < p.hi
      AND NOT COALESCE(
           c.fqn = p.term OR c.simple_name = p.simple
        OR (c.simple_name >= p.simple AND c.simple_name < p.hi), 0)
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 78: annotation FQN prefix
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'annotation' AS kind, a.annotation_fqn AS name,
           CASE WHEN a.target_kind = 'class' THEN c.fqn
                WHEN a.target_kind = 'method' AND m.owner_resolved <> 0
                  THEN mc.fqn || '#' || m.name ELSE NULL END AS owner,
           a.attributes AS detail, a.source_shard_id, 78 AS score,
           'annotation_prefix' AS match_reason
    FROM annotations a
    LEFT JOIN classes c ON a.target_kind = 'class' AND c.id = a.target_id
    LEFT JOIN methods m ON a.target_kind = 'method' AND m.id = a.target_id
    LEFT JOIN classes mc ON mc.id = m.class_id
    , params p
    WHERE p.hi <> '' AND a.annotation_fqn >= p.term AND a.annotation_fqn < p.hi
      AND NOT COALESCE(a.annotation_fqn = p.term, 0)
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 76: SPI implementation prefix, excluding exact impl/key
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'spi' AS kind, s.impl_fqn AS name, s.key AS owner, s.mechanism AS detail,
           s.source_shard_id, 76 AS score, 'spi_prefix' AS match_reason
    FROM spi_registrations s, params p
    WHERE p.hi <> '' AND s.impl_fqn >= p.term AND s.impl_fqn < p.hi
      AND NOT COALESCE(s.impl_fqn = p.term OR s.key = p.term, 0)
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 75: method-name prefix, excluding exact method/name
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'method' AS kind, $METHOD_SYMBOL_SQL AS name,
           c.fqn AS owner, m.descriptor AS detail,
           m.source_shard_id, 75 AS score, 'method_prefix' AS match_reason
    FROM methods m JOIN classes c ON c.id = m.class_id, params p
    WHERE p.hi <> '' AND m.name >= p.simple AND m.name < p.hi
      AND NOT COALESCE(($METHOD_EXACT_MATCH_SQL) OR m.name = p.simple, 0)
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 70: configuration-property prefix
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'config-property' AS kind, cp.name, cp.source_fqn AS owner,
           cp.type_fqn AS detail, cp.source_shard_id, 70 AS score,
           'config_prefix' AS match_reason
    FROM config_properties cp, params p
    WHERE p.hi <> '' AND cp.name >= p.term AND cp.name < p.hi
      AND NOT COALESCE(cp.name = p.term, 0)
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 65: exact string constant
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'string' AS kind, sc.value AS name, c.fqn AS owner,
           CASE WHEN m.name IS NULL THEN NULL ELSE m.name || m.descriptor END AS detail,
           sc.source_shard_id, 65 AS score, 'string_constant' AS match_reason
    FROM string_constants sc
    JOIN classes c ON c.id = sc.class_id
    LEFT JOIN methods m ON m.id = sc.method_id
    , params p
    WHERE sc.value = p.term
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
    // 60: string-value prefix
    """
    WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
    SELECT 'string' AS kind, sc.value AS name, c.fqn AS owner,
           CASE WHEN m.name IS NULL THEN NULL ELSE m.name || m.descriptor END AS detail,
           sc.source_shard_id, 60 AS score, 'string_prefix' AS match_reason
    FROM string_constants sc
    JOIN classes c ON c.id = sc.class_id
    LEFT JOIN methods m ON m.id = sc.method_id
    , params p
    WHERE p.hi <> '' AND sc.value >= p.term AND sc.value < p.hi
      AND NOT COALESCE(sc.value = p.term, 0)
    ORDER BY name, 5, owner, detail
    LIMIT ?
    """.trimIndent(),
)

/**
 * Route the exact-method tier by delimiter shape. With no '#', the tier is
 * provably empty and is skipped entirely; single-# inputs use only indexed
 * owner/name predicates, and only genuinely ambiguous multi-# inputs retain
 * the computed-symbol scan.
 */
internal fun searchSymbolFastTiers(term: String): List<String> {
    val exactMatch = routedMethodExactMatch(term)
    return buildList(SEARCH_SYMBOL_FAST_TIERS.size) {
        SEARCH_SYMBOL_FAST_TIERS.forEachIndexed { index, sql ->
            if (index != 1 || exactMatch != "0") {
                add(sql.replace(METHOD_EXACT_MATCH_SQL, exactMatch))
            }
        }
    }
}

// Probes each base table with equality and range predicates only, bypassing
// the search_symbols view so every branch uses its own index.
internal const val SEARCH_SYMBOL_FAST_SQL = """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?)),
method_hits(id) AS (
  SELECT m.id
  FROM params p
  CROSS JOIN classes c INDEXED BY idx_s_classes_fqn
  CROSS JOIN methods m INDEXED BY idx_s_methods_class ON m.class_id = c.id
  WHERE $METHOD_EXACT_MATCH_SQL
  UNION
  SELECT m.id FROM methods m, params p WHERE m.name = p.simple
  UNION
  SELECT m.id FROM methods m, params p
  WHERE p.hi <> '' AND m.name >= p.simple AND m.name < p.hi
)
SELECT kind, name, owner, detail, source_shard_id, score, match_reason FROM (
  SELECT 'class' AS kind, c.fqn AS name, NULL AS owner, c.kind AS detail,
         c.source_shard_id,
         CASE
           WHEN c.fqn = p.term THEN 100
           WHEN c.simple_name = p.simple THEN 90
           WHEN c.simple_name >= p.simple AND c.simple_name < p.hi THEN 82
           ELSE 80
         END AS score,
         CASE
           WHEN c.fqn = p.term THEN 'exact_fqn'
           WHEN c.simple_name = p.simple THEN 'simple_name'
           WHEN c.simple_name >= p.simple AND c.simple_name < p.hi THEN 'simple_prefix'
           ELSE 'prefix'
         END AS match_reason
  FROM classes c, params p
  WHERE c.fqn = p.term
     OR c.simple_name = p.simple
     OR (p.hi <> '' AND c.simple_name >= p.simple AND c.simple_name < p.hi)
     OR (p.hi <> '' AND c.fqn >= p.term AND c.fqn < p.hi)
  UNION ALL
  SELECT 'method', $METHOD_SYMBOL_SQL, c.fqn, m.descriptor, m.source_shard_id,
         CASE
           WHEN $METHOD_EXACT_MATCH_SQL THEN 98
           WHEN m.name = p.simple THEN 92
           ELSE 75
         END,
         CASE
           WHEN $METHOD_EXACT_MATCH_SQL THEN 'exact_method'
           WHEN m.name = p.simple THEN 'method_name'
           ELSE 'method_prefix'
         END
  FROM method_hits h
  CROSS JOIN methods m ON m.id = h.id
  JOIN classes c ON c.id = m.class_id
  , params p
  UNION ALL
  SELECT 'annotation', a.annotation_fqn,
         CASE WHEN a.target_kind = 'class' THEN c.fqn
              WHEN a.target_kind = 'method' AND m.owner_resolved <> 0
                THEN mc.fqn || '#' || m.name
              ELSE NULL END,
         a.attributes, a.source_shard_id,
         CASE WHEN a.annotation_fqn = p.term THEN 88 ELSE 78 END,
         CASE WHEN a.annotation_fqn = p.term THEN 'annotation_fqn' ELSE 'annotation_prefix' END
  FROM annotations a
  LEFT JOIN classes c ON a.target_kind = 'class' AND c.id = a.target_id
  LEFT JOIN methods m ON a.target_kind = 'method' AND m.id = a.target_id
  LEFT JOIN classes mc ON mc.id = m.class_id
  , params p
  WHERE a.annotation_fqn = p.term
     OR (p.hi <> '' AND a.annotation_fqn >= p.term AND a.annotation_fqn < p.hi)
  UNION ALL
  SELECT 'spi', s.impl_fqn, s.key, s.mechanism, s.source_shard_id,
         CASE WHEN s.impl_fqn = p.term OR s.key = p.term THEN 86 ELSE 76 END,
         CASE WHEN s.impl_fqn = p.term THEN 'spi_impl'
              WHEN s.key = p.term THEN 'spi_key'
              ELSE 'spi_prefix' END
  FROM spi_registrations s, params p
  WHERE s.impl_fqn = p.term
     OR s.key = p.term
     OR (p.hi <> '' AND s.impl_fqn >= p.term AND s.impl_fqn < p.hi)
  UNION ALL
  SELECT 'config-property', cp.name, cp.source_fqn, cp.type_fqn, cp.source_shard_id,
         CASE WHEN cp.name = p.term THEN 84 ELSE 70 END,
         CASE WHEN cp.name = p.term THEN 'config_property' ELSE 'config_prefix' END
  FROM config_properties cp, params p
  WHERE cp.name = p.term
     OR (p.hi <> '' AND cp.name >= p.term AND cp.name < p.hi)
  UNION ALL
  SELECT 'string', sc.value, c.fqn,
         CASE WHEN m.name IS NULL THEN NULL ELSE m.name || m.descriptor END,
         sc.source_shard_id,
         CASE WHEN sc.value = p.term THEN 65 ELSE 60 END,
         CASE WHEN sc.value = p.term THEN 'string_constant' ELSE 'string_prefix' END
  FROM string_constants sc
  JOIN classes c ON c.id = sc.class_id
  LEFT JOIN methods m ON m.id = sc.method_id
  , params p
  WHERE sc.value = p.term
     OR (p.hi <> '' AND sc.value >= p.term AND sc.value < p.hi)
)
ORDER BY score DESC, kind, name, source_shard_id, owner, detail
LIMIT ? OFFSET ?"""

// Exact score-50 method tier for terms without '#'. A non-NULL owner#name
// contains such a term iff owner or name contains it. NULL-name compatibility
// rows retain only the legacy exact/name-prefix arms, hence the flag on the
// name-contains predicate.
private const val SEARCH_SYMBOL_METHOD_NO_HASH_SQL = """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
SELECT 'method' AS kind, $METHOD_SYMBOL_SQL AS name,
       c.fqn AS owner, m.descriptor AS detail,
       m.source_shard_id AS source_shard_id, 50 AS score, 'method_contains' AS match_reason
FROM methods m JOIN classes c ON c.id = m.class_id, params p
WHERE (m.name = p.simple
    OR c.fqn = p.term
    OR m.descriptor = p.term
    OR m.name LIKE p.prefix ESCAPE '\'
    OR (m.owner_resolved <> 0 AND instr(c.fqn, char(0)) = 0
        AND m.name LIKE p.contains ESCAPE '\')
    OR c.fqn LIKE p.contains ESCAPE '\'
    OR m.descriptor LIKE p.contains ESCAPE '\')
  AND NOT COALESCE(
       m.name = p.simple
    OR (m.name >= p.simple AND m.name < p.hi), 0)
ORDER BY name, source_shard_id, owner, detail
LIMIT ?"""

// The contains tier of SEARCH_SYMBOL_SQL, split per kind and consumed in
// descending score order (55 class, 50 method, 45 annotation, 44 spi,
// 43 config-property, 30 string — strictly ordered, so appending whole tiers
// preserves the oracle's global ORDER BY). Each query keeps the oracle's
// WHERE arms mapped to that kind's view columns, minus the rows the fast
// query already returned: the NOT COALESCE(...) mirrors the >= 60 score
// conditions, with COALESCE because a NULL column (an orphaned method's
// symbol, say) must not exclude the row. LIMIT makes the scan stop as soon
// as the page is assembled; the expensive joined arms usually never run.
internal val SEARCH_SYMBOL_CONTAINS_TIERS: List<String> = listOf(
    """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
SELECT 'class' AS kind, c.fqn AS name, NULL AS owner, c.kind AS detail,
       c.source_shard_id, 55 AS score, 'class_contains' AS match_reason
FROM classes c, params p
WHERE (c.fqn = p.term
    OR c.simple_name = p.simple
    OR c.kind = p.term
    OR c.fqn LIKE p.prefix ESCAPE '\'
    OR c.simple_name LIKE p.prefix ESCAPE '\'
    OR c.fqn LIKE p.contains ESCAPE '\'
    OR c.kind LIKE p.contains ESCAPE '\')
  AND NOT COALESCE(
       c.fqn = p.term
    OR c.simple_name = p.simple
    OR (c.simple_name >= p.simple AND c.simple_name < p.hi)
    OR (c.fqn >= p.term AND c.fqn < p.hi), 0)
ORDER BY name, source_shard_id, owner, detail
LIMIT ?""",
    """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
SELECT 'method' AS kind, $METHOD_SYMBOL_SQL AS name,
       c.fqn AS owner, m.descriptor AS detail,
       m.source_shard_id AS source_shard_id, 50 AS score, 'method_contains' AS match_reason
FROM methods m JOIN classes c ON c.id = m.class_id, params p
WHERE (($METHOD_EXACT_MATCH_SQL)
    OR m.name = p.simple
    OR c.fqn = p.term
    OR m.descriptor = p.term
    OR ($METHOD_SYMBOL_SQL) LIKE p.prefix ESCAPE '\'
    OR m.name LIKE p.prefix ESCAPE '\'
    OR ($METHOD_SYMBOL_SQL) LIKE p.contains ESCAPE '\'
    OR c.fqn LIKE p.contains ESCAPE '\'
    OR m.descriptor LIKE p.contains ESCAPE '\')
  AND NOT COALESCE(
       ($METHOD_EXACT_MATCH_SQL)
    OR m.name = p.simple
    OR (m.name >= p.simple AND m.name < p.hi), 0)
ORDER BY name, source_shard_id, owner, detail
LIMIT ?""",
    """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
SELECT 'annotation' AS kind, a.annotation_fqn AS name,
       $ANNOTATION_OWNER_SQL AS owner,
       a.attributes AS detail, a.source_shard_id AS source_shard_id,
       45 AS score, 'annotation_contains' AS match_reason
FROM annotations a
LEFT JOIN classes c ON a.target_kind = 'class' AND c.id = a.target_id
LEFT JOIN methods m ON a.target_kind = 'method' AND m.id = a.target_id
LEFT JOIN classes mc ON mc.id = m.class_id
, params p
WHERE (a.annotation_fqn = p.term
    OR ($ANNOTATION_OWNER_SQL) = p.term
    OR a.attributes = p.term
    OR a.annotation_fqn LIKE p.prefix ESCAPE '\'
    OR a.annotation_fqn LIKE p.contains ESCAPE '\'
    OR ($ANNOTATION_OWNER_SQL) LIKE p.contains ESCAPE '\'
    OR a.attributes LIKE p.contains ESCAPE '\')
  AND NOT COALESCE(
       a.annotation_fqn = p.term
    OR (a.annotation_fqn >= p.term AND a.annotation_fqn < p.hi), 0)
ORDER BY name, source_shard_id, owner, detail
LIMIT ?""",
    """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
SELECT 'spi' AS kind, s.impl_fqn AS name, s.key AS owner, s.mechanism AS detail,
       s.source_shard_id, 44 AS score, 'spi_contains' AS match_reason
FROM spi_registrations s, params p
WHERE (s.impl_fqn = p.term
    OR s.key = p.term
    OR s.mechanism = p.term
    OR s.impl_fqn LIKE p.prefix ESCAPE '\'
    OR s.impl_fqn LIKE p.contains ESCAPE '\'
    OR s.key LIKE p.contains ESCAPE '\'
    OR s.mechanism LIKE p.contains ESCAPE '\')
  AND NOT COALESCE(
       s.impl_fqn = p.term
    OR s.key = p.term
    OR (s.impl_fqn >= p.term AND s.impl_fqn < p.hi), 0)
ORDER BY name, source_shard_id, owner, detail
LIMIT ?""",
    """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
SELECT 'config-property' AS kind, cp.name AS name, cp.source_fqn AS owner,
       cp.type_fqn AS detail, cp.source_shard_id,
       43 AS score, 'config-property_contains' AS match_reason
FROM config_properties cp, params p
WHERE (cp.name = p.term
    OR cp.source_fqn = p.term
    OR cp.type_fqn = p.term
    OR cp.name LIKE p.prefix ESCAPE '\'
    OR cp.name LIKE p.contains ESCAPE '\'
    OR cp.source_fqn LIKE p.contains ESCAPE '\'
    OR cp.type_fqn LIKE p.contains ESCAPE '\')
  AND NOT COALESCE(
       cp.name = p.term
    OR (cp.name >= p.term AND cp.name < p.hi), 0)
ORDER BY name, source_shard_id, owner, detail
LIMIT ?""",
    """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
SELECT 'string' AS kind, sc.value AS name, c.fqn AS owner,
       CASE WHEN m.name IS NULL THEN NULL ELSE m.name || m.descriptor END AS detail,
       sc.source_shard_id AS source_shard_id, 30 AS score, 'string_contains' AS match_reason
FROM string_constants sc
JOIN classes c ON c.id = sc.class_id
LEFT JOIN methods m ON m.id = sc.method_id
, params p
WHERE (sc.value = p.term
    OR c.fqn = p.term
    OR CASE WHEN m.name IS NULL THEN NULL ELSE m.name || m.descriptor END = p.term
    OR sc.value LIKE p.prefix ESCAPE '\'
    OR sc.value LIKE p.contains ESCAPE '\'
    OR c.fqn LIKE p.contains ESCAPE '\'
    OR CASE WHEN m.name IS NULL THEN NULL ELSE m.name || m.descriptor END LIKE p.contains ESCAPE '\')
  AND NOT COALESCE(
       sc.value = p.term
    OR (sc.value >= p.term AND sc.value < p.hi), 0)
ORDER BY name, source_shard_id, owner, detail
LIMIT ?""",
)

// ---------------------------------------------------------------------------
// Trigram-served rewrites of the contains scans, used for trigramSearchable
// terms (escaped == raw, so the raw '%term%' bound to each fts LIKE is the
// same pattern the legacy arms evaluate). Each rewrite computes the identical
// match SET as its legacy query — proven row-for-row, page-for-page by
// SearchSymbolParityTest — by folding every WHERE arm into an index-served
// branch of a UNION over rowids (UNION, not UNION ALL: a row matching several
// arms appears once, as in the single-scan original):
//
//  - same-column folding (always true): col = t and col LIKE 't%' are subsets
//    of col LIKE '%t%', which default-collation trigram LIKE serves with core
//    LIKE semantics (ASCII case folding included).
//  - simple_name is a substring of fqn (derived at merge time), so
//    simple-name equality/prefix arms fold into the classes_fts fqn arm.
//  - a resolved method projects as fqn || '#' || name. For terms without '#',
//    an occurrence lies wholly in owner or name, so compact methods_fts
//    name postings plus classes_fts owner postings are exact. Safe single-#
//    terms add an indexed owner-suffix/name-prefix boundary intersection;
//    ambiguous or non-indexable boundaries retain the legacy scan.
//  - the string tier's detail column is m.name || m.descriptor. For terms
//    without '(' a match cannot straddle the boundary (descriptors start
//    with '(', so a straddling occurrence would contain '('), hence
//    detail-contains ≡ name-contains OR descriptor-contains, and
//    detail = t is unsatisfiable. Strings reach their method's rows through
//    idx_s_strconst_class plus sc.method_id = m.id, exact because extraction
//    only attaches a method of the string's own class.
// ---------------------------------------------------------------------------

// Cheap, order-free cardinality probes for the adaptive routing above. Every
// query is hits-driven and capped by its final bind, so a dense posting list is
// detected without paying either rewrite's UNION/ORDER BY materialization.
internal const val CLASS_FTS_FQN_PROBE_SQL =
    "SELECT rowid FROM classes_fts WHERE fqn LIKE ? LIMIT ?"
internal const val CLASS_FTS_KIND_PROBE_SQL =
    "SELECT rowid FROM classes_fts WHERE kind LIKE ? LIMIT ?"
internal const val METHOD_FTS_NAME_PROBE_SQL =
    "SELECT rowid FROM methods_fts WHERE name LIKE ? LIMIT ?"
internal const val METHOD_FTS_DESCRIPTOR_PROBE_SQL =
    "SELECT rowid FROM methods_fts WHERE descriptor LIKE ? LIMIT ?"
internal const val METHOD_FTS_OWNER_PROBE_SQL = """
SELECT m.id
FROM classes_fts f CROSS JOIN methods m ON m.class_id = f.rowid
WHERE f.fqn LIKE ?
LIMIT ?"""
internal const val METHOD_FTS_OWNER_BOUNDARY_PROBE_SQL = """
SELECT m.id
FROM classes_fts f
CROSS JOIN methods m INDEXED BY idx_s_methods_class ON m.class_id = f.rowid
WHERE f.fqn LIKE ? AND m.name LIKE ? AND m.owner_resolved <> 0
  AND instr(f.fqn, char(0)) = 0
LIMIT ?"""
internal const val METHOD_FTS_NAME_BOUNDARY_PROBE_SQL = """
SELECT m.id
FROM methods_fts f
CROSS JOIN methods m ON m.id = f.rowid
JOIN classes c ON c.id = m.class_id
WHERE f.name LIKE ? AND c.fqn LIKE ? AND m.owner_resolved <> 0
  AND instr(c.fqn, char(0)) = 0
LIMIT ?"""
internal const val STRING_FTS_VALUE_PROBE_SQL =
    "SELECT rowid FROM string_constants_fts WHERE value LIKE ? LIMIT ?"
internal const val STRING_FTS_OWNER_PROBE_SQL = """
SELECT sc.id
FROM classes_fts f CROSS JOIN string_constants sc ON sc.class_id = f.rowid
WHERE f.fqn LIKE ?
LIMIT ?"""
internal const val STRING_FTS_METHOD_NAME_PROBE_SQL = """
SELECT sc.id
FROM methods_fts f
CROSS JOIN methods m ON m.id = f.rowid
JOIN string_constants sc ON sc.method_id = m.id AND sc.class_id = m.class_id
WHERE f.name LIKE ? AND m.name LIKE ?
LIMIT ?"""
internal const val STRING_FTS_METHOD_DESCRIPTOR_PROBE_SQL = """
SELECT sc.id
FROM methods_fts f
CROSS JOIN methods m ON m.id = f.rowid
JOIN string_constants sc ON sc.method_id = m.id AND sc.class_id = m.class_id
WHERE f.descriptor LIKE ? AND instr(m.name, char(0)) = 0
LIMIT ?"""

// find-class contains scan over classes_fts. Every legacy arm (fqn equality/
// prefix/contains, simple_name equality/prefix) folds into fqn LIKE '%t%'.
// CROSS JOIN (here and in the other rewrites) pins the hits union as the
// outer loop: left free, the planner sometimes walks a whole base-table
// index in ORDER BY order probing hits per row — measured seconds on a
// multi-GB session for rare terms, versus milliseconds hits-driven.
internal const val CLASS_SEARCH_FTS_SQL = """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?)),
hits(id) AS (
  SELECT rowid FROM classes_fts WHERE fqn LIKE ?
),
matches AS (
  SELECT c.fqn, c.simple_name, c.package_name, c.kind, c.super_fqn, c.source_shard_id,$CLASS_SEARCH_SCORE_SQL
  FROM hits h CROSS JOIN classes c ON c.id = h.id, params p
)$CLASS_SEARCH_SELECT_SQL"""

// find-method contains scan. Branches: exact symbol probe, the dotted
// (cls, mname) probe, name equality/prefix/contains through methods_fts.name,
// and owner contains through classes_fts. '#' terms never route here because
// they may straddle the owner/name boundary.
internal const val METHOD_SEARCH_FTS_SQL = """
WITH params(term, simple, prefix, contains, hi, cls, mname) AS (VALUES (?, ?, ?, ?, ?, ?, ?)),
hits(id) AS (
  SELECT m.id
  FROM params p
  CROSS JOIN classes c INDEXED BY idx_s_classes_fqn
  CROSS JOIN methods m INDEXED BY idx_s_methods_class ON m.class_id = c.id
  WHERE $METHOD_EXACT_MATCH_SQL
  UNION
  SELECT m.id
  FROM params p
  CROSS JOIN classes c INDEXED BY idx_s_classes_fqn
  CROSS JOIN methods m INDEXED BY idx_s_methods_class ON m.class_id = c.id
  WHERE c.fqn = p.cls AND m.name = p.mname
  UNION
  SELECT m.id FROM methods_fts f JOIN methods m ON m.id = f.rowid, params p
  WHERE f.name LIKE ? AND m.name LIKE p.contains ESCAPE '\'
  UNION
  SELECT m.id FROM classes_fts f JOIN methods m ON m.class_id = f.rowid
  WHERE f.fqn LIKE ?
),
matches AS (
  SELECT c.fqn AS class_fqn, m.name, m.descriptor, m.return_fqn, m.modifiers,
         m.source_shard_id,$METHOD_SEARCH_SCORE_SQL
  FROM hits h
  CROSS JOIN methods m ON m.id = h.id
  JOIN classes c ON c.id = m.class_id
  , params p
)$METHOD_SEARCH_SELECT_SQL"""

// search-symbol class tier (score 55): fqn arms and simple-name arms fold
// into the classes_fts fqn column, the kind arms into its kind column. The
// exclusion predicate and ORDER BY/LIMIT are the legacy tier's, verbatim.
internal const val SEARCH_SYMBOL_CLASS_FTS_SQL = """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?)),
hits(id) AS (
  SELECT rowid FROM classes_fts WHERE fqn LIKE ?
  UNION
  SELECT rowid FROM classes_fts WHERE kind LIKE ?
)
SELECT 'class' AS kind, c.fqn AS name, NULL AS owner, c.kind AS detail,
       c.source_shard_id, 55 AS score, 'class_contains' AS match_reason
FROM hits h CROSS JOIN classes c ON c.id = h.id, params p
WHERE NOT COALESCE(
       c.fqn = p.term
    OR c.simple_name = p.simple
    OR (c.simple_name >= p.simple AND c.simple_name < p.hi)
    OR (c.fqn >= p.term AND c.fqn < p.hi), 0)
ORDER BY name, source_shard_id, owner, detail
LIMIT ?"""

// search-symbol method tier (score 50): name arms through methods_fts.name,
// descriptor arms through its descriptor column, and owner arms through
// classes_fts. Safe single-# terms inject one additional FTS-anchored
// boundary intersection; other hash shapes retain the legacy tier.
internal const val SEARCH_SYMBOL_METHOD_FTS_SQL = """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?)),
hits(id) AS (
  SELECT m.id
  FROM methods_fts f
  JOIN methods m ON m.id = f.rowid
  JOIN classes c ON c.id = m.class_id
  , params p
  WHERE f.name LIKE ?
    AND ((m.owner_resolved <> 0 AND instr(c.fqn, char(0)) = 0)
         OR m.name LIKE p.prefix ESCAPE '\')
  UNION
  SELECT rowid FROM methods_fts WHERE descriptor LIKE ?
  UNION
  SELECT m.id FROM classes_fts f JOIN methods m ON m.class_id = f.rowid
  WHERE f.fqn LIKE ?
)
SELECT 'method' AS kind, $METHOD_SYMBOL_SQL AS name,
       c.fqn AS owner, m.descriptor AS detail,
       m.source_shard_id AS source_shard_id, 50 AS score, 'method_contains' AS match_reason
FROM hits h
CROSS JOIN methods m ON m.id = h.id
JOIN classes c ON c.id = m.class_id
, params p
WHERE NOT COALESCE(
       ($METHOD_EXACT_MATCH_SQL)
    OR m.name = p.simple
    OR (m.name >= p.simple AND m.name < p.hi), 0)
ORDER BY name, source_shard_id, owner, detail
LIMIT ?"""

private const val SEARCH_SYMBOL_METHOD_FTS_RESULT_MARKER =
    "\n)\nSELECT 'method' AS kind"
private const val SEARCH_SYMBOL_METHOD_OWNER_BOUNDARY_HIT_SQL = """
  UNION
  SELECT m.id
  FROM classes_fts f
  CROSS JOIN methods m INDEXED BY idx_s_methods_class ON m.class_id = f.rowid
  WHERE f.fqn LIKE ? AND m.name LIKE ? AND m.owner_resolved <> 0
    AND instr(f.fqn, char(0)) = 0
"""
private const val SEARCH_SYMBOL_METHOD_NAME_BOUNDARY_HIT_SQL = """
  UNION
  SELECT m.id
  FROM methods_fts f
  CROSS JOIN methods m ON m.id = f.rowid
  JOIN classes c ON c.id = m.class_id
  WHERE f.name LIKE ? AND c.fqn LIKE ? AND m.owner_resolved <> 0
    AND instr(c.fqn, char(0)) = 0
"""

private fun searchSymbolMethodBoundaryFtsSql(branch: String): String {
    check(SEARCH_SYMBOL_METHOD_FTS_RESULT_MARKER in SEARCH_SYMBOL_METHOD_FTS_SQL)
    return SEARCH_SYMBOL_METHOD_FTS_SQL.replace(
        SEARCH_SYMBOL_METHOD_FTS_RESULT_MARKER,
        branch + SEARCH_SYMBOL_METHOD_FTS_RESULT_MARKER,
    )
}

private val SEARCH_SYMBOL_METHOD_OWNER_BOUNDARY_FTS_SQL =
    searchSymbolMethodBoundaryFtsSql(SEARCH_SYMBOL_METHOD_OWNER_BOUNDARY_HIT_SQL)
private val SEARCH_SYMBOL_METHOD_NAME_BOUNDARY_FTS_SQL =
    searchSymbolMethodBoundaryFtsSql(SEARCH_SYMBOL_METHOD_NAME_BOUNDARY_HIT_SQL)

// search-symbol string tier (score 30), for terms without '(' only (see
// symbolContainsTiers): value arms through string_constants_fts, owner arms
// through an exact-fqn probe plus classes_fts (each joined to the strings of
// the class via idx_s_strconst_class), and the detail arms decomposed into
// the method's name (methods_fts name candidates) and descriptor
// (methods_fts descriptor column).
internal const val SEARCH_SYMBOL_STRING_FTS_SQL = """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?)),
hits(id) AS (
  SELECT rowid FROM string_constants_fts WHERE value LIKE ?
  UNION
  SELECT sc.id FROM classes c JOIN string_constants sc ON sc.class_id = c.id, params p
  WHERE c.fqn = p.term
  UNION
  SELECT sc.id FROM classes_fts f JOIN string_constants sc ON sc.class_id = f.rowid
  WHERE f.fqn LIKE ?
  UNION
  SELECT sc.id FROM methods_fts f
  JOIN methods m ON m.id = f.rowid
  JOIN string_constants sc ON sc.class_id = m.class_id AND sc.method_id = m.id
  , params p
  WHERE f.name LIKE ? AND m.name LIKE p.contains ESCAPE '\'
  UNION
  SELECT sc.id FROM methods_fts f
  JOIN methods m ON m.id = f.rowid
  JOIN string_constants sc ON sc.class_id = m.class_id AND sc.method_id = m.id
  WHERE f.descriptor LIKE ? AND instr(m.name, char(0)) = 0
)
SELECT 'string' AS kind, sc.value AS name, c.fqn AS owner,
       CASE WHEN m.name IS NULL THEN NULL ELSE m.name || m.descriptor END AS detail,
       sc.source_shard_id AS source_shard_id, 30 AS score, 'string_contains' AS match_reason
FROM hits h
CROSS JOIN string_constants sc ON sc.id = h.id
JOIN classes c ON c.id = sc.class_id
LEFT JOIN methods m ON m.id = sc.method_id
, params p
WHERE NOT COALESCE(
       sc.value = p.term
    OR (sc.value >= p.term AND sc.value < p.hi), 0)
ORDER BY name, source_shard_id, owner, detail
LIMIT ?"""
