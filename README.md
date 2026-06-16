# Research4Jar

[![CI](https://github.com/RD-Mason/research4jar/actions/workflows/ci.yml/badge.svg)](https://github.com/RD-Mason/research4jar/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

> Early-stage software under active development. Data contracts and commands may still evolve before the first stable release.

Research4Jar turns Maven/Java dependency jars into a local, queryable fact database so coding agents can inspect library behavior instead of guessing from model memory. It has deep Spring Boot facts, but the core class, method, package, SPI, string, and dependency provenance queries work for ordinary Java Maven projects too.

Large Spring Boot applications hide important behavior inside dependency jars: auto-configuration, conditional activation, bean factories, configuration properties, annotations, class hierarchies, and extension registrations. Those facts are easy for an AI tool to miss and expensive to recover by repeatedly reading bytecode or source. Research4Jar extracts them once into deterministic SQLite shards and exposes small, source-aware queries.

## Architecture

Research4Jar follows a four-layer onion:

1. **Jar facts**: a Kotlin/JVM indexer reads metadata and bytecode with ASM without loading classes or executing jar code.
2. **Immutable shards**: each jar becomes one content-addressed SQLite shard identified by `<jar_sha256>@<extractor_version>`.
3. **Project session**: shards selected by a project's classpath are merged into a compact session database. Symbol references remain unresolved until query time, so relationships can cross jar boundaries.
4. **Query and host integration**: a pure-Go CLI opens the session read-only and returns JSON with provenance and coverage. A project pointer and `CLAUDE.md` guidance let coding agents call it directly.

SQLite files are the only contract between the Kotlin indexer and Go querier.

## Requirements

Research4Jar ships as a CLI plus an MCP stdio server. The current repository has a Kotlin/JVM indexer and a pure-Go querier, so the environment depends on how you install and use it:

**To use a prebuilt CLI/MCP plugin:**

- `research4jar` on `PATH` (the archive's `bin/` directory).
- Java runtime 17+ for local jar extraction. If every shard is served by a configured registry, indexing can complete without starting the JVM, but registry misses still need Java.
- A target project Maven/Gradle wrapper (`./mvnw` or `./gradlew`) or `mvn`/`gradle` on `PATH` when you run `research4jar index` without `--jars`.
- Cursor, Claude Code, or another MCP host if you want to use `research4jar mcp` as a plugin.

**To build from source in this repository:**

- JDK 17+ (`java` and `javac`)
- Go 1.23+
- `make`
- Bash (`install.sh` uses `/usr/bin/env bash`)

**To run the full local verification suite:**

- Everything needed to build from source
- `jar`/`javac`, Python 3, and the `sqlite3` CLI for `make e2e`

Check the current machine and get install guidance with:

```bash
research4jar doctor                         # runtime checks for plugin/CLI use
research4jar doctor --source-build          # also checks source-build tools
research4jar doctor --format json           # structured output for agents
```

When an agent is installing the environment, have it run `research4jar doctor --format json`, execute the failed checks' `agent_install` commands, run each `verify` command, and repeat `research4jar doctor` until `"ok": true`.

## Install

**Prebuilt archives** (Linux/macOS/Windows, amd64/arm64) are published on the [Releases page](https://github.com/RD-Mason/research4jar/releases): unpack, put `bin/` on your PATH. The JVM indexer is bundled under `libexec/` and found automatically; a JRE 17+ is only needed for jars the shard registry does not cover.

**From source:**

```bash
./install.sh                  # builds and installs to ~/.local (override with PREFIX=...)
```

`install.sh` checks JDK/Go/Make before building. If a requirement is missing or too old, it prints a user-facing install note, an agent-friendly install command, and the verification command to run before retrying. Or step by step: `make build` then `make install PREFIX=$HOME/.local`. Make sure `$PREFIX/bin` is on your PATH.

## Quick Start

Inside any Maven or Gradle Spring Boot project:

```bash
research4jar index                # resolves the runtime classpath via mvnw/gradlew, indexes every jar
research4jar find-config-properties spring.datasource
research4jar find-implementations jakarta.servlet.Filter
research4jar find-by-annotation org.springframework.stereotype.Component
research4jar get-class org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
research4jar get-bean-definitions javax.sql.DataSource
research4jar explain-conditional org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
research4jar find-string X-Forwarded-For
research4jar list-extension-points
research4jar search-symbol DataSourceAutoConfiguration
research4jar open-symbol org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
research4jar why-dependency org.springframework.boot:spring-boot-autoconfigure
research4jar find-class FilterRegistrationBean
research4jar find-method org.example.Foo#bar
research4jar list-packages org.springframework.boot.autoconfigure
```

`research4jar index` prefers the project's `mvnw`/`gradlew` wrapper and falls back to `mvn`/`gradle` on PATH; pass `--jars <dir|glob|list>` to index explicit jars instead. Re-runs are incremental — unchanged jars hit the content-addressed cache and unchanged classpaths reuse the session database.

`find-implementations` is transitive by default (subinterface and superclass chains across jars); `find-by-annotation` expands meta-annotations by default (querying `@Component` finds `@Service` classes). Pass `--direct` for declared-only matching.

All query commands support `--format json|text`, `--page`, `--page-size`, and `--project-dir`. Use `--home` or `RESEARCH4JAR_HOME` to override the global data directory.
For high-fanout retrieval commands (`find-class`, `find-method`, `list-packages`, and `search-symbol`), JSON responses include `has_more`; use it to continue paging instead of relying on an exact pre-count.

## Use from Cursor, Claude Code, or any MCP host

`research4jar mcp` runs a stdio MCP server exposing indexing and every query as tools, so coding agents call Research4Jar directly.

**Cursor** — add to `.cursor/mcp.json` (project) or `~/.cursor/mcp.json` (global):

```json
{
  "mcpServers": {
    "research4jar": { "command": "research4jar", "args": ["mcp"] }
  }
}
```

**Claude Code**:

```bash
claude mcp add research4jar -- research4jar mcp
```

Tools exposed: `check_environment`, `index_project` (auto-resolves the classpath via Maven/Gradle), `search_symbols`, `open_symbol`, `why_dependency`, `find_class`, `find_method`, `list_packages`, `find_config_properties`, `find_implementations`, `find_by_annotation`, `get_class`, `get_bean_definitions`, `explain_conditional`, `find_string`, `list_extension_points`. Each accepts an optional `project_dir`; by default the server searches upward from its working directory. For agents, the intended retrieval flow is broad search first (`search_symbols`), then expand one result (`open_symbol`) or explain why its jar is present (`why_dependency`).

Agents should call `check_environment` before `index_project` on a new machine; it returns the same missing-tool status, user install notes, agent install commands, and verification commands as `research4jar doctor --format json`.

For CLI-driven agents without MCP, `research4jar index` also appends usage guidance to the project's `CLAUDE.md`, so Claude Code picks the tool up with zero configuration.

## Shard registry: index without extracting

Shards are content-addressed (`<jar_sha256>@<extractor_version>`) and byte-deterministic, so they can be built once and distributed. A registry is just a static file tree — any object storage, GitHub Pages site, or internal HTTP server can host one:

```
registry.json                      extractor version, shard count, signed flag
v2/<jar_sha256>.db                 the shard
v2/<jar_sha256>.db.sha256          checksum sidecar (required)
v2/<jar_sha256>.db.sig             ed25519 signature (required for verifying clients)
```

Point `research4jar index` at a registry and missing shards download instead of extracting locally — on a large classpath the first index drops from minutes of JVM extraction to seconds of downloads. When the registry covers every jar, the session merge also runs in pure Go and **no JVM is needed at all**:

```bash
research4jar index --registry https://shards.example.com            # or RESEARCH4JAR_REGISTRY
research4jar index --registry ... --registry-pubkey <hex>           # require valid signatures
```

Downloads are verified against the checksum sidecar, the shard's embedded identity (`shard_meta.jar_sha256`), and — when a public key is configured — an ed25519 signature. Verification failures and registry misses both fall back to local extraction; the registry is an accelerator, never a correctness dependency.

Publish a registry from any machine's local cache:

```bash
research4jar registry keygen ~/.research4jar-signing.key               # prints the public key
research4jar registry export ./registry --sign-key ~/.research4jar-signing.key
# upload ./registry to any static host
```

Or seed one directly from Maven coordinates — download, index, and export in one step (this is how the official registry is produced; see `registry/spring-coordinates.txt` and the `registry-publish` workflow, which deploys to GitHub Pages):

```bash
research4jar registry seed ./registry --coordinates coords.txt --sign-key ~/.research4jar-signing.key
```

Enterprises can run the same command against an internal Maven repository (`--repo https://nexus.internal/repository/maven-public`) to pre-index private artifacts.

## Cache lifecycle

The global cache (`research4jar cache stats`) grows one shard per unique jar. Collect garbage with:

```bash
research4jar cache gc                         # stale extractor versions + orphan files
research4jar cache gc --max-size 5G           # also evict least-recently-used shards over 5 GiB
research4jar cache gc --max-age 30d           # also drop session databases idle for 30 days
research4jar cache gc --dry-run               # report without deleting
```

Sessions are always rebuilt from shards by the next `research4jar index`, so session GC is safe; evicted shards re-download or re-extract on demand.

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
- General Java retrieval: indexed class search, method search, package summaries, broad `search-symbol`, and `open-symbol`
- Maven dependency provenance: `research4jar index` captures `.research4jar/dependencies.json` for Maven projects and `why-dependency` explains direct/transitive dependency paths by coordinate, jar filename, or class FQN
- Maven/Gradle classpath auto-discovery (`research4jar index`) and an MCP stdio server (`research4jar mcp`)
- Shard registry: static-hostable export (`research4jar registry export`), verified download-instead-of-extract (`research4jar index --registry`), and ed25519 signing
- Pure-Go indexing for registry-covered classpaths: session merge, project pointer, and CLAUDE.md guidance without a JVM
- Cache lifecycle: usage stats, stale-version and orphan cleanup, LRU eviction, and session expiry (`research4jar cache stats|gc`)
- Linux, macOS, and Windows-compatible data paths; pure-Go SQLite querying without CGO

## Known Limitations

- `find-by-annotation` expands meta-annotations but does not merge `@AliasFor` attribute aliases; attributes are reported as written on the matched annotation.
- `find-string` is substring matching over extracted constants, not full-text search with ranking.
- `why-dependency` currently requires a Maven project indexed through `research4jar index`; Gradle dependency provenance is not captured yet.
- A JRE is only needed when at least one jar misses the registry (or no registry is configured); fully covered classpaths index in pure Go.
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
- M3: build-tool/classpath discovery ✅ (pulled forward) and distribution foundations ✅
- M4: host integrations and MCP ✅ (pulled forward)
- M5: shard lifecycle — registry export/download with signing, LRU, and garbage collection ✅

Next up: a hosted public registry of pre-indexed shards for popular Spring artifacts, and cross-platform packaged binaries via the release pipeline.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), and [SECURITY.md](SECURITY.md).

## License

Licensed under the [Apache License 2.0](LICENSE).
