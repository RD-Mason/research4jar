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
    val schema_version: Int = SpringDepVersions.SCHEMA,
    val extractor_version: Int = SpringDepVersions.EXTRACTOR,
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

        当用户询问 Spring 配置项、接口实现、注解使用、@Bean 定义、条件装配或 SPI 扩展点时，
        运行以下命令并**依据其 JSON 输出**回答，不要凭记忆作答：

        ```bash
        springdep find-config-properties <配置项前缀>   # 配置项：类型/默认值/来源 jar
        springdep find-implementations <接口或类 FQN>   # 实现类（默认含传递闭包）
        springdep find-by-annotation <注解 FQN>         # 标注类（默认展开元注解）
        springdep get-class <类 FQN>                    # 单个类的全部已索引事实
        springdep get-bean-definitions <类型或配置类 FQN>
        springdep explain-conditional <配置类 FQN>      # 类与 @Bean 方法的条件装配
        springdep find-string <子串>                    # 字节码字符串常量检索
        springdep list-extension-points [key]           # SPI 注册总览/明细
        ```

        输出包含匹配结果、来源 jar，以及 `coverage` 字段（已索引多少个 jar、哪些 jar 未索引）。
        加 `--direct` 可关闭传递闭包/元注解展开；注解查询不合并 @AliasFor 属性别名。
        依赖变更后运行 `springdep index` 增量更新索引。
        如果用户问的内容不在结果里，结合 `coverage` 判断是「确实不存在」还是「所在 jar 未被索引」，并如实说明。
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
