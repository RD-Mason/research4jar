package dev.research4jar.cli

import dev.research4jar.envcheck.EnvCheck
import dev.research4jar.envcheck.Options as EnvCheckOptions
import dev.research4jar.query.ProjectIndex
import dev.research4jar.query.ProjectNotFoundException
import dev.research4jar.query.ProjectPointerData
import dev.research4jar.query.projectStatus
import java.io.PrintStream

/**
 * The JVM CLI dispatcher, ported from querier/cmd/research4jar/main.go (Go).
 * Flags, help texts, exit codes, stdout/stderr text, and JSON output must
 * match the Go CLI byte for byte — tests/e2e.sh (via tests/run-e2e-java.sh)
 * asserts behavior end to end.
 *
 * Daemon-readiness: command handlers never call exitProcess. fail() raises
 * [CliFailure], which [runCli] converts into Go fail()'s stdout JSON and the
 * exit code it returns to the caller.
 */

// Overridden at release time in Go via -ldflags "-X main.version=..."; the
// JVM CLI ships the same default until release stamping lands.
internal const val CLI_VERSION = "dev"

internal val QUERY_COMMANDS = setOf(
    "find-config-properties",
    "find-implementations",
    "find-by-annotation",
    "get-class",
    "get-bean-definitions",
    "explain-conditional",
    "find-string",
    "list-extension-points",
    "find-class",
    "find-method",
    "list-packages",
    "search-symbol",
    "open-symbol",
    "why-dependency",
)

/** Everything Go's parseOptions produces (main.go options struct). */
internal data class CommandOptions(
    val arg: String = "",
    val projectDir: String = "",
    val format: String = "json",
    val page: Int = 1,
    val pageSize: Int = 20,
    val home: String = "",
    val direct: Boolean = false,
    val sourceGrep: Boolean = true,
)

/**
 * Runs one CLI invocation against the given streams and returns the process
 * exit code. This is the entrypoint a long-lived daemon would call per
 * request; `main` wraps it with exitProcess.
 */
fun runCli(argv: Array<String>, out: PrintStream, err: PrintStream): Int {
    val io = CliIO(out, err)
    val exitCode = try {
        dispatch(argv, io)
    } catch (failure: CliFailure) {
        printJson(io.out, ErrorResponse(failure.code, failure.messageText))
        failure.exitCode
    }
    out.flush()
    err.flush()
    return exitCode
}

private fun dispatch(argv: Array<String>, io: CliIO): Int {
    if (argv.isEmpty() || argv[0] == "--help" || argv[0] == "-h") {
        printHelp(io.out)
        return 0
    }
    val command = argv[0]
    val rest = argv.copyOfRange(1, argv.size)
    return when {
        command == "version" || command == "--version" -> {
            io.out.println("research4jar $CLI_VERSION")
            0
        }

        command == "mcp" -> runMcpCommand(io)

        command == "index" -> {
            IndexOrchestrator.runIndexCommand(rest, io)
            0
        }

        command == "doctor" -> runDoctorCommand(rest, io)
        command == "status" -> runStatusCommand(rest, io)

        command == "cache" -> {
            runCacheCommand(rest, io)
            0
        }

        command == "registry" -> {
            runRegistryCommand(rest, io)
            0
        }

        command == "dep" -> {
            runDepCommand(rest, io)
            0
        }

        command == "artifact" -> {
            runArtifactCommand(rest, io)
            0
        }

        command == "class" -> {
            runClassAliasCommand(rest, io)
            0
        }

        command == "method" -> {
            runMethodAliasCommand(rest, io)
            0
        }

        command !in QUERY_COMMANDS ->
            fail("invalid_command", unknownCommandMessage(command), 2)

        else -> {
            runQueryCommand(command, rest, io)
            0
        }
    }
}

private fun runMcpCommand(io: CliIO): Int = try {
    McpServer.serve(System.`in`, io.out)
    0
} catch (exception: Exception) {
    io.err.println("research4jar mcp: ${errMessage(exception)}")
    1
}

private fun runDoctorCommand(args: Array<String>, io: CliIO): Int {
    var projectDir = ""
    var sourceBuild = false
    var format = "text"
    var index = 0
    while (index < args.size) {
        when (val argument = args[index]) {
            "--project-dir" -> {
                val (value, next) = optionValue(args, index, argument)
                projectDir = value
                index = next
            }

            "--format" -> {
                val (value, next) = optionValue(args, index, argument)
                if (value != "json" && value != "text") {
                    fail("invalid_arguments", "--format must be json or text", 2)
                }
                format = value
                index = next
            }

            "--source-build" -> sourceBuild = true
            else -> fail("invalid_arguments", "unknown option: $argument", 2)
        }
        index++
    }

    val report = EnvCheck.run(EnvCheckOptions(projectDir = projectDir, sourceBuild = sourceBuild))
    if (format == "json") {
        printJson(io.out, report)
    } else {
        printDoctorText(report, io.out)
    }
    return if (report.ok) 0 else 1
}

private fun runStatusCommand(args: Array<String>, io: CliIO): Int {
    if (helpRequested(args)) {
        printStatusHelp(io.out)
        return 0
    }
    var projectDir = ""
    var home = ""
    var format = "text"
    var checkClasspath = false
    var index = 0
    while (index < args.size) {
        when (val argument = args[index]) {
            "--project-dir", "--home", "--format" -> {
                val (value, next) = optionValue(args, index, argument)
                when (argument) {
                    "--project-dir" -> projectDir = value
                    "--home" -> home = value
                    "--format" -> {
                        if (value != "json" && value != "text") {
                            fail("invalid_arguments", "--format must be json or text", 2)
                        }
                        format = value
                    }
                }
                index = next
            }

            "--check-classpath" -> checkClasspath = true
            else -> fail("invalid_arguments", "unknown option: $argument", 2)
        }
        index++
    }
    val response = try {
        projectStatus(projectDir, home, checkClasspath)
    } catch (failure: CliFailure) {
        throw failure
    } catch (exception: Exception) {
        fail("status_error", errMessage(exception), 1)
    }
    emitResponse(response, format, io)
    return 0
}

// --- shared option parsing (Go parseOptions / optionValue / positiveInteger) ---

internal fun parseOptions(args: Array<String>, argOptional: Boolean): CommandOptions {
    var arg = ""
    var projectDir = ""
    var format = "json"
    var page = 1
    var pageSize = 20
    var home = ""
    var direct = false
    var sourceGrep = true
    var index = 0
    while (index < args.size) {
        when (val argument = args[index]) {
            "--project-dir" -> {
                val (value, next) = optionValue(args, index, argument)
                projectDir = value
                index = next
            }

            "--format" -> {
                val (value, next) = optionValue(args, index, argument)
                if (value != "json" && value != "text") {
                    fail("invalid_arguments", "--format must be json or text", 2)
                }
                format = value
                index = next
            }

            "--page" -> {
                val (value, next) = optionValue(args, index, argument)
                page = positiveInteger(argument, value)
                index = next
            }

            "--page-size" -> {
                val (value, next) = optionValue(args, index, argument)
                pageSize = positiveInteger(argument, value)
                index = next
            }

            "--home" -> {
                val (value, next) = optionValue(args, index, argument)
                home = value
                index = next
            }

            "--direct" -> direct = true
            "--source-grep" -> sourceGrep = true
            "--no-source-grep" -> sourceGrep = false
            else -> {
                if (argument.isNotEmpty() && argument[0] == '-') {
                    fail("invalid_arguments", "unknown option: $argument", 2)
                }
                if (arg.isNotEmpty()) {
                    fail("invalid_arguments", "command accepts exactly one argument", 2)
                }
                arg = argument
            }
        }
        index++
    }
    if (arg.isEmpty() && !argOptional) {
        fail("invalid_arguments", "query argument is required", 2)
    }
    return CommandOptions(
        arg = arg,
        projectDir = projectDir,
        format = format,
        page = page,
        pageSize = pageSize,
        home = home,
        direct = direct,
        sourceGrep = sourceGrep,
    )
}

/**
 * Go optionValue: the value following the flag plus the index to resume the
 * caller's loop at (the caller's own `index++` then skips past the value).
 */
internal fun optionValue(args: Array<String>, index: Int, option: String): Pair<String, Int> {
    if (index + 1 >= args.size) {
        fail("invalid_arguments", "$option requires a value", 2)
    }
    return args[index + 1] to index + 1
}

private fun positiveInteger(option: String, value: String): Int {
    val number = value.toIntOrNull()
    if (number == null || number < 1) {
        fail("invalid_arguments", "$option must be a positive integer", 2)
    }
    return number
}

internal fun helpRequested(args: Array<String>): Boolean =
    args.any { it == "--help" || it == "-h" }

// --- project resolution and error helpers (Go main.go) ---

internal fun resolveProjectOrFail(opts: CommandOptions): Pair<ProjectPointerData, String> = try {
    ProjectIndex.resolve(opts.projectDir, opts.home)
} catch (_: ProjectNotFoundException) {
    fail("no_project_index", noProjectIndexMessage(opts.projectDir), 1)
} catch (failure: CliFailure) {
    throw failure
} catch (exception: Exception) {
    fail("project_index_error", errMessage(exception), 1)
}

internal fun projectRootOrFail(opts: CommandOptions): String = try {
    ProjectIndex.root(opts.projectDir).toString()
} catch (_: ProjectNotFoundException) {
    fail("no_project_index", noProjectIndexMessage(opts.projectDir), 1)
} catch (failure: CliFailure) {
    throw failure
} catch (exception: Exception) {
    fail("project_index_error", errMessage(exception), 1)
}

internal fun failQueryError(exception: Exception): Nothing {
    var message = errMessage(exception)
    if (message.contains("no such table")) {
        message += " (session database was generated by an older version; " +
            "rerun research4jar index to upgrade)"
    }
    fail("query_error", message, 1)
}

internal fun noProjectIndexMessage(projectDir: String): String {
    var command = "research4jar index"
    if (projectDir.isNotEmpty() && projectDir != ".") {
        command += " --project-dir " + strconvQuote(projectDir)
    }
    return "no .research4jar/project.json found; run " + command +
        " first, then run research4jar status to verify the index. " +
        "If indexing fails, run research4jar doctor for environment checks."
}

internal fun doctorHint(projectDir: String, sourceBuild: Boolean): String {
    val args = mutableListOf("research4jar doctor")
    if (projectDir.isNotEmpty() && projectDir != ".") {
        args += "--project-dir " + strconvQuote(projectDir)
    }
    if (sourceBuild) {
        args += "--source-build"
    }
    return "Run " + args.joinToString(" ") +
        " for environment checks and installation guidance."
}

// --- unknown-command suggestions (Go commandSuggestions and helpers) ---

private fun unknownCommandMessage(command: String): String {
    var message = "unknown command: $command"
    val suggestions = commandSuggestions(command)
    if (suggestions.isNotEmpty()) {
        message += "\n\nDid you mean:"
        for (suggestion in suggestions) {
            message += "\n  research4jar $suggestion"
        }
    }
    return message + "\n\nRun research4jar --help for available commands."
}

private fun commandSuggestions(rawCommand: String): List<String> {
    val command = rawCommand.trim().lowercase()
    if (command.isEmpty()) return emptyList()
    val threshold = maxOf(2, command.length / 3)
    return knownTopLevelCommands()
        .map { candidate -> candidate to commandSuggestionScore(command, candidate) }
        .filter { (_, score) -> score <= threshold }
        .sortedWith(compareBy({ it.second }, { it.first }))
        .take(3)
        .map { it.first }
}

private fun knownTopLevelCommands(): List<String> {
    val commands = mutableListOf(
        "artifact",
        "cache",
        "class",
        "dep",
        "doctor",
        "index",
        "mcp",
        "method",
        "registry",
        "status",
        "version",
    )
    commands += QUERY_COMMANDS
    return commands.sorted()
}

private fun commandSuggestionScore(input: String, candidate: String): Int {
    if (candidate.startsWith(input)) return 0
    val compactInput = input.replace("-", "")
    val compactCandidate = candidate.replace("-", "")
    if (compactInput == compactCandidate) return 0
    if (compactCandidate.startsWith(compactInput)) return 1
    return levenshteinDistance(input, candidate)
}

private fun levenshteinDistance(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)
    for (leftIndex in 1..left.length) {
        current[0] = leftIndex
        for (rightIndex in 1..right.length) {
            val cost = if (left[leftIndex - 1] != right[rightIndex - 1]) 1 else 0
            current[rightIndex] = minOf(
                previous[rightIndex] + 1,
                current[rightIndex - 1] + 1,
                previous[rightIndex - 1] + cost,
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[right.length]
}
