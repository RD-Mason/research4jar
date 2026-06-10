package dev.springdep.indexer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.springdep.indexer.extract.JarExtractor
import dev.springdep.indexer.store.Manifest
import dev.springdep.indexer.store.SessionBuilder
import dev.springdep.indexer.store.SessionShard
import dev.springdep.indexer.store.ShardWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.io.path.name
import kotlin.system.exitProcess

private const val EXTRACTOR_VERSION = SpringDepVersions.EXTRACTOR

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
        runIndex(parseOptions(args))
    } catch (exception: IllegalArgumentException) {
        System.err.println("springdep-index: ${exception.message}")
        System.err.println("Run springdep-index --help for usage.")
        exitProcess(2)
    } catch (exception: Exception) {
        System.err.println("springdep-index: fatal: ${exception.message ?: exception.javaClass.name}")
        exitProcess(1)
    }
}

private fun runIndex(options: Options) {
    val startedAt = Instant.now()
    val objectMapper = jacksonObjectMapper()
    val dataPaths = SpringDepPaths.resolve(options.home)
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

    val threadCount = inputPaths.size
        .coerceAtMost(Runtime.getRuntime().availableProcessors())
        .coerceAtLeast(1)
    val executor = Executors.newFixedThreadPool(threadCount)
    try {
        // Hashing dominates the warm path (every jar plus every cached shard is
        // re-hashed on each run), so both validation phases fan out to workers.
        val uniqueByHash = linkedMapOf<String, HashedJar>()
        inputPaths
            .map { path -> path to executor.submit(Callable { Hashing.sha256(path) }) }
            .forEach { (path, future) ->
                try {
                    val hash = future.get()
                    uniqueByHash.putIfAbsent(hash, HashedJar(path, hash))
                } catch (exception: Exception) {
                    warn("${path}: cannot read jar: ${exception.cause?.message ?: exception.message}")
                    missing += path.fileName?.toString() ?: path.toString()
                }
            }

        uniqueByHash.values
            .map { jar ->
                val shardId = "${jar.sha256}@$EXTRACTOR_VERSION"
                val existing = manifest.find(shardId)
                val valid = if (existing == null) {
                    null
                } else {
                    executor.submit(Callable { validShard(existing.shardPath, existing.shardChecksum) })
                }
                Triple(jar, existing, valid)
            }
            .forEach { (jar, existing, valid) ->
                if (existing != null && valid?.get() == true) {
                    selectedShards += SessionShard("${jar.sha256}@$EXTRACTOR_VERSION", existing.shardPath)
                    skipped++
                    return@forEach
                }
                if (existing != null) {
                    warn("${jar.path}: cached shard failed checksum validation; rebuilding")
                    manifest.remove(existing.shardId)
                }
                pending += jar
            }

        // ObjectMapper is thread-safe for the read/write calls used here;
        // BytecodeExtractor copies it before changing serialization features.
        val sharedMapper = jacksonObjectMapper()
        val futures = pending.map { jar ->
            executor.submit(Callable {
                val shardId = "${jar.sha256}@$EXTRACTOR_VERSION"
                val expectedPath = dataPaths.shards.resolve("$shardId.db").toAbsolutePath().normalize()
                try {
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
                }
            })
        }
        futures.forEach { future ->
            val outcome = future.get()
            outcome.warnings.forEach { warning -> warn("${outcome.jar.path.name}: $warning") }
            if (outcome.shard != null) {
                selectedShards += outcome.shard
                newlyIndexed++
            } else {
                warn(
                    "${outcome.jar.path}: invalid or unreadable jar: " +
                        (outcome.error?.message ?: outcome.error?.javaClass?.name),
                )
                missing += outcome.jar.path.fileName?.toString() ?: outcome.jar.path.toString()
            }
        }
    } finally {
        executor.shutdown()
    }

    val sortedShardIds = selectedShards.map(SessionShard::shardId).sorted()
    val fingerprint = Hashing.sha256(sortedShardIds.joinToString("\n")).take(16)
    val sessionPath = dataPaths.sessions.resolve("$fingerprint.db").toAbsolutePath().normalize()
    SessionBuilder().buildIfAbsent(sessionPath, selectedShards)

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

    val statistics = IndexStatistics(
        jars_total = jarsTotal,
        jars_indexed = jarsIndexed,
        jars_newly_indexed = newlyIndexed,
        jars_skipped = skipped,
        jars_missing = missing.sorted(),
        duration_ms = Duration.between(startedAt, Instant.now()).toMillis(),
    )
    println(objectMapper.writeValueAsString(statistics))
}

private fun validShard(path: Path, expectedChecksum: String?): Boolean {
    if (expectedChecksum.isNullOrBlank() || !Files.isRegularFile(path)) return false
    return try {
        Hashing.sha256(path) == expectedChecksum
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
            "--project-dir" -> projectDir = Path.of(requireValue(args, ++index, argument))
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

private fun printHelp() {
    println(
        """
        Usage:
          springdep-index --jars <DIR|GLOB|comma-separated-list> --project-dir <PATH> [--home <DIR>]

        Options:
          --jars <VALUE>       Jar directory, glob, or comma-separated jar paths.
          --project-dir <PATH> Project root where .springdep/project.json is written.
          --home <DIR>         Override SPRINGDEP_HOME.
          --fat-jar <PATH>     Not implemented in M1; extract BOOT-INF/lib jars first.
          -h, --help           Show this help.
        """.trimIndent(),
    )
}
