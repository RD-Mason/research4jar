package dev.research4jar.runtime

import dev.research4jar.query.Db
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class SessionFileLeaseTest {
    @Test
    fun `repeated session churn releases local states and per-session sidecars`() {
        val directory = Files.createTempDirectory("r4j-lease-churn")
        val initialSessionStates = SessionFileLease.trackedSessionStateCount()
        val initialDirectoryStates = SessionFileLease.trackedDirectoryStateCount()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 400).map { index ->
                executor.submit {
                    start.await()
                    val session = directory.resolve("session-$index.db")
                    Files.write(session, byteArrayOf(1))
                    SessionFileLease.acquireShared(session).close()
                    assertTrue(
                        SessionFileLease.withExclusiveReclamation(session) {
                            Files.deleteIfExists(session)
                        },
                    )
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(initialSessionStates, SessionFileLease.trackedSessionStateCount())
        assertEquals(initialDirectoryStates, SessionFileLease.trackedDirectoryStateCount())
        val remaining = Files.list(directory).use { files ->
            files.map { it.fileName.toString() }.toList().toSet()
        }
        assertEquals(
            setOf(".session-leases", ".session-leases-turnstile"),
            remaining,
            "only the fixed directory coordinator may survive session churn",
        )
    }

    @Test
    fun `reclamation keeps sidecars when action retains the session`() {
        val directory = Files.createTempDirectory("r4j-lease-retained")
        val session = directory.resolve("session.db")
        val resource = directory.resolve(".session.db.lease")
        val turnstile = directory.resolve(".session.db.lease-turnstile")
        Files.write(session, byteArrayOf(1))
        SessionFileLease.acquireShared(session).close()

        assertFalse(SessionFileLease.withExclusiveReclamation(session) { false })
        assertTrue(Files.exists(session))
        assertTrue(Files.exists(resource))
        assertTrue(Files.exists(turnstile))

        assertTrue(
            SessionFileLease.withExclusiveReclamation(session) { Files.deleteIfExists(session) },
        )
        assertFalse(Files.exists(resource))
        assertFalse(Files.exists(turnstile))
    }

    @Test
    fun `queued second collector does not recreate reclaimed sidecars`() {
        val directory = Files.createTempDirectory("r4j-lease-double-collector")
        val session = directory.resolve("session.db")
        val resource = directory.resolve(".session.db.lease")
        val turnstile = directory.resolve(".session.db.lease-turnstile")
        Files.write(session, byteArrayOf(1))
        val firstDeleted = CountDownLatch(1)
        val finishFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val firstResult = AtomicReference<Boolean?>()
        val secondResult = AtomicReference<Boolean?>()
        val secondActions = AtomicInteger()

        val first = thread(start = true, name = "session-lease-first-collector") {
            firstResult.set(
                SessionFileLease.withExclusiveReclamation(session) {
                    val removed = Files.deleteIfExists(session)
                    firstDeleted.countDown()
                    finishFirst.await(5, TimeUnit.SECONDS)
                    removed
                },
            )
        }
        assertTrue(firstDeleted.await(5, TimeUnit.SECONDS))
        val second = thread(start = true, name = "session-lease-second-collector") {
            secondStarted.countDown()
            secondResult.set(
                SessionFileLease.withExclusiveReclamation(session) {
                    secondActions.incrementAndGet()
                    Files.deleteIfExists(session)
                },
            )
        }
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (second.state != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.WAITING, second.state, "second collector must queue behind the first")
        } finally {
            finishFirst.countDown()
        }

        first.join(5_000)
        second.join(5_000)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertEquals(true, firstResult.get())
        assertEquals(false, secondResult.get())
        assertEquals(0, secondActions.get(), "a missing session has no reclamation action to run")
        assertFalse(Files.exists(resource))
        assertFalse(Files.exists(turnstile))
    }

    @Test
    fun `cross process reader cannot lock an unlinked sidecar during cleanup`() {
        val directory = Files.createTempDirectory("r4j-lease-cross-cleanup")
        val session = directory.resolve("session.db")
        val resource = directory.resolve(".session.db.lease")
        val turnstile = directory.resolve(".session.db.lease-turnstile")
        Files.write(session, byteArrayOf(1))
        val java = Paths.get(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
        )
        val collector = ProcessBuilder(
            java.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            SessionLeaseProbeMain::class.java.name,
            "collect",
            session.toString(),
        ).redirectErrorStream(true).start()
        val output = collector.inputStream.bufferedReader()
        val input = collector.outputStream.bufferedWriter()
        val readerStarted = CountDownLatch(1)
        val readerFailure = AtomicReference<Throwable?>()
        var reader: Thread? = null
        try {
            assertEquals("READY", output.readLine())
            assertEquals("LOCKED", output.readLine())
            reader = thread(start = true, name = "session-lease-cross-process-reader") {
                readerStarted.countDown()
                try {
                    SessionFileLease.acquireShared(session).close()
                } catch (exception: Throwable) {
                    readerFailure.set(exception)
                }
            }
            assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
            // The child already owns the per-session turnstile. Let this JVM
            // enter at least one directory-shared try-lock attempt before the
            // child advances to unlink and directory-exclusive cleanup.
            Thread.sleep(100)
            input.write("DELETE\n")
            input.flush()

            assertEquals("DELETED", output.readLine())
            assertEquals("RECLAIMED", output.readLine())
            assertTrue(collector.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, collector.exitValue())
            reader.join(5_000)
            assertFalse(reader.isAlive)
            assertIs<NoSuchFileException>(readerFailure.get())
            assertFalse(Files.exists(resource))
            assertFalse(Files.exists(turnstile))
        } finally {
            input.close()
            reader?.interrupt()
            reader?.join(5_000)
            collector.destroyForcibly()
        }
    }

    @Test
    fun `stale shared acquire after reclamation does not recreate per-session sidecars`() {
        val directory = Files.createTempDirectory("r4j-lease-missing")
        val session = directory.resolve("session.db")
        val resource = directory.resolve(".session.db.lease")
        val turnstile = directory.resolve(".session.db.lease-turnstile")
        Files.write(session, byteArrayOf(1))
        SessionFileLease.acquireShared(session).close()
        val collectorEntered = CountDownLatch(1)
        val readerFailure = AtomicReference<Throwable?>()
        val reader = thread(start = true, name = "session-lease-stale-reader") {
            collectorEntered.await(5, TimeUnit.SECONDS)
            try {
                SessionFileLease.acquireShared(session).close()
            } catch (exception: Throwable) {
                readerFailure.set(exception)
            }
        }

        assertTrue(
            SessionFileLease.withExclusiveReclamation(session) {
                val removed = Files.deleteIfExists(session)
                collectorEntered.countDown()
                // Give the stale reader a chance to queue behind this local
                // writer before directory-exclusive cleanup begins.
                Thread.yield()
                removed
            },
        )
        reader.join(5_000)
        assertFalse(reader.isAlive)
        assertIs<NoSuchFileException>(readerFailure.get())
        assertFalse(Files.exists(resource))
        assertFalse(Files.exists(turnstile))

        assertFailsWith<NoSuchFileException> {
            SessionFileLease.acquireShared(session)
        }
        assertFalse(Files.exists(resource), "a missing-session query must not recreate its resource sidecar")
        assertFalse(Files.exists(turnstile), "a missing-session query must not recreate its turnstile")
    }

    @Test
    fun `concurrent local readers and writers never overlap file channels`() {
        val session = Files.createTempDirectory("r4j-lease-stress").resolve("session.db")
        Files.write(session, byteArrayOf(1))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val activeReaders = AtomicInteger()
        val activeWriter = AtomicBoolean()
        try {
            val futures = (0 until 8).map { worker ->
                executor.submit {
                    start.await()
                    repeat(100) { iteration ->
                        if ((worker + iteration) % 7 == 0) {
                            SessionFileLease.acquireExclusive(session).use {
                                assertTrue(activeWriter.compareAndSet(false, true))
                                assertEquals(0, activeReaders.get())
                                Thread.yield()
                                activeWriter.set(false)
                            }
                        } else {
                            SessionFileLease.acquireShared(session).use {
                                assertFalse(activeWriter.get())
                                activeReaders.incrementAndGet()
                                try {
                                    Thread.yield()
                                    assertFalse(activeWriter.get())
                                } finally {
                                    activeReaders.decrementAndGet()
                                }
                            }
                        }
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `closing a second reader cannot release the first readers cross process lock`() {
        val session = Files.createTempDirectory("r4j-lease-process").resolve("session.db")
        Files.write(session, byteArrayOf(1))
        val firstReader = SessionFileLease.acquireShared(session)
        var probe: Process? = null
        try {
            SessionFileLease.acquireShared(session).close()

            val java = Paths.get(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
            )
            probe = ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                SessionLeaseProbeMain::class.java.name,
                session.toString(),
            ).redirectErrorStream(true).start()
            assertEquals("READY", probe.inputStream.bufferedReader().readLine())
            assertFalse(
                probe.waitFor(300, TimeUnit.MILLISECONDS),
                "another JVM must remain blocked while the first shared lease is open",
            )
        } finally {
            firstReader.close()
        }

        try {
            assertTrue(probe!!.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, probe.exitValue())
        } finally {
            probe?.destroyForcibly()
        }
    }

    @Test
    fun `symlink aliases share one local state and preserve the cross process lock`() {
        val root = Files.createTempDirectory("r4j-lease-alias")
        val realDirectory = Files.createDirectories(root.resolve("real"))
        val session = realDirectory.resolve("session.db")
        Files.write(session, byteArrayOf(1))
        val aliasDirectory = root.resolve("alias")
        val symlinkCreated = try {
            Files.createSymbolicLink(aliasDirectory, realDirectory)
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IOException) {
            false
        }
        assumeTrue(symlinkCreated, "symbolic links are unavailable on this test host")

        val firstReader = SessionFileLease.acquireShared(session)
        var probe: Process? = null
        try {
            // This second spelling reaches the same session and sidecar
            // inodes. It must reuse the first reader's LocalState; otherwise
            // its close can release the first lock on Darwin.
            SessionFileLease.acquireShared(aliasDirectory.resolve(session.fileName)).close()

            val java = Paths.get(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
            )
            probe = ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                SessionLeaseProbeMain::class.java.name,
                session.toString(),
            ).redirectErrorStream(true).start()
            assertEquals("READY", probe.inputStream.bufferedReader().readLine())
            assertFalse(
                probe.waitFor(300, TimeUnit.MILLISECONDS),
                "the first alias must retain its cross-process shared lock",
            )
        } finally {
            firstReader.close()
        }

        try {
            assertTrue(probe!!.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, probe.exitValue())
        } finally {
            probe?.destroyForcibly()
        }
    }

    @Test
    fun `queued writer is not starved by later readers`() {
        val session = Files.createTempDirectory("r4j-lease-priority").resolve("session.db")
        Files.write(session, byteArrayOf(1))
        val firstReader = SessionFileLease.acquireShared(session)
        val writerAcquired = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val secondReaderAcquired = CountDownLatch(1)

        val writer = thread(start = true, name = "session-lease-writer") {
            SessionFileLease.acquireExclusive(session).use {
                writerAcquired.countDown()
                releaseWriter.await(5, TimeUnit.SECONDS)
            }
        }
        var secondReader: Thread? = null
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (writer.state != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue(writer.state == Thread.State.WAITING, "writer must be queued behind the first reader")

            secondReader = thread(start = true, name = "session-lease-late-reader") {
                SessionFileLease.acquireShared(session).use {
                    secondReaderAcquired.countDown()
                }
            }
            assertFalse(secondReaderAcquired.await(100, TimeUnit.MILLISECONDS))
            firstReader.close()
            assertTrue(writerAcquired.await(5, TimeUnit.SECONDS), "queued writer must acquire first")
            assertFalse(secondReaderAcquired.await(100, TimeUnit.MILLISECONDS))
            releaseWriter.countDown()
            assertTrue(secondReaderAcquired.await(5, TimeUnit.SECONDS))
            writer.join(5_000)
            secondReader.join(5_000)
            assertFalse(writer.isAlive)
            assertFalse(secondReader.isAlive)
        } finally {
            firstReader.close()
            releaseWriter.countDown()
            writer.interrupt()
            secondReader?.interrupt()
            writer.join(5_000)
            secondReader?.join(5_000)
        }
    }

    @Test
    fun `interrupted query does not bypass an exclusive lease`() {
        val session = Files.createTempDirectory("r4j-lease-interrupt").resolve("session.db")
        Files.write(session, byteArrayOf(1))
        val collector = SessionFileLease.acquireExclusive(session)
        val failure = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean(false)
        try {
            val query = thread(start = true, name = "session-lease-interrupted-query") {
                try {
                    Db.openReadOnly(session.toString(), immutable = true).close()
                } catch (exception: Throwable) {
                    failure.set(exception)
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (query.state != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.yield()
            }
            query.interrupt()
            query.join(5_000)

            assertFalse(query.isAlive)
            assertIs<IllegalStateException>(failure.get())
            assertTrue(interrupted.get(), "Db must restore the interrupt status")
        } finally {
            collector.close()
        }
    }
}

/** Separate-JVM lock probe used by the regression above. */
object SessionLeaseProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        println("READY")
        System.out.flush()
        if (args.size == 2 && args[0] == "collect") {
            val input = System.`in`.bufferedReader()
            SessionFileLease.withExclusiveReclamation(Paths.get(args[1])) {
                println("LOCKED")
                System.out.flush()
                check(input.readLine() == "DELETE")
                val removed = Files.deleteIfExists(Paths.get(args[1]))
                println("DELETED")
                System.out.flush()
                removed
            }
            println("RECLAIMED")
        } else {
            SessionFileLease.acquireExclusive(Paths.get(args.single())).use {
                println("ACQUIRED")
            }
        }
        System.out.flush()
    }
}
