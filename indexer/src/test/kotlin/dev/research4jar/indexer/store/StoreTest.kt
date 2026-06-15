package dev.research4jar.indexer.store

import dev.research4jar.indexer.Hashing
import dev.research4jar.indexer.extract.ConfigProperty
import dev.research4jar.indexer.extract.ExtractedAnnotation
import dev.research4jar.indexer.extract.ExtractedClass
import dev.research4jar.indexer.extract.ExtractedJar
import dev.research4jar.indexer.extract.ExtractedMethod
import dev.research4jar.indexer.extract.SpiRegistration
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    @Test
    fun `shards are deterministic and session merges one shard at a time`() {
        val root = Files.createTempDirectory("research4jar-store-test")
        val extracted = ExtractedJar(
            coordinate = "com.example:demo:1.0",
            spiRegistrations = listOf(
                SpiRegistration("autoconfig.imports", null, "example.BConfig"),
                SpiRegistration("autoconfig.imports", null, "example.AConfig"),
            ),
            configProperties = listOf(
                ConfigProperty(
                    prefix = "demo",
                    name = "demo.value",
                    typeFqn = "java.lang.String",
                    defaultValue = null,
                    description = "Demo value",
                    sourceFqn = "example.DemoProperties",
                ),
            ),
            classes = listOf(
                ExtractedClass(
                    fqn = "example.Demo",
                    kind = "class",
                    superFqn = null,
                    modifiers = 1,
                    isAbstract = false,
                    sourceFile = "Demo.java",
                    interfaces = listOf("example.Contract"),
                ),
            ),
        )
        val first = root.resolve("first.db")
        val second = root.resolve("second.db")
        ShardWriter().write(first, "abc123", extracted)
        ShardWriter().write(second, "abc123", extracted)

        assertEquals(Hashing.sha256(first), Hashing.sha256(second))
        DriverManager.getConnection("jdbc:sqlite:$first").use { connection ->
            connection.createStatement().executeQuery(
                "SELECT created_at, class_count FROM shard_meta",
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals(0, rows.getInt("created_at"))
                assertEquals(1, rows.getInt("class_count"))
            }
            connection.createStatement().executeQuery(
                "SELECT impl_fqn FROM spi_registrations ORDER BY id",
            ).use { rows ->
                val values = buildList {
                    while (rows.next()) add(rows.getString(1))
                }
                assertEquals(listOf("example.AConfig", "example.BConfig"), values)
            }
        }

        val empty = root.resolve("empty.db")
        ShardWriter().write(
            empty,
            "empty123",
            ExtractedJar(null),
        )
        val session = root.resolve("session.db")
        SessionBuilder().build(
            session,
            listOf(SessionShard("abc123@2", first), SessionShard("empty123@2", empty)),
        )
        DriverManager.getConnection("jdbc:sqlite:$session").use { connection ->
            connection.createStatement().executeQuery(
                "SELECT name, source_shard_id FROM config_properties",
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals("demo.value", rows.getString("name"))
                assertEquals("abc123@2", rows.getString("source_shard_id"))
            }
        }
    }

    @Test
    fun `config property shard order is stable across input order and null vs empty`() {
        val root = Files.createTempDirectory("research4jar-cfg-determinism")
        val nullPrefix = ConfigProperty(
            prefix = null,
            name = "demo.value",
            typeFqn = null,
            defaultValue = null,
            description = null,
            sourceFqn = null,
        )
        val emptyPrefix = nullPrefix.copy(prefix = "")
        val forward = root.resolve("forward.db")
        val reversed = root.resolve("reversed.db")
        ShardWriter().write(
            forward,
            "cfg123",
            ExtractedJar(coordinate = null, configProperties = listOf(nullPrefix, emptyPrefix)),
        )
        ShardWriter().write(
            reversed,
            "cfg123",
            ExtractedJar(coordinate = null, configProperties = listOf(emptyPrefix, nullPrefix)),
        )

        assertEquals(Hashing.sha256(forward), Hashing.sha256(reversed))
    }

    @Test
    fun `session merges class and method annotations with offset-stable ids`() {
        val root = Files.createTempDirectory("research4jar-session-ann")
        fun jar(classFqn: String, annotationFqn: String) = ExtractedJar(
            coordinate = null,
            classes = listOf(
                ExtractedClass(
                    fqn = classFqn,
                    kind = "class",
                    superFqn = null,
                    modifiers = 1,
                    isAbstract = false,
                    sourceFile = null,
                    interfaces = emptyList(),
                ),
            ),
            methods = listOf(
                ExtractedMethod(classFqn, "bean", "()V", null, 1),
            ),
            annotations = listOf(
                ExtractedAnnotation(
                    targetKind = "class",
                    classFqn = classFqn,
                    annotationFqn = annotationFqn,
                    attributes = "{}",
                ),
                ExtractedAnnotation(
                    targetKind = "method",
                    classFqn = classFqn,
                    methodName = "bean",
                    methodDescriptor = "()V",
                    annotationFqn = "$annotationFqn.OnMethod",
                    attributes = "{}",
                ),
            ),
        )
        val firstShard = root.resolve("first.db")
        val secondShard = root.resolve("second.db")
        ShardWriter().write(firstShard, "sha-a", jar("example.Alpha", "example.MarkA"))
        ShardWriter().write(secondShard, "sha-b", jar("example.Beta", "example.MarkB"))
        val session = root.resolve("session.db")
        SessionBuilder().build(
            session,
            listOf(SessionShard("sha-a@2", firstShard), SessionShard("sha-b@2", secondShard)),
        )

        DriverManager.getConnection("jdbc:sqlite:$session").use { connection ->
            connection.createStatement().executeQuery(
                "SELECT target_kind, COUNT(*) FROM annotations GROUP BY target_kind",
            ).use { rows ->
                val byKind = buildMap<String, Int> {
                    while (rows.next()) put(rows.getString(1), rows.getInt(2))
                }
                assertEquals(mapOf("class" to 2, "method" to 2), byKind)
            }
            // Method annotations from the second shard must point at the
            // offset-adjusted method row, not the raw per-shard id.
            connection.createStatement().executeQuery(
                """
                SELECT c.fqn
                FROM annotations a
                JOIN methods m ON m.id = a.target_id
                JOIN classes c ON c.id = m.class_id
                WHERE a.target_kind = 'method' AND a.annotation_fqn = 'example.MarkB.OnMethod'
                """.trimIndent(),
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals("example.Beta", rows.getString(1))
            }
            connection.createStatement().executeQuery(
                "SELECT session_schema_version FROM session_meta",
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals(
                    dev.research4jar.indexer.Research4JarVersions.SESSION,
                    rows.getInt(1),
                )
            }
        }
    }

    @Test
    fun `manifest registration is idempotent and preserves filename fallback`() {
        val root = Files.createTempDirectory("research4jar-manifest-test")
        val manifest = Manifest(root.resolve("manifest.db"))
        val shard = root.resolve("shard.db")
        Files.writeString(shard, "test")

        manifest.register(
            shardId = "abc@1",
            coordinate = null,
            jarFilename = "private.jar",
            jarSha256 = "abc",
            shardPath = shard,
            shardChecksum = "checksum",
            sizeBytes = 4,
        )
        manifest.register(
            shardId = "abc@1",
            coordinate = "should:not:replace",
            jarFilename = "other.jar",
            jarSha256 = "abc",
            shardPath = shard,
            shardChecksum = "other",
            sizeBytes = 5,
        )

        val record = manifest.find("abc@1")!!
        assertNull(record.jarCoordinate)
        assertEquals("private.jar", record.jarFilename)
        assertEquals("checksum", record.shardChecksum)
    }
}
