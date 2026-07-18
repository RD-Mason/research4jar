package dev.research4jar.query

/** Hard response bound shared by the CLI, MCP schema, and query engine. */
const val MAX_PAGE_SIZE = 1_000
const val MAX_RESULT_WINDOW = 100_000L

internal data class PageWindow(
    val limit: Int,
    val limitPlusOne: Int,
    val offset: Long,
    val needed: Long,
)

/**
 * Validates public query arguments and computes all pagination arithmetic in
 * Long. In particular, an overflowing Int must never reach SQLite as a
 * negative LIMIT (which SQLite interprets as unlimited).
 */
internal fun pageWindow(page: Int, pageSize: Int): PageWindow {
    require(page >= 1) { "page must be a positive integer" }
    require(pageSize in 1..MAX_PAGE_SIZE) {
        "page_size must be between 1 and $MAX_PAGE_SIZE"
    }
    val offset = Math.multiplyExact((page - 1).toLong(), pageSize.toLong())
    val limitPlusOne = Math.addExact(pageSize, 1)
    val needed = Math.addExact(offset, limitPlusOne.toLong())
    require(needed <= MAX_RESULT_WINDOW + 1L) {
        "requested page exceeds the $MAX_RESULT_WINDOW-result window"
    }
    return PageWindow(pageSize, limitPlusOne, offset, needed)
}

internal fun requirePageSize(pageSize: Int): Int {
    require(pageSize in 1..MAX_PAGE_SIZE) {
        "page_size must be between 1 and $MAX_PAGE_SIZE"
    }
    return pageSize
}
