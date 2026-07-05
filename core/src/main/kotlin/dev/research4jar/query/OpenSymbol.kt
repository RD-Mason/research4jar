package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

data class OpenSymbolResult(
    @JsonProperty("kind") val kind: String,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("class") val classDetail: ClassDetail? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("method") val method: MethodSearchResult? = null,
)

data class OpenSymbolResponse(
    @JsonProperty("query") val query: SymbolRequest,
    @JsonProperty("results") val results: List<OpenSymbolResult>,
    @JsonProperty("total") val total: Int,
    @JsonProperty("coverage") val coverage: Coverage,
)

/**
 * Expands a symbol returned by search-symbol. Class names return full class
 * detail; method names use the "class#method" shape emitted by search-symbol
 * and return the matching method overloads.
 */
fun openSymbol(
    pointer: ProjectPointerData,
    manifestPath: String,
    arg: String,
): OpenSymbolResponse {
    val hash = arg.indexOf('#')
    if (hash >= 0) {
        return openMethodSymbol(
            pointer, manifestPath, arg.substring(0, hash), arg.substring(hash + 1), arg,
        )
    }
    val classResponse = getClass(pointer, manifestPath, arg)
    if (classResponse.total > 0) {
        val results = classResponse.results.map {
            OpenSymbolResult(kind = "class", classDetail = it)
        }
        return OpenSymbolResponse(
            query = SymbolRequest(command = "open-symbol", arg = arg),
            results = results,
            total = results.size,
            coverage = coverageFrom(pointer),
        )
    }
    val methodResponse = findMethod(pointer, manifestPath, arg, 1, 20)
    val results = methodResponse.results.map { OpenSymbolResult(kind = "method", method = it) }
    return OpenSymbolResponse(
        query = SymbolRequest(command = "open-symbol", arg = arg),
        results = results,
        total = methodResponse.total,
        coverage = coverageFrom(pointer),
    )
}

private fun openMethodSymbol(
    pointer: ProjectPointerData,
    manifestPath: String,
    classFqn: String,
    methodName: String,
    arg: String,
): OpenSymbolResponse = Db.openReadOnly(pointer.sessionDbPath, immutable = true).use { session ->
    data class Pending(val method: MethodSearchResult, val shardId: String)
    val pending = session.query(
        """
        SELECT c.fqn, m.name, m.descriptor, m.return_fqn, m.modifiers, m.source_shard_id
        FROM methods m
        JOIN classes c ON c.id = m.class_id
        WHERE m.symbol = ?
        ORDER BY m.descriptor, m.source_shard_id
        """.trimIndent(),
        listOf("$classFqn#$methodName"),
    ) { rows ->
        rows.mapRows {
            Pending(
                MethodSearchResult(
                    classFqn = it.getString(1),
                    name = it.getString(2),
                    descriptor = it.getString(3),
                    returnFqn = it.getString(4),
                    modifiers = it.getInt(5),
                    sourceJar = "",
                    score = 100,
                    matchReason = "exact_method",
                ),
                it.getString(6),
            )
        }
    }

    val sources = if (pending.isNotEmpty()) ManifestCache.loadSourceJars(manifestPath) else null
    val results = pending.map {
        OpenSymbolResult(
            kind = "method",
            method = it.method.copy(sourceJar = sourceJarName(sources, it.shardId)),
        )
    }
    OpenSymbolResponse(
        query = SymbolRequest(command = "open-symbol", arg = arg),
        results = results,
        total = results.size,
        coverage = coverageFrom(pointer),
    )
}
