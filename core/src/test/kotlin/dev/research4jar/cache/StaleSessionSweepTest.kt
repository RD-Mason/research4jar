package dev.research4jar.cache

import dev.research4jar.indexer.Research4JarPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaleSessionSweepTest {
    private val home = createTempDirectory("r4j-sweep")

    @AfterTest
    fun cleanup() {
        home.toFile().deleteRecursively()
    }

    private fun sessionFile(name: String, age: Duration): Path {
        val dataPaths = Research4JarPaths.resolve(home.toString())
        Files.createDirectories(dataPaths.sessions)
        val file = dataPaths.sessions.resolve(name)
        Files.write(file, ByteArray(64))
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)))
        return file
    }

    @Test
    fun `removes only sessions older than the cutoff`() {
        val stale = sessionFile("stale.db", Duration.ofDays(45))
        val fresh = sessionFile("fresh.db", Duration.ofDays(2))
        val notASession = sessionFile("notes.txt", Duration.ofDays(400))

        val result = collectStaleSessions(
            Research4JarPaths.resolve(home.toString()),
            Duration.ofDays(30),
        )

        assertEquals(1, result.removed)
        assertEquals(64L, result.reclaimedBytes)
        assertFalse(Files.exists(stale), "stale session must be removed")
        assertTrue(Files.exists(fresh), "fresh session must survive")
        assertTrue(Files.exists(notASession), "non-.db files must never be touched")
    }

    @Test
    fun `missing sessions directory is a no-op`() {
        val result = collectStaleSessions(
            Research4JarPaths.resolve(home.resolve("nowhere").toString()),
            Duration.ofDays(30),
        )
        assertEquals(0, result.removed)
        assertEquals(0L, result.reclaimedBytes)
    }
}
