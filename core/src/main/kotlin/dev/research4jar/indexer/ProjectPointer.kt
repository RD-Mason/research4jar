package dev.research4jar.indexer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import dev.research4jar.indexer.store.AtomicFiles
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
    val schema_version: Int = Research4JarVersions.SCHEMA,
    val extractor_version: Int = Research4JarVersions.EXTRACTOR,
    val classpath_fingerprint: String,
    val session_db_path: String,
    val built_at: Long = Instant.now().epochSecond,
    val coverage: Coverage,
)

object ProjectPointer {
    // The HEADING doubles as the idempotence key in appendGuidance: guidance
    // is appended only when the heading is absent. Extending the snippet body
    // (e.g. the M-source get-source/search-source lines) therefore reaches
    // newly indexed projects only; already-guided projects keep their earlier
    // text — an accepted trade-off over rewriting user-owned agent files.
    private const val HEADING = "## Research4Jar（Java 依赖事实查询）"
    private val snippet = """
        $HEADING

        本项目已用 Research4Jar 索引了 Maven/Java 依赖 jar 里的类、方法、注解、字符串、
        SPI、Spring 配置项与字节码事实。

        依赖、JAR、外部类、import 来源问题优先使用 `.research4jar/project.json` 指向的索引与
        Research4Jar CLI/MCP 工具。不要只读 `.research4jar` 里的 JSON/SQLite 文件；先调用工具拿结构化答案。
        当用户询问依赖 jar 里的 Java/Spring 行为、某个类/方法/import 来自哪里、某个 Maven 依赖为什么存在、
        或配置项、接口实现、注解使用、@Bean 定义、条件装配、SPI 扩展点时，先检索再展开：

        ```bash
        research4jar dep precise <import|类|坐标|jar>       # 直接回答 import/class 来自哪个 jar，并联动源码消费位置
        research4jar artifact <坐标|artifactId|jar>         # 显式查 artifact/jar 及其依赖路径
        research4jar class <类名或 FQN>                     # 类来源 jar/依赖路径/源码消费位置
        research4jar method <方法名或 Class#method>         # 显式查方法（find-method 的短入口）
        research4jar search-symbol <关键词>                # 首选宽检索：类/方法/注解/SPI/配置项/字符串
        research4jar open-symbol <类 FQN 或 Class#method>  # 展开 search-symbol 返回的符号
        research4jar get-source <类 FQN 或 Class#method>   # 读依赖类源码；#method 只取单个方法体（省 token）
        research4jar search-source <文本> --in <坐标|jar|类> # 在单个依赖 jar 的源码里做子串检索
        research4jar dep why <坐标|jar|类 FQN>              # 解释 Maven 依赖来源与传递链路
        research4jar find-class <类名或包前缀>             # 通用 Java 类查询
        research4jar find-method <方法名或 Class#method>   # 通用 Java 方法查询
        research4jar list-packages [包前缀]                # 按 jar/package 查看包结构
        research4jar find-config-properties <配置项前缀>   # 配置项：类型/默认值/来源 jar
        research4jar find-implementations <接口或类 FQN>   # 实现类（默认含传递闭包）
        research4jar find-by-annotation <注解 FQN>         # 标注类（默认展开元注解）
        research4jar get-class <类 FQN>                    # 单个类的全部已索引事实
        research4jar get-bean-definitions <类型或配置类 FQN>
        research4jar explain-conditional <配置类 FQN>      # 类与 @Bean 方法的条件装配
        research4jar find-string <子串>                    # 字节码字符串常量检索
        research4jar list-extension-points [key]           # SPI 注册总览/明细
        ```

        输出包含匹配结果、来源 jar，以及 `coverage` 字段（已索引多少个 jar、哪些 jar 未索引）。
        `dep precise` 会同时用依赖索引确认「有没有这个依赖/来自哪个 jar」，并用源码搜索确认「项目哪里消费」。
        `dep why` 依赖 Maven 项目索引时生成的 `.research4jar/dependencies.json`。
        加 `--direct` 可关闭传递闭包/元注解展开；注解查询不合并 @AliasFor 属性别名。
        依赖变更后运行 `research4jar index` 增量更新索引。
        `get-source`/`search-source` 本地优先：先找本地 Maven/Gradle 缓存里的 sources jar，
        没有则用 CFR 反编译并缓存；返回的 `source_kind` 标明来源（sources-jar 或 decompiled）。
        加 `--fetch` 才会通过项目自己的 Maven 配置下载 sources jar（绝不默认联网）。
        如果用户问的内容不在结果里，结合 `coverage` 判断是「确实不存在」还是「所在 jar 未被索引」，并如实说明。
    """.trimIndent()

    fun write(projectDir: Path, projectIndex: ProjectIndex, objectMapper: ObjectMapper) {
        val research4JarDir = projectDir.resolve(".research4jar")
        Files.createDirectories(research4JarDir)
        val target = research4JarDir.resolve("project.json")
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

    /**
     * The per-agent instruction files this guidance is appended to. One
     * convention per ecosystem: CLAUDE.md (Claude Code and its compatibles),
     * AGENTS.md (the cross-vendor standard read by Codex, Cursor, Copilot,
     * Jules, Zed, and most newer tools), GEMINI.md (Gemini CLI). Together
     * they make the index self-announcing to any mainstream coding agent.
     */
    private val AGENT_FILES = listOf("CLAUDE.md", "AGENTS.md", "GEMINI.md")

    fun ensureClaudeInstructions(projectDir: Path) {
        Files.createDirectories(projectDir)
        AGENT_FILES.forEach { appendGuidance(projectDir.resolve(it)) }
    }

    private fun appendGuidance(file: Path) {
        val existing = if (Files.exists(file)) {
            String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        } else {
            ""
        }
        if (existing.contains(HEADING)) return

        val separator = when {
            existing.isEmpty() || existing.endsWith("\n\n") -> ""
            existing.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        Files.write(
            file,
            (existing + separator + snippet + "\n").toByteArray(StandardCharsets.UTF_8),
        )
    }
}
