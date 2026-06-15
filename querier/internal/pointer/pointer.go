// Package pointer writes the project index (.research4jar/project.json) and the
// CLAUDE.md guidance snippet, porting the Kotlin ProjectPointer so the
// pure-Go index path produces the same project-side artifacts. The snippet
// text must stay byte-identical to ProjectPointer.kt: both writers dedupe by
// searching for the heading.
package pointer

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"time"

	"dev.research4jar/querier/internal/project"
	"dev.research4jar/querier/internal/versions"
)

const heading = "## Research4Jar（Java 依赖事实查询）"

const snippet = heading + `

本项目已用 Research4Jar 索引了 Maven/Java 依赖 jar 里的类、方法、注解、字符串、
SPI、Spring 配置项与字节码事实。

当用户询问依赖 jar 里的 Java/Spring 行为、某个类/方法来自哪里、某个 Maven 依赖为什么存在、
或配置项、接口实现、注解使用、@Bean 定义、条件装配、SPI 扩展点时，先检索再展开：

` + "```bash" + `
research4jar search-symbol <关键词>                # 首选宽检索：类/方法/注解/SPI/配置项/字符串
research4jar open-symbol <类 FQN 或 Class#method>  # 展开 search-symbol 返回的符号
research4jar why-dependency <坐标|jar|类 FQN>      # 解释 Maven 依赖来源与传递链路
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
` + "```" + `

输出包含匹配结果、来源 jar，以及 ` + "`coverage`" + ` 字段（已索引多少个 jar、哪些 jar 未索引）。
` + "`why-dependency`" + ` 依赖 Maven 项目索引时生成的 ` + "`.research4jar/dependencies.json`" + `。
加 ` + "`--direct`" + ` 可关闭传递闭包/元注解展开；注解查询不合并 @AliasFor 属性别名。
依赖变更后运行 ` + "`research4jar index`" + ` 增量更新索引。
如果用户问的内容不在结果里，结合 ` + "`coverage`" + ` 判断是「确实不存在」还是「所在 jar 未被索引」，并如实说明。`

// Write atomically writes .research4jar/project.json with the same fields and
// order as the Kotlin ProjectIndex.
func Write(projectDir, fingerprint, sessionDBPath string, coverage project.Coverage) error {
	index := project.Pointer{
		SchemaVersion:        versions.Schema,
		ExtractorVersion:     versions.Extractor,
		ClasspathFingerprint: fingerprint,
		SessionDBPath:        sessionDBPath,
		BuiltAt:              time.Now().Unix(),
		Coverage:             coverage,
	}
	directory := filepath.Join(projectDir, ".research4jar")
	if err := os.MkdirAll(directory, 0o755); err != nil {
		return err
	}
	formatted, err := json.MarshalIndent(index, "", "  ")
	if err != nil {
		return err
	}
	target := filepath.Join(directory, "project.json")
	temp, err := os.CreateTemp(directory, ".project.json.*.tmp")
	if err != nil {
		return err
	}
	tempPath := temp.Name()
	defer os.Remove(tempPath)
	if _, err := temp.Write(append(formatted, '\n')); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(tempPath, target)
}

// EnsureClaudeInstructions appends the usage snippet to the project's
// CLAUDE.md unless the heading is already present.
func EnsureClaudeInstructions(projectDir string) error {
	if err := os.MkdirAll(projectDir, 0o755); err != nil {
		return err
	}
	claudeFile := filepath.Join(projectDir, "CLAUDE.md")
	existing := ""
	if content, err := os.ReadFile(claudeFile); err == nil {
		existing = string(content)
	}
	if strings.Contains(existing, heading) {
		return nil
	}
	separator := "\n\n"
	switch {
	case existing == "" || strings.HasSuffix(existing, "\n\n"):
		separator = ""
	case strings.HasSuffix(existing, "\n"):
		separator = "\n"
	}
	return os.WriteFile(
		claudeFile, []byte(existing+separator+snippet+"\n"), 0o644,
	)
}
