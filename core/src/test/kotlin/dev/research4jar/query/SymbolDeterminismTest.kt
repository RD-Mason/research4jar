package dev.research4jar.query

import dev.research4jar.indexer.store.SessionBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class SymbolDeterminismTest {
    @Test
    fun `direct annotation pages use attributes as a payload tie-breaker`() {
        val session = emptySession()
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO classes(
                      id, fqn, kind, super_fqn, modifiers, is_abstract, source_file,
                      simple_name, package_name, source_shard_id
                    ) VALUES (1, 'example.Annotated', 'class', NULL, 1, 0, NULL,
                              'Annotated', 'example', 7)
                    """.trimIndent(),
                )
                // Insert the lexical successor first so row-id order cannot
                // accidentally satisfy the public pagination order.
                statement.execute(
                    """
                    INSERT INTO annotations(
                      id, target_kind, target_id, annotation_fqn, attributes, source_shard_id
                    ) VALUES
                      (1, 'class', 1, 'example.Marker', '{"rank":2}', 7),
                      (2, 'class', 1, 'example.Marker', '{"rank":1}', 7)
                    """.trimIndent(),
                )
            }
        }

        val first = findByAnnotation(pointer(session), "", "example.Marker", true, 1, 1)
        val second = findByAnnotation(pointer(session), "", "example.Marker", true, 2, 1)

        assertEquals("""{"rank":1}""", first.results.single().attributes)
        assertEquals("""{"rank":2}""", second.results.single().attributes)
    }

    @Test
    fun `ambiguous method symbols order distinct decompositions by owner and name`() {
        val session = emptySession()
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO classes(
                      id, fqn, kind, super_fqn, modifiers, is_abstract, source_file,
                      simple_name, package_name, source_shard_id
                    ) VALUES
                      (1, 'example.Owner#part', 'class', NULL, 1, 0, NULL,
                          'Owner#part', 'example', 7),
                      (2, 'example.Owner', 'class', NULL, 1, 0, NULL,
                          'Owner', 'example', 7)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO methods(
                      id, class_id, name, descriptor, return_fqn, modifiers,
                      owner_resolved, source_shard_id
                    ) VALUES
                      (1, 1, 'call', '()V', NULL, 1, 1, 7),
                      (2, 2, 'part#call', '()V', NULL, 1, 1, 7)
                    """.trimIndent(),
                )
                // Make the incidental covering-index scan run in the opposite
                // direction. The result must follow the explicit payload order.
                statement.execute("DROP INDEX idx_s_classes_fqn")
                statement.execute("CREATE INDEX idx_s_classes_fqn ON classes(fqn DESC)")
            }
        }

        val response = openSymbol(pointer(session), "", "example.Owner#part#call")

        assertEquals(
            listOf("example.Owner" to "part#call", "example.Owner#part" to "call"),
            response.results.map { result ->
                result.method!!.let { method -> method.classFqn to method.name }
            },
        )
    }

    private fun emptySession(): Path {
        val session = Files.createTempDirectory("r4j-symbol-order").resolve("session.db")
        SessionBuilder().build(session, emptyList())
        return session
    }

    private fun pointer(session: Path) = ProjectPointerData(
        schemaVersion = 2,
        extractorVersion = 2,
        classpathFingerprint = "test",
        sessionDbPath = session.toString(),
    )
}
