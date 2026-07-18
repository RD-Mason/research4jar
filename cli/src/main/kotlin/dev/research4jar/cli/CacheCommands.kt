package dev.research4jar.cli

import dev.research4jar.cache.GCOptions
import dev.research4jar.cache.collectStats
import dev.research4jar.cache.gc
import dev.research4jar.cache.parseAge
import dev.research4jar.cache.parseSize
import dev.research4jar.indexer.Research4JarPaths
import dev.research4jar.indexer.Research4JarVersions
import java.time.Duration

/**
 * The `cache` command family, ported from querier/cmd/research4jar/main.go
 * runCacheCommand: usage, flag names, error codes, and JSON output match the
 * Go CLI exactly.
 */
internal fun runCacheCommand(args: Array<String>, io: CliIO) {
    if (helpRequested(args)) {
        printCacheHelp(io.out)
        return
    }
    if (args.isEmpty() || (args[0] != "stats" && args[0] != "gc")) {
        fail("invalid_arguments", "usage: research4jar cache <stats|gc> [options]", 2)
    }
    val subcommand = args[0]
    var home = ""
    var maxShardBytes = 0L
    var maxSessionAge: Duration = Duration.ZERO
    var dryRun = false
    var index = 1
    while (index < args.size) {
        val argument = args[index]
        if (
            subcommand == "stats" &&
            argument in setOf("--max-size", "--max-age", "--dry-run")
        ) {
            fail("invalid_arguments", "unknown option: $argument", 2)
        }
        when (argument) {
            "--home", "--max-size", "--max-age" -> {
                val (value, next) = optionValue(args, index, argument)
                when (argument) {
                    "--home" -> home = value
                    "--max-size" -> maxShardBytes = try {
                        parseSize(value)
                    } catch (exception: Exception) {
                        fail("invalid_arguments", errMessage(exception), 2)
                    }

                    "--max-age" -> maxSessionAge = try {
                        parseAge(value)
                    } catch (exception: Exception) {
                        fail("invalid_arguments", errMessage(exception), 2)
                    }
                }
                index = next
            }

            "--dry-run" -> dryRun = true
            else -> fail("invalid_arguments", "unknown option: $argument", 2)
        }
        index++
    }
    val dataPaths = try {
        Research4JarPaths.resolve(home)
    } catch (exception: Exception) {
        fail("cache_error", errMessage(exception), 1)
    }
    val response: Any = try {
        when (subcommand) {
            "stats" -> collectStats(dataPaths, Research4JarVersions.EXTRACTOR)
            else -> gc(
                dataPaths,
                Research4JarVersions.EXTRACTOR,
                GCOptions(
                    maxShardBytes = maxShardBytes,
                    maxSessionAge = maxSessionAge,
                    dryRun = dryRun,
                ),
            )
        }
    } catch (failure: CliFailure) {
        throw failure
    } catch (exception: Exception) {
        fail("cache_error", errMessage(exception), 1)
    }
    printJson(io.out, response)
}
