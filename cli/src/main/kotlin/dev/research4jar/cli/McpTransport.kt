package dev.research4jar.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.research4jar.runtime.OperationContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps the stdio reader available for ping/cancellation while bounded workers execute tools. */
internal class McpTransport(
    private val mapper: ObjectMapper,
    private val handle: (JsonNode) -> Map<String, Any?>?,
    private val queueCapacity: Int = 32,
    private val drainTimeoutMs: Long = TimeUnit.MINUTES.toMillis(31),
) {
    fun serve(stdin: InputStream, stdout: OutputStream) {
        val outputLock = Any()
        val closed = AtomicBoolean(false)
        val requests = ConcurrentHashMap<JsonNode, Request>()
        fun send(reply: Map<String, Any?>) {
            if (!closed.get()) {
                // Encode under the same lock as the write: responses/progress never interleave.
                stdout.write(mapper.writeValueAsBytes(reply))
                stdout.write('\n'.code)
                stdout.flush()
            }
        }
        fun pool(name: String, workers: Int) = ThreadPoolExecutor(
            workers, workers, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueCapacity),
            { task -> Thread(task, "research4jar-mcp-$name").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        val reads = pool("query", 2)
        val sources = pool("source", 1)
        val builds = pool("build", 1)
        val pools = listOf(reads, sources, builds)
        var eof = false
        try {
            val reader = stdin.bufferedReader()
            while (true) {
                val line = reader.readLine()
                if (line == null) {
                    eof = true
                    break
                }
                if (line.isBlank()) continue
                val incoming = try {
                    mapper.readTree(line)
                } catch (exception: Exception) {
                    synchronized(outputLock) {
                        send(mapOf("jsonrpc" to "2.0", "error" to mapOf(
                            "code" to -32700, "message" to "parse error: ${exception.message}",
                        )))
                    }
                    continue
                }
                if (incoming == null || !incoming.isObject) continue
                val method = incoming.get("method")?.asText()
                if (method == "notifications/cancelled") {
                    val id = incoming.get("params")?.get("requestId") ?: continue
                    val request = synchronized(outputLock) {
                        requests.remove(id)?.also { it.cancelled = true }
                    }
                    request?.cancel()
                    request?.let { pools.forEach { pool -> pool.remove(it.task) } }
                    continue
                }
                val id = incoming.get("id") ?: continue
                if (method != "tools/call") {
                    synchronized(outputLock) { handle(incoming)?.let(::send) }
                    continue
                }
                val params = incoming.get("params")
                val token = params?.get("_meta")?.get("progressToken")
                    ?.takeIf { it.isTextual || it.isIntegralNumber }
                val request = Request()
                var progress = 0L
                var lastProgressAt = 0L
                request.context = OperationContext { message ->
                    synchronized(outputLock) {
                        val now = System.nanoTime()
                        if (token != null && !request.cancelled && requests[id] === request &&
                            (progress == 0L || now - lastProgressAt >= TimeUnit.MILLISECONDS.toNanos(100))) {
                            lastProgressAt = now
                            send(mapOf("jsonrpc" to "2.0", "method" to "notifications/progress", "params" to mapOf(
                                "progressToken" to token, "progress" to ++progress, "message" to message,
                            )))
                        }
                    }
                }
                request.task = FutureTask {
                    try {
                        request.context.run {
                            OperationContext.progress("Running ${params?.get("name")?.asText() ?: "tool"}")
                            val reply = handle(incoming)
                            synchronized(outputLock) {
                                if (!request.cancelled && requests.remove(id, request) && reply != null) send(reply)
                            }
                        }
                    } catch (exception: Exception) {
                        synchronized(outputLock) {
                            if (!request.cancelled && requests.remove(id, request)) {
                                send(errorReply(id, exception.message ?: "tool execution failed"))
                            }
                        }
                    } finally {
                        requests.remove(id, request)
                    }
                }
                if (requests.putIfAbsent(id, request) != null) {
                    synchronized(outputLock) {
                        send(mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf(
                            "code" to -32600, "message" to "request id is already in flight",
                        )))
                    }
                    continue
                }
                val name = params?.get("name")?.asText()
                val executor = when {
                    name == "index_project" || name == "check_environment" ||
                        (name == "project_status" && params.get("arguments")?.get("check_classpath")?.asBoolean() == true) -> builds
                    name == "get_source" || name == "search_source" || name == "get_class" || name == "open_symbol" -> sources
                    else -> reads
                }
                try {
                    executor.execute(request.task)
                } catch (_: RejectedExecutionException) {
                    requests.remove(id, request)
                    synchronized(outputLock) { send(errorReply(id, "tool queue is full; retry after an active request finishes")) }
                }
            }
        } finally {
            // EOF is a half-close: piped CLI clients still expect their accepted calls to finish.
            // On a broken reader, or an expired shutdown deadline, cancel outstanding work instead.
            pools.forEach { it.shutdown() }
            try {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainTimeoutMs)
                if (eof) pools.forEach {
                    it.awaitTermination((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(outputLock) { closed.set(true); requests.values.forEach { it.cancelled = true } }
                requests.values.forEach { it.cancel() }
                pools.forEach { it.shutdownNow() }
            }
        }
    }

    private class Request {
        @Volatile var cancelled = false
        lateinit var context: OperationContext
        lateinit var task: FutureTask<Unit>
        fun cancel() {
            context.cancel()
            task.cancel(true)
        }
    }

    private fun errorReply(id: JsonNode, message: String): Map<String, Any?> = mapOf(
        "jsonrpc" to "2.0", "id" to id, "result" to mapOf(
            "isError" to true, "content" to listOf(mapOf("type" to "text", "text" to message)),
        ),
    )
}
