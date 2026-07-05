package dev.research4jar.gradle

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.research4jar.query.Graph
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Writes .research4jar/dependencies.json exactly like the Go depgraph.Write:
 * 2-space indented JSON + trailing newline, temp file + atomic rename,
 * generated_at stamped when absent.
 */
internal object DepGraphCaptureBridge {
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun write(projectDir: String, graph: Graph) {
        val directory = Paths.get(projectDir, ".research4jar")
        Files.createDirectories(directory)
        val stamped = if (graph.generatedAt == 0L) {
            graph.copy(generatedAt = System.currentTimeMillis() / 1000)
        } else {
            graph
        }
        val formatted = mapper.writeValueAsBytes(stamped) + '\n'.code.toByte()
        val target = directory.resolve("dependencies.json")
        val temporary = Files.createTempFile(directory, ".dependencies.json.", ".tmp")
        try {
            Files.write(temporary, formatted)
            Files.move(
                temporary, target,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
