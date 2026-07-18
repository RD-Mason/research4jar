package dev.research4jar.query

import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DbTest {
    @Test
    fun `EROFS sidecar failure falls back to an unlocked read-only query`() {
        val database = database()
        val attempted = AtomicBoolean(false)

        Db.openReadOnly(database.toString(), immutable = true) { session ->
            attempted.set(true)
            throw FileSystemException(
                session.resolveSibling(".${session.fileName}.lease").toString(),
                null,
                "Read-only file system",
            )
        }.use { connection ->
            assertEquals(42, connection.queryInt("SELECT value FROM sample", emptyList()))
        }

        assertTrue(attempted.get())
    }

    @Test
    fun `ordinary sidecar filesystem failures are not swallowed`() {
        val database = database()
        val failure = FileSystemException(
            database.resolveSibling(".${database.fileName}.lease").toString(),
            null,
            "Input/output error",
        )

        val thrown = assertFailsWith<FileSystemException> {
            Db.openReadOnly(database.toString(), immutable = true) { throw failure }
        }

        assertSame(failure, thrown)
    }

    private fun database(): Path {
        val database = Files.createTempDirectory("r4j-db-lease").resolve("session.db")
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE sample(value INTEGER NOT NULL)")
                statement.execute("INSERT INTO sample(value) VALUES (42)")
            }
        }
        return database
    }
}
