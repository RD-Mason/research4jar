package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonRawValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.ResultSet

data class SymbolResult(
    @JsonProperty("fqn") val fqn: String,
    @JsonProperty("source_jar") val sourceJar: String,
    @JsonRawValue
    @JsonProperty("attributes") val attributes: String?,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("matched_annotation") val matchedAnnotation: String = "",
)

data class SymbolResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("results") val results: List<SymbolResult>,
    @JsonProperty("total") val total: Int,
    @JsonProperty("page") val page: Int,
    @JsonProperty("page_size") val pageSize: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

// Transitive closure over declared supertypes: the seed FQN expands through
// super_fqn edges and class_interfaces edges, both stored as symbolic
// references, so the walk crosses jar boundaries inside the merged session.
private const val TRANSITIVE_IMPL_CTE = """
WITH RECURSIVE impl(fqn) AS (
  VALUES(?)
  UNION
  SELECT c.fqn FROM classes c JOIN impl ON c.super_fqn = impl.fqn
  UNION
  SELECT c.fqn FROM classes c
    JOIN class_interfaces ci ON ci.class_id = c.id
    JOIN impl ON ci.interface_fqn = impl.fqn
)"""

// Meta-annotation closure: an annotation type (kind='annotation') annotated by
// a member of the set joins the set. Plain UNION deduplicates, which also
// terminates self-referential annotations such as @Documented.
private const val META_ANNOTATION_CTE = """
WITH RECURSIVE meta(fqn) AS (
  VALUES(?)
  UNION
  SELECT c.fqn
  FROM classes c
  JOIN annotations a ON a.target_kind = 'class' AND a.target_id = c.id
  JOIN meta ON a.annotation_fqn = meta.fqn
  WHERE c.kind = 'annotation'
)"""

private val rawJsonMapper = jacksonObjectMapper()

private fun validRawJson(text: String?): String? {
    if (text == null) return null
    return try {
        rawJsonMapper.readTree(text)
        text
    } catch (_: com.fasterxml.jackson.core.JacksonException) {
        null
    }
}

fun findImplementations(
    pointer: ProjectPointerData,
    manifestPath: String,
    targetFqn: String,
    direct: Boolean,
    page: Int,
    pageSize: Int,
): SymbolResponse {
    var countSql = TRANSITIVE_IMPL_CTE + """
        SELECT COUNT(*) FROM classes c
        WHERE c.fqn IN (SELECT fqn FROM impl) AND c.fqn <> ?"""
    var selectSql = TRANSITIVE_IMPL_CTE + """
        SELECT c.fqn, c.source_shard_id
        FROM classes c
        WHERE c.fqn IN (SELECT fqn FROM impl) AND c.fqn <> ?
        ORDER BY c.fqn, c.source_shard_id
        LIMIT ? OFFSET ?"""
    if (direct) {
        countSql = """SELECT COUNT(*) FROM (
            SELECT class_id FROM class_interfaces WHERE interface_fqn = ?
            UNION
            SELECT id FROM classes WHERE super_fqn = ?
        )"""
        selectSql = """SELECT c.fqn, c.source_shard_id
            FROM classes c
            WHERE c.id IN (
              SELECT class_id FROM class_interfaces WHERE interface_fqn = ?
              UNION
              SELECT id FROM classes WHERE super_fqn = ?
            )
            ORDER BY c.fqn, c.source_shard_id
            LIMIT ? OFFSET ?"""
    }
    return findSymbols(
        pointer,
        manifestPath,
        SymbolRequest(command = "find-implementations", arg = targetFqn, direct = direct),
        listOf(targetFqn, targetFqn),
        page,
        pageSize,
        countSql,
        selectSql,
    ) { rows ->
        SymbolResult(
            fqn = rows.getString(1),
            sourceJar = "",
            attributes = null,
        ) to rows.getString(2)
    }
}

fun findByAnnotation(
    pointer: ProjectPointerData,
    manifestPath: String,
    annotationFqn: String,
    direct: Boolean,
    page: Int,
    pageSize: Int,
): SymbolResponse {
    var countSql = META_ANNOTATION_CTE + """
        SELECT COUNT(*)
        FROM annotations a
        JOIN classes c ON c.id = a.target_id
        WHERE a.target_kind = 'class' AND a.annotation_fqn IN (SELECT fqn FROM meta)"""
    var selectSql = META_ANNOTATION_CTE + """
        SELECT c.fqn, c.source_shard_id, a.attributes, a.annotation_fqn
        FROM annotations a
        JOIN classes c ON c.id = a.target_id
        WHERE a.target_kind = 'class' AND a.annotation_fqn IN (SELECT fqn FROM meta)
        ORDER BY c.fqn, c.source_shard_id, a.annotation_fqn, COALESCE(a.attributes, '')
        LIMIT ? OFFSET ?"""
    if (direct) {
        countSql = """SELECT COUNT(*)
            FROM annotations a
            JOIN classes c ON c.id = a.target_id
            WHERE a.target_kind = 'class' AND a.annotation_fqn = ?"""
        selectSql = """SELECT c.fqn, c.source_shard_id, a.attributes, a.annotation_fqn
            FROM annotations a
            JOIN classes c ON c.id = a.target_id
            WHERE a.target_kind = 'class' AND a.annotation_fqn = ?
            ORDER BY c.fqn, c.source_shard_id, a.annotation_fqn, COALESCE(a.attributes, '')
            LIMIT ? OFFSET ?"""
    }
    return findSymbols(
        pointer,
        manifestPath,
        SymbolRequest(command = "find-by-annotation", arg = annotationFqn, direct = direct),
        listOf(annotationFqn),
        page,
        pageSize,
        countSql,
        selectSql,
    ) { rows ->
        SymbolResult(
            fqn = rows.getString(1),
            sourceJar = "",
            attributes = validRawJson(rows.getString(3)),
            matchedAnnotation = rows.getString(4),
        ) to rows.getString(2)
    }
}

private fun findSymbols(
    pointer: ProjectPointerData,
    manifestPath: String,
    request: SymbolRequest,
    bindArgs: List<Any?>,
    page: Int,
    pageSize: Int,
    countSql: String,
    selectSql: String,
    scan: (ResultSet) -> Pair<SymbolResult, String>,
): SymbolResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    val window = pageWindow(page, pageSize)
    val total = session.queryInt(countSql, bindArgs)
    val pending = session.query(
        selectSql,
        bindArgs + listOf(window.limit, window.offset),
    ) { rows -> rows.mapRows(scan) }

    val sources = if (pending.isNotEmpty()) {
        ManifestCache.loadSourceJars(session, manifestPath, pending.map { it.second })
    } else {
        null
    }
    SymbolResponse(
        query = request,
        results = pending.map { (result, shardId) ->
            result.copy(sourceJar = sourceJarName(sources, shardId))
        },
        total = total,
        page = page,
        pageSize = pageSize,
        coverage = coverageFrom(pointer),
    )
}
