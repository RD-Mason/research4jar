package dev.research4jar.indexer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.research4jar.query.Db
import dev.research4jar.query.ManifestCache
import dev.research4jar.query.mapRows
import dev.research4jar.query.query
import java.nio.file.Files
import java.nio.file.Path

/**
 * One classpath conflict pair: two jars shipping the same class names. The
 * JVM loads whichever comes first on the classpath, so hidden multi-version
 * pairs (jackson-databind 2.19 AND 2.21, say) are a production-bug risk the
 * user should hear about at index time, not discover in a stack trace.
 */
data class ClassConflict(
    val jar_a: String,
    val jar_b: String,
    val shared_classes: Int,
)

object ClassConflicts {
    /**
     * package-info/module-info exist once per package/module per jar by
     * design; counting them would flag virtually every jar pair.
     */
    private const val DUPLICATE_FQNS = """
        SELECT fqn FROM classes
        WHERE fqn NOT LIKE '%.package-info'
          AND fqn NOT IN ('package-info', 'module-info')
        GROUP BY fqn HAVING COUNT(DISTINCT source_shard_id) > 1
    """

    /**
     * Detects cross-jar duplicate classes in [sessionPath], aggregated to jar
     * pairs (largest overlap first). Session content is immutable per
     * fingerprint, so the result is computed once and cached next to the
     * session — warm re-index runs re-warn at file-read cost.
     */
    fun detect(sessionPath: Path, manifestPath: Path): List<ClassConflict> {
        val cache = cachePath(sessionPath)
        if (Files.isRegularFile(cache)) {
            try {
                return jacksonObjectMapper().readValue<List<ClassConflict>>(cache.toFile())
            } catch (_: Exception) {
                // Unreadable cache: recompute and rewrite below.
            }
        }
        val conflicts = compute(sessionPath, manifestPath)
        try {
            Files.write(cache, jacksonObjectMapper().writeValueAsBytes(conflicts))
        } catch (_: Exception) {
            // The cache only saves the next run's query; detection stands.
        }
        return conflicts
    }

    internal fun cachePath(sessionPath: Path): Path =
        sessionPath.resolveSibling(sessionPath.fileName.toString() + ".conflicts.json")

    private fun compute(sessionPath: Path, manifestPath: Path): List<ClassConflict> =
        Db.openReadOnly(sessionPath.toString(), immutable = true).use { session ->
            data class KeyPair(val keyA: String, val keyB: String, val count: Int)

            val pairs = session.query(
                """
                SELECT a.source_shard_id, b.source_shard_id, COUNT(*)
                FROM classes a
                JOIN classes b ON b.fqn = a.fqn AND a.source_shard_id < b.source_shard_id
                WHERE a.fqn IN ($DUPLICATE_FQNS)
                GROUP BY a.source_shard_id, b.source_shard_id
                ORDER BY COUNT(*) DESC, a.source_shard_id, b.source_shard_id
                """.trimIndent(),
                emptyList(),
            ) { rows -> rows.mapRows { KeyPair(it.getString(1), it.getString(2), it.getInt(3)) } }
            if (pairs.isEmpty()) return@use emptyList()

            val keys = pairs.flatMap { listOf(it.keyA, it.keyB) }.distinct()
            val sources = ManifestCache.mapSessionRows(
                session,
                ManifestCache.loadRows(manifestPath.toString()),
                keys,
            )

            fun name(key: String): String {
                val row = sources[key] ?: return key
                return row.source.ifEmpty { row.shardId }
            }
            pairs.map { ClassConflict(name(it.keyA), name(it.keyB), it.count) }
        }

    /** Formats the stderr warning block; empty list formats to no lines. */
    fun warningLines(conflicts: List<ClassConflict>, shown: Int = 5): List<String> {
        if (conflicts.isEmpty()) return emptyList()
        val lines = mutableListOf(
            "WARNING: duplicate classes across classpath jars (multi-version conflict risk):",
        )
        conflicts.take(shown).forEach {
            lines += "  ${it.jar_a} and ${it.jar_b} share ${it.shared_classes} class name(s)"
        }
        if (conflicts.size > shown) {
            lines += "  ... and ${conflicts.size - shown} more jar pair(s)"
        }
        lines += "  the JVM loads whichever comes first on the classpath; " +
            "queries on affected classes note every owner (pin with get-source --in)"
        return lines
    }
}
