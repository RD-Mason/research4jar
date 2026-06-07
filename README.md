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

## Build

```bash
make build
```

Artifacts:

- `indexer/build/install/springdep-index/bin/springdep-index`
- `build/bin/springdep`

## Quick Start

Index a directory, glob, or comma-separated list of dependency jars:

```bash
indexer/build/install/springdep-index/bin/springdep-index \
  --jars /path/to/dependency-jars \
  --project-dir /path/to/project
```

Run queries from the indexed project or pass `--project-dir` explicitly:

```bash
build/bin/springdep find-config-properties spring.datasource \
  --project-dir /path/to/project

build/bin/springdep find-implementations jakarta.servlet.Filter \
  --project-dir /path/to/project

build/bin/springdep find-by-annotation \
  org.springframework.boot.context.properties.ConfigurationProperties \
  --project-dir /path/to/project
```

All commands support `--format json|text`, `--page`, and `--page-size`. Use `--home` or `SPRINGDEP_HOME` to override the global data directory.

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
- Classes, direct interfaces, methods, direct class/method annotations, `@Bean` methods, Spring conditions, and string constants
- Deterministic, atomic, content-addressed shards with incremental cache hits
- Cross-jar direct implementation and direct-subclass lookup
- Direct class annotation lookup with structured annotation attributes
- Linux, macOS, and Windows-compatible data paths; pure-Go SQLite querying without CGO

## Known Limitations

- `find-implementations` matches the interfaces a class directly declares plus its direct superclass. It does not traverse parent-class chains or subinterfaces (transitive closure) yet.
- `find-by-annotation` matches only annotations directly present on a class. Querying `@Component` does not include classes marked only with `@Service`, `@Repository`, or `@Controller`.
- Meta-annotation traversal, alias resolution, FTS, broader query commands, build-tool classpath discovery, MCP integration, and shard distribution are planned work.
- `--fat-jar` is not implemented; extract `BOOT-INF/lib/*.jar` first.

## Verification

```bash
make test
make e2e
```

The suite includes generated cross-jar fixtures, fixed-version Spring golden jars, actuator bean/condition calibration, malformed inputs, unsupported class versions, deterministic shard comparisons, incremental cache hits, and concurrent indexers.

## Roadmap

- [M0](docs/M0.md): walking skeleton, metadata extraction, shards, session database, first query
- [M1](docs/M1.md): ASM extraction engine, cross-jar relationship queries, golden validation
- M2: query-time graph traversal, meta-annotations, FTS, and the broader command set
- M3: build-tool/classpath discovery and distribution foundations
- M4: host integrations and MCP
- M5: shard lifecycle, signing, download, LRU, and garbage collection

Release automation and cross-platform packaged binaries are planned after the core query surface stabilizes.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), and [SECURITY.md](SECURITY.md).

## License

Licensed under the [Apache License 2.0](LICENSE).
