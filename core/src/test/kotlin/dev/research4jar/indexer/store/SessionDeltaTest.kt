package dev.research4jar.indexer.store

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.research4jar.indexer.MAX_CONSECUTIVE_SESSION_DELTAS
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
        // Corrupt-shard orphans (a method whose class row is gone) merge with
        // an unresolved owner. Their names still belong in methods_fts; alpha's
        // rides the delta DELETE mirror and delta's the delta INSERT mirror.
        injectOrphanMethod(alpha.path)
        injectOrphanMethod(delta.path)

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

        assertEquals(0, SessionBuilder().reusableSessionState(previous)?.deltaDepth)
        assertEquals(1, SessionBuilder().reusableSessionState(viaDelta)?.deltaDepth)
        assertEquals(0, SessionBuilder().reusableSessionState(viaFull)?.deltaDepth)
        assertEquals(canonicalDump(viaFull), canonicalDump(viaDelta))
        // The FTS5 shadows are maintained incrementally on the delta path
        // (mirrored deletes + per-shard inserts) while full builds rebuild
        // them once at commit: integrity-check throws on any divergence from
        // the content table, and a trigram-served LIKE must return the same
        // rows either way.
        assertFtsIntegrity(viaDelta)
        val probes = listOf(
            "SELECT s.value, ss.shard_id FROM string_constants_fts f " +
                "JOIN string_constants s ON s.id = f.rowid " +
                "JOIN session_shards ss ON ss.shard_key = s.source_shard_id " +
                "WHERE f.value LIKE '%from%'",
            "SELECT c.fqn, c.kind, ss.shard_id FROM classes_fts f " +
                "JOIN classes c ON c.id = f.rowid " +
                "JOIN session_shards ss ON ss.shard_key = c.source_shard_id " +
                "WHERE f.fqn LIKE '%Helper%'",
            "SELECT m.name, ss.shard_id FROM methods_fts f " +
                "JOIN methods m ON m.id = f.rowid " +
                "JOIN session_shards ss ON ss.shard_key = m.source_shard_id " +
                "WHERE f.name LIKE '%bean%'",
            "SELECT m.name, m.descriptor, ss.shard_id FROM methods_fts f " +
                "JOIN methods m ON m.id = f.rowid " +
                "JOIN session_shards ss ON ss.shard_key = m.source_shard_id " +
                "WHERE f.descriptor LIKE '%Helper;%'",
            "SELECT m.name, ss.shard_id FROM methods_fts f " +
                "JOIN methods m ON m.id = f.rowid " +
                "JOIN session_shards ss ON ss.shard_key = m.source_shard_id " +
                "WHERE f.name LIKE '%orphaned%'",
        )
        for (probe in probes) {
            assertEquals(ftsMatches(viaFull, probe), ftsMatches(viaDelta, probe), probe)
            assertTrue(
                ftsMatches(viaDelta, probe).isNotEmpty(),
                "battery must exercise the trigram path: $probe",
            )
        }
        // Both build paths must carry exactly delta's orphan (alpha's was
        // removed); its compact name shadow is covered by the probes above.
        assertEquals(1, scalar(viaDelta, "SELECT COUNT(*) FROM methods WHERE owner_resolved = 0"))
        assertEquals(1, scalar(viaFull, "SELECT COUNT(*) FROM methods WHERE owner_resolved = 0"))
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
    fun `session builder tracks a multi-generation delta chain and full build resets it`() {
        val root = Files.createTempDirectory("research4jar-delta-chain")
        fun shard(name: String): SessionShard {
            val path = root.resolve("$name-shard.db")
            ShardWriter().write(path, "sha-$name", richJar(name))
            return SessionShard("sha-$name@t", path)
        }

        val builder = SessionBuilder()
        val active = (1..8).map { shard("chain$it") }.toMutableList()
        var previous = root.resolve("generation-0.db")
        builder.build(previous, active)
        assertEquals(0, builder.reusableSessionState(previous)?.deltaDepth)

        // Exercise the entire policy-sized chain with real class/method/string
        // rows. Every generation must carry the atomically incremented depth,
        // remain logically equal to a clean merge, and keep all three external-
        // content FTS shadows aligned through repeated delete/insert cycles.
        for (generation in 1..MAX_CONSECUTIVE_SESSION_DELTAS) {
            val removed = active.removeAt(0)
            val added = shard("chain${generation + 8}")
            active += added
            val delta = root.resolve("generation-$generation.db")
            builder.buildDelta(previous, delta, setOf(removed.shardId), listOf(added))

            assertEquals(generation, builder.reusableSessionState(delta)?.deltaDepth)
            assertFtsIntegrity(delta)
            val full = root.resolve("generation-$generation-full.db")
            builder.build(full, active)
            assertEquals(0, builder.reusableSessionState(full)?.deltaDepth)
            assertFtsIntegrity(full)
            assertEquals(canonicalDump(full), canonicalDump(delta), "generation $generation")
            previous = delta
        }

        val rebased = root.resolve("rebased.db")
        builder.build(rebased, active)
        assertEquals(0, builder.reusableSessionState(rebased)?.deltaDepth)
        assertFtsIntegrity(rebased)
        assertEquals(canonicalDump(rebased), canonicalDump(previous))
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
    fun `delta shard keys use the full signed range and fail safely without a gap`() {
        val root = Files.createTempDirectory("research4jar-delta-shard-keys")
        fun emptyShard(id: String): SessionShard {
            val path = root.resolve("$id-shard.db")
            ShardWriter().write(path, "sha-$id", ExtractedJar(null))
            return SessionShard(id, path)
        }
        val alpha = emptyShard("a@2")
        val omega = emptyShard("z@2")
        val middle = emptyShard("m@2")
        val builder = SessionBuilder()

        val boundaryBase = root.resolve("boundary-base.db")
        builder.build(boundaryBase, listOf(alpha, omega))
        DriverManager.getConnection("jdbc:sqlite:$boundaryBase").use { connection ->
            connection.prepareStatement(
                "UPDATE session_shards SET shard_key = CASE shard_id " +
                    "WHEN 'a@2' THEN ? ELSE ? END",
            ).use { statement ->
                statement.setLong(1, Long.MIN_VALUE)
                statement.setLong(2, Long.MAX_VALUE)
                statement.executeUpdate()
            }
        }
        val boundaryDelta = root.resolve("boundary-delta.db")
        builder.buildDelta(boundaryBase, boundaryDelta, emptySet(), listOf(middle))
        DriverManager.getConnection("jdbc:sqlite:$boundaryDelta").use { connection ->
            val ordered = connection.createStatement().executeQuery(
                "SELECT shard_id, shard_key FROM session_shards ORDER BY shard_id",
            ).use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1) to rows.getLong(2))
                }
            }
            assertEquals(listOf("a@2", "m@2", "z@2"), ordered.map { it.first })
            assertTrue(ordered[0].second < ordered[1].second)
            assertTrue(ordered[1].second < ordered[2].second)
        }

        val exhaustedBase = root.resolve("exhausted-base.db")
        builder.build(exhaustedBase, listOf(alpha, omega))
        DriverManager.getConnection("jdbc:sqlite:$exhaustedBase").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("UPDATE session_shards SET shard_key = 0 WHERE shard_id = 'a@2'")
                statement.execute("UPDATE session_shards SET shard_key = 1 WHERE shard_id = 'z@2'")
            }
        }
        val exhaustedTarget = root.resolve("exhausted-target.db")
        assertFailsWith<ShardKeySpaceExhaustedException> {
            builder.buildDelta(exhaustedBase, exhaustedTarget, emptySet(), listOf(middle))
        }
        assertFalse(Files.exists(exhaustedTarget))
        assertNoTemporaries(root)

        val compactTailBase = root.resolve("compact-tail-base.db")
        builder.build(compactTailBase, listOf(alpha, middle))
        val finalTail = emptyShard("zz@2")
        val compactTailDelta = root.resolve("compact-tail-delta.db")
        builder.buildDelta(
            compactTailBase,
            compactTailDelta,
            emptySet(),
            listOf(omega, finalTail),
        )
        DriverManager.getConnection("jdbc:sqlite:$compactTailDelta").use { connection ->
            val keys = connection.createStatement().executeQuery(
                "SELECT shard_key FROM session_shards ORDER BY shard_id",
            ).use { rows ->
                buildList {
                    while (rows.next()) add(rows.getLong(1))
                }
            }
            assertEquals(listOf(0L, 65_536L, 131_072L, 196_608L), keys)
        }
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
        assertFtsIntegrity(deltaCopy)
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
    fun `pipeline allows eight deltas then fully rebases the ninth generation`() {
        val root = Files.createTempDirectory("research4jar-delta-depth")
        val jars = root.resolve("jars")
        val project = root.resolve("project")
        val home = root.resolve("home")
        Files.createDirectories(jars)
        assertEquals(8, MAX_CONSECUTIVE_SESSION_DELTAS)
        val active = (1..8).toMutableList()
        active.forEach { writeServiceJar(jars.resolve("lib$it.jar"), "com.example.Impl$it") }

        assertFalse(runIndex(jars, project, home).contains("delta-updating"))
        assertEquals(0, SessionBuilder().reusableSessionState(sessionOf(project))?.deltaDepth)

        for (generation in 1..MAX_CONSECUTIVE_SESSION_DELTAS + 1) {
            val removed = active.removeAt(0)
            val added = generation + 8
            Files.delete(jars.resolve("lib$removed.jar"))
            writeServiceJar(jars.resolve("lib$added.jar"), "com.example.Impl$added")
            active += added

            val stderr = runIndex(jars, project, home)
            val session = sessionOf(project)
            if (generation <= MAX_CONSECUTIVE_SESSION_DELTAS) {
                assertContains(stderr, "delta-updating session: +1 -1 shards")
                assertContains(
                    stderr,
                    "chain $generation/$MAX_CONSECUTIVE_SESSION_DELTAS",
                )
                assertEquals(generation, SessionBuilder().reusableSessionState(session)?.deltaDepth)
            } else {
                assertFalse(stderr.contains("delta-updating"), stderr)
                assertContains(stderr, "rebuilding session to compact 8 consecutive delta updates")
                assertEquals(0, SessionBuilder().reusableSessionState(session)?.deltaDepth)
            }
            assertEquals(8, SessionBuilder().reusableShardSet(session)?.size)
            assertFtsIntegrity(session)
        }

        // The ninth-generation policy build is a genuine from-scratch result:
        // deleting it makes an identical run rebuild through the no-base path,
        // and both logical data and FTS integrity agree exactly.
        val policyRebase = root.resolve("policy-rebase.db")
        val session = sessionOf(project)
        Files.copy(session, policyRebase)
        Files.delete(session)
        assertFalse(runIndex(jars, project, home).contains("delta-updating"))
        val forcedFull = sessionOf(project)
        assertEquals(0, SessionBuilder().reusableSessionState(forcedFull)?.deltaDepth)
        assertEquals(canonicalDump(forcedFull), canonicalDump(policyRebase))
        assertFtsIntegrity(policyRebase)
        assertFtsIntegrity(forcedFull)
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

    /**
     * Hand-crafts the corrupt-shard case: a method row whose class row is
     * missing, which [SessionBuilder.merge]'s LEFT JOIN turns into a NULL
     * unresolved session owner. ShardWriter can never emit one, so the row is spliced
     * into the finished shard file.
     */
    private fun injectOrphanMethod(shard: Path) {
        DriverManager.getConnection("jdbc:sqlite:${shard.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO methods(class_id, name, descriptor, return_fqn, modifiers) " +
                        "VALUES (4242, 'orphaned', '()V', NULL, 1)",
                )
            }
        }
    }

    /** integrity-check throws on any shadow/content divergence. */
    private fun assertFtsIntegrity(session: Path) {
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                for (fts in listOf("string_constants_fts", "classes_fts", "methods_fts")) {
                    statement.execute("INSERT INTO $fts($fts) VALUES('integrity-check')")
                }
            }
        }
    }

    private fun ftsMatches(session: Path, probe: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            sortedRows(connection, probe)
        }

    private fun scalar(session: Path, sql: String): Int =
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
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
                "SELECT c.fqn, c.kind, c.super_fqn, c.modifiers, c.is_abstract, c.source_file, " +
                "c.simple_name, c.package_name, ss.shard_id FROM classes c " +
                "JOIN session_shards ss ON ss.shard_key = c.source_shard_id",
            "class_interfaces" to
                "SELECT c.fqn, ci.interface_fqn, ss.shard_id " +
                "FROM class_interfaces ci JOIN classes c ON c.id = ci.class_id " +
                "JOIN session_shards ss ON ss.shard_key = ci.source_shard_id",
            "methods" to
                "SELECT c.fqn, m.name, m.descriptor, m.return_fqn, m.modifiers, m.owner_resolved, " +
                "ss.shard_id FROM methods m JOIN classes c ON c.id = m.class_id " +
                "JOIN session_shards ss ON ss.shard_key = m.source_shard_id",
            "annotations" to
                """
                SELECT a.target_kind,
                       CASE WHEN a.target_kind = 'class' THEN c.fqn
                            ELSE mc.fqn || '#' || m.name || m.descriptor END,
                       a.annotation_fqn, a.attributes, ss.shard_id
                FROM annotations a
                LEFT JOIN classes c ON a.target_kind = 'class' AND c.id = a.target_id
                LEFT JOIN methods m ON a.target_kind = 'method' AND m.id = a.target_id
                LEFT JOIN classes mc ON mc.id = m.class_id
                JOIN session_shards ss ON ss.shard_key = a.source_shard_id
                """.trimIndent(),
            "conditions" to
                """
                SELECT o.target_kind,
                       CASE WHEN o.target_kind = 'class' THEN c.fqn
                            ELSE mc.fqn || '#' || m.name || m.descriptor END,
                       o.type, o.ref_value, ss.shard_id
                FROM conditions o
                LEFT JOIN classes c ON o.target_kind = 'class' AND c.id = o.target_id
                LEFT JOIN methods m ON o.target_kind = 'method' AND m.id = o.target_id
                LEFT JOIN classes mc ON mc.id = m.class_id
                JOIN session_shards ss ON ss.shard_key = o.source_shard_id
                """.trimIndent(),
            "bean_definitions" to
                """
                SELECT b.config_fqn,
                       CASE WHEN b.method_id IS NULL THEN '<none>'
                            ELSE mc.fqn || '#' || m.name || m.descriptor END,
                       b.bean_type_fqn, b.bean_name, ss.shard_id
                FROM bean_definitions b
                LEFT JOIN methods m ON m.id = b.method_id
                LEFT JOIN classes mc ON mc.id = m.class_id
                JOIN session_shards ss ON ss.shard_key = b.source_shard_id
                """.trimIndent(),
            "string_constants" to
                """
                SELECT c.fqn,
                       CASE WHEN sc.method_id IS NULL THEN '<none>'
                            ELSE m.name || m.descriptor END,
                       sc.value, ss.shard_id
                FROM string_constants sc
                JOIN classes c ON c.id = sc.class_id
                LEFT JOIN methods m ON m.id = sc.method_id
                JOIN session_shards ss ON ss.shard_key = sc.source_shard_id
                """.trimIndent(),
            "config_properties" to
                "SELECT cp.prefix, cp.name, cp.type_fqn, cp.default_val, cp.description, " +
                "cp.source_fqn, ss.shard_id FROM config_properties cp " +
                "JOIN session_shards ss ON ss.shard_key = cp.source_shard_id",
            "spi_registrations" to
                "SELECT spi.mechanism, spi.key, spi.impl_fqn, ss.shard_id " +
                "FROM spi_registrations spi " +
                "JOIN session_shards ss ON ss.shard_key = spi.source_shard_id",
        )
    }
}
