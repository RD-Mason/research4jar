package dev.research4jar.indexer.store

import dev.research4jar.indexer.Research4JarVersions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

data class ManifestShard(
    val shardId: String,
    val jarCoordinate: String?,
    val jarFilename: String,
    val jarSha256: String,
    val shardPath: Path,
    val shardChecksum: String?,
    val sizeBytes: Long?,
)

/**
 * One cached jar content hash, valid only while the jar's size and mtime are
 * unchanged. Lets warm re-indexing skip re-hashing immutable dependency jars.
 */
data class CachedDigest(
    val sizeBytes: Long,
    val mtimeMillis: Long,
    val sha256: String,
)

/**
 * A complete manifest row, mirroring the Go manifest.Shard struct field for
 * field (NULL integers read as 0, NULL source as ""). Added for the
 * registry/cache lifecycle port, whose export and GC passes need every
 * column; [ManifestShard] stays the narrower shape the indexer reuses.
 */
data class ManifestRow(
    val shardId: String,
    val jarCoordinate: String?,
    val jarFilename: String,
    val jarSha256: String,
    val extractorVersion: Int,
    val shardPath: String,
    val shardChecksum: String?,
    val sizeBytes: Long,
    val createdAt: Long,
    val lastAccessAt: Long,
    val source: String,
)

class Manifest(
    private val path: Path,
) {
    init {
        Files.createDirectories(path.parent)
        connect().use(::createSchema)
    }

    fun find(shardId: String): ManifestShard? = connect().use { connection ->
        connection.prepareStatement(
            """
            SELECT shard_id, jar_coordinate, jar_filename, jar_sha256,
                   shard_path, shard_checksum, size_bytes
            FROM shards
            WHERE shard_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, shardId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                ManifestShard(
                    shardId = result.getString("shard_id"),
                    jarCoordinate = result.getString("jar_coordinate"),
                    jarFilename = result.getString("jar_filename"),
                    jarSha256 = result.getString("jar_sha256"),
                    shardPath = Paths.get(result.getString("shard_path")),
                    shardChecksum = result.getString("shard_checksum"),
                    sizeBytes = result.getLong("size_bytes").let {
                        if (result.wasNull()) null else it
                    },
                )
            }
        }
    }

    /**
     * Returns every shard row ordered by last access (oldest first), the
     * order LRU eviction consumes. Added for the registry/cache lifecycle
     * port; mirrors Go manifest.DB.List.
     */
    fun list(): List<ManifestRow> = connect().use { connection ->
        connection.prepareStatement(
            """
            SELECT shard_id, jar_coordinate, jar_filename, jar_sha256,
                   extractor_version, shard_path, shard_checksum, size_bytes,
                   created_at, last_access_at, source
            FROM shards
            ORDER BY last_access_at ASC, shard_id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { result ->
                val rows = mutableListOf<ManifestRow>()
                while (result.next()) {
                    rows += ManifestRow(
                        shardId = result.getString("shard_id"),
                        jarCoordinate = result.getString("jar_coordinate"),
                        jarFilename = result.getString("jar_filename"),
                        jarSha256 = result.getString("jar_sha256"),
                        extractorVersion = result.getInt("extractor_version"),
                        shardPath = result.getString("shard_path"),
                        shardChecksum = result.getString("shard_checksum"),
                        sizeBytes = result.getLong("size_bytes"),
                        createdAt = result.getLong("created_at"),
                        lastAccessAt = result.getLong("last_access_at"),
                        source = result.getString("source") ?: "",
                    )
                }
                rows
            }
        }
    }

    /**
     * Refreshes last_access_at for shards reused from the cache so LRU
     * garbage collection evicts genuinely unused shards first.
     */
    fun touch(shardIds: Collection<String>) {
        if (shardIds.isEmpty()) return
        val now = Instant.now().epochSecond
        connect().use { connection ->
            connection.prepareStatement(
                "UPDATE shards SET last_access_at = ? WHERE shard_id = ?",
            ).use { statement ->
                shardIds.forEach { shardId ->
                    statement.setLong(1, now)
                    statement.setString(2, shardId)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    fun remove(shardId: String) {
        connect().use { connection ->
            connection.prepareStatement("DELETE FROM shards WHERE shard_id = ?").use { statement ->
                statement.setString(1, shardId)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Inserts a shard row; an existing row for the same id is left untouched.
     * [source] and [extractorVersion] default to the indexer's local-build
     * values; the registry prefetch port passes source="remote" and the
     * registry's extractor version (mirrors Go manifest.DB.Register).
     */
    fun register(
        shardId: String,
        coordinate: String?,
        jarFilename: String,
        jarSha256: String,
        shardPath: Path,
        shardChecksum: String,
        sizeBytes: Long,
        source: String = "local",
        extractorVersion: Int = Research4JarVersions.EXTRACTOR,
    ) {
        val now = Instant.now().epochSecond
        connect().use { connection ->
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO shards(
                  shard_id, jar_coordinate, jar_filename, jar_sha256, extractor_version,
                  shard_path, shard_checksum, size_bytes, created_at, last_access_at, source
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, shardId)
                statement.setString(2, coordinate)
                statement.setString(3, jarFilename)
                statement.setString(4, jarSha256)
                statement.setInt(5, extractorVersion)
                statement.setString(6, shardPath.toAbsolutePath().normalize().toString())
                statement.setString(7, shardChecksum)
                statement.setLong(8, sizeBytes)
                statement.setLong(9, now)
                statement.setLong(10, now)
                statement.setString(11, source)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Loads the whole jar-digest cache keyed by absolute path. Callers must
     * re-validate each entry against the file's current size+mtime before
     * trusting the hash; a stale or missing entry just means "re-hash".
     */
    fun loadJarDigests(): Map<String, CachedDigest> = connect().use { connection ->
        connection.prepareStatement(
            "SELECT abs_path, size_bytes, mtime_millis, sha256 FROM jar_digest_cache",
        ).use { statement ->
            statement.executeQuery().use { result ->
                val digests = HashMap<String, CachedDigest>()
                while (result.next()) {
                    digests[result.getString("abs_path")] = CachedDigest(
                        sizeBytes = result.getLong("size_bytes"),
                        mtimeMillis = result.getLong("mtime_millis"),
                        sha256 = result.getString("sha256"),
                    )
                }
                digests
            }
        }
    }

    /** Upserts jar-digest cache rows in one transaction. Keys are absolute paths. */
    fun putJarDigests(entries: Map<String, CachedDigest>) {
        if (entries.isEmpty()) return
        connect().use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT OR REPLACE INTO jar_digest_cache(
                      abs_path, size_bytes, mtime_millis, sha256
                    ) VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    entries.forEach { (path, digest) ->
                        statement.setString(1, path)
                        statement.setLong(2, digest.sizeBytes)
                        statement.setLong(3, digest.mtimeMillis)
                        statement.setString(4, digest.sha256)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    private fun connect(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").also { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA busy_timeout=5000")
                statement.execute("PRAGMA synchronous=FULL")
            }
        }

    private fun createSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS shards (
                  shard_id TEXT PRIMARY KEY,
                  jar_coordinate TEXT,
                  jar_filename TEXT NOT NULL,
                  jar_sha256 TEXT NOT NULL,
                  extractor_version INTEGER NOT NULL,
                  shard_path TEXT NOT NULL,
                  shard_checksum TEXT,
                  size_bytes INTEGER,
                  created_at INTEGER,
                  last_access_at INTEGER,
                  source TEXT
                )
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_shards_sha ON shards(jar_sha256)")
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_shards_coord ON shards(jar_coordinate)",
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS jar_digest_cache (
                  abs_path TEXT PRIMARY KEY,
                  size_bytes INTEGER NOT NULL,
                  mtime_millis INTEGER NOT NULL,
                  sha256 TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
    }
}
