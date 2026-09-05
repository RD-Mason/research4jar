package dev.research4jar.query

import dev.research4jar.runtime.OperationContext
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QueryCancellationTest {
    @Test
    fun `cancelling a long SQLite query releases the connection`() {
        val path = Files.createTempFile("r4j-query-cancel", ".db")
        DriverManager.getConnection("jdbc:sqlite:$path").use {
            it.createStatement().use { statement -> statement.execute("CREATE TABLE fixture (value INTEGER)") }
        }
        val context = OperationContext()
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val work = executor.submit<Int> {
                context.run {
                    Db.openReadOnly(path.toString(), immutable = true).use { connection ->
                        started.countDown()
                        connection.queryInt(
                            "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x + 1 FROM n WHERE x < 1000000000) SELECT sum(x) FROM n",
                            emptyList(),
                        )
                    }
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            context.cancel()
            assertFailsWith<ExecutionException> { work.get(5, TimeUnit.SECONDS) }
            dev.research4jar.runtime.SessionFileLease.acquireExclusive(path).close()
        } finally {
            context.cancel()
            executor.shutdownNow()
            Files.deleteIfExists(path)
        }
    }
}
