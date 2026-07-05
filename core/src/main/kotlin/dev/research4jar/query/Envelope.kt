package dev.research4jar.query

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Shared response envelope types for the query engine, ported from
 * querier/internal/query (Go). JSON key sets must stay identical to the Go
 * querier: tests/e2e.sh asserts structural JSON equality. Go `omitempty`
 * fields map to explicit [JsonInclude] annotations here; everything else is
 * always emitted, including nulls and empty arrays.
 */
data class Coverage(
    @JsonProperty("jars_total") val jarsTotal: Int,
    @JsonProperty("jars_indexed") val jarsIndexed: Int,
    @JsonProperty("jars_missing") val jarsMissing: List<String>,
    @JsonProperty("extractor_version") val extractorVersion: Int,
)

data class Request(
    @JsonProperty("command") val command: String,
    @JsonProperty("prefix") val prefix: String,
)

data class SymbolRequest(
    @JsonProperty("command") val command: String,
    @JsonProperty("arg") val arg: String,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("direct") val direct: Boolean = false,
)

fun coverageFrom(pointer: ProjectPointerData): Coverage = Coverage(
    jarsTotal = pointer.coverage.jarsTotal,
    jarsIndexed = pointer.coverage.jarsIndexed,
    jarsMissing = pointer.coverage.jarsMissing ?: emptyList(),
    extractorVersion = pointer.extractorVersion,
)

/** Escapes LIKE wildcards with backslash, mirroring the Go escapeLike. */
fun escapeLike(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** Source-jar attribution: manifest coordinate/filename, else the shard id. */
fun sourceJarName(sources: Map<String, String>?, shardId: String): String {
    val source = sources?.get(shardId)
    return if (source.isNullOrEmpty()) shardId else source
}

/**
 * Smallest string greater than every string starting with [term] ("" when no
 * finite bound exists). Range predicates treat "" bounds as empty via
 * `col < ''` being false for all real values, which sends the query down the
 * legacy contains path — always correct, just slower. Non-ASCII terms return
 * "" outright: JDBC re-encodes bound strings as UTF-8, so a byte-level
 * increment (the Go approach) is not representable; UTF-8 preserves
 * code-point order, but symbol searches are overwhelmingly ASCII and the
 * fallback stays correct.
 */
fun prefixUpperBound(term: String): String {
    if (term.isEmpty() || term.any { it.code > 0x7F }) return ""
    val chars = term.toCharArray()
    for (i in chars.indices.reversed()) {
        if (chars[i].code < 0x7F) {
            chars[i] = chars[i] + 1
            return String(chars, 0, i + 1)
        }
    }
    return ""
}

/** lastIndexOf('.') split into (package, simpleName), mirroring Go splitFQN. */
fun splitFqn(fqn: String): Pair<String, String> {
    val index = fqn.lastIndexOf('.')
    return if (index < 0) "" to fqn else fqn.substring(0, index) to fqn.substring(index + 1)
}
