# AGENTS

依赖、JAR、外部类、import 来源问题优先使用 `.research4jar/project.json` 指向的
Research4Jar 索引与 `research4jar` CLI/MCP 工具，不要只直接读取
`.research4jar` 下的 JSON/SQLite 文件。

常用入口：

```bash
research4jar dep precise '<import|class|coordinate|jar>'  # jar 来源 + Maven 路径 + 源码消费位置
research4jar dep why '<coordinate|jar|class>'             # 解释 jar 被谁引入、直接依赖是谁
research4jar artifact '<coordinate|artifactId|jar>'       # 显式查 artifact/jar
research4jar class '<simpleName|fqn>'                     # 类来源 jar + 依赖路径 + 源码消费位置
research4jar method '<name|Class#method>'                 # 显式查方法
research4jar search-symbol '<text>'                       # 宽检索后再 open-symbol
research4jar open-symbol '<fqn|Class#method>'             # 展开一个符号
```

依赖索引用来确认“有没有依赖、来自哪个 jar、哪个直接依赖引入”；`dep precise`
返回的 `source_usages` 用源码/构建文件搜索确认“项目哪里消费”。如果结果为空，结合
`coverage` 判断是确实不存在，还是相关 jar 没有被索引。
