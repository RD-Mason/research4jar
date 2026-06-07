package dev.springdep.indexer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectPointerTest {
    @Test
    fun `writes pointer and appends Claude instructions only once`() {
        val project = Files.createTempDirectory("springdep-project-test")
        val original = "# Existing instructions\n\n\n"
        Files.writeString(project.resolve("CLAUDE.md"), original)
        val pointer = ProjectIndex(
            classpath_fingerprint = "0123456789abcdef",
            session_db_path = "/tmp/session.db",
            coverage = Coverage(2, 1, listOf("broken.jar")),
        )

        ProjectPointer.write(project, pointer, jacksonObjectMapper())
        ProjectPointer.ensureClaudeInstructions(project)
        ProjectPointer.ensureClaudeInstructions(project)

        val json = jacksonObjectMapper().readTree(project.resolve(".springdep/project.json").toFile())
        assertEquals("0123456789abcdef", json["classpath_fingerprint"].asText())
        val claude = Files.readString(project.resolve("CLAUDE.md"))
        assertTrue(claude.startsWith(original))
        assertEquals(1, "## SpringDep（jar 配置项查询）".toRegex().findAll(claude).count())
    }
}
