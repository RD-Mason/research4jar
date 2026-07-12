package dev.research4jar.query

import dev.research4jar.indexer.store.SessionBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the per-kind contains-tier cascade returns exactly the pages the
 * retired single-query SEARCH_SYMBOL_SQL (kept as the oracle) would return.
 * The session mixes all six symbol kinds with adversarial rows: orphaned
 * methods with NULL symbols, case variants (ranges are case-sensitive,
 * LIKE is not), CJK names (empty range upper bound), and LIKE wildcard
 * literals, across a battery of terms and pages.
 */
class SearchSymbolParityTest {
    private data class Row(
        val kind: String,
        val name: String?,
        val owner: String?,
        val detail: String?,
        val score: Int,
        val matchReason: String,
    )

    private fun buildSession(): Path {
        val dir = Files.createTempDirectory("r4j-parity")
        val session = dir.resolve("session.db")
        SessionBuilder().build(session, emptyList())
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.autoCommit = false
            val random = Random(42)
            val words = listOf(
                "Widget", "widget", "WIDGET", "Gadget", "Servlet", "Template",
                "RestTemplate", "Handler", "工具", "Wid%get", "Wid_get", "wid",
            )
            fun word() = words[random.nextInt(words.size)]
            fun exec(sql: String, vararg args: Any?) =
                connection.prepareStatement(sql).use { statement ->
                    args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                    statement.executeUpdate()
                }

            var classId = 0
            repeat(60) {
                classId++
                val simple = "${word()}${if (random.nextBoolean()) random.nextInt(10).toString() else ""}"
                val pkg = listOf("com.example", "org.acme", "io.测试").random(random)
                exec(
                    """INSERT INTO classes(id, fqn, kind, super_fqn, modifiers, is_abstract,
                       source_file, simple_name, package_name, source_shard_id)
                       VALUES (?, ?, 'class', NULL, 1, 0, NULL, ?, ?, ?)""",
                    classId, "$pkg.$simple", simple, pkg, "shard-${random.nextInt(3)}",
                )
            }
            // Term-free host class for the NULL-symbol methods below: rows
            // with a NULL name crash the response mapping in the cascade and
            // the oracle alike (real merges never produce them), so they must
            // exist — exercising the COALESCE in the exclusion predicates —
            // without ever matching a battery term.
            exec(
                """INSERT INTO classes(id, fqn, kind, super_fqn, modifiers, is_abstract,
                   source_file, simple_name, package_name, source_shard_id)
                   VALUES (999, 'qq.safe.Qz', 'class', NULL, 1, 0, NULL, 'Qz', 'qq.safe', 'shard-0')""",
            )
            var methodId = 0
            repeat(80) {
                methodId++
                val orphan = methodId % 10 == 0
                val owner = if (orphan) 999 else random.nextInt(1, classId + 1)
                val name = if (orphan) "qqq$methodId" else "do${word()}"
                val symbol = if (orphan) null else "com.example.C$owner#$name"
                exec(
                    """INSERT INTO methods(id, class_id, name, descriptor, return_fqn,
                       modifiers, symbol, source_shard_id)
                       VALUES (?, ?, ?, '()V', NULL, 1, ?, ?)""",
                    methodId, owner, name, symbol, "shard-${random.nextInt(3)}",
                )
            }
            repeat(40) {
                val onClass = random.nextBoolean()
                exec(
                    """INSERT INTO annotations(target_kind, target_id, annotation_fqn,
                       attributes, source_shard_id)
                       VALUES (?, ?, ?, ?, ?)""",
                    if (onClass) "class" else "method",
                    if (onClass) random.nextInt(1, classId + 1) else random.nextInt(1, methodId + 1),
                    "org.anno.${word()}Marker",
                    if (random.nextBoolean()) """{"value":"${word()}"}""" else null,
                    "shard-${random.nextInt(3)}",
                )
            }
            repeat(20) {
                exec(
                    """INSERT INTO spi_registrations(mechanism, key, impl_fqn, source_shard_id)
                       VALUES ('services', ?, ?, ?)""",
                    "org.spi.${word()}Provider", "com.impl.${word()}Impl", "shard-${random.nextInt(3)}",
                )
            }
            repeat(20) {
                exec(
                    """INSERT INTO config_properties(prefix, name, type_fqn, default_val,
                       description, source_fqn, source_shard_id)
                       VALUES (?, ?, ?, NULL, NULL, ?, ?)""",
                    "app", "app.${word().lowercase()}.enabled", "java.lang.Boolean",
                    "com.example.${word()}Properties", "shard-${random.nextInt(3)}",
                )
            }
            repeat(50) {
                exec(
                    """INSERT INTO string_constants(class_id, method_id, value, source_shard_id)
                       VALUES (?, ?, ?, ?)""",
                    random.nextInt(1, classId + 1),
                    if (random.nextBoolean()) random.nextInt(1, methodId + 1) else null,
                    "the ${word()} constant ${random.nextInt(5)}",
                    "shard-${random.nextInt(3)}",
                )
            }
            connection.commit()
        }
        return session
    }

    private fun oraclePage(
        session: Connection,
        term: String,
        page: Int,
        pageSize: Int,
    ): Pair<List<Row>, Boolean> {
        val rows = session.query(
            SEARCH_SYMBOL_SQL,
            matchArgs(term) + listOf(pageSize + 1, (page - 1) * pageSize),
        ) { result ->
            result.mapRows {
                Row(
                    kind = it.getString(1),
                    name = it.getString(2),
                    owner = it.getString(3),
                    detail = it.getString(4),
                    score = it.getInt(6),
                    matchReason = it.getString(7),
                )
            }
        }
        val hasMore = rows.size > pageSize
        return (if (hasMore) rows.subList(0, pageSize) else rows) to hasMore
    }

    @Test
    fun `cascade pages equal the single-query oracle for every term and page`() {
        val session = buildSession()
        val pointer = ProjectPointerData(
            schemaVersion = 2,
            extractorVersion = 2,
            classpathFingerprint = "test",
            sessionDbPath = session.toString(),
        )
        val terms = listOf(
            "Widget", "widget", "WIDGET", "wid", "doGadget", "Template",
            "com.example.Widget", "工具", "Wid\\%get", "%", "Provider",
            "app.widget.enabled", "constant", "zzz-no-match", "services",
            "class",
        )
        var comparisons = 0
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { oracle ->
            for (term in terms) {
                for (pageSize in listOf(5, 20)) {
                    var page = 1
                    while (page <= 6) {
                        val (expected, expectedHasMore) = oraclePage(oracle, term, page, pageSize)
                        val response = searchSymbol(pointer, "", term, page, pageSize)
                        val actual = response.results.map {
                            Row(it.kind, it.name, it.owner, it.detail, it.score, it.matchReason)
                        }
                        assertEquals(expected, actual, "term=$term page=$page pageSize=$pageSize")
                        assertEquals(
                            expectedHasMore,
                            response.hasMore,
                            "hasMore for term=$term page=$page pageSize=$pageSize",
                        )
                        comparisons++
                        if (!expectedHasMore) break
                        page++
                    }
                }
            }
        }
        // Guard against the battery silently degenerating to empty results.
        assertTrue(comparisons > 60, "ran $comparisons page comparisons")
    }
}
