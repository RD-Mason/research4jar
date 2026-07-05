package dev.research4jar.registry

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.research4jar.indexer.DataPaths
import dev.research4jar.indexer.Hashing
import dev.research4jar.indexer.IndexStatistics
import dev.research4jar.indexer.Research4JarVersions
import dev.research4jar.indexer.extract.JarExtractor
import dev.research4jar.indexer.store.CachedDigest
import dev.research4jar.indexer.store.Manifest
import dev.research4jar.indexer.store.ManifestShard
import dev.research4jar.indexer.store.SessionBuilder
import dev.research4jar.indexer.store.SessionShard
import dev.research4jar.indexer.store.ShardWriter
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * Registry seeding, ported from querier/internal/registry/seed.go: downloads
 * Maven coordinates, indexes them with the same extraction pipeline the
 * indexer main uses, and leaves the shards ready for [export].
 */

/** Where seed coordinates resolve unless overridden (Go DefaultMavenRepo). */
const val DEFAULT_MAVEN_REPO = "https://repo1.maven.org/maven2"

/** Identifies one Maven artifact to seed (Go registry.Coordinate). */
data class Coordinate(
    val group: String,
    val artifact: String,
    val version: String,
) {
    override fun toString(): String = "$group:$artifact:$version"

    /** The artifact's jar location under a Maven repository base URL. */
    fun jarUrl(repo: String): String =
        "${repo.trimEnd('/')}/${group.replace('.', '/')}/${pathEscape(artifact)}/" +
            "${pathEscape(version)}/${pathEscape(artifact)}-${pathEscape(version)}.jar"
}

/**
 * Reads one group:artifact:version per line; blank lines and # comments are
 * skipped. Malformed lines raise IllegalArgumentException with the Go error
 * text.
 */
fun parseCoordinates(reader: Reader): List<Coordinate> {
    val coordinates = mutableListOf<Coordinate>()
    var line = 0
    reader.buffered().forEachLine { rawLine ->
        line++
        val text = rawLine.trim()
        if (text.isEmpty() || text.startsWith("#")) {
            return@forEachLine
        }
        val parts = text.split(":")
        if (parts.size != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw IllegalArgumentException(
                "line $line: ${goQuote(text)} is not group:artifact:version",
            )
        }
        coordinates += Coordinate(group = parts[0], artifact = parts[1], version = parts[2])
    }
    return coordinates
}

/** The downloaded jar paths (in coordinate order) and the failure count. */
data class DownloadedJars(
    val jarPaths: List<Path>,
    val failures: Int,
)

/**
 * Fetches every coordinate's jar into [destDir] over at most 6 parallel
 * downloads. Individual failures are warned about and skipped — a partially
 * seeded registry is still useful, and clients fall back to local extraction
 * on misses (Go registry.DownloadJars).
 */
fun downloadJars(
    repo: String,
    coordinates: List<Coordinate>,
    destDir: Path,
    warnings: Appendable,
): DownloadedJars {
    try {
        Files.createDirectories(destDir)
    } catch (exception: Exception) {
        writeWarning(warnings, "warning: ${errorText(exception)}\n")
        return DownloadedJars(emptyList(), coordinates.size)
    }
    val jarPaths = arrayOfNulls<Path>(coordinates.size)
    val lock = Any()
    var failures = 0
    val workers = minOf(6, coordinates.size)
    if (workers > 0) {
        val pool = Executors.newFixedThreadPool(workers)
        try {
            coordinates.indices.map { index ->
                pool.submit {
                    val coordinate = coordinates[index]
                    val target = destDir.resolve("${coordinate.artifact}-${coordinate.version}.jar")
                    try {
                        downloadFile(coordinate.jarUrl(repo), target)
                    } catch (exception: Exception) {
                        synchronized(lock) {
                            writeWarning(
                                warnings,
                                "warning: $coordinate: ${errorText(exception)}; skipping\n",
                            )
                            failures++
                        }
                        return@submit
                    }
                    jarPaths[index] = target
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
    }
    return DownloadedJars(jarPaths.filterNotNull(), failures)
}

private fun downloadFile(source: String, target: Path) {
    val connection = URL(source).openConnection() as HttpURLConnection
    connection.connectTimeout = SEED_TIMEOUT_MILLIS
    connection.readTimeout = SEED_TIMEOUT_MILLIS
    connection.requestMethod = "GET"
    val status = connection.responseCode
    if (status != 200) {
        try {
            connection.errorStream?.close()
        } catch (_: IOException) {
            // Nothing to do; the download already failed.
        }
        val message = connection.responseMessage
        val statusLine = if (message.isNullOrEmpty()) "$status" else "$status $message"
        throw IOException("$source returned $statusLine")
    }
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", "")
    try {
        connection.inputStream.use { input ->
            Files.newOutputStream(temporary).use { output -> input.copyTo(output) }
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

// 5 minutes, matching the Go seed download client timeout.
private const val SEED_TIMEOUT_MILLIS = 5 * 60 * 1000

/**
 * What `registry seed` prints: Go embeds ExportResult and flattens it, so
 * the export fields come first (Go's anonymous struct in seedRegistry).
 */
data class SeedResult(
    @JsonProperty("output_dir") val outputDir: String,
    @JsonProperty("exported") val exported: Int,
    @JsonProperty("skipped") val skipped: Int,
    @JsonProperty("total_bytes") val totalBytes: Long,
    @JsonProperty("signed") val signed: Boolean,
    @JsonProperty("coordinates") val coordinates: Int,
    @JsonProperty("download_failed") val downloadFailed: Int,
    @JsonProperty("jars_downloaded") val jarsDownloaded: Int,
)

private class SeedPendingJar(
    val path: Path,
    val sha256: String,
)

private class SeedFileStat(
    val abs: String,
    val size: Long,
    val mtime: Long,
)

private class SeedExtraction(
    val jar: SeedPendingJar,
    val shard: SessionShard?,
    val warnings: List<String> = emptyList(),
    val error: Exception? = null,
)

/**
 * Indexes concrete jar files into the shard cache and builds the merged
 * session — the same hash → digest-cache → dedupe → extract → shard write →
 * manifest register pipeline as the indexer entrypoint (indexer/Main.kt),
 * whose runIndex could not be refactored for reuse under this port's
 * file-change constraints; the loop is deliberately duplicated here and
 * should be unified later. Go's `registry seed` gets the same effects by
 * shelling out to the JVM indexer with a scratch project dir; this in-process
 * variant skips only the project pointer + CLAUDE.md, which Go writes into a
 * temp dir it deletes. Progress and warning lines go to [stderr] with the
 * indexer's exact texts; the returned stats are what the indexer prints as
 * its stdout JSON.
 */
fun seedIndex(jarPaths: List<Path>, dataPaths: DataPaths, stderr: Appendable): IndexStatistics {
    val startedAt = Instant.now()
    Files.createDirectories(dataPaths.shards)
    Files.createDirectories(dataPaths.sessions)

    val manifest = Manifest(dataPaths.manifest)
    val missing = mutableListOf<String>()
    val selectedShards = mutableListOf<SessionShard>()
    var skipped = 0
    var newlyIndexed = 0
    val pending = mutableListOf<SeedPendingJar>()
    val cacheHits = mutableListOf<String>()

    val threadCount = jarPaths.size
        .coerceAtMost(Runtime.getRuntime().availableProcessors())
        .coerceAtLeast(1)
    val executor = Executors.newFixedThreadPool(threadCount)
    try {
        val digestCache = manifest.loadJarDigests()
        val statByPath = LinkedHashMap<Path, SeedFileStat>()
        val resolvedShas = HashMap<Path, String>()
        val toHash = mutableListOf<Path>()
        for (path in jarPaths) {
            val attributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } catch (exception: Exception) {
                warn(stderr, "$path: cannot read jar: ${exception.message}")
                missing += path.fileName?.toString() ?: path.toString()
                continue
            }
            val stat = SeedFileStat(
                abs = path.toAbsolutePath().normalize().toString(),
                size = attributes.size(),
                mtime = attributes.lastModifiedTime().toMillis(),
            )
            statByPath[path] = stat
            val cached = digestCache[stat.abs]
            if (cached != null && cached.sizeBytes == stat.size && cached.mtimeMillis == stat.mtime) {
                resolvedShas[path] = cached.sha256
            } else {
                toHash.add(path)
            }
        }

        val freshDigests = HashMap<String, CachedDigest>()
        toHash
            .map { path -> path to executor.submit(Callable { Hashing.sha256(path) }) }
            .forEach { (path, future) ->
                try {
                    val hash = future.get()
                    resolvedShas[path] = hash
                    statByPath[path]?.let { stat ->
                        freshDigests[stat.abs] = CachedDigest(stat.size, stat.mtime, hash)
                    }
                } catch (exception: Exception) {
                    warn(stderr, "$path: cannot read jar: ${exception.cause?.message ?: exception.message}")
                    missing += path.fileName?.toString() ?: path.toString()
                }
            }
        if (freshDigests.isNotEmpty()) {
            try {
                manifest.putJarDigests(freshDigests)
            } catch (exception: Exception) {
                warn(stderr, "cannot persist jar digest cache: ${exception.message}")
            }
        }

        // Dedupe by content hash, preserving input order.
        val uniqueByHash = linkedMapOf<String, SeedPendingJar>()
        for (path in jarPaths) {
            val sha = resolvedShas[path] ?: continue
            uniqueByHash.putIfAbsent(sha, SeedPendingJar(path, sha))
        }

        uniqueByHash.values.forEach { jar ->
            val shardId = "${jar.sha256}@${Research4JarVersions.EXTRACTOR}"
            val existing = manifest.find(shardId)
            if (existing != null && validShard(existing)) {
                selectedShards += SessionShard(shardId, existing.shardPath)
                skipped++
                cacheHits += existing.shardId
                return@forEach
            }
            if (existing != null) {
                warn(stderr, "${jar.path}: cached shard missing or wrong size; rebuilding")
                manifest.remove(existing.shardId)
            }
            pending += jar
        }

        val sharedMapper = jacksonObjectMapper()
        val extractTotal = pending.size
        if (extractTotal > 0) {
            progress(
                stderr,
                "extracting $extractTotal jars ($skipped cached, ${selectedShards.size} reused)...",
            )
        }
        val extractedCount = AtomicInteger(0)
        val progressStep = maxOf(1, extractTotal / 10)
        val futures = pending.map { jar ->
            executor.submit(Callable {
                val shardId = "${jar.sha256}@${Research4JarVersions.EXTRACTOR}"
                val expectedPath = dataPaths.shards.resolve("$shardId.db").toAbsolutePath().normalize()
                val outcome = try {
                    val extracted = ZipFile(jar.path.toFile()).use {
                        JarExtractor(sharedMapper).extract(it)
                    }
                    ShardWriter().write(expectedPath, jar.sha256, extracted)
                    val checksum = Hashing.sha256(expectedPath)
                    manifest.register(
                        shardId = shardId,
                        coordinate = extracted.coordinate,
                        jarFilename = jar.path.fileName.toString(),
                        jarSha256 = jar.sha256,
                        shardPath = expectedPath,
                        shardChecksum = checksum,
                        sizeBytes = Files.size(expectedPath),
                    )
                    SeedExtraction(
                        jar = jar,
                        shard = SessionShard(shardId, expectedPath),
                        warnings = extracted.warnings,
                    )
                } catch (exception: Exception) {
                    SeedExtraction(jar = jar, shard = null, error = exception)
                }
                val done = extractedCount.incrementAndGet()
                if (done == extractTotal || done % progressStep == 0) {
                    progress(stderr, "extracted $done/$extractTotal jars")
                }
                outcome
            })
        }
        futures.forEach { future ->
            val outcome = future.get()
            outcome.warnings.forEach { warning ->
                warn(stderr, "${outcome.jar.path.fileName}: $warning")
            }
            if (outcome.shard != null) {
                selectedShards += outcome.shard
                newlyIndexed++
            } else {
                warn(
                    stderr,
                    "${outcome.jar.path}: invalid or unreadable jar: " +
                        (outcome.error?.message ?: outcome.error?.javaClass?.name),
                )
                missing += outcome.jar.path.fileName?.toString() ?: outcome.jar.path.toString()
            }
        }
    } finally {
        executor.shutdown()
    }
    manifest.touch(cacheHits)

    val sortedShardIds = selectedShards.map(SessionShard::shardId).sorted()
    val fingerprint = Hashing.sha256(sortedShardIds.joinToString("\n")).take(16)
    val sessionPath = dataPaths.sessions.resolve("$fingerprint.db").toAbsolutePath().normalize()
    SessionBuilder().buildIfAbsent(sessionPath, selectedShards)

    val jarsIndexed = selectedShards.size
    return IndexStatistics(
        jars_total = jarsIndexed + missing.size,
        jars_indexed = jarsIndexed,
        jars_newly_indexed = newlyIndexed,
        jars_skipped = skipped,
        jars_missing = missing.sorted(),
        duration_ms = Duration.between(startedAt, Instant.now()).toMillis(),
    )
}

/** Mirrors the indexer's cached-shard reuse check (Main.kt validShard). */
private fun validShard(shard: ManifestShard): Boolean {
    if (!Files.isRegularFile(shard.shardPath)) return false
    val recordedSize = shard.sizeBytes ?: return false
    return try {
        Files.size(shard.shardPath) == recordedSize
    } catch (_: Exception) {
        false
    }
}

private fun warn(stderr: Appendable, message: String) {
    writeWarning(stderr, "warning: $message\n")
}

private fun progress(stderr: Appendable, message: String) {
    writeWarning(stderr, "research4jar-index: $message\n")
}
