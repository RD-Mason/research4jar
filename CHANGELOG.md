# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project intends to follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added (M5 shard lifecycle)

- Pure-Go index path: when the registry (plus local cache) covers every jar, `springdep index` merges the session database, writes the project pointer, and appends the CLAUDE.md guidance itself — the JVM indexer never starts and a JRE is no longer required on fully covered machines. Partial coverage falls back to the JVM indexer as before. The session layout version is stamped and drift-guarded, and the e2e suite proves the path by indexing with `--indexer /nonexistent` and asserting byte-identical CLAUDE.md output across both writers.

- Shard registry: `springdep registry export` publishes the local cache as a static file tree (`v<extractor>/<jar_sha256>.db` + `.sha256` sidecar + optional `.sig`) any HTTP host can serve; `springdep registry keygen` generates an ed25519 signing keypair.
- Registry-backed indexing: `springdep index --registry <url>` (or `SPRINGDEP_REGISTRY`) downloads missing shards instead of extracting them locally, verifying the checksum sidecar, the shard's embedded `shard_meta` identity, and — with `--registry-pubkey`/`SPRINGDEP_REGISTRY_PUBKEY` — an ed25519 signature. Misses and verification failures degrade to local extraction.
- Cache lifecycle: `springdep cache stats` reports shard/session usage; `springdep cache gc` removes stale-extractor-version shards and orphan files, evicts least-recently-used shards over `--max-size`, drops sessions idle past `--max-age`, and supports `--dry-run`. Cache hits now refresh `last_access_at` so LRU order reflects real use.
- Downloaded shards register in the manifest with `source='remote'` and their embedded Maven coordinate; a Go-side extractor-version constant is guarded against drift from `SpringDepVersions.kt` by test.
- End-to-end coverage: signed registry round-trip over a local HTTP server, prefetch-only indexing (zero local extraction), and cache GC.

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
