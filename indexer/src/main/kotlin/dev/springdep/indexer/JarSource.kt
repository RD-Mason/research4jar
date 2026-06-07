package dev.springdep.indexer

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

object JarSource {
    fun resolve(specification: String?): List<Path> {
        if (specification.isNullOrBlank()) return emptyList()

        return specification
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .flatMap(::resolveOne)
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .sortedBy(Path::toString)
    }

    private fun resolveOne(value: String): List<Path> {
        val path = Paths.get(value)
        if (Files.isDirectory(path)) {
            return Files.walk(path).use { stream ->
                stream
                    .filter(Files::isRegularFile)
                    .filter { it.fileName.toString().endsWith(".jar", ignoreCase = true) }
                    .collect(Collectors.toList())
            }
        }
        if (!containsGlob(value)) {
            return listOf(path)
        }

        val absolutePattern = path.toAbsolutePath().normalize().toString()
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$absolutePattern")
        val root = globSearchRoot(path)
        if (!Files.exists(root)) return emptyList()

        return Files.walk(root).use { stream ->
            stream
                .filter(Files::isRegularFile)
                .filter { matcher.matches(it.toAbsolutePath().normalize()) }
                .filter { it.fileName.toString().endsWith(".jar", ignoreCase = true) }
                .collect(Collectors.toList())
        }
    }

    private fun containsGlob(value: String): Boolean =
        value.any { it == '*' || it == '?' || it == '[' || it == '{' }

    private fun globSearchRoot(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var root = absolute.root ?: Paths.get(".").toAbsolutePath().normalize()
        for (part in absolute) {
            if (containsGlob(part.toString())) break
            root = root.resolve(part)
        }
        return if (Files.isDirectory(root)) root else root.parent ?: absolute.root
    }
}
