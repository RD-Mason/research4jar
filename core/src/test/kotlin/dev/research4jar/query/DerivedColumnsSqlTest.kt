package dev.research4jar.query

import dev.research4jar.indexer.store.PACKAGE_NAME_EXPR
import dev.research4jar.indexer.store.SIMPLE_NAME_EXPR
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves the derived-column SQL (the rtrim/substr trick) is exact
 * lastIndexOf('.') semantics by running the production expressions from
 * [SIMPLE_NAME_EXPR]/[PACKAGE_NAME_EXPR] against edge-case FQNs in the same
 * INSERT...SELECT shape the merge uses, and comparing with the Kotlin
 * reference split. Also covers the LEFT JOIN symbol computation, including
 * the orphaned-method case (missing class row → NULL symbol).
 */
class DerivedColumnsSqlTest {
    private val edgeCases = listOf(
        "NoDots",
        "a.b.C",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "a.b\$Inner",
        "a.a.a",
        "ab.aab",
        ".leadingDot",
        "trailing.",
        "...",
        "single.x",
        "包名.类名",
        "mixed.包.Class",
    )

    @Test
    fun `sql split matches lastIndexOf semantics for every edge case`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE shard_classes (id INTEGER PRIMARY KEY, fqn TEXT NOT NULL)",
                )
                statement.execute(
                    "CREATE TABLE shard_methods (id INTEGER PRIMARY KEY, class_id INTEGER NOT NULL, name TEXT NOT NULL)",
                )
                statement.execute(
                    "CREATE TABLE classes (id INTEGER PRIMARY KEY, fqn TEXT NOT NULL, simple_name TEXT, package_name TEXT)",
                )
                statement.execute(
                    "CREATE TABLE methods (id INTEGER PRIMARY KEY, class_id INTEGER NOT NULL, name TEXT NOT NULL, symbol TEXT)",
                )
            }
            connection.prepareStatement("INSERT INTO shard_classes(id, fqn) VALUES (?, ?)").use { insert ->
                edgeCases.forEachIndexed { index, fqn ->
                    insert.setInt(1, index + 1)
                    insert.setString(2, fqn)
                    insert.executeUpdate()
                }
            }
            connection.prepareStatement(
                "INSERT INTO shard_methods(id, class_id, name) VALUES (?, ?, ?)",
            ).use { insert ->
                edgeCases.forEachIndexed { index, _ ->
                    insert.setInt(1, index + 1)
                    insert.setInt(2, index + 1)
                    insert.setString(3, "method$index")
                    insert.executeUpdate()
                }
                // Orphaned method: class_id points at no class row. The merge's
                // LEFT JOIN must keep the row and leave symbol NULL, exactly
                // like the pre-M6 UPDATE's scalar subquery did.
                insert.setInt(1, edgeCases.size + 1)
                insert.setInt(2, 9999)
                insert.setString(3, "orphan")
                insert.executeUpdate()
            }

            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO classes(fqn, simple_name, package_name)
                    SELECT fqn, $SIMPLE_NAME_EXPR, $PACKAGE_NAME_EXPR
                    FROM shard_classes
                    ORDER BY id
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO methods(class_id, name, symbol)
                    SELECT m.class_id, m.name, c.fqn || '#' || m.name
                    FROM shard_methods m
                    LEFT JOIN shard_classes c ON c.id = m.class_id
                    ORDER BY m.id
                    """.trimIndent(),
                )
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT fqn, simple_name, package_name FROM classes ORDER BY id",
                ).use { rows ->
                    edgeCases.forEach { fqn ->
                        rows.next()
                        val (expectedPackage, expectedSimple) = splitFqn(fqn)
                        assertEquals(fqn, rows.getString(1))
                        assertEquals(expectedSimple, rows.getString(2), "simple_name for $fqn")
                        assertEquals(expectedPackage, rows.getString(3), "package_name for $fqn")
                    }
                }
                statement.executeQuery(
                    "SELECT symbol FROM methods ORDER BY id",
                ).use { rows ->
                    edgeCases.forEachIndexed { index, fqn ->
                        rows.next()
                        assertEquals("$fqn#method$index", rows.getString(1), "symbol for $fqn")
                    }
                    rows.next()
                    assertNull(rows.getString(1), "orphaned method must keep a NULL symbol")
                }
            }
        }
    }
}
