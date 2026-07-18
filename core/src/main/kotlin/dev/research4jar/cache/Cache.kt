package dev.research4jar.cache

import dev.research4jar.runtime.SessionFileLease
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import dev.research4jar.indexer.DataPaths
import dev.research4jar.indexer.store.Manifest
import dev.research4jar.indexer.store.ManifestRow
import dev.research4jar.registry.goQuote
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

/**
 * Shard and session lifecycle, ported from querier/internal/cache/cache.go:
 * usage statistics and garbage collection (stale extractor versions, orphan
 * files, abandoned build temporaries, LRU eviction over a size budget, and
 * session expiry). JSON field names and shapes match the Go structs exactly.
 */

/**
 * A healthy session build updates its temporary database continuously and
 * normally finishes in seconds or minutes (even the 10k-jar stress build is a
 * few minutes). Keeping a full day of slack prevents cache maintenance from
 * racing a slow active build while still reclaiming multi-GB files left by a
 * killed JVM, power loss, or machine restart.
 */
internal val SESSION_TEMP_STALE_AFTER: Duration = Duration.ofHours(24)

/** Summarizes the global cache (Go cache.Stats). */
data class CacheStats(
    @JsonProperty("home") val home: String,
    @JsonProperty("shard_count") val shardCount: Int,
    @JsonProperty("shard_bytes") val shardBytes: Long,
    @JsonProperty("shards_local") val shardsLocal: Int,
    @JsonProperty("shards_remote") val shardsRemote: Int,
    @JsonProperty("shards_stale_version") val shardsStaleVersion: Int,
    // Includes untracked shard databases and stale session-build .tmp files.
    @JsonProperty("orphan_files") val orphanFiles: Int,
    @JsonProperty("orphan_bytes") val orphanBytes: Long,
    @JsonProperty("session_count") val sessionCount: Int,
    @JsonProperty("session_bytes") val sessionBytes: Long,
)

/**
 * Bounds a collection run (Go cache.GCOptions). Zero values disable that
 * policy: maxShardBytes == 0 keeps every current-version shard,
 * maxSessionAge == zero keeps every session. Stale-version shards and orphan
 * files are always collected.
 */
data class GCOptions(
    val maxShardBytes: Long = 0,
    val maxSessionAge: Duration = Duration.ZERO,
    val dryRun: Boolean = false,
)

/** Reports what a collection run removed — or would remove (Go cache.GCResult). */
data class GCResult(
    @JsonProperty("dry_run") val dryRun: Boolean,
    @JsonProperty("removed_stale_version") val removedStaleVersion: Int,
    @JsonProperty("removed_orphans") val removedOrphans: Int,
    @JsonProperty("removed_lru") val removedLru: Int,
    @JsonProperty("removed_sessions") val removedSessions: Int,
    @JsonProperty("reclaimed_bytes") val reclaimedBytes: Long,
    @JsonProperty("remaining_shards") val remainingShards: Int,
    @JsonProperty("remaining_shard_bytes") val remainingShardBytes: Long,
    @JsonProperty("remaining_sessions") val remainingSessions: Int,
    @JsonProperty("remaining_session_bytes") val remainingSessionBytes: Long,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("removed") val removed: List<String> = emptyList(),
)

/** Walks the manifest, shards directory, and sessions directory (Go CollectStats). */
fun collectStats(dataPaths: DataPaths, currentExtractorVersion: Int): CacheStats {
    val manifest = Manifest(dataPaths.manifest)
    val shards = manifest.list()
    var shardCount = 0
    var shardBytes = 0L
    var shardsLocal = 0
    var shardsRemote = 0
    var shardsStaleVersion = 0
    val tracked = HashSet<String>()
    for (shard in shards) {
        shardCount++
        shardBytes += shard.sizeBytes
        tracked += cleanPath(shard.shardPath)
        when (shard.source) {
            "remote" -> shardsRemote++
            else -> shardsLocal++
        }
        if (shard.extractorVersion != currentExtractorVersion) {
            shardsStaleVersion++
        }
    }
    var orphanFiles = 0
    var orphanBytes = 0L
    for (file in listFiles(dataPaths.shards, ".db")) {
        if (cleanPath(file.path.toString()) !in tracked) {
            orphanFiles++
            orphanBytes += file.size
        }
    }
    for (file in staleSessionTemporaries(dataPaths, Instant.now())) {
        orphanFiles++
        orphanBytes += file.size
    }
    var sessionCount = 0
    var sessionBytes = 0L
    for (file in listFiles(dataPaths.sessions, ".db")) {
        sessionCount++
        sessionBytes += file.size
    }
    return CacheStats(
        home = dataPaths.home.toString(),
        shardCount = shardCount,
        shardBytes = shardBytes,
        shardsLocal = shardsLocal,
        shardsRemote = shardsRemote,
        shardsStaleVersion = shardsStaleVersion,
        orphanFiles = orphanFiles,
        orphanBytes = orphanBytes,
        sessionCount = sessionCount,
        sessionBytes = sessionBytes,
    )
}

/**
 * Collects garbage in four policy passes (Go cache.GC): shards from older
 * extractor versions; orphan shard files and abandoned session temporaries;
 * least-recently-used shards beyond the size budget; and sessions older than
 * the age limit.
 * Sessions are always rebuildable from shards by the next index run.
 */
fun gc(dataPaths: DataPaths, currentExtractorVersion: Int, options: GCOptions): GCResult {
    val manifest = Manifest(dataPaths.manifest)
    val shards = manifest.list()

    val removed = mutableListOf<String>()
    var reclaimedBytes = 0L
    var removedStaleVersion = 0
    var removedOrphans = 0
    var removedLru = 0
    var removedSessions = 0

    fun removeShard(shard: ManifestRow) {
        removed += shard.shardId
        reclaimedBytes += shard.sizeBytes
        if (options.dryRun) {
            return
        }
        Files.deleteIfExists(Paths.get(shard.shardPath))
        manifest.remove(shard.shardId)
    }

    val current = mutableListOf<ManifestRow>()
    for (shard in shards) {
        if (shard.extractorVersion != currentExtractorVersion) {
            removeShard(shard)
            removedStaleVersion++
            continue
        }
        current += shard
    }

    // Orphans: shard files the manifest does not reference. Track every
    // manifest path (stale rows included) so a dry run does not double-count
    // stale-version files as orphans.
    val tracked = HashSet<String>()
    for (shard in shards) {
        tracked += cleanPath(shard.shardPath)
    }
    for (file in listFiles(dataPaths.shards, ".db")) {
        if (cleanPath(file.path.toString()) in tracked) {
            continue
        }
        removed += file.path.fileName.toString()
        reclaimedBytes += file.size
        removedOrphans++
        if (!options.dryRun) {
            Files.deleteIfExists(file.path)
        }
    }

    // A normal exception is covered by the writer's finally block. These are
    // the leftovers that cannot run finally (SIGKILL, OOM-kill, power loss).
    // Fresh files are treated as active leases and are never reported or
    // removed; actual deletion re-checks mtime to close the listing race.
    val temporaryCutoffNow = Instant.now()
    for (file in staleSessionTemporaries(dataPaths, temporaryCutoffNow)) {
        val removedNow = options.dryRun || deleteIfStillStaleSessionTemporary(file.path, temporaryCutoffNow)
        if (!removedNow) continue
        removed += file.path.fileName.toString()
        reclaimedBytes += file.size
        removedOrphans++
    }

    // LRU eviction over the size budget. Manifest.list orders oldest access first.
    var remaining: List<ManifestRow> = current
    if (options.maxShardBytes > 0) {
        var totalBytes = 0L
        for (shard in remaining) {
            totalBytes += shard.sizeBytes
        }
        var index = 0
        while (totalBytes > options.maxShardBytes && index < remaining.size) {
            val shard = remaining[index]
            index++
            removeShard(shard)
            removedLru++
            totalBytes -= shard.sizeBytes
        }
        remaining = remaining.subList(index, remaining.size)
    }

    var remainingShards = 0
    var remainingShardBytes = 0L
    for (shard in remaining) {
        remainingShards++
        remainingShardBytes += shard.sizeBytes
    }

    val cutoff: Instant? = if (options.maxSessionAge > Duration.ZERO) {
        Instant.now().minus(options.maxSessionAge)
    } else {
        null
    }
    var remainingSessions = 0
    var remainingSessionBytes = 0L
    for (file in listFiles(dataPaths.sessions, ".db")) {
        if (cutoff != null && file.modTime.toInstant().isBefore(cutoff)) {
            val removedNow = options.dryRun || deleteIfStillStaleSession(file.path, cutoff)
            if (removedNow) {
                removed += file.path.fileName.toString()
                reclaimedBytes += file.size
                removedSessions++
                continue
            }
        }
        remainingSessions++
        remainingSessionBytes += file.size
    }
    return GCResult(
        dryRun = options.dryRun,
        removedStaleVersion = removedStaleVersion,
        removedOrphans = removedOrphans,
        removedLru = removedLru,
        removedSessions = removedSessions,
        reclaimedBytes = reclaimedBytes,
        remainingShards = remainingShards,
        remainingShardBytes = remainingShardBytes,
        remainingSessions = remainingSessions,
        remainingSessionBytes = remainingSessionBytes,
        removed = removed,
    )
}

/**
 * Removes sessions unused for longer than [maxAge] (unless null) and always
 * removes abandoned session-build temporaries older than
 * [SESSION_TEMP_STALE_AFTER]. Sessions are
 * content-addressed caches, always rebuildable by the next index run; mtime
 * approximates last use because both the index reuse path and the query
 * engine touch a session when they open it. This is the safe subset of [gc]
 * that the indexer runs automatically. A recent .tmp is an active-build lease;
 * the generous age floor plus an mtime re-check keeps it out of collection.
 */
fun collectStaleSessions(dataPaths: DataPaths, maxAge: Duration?): SessionSweepResult {
    val now = Instant.now()
    // Match explicit `cache gc --max-age 0d`: zero means disabled. Keeping
    // this invariant at the deletion boundary also protects callers that
    // parse equivalent spellings such as 0h instead of the literal "0".
    val cutoff = maxAge?.takeIf { it > Duration.ZERO }?.let(now::minus)
    var removedSessions = 0
    var removedTemporaries = 0
    var reclaimedBytes = 0L
    if (cutoff != null) {
        for (file in listFiles(dataPaths.sessions, ".db")) {
            if (!file.modTime.toInstant().isBefore(cutoff)) {
                continue
            }
            if (!deleteIfStillStaleSession(file.path, cutoff)) continue
            removedSessions++
            reclaimedBytes += file.size
        }
    }
    for (file in staleSessionTemporaries(dataPaths, now)) {
        if (!deleteIfStillStaleSessionTemporary(file.path, now)) continue
        removedTemporaries++
        reclaimedBytes += file.size
    }
    return SessionSweepResult(
        removed = removedSessions + removedTemporaries,
        reclaimedBytes = reclaimedBytes,
        removedSessions = removedSessions,
        removedTemporaries = removedTemporaries,
    )
}

data class SessionSweepResult(
    val removed: Int,
    val reclaimedBytes: Long,
    val removedSessions: Int = removed,
    val removedTemporaries: Int = 0,
)

/** Delete only while no query holds the session and only if it is still stale. */
internal fun deleteIfStillStaleSession(path: Path, cutoff: Instant): Boolean {
    return try {
        SessionFileLease.withExclusiveReclamation(path) {
            val attributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: Exception) {
                return@withExclusiveReclamation false
            }
            if (attributes.isDirectory || !path.fileName.toString().endsWith(".db")) {
                return@withExclusiveReclamation false
            }
            if (!attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
                return@withExclusiveReclamation false
            }
            try {
                val deleted = Files.deleteIfExists(path)
                if (deleted) {
                    // The class-conflict audit cached beside the session goes
                    // with it; a leftover sidecar would be an orphan forever.
                    Files.deleteIfExists(
                        path.resolveSibling(path.fileName.toString() + ".conflicts.json"),
                    )
                }
                deleted
            } catch (_: Exception) {
                false
            }
        }
    } catch (_: Exception) {
        false
    }
}

/** Parses human-friendly byte sizes: 500M, 2G, 1024K, 123 (bytes). */
fun parseSize(value: String): Long {
    var text = value.trim().uppercase()
    var multiplier = 1L
    when {
        text.endsWith("K") -> {
            multiplier = 1L shl 10
            text = text.removeSuffix("K")
        }
        text.endsWith("M") -> {
            multiplier = 1L shl 20
            text = text.removeSuffix("M")
        }
        text.endsWith("G") -> {
            multiplier = 1L shl 30
            text = text.removeSuffix("G")
        }
    }
    val number = text.trim().toLongOrNull()
    if (number == null || number < 0) {
        throw IllegalArgumentException("invalid size ${goQuote(value)} (use e.g. 500M, 2G)")
    }
    return number * multiplier
}

/** Parses durations in days or hours: 30d, 12h. */
fun parseAge(value: String): Duration {
    val text = value.trim().lowercase()
    when {
        text.endsWith("d") -> {
            val days = text.removeSuffix("d").toIntOrNull()
            if (days == null || days < 0) {
                throw IllegalArgumentException("invalid age ${goQuote(value)} (use e.g. 30d, 12h)")
            }
            return Duration.ofHours(days.toLong() * 24)
        }
        text.endsWith("h") -> {
            val hours = text.removeSuffix("h").toIntOrNull()
            if (hours == null || hours < 0) {
                throw IllegalArgumentException("invalid age ${goQuote(value)} (use e.g. 30d, 12h)")
            }
            return Duration.ofHours(hours.toLong())
        }
    }
    throw IllegalArgumentException("invalid age ${goQuote(value)} (use e.g. 30d, 12h)")
}

private class FileEntry(
    val path: Path,
    val size: Long,
    val modTime: FileTime,
)

/** Go filepath.Clean over the stored string form: lexical normalization. */
private fun cleanPath(path: String): String =
    Paths.get(path).normalize().toString()

private fun staleSessionTemporaries(dataPaths: DataPaths, now: Instant): List<FileEntry> {
    val cutoff = now.minus(SESSION_TEMP_STALE_AFTER)
    return listFiles(dataPaths.sessions, ".tmp").filter { file ->
        isSessionTemporaryName(file.path.fileName.toString()) &&
            file.modTime.toInstant().isBefore(cutoff)
    }
}

private fun deleteIfStillStaleSessionTemporary(path: Path, now: Instant): Boolean {
    val attributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        return false
    }
    if (attributes.isDirectory || !isSessionTemporaryName(path.fileName.toString())) return false
    if (!attributes.lastModifiedTime().toInstant().isBefore(now.minus(SESSION_TEMP_STALE_AFTER))) {
        return false
    }
    return try {
        Files.deleteIfExists(path)
    } catch (_: Exception) {
        false
    }
}

// AtomicFiles.temporaryIn creates hidden files of the form .<label>.<random>.tmp.
// The sessions directory is cache-owned, but the hidden-prefix guard still
// avoids treating an arbitrary user-visible notes.tmp as a build artifact.
private fun isSessionTemporaryName(name: String): Boolean =
    name.startsWith(".") && name.endsWith(".tmp")

private fun listFiles(dir: Path, suffix: String): List<FileEntry> {
    val entries = try {
        Files.newDirectoryStream(dir).use { it.toList() }
    } catch (_: Exception) {
        return emptyList()
    }
    val files = mutableListOf<FileEntry>()
    for (entry in entries) {
        if (!entry.fileName.toString().endsWith(suffix)) {
            continue
        }
        val attributes = try {
            Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            continue
        }
        if (attributes.isDirectory) {
            continue
        }
        files += FileEntry(entry, attributes.size(), attributes.lastModifiedTime())
    }
    files.sortBy { it.path.toString() }
    return files
}
