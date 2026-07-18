package dev.research4jar.registry

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GoJsonTest {
    @Test
    fun `streaming encoder is byte-identical and leaves its target open`() {
        val value = linkedMapOf(
            "text" to "<tag>&\u0008\u000c",
            "items" to listOf(1, 2),
            "empty" to emptyMap<String, String>(),
        )
        val expected = """
            {
              "text": "<tag>&\b\f",
              "items": [
                1,
                2
              ],
              "empty": {}
            }

        """.trimIndent()
        val bytes = ByteArrayOutputStream()
        var closed = false
        val target = object : FilterOutputStream(bytes) {
            override fun close() {
                closed = true
                super.close()
            }
        }

        GoJson.encodeIndent(value, target)

        assertEquals(expected, String(bytes.toByteArray(), StandardCharsets.UTF_8))
        assertFalse(closed)
        assertEquals(expected, GoJson.encodeIndent(value))
    }
}
