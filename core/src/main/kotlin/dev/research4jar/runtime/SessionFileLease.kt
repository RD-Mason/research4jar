package dev.research4jar.runtime

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * Cross-process lifetime lease for an immutable session database.
 *
 * Queries hold a shared lease until their JDBC connection closes; collectors
 * take an exclusive lease, re-check staleness, and only then unlink. Separate
 * turnstile/resource sidecars are used instead of locking the database itself
 * so Windows can delete the DB while the exclusive lock remains held and a
 * platform's close-all-locks rule cannot invalidate the durable lease.
 * A stable directory-level coordinator makes per-session sidecars reclaimable:
 * collectors close and unlink them only while holding its lifecycle lock
 * exclusively. The two coordinator files persist once per sessions directory;
 * deleting those stable lock paths would reintroduce the inode ABA problem.
 */
internal object SessionFileLease {
    private val states = ConcurrentHashMap<Path, StateEntry>()
    private val directoryStates = ConcurrentHashMap<Path, DirectoryStateEntry>()

    fun acquireShared(session: Path): AutoCloseable = acquire(session, shared = true)

    /**
     * Reuse probes treat a session deleted while they waited as a cache miss;
     * ordinary query opens use [acquireShared] and retain its strict failure.
     */
    fun acquireSharedIfPresent(session: Path): AutoCloseable? = try {
        acquireShared(session)
    } catch (_: NoSuchFileException) {
        null
    }

    fun acquireExclusive(session: Path): AutoCloseable = acquire(session, shared = false)

    /**
     * Runs [action] under the session's exclusive lease. A true result means
     * that the session was actually unlinked, so the now-unused per-session
     * sidecars are reclaimed before another process may open their paths.
     *
     * Callers that merely inspect or retain the session return false; their
     * sidecars remain intact and continue to identify the same lock inodes.
     */
    fun withExclusiveReclamation(session: Path, action: () -> Boolean): Boolean {
        val canonical = canonicalize(session)
        val entry = retainState(canonical)
        try {
            val paths = leasePaths(canonical)
            val lease = try {
                // Unlike a builder publish, reclamation has nothing to lock
                // once another collector removed the session. Re-check under
                // the directory lifecycle lock before opening either
                // per-session sidecar, so a queued second collector cannot
                // recreate the names the first collector just reclaimed.
                entry.state.acquireExclusive(paths, requireSession = true)
            } catch (_: NoSuchFileException) {
                return false
            }
            try {
                val removed = action()
                if (removed) lease.reclaimSidecars()
                return removed
            } finally {
                lease.close()
            }
        } finally {
            releaseState(canonical, entry)
        }
    }

    /** Visible to churn tests; production decisions must not depend on it. */
    internal fun trackedSessionStateCount(): Int = states.size

    /** Visible to churn tests; production decisions must not depend on it. */
    internal fun trackedDirectoryStateCount(): Int = directoryStates.size

    private fun acquire(session: Path, shared: Boolean): AutoCloseable {
        // The map key and both sidecar paths must be derived from the same
        // canonical identity. A parent-directory symlink (or a case alias on
        // a case-insensitive filesystem) can otherwise produce two LocalState
        // instances that open the same sidecar inode. On Darwin, closing a
        // channel through either state may then release every lock this JVM
        // holds on that inode.
        val canonical = canonicalize(session)
        val entry = retainState(canonical)
        try {
            val lease = entry.state.acquire(shared, leasePaths(canonical))
            return Lease {
                try {
                    lease.close()
                } finally {
                    releaseState(canonical, entry)
                }
            }
        } catch (exception: Throwable) {
            releaseState(canonical, entry)
            throw exception
        }
    }

    /**
     * Retain and lookup are one CHM operation. Reserving the reference before
     * returning the state prevents this race: one thread observes state A,
     * the last old lease removes A, and a third thread installs state B before
     * the first thread starts acquiring. A and B could otherwise lock the same
     * sidecar inode independently inside one JVM.
     */
    private fun retainState(path: Path): StateEntry = states.compute(path) { _, current ->
        (current ?: StateEntry(LocalState())).also { it.references++ }
    }!!

    private fun releaseState(path: Path, retained: StateEntry) {
        states.compute(path) { _, current ->
            check(current === retained) { "session lease state changed while retained: $path" }
            check(current.references > 0) { "session lease state released without a reference: $path" }
            current.references--
            current.takeUnless { it.references == 0 }
        }
    }

    private fun <T> withDirectoryState(path: Path, action: (DirectoryState) -> T): T {
        val entry = directoryStates.compute(path) { _, current ->
            (current ?: DirectoryStateEntry(DirectoryState())).also { it.references++ }
        }!!
        try {
            return action(entry.state)
        } finally {
            directoryStates.compute(path) { _, current ->
                check(current === entry) { "directory lease state changed while retained: $path" }
                check(current.references > 0) {
                    "directory lease state released without a reference: $path"
                }
                current.references--
                current.takeUnless { it.references == 0 }
            }
        }
    }

    /**
     * Resolves every existing path component while still supporting a target
     * session (or parent directory) that has not been created yet. Session
     * readers normally resolve the file itself; builders at least resolve the
     * longest existing parent before the sidecar directory is created.
     */
    private fun canonicalize(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        return canonicalizeExistingPrefix(absolute)
    }

    private fun canonicalizeExistingPrefix(path: Path): Path = try {
        path.toRealPath()
    } catch (missing: NoSuchFileException) {
        val parent = path.parent ?: throw missing
        val name = path.fileName ?: throw missing
        canonicalizeExistingPrefix(parent).resolve(name.toString())
    }

    private fun leasePaths(session: Path): LeasePaths {
        val parent = session.parent
            ?: throw IllegalArgumentException("session path has no parent: $session")
        Files.createDirectories(parent)
        // Keep the writer turnstile and the durable resource lease on
        // different inodes. On Darwin (and some other platforms), closing any
        // channel for a file can release every lock this JVM holds on that
        // inode, even when FileLock.isValid still reports true.
        return LeasePaths(
            session = session,
            turnstile = parent.resolve(".${session.fileName}.lease-turnstile"),
            resource = parent.resolve(".${session.fileName}.lease"),
            directoryTurnstile = parent.resolve(".session-leases-turnstile"),
            directoryResource = parent.resolve(".session-leases"),
        )
    }

    private data class LeasePaths(
        val session: Path,
        val turnstile: Path,
        val resource: Path,
        val directoryTurnstile: Path,
        val directoryResource: Path,
    ) {
        val directory: Path
            get() = directoryResource.parent
    }

    private class StateEntry(val state: LocalState, var references: Int = 0)

    private class DirectoryStateEntry(
        val state: DirectoryState,
        var references: Int = 0,
    )

    /**
     * Serializes this JVM's access to the stable directory coordinator. Java
     * rejects overlapping locks in one JVM instead of blocking, and Darwin may
     * drop every process lock on an inode when any sibling channel closes.
     * Keeping one local operation at a time avoids both behaviours while the
     * OS locks still coordinate independent JVMs.
     */
    private class DirectoryState {
        private val gate = ReentrantLock()

        fun <T> withShared(paths: LeasePaths, action: () -> T): T =
            withCoordinator(paths, shared = true, action)

        fun <T> withExclusive(paths: LeasePaths, action: () -> T): T =
            withCoordinator(paths, shared = false, action)

        private fun <T> withCoordinator(
            paths: LeasePaths,
            shared: Boolean,
            action: () -> T,
        ): T {
            gate.lockInterruptibly()
            try {
                // A separate coordinator turnstile gives lifecycle-exclusive
                // cleanup writer priority. Once the lifecycle lock is held,
                // the transient turnstile may be closed: it is a different
                // inode, so Darwin's close-all-locks rule cannot affect it.
                val turnstile = lock(paths.directoryTurnstile, shared)
                try {
                    val lifecycle = lock(paths.directoryResource, shared)
                    try {
                        turnstile.close()
                        return action()
                    } finally {
                        lifecycle.close()
                    }
                } finally {
                    turnstile.close()
                }
            } finally {
                gate.unlock()
            }
        }
    }

    private class HeldFileLock(
        private val channel: FileChannel,
        private val lock: FileLock,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }

    private fun lock(path: Path, shared: Boolean): HeldFileLock {
        val channel = open(path)
        try {
            return HeldFileLock(channel, channel.lock(0L, 1L, shared))
        } catch (exception: Throwable) {
            channel.close()
            throw exception
        }
    }

    private fun tryLock(path: Path, shared: Boolean): HeldFileLock? {
        val channel = open(path)
        try {
            val lock = channel.tryLock(0L, 1L, shared)
            if (lock == null) {
                channel.close()
                return null
            }
            return HeldFileLock(channel, lock)
        } catch (exception: Throwable) {
            channel.close()
            throw exception
        }
    }

    /**
     * Opens the per-session turnstile only while holding the stable directory
     * lifecycle lock. A failed try-lock releases every directory lock before
     * the short interruptible backoff, so a collector that already owns the
     * session can always advance to directory-exclusive sidecar cleanup.
     */
    private fun acquireTurnstile(
        paths: LeasePaths,
        shared: Boolean,
        requireSession: Boolean = shared,
    ): HeldFileLock {
        while (true) {
            var opened: HeldFileLock? = null
            val acquired = try {
                withDirectoryState(paths.directory) { directory ->
                    directory.withShared(paths) {
                        if (requireSession) {
                            val attributes = Files.readAttributes(
                                paths.session,
                                BasicFileAttributes::class.java,
                            )
                            if (!attributes.isRegularFile) {
                                throw NoSuchFileException(paths.session.toString())
                            }
                        }
                        tryLock(paths.turnstile, shared).also { opened = it }
                    }
                }
            } catch (exception: Throwable) {
                try {
                    opened?.close()
                } catch (closeFailure: Throwable) {
                    exception.addSuppressed(closeFailure)
                }
                throw exception
            }
            if (acquired != null) return acquired
            Thread.sleep(1L)
        }
    }

    private fun reclaimSidecars(paths: LeasePaths, releaseLocks: () -> Unit) {
        withDirectoryState(paths.directory) { directory ->
            directory.withExclusive(paths) {
                // Windows cannot unlink an open lock file. Keep the stable
                // directory lifecycle lock exclusive while all per-session
                // channels close and both names are removed.
                releaseLocks()
                Files.deleteIfExists(paths.resource)
                Files.deleteIfExists(paths.turnstile)
            }
        }
    }

    private fun open(path: Path): FileChannel = FileChannel.open(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
    )

    private class LocalState {
        private val gate = ReentrantLock()
        private val turnGate = ReentrantLock()
        private val available: Condition = gate.newCondition()
        private var readers = 0
        private var writer = false
        private var waitingWriters = 0
        private var sharedChannel: FileChannel? = null
        private var sharedLock: FileLock? = null

        fun acquire(shared: Boolean, paths: LeasePaths): AutoCloseable =
            if (shared) acquireShared(paths) else acquireExclusive(paths, requireSession = false)

        private fun acquireShared(paths: LeasePaths): AutoCloseable {
            while (true) {
                gate.lockInterruptibly()
                try {
                    while (writer || waitingWriters > 0) available.await()
                } finally {
                    gate.unlock()
                }

                // The first byte is a cross-process writer turnstile. Every
                // reader passes through it, even when this JVM already holds
                // the shared resource lock, so a writer in another process
                // cannot be starved by a continuous stream of local readers.
                // Serialize turnstile channels inside this JVM. Besides
                // reducing syscalls, this avoids the platform close-all-locks
                // rule releasing a sibling reader's transient turnstile lock.
                turnGate.lockInterruptibly()
                val turnstile = try {
                    acquireTurnstile(paths, shared = true)
                } catch (exception: Throwable) {
                    turnGate.unlock()
                    throw exception
                }
                var acquiredReader = false
                var gateHeld = false
                try {
                    gate.lockInterruptibly()
                    gateHeld = true
                    try {
                        // A local writer may have queued while this reader was
                        // outside the gate acquiring the OS turnstile. Let it
                        // go first and retry after releasing the turnstile.
                        if (writer || waitingWriters > 0) continue
                        if (readers == 0) {
                            val resourceChannel = open(paths.resource)
                            try {
                                sharedLock = resourceChannel.lock(0L, 1L, true)
                                sharedChannel = resourceChannel
                            } catch (exception: Exception) {
                                resourceChannel.close()
                                throw exception
                            }
                        }
                        readers++
                        acquiredReader = true
                        return Lease { releaseShared() }
                    } finally {
                        if (gateHeld) gate.unlock()
                    }
                } finally {
                    try {
                        turnstile.close()
                    } catch (exception: Throwable) {
                        if (acquiredReader) {
                            try {
                                releaseShared()
                            } catch (releaseFailure: Throwable) {
                                exception.addSuppressed(releaseFailure)
                            }
                        }
                        throw exception
                    } finally {
                        turnGate.unlock()
                    }
                }
            }
        }

        fun acquireExclusive(
            paths: LeasePaths,
            requireSession: Boolean,
        ): ExclusiveLease {
            gate.lockInterruptibly()
            waitingWriters++
            try {
                while (writer || readers > 0) available.await()
                writer = true
            } finally {
                waitingWriters--
                available.signalAll()
                gate.unlock()
            }

            // A reader can be between its initial local-state check and the
            // point where it increments `readers` while already holding the
            // OS turnstile. Join the same JVM gate before touching that inode;
            // otherwise FileChannel reports OverlappingFileLockException
            // instead of blocking this writer behind the reader.
            try {
                turnGate.lockInterruptibly()
            } catch (exception: Throwable) {
                clearWriter()
                throw exception
            }

            val turnstile = try {
                acquireTurnstile(
                    paths,
                    shared = false,
                    requireSession = requireSession,
                )
            } catch (exception: Throwable) {
                try {
                    clearWriter()
                } finally {
                    turnGate.unlock()
                }
                throw exception
            }
            val resourceChannel = try {
                open(paths.resource)
            } catch (exception: Throwable) {
                try {
                    turnstile.close()
                } finally {
                    try {
                        clearWriter()
                    } finally {
                        turnGate.unlock()
                    }
                }
                throw exception
            }
            val resourceLock = try {
                // The directory lock is already released, and the exclusive
                // session turnstile prevents both cleanup and new readers.
                // Waiting here therefore cannot invert the directory/session
                // lock order.
                resourceChannel.lock(0L, 1L, false)
            } catch (exception: Throwable) {
                try {
                    resourceChannel.close()
                } finally {
                    try {
                        turnstile.close()
                    } finally {
                        try {
                            clearWriter()
                        } finally {
                            turnGate.unlock()
                        }
                    }
                }
                throw exception
            }
            return ExclusiveLease(paths, turnstile, resourceChannel, resourceLock)
        }

        inner class ExclusiveLease(
            private val paths: LeasePaths,
            private val turnstile: HeldFileLock,
            private val resourceChannel: FileChannel,
            private val resourceLock: FileLock,
        ) : AutoCloseable {
            private var closed = false
            private var sessionLocksReleased = false

            fun reclaimSidecars() {
                finish(reclaim = true)
            }

            override fun close() {
                finish(reclaim = false)
            }

            private fun finish(reclaim: Boolean) {
                synchronized(this) {
                    if (closed) return
                    closed = true
                }
                try {
                    if (reclaim) {
                        reclaimAndReleaseSessionLocks()
                    } else {
                        releaseSessionLocks()
                    }
                } finally {
                    try {
                        clearWriter()
                    } finally {
                        turnGate.unlock()
                    }
                }
            }

            /**
             * Directory-coordinator acquisition can itself fail or be
             * interrupted before [reclaimSidecars] invokes its callback.
             * The exclusive session locks must still close in that case;
             * otherwise the local state is released while this JVM keeps an
             * unreachable OS lock on the old sidecar inode.
             */
            private fun reclaimAndReleaseSessionLocks() {
                var reclamationFailure: Throwable? = null
                try {
                    SessionFileLease.reclaimSidecars(paths) { releaseSessionLocks() }
                } catch (exception: Throwable) {
                    reclamationFailure = exception
                    throw exception
                } finally {
                    try {
                        releaseSessionLocks()
                    } catch (releaseFailure: Throwable) {
                        val primary = reclamationFailure
                        if (primary == null) throw releaseFailure
                        primary.addSuppressed(releaseFailure)
                    }
                }
            }

            private fun releaseSessionLocks() {
                synchronized(this) {
                    if (sessionLocksReleased) return
                    sessionLocksReleased = true
                }
                try {
                    resourceLock.release()
                } finally {
                    try {
                        resourceChannel.close()
                    } finally {
                        turnstile.close()
                    }
                }
            }
        }

        private fun releaseShared() {
            gate.lock()
            try {
                check(readers > 0) { "session lease released without a reader" }
                readers--
                if (readers == 0) {
                    try {
                        sharedLock?.release()
                    } finally {
                        sharedLock = null
                        try {
                            sharedChannel?.close()
                        } finally {
                            sharedChannel = null
                            available.signalAll()
                        }
                    }
                }
            } finally {
                gate.unlock()
            }
        }

        private fun clearWriter() {
            gate.lock()
            try {
                writer = false
                available.signalAll()
            } finally {
                gate.unlock()
            }
        }

    }

    private class Lease(private val release: () -> Unit) : AutoCloseable {
        private var closed = false

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            release()
        }
    }
}
