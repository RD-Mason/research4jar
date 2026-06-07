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
        if (containsGlob(value)) {
            return resolveGlob(value)
        }
        val path = Paths.get(value)
        if (Files.isDirectory(path)) {
            return Files.walk(path).use { stream ->
                stream
                    .filter(Files::isRegularFile)
                    .filter { it.fileName.toString().endsWith(".jar", ignoreCase = true) }
                    .collect(Collectors.toList())
            }
        }
        return listOf(path)
    }

    private fun resolveGlob(value: String): List<Path> {
        val firstGlob = value.indexOfFirst { it == '*' || it == '?' || it == '[' || it == '{' }
        val separator = value.substring(0, firstGlob)
            .indexOfLast { it == '/' || it == '\\' }
        val rootText = if (separator < 0) "." else value.substring(0, separator + 1)
        val relativePattern = value.substring(separator + 1).replace('\\', '/')
        val root = Paths.get(rootText).toAbsolutePath().normalize()
        if (!Files.exists(root)) return emptyList()
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$relativePattern")

        return Files.walk(root).use { stream ->
            stream
                .filter(Files::isRegularFile)
                .filter { matcher.matches(root.relativize(it.toAbsolutePath().normalize())) }
                .filter { it.fileName.toString().endsWith(".jar", ignoreCase = true) }
                .collect(Collectors.toList())
        }
    }

    private fun containsGlob(value: String): Boolean =
        value.any { it == '*' || it == '?' || it == '[' || it == '{' }
}
