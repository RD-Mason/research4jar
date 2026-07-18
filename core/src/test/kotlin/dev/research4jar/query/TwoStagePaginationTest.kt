package dev.research4jar.query

import dev.research4jar.indexer.store.SessionBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the two-stage search through the real query functions against a
 * real session database: a full page of index-backed matches serves from the
 * fast path, an underfilled page falls back to the legacy contains scan, and
 * the ranking tiers keep both paths page-consistent.
 */
class TwoStagePaginationTest {
    private fun buildSession(): Path {
        val dir = Files.createTempDirectory("r4j-two-stage")
        val session = dir.resolve("session.db")
        // Zero shards still creates the full session layout: tables, the
        // search_symbols view, indexes, and the version stamp.
        SessionBuilder().build(session, emptyList())
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO classes(fqn, kind, super_fqn, modifiers, is_abstract, source_file,
                                    simple_name, package_name, source_shard_id)
                VALUES (?, 'class', NULL, 1, 0, NULL, ?, ?, 'shard-a')
                """.trimIndent(),
            ).use { insert ->
                fun add(fqn: String) {
                    val (packageName, simpleName) = splitFqn(fqn)
                    insert.setString(1, fqn)
                    insert.setString(2, simpleName)
                    insert.setString(3, packageName)
                    insert.executeUpdate()
                }
                // Exact simple-name match (tier 90) must rank first.
                add("com.example.Widget")
                // 29 prefix matches (tier 82), Widget01..Widget29.
                (1..29).forEach { add("com.example.Widget%02d".format(it)) }
                // 3 contains-only matches (tier 50): 'Widget' is inside, not a prefix.
                add("com.acme.SuperWidgetize")
                add("com.acme.AntiWidgetFactory")
                add("com.acme.NonWidgetish")
            }
            // Rows were inserted directly (not through the shard merge), so
            // re-run the classes_fts rebuild and the search-domain fill the
            // merge commit performs — the trigram-served contains fallback
            // reads these structures.
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO classes_fts(classes_fts) VALUES('rebuild')")
            }
            SessionBuilder().syncSearchDomains(connection, methodIdOffset = 0, stringIdOffset = 0)
        }
        return session
    }

    private fun pointer(session: Path) = ProjectPointerData(
        schemaVersion = 2,
        extractorVersion = 2,
        classpathFingerprint = "test",
        sessionDbPath = session.toString(),
    )

    @Test
    fun `full first page is served by indexed tiers with has_more`() {
        val session = buildSession()
        val response = findClass(pointer(session), "", "Widget", page = 1, pageSize = 20)
        assertEquals(20, response.results.size)
        assertTrue(response.hasMore)
        assertEquals("simple_name", response.results.first().matchReason)
        assertEquals("com.example.Widget", response.results.first().fqn)
        // Everything else on page 1 is the prefix tier — no contains rows can
        // outrank them, so the fast page equals the legacy page.
        response.results.drop(1).forEach {
            assertEquals("simple_prefix", it.matchReason)
            assertEquals(82, it.score)
        }
    }

    @Test
    fun `underfilled page falls back and appends the contains tier`() {
        val session = buildSession()
        val response = findClass(pointer(session), "", "Widget", page = 2, pageSize = 20)
        // Page 2: remaining 10 prefix rows, then the 3 contains-only rows.
        assertEquals(13, response.results.size)
        assertFalse(response.hasMore)
        response.results.take(10).forEach { assertEquals("simple_prefix", it.matchReason) }
        response.results.drop(10).forEach {
            assertEquals("contains", it.matchReason)
            assertEquals(50, it.score)
        }
    }

    @Test
    fun `search-symbol ranks class tiers above the contains tier`() {
        val session = buildSession()
        val response = searchSymbol(pointer(session), "", "Widget", page = 1, pageSize = 40)
        assertEquals(33, response.results.size)
        assertEquals("simple_name", response.results.first().matchReason)
        val reasons = response.results.map { it.matchReason }
        // No contains row may appear before the last indexed-tier row.
        val lastIndexed = reasons.indexOfLast { it == "simple_prefix" || it == "simple_name" }
        val firstContains = reasons.indexOfFirst { it.endsWith("_contains") }
        assertTrue(firstContains > lastIndexed)
        val scores = response.results.map { it.score }
        assertEquals(scores.sortedDescending(), scores, "scores must be non-increasing")
    }

    @Test
    fun `case-mismatched prefix terms surface through the contains tier`() {
        val session = buildSession()
        val response = findClass(pointer(session), "", "widget", page = 1, pageSize = 40)
        // Ranges are case-sensitive; default LIKE is not. The rows still
        // surface, ranked in the contains tier.
        assertTrue(response.results.isNotEmpty())
        response.results.forEach { assertEquals("contains", it.matchReason) }
    }
}
