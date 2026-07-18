package dev.research4jar.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaginationTest {
    @Test
    fun `offset arithmetic stays long and bounded`() {
        val window = pageWindow(page = 5_000, pageSize = 20)
        assertEquals(99_980L, window.offset)
        assertEquals(100_001L, window.needed)

        assertFailsWith<IllegalArgumentException> {
            pageWindow(Int.MAX_VALUE, MAX_PAGE_SIZE)
        }
        assertFailsWith<IllegalArgumentException> {
            pageWindow(1, Int.MAX_VALUE)
        }
    }

    @Test
    fun `non-positive pagination is rejected at the query boundary`() {
        assertFailsWith<IllegalArgumentException> { pageWindow(0, 20) }
        assertFailsWith<IllegalArgumentException> { pageWindow(1, 0) }
    }
}
