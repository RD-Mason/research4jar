package dev.research4jar.indexer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.research4jar.indexer.extract.JarExtractor
import dev.research4jar.indexer.store.CachedDigest
import dev.research4jar.indexer.store.Manifest
import dev.research4jar.indexer.store.ManifestShard
import dev.research4jar.indexer.store.SessionBuilder
import dev.research4jar.indexer.store.SessionShard
import dev.research4jar.indexer.store.ShardWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.io.path.name
import kotlin.system.exitProcess

private const val EXTRACTOR_VERSION = Research4JarVersions.EXTRACTOR

data class IndexStatistics(
    val jars_total: Int,
    val jars_indexed: Int,
    val jars_newly_indexed: Int,
    val jars_skipped: Int,
    val jars_missing: List<String>,
    val duration_ms: Long,
)

private data class Options(
    val jars: String?,
    val projectDir: Path,
    val home: String?,
)

private data class HashedJar(
    val path: Path,
    val sha256: String,
)

private data class JarStat(
    val abs: String,
    val size: Long,
    val mtime: Long,
)

private data class ExtractionOutcome(
    val jar: HashedJar,
    val shard: SessionShard?,
    val warnings: List<String> = emptyList(),
    val error: Exception? = null,
)

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        printHelp()
        return
    }
    try {
        val statistics = executeIndex(parseOptions(args))
        println(jacksonObjectMapper().writeValueAsString(statistics))
    } catch (exception: IllegalArgumentException) {
        System.err.println("research4jar-index: ${exception.message}")
        System.err.println("Run research4jar-index --help for usage.")
        exitProcess(2)
    } catch (exception: Exception) {
        System.err.println("research4jar-index: fatal: ${exception.message ?: exception.javaClass.name}")
        exitProcess(1)
    }
}

/**
 * Library entrypoint for the extraction pipeline: hash, extract-or-reuse,
 * session build, project pointer, CLAUDE.md guidance. The JVM CLI and MCP
 * server call this in-process; the launcher `main` wraps it for the raw
 * `--jars/--project-dir/--home` contract.
 */
fun runIndexPipeline(jars: String, projectDir: Path, home: String?): IndexStatistics =
    executeIndex(
        Options(
            jars = jars,
            projectDir = projectDir.toAbsolutePath().normalize(),
            home = home,
        ),
    )

private fun executeIndex(options: Options): IndexStatistics {
    val startedAt = Instant.now()
    val objectMapper = jacksonObjectMapper()
    val dataPaths = Research4JarPaths.resolve(options.home)
    Files.createDirectories(dataPaths.shards)
    Files.createDirectories(dataPaths.sessions)
    Files.createDirectories(options.projectDir)

    val inputPaths = JarSource.resolve(options.jars)
    val missing = mutableListOf<String>()
    val manifest = Manifest(dataPaths.manifest)
    val selectedShards = mutableListOf<SessionShard>()
    var skipped = 0
    var newlyIndexed = 0
    val pending = mutableListOf<HashedJar>()
    val cacheHits = mutableListOf<String>()
    var sessionStreamed = false

    val threadCount = inputPaths.size
        .coerceAtMost(Runtime.getRuntime().availableProcessors())
        .coerceAtLeast(1)
    val executor = Executors.newFixedThreadPool(threadCount)
    try {
        // Hashing dominates the warm path. Dependency jars in the Maven/Gradle
        // caches are immutable, so a digest-cache row matching the current
        // size+mtime lets us skip re-hashing entirely; only misses fan out.
        val digestCache = manifest.loadJarDigests()
        val statByPath = LinkedHashMap<Path, JarStat>()
        val resolvedShas = HashMap<Path, String>()
        val toHash = mutableListOf<Path>()
        for (path in inputPaths) {
            val attributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } catch (exception: Exception) {
                warn("${path}: cannot read jar: ${exception.message}")
                missing += path.fileName?.toString() ?: path.toString()
                continue
            }
            val stat = JarStat(
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
                    warn("${path}: cannot read jar: ${exception.cause?.message ?: exception.message}")
                    missing += path.fileName?.toString() ?: path.toString()
                }
            }
        if (freshDigests.isNotEmpty()) {
            try {
                manifest.putJarDigests(freshDigests)
            } catch (exception: Exception) {
                warn("cannot persist jar digest cache: ${exception.message}")
            }
        }

        // Dedupe by content hash, preserving input order.
        val uniqueByHash = linkedMapOf<String, HashedJar>()
        for (path in inputPaths) {
            val sha = resolvedShas[path] ?: continue
            uniqueByHash.putIfAbsent(sha, HashedJar(path, sha))
        }

        val knownShards = manifest.findAll(
            uniqueByHash.values.map { "${it.sha256}@$EXTRACTOR_VERSION" },
        )
        uniqueByHash.values
            .forEach { jar ->
                val shardId = "${jar.sha256}@$EXTRACTOR_VERSION"
                val existing = knownShards[shardId]
                if (existing != null && validShard(existing)) {
                    selectedShards += SessionShard(shardId, existing.shardPath)
                    skipped++
                    cacheHits += existing.shardId
                    return@forEach
                }
                if (existing != null) {
                    warn("${jar.path}: cached shard missing or wrong size; rebuilding")
                    manifest.remove(existing.shardId)
                }
                pending += jar
            }

        // ObjectMapper is thread-safe for the read/write calls used here;
        // BytecodeExtractor copies it before changing serialization features.
        val sharedMapper = jacksonObjectMapper()
        // Bytecode extraction is the slow phase on a cold index. Emit progress to
        // stderr (stdout is reserved for the final stats JSON) so a long first run
        // is visibly working rather than indistinguishable from a hang.
        val extractTotal = pending.size
        if (extractTotal > 0) {
            progress("extracting $extractTotal jars ($skipped cached, ${selectedShards.size} reused)...")
        }
        val extractedCount = AtomicInteger(0)
        val progressStep = maxOf(1, extractTotal / 10)
        // The streaming merge below consumes futures in sorted shardId order,
        // so submit extraction in that same order: the pool then completes
        // shards roughly as the merge needs them, instead of the merge frontier
        // head-of-line blocking on a shard the pool has not even started.
        // (shardId sorts identically to sha256; the "@version" suffix is
        // constant within a run.) Exception: a jar big enough to dominate the
        // extraction makespan (more than a full thread's fair share of the
        // pending bytes; think kotlin-compiler-embeddable) starts immediately —
        // the merge cannot pass its position until it lands wherever it sits in
        // the schedule, so every submission slot it waits is pure wall time.
        val jarSize = { jar: HashedJar -> statByPath[jar.path]?.size ?: 0L }
        val fairShare = pending.sumOf(jarSize) / threadCount
        val submissionOrder = pending.sortedBy(HashedJar::sha256)
            .partition { jarSize(it) > fairShare }
            .let { (outsized, rest) -> outsized.sortedByDescending(jarSize) + rest }
        // A jar's transient extraction model costs a multiple of its file
        // size on the heap. With enough large jars on the classpath the
        // largest-first schedule clusters them at the front, and a full
        // thread pool of concurrent big extractions overflows the default
        // -Xmx512m (measured: OOM on a 1000-jar classpath with five >50MB
        // jars). The gate bounds the jar bytes being extracted at once to a
        // quarter of the heap; a jar larger than the whole budget takes every
        // permit and runs alone. Fairness keeps the submission order.
        val extractionBudgetKb = (Runtime.getRuntime().maxMemory() / 4 / 1024)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1024)
        val extractionGate = java.util.concurrent.Semaphore(extractionBudgetKb, true)
        fun permitsFor(jar: HashedJar): Int =
            (jarSize(jar) / 1024).coerceIn(1L, extractionBudgetKb.toLong()).toInt()
        val futuresByShardId = submissionOrder.associate { jar ->
            val shardId = "${jar.sha256}@$EXTRACTOR_VERSION"
            shardId to executor.submit(Callable {
                val expectedPath = dataPaths.shards.resolve("$shardId.db").toAbsolutePath().normalize()
                val permits = permitsFor(jar)
                extractionGate.acquire(permits)
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
                    ExtractionOutcome(
                        jar = jar,
                        shard = SessionShard(shardId, expectedPath),
                        warnings = extracted.warnings,
                    )
                } catch (exception: Exception) {
                    ExtractionOutcome(jar = jar, shard = null, error = exception)
                } finally {
                    extractionGate.release(permits)
                }
                val done = extractedCount.incrementAndGet()
                if (done == extractTotal || done % progressStep == 0) {
                    progress("extracted $done/$extractTotal jars")
                }
                outcome
            })
        }
        fun collectOutcome(outcome: ExtractionOutcome): SessionShard? {
            outcome.warnings.forEach { warning -> warn("${outcome.jar.path.name}: $warning") }
            if (outcome.shard != null) {
                selectedShards += outcome.shard
                newlyIndexed++
                return outcome.shard
            }
            warn(
                "${outcome.jar.path}: invalid or unreadable jar: " +
                    (outcome.error?.message ?: outcome.error?.javaClass?.name),
            )
            missing += outcome.jar.path.fileName?.toString() ?: outcome.jar.path.toString()
            return null
        }

        // Shard ids derive from jar hashes, so the full expected shard set —
        // and the sorted merge order the session needs — is known before any
        // extraction finishes. Unless a reusable session already exists for
        // that optimistic fingerprint, merge shards on this thread as their
        // futures complete while the extraction pool runs ahead: the session
        // build overlaps extraction instead of starting after it. A failed
        // extraction only shrinks the merged set, and a sorted-order merge of
        // any subset is byte-equivalent to a plain rebuild of that subset, so
        // the committed name comes from the shards that actually merged.
        val cachedShardsById = selectedShards.associateBy(SessionShard::shardId)
        val expectedShardIds = (cachedShardsById.keys + futuresByShardId.keys).sorted()
        val sessionBuilder = SessionBuilder()
        val optimisticSession = sessionPathFor(dataPaths, sessionFingerprint(expectedShardIds))
        if (futuresByShardId.isNotEmpty() && !sessionBuilder.isReusable(optimisticSession)) {
            sessionBuilder.openStream(dataPaths.sessions).use { stream ->
                for (shardId in expectedShardIds) {
                    val shard = cachedShardsById[shardId]
                        ?: collectOutcome(futuresByShardId.getValue(shardId).get())
                        ?: continue
                    stream.merge(shard)
                }
                stream.commit(
                    sessionPathFor(
                        dataPaths,
                        sessionFingerprint(selectedShards.map(SessionShard::shardId)),
                    ),
                )
            }
            sessionStreamed = true
        } else {
            // Nothing to extract, or the expected session is already on disk:
            // collect outcomes for the shard cache only and let buildIfAbsent
            // below reuse the session (or rebuild it for the smaller actual
            // fingerprint when an extraction failed — rare, correctness over
            // speed).
            futuresByShardId.values.forEach { future -> collectOutcome(future.get()) }
        }
    } finally {
        executor.shutdown()
    }
    manifest.touch(cacheHits)

    val fingerprint = sessionFingerprint(selectedShards.map(SessionShard::shardId))
    val sessionPath = sessionPathFor(dataPaths, fingerprint)
    if (!sessionStreamed) {
        SessionBuilder().buildIfAbsent(sessionPath, selectedShards)
    }

    val jarsIndexed = selectedShards.size
    val jarsTotal = jarsIndexed + missing.size
    val coverage = Coverage(
        jars_total = jarsTotal,
        jars_indexed = jarsIndexed,
        jars_missing = missing.sorted(),
    )
    ProjectPointer.write(
        options.projectDir,
        ProjectIndex(
            classpath_fingerprint = fingerprint,
            session_db_path = sessionPath.toString(),
            coverage = coverage,
        ),
        objectMapper,
    )
    ProjectPointer.ensureClaudeInstructions(options.projectDir)
    sweepStaleSessions(dataPaths)

    return IndexStatistics(
        jars_total = jarsTotal,
        jars_indexed = jarsIndexed,
        jars_newly_indexed = newlyIndexed,
        jars_skipped = skipped,
        jars_missing = missing.sorted(),
        duration_ms = Duration.between(startedAt, Instant.now()).toMillis(),
    )
}

// The session fingerprint depends only on the set of merged shard ids, never
// on merge timing, so the streaming and plain build paths name identical
// sessions identically.
private fun sessionFingerprint(shardIds: List<String>): String =
    Hashing.sha256(shardIds.sorted().joinToString("\n")).take(16)

private fun sessionPathFor(dataPaths: DataPaths, fingerprint: String): Path =
    dataPaths.sessions.resolve("$fingerprint.db").toAbsolutePath().normalize()

// A cached shard is reused when its file still exists and its on-disk size
// matches the size recorded at registration. The shard was checksum-verified
// when written and lives in research4jar's own cache directory, so a per-run
// content re-hash costs far more than it catches; the size check still detects
// truncation and missing files. (The Go registry prefetch likewise trusts the
// manifest without re-hashing shard databases.)
private fun validShard(shard: ManifestShard): Boolean {
    if (!Files.isRegularFile(shard.shardPath)) return false
    val recordedSize = shard.sizeBytes ?: return false
    return try {
        Files.size(shard.shardPath) == recordedSize
    } catch (_: Exception) {
        false
    }
}

private fun parseOptions(args: Array<String>): Options {
    var jars: String? = null
    var projectDir: Path? = null
    var home: String? = null
    var index = 0
    while (index < args.size) {
        when (val argument = args[index]) {
            "--jars" -> jars = requireValue(args, ++index, argument)
            "--project-dir" -> projectDir = Paths.get(requireValue(args, ++index, argument))
            "--home" -> home = requireValue(args, ++index, argument)
            "--fat-jar" -> throw IllegalArgumentException(
                "--fat-jar is not implemented in M1; pass extracted dependency jars with --jars",
            )
            else -> throw IllegalArgumentException("unknown option: $argument")
        }
        index++
    }
    require(!jars.isNullOrBlank()) { "--jars is required" }
    require(projectDir != null) { "--project-dir is required" }
    return Options(
        jars = jars,
        projectDir = projectDir.toAbsolutePath().normalize(),
        home = home,
    )
}

private fun requireValue(args: Array<String>, index: Int, option: String): String {
    if (index >= args.size) throw IllegalArgumentException("$option requires a value")
    return args[index]
}

private fun warn(message: String) {
    System.err.println("warning: $message")
}

// Progress goes to stderr; stdout carries only the final stats JSON that
// agents parse. PrintStream.println is synchronized, so concurrent extraction
// workers never interleave a line.
/**
 * Every index run sweeps sessions whose mtime (refreshed on each index reuse
 * and query open, so it approximates last use) is older than
 * RESEARCH4JAR_SESSION_MAX_AGE (default 30d; "off" or "0" disables). Old
 * sessions otherwise accumulate one multi-hundred-MB file per classpath
 * change with nothing reclaiming them; the swept ones rebuild from cached
 * shards in seconds if a project still needs them.
 */
private fun sweepStaleSessions(dataPaths: DataPaths) {
    val configured = System.getenv("RESEARCH4JAR_SESSION_MAX_AGE")?.trim()
    if (configured != null && (configured.equals("off", ignoreCase = true) || configured == "0")) {
        return
    }
    val maxAge = if (configured.isNullOrEmpty()) {
        Duration.ofDays(30)
    } else {
        try {
            dev.research4jar.cache.parseAge(configured)
        } catch (exception: IllegalArgumentException) {
            warn("RESEARCH4JAR_SESSION_MAX_AGE: ${exception.message}; using default 30d")
            Duration.ofDays(30)
        }
    }
    val sweep = dev.research4jar.cache.collectStaleSessions(dataPaths, maxAge)
    if (sweep.removed > 0) {
        val reclaimedMb = sweep.reclaimedBytes / (1024 * 1024)
        progress(
            "removed ${sweep.removed} stale session(s), reclaimed ${reclaimedMb}MB " +
                "(unused for ${maxAge.toDays()}d; set RESEARCH4JAR_SESSION_MAX_AGE=off to keep them)",
        )
    }
}

private fun progress(message: String) {
    System.err.println("research4jar-index: $message")
}

private fun printHelp() {
    println(
        """
        Usage:
          research4jar-index --jars <DIR|GLOB|comma-separated-list> --project-dir <PATH> [--home <DIR>]

        Options:
          --jars <VALUE>       Jar directory, glob, or comma-separated jar paths.
          --project-dir <PATH> Project root where .research4jar/project.json is written.
          --home <DIR>         Override RESEARCH4JAR_HOME.
          --fat-jar <PATH>     Not implemented in M1; extract BOOT-INF/lib jars first.
          -h, --help           Show this help.
        """.trimIndent(),
    )
}
