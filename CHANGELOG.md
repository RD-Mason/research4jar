# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project intends to follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added (M2 + distribution)

- Transitive `find-implementations`: query-time recursive traversal over superclass chains and subinterface graphs across all indexed jars (`--direct` restores declared-only matching).
- Meta-annotation expansion in `find-by-annotation`: querying `@Component` now returns `@Service`/`@Repository`/`@Controller` classes; results carry `matched_annotation` provenance (`--direct` opts out; `@AliasFor` merging is out of scope).
- New commands: `get-class`, `get-bean-definitions`, `explain-conditional`, `find-string`, `list-extension-points`.
- Session databases now merge `methods`, `bean_definitions`, `conditions`, `string_constants`, and method-target annotations, with a `session_meta` version stamp; pre-M2 sessions rebuild automatically on the next index run.
- `springdep index`: drives the JVM indexer directly and auto-resolves the runtime classpath via `mvnw`/`mvn` or `gradlew`/`gradle` when `--jars` is omitted.
- `springdep mcp`: stdio MCP server exposing indexing and all queries as tools for Cursor, Claude Code, and other MCP hosts.
- `make install` / `install.sh`: installs `springdep` and the indexer under a prefix (default `~/.local`) for use from any project.

### Added

- ASM-based extraction of classes, interfaces, methods, direct annotations, `@Bean` methods, Spring conditions, and string constants.
- Full `spring.factories`, auto-configuration imports, and Java services extraction.
- `find-implementations` and `find-by-annotation` commands with source provenance, pagination, and coverage.
- Cross-jar symbolic joins, parallel jar extraction, schema version 2, and extractor version 2.
- Fixed Spring golden jars, actuator runtime calibration, and cross-platform CI scaffolding.

### Changed

- Session databases now merge classes, direct interfaces, and annotations.
- M0 shards automatically age out of selection through the `@2` shard identity.

### Performance

- Jar hashing and cached-shard checksum validation now run in parallel across all cores instead of sequentially, cutting the warm-path cost of large classpaths.
- Unchanged classpaths reuse the existing fingerprint-addressed session database instead of rebuilding it on every run.
- Shard and session builds write through an in-memory journal with syncs deferred to the atomic commit, removing redundant journal and fsync I/O while keeping byte-deterministic output.
- Session databases ship `ANALYZE` statistics so the querier's planner picks indexed plans.
- `find-implementations` uses an index-backed `UNION` instead of an `OR` predicate, keeping plans robust on large sessions.
- Queries with no results skip opening the manifest database; manifest reads tolerate concurrent indexer writes via a busy timeout.
- SHA-256 hex encoding uses a lookup table instead of per-byte string formatting.

## [0.1.0] - 2026-06-07

### Added

- Kotlin indexer and pure-Go read-only querier.
- Content-addressed SQLite shards, manifest, project session database, and project pointer.
- Spring configuration metadata and auto-configuration list extraction.
- `find-config-properties`, deterministic writes, incremental cache hits, and malformed-jar handling.
