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
    page: Int,
    pageSize: Int,
    scan: (java.sql.ResultSet) -> T,
): List<T> {
    val limits = listOf(pageSize + 1, (page - 1) * pageSize)
    val fast = session.query(fastSql, fastArgs + limits) { it.mapRows(scan) }
    if (fast.size > pageSize) return fast
    for ((sql, args) in legacies.dropLast(1)) {
        try {
            return session.query(sql, args + limits) { it.mapRows(scan) }
        } catch (_: java.sql.SQLException) {
            // Session predates the trigram tables; the next candidate answers.
        }
    }
    val (sql, args) = legacies.last()
    return session.query(sql, args + limits) { it.mapRows(scan) }
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

// Likewise for find-method. No '(' restriction: unlike the search-symbol
// string tier there is no name||descriptor concatenation to straddle — every
// arm reads a single stored column.
internal fun methodContainsQueries(term: String): List<Pair<String, List<Any?>>> {
    val legacy = METHOD_SEARCH_SQL to methodMatchArgs(term)
    if (!trigramSearchable(term)) return listOf(legacy)
    val pattern = containsPattern(term)
    return listOf(
        METHOD_SEARCH_FTS_SQL to methodMatchArgs(term) + listOf(pattern, pattern),
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
        session, CLASS_SEARCH_FAST_SQL, matchArgs(term), classContainsQueries(term), page, pageSize,
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

    val sources = if (pending.isNotEmpty()) ManifestCache.loadSourceJars(manifestPath) else null
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
        session, METHOD_SEARCH_FAST_SQL, methodMatchArgs(term), methodContainsQueries(term), page, pageSize,
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

    val sources = if (pending.isNotEmpty()) ManifestCache.loadSourceJars(manifestPath) else null
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
        listOf(prefix, prefix, likePrefix, pageSize + 1, (page - 1) * pageSize),
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

    val sources = if (pending.isNotEmpty()) ManifestCache.loadSourceJars(manifestPath) else null
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
)

// Per-term tier selection. Class/method/string tiers serve trigram-eligible
// terms (>= 3 codepoints, no LIKE metacharacters — same routing rule as
// find-string) through the fts rewrites; the string tier additionally sends
// '('-containing terms to the legacy scan, because its detail column is the
// computed m.name || m.descriptor and an occurrence spanning that boundary
// always covers the descriptor's leading '(' — such terms cannot be
// decomposed into per-column matches. Annotation, spi and config-property
// tiers always run the original scans (measured negligible).
internal fun symbolContainsTiers(term: String): List<ContainsTier> {
    if (!trigramSearchable(term)) {
        return SEARCH_SYMBOL_CONTAINS_TIERS.map { ContainsTier(it, null, null) }
    }
    val pattern = containsPattern(term)
    val args = matchArgs(term)
    return listOf(
        ContainsTier(
            SEARCH_SYMBOL_CONTAINS_TIERS[0],
            SEARCH_SYMBOL_CLASS_FTS_SQL,
            args + listOf(pattern, pattern),
        ),
        ContainsTier(
            SEARCH_SYMBOL_CONTAINS_TIERS[1],
            SEARCH_SYMBOL_METHOD_FTS_SQL,
            args + listOf(pattern, pattern, pattern),
        ),
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
    if (tier.ftsSql != null) {
        try {
            return session.query(tier.ftsSql, tier.ftsArgs!! + listOf(limit)) { it.mapRows(scan) }
        } catch (_: java.sql.SQLException) {
            // Session predates classes_fts/methods_fts; the legacy tier answers.
        }
    }
    return session.query(tier.legacySql, matchArgs(term) + listOf(limit)) { it.mapRows(scan) }
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
    val limit = pageSize + 1
    val offset = (page - 1) * pageSize
    val needed = offset + limit
    var rows = session.query(SEARCH_SYMBOL_FAST_SQL, matchArgs(term) + listOf(needed, 0)) {
        it.mapRows(scan)
    }
    for (tier in symbolContainsTiers(term)) {
        if (rows.size >= needed) break
        rows = rows + runContainsTier(session, tier, term, needed - rows.size, scan)
    }
    var pending = rows.drop(offset)
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

    val sources = if (pending.isNotEmpty()) ManifestCache.loadSourceJars(manifestPath) else null
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
           WHEN m.symbol = p.term THEN 100
           WHEN c.fqn = p.cls AND m.name = p.mname THEN 100
           WHEN m.name = p.term THEN 95
           WHEN m.name >= p.simple AND m.name < p.hi THEN 75
           ELSE 50
         END AS score,
         CASE
           WHEN m.symbol = p.term THEN 'exact_method'
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
  WHERE m.symbol = p.term
     OR (c.fqn = p.cls AND m.name = p.mname)
     OR m.name = p.term
     OR m.name LIKE p.prefix ESCAPE '\'
     OR m.name LIKE p.contains ESCAPE '\'
     OR c.fqn LIKE p.contains ESCAPE '\'
)$METHOD_SEARCH_SELECT_SQL"""

internal const val METHOD_SEARCH_FAST_SQL = """
WITH params(term, simple, prefix, contains, hi, cls, mname) AS (VALUES (?, ?, ?, ?, ?, ?, ?)),
hits(id) AS (
  SELECT m.id FROM methods m, params p WHERE m.symbol = p.term
  UNION
  SELECT m.id FROM methods m JOIN classes c ON c.id = m.class_id, params p
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
  FROM methods m
  JOIN hits h ON h.id = m.id
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
ORDER BY score DESC, kind, name, source_shard_id
LIMIT ? OFFSET ?"""

// Probes each base table with equality and range predicates only, bypassing
// the search_symbols view so every branch uses its own index.
internal const val SEARCH_SYMBOL_FAST_SQL = """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
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
  SELECT 'method', m.symbol, c.fqn, m.descriptor, m.source_shard_id,
         CASE
           WHEN m.symbol = p.term THEN 98
           WHEN m.name = p.simple THEN 92
           ELSE 75
         END,
         CASE
           WHEN m.symbol = p.term THEN 'exact_method'
           WHEN m.name = p.simple THEN 'method_name'
           ELSE 'method_prefix'
         END
  FROM methods m JOIN classes c ON c.id = m.class_id, params p
  WHERE m.symbol = p.term
     OR m.name = p.simple
     OR (p.hi <> '' AND m.name >= p.simple AND m.name < p.hi)
  UNION ALL
  SELECT 'annotation', a.annotation_fqn,
         CASE WHEN a.target_kind = 'class' THEN c.fqn
              WHEN a.target_kind = 'method' THEN m.symbol
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
ORDER BY score DESC, kind, name, source_shard_id
LIMIT ? OFFSET ?"""

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
ORDER BY name, source_shard_id
LIMIT ?""",
    """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
SELECT 'method' AS kind, m.symbol AS name, c.fqn AS owner, m.descriptor AS detail,
       m.source_shard_id AS source_shard_id, 50 AS score, 'method_contains' AS match_reason
FROM methods m JOIN classes c ON c.id = m.class_id, params p
WHERE (m.symbol = p.term
    OR m.name = p.simple
    OR c.fqn = p.term
    OR m.descriptor = p.term
    OR m.symbol LIKE p.prefix ESCAPE '\'
    OR m.name LIKE p.prefix ESCAPE '\'
    OR m.symbol LIKE p.contains ESCAPE '\'
    OR c.fqn LIKE p.contains ESCAPE '\'
    OR m.descriptor LIKE p.contains ESCAPE '\')
  AND NOT COALESCE(
       m.symbol = p.term
    OR m.name = p.simple
    OR (m.name >= p.simple AND m.name < p.hi), 0)
ORDER BY name, source_shard_id
LIMIT ?""",
    """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?))
SELECT 'annotation' AS kind, a.annotation_fqn AS name,
       CASE WHEN a.target_kind = 'class' THEN c.fqn
            WHEN a.target_kind = 'method' THEN m.symbol
            ELSE NULL END AS owner,
       a.attributes AS detail, a.source_shard_id AS source_shard_id,
       45 AS score, 'annotation_contains' AS match_reason
FROM annotations a
LEFT JOIN classes c ON a.target_kind = 'class' AND c.id = a.target_id
LEFT JOIN methods m ON a.target_kind = 'method' AND m.id = a.target_id
, params p
WHERE (a.annotation_fqn = p.term
    OR CASE WHEN a.target_kind = 'class' THEN c.fqn
            WHEN a.target_kind = 'method' THEN m.symbol
            ELSE NULL END = p.term
    OR a.attributes = p.term
    OR a.annotation_fqn LIKE p.prefix ESCAPE '\'
    OR a.annotation_fqn LIKE p.contains ESCAPE '\'
    OR CASE WHEN a.target_kind = 'class' THEN c.fqn
            WHEN a.target_kind = 'method' THEN m.symbol
            ELSE NULL END LIKE p.contains ESCAPE '\'
    OR a.attributes LIKE p.contains ESCAPE '\')
  AND NOT COALESCE(
       a.annotation_fqn = p.term
    OR (a.annotation_fqn >= p.term AND a.annotation_fqn < p.hi), 0)
ORDER BY name, source_shard_id
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
ORDER BY name, source_shard_id
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
ORDER BY name, source_shard_id
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
ORDER BY name, source_shard_id
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
//  - m.name is a trailing substring of m.symbol whenever symbol is NOT NULL
//    (the merge writes symbol = fqn || '#' || name), so name arms fold into
//    the methods_fts symbol arm; NULL-symbol orphans are re-served by their
//    ORIGINAL legacy arms over the ~empty idx_s_methods_orphan partial index.
//    c.fqn is NOT folded into symbol (the parity fixture deliberately breaks
//    that containment): owner arms run through classes_fts joined on
//    class_id instead, which is exact on any data.
//  - the string tier's detail column is m.name || m.descriptor. For terms
//    without '(' a match cannot straddle the boundary (descriptors start
//    with '(', so a straddling occurrence would contain '('), hence
//    detail-contains ≡ name-contains OR descriptor-contains, and
//    detail = t is unsatisfiable. Strings reach their method's rows through
//    idx_s_strconst_class plus sc.method_id = m.id, exact because extraction
//    only attaches a method of the string's own class.
// ---------------------------------------------------------------------------

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

// find-method contains scan. Branches: exact symbol probe (a full 'fqn#name'
// term is not a substring of anything the name arms see), the dotted
// (cls, mname) probe (its '#'-free form never occurs in symbol), the name
// arms (equality/prefix/contains ≡ name-contains, folded into symbol via the
// merge invariant and verified back on m.name), the owner arm via
// classes_fts, and the NULL-symbol orphans re-running their original arms.
internal const val METHOD_SEARCH_FTS_SQL = """
WITH params(term, simple, prefix, contains, hi, cls, mname) AS (VALUES (?, ?, ?, ?, ?, ?, ?)),
hits(id) AS (
  SELECT m.id FROM methods m, params p WHERE m.symbol = p.term
  UNION
  SELECT m.id FROM methods m JOIN classes c ON c.id = m.class_id, params p
  WHERE c.fqn = p.cls AND m.name = p.mname
  UNION
  SELECT m.id FROM methods_fts f JOIN methods m ON m.id = f.rowid, params p
  WHERE f.symbol LIKE ? AND m.name LIKE p.contains ESCAPE '\'
  UNION
  SELECT m.id FROM classes_fts f JOIN methods m ON m.class_id = f.rowid
  WHERE f.fqn LIKE ?
  UNION
  SELECT m.id FROM methods m, params p
  WHERE m.symbol IS NULL
    AND (m.name = p.term
      OR m.name LIKE p.prefix ESCAPE '\'
      OR m.name LIKE p.contains ESCAPE '\')
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
ORDER BY name, source_shard_id
LIMIT ?"""

// search-symbol method tier (score 50): symbol arms (equality/prefix/contains
// plus the name arms via the merge invariant) through the symbol column,
// descriptor arms through the descriptor column (a NULL symbol indexes as ''
// but the descriptor still indexes, so orphans surface here too), owner arms
// through classes_fts, orphan name/descriptor/owner arms re-run verbatim.
internal const val SEARCH_SYMBOL_METHOD_FTS_SQL = """
WITH params(term, simple, prefix, contains, hi) AS (VALUES (?, ?, ?, ?, ?)),
hits(id) AS (
  SELECT rowid FROM methods_fts WHERE symbol LIKE ?
  UNION
  SELECT rowid FROM methods_fts WHERE descriptor LIKE ?
  UNION
  SELECT m.id FROM classes_fts f JOIN methods m ON m.class_id = f.rowid
  WHERE f.fqn LIKE ?
  UNION
  SELECT m.id FROM methods m JOIN classes c ON c.id = m.class_id, params p
  WHERE m.symbol IS NULL
    AND (m.name = p.simple
      OR c.fqn = p.term
      OR m.descriptor = p.term
      OR m.name LIKE p.prefix ESCAPE '\'
      OR c.fqn LIKE p.contains ESCAPE '\'
      OR m.descriptor LIKE p.contains ESCAPE '\')
)
SELECT 'method' AS kind, m.symbol AS name, c.fqn AS owner, m.descriptor AS detail,
       m.source_shard_id AS source_shard_id, 50 AS score, 'method_contains' AS match_reason
FROM hits h
CROSS JOIN methods m ON m.id = h.id
JOIN classes c ON c.id = m.class_id
, params p
WHERE NOT COALESCE(
       m.symbol = p.term
    OR m.name = p.simple
    OR (m.name >= p.simple AND m.name < p.hi), 0)
ORDER BY name, source_shard_id
LIMIT ?"""

// search-symbol string tier (score 30), for terms without '(' only (see
// symbolContainsTiers): value arms through string_constants_fts, owner arms
// through an exact-fqn probe plus classes_fts (each joined to the strings of
// the class via idx_s_strconst_class), and the detail arms decomposed into
// the method's name (methods_fts symbol candidates verified on m.name, plus
// the orphan re-run) and descriptor (methods_fts descriptor column).
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
  WHERE f.symbol LIKE ? AND m.name LIKE p.contains ESCAPE '\'
  UNION
  SELECT sc.id FROM methods m
  JOIN string_constants sc ON sc.class_id = m.class_id AND sc.method_id = m.id
  , params p
  WHERE m.symbol IS NULL AND m.name LIKE p.contains ESCAPE '\'
  UNION
  SELECT sc.id FROM methods_fts f
  JOIN methods m ON m.id = f.rowid
  JOIN string_constants sc ON sc.class_id = m.class_id AND sc.method_id = m.id
  WHERE f.descriptor LIKE ?
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
ORDER BY name, source_shard_id
LIMIT ?"""
