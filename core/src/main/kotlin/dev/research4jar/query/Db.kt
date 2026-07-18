package dev.research4jar.query

import dev.research4jar.runtime.SessionFileLease
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.attribute.FileTime
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.sqlite.SQLiteConfig

/**
 * Read-only SQLite access for the query engine (xerial sqlite-jdbc). Session
 * databases are immutable content-addressed files; the manifest may be
 * rewritten by a concurrent indexer run, so it opens with a busy timeout.
 */
object Db {
    private const val READ_ONLY_FILE_SYSTEM_REASON = "Read-only file system"

    /**
     * Session mtime doubles as the last-use marker for the indexer's
     * automatic stale-session sweep. Refreshing it at most once every five
     * minutes keeps an actively queried session alive even with the minimum
     * supported 1h expiry, without a metadata write per query.
     */
    private val TOUCH_AFTER: Duration = Duration.ofMinutes(5)

    fun openReadOnly(path: String, immutable: Boolean): Connection =
        openReadOnly(path, immutable) { SessionFileLease.acquireShared(it) }

    /** Lease injection keeps read-only-filesystem handling testable without a privileged mount. */
    internal fun openReadOnly(
        path: String,
        immutable: Boolean,
        acquireLease: (Path) -> AutoCloseable,
    ): Connection {
        val absolute = Paths.get(path).toAbsolutePath().normalize()
        val lease = if (immutable) {
            try {
                acquireLease(absolute)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("interrupted while acquiring the session read lease", exception)
            } catch (_: AccessDeniedException) {
                // Read-only cache homes must remain queryable. A collector in
                // the same unwritable home cannot acquire its exclusive lease
                // either, so falling back does not create a deletion path.
                null
            } catch (_: ReadOnlyFileSystemException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (exception: FileSystemException) {
                // The Unix NIO provider maps EROFS to a plain
                // FileSystemException and exposes the condition through this
                // exact reason. Do not turn other sidecar I/O failures into an
                // unlocked query: corruption and ordinary I/O must surface.
                if (isReadOnlyFileSystem(exception)) null else throw exception
            }
        } else {
            null
        }
        // Only sessions open as immutable; the manifest's mtime is not a
        // last-use marker and must stay untouched.
        if (immutable) {
            touchIfStale(absolute)
        }
        val config = SQLiteConfig()
        // setReadOnly clears the default READWRITE|CREATE open flags before
        // adding READONLY; setOpenMode(READONLY) alone ORs onto the defaults,
        // an invalid combination that sqlite rejects with SQLITE_MISUSE.
        config.setReadOnly(true)
        if (!immutable) {
            config.busyTimeout = 5000
        }
        return try {
            val connection = config.createConnection("jdbc:sqlite:$absolute")
            if (lease == null) connection else LeasedConnection(connection, lease)
        } catch (exception: Exception) {
            lease?.close()
            throw exception
        }
    }

    private fun isReadOnlyFileSystem(exception: FileSystemException): Boolean =
        exception.reason?.equals(READ_ONLY_FILE_SYSTEM_REASON, ignoreCase = true) == true

    /**
     * Couples the lease's lifetime to the connection's. A compile-time
     * delegate rather than a dynamic [java.lang.reflect.Proxy]: generating
     * the proxy class cost ~3.6ms in every one-shot CLI process, and each
     * JDBC call then paid reflective dispatch. Identity equals/hashCode and
     * the idempotent close both match the previous proxy behaviour.
     */
    private class LeasedConnection(
        private val delegate: Connection,
        private val lease: AutoCloseable,
    ) : Connection by delegate {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    delegate.close()
                } finally {
                    lease.close()
                }
            }
        }

        override fun isClosed(): Boolean = closed.get() || delegate.isClosed

        override fun toString(): String = "Leased($delegate)"
    }

    private fun touchIfStale(path: Path) {
        try {
            val lastUsed = Files.getLastModifiedTime(path).toInstant()
            if (Duration.between(lastUsed, Instant.now()) > TOUCH_AFTER) {
                Files.setLastModifiedTime(path, FileTime.from(Instant.now()))
            }
        } catch (_: Exception) {
            // best-effort: a read-only cache directory must not fail the query
        }
    }
}

/** Binds args by position and runs the query through [consume]. */
fun <T> Connection.query(sql: String, args: List<Any?>, consume: (ResultSet) -> T): T =
    prepareStatement(sql).use { statement ->
        statement.bindAll(args)
        statement.executeQuery().use(consume)
    }

/** Runs a COUNT-style query returning the first column of the first row. */
fun Connection.queryInt(sql: String, args: List<Any?>): Int =
    query(sql, args) { rows ->
        rows.next()
        rows.getInt(1)
    }

fun PreparedStatement.bindAll(args: List<Any?>) {
    args.forEachIndexed { index, value ->
        when (value) {
            null -> setObject(index + 1, null)
            is String -> setString(index + 1, value)
            is Int -> setInt(index + 1, value)
            is Long -> setLong(index + 1, value)
            is Boolean -> setBoolean(index + 1, value)
            else -> setObject(index + 1, value)
        }
    }
}

/** Reads every row through [scan], mirroring the Go rows loop. */
fun <T> ResultSet.mapRows(scan: (ResultSet) -> T): List<T> {
    val results = mutableListOf<T>()
    while (next()) {
        results += scan(this)
    }
    return results
}
