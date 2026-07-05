package dev.research4jar.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class Research4JarPluginTest {
    @Test
    fun `indexes a java project and captures gradle provenance`() {
        val projectDir = Files.createTempDirectory("research4jar-plugin-test").toFile()
        val home = File(projectDir, "r4j-home")
        val localJar = buildTinyJar(projectDir)

        File(projectDir, "settings.gradle").writeText("rootProject.name = 'fixture'\n")
        File(projectDir, "build.gradle").writeText(
            """
            plugins {
                id 'java'
                id 'dev.research4jar'
            }
            group = 'com.example'
            version = '1.0'
            dependencies {
                implementation files('${localJar.name}')
            }
            research4jarIndex {
                home = '${home.absolutePath.replace("\\", "\\\\")}'
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("research4jarIndex", "--stacktrace")
            .build()

        assertTrue(result.task(":research4jarIndex")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(File(projectDir, ".research4jar/project.json").isFile, "project pointer written")
        val provenance = File(projectDir, ".research4jar/dependencies.json")
        assertTrue(provenance.isFile, "dependency provenance written")
        assertTrue(provenance.readText().contains("\"build_tool\" : \"gradle\""))
        assertTrue(File(home, "manifest.db").isFile, "manifest created under the task home")
    }

    private fun buildTinyJar(dir: File): File {
        val jar = File(dir, "tiny.jar")
        java.util.zip.ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            zip.closeEntry()
        }
        return jar
    }
}
