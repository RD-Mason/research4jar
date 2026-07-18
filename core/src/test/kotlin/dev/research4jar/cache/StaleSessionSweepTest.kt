package dev.research4jar.cache

import dev.research4jar.indexer.Research4JarPaths
import dev.research4jar.indexer.Research4JarVersions
import dev.research4jar.runtime.SessionFileLease
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    private fun sessionTemporary(name: String, age: Duration, hidden: Boolean = true): Path {
        val dataPaths = Research4JarPaths.resolve(home.toString())
        Files.createDirectories(dataPaths.sessions)
        val prefix = if (hidden) ".session." else ""
        val file = dataPaths.sessions.resolve("$prefix$name.tmp")
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
        assertFalse(Files.exists(stale.resolveSibling(".${stale.fileName}.lease")))
        assertFalse(Files.exists(stale.resolveSibling(".${stale.fileName}.lease-turnstile")))
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

    @Test
    fun `automatic sweep removes abandoned temporaries but preserves active builds`() {
        val expiredSession = sessionFile("expired.db", Duration.ofDays(45))
        val abandoned = sessionTemporary("abandoned", Duration.ofDays(2))
        val active = sessionTemporary("active", Duration.ofHours(1))
        val unrelated = sessionTemporary("notes", Duration.ofDays(2), hidden = false)

        // Null disables ordinary session expiry, but crash leftovers must
        // still be reclaimed even when the user keeps completed sessions.
        val result = collectStaleSessions(
            Research4JarPaths.resolve(home.toString()),
            maxAge = null,
        )

        assertEquals(1, result.removed)
        assertEquals(0, result.removedSessions)
        assertEquals(1, result.removedTemporaries)
        assertFalse(Files.exists(abandoned))
        assertTrue(Files.exists(active), "a recent temporary is an active-build lease")
        assertTrue(Files.exists(expiredSession), "session expiry is disabled")
        assertTrue(Files.exists(unrelated), "only hidden AtomicFiles temporaries are cache-owned")
    }

    @Test
    fun `zero duration disables completed session expiry`() {
        val publishedSession = sessionFile("published.db", Duration.ZERO)
        val abandoned = sessionTemporary("abandoned-zero", Duration.ofDays(2))

        val result = collectStaleSessions(
            Research4JarPaths.resolve(home.toString()),
            Duration.ZERO,
        )

        assertEquals(0, result.removedSessions)
        assertEquals(1, result.removedTemporaries)
        assertTrue(Files.exists(publishedSession), "0d and 0h must not delete a newly published session")
        assertFalse(Files.exists(abandoned), "zero only disables completed-session expiry")
    }

    @Test
    fun `collector waits for a query lease and rechecks refreshed mtime`() {
        val session = sessionFile("raced.db", Duration.ofDays(45))
        val cutoff = Instant.now().minus(Duration.ofDays(30))
        val queryLease = SessionFileLease.acquireShared(session)
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val deletion = executor.submit<Boolean> {
                started.countDown()
                deleteIfStillStaleSession(session, cutoff)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(50)
            assertFalse(deletion.isDone, "exclusive deletion must wait for an open query")

            Files.setLastModifiedTime(session, FileTime.from(Instant.now()))
            queryLease.close()

            assertFalse(deletion.get(5, TimeUnit.SECONDS), "collector must recheck after acquiring its lease")
            assertTrue(Files.exists(session), "a query-refreshed session must remain addressable")
        } finally {
            queryLease.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `cache stats and gc expose stale session temporaries as orphans`() {
        val abandoned = sessionTemporary("gc-abandoned", Duration.ofDays(2))
        val active = sessionTemporary("gc-active", Duration.ofHours(1))
        val dataPaths = Research4JarPaths.resolve(home.toString())

        val before = collectStats(dataPaths, Research4JarVersions.EXTRACTOR)
        assertEquals(1, before.orphanFiles)
        assertEquals(64L, before.orphanBytes)

        val result = gc(
            dataPaths,
            Research4JarVersions.EXTRACTOR,
            GCOptions(),
        )
        assertEquals(1, result.removedOrphans)
        assertEquals(64L, result.reclaimedBytes)
        assertFalse(Files.exists(abandoned))
        assertTrue(Files.exists(active))

        val after = collectStats(dataPaths, Research4JarVersions.EXTRACTOR)
        assertEquals(0, after.orphanFiles)
        assertEquals(0L, after.orphanBytes)
    }
}
