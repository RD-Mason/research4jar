package dev.research4jar.cli

import kotlin.system.exitProcess

/**
 * JVM CLI entrypoint (M6). Commands are ported from the Go querier phase by
 * phase; `index-raw` preserves the raw indexer argument contract
 * (`--jars/--project-dir/--home`, JSON stats on stdout) that tests/e2e.sh
 * drives via RESEARCH4JAR_INDEX.
 */
fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "index-raw") {
        dev.research4jar.indexer.main(args.copyOfRange(1, args.size))
        return
    }
    System.err.println(
        "research4jar JVM CLI: command set lands with M6 Phase 1; " +
            "use the Go binary until the migration completes",
    )
    exitProcess(2)
}
