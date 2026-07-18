package dev.research4jar.cli

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.research4jar.indexer.Coverage as PointerCoverage
import dev.research4jar.indexer.DataPaths
import dev.research4jar.indexer.JarSource
import dev.research4jar.indexer.ProjectIndex as PointerProjectIndex
import dev.research4jar.indexer.ProjectPointer
import dev.research4jar.indexer.Research4JarPaths
import dev.research4jar.indexer.Research4JarVersions
import dev.research4jar.indexer.runIndexPipeline
import dev.research4jar.indexer.store.Manifest
import dev.research4jar.indexer.store.SessionBuilder
import dev.research4jar.indexer.store.SessionShard
import dev.research4jar.query.Classpath
import dev.research4jar.query.DepGraphCapture
import dev.research4jar.query.DepGraphFile
import dev.research4jar.query.DepGraphUnsupportedException
import dev.research4jar.query.ProjectIndex
import dev.research4jar.query.sessionFingerprint
import dev.research4jar.registry.PrefetchStats
import dev.research4jar.registry.RegistryClient
import dev.research4jar.registry.prefetch
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant

/**
 * Index orchestration for both surfaces: the CLI `index` command (Go
 * runIndexCommand + prefetchFromRegistry + finishIndexFromRegistry +
 * captureDependencyProvenance in querier/cmd/research4jar/main.go) and the MCP
 * `index_project` tool (Go runIndex in querier/internal/mcp/server.go).
 *
 * DELIBERATE BEHAVIOR DIFFERENCE from Go: where Go locates and spawns the
 * external research4jar-index launcher, this CLI runs the extraction pipeline
 * IN-PROCESS via [runIndexPipeline]. The --indexer flag and the
 * RESEARCH4JAR_INDEX environment variable are ACCEPTED but ignored — never
 * validated or executed — so a registry-covered run passed a bogus --indexer
 * still succeeds, and on fallback runs the stats JSON on stdout plus the
 * "research4jar-index:" progress lines on stderr come from the pipeline
 * itself, matching what the spawned launcher used to produce.
 */
object IndexOrchestrator {

    private data class IndexCommandOptions(
        val jars: String = "",
        val projectDir: String = ".",
        val home: String = "",
        val indexer: String = "",
        val registry: String = "",
        val registryPubkey: String = "",
    )

    /** Prefetch outcome bundle (Go returns jars, stats, dataPaths [, warnings]). */
    private class PrefetchOutcome(
        val jars: String,
        val stats: PrefetchStats,
        val dataPaths: DataPaths,
        val warnings: String = "",
    )

    /** What finishIndexFromRegistry prints on stdout (Go anonymous struct). */
    private data class RegistryIndexStats(
        @JsonProperty("jars_total") val jarsTotal: Int,
        @JsonProperty("jars_indexed") val jarsIndexed: Int,
        @JsonProperty("jars_newly_indexed") val jarsNewlyIndexed: Int,
        @JsonProperty("jars_skipped") val jarsSkipped: Int,
        @JsonProperty("jars_missing") val jarsMissing: List<String>,
        @JsonProperty("duration_ms") val durationMs: Long,
    )

    // --- CLI `index` command (Go runIndexCommand) ---

    fun runIndexCommand(args: Array<String>, io: CliIO) {
        if (helpRequested(args)) {
            printIndexHelp(io.out)
            return
        }
        var jars = ""
        var projectDir = "."
        var home = ""
        var indexer = ""
        var registryUrl = System.getenv("RESEARCH4JAR_REGISTRY") ?: ""
        var registryPubkey = System.getenv("RESEARCH4JAR_REGISTRY_PUBKEY") ?: ""
        var index = 0
        while (index < args.size) {
            when (val argument = args[index]) {
                "--jars", "--project-dir", "--home", "--indexer",
                "--registry", "--registry-pubkey",
                -> {
                    val (value, next) = optionValue(args, index, argument)
                    when (argument) {
                        "--jars" -> jars = value
                        "--project-dir" -> projectDir = value
                        "--home" -> home = value
                        "--indexer" -> indexer = value // accepted, ignored (in-process pipeline)
                        "--registry" -> registryUrl = value
                        "--registry-pubkey" -> registryPubkey = value
                    }
                    index = next
                }

                else -> fail("invalid_arguments", "unknown option: $argument", 2)
            }
            index++
        }
        val opts = IndexCommandOptions(
            jars = jars,
            projectDir = projectDir,
            home = home,
            indexer = indexer,
            registry = registryUrl,
            registryPubkey = registryPubkey,
        )

        val startedAt = Instant.now()
        val previousFingerprint = indexedFingerprint(opts.projectDir)
        var jarsSpec = opts.jars
        if (opts.registry.isNotEmpty()) {
            val outcome = prefetchFromRegistry(opts, io)
            jarsSpec = outcome.jars
            if (outcome.stats.complete) {
                finishIndexFromRegistry(
                    outcome.stats, outcome.dataPaths, opts.projectDir,
                    previousFingerprint, startedAt, io,
                )
                return
            }
        } else {
            printRegistryHint(io)
        }
        // Fallback: Go locates (indexer_not_found on failure) and spawns the
        // launcher here; this CLI extracts in-process instead — see the
        // deliberate-difference note on this object.
        runInProcessExtraction(jarsSpec, opts.projectDir, opts.home, io)
        captureDependencyProvenance(opts.projectDir, previousFingerprint, io)
    }

    /**
     * The in-process stand-in for Go's indexer.Run: resolves the classpath
     * via the build tool when no --jars was given (same stderr line), runs
     * the extraction pipeline, and prints the stats JSON the launcher would
     * have written to stdout (compact, one line).
     */
    private fun runInProcessExtraction(jarsSpec: String, projectDir: String, home: String, io: CliIO) {
        try {
            var jars = jarsSpec
            if (jars.isEmpty()) {
                val discovered = Classpath.discover(projectDir)
                if (discovered.isEmpty()) {
                    throw RuntimeException("build tool resolved an empty runtime classpath")
                }
                io.err.println(
                    "research4jar: resolved ${discovered.size} dependency jars from the build tool",
                )
                jars = discovered.joinToString(",")
            }
            val statistics = runIndexPipeline(jars, Paths.get(projectDir), home.ifEmpty { null })
            io.out.println(jacksonObjectMapper().writeValueAsString(statistics))
        } catch (failure: CliFailure) {
            throw failure
        } catch (exception: Exception) {
            fail(
                "index_error",
                errMessage(exception) + "\n\n" + doctorHint(projectDir, false),
                1,
            )
        }
    }

    /**
     * Downloads missing shards before extraction so it hits the local cache
     * (Go prefetchFromRegistry in main.go). Prefetch problems degrade to
     * local extraction and never abort the index run.
     */
    private fun prefetchFromRegistry(opts: IndexCommandOptions, io: CliIO): PrefetchOutcome {
        val client = try {
            RegistryClient(opts.registry, opts.registryPubkey)
        } catch (exception: Exception) {
            fail("invalid_arguments", errMessage(exception), 2)
        }
        var jars = opts.jars
        val jarList: List<String>
        if (jars.isEmpty()) {
            val discovered = try {
                Classpath.discover(opts.projectDir)
            } catch (exception: Exception) {
                fail("index_error", errMessage(exception), 1)
            }
            if (discovered.isEmpty()) {
                fail("index_error", "build tool resolved an empty runtime classpath", 1)
            }
            io.err.println(
                "research4jar: resolved ${discovered.size} dependency jars from the build tool",
            )
            jarList = discovered
            jars = discovered.joinToString(",")
        } else {
            jarList = try {
                JarSource.resolve(jars).map { it.toString() }
            } catch (exception: Exception) {
                fail("index_error", errMessage(exception), 1)
            }
        }
        val dataPaths = try {
            Research4JarPaths.resolve(opts.home)
        } catch (exception: Exception) {
            fail("index_error", errMessage(exception), 1)
        }
        val manifest = try {
            Manifest(dataPaths.manifest)
        } catch (exception: Exception) {
            fail("index_error", errMessage(exception), 1)
        }
        val stats = prefetch(
            client, manifest, dataPaths.shards,
            Research4JarVersions.EXTRACTOR, jarList, io.err,
        )
        io.err.println(
            "research4jar: registry prefetch: ${stats.cacheHits} already cached, " +
                "${stats.downloaded} downloaded, ${stats.misses} not in registry, " +
                "${stats.failures} failed",
        )
        return PrefetchOutcome(jars, stats, dataPaths)
    }

    /**
     * Completes an index run whose every shard came from the cache or the
     * registry: session merge, project pointer, and CLAUDE.md guidance —
     * no extraction at all (Go finishIndexFromRegistry in main.go).
     */
    private fun finishIndexFromRegistry(
        stats: PrefetchStats,
        dataPaths: DataPaths,
        projectDir: String,
        previousFingerprint: String,
        startedAt: Instant,
        io: CliIO,
    ) {
        val shards = stats.shards.map { SessionShard(it.shardId, it.path) }
        val shardIds = stats.shards.map { it.shardId }
        val fingerprint = sessionFingerprint(shardIds)
        val sessionPath = dataPaths.sessions.resolve("$fingerprint.db")
        try {
            SessionBuilder().buildIfAbsent(sessionPath, shards)
        } catch (exception: Exception) {
            fail("index_error", "build session: ${errMessage(exception)}", 1)
        }
        val coverage = PointerCoverage(
            jars_total = shards.size,
            jars_indexed = shards.size,
            jars_missing = emptyList(),
        )
        try {
            ProjectPointer.write(
                Paths.get(projectDir),
                PointerProjectIndex(
                    classpath_fingerprint = fingerprint,
                    session_db_path = sessionPath.toString(),
                    coverage = coverage,
                ),
                jacksonObjectMapper(),
            )
        } catch (exception: Exception) {
            fail("index_error", "write project pointer: ${errMessage(exception)}", 1)
        }
        try {
            ProjectPointer.ensureClaudeInstructions(Paths.get(projectDir))
        } catch (exception: Exception) {
            fail("index_error", "write CLAUDE.md guidance: ${errMessage(exception)}", 1)
        }
        captureDependencyProvenance(projectDir, previousFingerprint, io)
        io.err.println(
            "research4jar: session built from cached/registry shards; no local extraction needed",
        )
        printJson(
            io.out,
            RegistryIndexStats(
                jarsTotal = shards.size,
                jarsIndexed = shards.size,
                jarsNewlyIndexed = 0,
                jarsSkipped = shards.size,
                jarsMissing = emptyList(),
                durationMs = Duration.between(startedAt, Instant.now()).toMillis(),
            ),
        )
    }

    private fun printRegistryHint(io: CliIO) {
        io.err.println(
            "research4jar: no shard registry configured; a first index extracts every " +
                "jar locally (can take minutes on a large classpath). Set --registry <URL> " +
                "or RESEARCH4JAR_REGISTRY to download prebuilt shards instead.",
        )
    }

    /**
     * Refreshes .research4jar/dependencies.json via a full
     * `mvn dependency:tree` run; skipped when the classpath fingerprint did
     * not change and the provenance file already exists (Go
     * captureDependencyProvenance in main.go).
     */
    private fun captureDependencyProvenance(projectDir: String, previousFingerprint: String, io: CliIO) {
        if (previousFingerprint.isNotEmpty() &&
            previousFingerprint == indexedFingerprint(projectDir) &&
            DepGraphFile.exists(projectDir)
        ) {
            io.err.println("research4jar: classpath unchanged; reusing dependency provenance")
            return
        }
        val graph = try {
            DepGraphCapture.capture(projectDir)
        } catch (_: DepGraphUnsupportedException) {
            return
        } catch (exception: Exception) {
            io.err.println("warning: dependency provenance unavailable: ${errMessage(exception)}")
            return
        }
        try {
            DepGraphCapture.write(projectDir, graph)
        } catch (exception: Exception) {
            io.err.println("warning: write dependency provenance: ${errMessage(exception)}")
        }
    }

    /**
     * The classpath fingerprint from the project pointer, "" when no
     * readable pointer exists (Go indexedFingerprint).
     */
    private fun indexedFingerprint(projectDir: String): String = try {
        ProjectIndex.load(
            Paths.get(projectDir).resolve(".research4jar").resolve("project.json"),
        ).classpathFingerprint
    } catch (_: Exception) {
        ""
    }

    // --- MCP `index_project` tool (Go runIndex in internal/mcp/server.go) ---

    /**
     * Runs the index_project MCP tool and returns the result map with Go's
     * exact keys: status / project_dir / index_mode / hint /
     * registry_prefetch{jars_total, jars_unique, cache_hits, downloaded,
     * misses, failures, hash_failures, complete, warnings} /
     * dependency_provenance / note. Errors are thrown; the MCP server turns
     * them into isError tool results.
     */
    fun runIndexTool(arguments: McpServer.ToolArguments): Any {
        var projectDir = arguments.projectDir
        if (projectDir.isEmpty()) {
            projectDir = System.getProperty("user.dir")
                ?: throw RuntimeException("cannot determine working directory")
        }
        var jars = arguments.jars
        val previousFingerprint = indexedFingerprint(projectDir)
        val result = LinkedHashMap<String, Any?>()
        result["status"] = "indexed"
        result["project_dir"] = projectDir
        result["index_mode"] = "jvm"
        val registryUrl = firstNonEmpty(arguments.registry, System.getenv("RESEARCH4JAR_REGISTRY") ?: "")
        val registryPubkey = firstNonEmpty(
            arguments.registryPubkey,
            System.getenv("RESEARCH4JAR_REGISTRY_PUBKEY") ?: "",
        )
        if (registryUrl.isNotEmpty()) {
            val outcome = prefetchForTool(registryUrl, registryPubkey, jars, projectDir, arguments.home)
            jars = outcome.jars
            result["registry_prefetch"] = registryPrefetchSummary(outcome.stats, outcome.warnings)
            if (outcome.stats.complete) {
                finishIndexFromRegistryTool(outcome.stats, outcome.dataPaths, projectDir)
                result["index_mode"] = "registry"
                result["dependency_provenance"] =
                    captureDependencyProvenanceStatus(projectDir, previousFingerprint)
                result["note"] = "Session built from cached/registry shards; query tools are " +
                    "ready without launching the JVM indexer."
                return result
            }
        } else {
            // Parity with the CLI registry hint: host logs swallow stderr, so the
            // MCP path surfaces it as a result field instead.
            result["hint"] = "No shard registry configured; this index extracts every jar " +
                "locally (can take minutes on a large classpath). Pass the registry argument " +
                "or set RESEARCH4JAR_REGISTRY to download prebuilt shards instead."
        }

        // In-process extraction; Go wraps every indexer.Run failure (classpath
        // discovery included) in this message. The launcher-locate failure
        // mode does not exist here — --indexer is accepted but ignored.
        try {
            var resolved = jars
            if (resolved.isEmpty()) {
                val discovered = Classpath.discover(projectDir)
                if (discovered.isEmpty()) {
                    throw RuntimeException("build tool resolved an empty runtime classpath")
                }
                System.err.println(
                    "research4jar: resolved ${discovered.size} dependency jars from the build tool",
                )
                resolved = discovered.joinToString(",")
            }
            // Unlike Go (which lets the launcher's stats JSON leak onto the
            // MCP host's stdout), the pipeline's stats stay internal here;
            // stdout carries only JSON-RPC frames.
            runIndexPipeline(resolved, Paths.get(projectDir), arguments.home.ifEmpty { null })
        } catch (exception: Exception) {
            throw RuntimeException(
                "indexing failed: ${errMessage(exception)}. " +
                    "Call check_environment for installation guidance",
            )
        }
        result["dependency_provenance"] =
            captureDependencyProvenanceStatus(projectDir, previousFingerprint)
        result["note"] = "Project pointer written to .research4jar/project.json; query tools are ready."
        return result
    }

    /** Go prefetchFromRegistry (MCP flavor): errors throw, warnings buffer. */
    private fun prefetchForTool(
        registryUrl: String,
        registryPubkey: String,
        jarsSpec: String,
        projectDir: String,
        home: String,
    ): PrefetchOutcome {
        val client = RegistryClient(registryUrl, registryPubkey)
        var jars = jarsSpec
        val jarList: List<String>
        if (jars.isEmpty()) {
            jarList = Classpath.discover(projectDir)
            if (jarList.isEmpty()) {
                throw RuntimeException("build tool resolved an empty runtime classpath")
            }
            jars = jarList.joinToString(",")
        } else {
            jarList = JarSource.resolve(jars).map { it.toString() }
        }
        val dataPaths = Research4JarPaths.resolve(home.ifEmpty { null })
        val manifest = Manifest(dataPaths.manifest)
        val warnings = StringBuilder()
        val stats = prefetch(
            client, manifest, dataPaths.shards,
            Research4JarVersions.EXTRACTOR, jarList, warnings,
        )
        return PrefetchOutcome(jars, stats, dataPaths, warnings.toString().trim())
    }

    /** Go finishIndexFromRegistry (MCP flavor): errors throw with Go's wrap texts. */
    private fun finishIndexFromRegistryTool(
        stats: PrefetchStats,
        dataPaths: DataPaths,
        projectDir: String,
    ) {
        val shards = stats.shards.map { SessionShard(it.shardId, it.path) }
        val shardIds = stats.shards.map { it.shardId }
        val fingerprint = sessionFingerprint(shardIds)
        val sessionPath = dataPaths.sessions.resolve("$fingerprint.db")
        try {
            SessionBuilder().buildIfAbsent(sessionPath, shards)
        } catch (exception: Exception) {
            throw RuntimeException("build session: ${errMessage(exception)}")
        }
        try {
            ProjectPointer.write(
                Paths.get(projectDir),
                PointerProjectIndex(
                    classpath_fingerprint = fingerprint,
                    session_db_path = sessionPath.toString(),
                    coverage = PointerCoverage(
                        jars_total = shards.size,
                        jars_indexed = shards.size,
                        jars_missing = emptyList(),
                    ),
                ),
                jacksonObjectMapper(),
            )
        } catch (exception: Exception) {
            throw RuntimeException("write project pointer: ${errMessage(exception)}")
        }
        try {
            ProjectPointer.ensureClaudeInstructions(Paths.get(projectDir))
        } catch (exception: Exception) {
            throw RuntimeException("write CLAUDE.md guidance: ${errMessage(exception)}")
        }
    }

    private fun registryPrefetchSummary(stats: PrefetchStats, warnings: String): Map<String, Any?> {
        val summary = LinkedHashMap<String, Any?>()
        summary["jars_total"] = stats.jarsTotal
        summary["jars_unique"] = stats.jarsUnique
        summary["cache_hits"] = stats.cacheHits
        summary["downloaded"] = stats.downloaded
        summary["misses"] = stats.misses
        summary["failures"] = stats.failures
        summary["hash_failures"] = stats.hashFailures
        summary["complete"] = stats.complete
        if (warnings.isNotEmpty()) {
            summary["warnings"] = warnings
        }
        return summary
    }

    /**
     * The MCP result's dependency_provenance strings (Go
     * captureDependencyProvenance in internal/mcp/server.go).
     */
    private fun captureDependencyProvenanceStatus(projectDir: String, previousFingerprint: String): String {
        if (previousFingerprint.isNotEmpty() &&
            previousFingerprint == indexedFingerprint(projectDir) &&
            DepGraphFile.exists(projectDir)
        ) {
            return "reused (classpath unchanged)"
        }
        val graph = try {
            DepGraphCapture.capture(projectDir)
        } catch (_: DepGraphUnsupportedException) {
            return "unsupported build tool"
        } catch (exception: Exception) {
            return "capture failed: ${errMessage(exception)}"
        }
        return try {
            DepGraphCapture.write(projectDir, graph)
            "captured"
        } catch (exception: Exception) {
            "capture succeeded but write failed: ${errMessage(exception)}"
        }
    }

    private fun firstNonEmpty(vararg values: String): String =
        values.firstOrNull { it.isNotEmpty() } ?: ""
}
