package dev.research4jar.indexer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectPointerTest {
    @Test
    fun `writes pointer and appends Claude instructions only once`() {
        val project = Files.createTempDirectory("research4jar-project-test")
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

        val json = jacksonObjectMapper().readTree(project.resolve(".research4jar/project.json").toFile())
        assertEquals("0123456789abcdef", json["classpath_fingerprint"].asText())
        val claude = Files.readString(project.resolve("CLAUDE.md"))
        assertTrue(claude.startsWith(original))
        assertEquals(1, "## Research4Jar（Java 依赖事实查询）".toRegex().findAll(claude).count())
        // The same guidance reaches every mainstream agent convention, and
        // stays idempotent in each file.
        for (agentFile in listOf("AGENTS.md", "GEMINI.md")) {
            val content = Files.readString(project.resolve(agentFile))
            assertEquals(
                1,
                "## Research4Jar（Java 依赖事实查询）".toRegex().findAll(content).count(),
                "guidance in $agentFile",
            )
        }
    }


    @Test
    fun `legacy guidance section upgrades in place, edited sections stay untouched`() {
        val project = Files.createTempDirectory("research4jar-guidance-upgrade")
        // Simulate a project guided by the previous release: heading present,
        // body without the source-retrieval commands.
        val legacy = ProjectPointer.legacySnippetsForTest().first()
        val trailing = "\n\n## User notes\n\nkeep me\n"
        Files.writeString(project.resolve("CLAUDE.md"), "# Mine\n\n" + legacy + trailing)
        // An edited variant must never be rewritten.
        Files.writeString(project.resolve("AGENTS.md"), "# Mine\n\n" + legacy.replace("research4jar dep why", "research4jar dep why  # my note") + trailing)

        ProjectPointer.ensureClaudeInstructions(project)

        val upgraded = Files.readString(project.resolve("CLAUDE.md"))
        assertTrue(upgraded.contains("get-source"), "legacy section must gain the new commands")
        assertTrue(upgraded.contains("## User notes\n\nkeep me"), "content after the section survives")
        assertEquals(1, "## Research4Jar（Java 依赖事实查询）".toRegex().findAll(upgraded).count())
        val edited = Files.readString(project.resolve("AGENTS.md"))
        assertTrue(!edited.contains("get-source"), "user-edited section is never rewritten")
        // GEMINI.md did not exist: plain append applies.
        assertTrue(Files.readString(project.resolve("GEMINI.md")).contains("get-source"))
    }
}
