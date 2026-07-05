package dev.research4jar.envcheck

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Locates the research4jar-index launcher, ported from
 * querier/internal/indexer.Locate (Go): explicit path, the RESEARCH4JAR_INDEX
 * env var, the install layout next to this executable, the in-repo
 * development layout, then PATH.
 */
object IndexerLocate {
    class NotFoundException : RuntimeException(
        "research4jar-index not found; set RESEARCH4JAR_INDEX, pass --indexer, " +
            "or install it next to the research4jar binary",
    )

    fun locate(explicit: String = ""): String {
        if (explicit.isNotEmpty()) return explicit
        val env = System.getenv("RESEARCH4JAR_INDEX")
        if (!env.isNullOrEmpty()) return env
        val goos = currentGoos()
        val launcher = if (goos == "windows") "research4jar-index.bat" else "research4jar-index"
        val binDir = executableDir()
        if (binDir != null) {
            val candidates = listOf(
                binDir.resolve(launcher),
                // `make install` layout: bin/research4jar next to
                // libexec/research4jar-index/bin/research4jar-index. When the
                // CLI jar lives in a lib/ sibling instead of bin/, the shared
                // parent still holds libexec/, so the same candidate resolves.
                binDir.resolve(Paths.get("..", "libexec", "research4jar-index", "bin", launcher)),
            )
            for (candidate in candidates) {
                if (isExecutableFile(candidate, goos)) return candidate.normalize().toString()
            }
            // In-repo layout for development builds. The Go binary sits at a
            // fixed depth (build/bin, two levels below the repo root); the JVM
            // CLI runs from cli/build/libs/*.jar or a build classes directory,
            // so walk the base dir and a few ancestors for the Gradle
            // installDist output instead of hardcoding one ../.. offset.
            for (ancestor in generateSequence(binDir) { it.parent }.take(6)) {
                val candidate = ancestor.resolve(
                    Paths.get("core", "build", "install", "research4jar-index", "bin", launcher),
                )
                if (isExecutableFile(candidate, goos)) return candidate.normalize().toString()
            }
        }
        lookPath(launcher, goos)?.let { return it }
        throw NotFoundException()
    }

    // Best-effort analog of Go's os.Executable for a JVM CLI: prefer the
    // research4jar.home system property (launcher scripts set it to the
    // install home whose bin/ holds the CLI), else the directory containing
    // the running jar or classes tree via this class's CodeSource. Any
    // failure returns null and locate() falls through to the PATH candidate.
    private fun executableDir(): Path? = homeBinDir() ?: codeSourceDir()

    private fun homeBinDir(): Path? = try {
        val home = System.getProperty("research4jar.home")
        if (home.isNullOrEmpty()) {
            null
        } else {
            val homePath = Paths.get(home).toAbsolutePath().normalize()
            val bin = homePath.resolve("bin")
            if (Files.isDirectory(bin)) bin else homePath
        }
    } catch (_: Exception) {
        null
    }

    private fun codeSourceDir(): Path? = try {
        val location = IndexerLocate::class.java.protectionDomain?.codeSource?.location
        if (location == null) {
            null
        } else {
            val source = Paths.get(location.toURI()).toAbsolutePath().normalize()
            if (Files.isDirectory(source)) source else source.parent
        }
    } catch (_: Exception) {
        null
    }

    /** Go's isExecutableFile: a regular file with any execute bit set. */
    private fun isExecutableFile(path: Path, goos: String): Boolean =
        Files.isRegularFile(path) && (goos == "windows" || hasExecuteBit(path))
}
