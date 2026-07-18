package dev.research4jar.runtime

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Request-scoped working directory for long-lived hosts such as the CLI
 * daemon. Java's default filesystem caches the process directory, so changing
 * the `user.dir` system property cannot make relative [Path] resolution follow
 * an individual request. Query and data-home paths resolve through this
 * context instead.
 */
object WorkingDirectoryContext {
    private val requestDirectory = ThreadLocal<Path?>()

    /** Current request directory, or the process directory outside a host. */
    fun current(): Path = requestDirectory.get()
        ?: Paths.get("").toAbsolutePath().normalize()

    /** Resolve [path] exactly as a process launched from [current] would. */
    fun resolve(path: Path): Path =
        if (path.isAbsolute) path.normalize() else current().resolve(path).normalize()

    fun resolve(path: String): Path = resolve(Paths.get(path))

    /** Run [action] with an absolute request working directory. */
    fun <T> withDirectory(directory: Path, action: () -> T): T {
        require(directory.isAbsolute) { "working directory must be absolute: $directory" }
        val previous = requestDirectory.get()
        requestDirectory.set(directory.normalize())
        return try {
            action()
        } finally {
            if (previous == null) requestDirectory.remove() else requestDirectory.set(previous)
        }
    }
}
