package dev.springdep.indexer

import java.nio.file.Path
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
        assertEquals(expected("/custom/springdep"), paths.home.toString())
    }

    @Test
    fun `environment home wins over platform default`() {
        val paths = SpringDepPaths.resolve(
            environment = mapOf("SPRINGDEP_HOME" to "/environment"),
            osName = "Mac OS X",
            userHome = "/Users/test",
        )
        assertEquals(expected("/environment"), paths.home.toString())
    }

    @Test
    fun `platform defaults follow specification`() {
        assertEquals(
            expected("/Users/test/Library/Application Support/springdep"),
            SpringDepPaths.resolve(
                environment = emptyMap(),
                osName = "Mac OS X",
                userHome = "/Users/test",
            ).home.toString(),
        )
        assertEquals(
            expected("/xdg/springdep"),
            SpringDepPaths.resolve(
                environment = mapOf("XDG_DATA_HOME" to "/xdg"),
                osName = "Linux",
                userHome = "/home/test",
            ).home.toString(),
        )
        assertEquals(
            expected("/home/test/.local/share/springdep"),
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
            expected("/Users/test/Library/Application Support/springdep"),
            paths.home.toString(),
        )
    }

    private fun expected(value: String): String =
        Path.of(value).toAbsolutePath().normalize().toString()
}
