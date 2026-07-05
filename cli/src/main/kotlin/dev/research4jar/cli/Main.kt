package dev.research4jar.cli

import kotlin.system.exitProcess

/**
 * JVM CLI entrypoint (M6). `index-raw` preserves the raw indexer argument
 * contract (`--jars/--project-dir/--home`, JSON stats on stdout) that
 * tests/e2e.sh drives via RESEARCH4JAR_INDEX; every other invocation goes
 * through [runCli], the Go-parity dispatcher ported from
 * querier/cmd/research4jar/main.go. Only this wrapper may exit the process —
 * command handlers return exit codes so a daemon can reuse them.
 */
fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "index-raw") {
        dev.research4jar.indexer.main(args.copyOfRange(1, args.size))
        return
    }
    exitProcess(runCli(args, System.out, System.err))
}
