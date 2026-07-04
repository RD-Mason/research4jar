package dev.research4jar.indexer

import java.nio.file.Files
import java.io.File
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals

class JarSourceTest {
    @Test
    fun `directory glob and comma list resolve jars deterministically`() {
        val root = Files.createTempDirectory("jar-source-test")
        val nested = root.resolve("nested").createDirectories()
        val alpha = root.resolve("alpha.jar").createFile()
        val beta = nested.resolve("beta.JAR").createFile()
        nested.resolve("ignored.txt").createFile()

        assertEquals(
            listOf(alpha.toAbsolutePath(), beta.toAbsolutePath()).map { it.normalize() },
            JarSource.resolve(root.toString()),
        )
        assertEquals(
            listOf(alpha.toAbsolutePath().normalize()),
            JarSource.resolve(root.toString() + File.separator + "*.jar"),
        )
        assertEquals(
            listOf(alpha, beta).map { it.toAbsolutePath().normalize() }.sortedBy { it.toString() },
            JarSource.resolve("${beta},${alpha},${alpha}"),
        )
    }
}
