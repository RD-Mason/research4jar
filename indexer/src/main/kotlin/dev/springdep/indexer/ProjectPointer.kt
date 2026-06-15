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
    private const val HEADING = "## SpringDep（Java 依赖事实查询）"
    private val snippet = """
        $HEADING

        本项目已用 SpringDep 索引了 Maven/Java 依赖 jar 里的类、方法、注解、字符串、
        SPI、Spring 配置项与字节码事实。

        当用户询问依赖 jar 里的 Java/Spring 行为、某个类/方法来自哪里、某个 Maven 依赖为什么存在、
        或配置项、接口实现、注解使用、@Bean 定义、条件装配、SPI 扩展点时，先检索再展开：

        ```bash
        springdep search-symbol <关键词>                # 首选宽检索：类/方法/注解/SPI/配置项/字符串
        springdep open-symbol <类 FQN 或 Class#method>  # 展开 search-symbol 返回的符号
        springdep why-dependency <坐标|jar|类 FQN>      # 解释 Maven 依赖来源与传递链路
        springdep find-class <类名或包前缀>             # 通用 Java 类查询
        springdep find-method <方法名或 Class#method>   # 通用 Java 方法查询
        springdep list-packages [包前缀]                # 按 jar/package 查看包结构
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
        `why-dependency` 依赖 Maven 项目索引时生成的 `.springdep/dependencies.json`。
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
