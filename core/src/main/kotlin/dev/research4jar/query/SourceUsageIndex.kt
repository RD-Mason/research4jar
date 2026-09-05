package dev.research4jar.query

import dev.research4jar.runtime.OperationContext
import dev.research4jar.runtime.WorkingDirectoryContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal data class SourceUsageScan(val usages: List<SourceUsage>, val hasMore: Boolean, val truncatedReason: String)

/** Process-local and bounded: no project setup, watcher delay, or persistent source copies. */
internal object SourceUsageIndexes {
    private val projects = LinkedHashMap<Path, SourceUsageIndex>(4, 0.75f, true)

    fun find(projectDir: String, highSignal: List<String>, broad: List<String>, limit: Int): SourceUsageScan {
        if (projectDir.isEmpty()) return SourceUsageScan(emptyList(), false, "")
        val root = WorkingDirectoryContext.resolve(projectDir)
        val index = synchronized(projects) {
            projects[root] ?: SourceUsageIndex(root).also {
                projects[root] = it
                if (projects.size > 4) projects.remove(projects.keys.first())
            }
        }
        return index.find(highSignal, broad, limit)
    }
}

/**
 * Refresh file metadata on every call, reuse unchanged text, and memoize queries by the complete
 * inventory generation. A changed/new/deleted file invalidates results on the very next call.
 * Both signal tiers share one content pass and one file budget, so a large first tier cannot starve
 * the broad tier. All matching remains literal and case-sensitive, including build/config files.
 */
internal class SourceUsageIndex(
    private val root: Path,
    private val maxFiles: Int = 20_000,
    private val maxContentBytes: Int = 16 * 1024 * 1024,
    private val timeBudgetNanos: Long = 1500L * 1_000_000,
) {
    private data class Stamp(val size: Long, val modified: FileTime, val key: Any?)
    private data class TextEntry(val stamp: Stamp, val text: String?)
    private data class Query(val highSignal: List<String>, val broad: List<String>, val limit: Int)
    private var inventory = linkedMapOf<Path, Stamp>()
    private val contents = LinkedHashMap<Path, TextEntry>(64, 0.75f, true)
    private val results = LinkedHashMap<Query, SourceUsageScan>(16, 0.75f, true)
    private var contentBytes = 0
    private var resultBytes = 0
    internal var contentReads = 0
        private set

    @Synchronized
    fun find(highSignal: List<String>, broad: List<String>, limit: Int): SourceUsageScan {
        requirePageSize(limit)
        val deadline = System.nanoTime() + timeBudgetNanos
        val next = linkedMapOf<Path, Stamp>()
        var truncated = ""
        fun budget(): FileVisitResult? {
            OperationContext.checkCancelled()
            if (System.nanoTime() - deadline > 0) {
                truncated = "time_budget"
                return FileVisitResult.TERMINATE
            }
            return null
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                budget() ?: if (dir.fileName?.toString() in SKIP_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                budget()?.let { return it }
                if (!isSource(file)) return FileVisitResult.CONTINUE
                if (next.size == maxFiles) {
                    truncated = "file_budget"
                    return FileVisitResult.TERMINATE
                }
                next[file] = stamp(attrs)
                return FileVisitResult.CONTINUE
            }
        })
        // Preserve traversal order as well as identity: a reordered inventory changes result order.
        if (truncated.isNotEmpty() || next.entries.toList() != inventory.entries.toList()) {
            results.clear()
            resultBytes = 0
            val iterator = contents.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (next[entry.key] != entry.value.stamp) {
                    contentBytes -= weight(entry.value.text)
                    iterator.remove()
                }
            }
            inventory = next
        }
        val query = Query(highSignal.toList(), broad.toList(), limit)
        if (truncated.isEmpty()) results[query]?.let { return it }

        val highHits = mutableListOf<SourceUsage>()
        val broadHits = mutableListOf<SourceUsage>()
        var stable = true
        for ((path, signature) in next) {
            if (budget() != null) break
            if (signature.size > MAX_FILE_BYTES) continue
            val cached = contents[path]
            val text = if (cached?.stamp == signature) cached.text else {
                val loaded = readText(path)
                contentReads++
                val current = stamp(Files.readAttributes(path, BasicFileAttributes::class.java))
                if (current == signature) cacheText(path, TextEntry(signature, loaded)) else stable = false
                loaded
            } ?: continue
            if (highSignal.none { text.contains(it) } && broad.none { text.contains(it) }) continue
            val relative = root.relativize(path).toString().replace(File.separatorChar, '/')
            text.lineSequence().forEachIndexed { lineIndex, line ->
                OperationContext.checkCancelled()
                val high = highSignal.firstOrNull { line.contains(it) }
                val match = high ?: broad.firstOrNull { line.contains(it) } ?: return@forEachIndexed
                val hits = if (high != null) highHits else broadHits
                if (hits.size <= limit) hits += SourceUsage(relative, lineIndex + 1, match, trimLine(line))
            }
            if (highHits.size > limit) break
        }
        val hits = highHits + broadHits
        val response = SourceUsageScan(hits.take(limit), hits.size > limit, truncated)
        // Never freeze incomplete/time-limited or concurrently edited results in the query cache.
        if (stable && truncated.isEmpty()) {
            results[query] = response
            resultBytes += resultWeight(response)
            while (results.size > 16 || resultBytes > 2 * 1024 * 1024) {
                val key = results.keys.first()
                resultBytes -= resultWeight(results.remove(key)!!)
            }
        }
        return response
    }

    private fun cacheText(path: Path, entry: TextEntry) {
        contents.remove(path)?.let { contentBytes -= weight(it.text) }
        if (weight(entry.text) > maxContentBytes) return
        contents[path] = entry
        contentBytes += weight(entry.text)
        while (contentBytes > maxContentBytes) {
            contentBytes -= weight(contents.remove(contents.keys.first())!!.text)
        }
    }

    private fun readText(path: Path): String? = Files.newInputStream(path).use { input ->
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            OperationContext.checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > MAX_FILE_BYTES) return null
            if ((0 until count).any { buffer[it] == 0.toByte() }) return null
            bytes.write(buffer, 0, count)
        }
        String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun resultWeight(result: SourceUsageScan): Int = 256 + result.usages.sumOf {
        128 + 2 * (it.path.length + it.match.length + it.text.length)
    }

    private fun weight(text: String?): Int = 128 + (text?.length ?: 0) * 2
    private fun stamp(attrs: BasicFileAttributes) = Stamp(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey())

    private fun trimLine(line: String): String {
        val bytes = line.trim().toByteArray(Charsets.UTF_8)
        return String(bytes, 0, minOf(bytes.size, 240), Charsets.UTF_8)
    }

    private fun isSource(path: Path): Boolean = path.fileName.toString().let {
        it.lastIndexOf('.').let { dot -> dot >= 0 && path.fileName.toString().substring(dot) in EXTENSIONS }
    }

    companion object {
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private val SKIP_DIRS = setOf(
            ".git", ".gradle", ".idea", ".mvn", ".research4jar", ".settings", ".vscode",
            "build", "coverage", "dist", "generated", "generated-sources",
            "generated-test-sources", "node_modules", "out", "target",
        )
        private val EXTENSIONS = setOf(
            ".java", ".kt", ".kts", ".groovy", ".scala", ".xml", ".gradle", ".properties", ".yml", ".yaml",
        )
    }
}
