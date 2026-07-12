package dev.research4jar.query

import dev.research4jar.indexer.store.SessionBuilder
import dev.research4jar.indexer.store.SessionShard
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves find-string returns exactly the pages the legacy LIKE scan (kept as
 * the oracle) returns — same rows, order, and totals — whichever path serves
 * the term. The session is built through the real shard merge so the fts
 * rebuild in Stream.commit is what populates the trigram index, and the
 * values are adversarial: ASCII case variants, CJK, LIKE wildcard literals,
 * backslashes, embedded NULs, very long values, and values duplicated across
 * shards. Terms the trigram index cannot serve (fewer than three codepoints,
 * or containing % _ \) route to the legacy scan and run through the same
 * battery, as does the dropped-table fallback for pre-FTS sessions.
 */
class FindStringParityTest {
    private val sharedValues = listOf(
        "spring.application.name",
        "Spring Boot Application",
        "SPRING_PROFILES_ACTIVE",
        "sPrInGmIxEd",
        "配置包名字测试",
        "包名",
        "100% pure juice",
        "under_score_key",
        "back\\slash\\path",
        "%_\\",
        "wid",
        "ab",
        "a",
        "",
        "the needle " + "x".repeat(9000) + " haystack",
        "line\nbreak spring value",
        "nul\u0000spring tail",
        "trailing spaces   ",
    )

    private fun createShard(dir: Path, shardId: String, extraValues: List<String>): SessionShard {
        val path = dir.resolve("$shardId.db")
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                // The subset of the shard layout the session merge reads.
                statement.execute("CREATE TABLE config_properties (id INTEGER PRIMARY KEY, prefix TEXT, name TEXT NOT NULL, type_fqn TEXT, default_val TEXT, description TEXT, source_fqn TEXT)")
                statement.execute("CREATE TABLE spi_registrations (id INTEGER PRIMARY KEY, mechanism TEXT NOT NULL, key TEXT, impl_fqn TEXT NOT NULL)")
                statement.execute("CREATE TABLE classes (id INTEGER PRIMARY KEY, fqn TEXT NOT NULL, kind TEXT, super_fqn TEXT, modifiers INTEGER, is_abstract INTEGER, source_file TEXT)")
                statement.execute("CREATE TABLE class_interfaces (class_id INTEGER NOT NULL, interface_fqn TEXT NOT NULL)")
                statement.execute("CREATE TABLE methods (id INTEGER PRIMARY KEY, class_id INTEGER NOT NULL, name TEXT NOT NULL, descriptor TEXT NOT NULL, return_fqn TEXT, modifiers INTEGER)")
                statement.execute("CREATE TABLE annotations (id INTEGER PRIMARY KEY, target_kind TEXT NOT NULL, target_id INTEGER NOT NULL, annotation_fqn TEXT NOT NULL, attributes TEXT)")
                statement.execute("CREATE TABLE bean_definitions (id INTEGER PRIMARY KEY, config_fqn TEXT NOT NULL, method_id INTEGER, bean_type_fqn TEXT, bean_name TEXT)")
                statement.execute("CREATE TABLE conditions (id INTEGER PRIMARY KEY, target_kind TEXT NOT NULL, target_id INTEGER NOT NULL, type TEXT NOT NULL, ref_value TEXT)")
                statement.execute("CREATE TABLE string_constants (id INTEGER PRIMARY KEY, class_id INTEGER NOT NULL, method_id INTEGER, value TEXT NOT NULL)")
            }
            fun exec(sql: String, vararg args: Any?) =
                connection.prepareStatement(sql).use { statement ->
                    args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                    statement.executeUpdate()
                }
            // com.shared.Dup exists in both shards so identical (value, fqn)
            // pairs collide across shards and tie-break on source_shard_id.
            exec("INSERT INTO classes(id, fqn, kind, modifiers, is_abstract) VALUES (1, 'com.shared.Dup', 'class', 1, 0)")
            exec("INSERT INTO classes(id, fqn, kind, modifiers, is_abstract) VALUES (2, 'com.$shardId.Alpha', 'class', 1, 0)")
            exec("INSERT INTO classes(id, fqn, kind, modifiers, is_abstract) VALUES (3, 'com.$shardId.Beta', 'class', 1, 0)")
            exec("INSERT INTO methods(id, class_id, name, descriptor, modifiers) VALUES (1, 2, 'load', '()V', 1)")
            exec("INSERT INTO methods(id, class_id, name, descriptor, modifiers) VALUES (2, 3, 'save', '(I)V', 1)")
            var stringId = 0
            for ((index, value) in (sharedValues + extraValues).withIndex()) {
                // Cycle rows through class-level (NULL method) and both
                // methods; duplicate every third value inside the shard too.
                repeat(if (index % 3 == 0) 2 else 1) {
                    stringId++
                    exec(
                        "INSERT INTO string_constants(id, class_id, method_id, value) VALUES (?, ?, ?, ?)",
                        stringId,
                        index % 3 + 1,
                        when (stringId % 3) {
                            0 -> null
                            1 -> 1
                            else -> 2
                        },
                        value,
                    )
                }
            }
        }
        return SessionShard(shardId, path)
    }

    private fun buildSession(): Path {
        val dir = Files.createTempDirectory("r4j-string-parity")
        val session = dir.resolve("session.db")
        val shards = listOf(
            createShard(dir, "shard-a", listOf("only in a: Spring winter", "solo%percent_a")),
            createShard(dir, "shard-b", listOf("only in b: SPRING summer", "包名字 only b")),
        )
        SessionBuilder().build(session, shards)
        return session
    }

    private fun pointer(session: Path) = ProjectPointerData(
        schemaVersion = 2,
        extractorVersion = 2,
        classpathFingerprint = "test",
        sessionDbPath = session.toString(),
    )

    private val terms = listOf(
        "spring", "Spring", "SPRING", "sPrInG", "Ing", "ING_prof",
        "包名字", "配置包名字", "包名", "配",
        "100%", "under_score", "back\\slash", "%", "_", "\\", "%_\\", "e%e",
        "a", "ab", "abc", "wid", "juice",
        "needle", "haystack", "xxx",
        "spring tail", "nul", "line\nbreak", "ail",
        "trailing spaces", "spaces   ",
        "spring.application.name", "zzz-no-match", "",
        "e".repeat(300),
    )

    private fun oraclePage(
        oracle: Connection,
        term: String,
        page: Int,
        pageSize: Int,
    ): Pair<Int, List<StringConstant>> {
        val pattern = "%${escapeLike(term)}%"
        val total = oracle.queryInt(FIND_STRING_COUNT_SQL, listOf(pattern))
        val rows = oracle.query(
            FIND_STRING_SQL,
            listOf(pattern, pageSize, (page - 1) * pageSize),
        ) { result ->
            result.mapRows {
                val methodName = it.getString(3)
                val methodDescriptor = it.getString(4)
                StringConstant(
                    value = it.getString(1),
                    classFqn = it.getString(2),
                    method = if (methodName != null && methodDescriptor != null) {
                        methodName + methodDescriptor
                    } else {
                        null
                    },
                    sourceJar = it.getString(5),
                )
            }
        }
        return total to rows
    }

    private fun assertParity(session: Path, terms: List<String>) {
        var comparisons = 0
        var nonEmptyPages = 0
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { oracle ->
            for (term in terms) {
                for (pageSize in listOf(3, 20)) {
                    var page = 1
                    while (page <= 12) {
                        val (expectedTotal, expected) = oraclePage(oracle, term, page, pageSize)
                        val response = findString(pointer(session), "", term, page, pageSize)
                        assertEquals(
                            expected,
                            response.results,
                            "rows for term=${term.take(30)} page=$page pageSize=$pageSize",
                        )
                        assertEquals(
                            expectedTotal,
                            response.total,
                            "total for term=${term.take(30)} page=$page pageSize=$pageSize",
                        )
                        comparisons++
                        if (expected.isNotEmpty()) nonEmptyPages++
                        if (expected.size < pageSize) break
                        page++
                    }
                }
            }
        }
        // Guard against the battery silently degenerating to empty results.
        assertTrue(comparisons > 80, "ran $comparisons page comparisons")
        assertTrue(nonEmptyPages > 40, "saw $nonEmptyPages non-empty pages")
    }

    @Test
    fun `fts path pages equal the legacy scan for every term and page`() {
        val session = buildSession()
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            // The commit-time rebuild must have indexed every merged row.
            val fts = connection.queryInt("SELECT COUNT(*) FROM string_constants_fts", emptyList())
            val raw = connection.queryInt("SELECT COUNT(*) FROM string_constants", emptyList())
            assertEquals(raw, fts, "fts rebuild must cover the merged rows")
            assertTrue(raw > 40, "merged $raw string rows")
            // The FTS SQL must execute and hit rows on its own: findString's
            // fallback would otherwise let a broken FTS path pass this whole
            // battery through the legacy scan.
            val ftsHits = connection.queryInt(FIND_STRING_FTS_COUNT_SQL, listOf("%spring%"))
            val oracleHits = connection.queryInt(FIND_STRING_COUNT_SQL, listOf("%spring%"))
            assertEquals(oracleHits, ftsHits, "fts count for the spring probe")
            assertTrue(ftsHits > 0, "the spring probe must match rows")
            connection.query(
                FIND_STRING_FTS_SQL,
                listOf("%spring%", 100, 0),
            ) { result -> assertTrue(result.mapRows { it.getString(1) }.isNotEmpty()) }
        }
        assertParity(session, terms)
    }

    @Test
    fun `sessions without the fts table fall back to the legacy scan`() {
        val session = buildSession()
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.execute("DROP TABLE string_constants_fts") }
        }
        // Trigram-eligible terms only: these are the ones that would hit the
        // missing table.
        assertParity(session, terms.filter(::trigramSearchable))
    }

    @Test
    fun `term routing matches the trigram contract`() {
        assertTrue(trigramSearchable("abc"))
        assertTrue(trigramSearchable("包名字"))
        assertTrue(trigramSearchable("spring tail"))
        assertTrue(!trigramSearchable("ab"))
        assertTrue(!trigramSearchable("包名"))
        assertTrue(!trigramSearchable(""))
        assertTrue(!trigramSearchable("100%"))
        assertTrue(!trigramSearchable("under_score"))
        assertTrue(!trigramSearchable("back\\slash"))
    }
}
