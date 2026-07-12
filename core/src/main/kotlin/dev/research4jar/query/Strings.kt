package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonProperty
import java.sql.Connection
import java.sql.SQLException

data class StringConstant(
    @JsonProperty("value") val value: String,
    @JsonProperty("class_fqn") val classFqn: String,
    @JsonProperty("method") val method: String?,
    @JsonProperty("source_jar") val sourceJar: String,
)

data class StringSearchResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("results") val results: List<StringConstant>,
    @JsonProperty("total") val total: Int,
    @JsonProperty("page") val page: Int,
    @JsonProperty("page_size") val pageSize: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

// The legacy scan, kept verbatim: it serves terms the trigram index cannot,
// covers sessions built before string_constants_fts existed, and is the
// oracle FindStringParityTest holds the FTS path to.
internal const val FIND_STRING_COUNT_SQL =
    """SELECT COUNT(*) FROM string_constants WHERE value LIKE ? ESCAPE '\'"""

internal const val FIND_STRING_SQL = """
SELECT s.value, c.fqn, m.name, m.descriptor, s.source_shard_id
FROM string_constants s
JOIN classes c ON c.id = s.class_id
LEFT JOIN methods m ON m.id = s.method_id
WHERE s.value LIKE ? ESCAPE '\'
ORDER BY s.value, c.fqn, s.source_shard_id, COALESCE(m.name, ''), COALESCE(m.descriptor, '')
LIMIT ? OFFSET ?"""

// No ESCAPE clause: LIKE ... ESCAPE is a three-argument function that never
// reaches fts5's xBestIndex, so the escaped form would force a linear scan
// of the fts table. Only terms whose unescaped pattern is literal-safe route
// here (trigramSearchable).
internal const val FIND_STRING_FTS_COUNT_SQL =
    "SELECT COUNT(*) FROM string_constants_fts WHERE value LIKE ?"

internal const val FIND_STRING_FTS_SQL = """
SELECT s.value, c.fqn, m.name, m.descriptor, s.source_shard_id
FROM string_constants_fts f
JOIN string_constants s ON s.id = f.rowid
JOIN classes c ON c.id = s.class_id
LEFT JOIN methods m ON m.id = s.method_id
WHERE f.value LIKE ?
ORDER BY s.value, c.fqn, s.source_shard_id, COALESCE(m.name, ''), COALESCE(m.descriptor, '')
LIMIT ? OFFSET ?"""

/**
 * Whether the trigram FTS path may serve [term]. fts5's LIKE pushdown needs
 * one run of three or more non-wildcard codepoints to probe the index —
 * shorter patterns degrade to a scan of the fts table, slower than the
 * legacy scan — and terms containing LIKE metacharacters need the ESCAPE
 * form the pushdown cannot see. Everything else returns byte-identical rows
 * through either path: fts5 verifies each trigram candidate with core LIKE
 * semantics (ASCII-only case folding, NUL truncation), proven against this
 * driver build by FindStringParityTest.
 */
internal fun trigramSearchable(term: String): Boolean =
    term.codePointCount(0, term.length) >= 3 &&
        term.none { it == '%' || it == '_' || it == '\\' }

/**
 * Searches extracted string constants by substring. Useful for locating which
 * jar/class owns a property key, header name, or log message.
 */
fun findString(
    pointer: ProjectPointerData,
    manifestPath: String,
    text: String,
    page: Int,
    pageSize: Int,
): StringSearchResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    val (total, pending) = if (trigramSearchable(text)) {
        try {
            fetchPage(session, FIND_STRING_FTS_COUNT_SQL, FIND_STRING_FTS_SQL, "%$text%", page, pageSize)
        } catch (_: SQLException) {
            // Session predates string_constants_fts; the legacy scan still answers.
            fetchLegacyPage(session, text, page, pageSize)
        }
    } else {
        fetchLegacyPage(session, text, page, pageSize)
    }

    val sources = if (pending.isNotEmpty()) ManifestCache.loadSourceJars(manifestPath) else null
    StringSearchResponse(
        query = SymbolRequest(command = "find-string", arg = text),
        results = pending.map { it.constant.copy(sourceJar = sourceJarName(sources, it.shardId)) },
        total = total,
        page = page,
        pageSize = pageSize,
        coverage = coverageFrom(pointer),
    )
}

private data class PendingString(val constant: StringConstant, val shardId: String)

private fun fetchLegacyPage(
    session: Connection,
    text: String,
    page: Int,
    pageSize: Int,
): Pair<Int, List<PendingString>> =
    fetchPage(session, FIND_STRING_COUNT_SQL, FIND_STRING_SQL, "%${escapeLike(text)}%", page, pageSize)

private fun fetchPage(
    session: Connection,
    countSql: String,
    pageSql: String,
    pattern: String,
    page: Int,
    pageSize: Int,
): Pair<Int, List<PendingString>> {
    val total = session.queryInt(countSql, listOf(pattern))
    val pending = session.query(pageSql, listOf(pattern, pageSize, (page - 1) * pageSize)) { rows ->
        rows.mapRows {
            val methodName = it.getString(3)
            val methodDescriptor = it.getString(4)
            PendingString(
                StringConstant(
                    value = it.getString(1),
                    classFqn = it.getString(2),
                    method = if (methodName != null && methodDescriptor != null) {
                        methodName + methodDescriptor
                    } else {
                        null
                    },
                    sourceJar = "",
                ),
                it.getString(5),
            )
        }
    }
    return total to pending
}
