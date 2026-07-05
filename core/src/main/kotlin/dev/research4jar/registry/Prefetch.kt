package dev.research4jar.registry

import dev.research4jar.indexer.Hashing
import dev.research4jar.indexer.store.CachedDigest
import dev.research4jar.indexer.store.Manifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Registry prefetch, ported from querier/internal/registry/prefetch.go:
 * installs registry shards for every jar that has no valid local shard, so
 * the indexing that runs next hits the cache instead of extracting. Failures
 * degrade to local extraction: they are warned about on the warnings sink
 * (byte-identical "warning: registry prefetch: ..." lines) and never abort
 * the run.
 */

/** Names one valid local shard after a prefetch (Go registry.ShardRef). */
data class ShardRef(
    val shardId: String,
    val path: Path,
)

/**
 * Reports what a registry prefetch accomplished (Go registry.PrefetchStats).
 * When [complete] is true every unique readable jar has a valid local shard
 * (listed in [shards]) and indexing needs no local extraction at all.
 */
data class PrefetchStats(
    val jarsTotal: Int = 0,
    val jarsUnique: Int = 0,
    val cacheHits: Int = 0,
    val downloaded: Int = 0,
    val misses: Int = 0,
    val failures: Int = 0,
    val hashFailures: Int = 0,
    val shards: List<ShardRef> = emptyList(),
    val complete: Boolean = false,
)

private class HashedJar(
    val path: String,
    val sha256: String,
)

private class JarStat(
    val abs: String,
    val size: Long,
    val mtime: Long,
)

private class FetchOutcome(
    val jar: HashedJar,
    val result: FetchResult?,
    val error: Exception?,
)

/**
 * Prefetches shards for [jarPaths] from [client] into [shardsDir],
 * registering downloads in [manifest]. Mirrors Go registry.Prefetch: a
 * jar-digest cache short-circuits hashing by absolute path + size + mtime,
 * hashing fans out over min(CPUs, misses) workers, downloads over at most 6.
 */
fun prefetch(
    client: RegistryClient,
    manifest: Manifest,
    shardsDir: Path,
    extractorVersion: Int,
    jarPaths: List<String>,
    warnings: Appendable,
): PrefetchStats {
    if (jarPaths.isEmpty()) {
        return PrefetchStats(jarsTotal = 0)
    }
    var jarsUnique = 0
    var cacheHits = 0
    var downloaded = 0
    var misses = 0
    var failures = 0
    var hashFailures = 0
    val shards = mutableListOf<ShardRef>()

    val hashed = arrayOfNulls<HashedJar>(jarPaths.size)

    // Jar-digest cache: jars in the local Maven/Gradle caches are immutable,
    // so a row matching the current size+mtime lets us skip re-hashing — which
    // otherwise dominates the warm path. A load error just disables the cache.
    val digestCache: Map<String, CachedDigest> = try {
        manifest.loadJarDigests()
    } catch (_: Exception) {
        emptyMap()
    }
    val stats = arrayOfNulls<JarStat>(jarPaths.size)
    val needHash = mutableListOf<Int>()
    for ((index, path) in jarPaths.withIndex()) {
        val stat = try {
            val attributes = Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java)
            JarStat(
                abs = Paths.get(path).toAbsolutePath().normalize().toString(),
                size = attributes.size(),
                mtime = attributes.lastModifiedTime().toMillis(),
            )
        } catch (_: Exception) {
            continue // unreadable; counted as a hash failure downstream
        }
        stats[index] = stat
        val entry = digestCache[stat.abs]
        if (entry != null && entry.sizeBytes == stat.size && entry.mtimeMillis == stat.mtime) {
            hashed[index] = HashedJar(path = path, sha256 = entry.sha256)
            continue
        }
        needHash += index
    }

    if (needHash.isNotEmpty()) {
        val hashWorkers = minOf(Runtime.getRuntime().availableProcessors(), needHash.size)
        runOnPool(Executors.newFixedThreadPool(hashWorkers), needHash) { index ->
            val digest = try {
                Hashing.sha256(Paths.get(jarPaths[index]))
            } catch (_: Exception) {
                // The indexer reports unreadable jars itself.
                return@runOnPool
            }
            hashed[index] = HashedJar(path = jarPaths[index], sha256 = digest)
        }

        val fresh = HashMap<String, CachedDigest>()
        for (index in needHash) {
            val jar = hashed[index] ?: continue
            val stat = stats[index] ?: continue
            fresh[stat.abs] = CachedDigest(
                sizeBytes = stat.size,
                mtimeMillis = stat.mtime,
                sha256 = jar.sha256,
            )
        }
        try {
            manifest.putJarDigests(fresh)
        } catch (exception: Exception) {
            writeWarning(
                warnings,
                "warning: registry prefetch: cache jar digests: ${errorText(exception)}\n",
            )
        }
    }

    // Manifest decisions and registration stay on this thread; only the
    // network downloads fan out.
    val toFetch = mutableListOf<HashedJar>()
    val hitShardIds = mutableListOf<String>()
    val seen = HashSet<String>()
    for (jar in hashed) {
        if (jar == null) {
            hashFailures++
            continue
        }
        if (!seen.add(jar.sha256)) {
            continue
        }
        jarsUnique++
        val shardId = "${jar.sha256}@$extractorVersion"
        val row = try {
            manifest.find(shardId)
        } catch (exception: Exception) {
            writeWarning(warnings, "warning: registry prefetch: ${errorText(exception)}\n")
            failures++
            continue
        }
        if (row != null) {
            if (Files.isRegularFile(row.shardPath)) {
                cacheHits++
                hitShardIds += shardId
                shards += ShardRef(shardId = shardId, path = row.shardPath)
                continue
            }
            // Row without its file: drop it so the download re-registers.
            try {
                manifest.remove(shardId)
            } catch (exception: Exception) {
                writeWarning(warnings, "warning: registry prefetch: ${errorText(exception)}\n")
                failures++
                continue
            }
        }
        toFetch += jar
    }

    val outcomes = arrayOfNulls<FetchOutcome>(toFetch.size)
    if (toFetch.isNotEmpty()) {
        val downloadWorkers = minOf(6, toFetch.size)
        runOnPool(Executors.newFixedThreadPool(downloadWorkers), toFetch.indices.toList()) { index ->
            val jar = toFetch[index]
            outcomes[index] = try {
                FetchOutcome(jar, client.fetch(jar.sha256, extractorVersion, shardsDir), null)
            } catch (exception: Exception) {
                FetchOutcome(jar, null, exception)
            }
        }
    }

    for (slot in outcomes) {
        val entry = slot ?: continue
        val error = entry.error
        when {
            error is ShardNotFoundException -> misses++
            error != null -> {
                writeWarning(warnings, "warning: registry prefetch: ${errorText(error)}\n")
                failures++
            }
            else -> {
                val result = entry.result ?: continue
                try {
                    manifest.register(
                        shardId = result.shardId,
                        coordinate = result.jarCoordinate,
                        jarFilename = Paths.get(entry.jar.path).fileName.toString(),
                        jarSha256 = entry.jar.sha256,
                        shardPath = result.shardPath,
                        shardChecksum = result.checksum,
                        sizeBytes = result.sizeBytes,
                        source = "remote",
                        extractorVersion = extractorVersion,
                    )
                } catch (exception: Exception) {
                    writeWarning(warnings, "warning: registry prefetch: ${errorText(exception)}\n")
                    failures++
                    continue
                }
                downloaded++
                shards += ShardRef(shardId = result.shardId, path = result.shardPath)
            }
        }
    }

    // The indexer refreshes last_access_at itself; on a fully covered
    // classpath it never runs, so keep LRU order accurate here.
    try {
        manifest.touch(hitShardIds)
    } catch (exception: Exception) {
        writeWarning(warnings, "warning: registry prefetch: ${errorText(exception)}\n")
    }

    val complete = hashFailures == 0 &&
        misses == 0 &&
        failures == 0 &&
        shards.size == jarsUnique &&
        jarsUnique > 0
    return PrefetchStats(
        jarsTotal = jarPaths.size,
        jarsUnique = jarsUnique,
        cacheHits = cacheHits,
        downloaded = downloaded,
        misses = misses,
        failures = failures,
        hashFailures = hashFailures,
        shards = shards,
        complete = complete,
    )
}

/**
 * Runs [work] for every item on the pool and waits for completion, shutting
 * the pool down afterwards. Waiting on each Future publishes the workers'
 * writes to result arrays, standing in for Go's WaitGroup.
 */
private fun <T> runOnPool(pool: ExecutorService, items: List<T>, work: (T) -> Unit) {
    try {
        val futures: List<Future<*>> = items.map { item -> pool.submit { work(item) } }
        futures.forEach { it.get() }
    } finally {
        pool.shutdown()
    }
}
