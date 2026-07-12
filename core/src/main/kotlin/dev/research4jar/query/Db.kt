package dev.research4jar.query

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import org.sqlite.SQLiteConfig

/**
 * Read-only SQLite access for the query engine (xerial sqlite-jdbc). Session
 * databases are immutable content-addressed files; the manifest may be
 * rewritten by a concurrent indexer run, so it opens with a busy timeout.
 */
object Db {
    /**
     * Session mtime doubles as the last-use marker for the indexer's
     * automatic stale-session sweep. Refreshing it at most once a day keeps
     * an actively queried session alive without a metadata write per query.
     */
    private val TOUCH_AFTER: Duration = Duration.ofHours(24)

    fun openReadOnly(path: String, immutable: Boolean): Connection {
        val absolute = Paths.get(path).toAbsolutePath().normalize()
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
        return config.createConnection("jdbc:sqlite:$absolute")
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
