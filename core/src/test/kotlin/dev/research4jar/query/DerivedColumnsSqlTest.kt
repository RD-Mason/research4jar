package dev.research4jar.query

import dev.research4jar.indexer.store.DERIVED_COLUMN_UPDATES
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the set-based derived-column SQL (the rtrim/substr trick) is exact
 * lastIndexOf('.') semantics by running the production statements from
 * [DERIVED_COLUMN_UPDATES] against edge-case FQNs and comparing with the
 * Kotlin reference split.
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
                    "CREATE TABLE classes (id INTEGER PRIMARY KEY, fqn TEXT NOT NULL, simple_name TEXT, package_name TEXT)",
                )
                statement.execute(
                    "CREATE TABLE methods (id INTEGER PRIMARY KEY, class_id INTEGER NOT NULL, name TEXT NOT NULL, symbol TEXT)",
                )
            }
            connection.prepareStatement("INSERT INTO classes(id, fqn) VALUES (?, ?)").use { insert ->
                edgeCases.forEachIndexed { index, fqn ->
                    insert.setInt(1, index + 1)
                    insert.setString(2, fqn)
                    insert.executeUpdate()
                }
            }
            connection.prepareStatement(
                "INSERT INTO methods(id, class_id, name) VALUES (?, ?, ?)",
            ).use { insert ->
                edgeCases.forEachIndexed { index, _ ->
                    insert.setInt(1, index + 1)
                    insert.setInt(2, index + 1)
                    insert.setString(3, "method$index")
                    insert.executeUpdate()
                }
            }

            connection.createStatement().use { statement ->
                DERIVED_COLUMN_UPDATES.forEach { statement.executeUpdate(it) }
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
                    "SELECT m.symbol, c.fqn, m.name FROM methods m JOIN classes c ON c.id = m.class_id ORDER BY m.id",
                ).use { rows ->
                    edgeCases.forEachIndexed { index, fqn ->
                        rows.next()
                        assertEquals("$fqn#method$index", rows.getString(1), "symbol for $fqn")
                    }
                }
            }
        }
    }
}
