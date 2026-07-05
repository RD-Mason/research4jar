package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonProperty

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
    val pattern = "%${escapeLike(text)}%"
    val total = session.queryInt(
        "SELECT COUNT(*) FROM string_constants WHERE value LIKE ? ESCAPE '\\'",
        listOf(pattern),
    )

    data class Pending(val constant: StringConstant, val shardId: String)
    val pending = session.query(
        """
        SELECT s.value, c.fqn, m.name, m.descriptor, s.source_shard_id
        FROM string_constants s
        JOIN classes c ON c.id = s.class_id
        LEFT JOIN methods m ON m.id = s.method_id
        WHERE s.value LIKE ? ESCAPE '\'
        ORDER BY s.value, c.fqn, s.source_shard_id, COALESCE(m.name, ''), COALESCE(m.descriptor, '')
        LIMIT ? OFFSET ?
        """.trimIndent(),
        listOf(pattern, pageSize, (page - 1) * pageSize),
    ) { rows ->
        rows.mapRows {
            val methodName = it.getString(3)
            val methodDescriptor = it.getString(4)
            Pending(
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
