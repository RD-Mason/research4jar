package dev.springdep.indexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PathsTest {
    @Test
    fun `explicit home wins`() {
        val paths = SpringDepPaths.resolve(
            explicitHome = "/custom/springdep",
            environment = mapOf("SPRINGDEP_HOME" to "/environment"),
            osName = "Linux",
            userHome = "/home/user",
        )
        assertEquals("/custom/springdep", paths.home.toString())
    }

    @Test
    fun `environment home wins over platform default`() {
        val paths = SpringDepPaths.resolve(
            environment = mapOf("SPRINGDEP_HOME" to "/environment"),
            osName = "Mac OS X",
            userHome = "/Users/test",
        )
        assertEquals("/environment", paths.home.toString())
    }

    @Test
    fun `platform defaults follow specification`() {
        assertEquals(
            "/Users/test/Library/Application Support/springdep",
            SpringDepPaths.resolve(
                environment = emptyMap(),
                osName = "Mac OS X",
                userHome = "/Users/test",
            ).home.toString(),
        )
        assertEquals(
            "/xdg/springdep",
            SpringDepPaths.resolve(
                environment = mapOf("XDG_DATA_HOME" to "/xdg"),
                osName = "Linux",
                userHome = "/home/test",
            ).home.toString(),
        )
        assertEquals(
            "/home/test/.local/share/springdep",
            SpringDepPaths.resolve(
                environment = emptyMap(),
                osName = "Linux",
                userHome = "/home/test",
            ).home.toString(),
        )
    }

    @Test
    fun `windows requires local app data`() {
        assertFailsWith<IllegalStateException> {
            SpringDepPaths.resolve(
                environment = emptyMap(),
                osName = "Windows 11",
                userHome = "C:\\Users\\test",
            )
        }
    }

    @Test
    fun `darwin is not mistaken for windows`() {
        val paths = SpringDepPaths.resolve(
            environment = emptyMap(),
            osName = "Darwin",
            userHome = "/Users/test",
        )
        assertEquals(
            "/Users/test/Library/Application Support/springdep",
            paths.home.toString(),
        )
    }
}
