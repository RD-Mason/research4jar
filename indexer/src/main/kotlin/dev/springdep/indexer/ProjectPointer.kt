package dev.springdep.indexer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import dev.springdep.indexer.store.AtomicFiles
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

data class Coverage(
    val jars_total: Int,
    val jars_indexed: Int,
    val jars_missing: List<String>,
)

data class ProjectIndex(
    val schema_version: Int = 2,
    val extractor_version: Int = 2,
    val classpath_fingerprint: String,
    val session_db_path: String,
    val built_at: Long = Instant.now().epochSecond,
    val coverage: Coverage,
)

object ProjectPointer {
    private const val HEADING = "## SpringDep（jar 配置项查询）"
    private val snippet = """
        $HEADING

        本项目已用 SpringDep 索引了依赖 jar 里的 Spring 配置项与字节码事实。

        当用户询问某个 Spring 配置项的含义、类型、默认值，或想知道某前缀下有哪些配置项时，
        运行以下命令并**依据其 JSON 输出**回答，不要凭记忆作答：

        ```bash
        springdep find-config-properties <配置项前缀>
        springdep find-implementations <接口或类 FQN>
        springdep find-by-annotation <注解 FQN>
        ```

        输出包含匹配结果、来源 jar，以及 `coverage` 字段（已索引多少个 jar、哪些 jar 未索引）。
        M1 的实现关系与注解查询只匹配直接声明，不做传递闭包或元注解展开。
        如果用户问的配置项不在结果里，结合 `coverage` 判断是「确实不存在」还是「所在 jar 未被索引」，并如实说明。
    """.trimIndent()

    fun write(projectDir: Path, projectIndex: ProjectIndex, objectMapper: ObjectMapper) {
        val springDepDir = projectDir.resolve(".springdep")
        Files.createDirectories(springDepDir)
        val target = springDepDir.resolve("project.json")
        val temporary = AtomicFiles.temporaryTarget(target)
        try {
            val formatted = objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsBytes(projectIndex)
            Files.write(temporary, formatted)
            AtomicFiles.commit(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun ensureClaudeInstructions(projectDir: Path) {
        Files.createDirectories(projectDir)
        val claudeFile = projectDir.resolve("CLAUDE.md")
        val existing = if (Files.exists(claudeFile)) Files.readString(claudeFile) else ""
        if (existing.contains(HEADING)) return

        val separator = when {
            existing.isEmpty() || existing.endsWith("\n\n") -> ""
            existing.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        Files.writeString(
            claudeFile,
            existing + separator + snippet + "\n",
            StandardCharsets.UTF_8,
        )
    }
}
