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
// Non-QUERY_COMMANDS commands that still open a session database and
// therefore acquire its read lease. `version`, `status`, `cache`, and
// friends must not create the warmup scratch file.
private val SESSION_OPENING_COMMANDS = setOf("dep", "artifact", "class", "method")

/**
 * Session-opening one-shot commands pay ~4ms of first-use class
 * initialization (NIO file locks and the lease protocol around them) inside
 * their first [dev.research4jar.query.Db.openReadOnly]. Warming that class
 * graph on a background thread overlaps the cost with option parsing,
 * project resolution, and sqlite driver loading instead. Purely a
 * class-initialization prefetch — the real lease acquisition is unchanged.
 */
private fun warmSessionLeaseInBackground(args: Array<String>) {
    val command = args.firstOrNull() ?: return
    if (command !in QUERY_COMMANDS && command !in SESSION_OPENING_COMMANDS) return
    Thread { dev.research4jar.runtime.SessionLeaseWarmup.warm() }.apply {
        name = "research4jar-lease-warmup"
        isDaemon = true
        start()
    }
}

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "index-raw") {
        NativeLib.pin(NativeLib.homeArg(args))
        dev.research4jar.indexer.main(args.copyOfRange(1, args.size))
        return
    }
    if (args.isNotEmpty() && args[0] == "daemon") {
        NativeLib.pin(null)
        exitProcess(Daemon.runServer { argv, out, err -> runCli(argv, out, err) })
    }
    // Both daemon entry points already no-op under RESEARCH4JAR_NO_DAEMON;
    // checking the environment here as well keeps the Daemon class graph
    // (several ms of one-shot classloading) entirely unloaded in that case —
    // the same short-circuit pattern that keeps BUILD_ID lazy.
    val daemonDisabled = System.getenv("RESEARCH4JAR_NO_DAEMON") != null
    // The daemon client path never opens sqlite, so pin() runs only after it.
    if (!daemonDisabled) {
        Daemon.tryServe(args)?.let { exitProcess(it) }
    }
    warmSessionLeaseInBackground(args)
    NativeLib.pin(NativeLib.homeArg(args))
    val code = runCli(args, System.out, System.err)
    if (!daemonDisabled) {
        Daemon.spawnAfterColdRun(args)
    }
    exitProcess(code)
}
