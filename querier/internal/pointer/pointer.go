// Package pointer writes the project index (.springdep/project.json) and the
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

	"dev.springdep/querier/internal/project"
	"dev.springdep/querier/internal/versions"
)

const heading = "## SpringDep（jar 配置项查询）"

const snippet = heading + `

本项目已用 SpringDep 索引了依赖 jar 里的 Spring 配置项与字节码事实。

当用户询问 Spring 配置项、接口实现、注解使用、@Bean 定义、条件装配或 SPI 扩展点时，
运行以下命令并**依据其 JSON 输出**回答，不要凭记忆作答：

` + "```bash" + `
springdep find-config-properties <配置项前缀>   # 配置项：类型/默认值/来源 jar
springdep find-implementations <接口或类 FQN>   # 实现类（默认含传递闭包）
springdep find-by-annotation <注解 FQN>         # 标注类（默认展开元注解）
springdep get-class <类 FQN>                    # 单个类的全部已索引事实
springdep get-bean-definitions <类型或配置类 FQN>
springdep explain-conditional <配置类 FQN>      # 类与 @Bean 方法的条件装配
springdep find-string <子串>                    # 字节码字符串常量检索
springdep list-extension-points [key]           # SPI 注册总览/明细
` + "```" + `

输出包含匹配结果、来源 jar，以及 ` + "`coverage`" + ` 字段（已索引多少个 jar、哪些 jar 未索引）。
加 ` + "`--direct`" + ` 可关闭传递闭包/元注解展开；注解查询不合并 @AliasFor 属性别名。
依赖变更后运行 ` + "`springdep index`" + ` 增量更新索引。
如果用户问的内容不在结果里，结合 ` + "`coverage`" + ` 判断是「确实不存在」还是「所在 jar 未被索引」，并如实说明。`

// Write atomically writes .springdep/project.json with the same fields and
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
	directory := filepath.Join(projectDir, ".springdep")
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
