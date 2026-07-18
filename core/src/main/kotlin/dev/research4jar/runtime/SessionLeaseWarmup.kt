package dev.research4jar.runtime

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Pre-initializes the session-lease machinery (and the JDK file-lock class
 * graph beneath it) by running one real shared acquire/release against a
 * scratch file in the user temp directory. A one-shot query process
 * otherwise pays ~4ms of first-use class initialization inside its
 * latency-critical session open; run from a background thread at startup,
 * that cost overlaps the JVM's remaining bootstrap instead.
 *
 * Correctness is untouched: the warmup exercises the unmodified protocol on
 * a path outside any data home (collectors can never observe it), and the
 * shared leases concurrent warmups take on the same scratch file are
 * mutually compatible. Best-effort: on any failure the real acquire simply
 * pays the first-use costs, exactly as without this optimization.
 */
object SessionLeaseWarmup {
    fun warm() {
        try {
            val directory = Paths.get(
                System.getProperty("java.io.tmpdir"),
                "research4jar-warmup-${System.getProperty("user.name") ?: "default"}",
            )
            Files.createDirectories(directory)
            val session = directory.resolve("warm.db")
            if (!Files.exists(session)) {
                try {
                    Files.createFile(session)
                } catch (_: FileAlreadyExistsException) {
                    // A concurrent warmup won the creation race.
                }
            }
            SessionFileLease.acquireSharedIfPresent(session)?.close()
        } catch (_: Exception) {
            // Interruption, permissions, a read-only or full temp filesystem:
            // warmup must never surface a failure.
        }
    }
}
