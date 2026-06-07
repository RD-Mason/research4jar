package dev.springdep.indexer.store

import dev.springdep.indexer.Hashing
import dev.springdep.indexer.extract.ConfigProperty
import dev.springdep.indexer.extract.ExtractedClass
import dev.springdep.indexer.extract.ExtractedJar
import dev.springdep.indexer.extract.SpiRegistration
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    @Test
    fun `shards are deterministic and session merges one shard at a time`() {
        val root = Files.createTempDirectory("springdep-store-test")
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
    fun `manifest registration is idempotent and preserves filename fallback`() {
        val root = Files.createTempDirectory("springdep-manifest-test")
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
