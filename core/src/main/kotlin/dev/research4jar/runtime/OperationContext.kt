package dev.research4jar.runtime

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Request-local cancellation and progress, shared by the MCP host and core operations. */
class OperationContext(private val report: (String) -> Unit = {}) {
    private val cancelled = AtomicBoolean(false)
    private val cancellations = ConcurrentHashMap.newKeySet<() -> Unit>()

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            cancellations.forEach { action -> runCatching(action) }
        }
    }

    fun <T> run(action: () -> T): T {
        val previous = active.get()
        active.set(this)
        return try {
            checkCancelled()
            action()
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    companion object {
        private val active = ThreadLocal<OperationContext?>()

        fun checkCancelled() {
            if (Thread.currentThread().isInterrupted || active.get()?.cancelled?.get() == true) {
                throw CancellationException("operation cancelled")
            }
        }

        fun progress(message: String) {
            checkCancelled()
            active.get()?.report?.invoke(message)
        }

        /** Registration also handles cancellation racing with resource creation. */
        fun onCancel(action: () -> Unit): AutoCloseable {
            val context = active.get() ?: return AutoCloseable {}
            context.cancellations.add(action)
            if (context.cancelled.get()) runCatching(action)
            return AutoCloseable { context.cancellations.remove(action) }
        }
    }
}
