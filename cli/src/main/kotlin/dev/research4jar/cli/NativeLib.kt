package dev.research4jar.cli

import dev.research4jar.indexer.Research4JarPaths
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.sqlite.SQLiteJDBCLoader
import org.sqlite.util.OSInfo

/**
 * Pins the sqlite-jdbc native library to a stable file under the data home.
 *
 * The stock loader extracts the bundled library to a fresh random temp path
 * on every process start; on macOS every new path pays Gatekeeper validation
 * again (~400ms measured), which dominated one-shot CLI latency. The same
 * file loaded from a stable path validates once and is nearly free
 * afterwards. Best-effort only: on any failure the properties stay unset and
 * the stock loader takes over.
 */
object NativeLib {
    fun pin(explicitHome: String?) {
        try {
            // A user-provided library location always wins.
            if (System.getProperty("org.sqlite.lib.path") != null) return
            val folder = OSInfo.getNativeLibFolderPathForCurrentOS()
            val name = linkedSetOf(
                System.mapLibraryName("sqlitejdbc"),
                "libsqlitejdbc.dylib",
                "libsqlitejdbc.so",
                "sqlitejdbc.dll",
            ).firstOrNull {
                NativeLib::class.java.getResource("/org/sqlite/native/$folder/$it") != null
            } ?: return
            val libDir = Research4JarPaths.resolve(explicitHome).home.resolve("lib")
            // Version-keyed so a sqlite-jdbc upgrade re-extracts cleanly.
            val pinned = libDir.resolve("sqlitejdbc-${SQLiteJDBCLoader.getVersion()}-$name")
            if (!Files.isRegularFile(pinned)) {
                Files.createDirectories(libDir)
                val temporary = Files.createTempFile(libDir, "sqlitejdbc", ".tmp")
                try {
                    NativeLib::class.java
                        .getResourceAsStream("/org/sqlite/native/$folder/$name")!!
                        .use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
                    Files.move(temporary, pinned, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    // A concurrent run may have won the rename race; only the
                    // file's existence matters.
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            if (Files.isRegularFile(pinned)) {
                System.setProperty("org.sqlite.lib.path", libDir.toAbsolutePath().toString())
                System.setProperty("org.sqlite.lib.name", pinned.fileName.toString())
            }
        } catch (_: Exception) {
            // stock loader path still works
        }
    }

    /** The --home override from raw args, mirroring how commands resolve it. */
    fun homeArg(args: Array<String>): String? {
        args.forEachIndexed { index, arg ->
            if (arg == "--home" && index + 1 < args.size) return args[index + 1]
            if (arg.startsWith("--home=")) return arg.substringAfter("=")
        }
        return null
    }
}
