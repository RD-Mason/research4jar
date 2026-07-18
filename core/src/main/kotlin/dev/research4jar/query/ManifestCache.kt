package dev.research4jar.query

import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection

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

    /**
     * Resolve both canonical shard ids and a v7 session's compact numeric
     * keys. Including the canonical ids keeps hand-written/legacy test
     * sessions compatible; production result rows use the numeric entries.
     * Missing manifest rows still resolve to the canonical shard id rather
     * than leaking the internal integer into user-facing JSON.
     */
    fun loadSourceJars(
        session: Connection,
        manifestPath: String,
        shardKeys: Collection<String>,
    ): Map<String, String> =
        mapSessionRows(session, loadRows(manifestPath), shardKeys).mapValues { (_, row) ->
            row.source.ifEmpty { row.shardId }
        }

    internal fun mapSessionRows(
        session: Connection,
        manifestRows: List<CachedManifestRow>,
        shardKeys: Collection<String>? = null,
    ): Map<String, CachedManifestRow> {
        val byCanonicalId = manifestRows.associateBy(CachedManifestRow::shardId)
        val requested = shardKeys?.distinct()
        val mapped = LinkedHashMap<String, CachedManifestRow>(
            if (requested == null) byCanonicalId.size * 2 else requested.size,
        )
        if (requested == null) {
            mapped.putAll(byCanonicalId)
        } else {
            requested.forEach { key -> byCanonicalId[key]?.let { mapped[key] = it } }
        }
        try {
            val numericKeys = requested?.mapNotNull { it.toLongOrNull() }
            if (numericKeys == null) {
                session.query(
                    "SELECT shard_key, shard_id FROM session_shards",
                    emptyList(),
                ) { rows ->
                    while (rows.next()) putSessionRow(rows, byCanonicalId, mapped)
                }
            } else {
                // Public pages cap at 1000 rows. Chunking keeps well below
                // SQLite builds with a 999-variable limit while resolving only
                // the page's keys, not every shard in a 10k-jar session.
                numericKeys.distinct().chunked(400).forEach { chunk ->
                    if (chunk.isEmpty()) return@forEach
                    session.query(
                        "SELECT shard_key, shard_id FROM session_shards WHERE shard_key IN (" +
                            List(chunk.size) { "?" }.joinToString(",") + ")",
                        chunk,
                    ) { rows ->
                        while (rows.next()) putSessionRow(rows, byCanonicalId, mapped)
                    }
                }
            }
        } catch (_: java.sql.SQLException) {
            // A hand-written/pre-v7 session uses canonical text ids directly.
        }
        return mapped
    }

    private fun putSessionRow(
        rows: java.sql.ResultSet,
        byCanonicalId: Map<String, CachedManifestRow>,
        mapped: MutableMap<String, CachedManifestRow>,
    ) {
        val key = rows.getLong(1).toString()
        val shardId = rows.getString(2)
        mapped[key] = byCanonicalId[shardId] ?: CachedManifestRow(
            shardId = shardId,
            coordinate = "",
            filename = "",
            source = "",
        )
    }

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
