package dev.research4jar.cli

import dev.research4jar.query.ProjectIndex
import dev.research4jar.query.artifactPrecise
import dev.research4jar.query.classPrecise
import dev.research4jar.query.dependencyPrecise
import dev.research4jar.query.explainConditional
import dev.research4jar.query.findByAnnotation
import dev.research4jar.query.findClass
import dev.research4jar.query.findConfigProperties
import dev.research4jar.query.findImplementations
import dev.research4jar.query.findMethod
import dev.research4jar.query.findString
import dev.research4jar.query.getBeanDefinitions
import dev.research4jar.query.getClass
import dev.research4jar.query.getSource
import dev.research4jar.query.listExtensionPoints
import dev.research4jar.query.listPackages
import dev.research4jar.query.openSymbol
import dev.research4jar.query.searchSource
import dev.research4jar.query.searchSymbol
import dev.research4jar.query.whyDependency

/**
 * The query-command half of the Go CLI main(): the fourteen query commands
 * plus the dep/artifact/class/method entrypoints, ported from
 * querier/cmd/research4jar/main.go.
 */

internal fun runQueryCommand(command: String, args: Array<String>, io: CliIO) {
    if (helpRequested(args)) {
        printQueryHelp(command, io.out)
        return
    }
    val opts = parseOptions(
        args,
        command == "list-extension-points" || command == "list-packages",
        optionsForQuery(command),
    )
    if (command == "search-source" && opts.inTarget.isEmpty()) {
        fail("invalid_arguments", "search-source requires --in <coordinate|jar-filename|class-fqn>", 2)
    }
    val (pointer, manifestPath) = resolveProjectOrFail(opts)

    val response: Any = try {
        when (command) {
            "find-config-properties" ->
                findConfigProperties(pointer, manifestPath, opts.arg, opts.page, opts.pageSize)

            "find-implementations" ->
                findImplementations(pointer, manifestPath, opts.arg, opts.direct, opts.page, opts.pageSize)

            "find-by-annotation" ->
                findByAnnotation(pointer, manifestPath, opts.arg, opts.direct, opts.page, opts.pageSize)

            "get-class" -> getClass(pointer, manifestPath, opts.arg)

            "get-bean-definitions" ->
                getBeanDefinitions(pointer, manifestPath, opts.arg, opts.page, opts.pageSize)

            "explain-conditional" -> explainConditional(pointer, manifestPath, opts.arg)

            "find-string" ->
                findString(pointer, manifestPath, opts.arg, opts.page, opts.pageSize)

            "list-extension-points" ->
                listExtensionPoints(pointer, manifestPath, opts.arg, opts.page, opts.pageSize)

            "find-class" ->
                findClass(pointer, manifestPath, opts.arg, opts.page, opts.pageSize)

            "find-method" ->
                findMethod(pointer, manifestPath, opts.arg, opts.page, opts.pageSize)

            "list-packages" ->
                listPackages(pointer, manifestPath, opts.arg, opts.page, opts.pageSize)

            "search-symbol" ->
                searchSymbol(pointer, manifestPath, opts.arg, opts.page, opts.pageSize)

            "open-symbol" -> openSymbol(pointer, manifestPath, opts.arg)

            "get-source" ->
                getSource(
                    pointer, manifestPath,
                    ProjectIndex.root(opts.projectDir).toString(), opts.home,
                    opts.arg, opts.fetch,
                )

            "search-source" ->
                searchSource(
                    pointer, manifestPath,
                    ProjectIndex.root(opts.projectDir).toString(), opts.home,
                    opts.arg, opts.inTarget, opts.fetch, opts.page, opts.pageSize,
                )

            "why-dependency" -> {
                // Go resolves the project root here and folds a failure into
                // the shared query_error path (ProjectNotFoundException's
                // message is "no project index", matching project.ErrNotFound).
                val projectRoot = ProjectIndex.root(opts.projectDir).toString()
                whyDependency(pointer, manifestPath, projectRoot, opts.arg)
            }

            else -> fail("invalid_command", unknownQueryCommand(command), 2)
        }
    } catch (failure: CliFailure) {
        throw failure
    } catch (exception: Exception) {
        failQueryError(exception)
    }

    emitResponse(response, opts.format, io)
}

// Unreachable: dispatch() only routes members of QUERY_COMMANDS here. Kept as
// a defensive message rather than a raw when-exhaustiveness crash.
private fun unknownQueryCommand(command: String): String = "unknown command: $command"

internal fun runDepCommand(args: Array<String>, io: CliIO) {
    if (args.isEmpty() || helpRequested(args.copyOfRange(0, 1))) {
        printDepHelp(io.out)
        return
    }
    val subcommand = args[0]
    if (args.size > 1 && helpRequested(args.copyOfRange(1, args.size))) {
        printDepSubcommandHelp(subcommand, io.out)
        return
    }
    if (subcommand != "precise" && subcommand != "origin" &&
        subcommand != "resolve" && subcommand != "why"
    ) {
        fail(
            "invalid_arguments",
            "usage: research4jar dep precise <IMPORT|CLASS|COORD|JAR> [--no-source-grep] | " +
                "research4jar dep why <COORD|JAR|CLASS>",
            2,
        )
    }
    val opts = parseOptions(
        args.copyOfRange(1, args.size),
        false,
        if (subcommand == "why") optionsForQuery("why-dependency") else preciseLookupOptions(),
    )
    when (subcommand) {
        "precise", "origin", "resolve" -> runDependencyPrecise(opts, io)
        "why" -> runWhyDependency(opts, io)
        else -> fail("invalid_arguments", "unknown dep subcommand: $subcommand", 2)
    }
}

internal fun runArtifactCommand(args: Array<String>, io: CliIO) {
    if (helpRequested(args)) {
        printArtifactHelp(io.out)
        return
    }
    val opts = parseOptions(args, false, preciseLookupOptions())
    runArtifactPrecise(opts, io)
}

internal fun runClassAliasCommand(args: Array<String>, io: CliIO) {
    if (helpRequested(args)) {
        printClassHelp(io.out)
        return
    }
    val opts = parseOptions(args, false, preciseLookupOptions())
    val (pointer, manifestPath) = resolveProjectOrFail(opts)
    val projectRoot = projectRootOrFail(opts)
    val response = try {
        classPrecise(pointer, manifestPath, projectRoot, opts.arg, opts.pageSize, opts.sourceGrep)
    } catch (failure: CliFailure) {
        throw failure
    } catch (exception: Exception) {
        failQueryError(exception)
    }
    emitResponse(response, opts.format, io)
}

internal fun runMethodAliasCommand(args: Array<String>, io: CliIO) {
    if (helpRequested(args)) {
        printMethodHelp(io.out)
        return
    }
    val opts = parseOptions(args, false, methodLookupOptions())
    val (pointer, manifestPath) = resolveProjectOrFail(opts)
    val response = try {
        findMethod(pointer, manifestPath, opts.arg, opts.page, opts.pageSize)
    } catch (failure: CliFailure) {
        throw failure
    } catch (exception: Exception) {
        failQueryError(exception)
    }
    emitResponse(response, opts.format, io)
}

private fun runDependencyPrecise(opts: CommandOptions, io: CliIO) {
    val (pointer, manifestPath) = resolveProjectOrFail(opts)
    val projectRoot = projectRootOrFail(opts)
    val response = try {
        dependencyPrecise(pointer, manifestPath, projectRoot, opts.arg, opts.pageSize, opts.sourceGrep)
    } catch (failure: CliFailure) {
        throw failure
    } catch (exception: Exception) {
        failQueryError(exception)
    }
    emitResponse(response, opts.format, io)
}

private fun runArtifactPrecise(opts: CommandOptions, io: CliIO) {
    val (pointer, manifestPath) = resolveProjectOrFail(opts)
    val projectRoot = projectRootOrFail(opts)
    val response = try {
        artifactPrecise(pointer, manifestPath, projectRoot, opts.arg, opts.pageSize, opts.sourceGrep)
    } catch (failure: CliFailure) {
        throw failure
    } catch (exception: Exception) {
        failQueryError(exception)
    }
    emitResponse(response, opts.format, io)
}

private fun runWhyDependency(opts: CommandOptions, io: CliIO) {
    val (pointer, manifestPath) = resolveProjectOrFail(opts)
    val projectRoot = projectRootOrFail(opts)
    val response = try {
        whyDependency(pointer, manifestPath, projectRoot, opts.arg)
    } catch (failure: CliFailure) {
        throw failure
    } catch (exception: Exception) {
        failQueryError(exception)
    }
    emitResponse(response, opts.format, io)
}
