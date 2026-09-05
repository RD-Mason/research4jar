package dev.research4jar.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.research4jar.runtime.OperationContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpTransportTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `ping and reads stay responsive during indexing and cancellation suppresses the result`() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        Session { request ->
            if (request["params"]?.get("name")?.asText() == "index_project") {
                started.countDown()
                try { CountDownLatch(1).await(30, TimeUnit.SECONDS) } finally { stopped.countDown() }
            }
            reply(request)
        }.use { session ->
            session.call(1, "index_project")
            assertTrue(started.await(5, TimeUnit.SECONDS))
            session.send("""{"jsonrpc":"2.0","id":2,"method":"ping"}""")
            assertEquals(2, session.receive()["id"].asInt())
            session.call(3, "find_class")
            assertEquals(3, session.receive()["id"].asInt())
            session.cancel(1)
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            session.send("""{"jsonrpc":"2.0","id":4,"method":"ping"}""")
            assertEquals(4, session.receive()["id"].asInt(), "cancelled index must not publish a response")
        }
    }

    @Test
    fun `bounded queues reject overload and a cancelled queued call frees its slot`() {
        val started = CountDownLatch(1)
        val executed = mutableListOf<Int>()
        Session(queueCapacity = 1) { request ->
            val id = request["id"].asInt()
            synchronized(executed) { executed.add(id) }
            if (id == 1) {
                started.countDown()
                CountDownLatch(1).await(30, TimeUnit.SECONDS)
            }
            reply(request)
        }.use { session ->
            session.call(1, "index_project")
            assertTrue(started.await(5, TimeUnit.SECONDS))
            session.call(2, "index_project")
            session.call(3, "index_project")
            val rejected = session.receive()
            assertEquals(3, rejected["id"].asInt())
            assertTrue(rejected["result"]["isError"].asBoolean())
            session.cancel(2)
            session.call(4, "index_project")
            session.cancel(1)
            assertEquals(4, session.receive()["id"].asInt())
            assertFalse(synchronized(executed) { 2 in executed })
        }
    }

    @Test
    fun `progress is opt in correlated and stops before the final response`() {
        Session { request -> OperationContext.progress("phase"); reply(request) }.use { session ->
            session.call(1, "find_class")
            assertEquals(1, session.receive()["id"].asInt())
            session.send("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"find_class","_meta":{"progressToken":"token"}}}""")
            var last = 0L
            while (true) {
                val response = session.receive()
                if (response.has("id")) {
                    assertEquals(2, response["id"].asInt())
                    break
                }
                assertEquals("notifications/progress", response["method"].asText())
                assertEquals("token", response["params"]["progressToken"].asText())
                val current = response["params"]["progress"].asLong()
                assertTrue(current > last)
                last = current
            }
            assertTrue(last > 0)
        }
    }

    private fun reply(request: JsonNode): Map<String, Any?> =
        mapOf("jsonrpc" to "2.0", "id" to request["id"], "result" to emptyMap<String, Any>())

    private inner class Session(
        queueCapacity: Int = 32,
        handle: (JsonNode) -> Map<String, Any?>?,
    ) : AutoCloseable {
        private val input = PipedInputStream(64 * 1024)
        private val client = PipedOutputStream(input)
        private val replies = LinkedBlockingQueue<JsonNode>()
        private val server = thread(isDaemon = true, name = "mcp-test") {
            val output = object : OutputStream() {
                private val buffer = ByteArrayOutputStream()
                override fun write(value: Int) {
                    if (value == 10) {
                        replies.put(mapper.readTree(buffer.toByteArray()))
                        buffer.reset()
                    } else buffer.write(value)
                }
            }
            McpTransport(mapper, handle, queueCapacity, drainTimeoutMs = 2000).serve(input, output)
        }

        fun send(line: String) { client.write((line + "\n").toByteArray()); client.flush() }
        fun call(id: Int, name: String) = send("""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name"}}""")
        fun cancel(id: Int) = send("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":$id}}""")
        fun receive(): JsonNode = replies.poll(5, TimeUnit.SECONDS) ?: error("MCP response timed out")
        override fun close() {
            client.close()
            server.join(5000)
            assertFalse(server.isAlive, "MCP transport did not shut down")
        }
    }
}
