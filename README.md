# SpringDep

[![CI](https://github.com/RD-Mason/research4jar/actions/workflows/ci.yml/badge.svg)](https://github.com/RD-Mason/research4jar/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

> Early-stage software under active development. Data contracts and commands may still evolve before the first stable release.

SpringDep turns Spring dependency jars into a local, queryable fact database so coding agents can inspect framework behavior instead of guessing from model memory.

Large Spring Boot applications hide important behavior inside dependency jars: auto-configuration, conditional activation, bean factories, configuration properties, annotations, class hierarchies, and extension registrations. Those facts are easy for an AI tool to miss and expensive to recover by repeatedly reading bytecode or source. SpringDep extracts them once into deterministic SQLite shards and exposes small, source-aware queries.

## Architecture

SpringDep follows a four-layer onion:

1. **Jar facts**: a Kotlin/JVM indexer reads metadata and bytecode with ASM without loading classes or executing jar code.
2. **Immutable shards**: each jar becomes one content-addressed SQLite shard identified by `<jar_sha256>@<extractor_version>`.
3. **Project session**: shards selected by a project's classpath are merged into a compact session database. Symbol references remain unresolved until query time, so relationships can cross jar boundaries.
4. **Query and host integration**: a pure-Go CLI opens the session read-only and returns JSON with provenance and coverage. A project pointer and `CLAUDE.md` guidance let coding agents call it directly.

SQLite files are the only contract between the Kotlin indexer and Go querier.

## Requirements

- JDK 17 or newer
- Go 1.23 or newer
- Bash for the end-to-end test script

## Install

```bash
./install.sh                  # builds and installs to ~/.local (override with PREFIX=...)
```

Or step by step: `make build` then `make install PREFIX=$HOME/.local`. Requirements: JDK 17+ and Go 1.23+ to build; only a JRE 17+ at runtime. Make sure `$PREFIX/bin` is on your PATH.

## Quick Start

Inside any Maven or Gradle Spring Boot project:

```bash
springdep index                # resolves the runtime classpath via mvnw/gradlew, indexes every jar
springdep find-config-properties spring.datasource
springdep find-implementations jakarta.servlet.Filter
springdep find-by-annotation org.springframework.stereotype.Component
springdep get-class org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
springdep get-bean-definitions javax.sql.DataSource
springdep explain-conditional org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
springdep find-string X-Forwarded-For
springdep list-extension-points
```

`springdep index` prefers the project's `mvnw`/`gradlew` wrapper and falls back to `mvn`/`gradle` on PATH; pass `--jars <dir|glob|list>` to index explicit jars instead. Re-runs are incremental — unchanged jars hit the content-addressed cache and unchanged classpaths reuse the session database.

`find-implementations` is transitive by default (subinterface and superclass chains across jars); `find-by-annotation` expands meta-annotations by default (querying `@Component` finds `@Service` classes). Pass `--direct` for declared-only matching.

All query commands support `--format json|text`, `--page`, `--page-size`, and `--project-dir`. Use `--home` or `SPRINGDEP_HOME` to override the global data directory.

## Use from Cursor, Claude Code, or any MCP host

`springdep mcp` runs a stdio MCP server exposing indexing and every query as tools, so coding agents call SpringDep directly.

**Cursor** — add to `.cursor/mcp.json` (project) or `~/.cursor/mcp.json` (global):

```json
{
  "mcpServers": {
    "springdep": { "command": "springdep", "args": ["mcp"] }
  }
}
```

**Claude Code**:

```bash
claude mcp add springdep -- springdep mcp
```

Tools exposed: `index_project` (auto-resolves the classpath via Maven/Gradle), `find_config_properties`, `find_implementations`, `find_by_annotation`, `get_class`, `get_bean_definitions`, `explain_conditional`, `find_string`, `list_extension_points`. Each accepts an optional `project_dir`; by default the server searches upward from its working directory.

For CLI-driven agents without MCP, `springdep index` also appends usage guidance to the project's `CLAUDE.md`, so Claude Code picks the tool up with zero configuration.

## Coverage

Every JSON response includes:

```json
{
  "coverage": {
    "jars_total": 312,
    "jars_indexed": 310,
    "jars_missing": ["internal-foo.jar"],
    "extractor_version": 2
  }
}
```

Use this field when interpreting an empty result. It distinguishes "not found in the indexed classpath" from "the relevant jar may be missing or unreadable."

## Current Capabilities

- Spring configuration metadata and Maven coordinates
- All `spring.factories` keys, auto-configuration imports, and Java service registrations
- Classes, interfaces, methods, class/method annotations, `@Bean` methods, Spring conditions, and string constants
- Deterministic, atomic, content-addressed shards with incremental cache hits and session reuse
- Transitive cross-jar implementation lookup (query-time recursive traversal over symbolic references)
- Meta-annotation expansion at query time (`@Component` finds `@Service`/`@Repository`/`@Controller` classes)
- Class detail, bean-definition, conditional-explanation, string-constant, and extension-point queries
- Maven/Gradle classpath auto-discovery (`springdep index`) and an MCP stdio server (`springdep mcp`)
- Linux, macOS, and Windows-compatible data paths; pure-Go SQLite querying without CGO

## Known Limitations

- `find-by-annotation` expands meta-annotations but does not merge `@AliasFor` attribute aliases; attributes are reported as written on the matched annotation.
- `find-string` is substring matching over extracted constants, not full-text search with ranking.
- Shard signing/distribution and cache garbage collection are planned work.
- `--fat-jar` is not implemented; extract `BOOT-INF/lib/*.jar` first.

## Verification

```bash
make test
make e2e
```

The suite includes generated cross-jar fixtures, fixed-version Spring golden jars, actuator bean/condition calibration, malformed inputs, unsupported class versions, deterministic shard comparisons, incremental cache hits, and concurrent indexers.

## Roadmap

- [M0](docs/M0.md): walking skeleton, metadata extraction, shards, session database, first query ✅
- [M1](docs/M1.md): ASM extraction engine, cross-jar relationship queries, golden validation ✅
- M2: query-time graph traversal, meta-annotations, string search, and the broader command set ✅
- M3: build-tool/classpath discovery ✅ (pulled forward) and distribution foundations
- M4: host integrations and MCP ✅ (pulled forward)
- M5: shard lifecycle, signing, download, LRU, and garbage collection

Release automation and cross-platform packaged binaries are planned after the core query surface stabilizes.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), and [SECURITY.md](SECURITY.md).

## License

Licensed under the [Apache License 2.0](LICENSE).
