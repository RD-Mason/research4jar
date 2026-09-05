package dev.research4jar.query

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SourceUsageIndexTest {
    @TempDir lateinit var root: Path

    private fun file(name: String, text: String): Path = root.resolve(name).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    @Test
    fun `unchanged calls reuse results and different queries reuse text`() {
        file("Example.java", "import example.Widget;\nclass Example { Widget field; Other another; }\n")
        val index = SourceUsageIndex(root)
        val result = index.find(listOf("import example.Widget"), listOf("Widget"), 20)
        assertEquals(listOf(1, 2), result.usages.map { it.line })
        assertEquals(listOf("import example.Widget", "Widget"), result.usages.map { it.match })
        assertEquals(result, index.find(listOf("import example.Widget"), listOf("Widget"), 20))
        assertEquals(1, index.contentReads)
        assertEquals(1, index.find(emptyList(), listOf("Other"), 20).usages.size)
        assertEquals(1, index.contentReads)
    }

    @Test
    fun `same length edits creates deletes and renames invalidate cached answers`() {
        val source = file("Example.java", "Widget value;\n")
        val index = SourceUsageIndex(root)
        assertEquals(1, index.find(emptyList(), listOf("Widget"), 20).usages.size)
        val oldTime = Files.getLastModifiedTime(source)
        Files.writeString(source, "Gadget value;\n")
        Files.setLastModifiedTime(source, FileTime.fromMillis(oldTime.toMillis() + 1000))
        assertTrue(index.find(emptyList(), listOf("Widget"), 20).usages.isEmpty())
        val added = file("nested/New.java", "Widget added;\n")
        assertEquals("nested/New.java", index.find(emptyList(), listOf("Widget"), 20).usages.single().path)
        val renamed = root.resolve("nested/Renamed.java")
        Files.move(added, renamed)
        assertEquals("nested/Renamed.java", index.find(emptyList(), listOf("Widget"), 20).usages.single().path)
        Files.delete(renamed)
        assertTrue(index.find(emptyList(), listOf("Widget"), 20).usages.isEmpty())
    }

    @Test
    fun `high signal outranks broad hits and duplicate lines do not consume the page`() {
        file("Example.java", "Widget first;\nimport example.Widget;\nWidget third;\nWidget fourth;\n")
        val result = SourceUsageIndex(root).find(listOf("import example.Widget"), listOf("Widget"), 2)
        assertEquals(listOf(2, 1), result.usages.map { it.line })
        assertTrue(result.hasMore)
        assertEquals("", result.truncatedReason)
    }

    @Test
    fun `broad tier searches beyond the former shared two thousand file budget`() {
        repeat(2105) { file("src/Example$it.java", "package sample;\n") }
        file("src/Target.java", "Widget value;\n")
        val index = SourceUsageIndex(root, timeBudgetNanos = TimeUnit.SECONDS.toNanos(30))
        val result = index.find(listOf("import example.Widget"), listOf("Widget"), 20)
        assertEquals("src/Target.java", result.usages.single().path)
        assertEquals("", result.truncatedReason)
        assertEquals(2106, index.contentReads, "the two tiers should share a content pass")
    }

    @Test
    fun `file budget is explicit and incomplete scans never become complete cached answers`() {
        val first = file("One.java", "other\n")
        val second = file("Two.java", "other\n")
        val index = SourceUsageIndex(root, maxFiles = 1)
        repeat(2) { assertEquals("file_budget", index.find(emptyList(), listOf("Widget"), 20).truncatedReason) }
        Files.delete(first)
        Files.delete(second)
        file("Three.java", "Widget\n")
        val complete = index.find(emptyList(), listOf("Widget"), 20)
        assertEquals("", complete.truncatedReason)
        assertEquals(1, complete.usages.size)
    }

    @Test
    fun `content eviction keeps results correct and ignored or binary files remain excluded`() {
        file("One.java", "Widget one;\n")
        file("Two.java", "Widget two;\n")
        file("config.yml", "value: Widget\n")
        file("target/Generated.java", "Widget generated;\n")
        file("Binary.java", "Widget\u0000binary")
        file("Notes.txt", "Widget notes")
        val index = SourceUsageIndex(root, maxContentBytes = 160)
        val result = index.find(emptyList(), listOf("Widget"), 20)
        assertEquals(setOf("One.java", "Two.java", "config.yml"), result.usages.map { it.path }.toSet())
        assertFalse(result.hasMore)
        assertTrue(index.find(emptyList(), listOf("missing"), 20).usages.isEmpty())
        assertEquals(result, index.find(emptyList(), listOf("Widget"), 20))
    }
}
