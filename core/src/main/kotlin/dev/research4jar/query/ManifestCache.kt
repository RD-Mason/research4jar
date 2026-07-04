package dev.research4jar.query

import java.nio.file.Files
import java.nio.file.Paths

data class CachedManifestRow(
    val shardId: String,
    /** "" when the jar has no recorded Maven coordinate. */
    val coordinate: String,
    val filename: String,
    /** COALESCE(NULLIF(coordinate, ''), filename). */
    val source: String,
)

/**
 * Process-wide cache of the manifest's shards table keyed by path+mtime.
 * The manifest changes only when an indexer/prefetch run rewrites it; a
 * long-lived MCP server or daemon reuses the parsed rows across calls, and a
 * rewrite bumps the mtime and is picked up on the next call. Parsed data
 * only — never a live connection.
 */
object ManifestCache {
    private val lock = Any()
    private var cachedPath: String? = null
    private var cachedMtime: Long = 0
    private var cachedRows: List<CachedManifestRow>? = null

    fun loadRows(manifestPath: String): List<CachedManifestRow> {
        if (manifestPath.isEmpty()) return emptyList()
        val mtime = try {
            Files.getLastModifiedTime(Paths.get(manifestPath)).toMillis()
        } catch (_: java.io.IOException) {
            null
        }
        if (mtime != null) {
            synchronized(lock) {
                val rows = cachedRows
                if (cachedPath == manifestPath && cachedMtime == mtime && rows != null) {
                    return rows
                }
            }
        }
        val rows = scanRows(manifestPath)
        if (mtime != null) {
            synchronized(lock) {
                cachedPath = manifestPath
                cachedMtime = mtime
                cachedRows = rows
            }
        }
        return rows
    }

    fun loadSourceJars(manifestPath: String): Map<String, String> =
        loadRows(manifestPath).associate { it.shardId to it.source }

    private fun scanRows(manifestPath: String): List<CachedManifestRow> =
        Db.openReadOnly(manifestPath, immutable = false).use { manifest ->
            manifest.query(
                """
                SELECT shard_id, jar_coordinate, jar_filename,
                       COALESCE(NULLIF(jar_coordinate, ''), jar_filename)
                FROM shards
                """.trimIndent(),
                emptyList(),
            ) { rows ->
                rows.mapRows {
                    CachedManifestRow(
                        shardId = it.getString(1),
                        coordinate = it.getString(2) ?: "",
                        filename = it.getString(3),
                        source = it.getString(4),
                    )
                }
            }
        }
}
