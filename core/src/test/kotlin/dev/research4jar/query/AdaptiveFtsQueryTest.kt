package dev.research4jar.query

import dev.research4jar.indexer.store.SessionBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Dense-postings routing must change only the plan, never pages or ordering. */
class AdaptiveFtsQueryTest {
    private data class ClassRow(
        val fqn: String,
        val simple: String?,
        val pkg: String?,
        val kind: String?,
        val superFqn: String?,
        val shard: String,
        val score: Int,
        val reason: String,
    )

    private data class MethodRow(
        val owner: String,
        val name: String,
        val descriptor: String,
        val returnFqn: String?,
        val modifiers: Int,
        val shard: String,
        val score: Int,
        val reason: String,
    )

    private data class SearchRow(
        val kind: String,
        val name: String?,
        val owner: String?,
        val detail: String?,
        val shard: String,
        val score: Int,
        val reason: String,
    )

    private fun stringOracle(
        db: Connection,
        term: String,
        page: Int,
        pageSize: Int,
    ): Pair<Int, List<StringConstant>> {
        val pattern = "%${escapeLike(term)}%"
        val total = db.queryInt(FIND_STRING_COUNT_SQL, listOf(pattern))
        val rows = db.query(
            FIND_STRING_SQL,
            listOf(pattern, pageSize, (page - 1) * pageSize),
        ) { result ->
            result.mapRows {
                val methodName = it.getString(3)
                val descriptor = it.getString(4)
                StringConstant(
                    value = it.getString(1),
                    classFqn = it.getString(2),
                    method = if (methodName == null || descriptor == null) null else methodName + descriptor,
                    sourceJar = it.getString(5),
                )
            }
        }
        return total to rows
    }

    private fun buildDenseSession(): Path {
        val session = Files.createTempDirectory("r4j-dense-fts").resolve("session.db")
        SessionBuilder().build(session, emptyList())
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { db ->
            db.autoCommit = false
            db.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    WITH RECURSIVE seq(x) AS (
                      VALUES(1) UNION ALL SELECT x + 1 FROM seq WHERE x < 20000
                    )
                    INSERT INTO classes(
                      id, fqn, kind, super_fqn, modifiers, is_abstract, source_file,
                      simple_name, package_name, source_shard_id
                    )
                    SELECT x, printf('com.example.DenseClass%05dCommonMidTail', x),
                           'class', NULL, 1, 0, NULL,
                           printf('DenseClass%05dCommonMidTail', x), 'com.example', 's'
                    FROM seq
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    WITH RECURSIVE seq(x) AS (
                      VALUES(1) UNION ALL SELECT x + 1 FROM seq WHERE x < 20000
                    )
                    INSERT INTO methods(
                      id, class_id, name, descriptor, return_fqn, modifiers, owner_resolved,
                      source_shard_id
                    )
                    SELECT x, x, printf('runMethodMid%05dTail', x), '()V', NULL, 1,
                           1,
                           's'
                    FROM seq
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    WITH RECURSIVE seq(x) AS (
                      VALUES(1) UNION ALL SELECT x + 1 FROM seq WHERE x < 20000
                    )
                    INSERT INTO string_constants(id, class_id, method_id, value, source_shard_id)
                    SELECT x, x, x, printf('payload StringMid %05d tail', x), 's'
                    FROM seq
                    """.trimIndent(),
                )
                statement.execute("INSERT INTO classes_fts(classes_fts) VALUES('rebuild')")
                statement.execute("INSERT INTO methods_fts(methods_fts) VALUES('rebuild')")
                statement.execute(
                    "INSERT INTO string_constants_fts(string_constants_fts) VALUES('rebuild')",
                )
            }
            db.commit()
            db.autoCommit = true
            db.createStatement().use { statement ->
                statement.execute("PRAGMA analysis_limit=400")
                statement.execute("ANALYZE")
            }
        }
        return session
    }

    private fun pointer(session: Path) = ProjectPointerData(
        schemaVersion = 2,
        extractorVersion = 2,
        classpathFingerprint = "dense-test",
        sessionDbPath = session.toString(),
    )

    private fun classOracle(
        db: Connection,
        term: String,
        page: Int,
        pageSize: Int,
    ): Pair<List<ClassRow>, Boolean> {
        val rows = db.query(
            CLASS_SEARCH_SQL,
            matchArgs(term) + listOf(pageSize + 1, (page - 1) * pageSize),
        ) { result ->
            result.mapRows {
                ClassRow(
                    it.getString(1), it.getString(2), it.getString(3), it.getString(4),
                    it.getString(5), it.getString(6), it.getInt(7), it.getString(8),
                )
            }
        }
        val hasMore = rows.size > pageSize
        return (if (hasMore) rows.take(pageSize) else rows) to hasMore
    }

    private fun methodOracle(
        db: Connection,
        term: String,
        page: Int,
        pageSize: Int,
    ): Pair<List<MethodRow>, Boolean> {
        val rows = db.query(
            METHOD_SEARCH_SQL,
            methodMatchArgs(term) + listOf(pageSize + 1, (page - 1) * pageSize),
        ) { result ->
            result.mapRows {
                MethodRow(
                    it.getString(1), it.getString(2), it.getString(3), it.getString(4),
                    it.getInt(5), it.getString(6), it.getInt(7), it.getString(8),
                )
            }
        }
        val hasMore = rows.size > pageSize
        return (if (hasMore) rows.take(pageSize) else rows) to hasMore
    }

    private fun searchOracle(
        db: Connection,
        term: String,
        page: Int,
        pageSize: Int,
    ): Pair<List<SearchRow>, Boolean> {
        val rows = db.query(
            SEARCH_SYMBOL_SQL,
            matchArgs(term) + listOf(pageSize + 1, (page - 1) * pageSize),
        ) { result ->
            result.mapRows {
                SearchRow(
                    it.getString(1), it.getString(2), it.getString(3), it.getString(4),
                    it.getString(5), it.getInt(6), it.getString(7),
                )
            }
        }
        val hasMore = rows.size > pageSize
        return (if (hasMore) rows.take(pageSize) else rows) to hasMore
    }

    @Test
    fun `dense postings select scans while every page stays oracle-identical`() {
        val session = buildDenseSession()
        val pointer = pointer(session)
        DriverManager.getConnection("jdbc:sqlite:${session.toAbsolutePath()}").use { db ->
            assertTrue(preferLegacyForDenseFts(db, classFtsDensityPlan("CommonMid")))
            assertTrue(preferLegacyForDenseFts(db, methodFtsDensityPlan("MethodMid")))
            assertTrue(preferLegacyForDenseFts(db, symbolClassFtsDensityPlan("CommonMid")))
            assertTrue(preferLegacyForDenseFts(db, symbolMethodFtsDensityPlan("MethodMid")))
            assertTrue(preferLegacyForDenseFts(db, symbolStringFtsDensityPlan("StringMid")))
            assertTrue(preferLegacyForDenseFts(db, findStringFtsDensityPlan("StringMid")))
            assertFalse(preferLegacyForDenseFts(db, classFtsDensityPlan("no-such-class")))
            assertFalse(preferLegacyForDenseFts(db, findStringFtsDensityPlan("no-such-string")))

            // Two individually sparse arms whose aggregate postings work is
            // dense must still select the scan.
            assertTrue(
                preferLegacyForDenseFts(
                    db,
                    FtsDensityPlan(
                        populationTable = "methods",
                        populationIndex = "idx_s_methods_name",
                        probes = listOf(
                            FtsDensityProbe(
                                "SELECT id FROM methods WHERE id BETWEEN 1 AND 3000 LIMIT ?",
                                emptyList(),
                            ),
                            FtsDensityProbe(
                                "SELECT id FROM methods WHERE id BETWEEN 3001 AND 6000 LIMIT ?",
                                emptyList(),
                            ),
                        ),
                    ),
                ),
            )

            val pageSize = 7
            for (page in listOf(1, 2, 100, 2858)) {
                val (expectedClasses, classHasMore) = classOracle(db, "CommonMid", page, pageSize)
                val classes = findClass(pointer, "", "CommonMid", page, pageSize)
                assertEquals(
                    expectedClasses,
                    classes.results.map {
                        ClassRow(
                            it.fqn, it.simpleName, it.packageName, it.kind, it.superFqn,
                            it.sourceJar, it.score, it.matchReason,
                        )
                    },
                    "find-class page=$page",
                )
                assertEquals(classHasMore, classes.hasMore)

                val (expectedMethods, methodHasMore) = methodOracle(db, "MethodMid", page, pageSize)
                val methods = findMethod(pointer, "", "MethodMid", page, pageSize)
                assertEquals(
                    expectedMethods,
                    methods.results.map {
                        MethodRow(
                            it.classFqn, it.name, it.descriptor, it.returnFqn, it.modifiers,
                            it.sourceJar, it.score, it.matchReason,
                        )
                    },
                    "find-method page=$page",
                )
                assertEquals(methodHasMore, methods.hasMore)
            }

            for (
                term in listOf(
                    "CommonMid", "MethodMid", "StringMid", "runMethodMid", "payload StringMid",
                )
            ) {
                for (page in listOf(1, 2, 100, 2858)) {
                    val (expected, expectedHasMore) = searchOracle(db, term, page, pageSize)
                    val (actualRows, actualHasMore) = searchSymbolRows(db, term, page, pageSize)
                    assertEquals(
                        expected,
                        actualRows.map {
                            SearchRow(
                                it.kind, it.name, it.owner, it.detail, it.shardId,
                                it.score, it.matchReason,
                            )
                        },
                        "search-symbol term=$term page=$page",
                    )
                    assertEquals(expectedHasMore, actualHasMore)
                }
            }

            for (page in listOf(1, 2, 100, 2858)) {
                val (expectedTotal, expectedRows) = stringOracle(db, "StringMid", page, pageSize)
                val actual = findString(pointer, "", "StringMid", page, pageSize)
                assertEquals(expectedTotal, actual.total, "find-string total page=$page")
                assertEquals(expectedRows, actual.results, "find-string rows page=$page")
            }
            val rare = findString(pointer, "", "no-such-string", 1, pageSize)
            assertEquals(0, rare.total)
            assertTrue(rare.results.isEmpty())

            // Defensive sessions without usable stats estimate population via
            // MAX(id), and pre-v6 sessions skip the missing FTS query entirely.
            db.createStatement().use { statement ->
                statement.executeUpdate(
                    "DELETE FROM sqlite_stat1 WHERE idx = 'idx_s_classes_fqn'",
                )
            }
            assertTrue(preferLegacyForDenseFts(db, classFtsDensityPlan("CommonMid")))
            db.createStatement().use { statement -> statement.execute("DROP TABLE classes_fts") }
            assertTrue(preferLegacyForDenseFts(db, classFtsDensityPlan("CommonMid")))
        }
    }
}
