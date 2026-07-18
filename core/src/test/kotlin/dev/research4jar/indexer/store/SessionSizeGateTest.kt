package dev.research4jar.indexer.store

import dev.research4jar.indexer.extract.ExtractedClass
import dev.research4jar.indexer.extract.ExtractedJar
import dev.research4jar.indexer.extract.ExtractedMethod
import dev.research4jar.indexer.extract.ExtractedStringConstant
import dev.research4jar.query.ProjectPointerData
import dev.research4jar.query.findMethod
import dev.research4jar.query.findString
import dev.research4jar.query.searchSymbol
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The below-gate half of the accelerator size gate: a session whose merged
 * method count stays under [SessionBuilder.ftsMinMethods] must build exactly
 * the legacy (pre-accelerator) shape — none of [GATED_SESSION_OBJECTS], the
 * never-gated string domain intact — validate as reusable, keep the delta
 * path green while skipping all accelerator maintenance, and serve correct
 * query results through the public API's per-tier legacy fallbacks. The
 * accelerated half (gate forced to 0) is covered by SessionDeltaTest,
 * StoreTest and the query parity batteries.
 */
class SessionSizeGateTest {
    private var savedFtsGate = SessionBuilder.ftsMinMethods

    @BeforeTest
    fun pinGateAboveFixtures() {
        savedFtsGate = SessionBuilder.ftsMinMethods
        SessionBuilder.ftsMinMethods = 1_000_000
    }

    @AfterTest
    fun restoreSizeGate() {
        SessionBuilder.ftsMinMethods = savedFtsGate
    }

    private fun jar(name: String): ExtractedJar {
        val config = "com.$name.Configuration"
        val helper = "com.$name.Helper"
        return ExtractedJar(
            coordinate = "com.example:$name:1.0",
            classes = listOf(
                ExtractedClass(config, "class", "java.lang.Object", 1, false, null, emptyList()),
                ExtractedClass(helper, "class", config, 17, true, null, emptyList()),
            ),
            methods = listOf(
                ExtractedMethod(config, "bean", "()Lcom/$name/Helper;", helper, 1),
                ExtractedMethod(helper, "run", "()V", null, 1),
            ),
            stringConstants = listOf(
                ExtractedStringConstant(helper, "run", "()V", "hello from $name"),
                ExtractedStringConstant(classFqn = config, value = "static $name"),
            ),
        )
    }

    private fun shard(root: Path, name: String): SessionShard {
        val path = root.resolve("$name-shard.db")
        ShardWriter().write(path, "sha-$name", jar(name))
        return SessionShard("sha-$name@t", path)
    }

    private fun pointer(session: Path) = ProjectPointerData(
        schemaVersion = 2,
        extractorVersion = 2,
        classpathFingerprint = "gate-test",
        sessionDbPath = session.toString(),
    )

    private fun schemaNames(session: Path): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM sqlite_schema").use { rows ->
                    buildSet {
                        while (rows.next()) add(rows.getString(1))
                    }
                }
            }
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

    private fun assertBelowGateShape(session: Path, label: String) {
        val names = schemaNames(session)
        for (gated in GATED_SESSION_OBJECTS) {
            assertTrue(gated !in names, "$label must not contain gated object $gated")
        }
        // The string domain is never gated: it replaced the old per-row
        // string FTS outright and is find-string's only substring path.
        for (
        required in listOf(
            "string_values", "string_value_packs", "string_values_fts",
            "idx_s_string_values_value",
        )
        ) {
            assertTrue(required in names, "$label must contain $required")
        }
        assertTrue(SessionBuilder().isReusable(session), "$label must validate as reusable")
    }

    private fun assertLegacyQueriesServe(session: Path, presentJars: List<String>, absentJar: String) {
        val pointer = pointer(session)
        // search-symbol contains tiers: every trigram rewrite fails on the
        // missing gated tables and the legacy per-kind scans answer — class
        // fqn contains, method symbol contains (through the owner), and
        // string owner contains.
        val symbols = searchSymbol(pointer, "", "nfigurati", 1, 20)
        assertEquals(
            presentJars.flatMap {
                listOf(
                    "class|com.$it.Configuration|class_contains",
                    "method|com.$it.Configuration#bean|method_contains",
                    "string|static $it|string_contains",
                )
            }.sorted(),
            symbols.results.map { "${it.kind}|${it.name}|${it.matchReason}" }.sorted(),
            "search-symbol contains must serve through the legacy tier scans",
        )

        // find-method contains tier: method_names_fts is absent, the legacy
        // methods scan answers.
        val methods = findMethod(pointer, "", "ean", 1, 20)
        assertEquals(
            presentJars.map { "com.$it.Configuration" }.sorted(),
            methods.results.map { it.classFqn }.sorted(),
            "find-method contains must serve through the legacy scan",
        )
        assertTrue(methods.results.all { it.name == "bean" && it.matchReason == "contains" })

        // find-string still rides the (ungated) packed string-value domain.
        val strings = findString(pointer, "", "hello from", 1, 20)
        assertEquals(
            presentJars.map { "hello from $it" }.sorted(),
            strings.results.map { it.value }.sorted(),
        )
        assertEquals(0, findString(pointer, "", "hello from $absentJar", 1, 20).total)
    }

    @Test
    fun `below-gate sessions build the legacy shape and deltas skip accelerator sync`() {
        val root = Files.createTempDirectory("research4jar-size-gate")
        val alpha = shard(root, "alpha")
        val bravo = shard(root, "bravo")
        val charlie = shard(root, "charlie")
        val delta = shard(root, "delta")

        val previous = root.resolve("previous-session.db")
        SessionBuilder().build(previous, listOf(alpha, bravo, charlie))
        assertBelowGateShape(previous, "full build")
        assertEquals(0, SessionBuilder().reusableSessionState(previous)?.deltaDepth)
        assertLegacyQueriesServe(previous, listOf("alpha", "bravo", "charlie"), absentJar = "delta")

        // The delta inherits the below-gate decision: it must stay green
        // while skipping every accelerator maintenance site (a classes_fts
        // mirror or method-domain sync would throw on the missing tables).
        val viaDelta = root.resolve("delta-session.db")
        SessionBuilder().buildDelta(
            previous = previous,
            target = viaDelta,
            removedShardIds = setOf(alpha.shardId),
            addedShards = listOf(delta),
        )
        assertBelowGateShape(viaDelta, "delta build")
        assertEquals(1, SessionBuilder().reusableSessionState(viaDelta)?.deltaDepth)
        assertEquals(
            setOf(bravo.shardId, charlie.shardId, delta.shardId),
            SessionBuilder().reusableShardSet(viaDelta),
        )
        // The added shard's rows are queryable, the removed shard's are gone;
        // the string domain kept its per-shard append (delta's values in the
        // domain and its FTS) while alpha's stale values just match nothing.
        assertLegacyQueriesServe(viaDelta, listOf("bravo", "charlie", "delta"), absentJar = "alpha")
        assertTrue(
            scalar(
                viaDelta,
                "SELECT COUNT(*) FROM string_values WHERE value = 'hello from delta'",
            ) == 1,
            "the delta must append the added shard's values to the string domain",
        )
        assertEquals(
            scalar(viaDelta, "SELECT COUNT(*) FROM string_value_packs"),
            scalar(viaDelta, "SELECT COUNT(*) FROM string_values_fts"),
            "string_values_fts must carry every pack after the delta",
        )
    }
}
