package dev.springdep.indexer.store

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal object AtomicFiles {
    fun temporaryTarget(target: Path): Path {
        Files.createDirectories(target.parent)
        return Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    }

    fun commit(temporary: Path, target: Path) {
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        forceDirectory(target.parent)
    }

    private fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // Directory fsync is unavailable on some platforms/filesystems.
        }
    }
}
