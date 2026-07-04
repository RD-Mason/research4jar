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

// Two-stage search shared by find-class/find-method/search-symbol: the fast
// query probes only equality and range predicates (index-backed); its every
// match outscores every contains-tier match, so a full fast page equals the
// legacy page. Underfilled pages fall back to the legacy contains scan.
private fun <T> twoStage(
    session: java.sql.Connection,
    fastSql: String,
    legacySql: String,
    args: List<Any?>,
    page: Int,
    pageSize: Int,
    scan: (java.sql.ResultSet) -> T,
): List<T> {
    val limitArgs = args + listOf(pageSize + 1, (page - 1) * pageSize)
    val fast = session.query(fastSql, limitArgs) { it.mapRows(scan) }
    if (fast.size > pageSize) return fast
    return session.query(legacySql, limitArgs) { it.mapRows(scan) }
}

fun findClass(
    pointer: ProjectPointerData,
    manifestPath: String,
    term: String,
    page: Int,
    pageSize: Int,
): ClassSearchResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    data class Pending(val result: ClassSearchResult, val shardId: String)
    var pending = twoStage(session, CLASS_SEARCH_FAST_SQL, CLASS_SEARCH_SQL, matchArgs(term), page, pageSize) {
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
        session, METHOD_SEARCH_FAST_SQL, METHOD_SEARCH_SQL, methodMatchArgs(term), page, pageSize,
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

fun searchSymbol(
    pointer: ProjectPointerData,
    manifestPath: String,
    term: String,
    page: Int,
    pageSize: Int,
): SearchSymbolResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    data class Pending(val result: SearchSymbolResult, val shardId: String)
    var pending = twoStage(
        session, SEARCH_SYMBOL_FAST_SQL, SEARCH_SYMBOL_SQL, matchArgs(term), page, pageSize,
    ) {
        Pending(
            SearchSymbolResult(
                kind = it.getString(1),
                name = it.getString(2),
                owner = it.getString(3),
                detail = it.getString(4),
                sourceJar = "",
                score = it.getInt(6),
                matchReason = it.getString(7),
            ),
            it.getString(5),
        )
    }
    val hasMore = pending.size > pageSize
    if (hasMore) pending = pending.subList(0, pageSize)

    val sources = if (pending.isNotEmpty()) ManifestCache.loadSourceJars(manifestPath) else null
    val results = pending.map { it.result.copy(sourceJar = sourceJarName(sources, it.shardId)) }
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
