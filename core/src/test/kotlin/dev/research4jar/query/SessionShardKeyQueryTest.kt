package dev.research4jar.query

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.research4jar.indexer.extract.ExtractedClass
import dev.research4jar.indexer.extract.ExtractedJar
import dev.research4jar.indexer.store.Manifest
import dev.research4jar.indexer.store.SessionBuilder
import dev.research4jar.indexer.store.SessionShard
import dev.research4jar.indexer.store.ShardWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionShardKeyQueryTest {
    @Test
    fun `full and delta sessions preserve shard ordering and external provenance`() {
        val root = Files.createTempDirectory("research4jar-shard-key-query")
        val manifestPath = root.resolve("manifest.db")
        val manifest = Manifest(manifestPath)
        fun shard(id: String, coordinate: String): SessionShard {
            val path = root.resolve("${id.substringBefore('@')}.db")
            ShardWriter().write(
                path,
                id,
                ExtractedJar(
                    coordinate = coordinate,
                    classes = listOf(
                        ExtractedClass(
                            fqn = "example.Shared",
                            kind = "class",
                            superFqn = null,
                            modifiers = 1,
                            isAbstract = false,
                            sourceFile = "Shared.java",
                            interfaces = emptyList(),
                        ),
                    ),
                ),
            )
            manifest.register(
                shardId = id,
                coordinate = coordinate,
                jarFilename = "${id.substringBefore('@')}.jar",
                jarSha256 = id,
                shardPath = path,
                shardChecksum = "checksum-$id",
                sizeBytes = Files.size(path),
            )
            return SessionShard(id, path)
        }

        val alpha = shard("a@2", "example:alpha:1")
        val middle = shard("m@2", "example:middle:1")
        val omega = shard("z@2", "example:omega:1")
        val builder = SessionBuilder()
        val previous = root.resolve("previous.db")
        builder.build(previous, listOf(alpha, omega))
        val delta = root.resolve("delta.db")
        builder.buildDelta(previous, delta, emptySet(), listOf(middle))
        val full = root.resolve("full.db")
        builder.build(full, listOf(omega, middle, alpha))

        val expectedSources = listOf("example:alpha:1", "example:middle:1", "example:omega:1")
        for (session in listOf(full, delta)) {
            val pointer = pointer(session)
            val classPages = (1..3).map { page ->
                findClass(pointer, manifestPath.toString(), "example.Shared", page, 1)
                    .results.single().sourceJar
            }
            val symbolPages = (1..3).map { page ->
                searchSymbol(pointer, manifestPath.toString(), "example.Shared", page, 1)
                    .results.single().sourceJar
            }
            assertEquals(expectedSources, classPages)
            assertEquals(expectedSources, symbolPages)

            val precise = classPrecise(
                pointer = pointer,
                manifestPath = manifestPath.toString(),
                projectDir = root.toString(),
                arg = "example.Shared",
                pageSize = 10,
                includeSourceUsages = false,
            )
            assertEquals(listOf("a@2", "m@2", "z@2"), precise.origins.map { it.shardId })
            assertEquals(expectedSources, precise.origins.map { it.sourceJar })

            // Exercise the serialized field names as well as the Kotlin model:
            // internal numeric keys must never escape as shard_id/source_jar.
            val json = jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(precise)
            assertEquals("a@2", json.path("origins").path(0).path("shard_id").asText())
            assertEquals(
                "example:alpha:1",
                json.path("origins").path(0).path("source_jar").asText(),
            )
        }
    }

    private fun pointer(session: Path) = ProjectPointerData(
        schemaVersion = 2,
        extractorVersion = 2,
        classpathFingerprint = "test",
        sessionDbPath = session.toString(),
    )
}
