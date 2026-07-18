package dev.research4jar.indexer.store

import dev.research4jar.indexer.Hashing
import dev.research4jar.indexer.extract.ConfigProperty
import dev.research4jar.indexer.extract.ExtractedAnnotation
import dev.research4jar.indexer.extract.ExtractedClass
import dev.research4jar.indexer.extract.ExtractedJar
import dev.research4jar.indexer.extract.ExtractedMethod
import dev.research4jar.indexer.extract.SpiRegistration
import dev.research4jar.runtime.SessionFileLease
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    // The fixtures are far below the accelerator size gate but the contract
    // battery corrupts the gated objects, which must therefore exist; force
    // the accelerated shape and restore after. Individual tests that pin the
    // below-gate shape raise the gate again inside their bodies.
    private var savedFtsGate = SessionBuilder.ftsMinMethods

    @BeforeTest
    fun forceAcceleratedSessions() {
        savedFtsGate = SessionBuilder.ftsMinMethods
        SessionBuilder.ftsMinMethods = 0
    }

    @AfterTest
    fun restoreSizeGate() {
        SessionBuilder.ftsMinMethods = savedFtsGate
    }

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
                "SELECT cp.name, ss.shard_id, typeof(cp.source_shard_id) " +
                    "FROM config_properties cp " +
                    "JOIN session_shards ss ON ss.shard_key = cp.source_shard_id",
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals("demo.value", rows.getString("name"))
                assertEquals("abc123@2", rows.getString("shard_id"))
                assertEquals("integer", rows.getString(3))
            }
            connection.createStatement().executeQuery(
                "SELECT simple_name, package_name FROM classes WHERE fqn = 'example.Demo'",
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals("Demo", rows.getString("simple_name"))
                assertEquals("example", rows.getString("package_name"))
            }
            connection.createStatement().executeQuery(
                "SELECT kind, COUNT(*) FROM search_symbols GROUP BY kind ORDER BY kind",
            ).use { rows ->
                val byKind = buildMap<String, Int> {
                    while (rows.next()) put(rows.getString(1), rows.getInt(2))
                }
                assertEquals(
                    mapOf("class" to 1, "config-property" to 1, "spi" to 2),
                    byKind,
                )
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
                "SELECT session_schema_version, delta_depth FROM session_meta",
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals(
                    dev.research4jar.indexer.Research4JarVersions.SESSION,
                    rows.getInt(1),
                )
                assertEquals(0, rows.getInt(2))
                assertFalse(rows.next())
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

    @Test
    fun `v6 session is not reused after compact shard key layout upgrade`() {
        val root = Files.createTempDirectory("research4jar-session-v6")
        val session = root.resolve("session.db")
        val builder = SessionBuilder()
        builder.build(session, emptyList())
        assertTrue(builder.isReusable(session))

        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE session_meta SET session_schema_version = 6")
            }
        }

        assertFalse(builder.isReusable(session))
        assertNull(builder.reusableShardSet(session))
    }

    @Test
    fun `session reuse rejects and repairs missing schema contract objects`() {
        val root = Files.createTempDirectory("research4jar-session-contract")
        val corruptions = linkedMapOf(
            "metadata" to listOf("ALTER TABLE session_meta DROP COLUMN delta_depth"),
            "table" to listOf("DROP TABLE classes"),
            "view" to listOf("DROP VIEW search_symbols"),
            "fts" to listOf("DROP TABLE classes_fts"),
            "wrong-fts-definition" to listOf(
                "DROP TABLE method_names_fts",
                "CREATE VIRTUAL TABLE method_names_fts USING fts5(" +
                    "names, extra, content='method_name_packs', content_rowid='pack_id', " +
                    "tokenize='trigram', detail='none', columnsize=0)",
            ),
            "domain-table" to listOf("DROP TABLE method_descriptors"),
            "pack-table" to listOf("DROP TABLE string_value_packs"),
            "index" to listOf("DROP INDEX idx_s_strconst_method"),
            "domain-index" to listOf("DROP INDEX idx_s_methods_descriptor"),
            "non-unique-domain-index" to listOf(
                "DROP INDEX idx_s_method_names_name",
                "CREATE INDEX idx_s_method_names_name ON method_names(name)",
            ),
            "extra-column" to listOf("ALTER TABLE methods ADD COLUMN symbol TEXT"),
            "wrong-index-definition" to listOf(
                "DROP INDEX idx_s_methods_name",
                "CREATE INDEX idx_s_methods_name ON methods(name) WHERE 0",
            ),
        )

        for ((label, ddls) in corruptions) {
            val session = root.resolve("$label.db")
            val builder = SessionBuilder()
            builder.build(session, emptyList())
            assertTrue(builder.isReusable(session), label)

            DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    ddls.forEach(statement::execute)
                }
            }

            assertFalse(builder.isReusable(session), label)
            assertNull(builder.reusableShardSet(session), label)
            builder.buildIfAbsent(session, emptyList())
            assertTrue(builder.isReusable(session), "$label must be rebuilt, not reused")
        }
    }

    @Test
    fun `gated accelerator objects are validated all-or-none`() {
        val root = Files.createTempDirectory("research4jar-session-gate-contract")
        val session = root.resolve("session.db")
        val builder = SessionBuilder()
        // Built accelerated (class-level gate override): all gated objects.
        builder.build(session, emptyList())
        assertTrue(builder.isReusable(session))

        // Strip every gated object except idx_s_methods_descriptor (an index
        // on the ungated methods table, so it survives the table drops).
        // Any PARTIAL accelerator subset is corruption: rebuild.
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE classes_fts")
                statement.execute("DROP TABLE method_names_fts")
                statement.execute("DROP TABLE method_descriptors_fts")
                statement.execute("DROP TABLE method_name_packs")
                statement.execute("DROP TABLE method_descriptor_packs")
                // Dropping the domain tables also drops their unique indexes.
                statement.execute("DROP TABLE method_names")
                statement.execute("DROP TABLE method_descriptors")
            }
        }
        assertFalse(builder.isReusable(session), "partial accelerator set must be rejected")
        assertNull(builder.reusableShardSet(session))

        // Removing the last gated object leaves exactly the below-gate shape,
        // which is a VALID v9 session (presence-consistency, all-or-none).
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP INDEX idx_s_methods_descriptor")
            }
        }
        assertTrue(
            builder.isReusable(session),
            "a session with none of the gated objects is the valid below-gate shape",
        )
    }

    @Test
    fun `ordinary table cannot impersonate a required fts shadow`() {
        val root = Files.createTempDirectory("research4jar-session-fake-fts")
        val session = root.resolve("session.db")
        val builder = SessionBuilder()
        builder.build(session, emptyList())

        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE method_names_fts")
                statement.execute(
                    "CREATE TABLE method_names_fts(rowid INTEGER, names TEXT)",
                )
            }
        }

        assertFalse(builder.isReusable(session))
        assertNull(builder.reusableShardSet(session))
    }

    @Test
    fun `session contract requires integer and lexically ordered shard keys`() {
        val root = Files.createTempDirectory("research4jar-session-shard-key-contract")
        val builder = SessionBuilder()

        val wrongType = root.resolve("wrong-type.db")
        builder.build(wrongType, emptyList())
        DriverManager.getConnection("jdbc:sqlite:${wrongType.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP INDEX idx_session_shards_key")
                statement.execute("DROP TABLE session_shards")
                statement.execute(
                    "CREATE TABLE session_shards(" +
                        "shard_id TEXT PRIMARY KEY, shard_key TEXT NOT NULL)",
                )
                statement.execute(
                    "CREATE UNIQUE INDEX idx_session_shards_key ON session_shards(shard_key)",
                )
            }
        }
        assertFalse(builder.isReusable(wrongType))

        val wrongOrder = root.resolve("wrong-order.db")
        builder.build(wrongOrder, emptyList())
        DriverManager.getConnection("jdbc:sqlite:${wrongOrder.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO session_shards VALUES ('a@2', 1)")
                statement.execute("INSERT INTO session_shards VALUES ('b@2', 0)")
            }
        }
        assertFalse(builder.isReusable(wrongOrder))
        assertNull(builder.reusableShardSet(wrongOrder))
    }

    @Test
    fun `view with the right columns cannot impersonate search symbols`() {
        val root = Files.createTempDirectory("research4jar-session-fake-view")
        val session = root.resolve("session.db")
        val builder = SessionBuilder()
        builder.build(session, emptyList())

        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP VIEW search_symbols")
                statement.execute(
                    """
                    CREATE VIEW search_symbols AS
                    SELECT 'class' AS kind, fqn AS name, NULL AS owner, kind AS detail,
                           source_shard_id, simple_name, package_name, 55 AS score_hint
                    FROM classes WHERE 0
                    """.trimIndent(),
                )
            }
        }

        assertFalse(builder.isReusable(session))
        assertNull(builder.reusableShardSet(session))
        builder.buildIfAbsent(session, emptyList())
        assertTrue(builder.isReusable(session))
    }

    @Test
    fun `session reuse preserves string literal semantics in the search view`() {
        val root = Files.createTempDirectory("research4jar-session-view-literal")
        val session = root.resolve("session.db")
        val builder = SessionBuilder()
        builder.build(session, emptyList())

        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                val originalSql = statement.executeQuery(
                    "SELECT sql FROM sqlite_schema WHERE type = 'view' AND name = 'search_symbols'",
                ).use { rows ->
                    assertTrue(rows.next())
                    rows.getString(1)
                }
                val changedSql = originalSql.replaceFirst("'class'", "'CLASS'")
                assertTrue(changedSql != originalSql)
                statement.execute("DROP VIEW search_symbols")
                statement.execute(changedSql)
            }
        }

        assertFalse(builder.isReusable(session))
        assertNull(builder.reusableShardSet(session))
        builder.buildIfAbsent(session, emptyList())
        assertTrue(builder.isReusable(session))
    }

    @Test
    fun `delta build keeps a missing base as a strict failure`() {
        val root = Files.createTempDirectory("research4jar-missing-delta-base")
        val missing = root.resolve("missing.db")
        val target = root.resolve("target.db")

        assertFailsWith<NoSuchFileException> {
            SessionBuilder().buildDelta(missing, target, emptySet(), emptyList())
        }
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(root.resolve(".missing.db.lease")))
        assertFalse(Files.exists(root.resolve(".missing.db.lease-turnstile")))
    }

    @Test
    fun `index reuse rebuilds if collector deletes before its lease`() {
        val root = Files.createTempDirectory("research4jar-session-reuse-race")
        val session = root.resolve("session.db")
        val builder = SessionBuilder()
        builder.build(session, emptyList())
        val collector = SessionFileLease.acquireExclusive(session)
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reuse = executor.submit {
                started.countDown()
                builder.buildIfAbsent(session, emptyList())
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(50)
            assertFalse(reuse.isDone, "reuse must wait for the collector's exclusive lease")

            assertTrue(Files.deleteIfExists(session))
            collector.close()
            reuse.get(10, TimeUnit.SECONDS)

            assertTrue(Files.isRegularFile(session), "index must rebuild the path before publishing its pointer")
            assertTrue(builder.isReusable(session))
        } finally {
            collector.close()
            executor.shutdownNow()
        }
    }
}
