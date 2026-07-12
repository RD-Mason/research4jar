package dev.research4jar.indexer.store

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.research4jar.indexer.extract.ConfigProperty
import dev.research4jar.indexer.extract.ExtractedAnnotation
import dev.research4jar.indexer.extract.ExtractedBeanDefinition
import dev.research4jar.indexer.extract.ExtractedClass
import dev.research4jar.indexer.extract.ExtractedCondition
import dev.research4jar.indexer.extract.ExtractedJar
import dev.research4jar.indexer.extract.ExtractedMethod
import dev.research4jar.indexer.extract.ExtractedStringConstant
import dev.research4jar.indexer.extract.SpiRegistration
import dev.research4jar.indexer.main
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The delta build promises LOGICAL equivalence with a full rebuild — same
 * rows and relationships, not the same bytes — so equality is asserted over
 * canonical table dumps: every id-based reference is resolved to its stable
 * key (class fqn, method fqn#name+descriptor) and each table compares as a
 * sorted multiset.
 */
class SessionDeltaTest {
    @Test
    fun `delta session is logically equivalent to a full rebuild`() {
        val root = Files.createTempDirectory("research4jar-delta-equiv")
        fun shard(name: String): SessionShard {
            val path = root.resolve("$name.db")
            ShardWriter().write(path, "sha-$name", richJar(name))
            return SessionShard("sha-$name@t", path)
        }
        val alpha = shard("alpha")
        val bravo = shard("bravo")
        val charlie = shard("charlie")
        val delta = shard("delta")

        val previous = root.resolve("previous-session.db")
        SessionBuilder().build(previous, listOf(alpha, bravo, charlie))
        val viaDelta = root.resolve("delta-session.db")
        SessionBuilder().buildDelta(
            previous = previous,
            target = viaDelta,
            removedShardIds = setOf(alpha.shardId),
            addedShards = listOf(delta),
        )
        val viaFull = root.resolve("full-session.db")
        SessionBuilder().build(viaFull, listOf(bravo, charlie, delta))

        assertEquals(canonicalDump(viaFull), canonicalDump(viaDelta))
        assertTrue(SessionBuilder().isReusable(viaDelta))
        assertEquals(
            setOf(bravo.shardId, charlie.shardId, delta.shardId),
            SessionBuilder().reusableShardSet(viaDelta),
        )
        // The previous session is an input, never mutated.
        assertEquals(
            setOf(alpha.shardId, bravo.shardId, charlie.shardId),
            SessionBuilder().reusableShardSet(previous),
        )
        assertNoTemporaries(root)
    }

    @Test
    fun `delta build failure throws and leaves no temporary file`() {
        val root = Files.createTempDirectory("research4jar-delta-fail")
        val garbage = root.resolve("not-a-session.db")
        Files.write(garbage, ByteArray(512) { 0x42 })
        assertFailsWith<Exception> {
            SessionBuilder().buildDelta(
                previous = garbage,
                target = root.resolve("target-session.db"),
                removedShardIds = setOf("sha-gone@t"),
                addedShards = emptyList(),
            )
        }
        assertFalse(Files.exists(root.resolve("target-session.db")))
        assertNoTemporaries(root)
    }

    @Test
    fun `pipeline delta path matches a full rebuild of the same jar set`() {
        val root = Files.createTempDirectory("research4jar-delta-pipeline")
        val jars = root.resolve("jars")
        val project = root.resolve("project")
        val home = root.resolve("home")
        Files.createDirectories(jars)
        (1..8).forEach { writeServiceJar(jars.resolve("lib$it.jar"), "com.example.Impl$it") }
        assertFalse(runIndex(jars, project, home).contains("delta-updating"))

        // 1 removed + 1 added out of 8 is within the 25% delta bound.
        Files.delete(jars.resolve("lib1.jar"))
        writeServiceJar(jars.resolve("lib9.jar"), "com.example.Impl9")
        val stderr = runIndex(jars, project, home)
        assertContains(stderr, "delta-updating session: +1 -1 shards")

        val sessionPath = sessionOf(project)
        val deltaCopy = root.resolve("delta-copy.db")
        Files.copy(sessionPath, deltaCopy)
        // Deleting the session forces the next identical run through the full
        // rebuild (the pointer's session is gone, so no delta base exists).
        Files.delete(sessionPath)
        assertFalse(runIndex(jars, project, home).contains("delta-updating"))
        assertEquals(sessionPath, sessionOf(project))
        assertEquals(canonicalDump(sessionPath), canonicalDump(deltaCopy))
        assertEquals(8, canonicalDump(deltaCopy).getValue("spi_registrations").size)
    }

    @Test
    fun `pipeline rebuilds fully past the delta threshold`() {
        val root = Files.createTempDirectory("research4jar-delta-threshold")
        val jars = root.resolve("jars")
        val project = root.resolve("project")
        val home = root.resolve("home")
        Files.createDirectories(jars)
        (1..4).forEach { writeServiceJar(jars.resolve("lib$it.jar"), "com.example.Impl$it") }
        runIndex(jars, project, home)

        // 2 removed + 2 added against a new set of 4 is 100% churn — far past
        // the 25% bound, so the delta must not run.
        Files.delete(jars.resolve("lib1.jar"))
        Files.delete(jars.resolve("lib2.jar"))
        writeServiceJar(jars.resolve("lib5.jar"), "com.example.Impl5")
        writeServiceJar(jars.resolve("lib6.jar"), "com.example.Impl6")
        val stderr = runIndex(jars, project, home)
        assertFalse(stderr.contains("delta-updating"), stderr)
        assertEquals(4, SessionBuilder().reusableShardSet(sessionOf(project))?.size)
    }

    @Test
    fun `pipeline rebuilds fully when the previous session is corrupt`() {
        val root = Files.createTempDirectory("research4jar-delta-corrupt")
        val jars = root.resolve("jars")
        val project = root.resolve("project")
        val home = root.resolve("home")
        Files.createDirectories(jars)
        (1..8).forEach { writeServiceJar(jars.resolve("lib$it.jar"), "com.example.Impl$it") }
        runIndex(jars, project, home)
        Files.write(sessionOf(project), ByteArray(1024) { 0x13 })

        Files.delete(jars.resolve("lib1.jar"))
        writeServiceJar(jars.resolve("lib9.jar"), "com.example.Impl9")
        val stderr = runIndex(jars, project, home)
        assertFalse(stderr.contains("delta-updating"), stderr)
        val session = sessionOf(project)
        assertTrue(SessionBuilder().isReusable(session))
        assertEquals(8, canonicalDump(session).getValue("spi_registrations").size)
    }

    private fun richJar(name: String): ExtractedJar {
        val config = "com.$name.Configuration"
        val helper = "com.$name.Helper"
        val beanDescriptor = "()Lcom/$name/Helper;"
        return ExtractedJar(
            coordinate = "com.example:$name:1.0",
            spiRegistrations = listOf(
                SpiRegistration("services", "com.example.Api", "com.$name.Impl"),
            ),
            configProperties = listOf(
                ConfigProperty(name, "$name.enabled", "java.lang.Boolean", "true", null, config),
            ),
            classes = listOf(
                ExtractedClass(
                    config, "class", "java.lang.Object", 1, false,
                    "Configuration.java", listOf("com.example.Api"),
                ),
                ExtractedClass(helper, "class", config, 17, true, null, emptyList()),
            ),
            methods = listOf(
                ExtractedMethod(config, "bean", beanDescriptor, helper, 1),
                ExtractedMethod(helper, "run", "()V", null, 1),
            ),
            annotations = listOf(
                ExtractedAnnotation(
                    targetKind = "class", classFqn = config,
                    annotationFqn = "org.Conf", attributes = """{"jar":"$name"}""",
                ),
                ExtractedAnnotation(
                    "method", config, "bean", beanDescriptor, "org.Bean", "{}",
                ),
            ),
            beanDefinitions = listOf(
                ExtractedBeanDefinition(config, "bean", beanDescriptor, helper, "${name}Helper"),
            ),
            conditions = listOf(
                ExtractedCondition(
                    targetKind = "class", classFqn = config,
                    type = "onClass", refValue = "com.$name.Marker",
                ),
                ExtractedCondition(
                    "method", config, "bean", beanDescriptor, "onMissingBean", helper,
                ),
            ),
            stringConstants = listOf(
                ExtractedStringConstant(helper, "run", "()V", "hello from $name"),
                ExtractedStringConstant(classFqn = config, value = "static $name"),
            ),
        )
    }

    /** A minimal but real jar: one META-INF/services registration. */
    private fun writeServiceJar(target: Path, implFqn: String) {
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/services/com.example.Api"))
            zip.write("$implFqn\n".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
    }

    /** Runs the real index pipeline, returning captured stderr. */
    private fun runIndex(jars: Path, project: Path, home: Path): String {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(stdout, true, StandardCharsets.UTF_8))
            System.setErr(PrintStream(stderr, true, StandardCharsets.UTF_8))
            main(
                arrayOf(
                    "--jars", jars.toString(),
                    "--project-dir", project.toString(),
                    "--home", home.toString(),
                ),
            )
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return stderr.toString(StandardCharsets.UTF_8)
    }

    private fun sessionOf(project: Path): Path =
        Path.of(
            jacksonObjectMapper()
                .readTree(project.resolve(".research4jar/project.json").toFile())
                .get("session_db_path").asText(),
        )

    private fun assertNoTemporaries(directory: Path) {
        Files.list(directory).use { entries ->
            assertTrue(entries.noneMatch { it.fileName.toString().endsWith(".tmp") })
        }
    }

    private fun canonicalDump(session: Path): Map<String, List<String>> =
        DriverManager.getConnection("jdbc:sqlite:$session").use { connection ->
            CANONICAL_QUERIES.mapValues { (_, sql) -> sortedRows(connection, sql) }
        }

    private fun sortedRows(connection: Connection, sql: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                val width = rows.metaData.columnCount
                buildList {
                    while (rows.next()) {
                        add((1..width).joinToString("|") { rows.getString(it) ?: "<null>" })
                    }
                }.sorted()
            }
        }

    private companion object {
        /**
         * One canonical projection per session table: FK ids resolve to
         * stable keys, so two logically equal sessions dump identically no
         * matter which build path produced them.
         */
        val CANONICAL_QUERIES = mapOf(
            "session_meta" to "SELECT session_schema_version FROM session_meta",
            "session_shards" to "SELECT shard_id FROM session_shards",
            "classes" to
                "SELECT fqn, kind, super_fqn, modifiers, is_abstract, source_file, " +
                "simple_name, package_name, source_shard_id FROM classes",
            "class_interfaces" to
                "SELECT c.fqn, ci.interface_fqn, ci.source_shard_id " +
                "FROM class_interfaces ci JOIN classes c ON c.id = ci.class_id",
            "methods" to
                "SELECT c.fqn, m.name, m.descriptor, m.return_fqn, m.modifiers, m.symbol, " +
                "m.source_shard_id FROM methods m JOIN classes c ON c.id = m.class_id",
            "annotations" to
                """
                SELECT a.target_kind,
                       CASE WHEN a.target_kind = 'class' THEN c.fqn
                            ELSE mc.fqn || '#' || m.name || m.descriptor END,
                       a.annotation_fqn, a.attributes, a.source_shard_id
                FROM annotations a
                LEFT JOIN classes c ON a.target_kind = 'class' AND c.id = a.target_id
                LEFT JOIN methods m ON a.target_kind = 'method' AND m.id = a.target_id
                LEFT JOIN classes mc ON mc.id = m.class_id
                """.trimIndent(),
            "conditions" to
                """
                SELECT o.target_kind,
                       CASE WHEN o.target_kind = 'class' THEN c.fqn
                            ELSE mc.fqn || '#' || m.name || m.descriptor END,
                       o.type, o.ref_value, o.source_shard_id
                FROM conditions o
                LEFT JOIN classes c ON o.target_kind = 'class' AND c.id = o.target_id
                LEFT JOIN methods m ON o.target_kind = 'method' AND m.id = o.target_id
                LEFT JOIN classes mc ON mc.id = m.class_id
                """.trimIndent(),
            "bean_definitions" to
                """
                SELECT b.config_fqn,
                       CASE WHEN b.method_id IS NULL THEN '<none>'
                            ELSE mc.fqn || '#' || m.name || m.descriptor END,
                       b.bean_type_fqn, b.bean_name, b.source_shard_id
                FROM bean_definitions b
                LEFT JOIN methods m ON m.id = b.method_id
                LEFT JOIN classes mc ON mc.id = m.class_id
                """.trimIndent(),
            "string_constants" to
                """
                SELECT c.fqn,
                       CASE WHEN sc.method_id IS NULL THEN '<none>'
                            ELSE m.name || m.descriptor END,
                       sc.value, sc.source_shard_id
                FROM string_constants sc
                JOIN classes c ON c.id = sc.class_id
                LEFT JOIN methods m ON m.id = sc.method_id
                """.trimIndent(),
            "config_properties" to
                "SELECT prefix, name, type_fqn, default_val, description, source_fqn, " +
                "source_shard_id FROM config_properties",
            "spi_registrations" to
                "SELECT mechanism, key, impl_fqn, source_shard_id FROM spi_registrations",
        )
    }
}
