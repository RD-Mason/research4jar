package dev.springdep.indexer.store

import dev.springdep.indexer.SpringDepVersions
import java.nio.file.Files
import java.nio.file.Path
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
                    shardPath = Path.of(result.getString("shard_path")),
                    shardChecksum = result.getString("shard_checksum"),
                    sizeBytes = result.getLong("size_bytes").let {
                        if (result.wasNull()) null else it
                    },
                )
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

    fun register(
        shardId: String,
        coordinate: String?,
        jarFilename: String,
        jarSha256: String,
        shardPath: Path,
        shardChecksum: String,
        sizeBytes: Long,
    ) {
        val now = Instant.now().epochSecond
        connect().use { connection ->
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO shards(
                  shard_id, jar_coordinate, jar_filename, jar_sha256, extractor_version,
                  shard_path, shard_checksum, size_bytes, created_at, last_access_at, source
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'local')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, shardId)
                statement.setString(2, coordinate)
                statement.setString(3, jarFilename)
                statement.setString(4, jarSha256)
                statement.setInt(5, SpringDepVersions.EXTRACTOR)
                statement.setString(6, shardPath.toAbsolutePath().normalize().toString())
                statement.setString(7, shardChecksum)
                statement.setLong(8, sizeBytes)
                statement.setLong(9, now)
                statement.setLong(10, now)
                statement.executeUpdate()
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
        }
    }
}
