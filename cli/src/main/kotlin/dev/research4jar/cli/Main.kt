package dev.research4jar.cli

import kotlin.system.exitProcess

/**
 * JVM CLI entrypoint (M6). `index-raw` preserves the raw indexer argument
 * contract (`--jars/--project-dir/--home`, JSON stats on stdout) that
 * tests/e2e.sh drives via RESEARCH4JAR_INDEX; every other invocation goes
 * through [runCli], the Go-parity dispatcher ported from
 * querier/cmd/research4jar/main.go. Only this wrapper may exit the process —
 * command handlers return exit codes so the daemon can reuse them.
 *
 * Daemon fast path: query commands try a warm daemon first (~50-80ms
 * round-trip vs ~700ms cold JVM); any miss falls through to the in-process
 * run and spawns a daemon in the background for next time.
 */
fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "index-raw") {
        dev.research4jar.indexer.main(args.copyOfRange(1, args.size))
        return
    }
    if (args.isNotEmpty() && args[0] == "daemon") {
        exitProcess(Daemon.runServer { argv, out, err -> runCli(argv, out, err) })
    }
    Daemon.tryServe(args)?.let { exitProcess(it) }
    val code = runCli(args, System.out, System.err)
    Daemon.spawnAfterColdRun(args)
    exitProcess(code)
}
