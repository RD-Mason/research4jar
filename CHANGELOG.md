# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project intends to follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- Session build is ~40% faster (3.4-3.6 s vs 4.2-7.1 s for a 222-jar, 184 MB classpath): the derived columns (`simple_name`, `package_name`, `symbol`) are computed inline in the merge `INSERT...SELECT` instead of a post-merge UPDATE pass that rewrote every row of the two largest tables, and `ANALYZE` samples row statistics (`analysis_limit`) instead of scanning the full session. Session content is unchanged (verified table-by-table); the file also shrinks a few percent from less rewrite fragmentation.

### Added

- Stale sessions are now reclaimed automatically: every `research4jar index` run removes session databases unused for more than 30 days (override with `RESEARCH4JAR_SESSION_MAX_AGE`, e.g. `7d`, `12h`, or `off`). Previously each classpath change stranded a multi-hundred-MB session that only a manual `cache gc --max-age` would delete. A session's mtime now tracks last use — the index reuse path and query engine refresh it (at most once a day on the query side) — so actively used sessions never age out, and a swept session rebuilds from cached shards in seconds.

## [0.1.0] - 2026-07-05

### Changed (M6 pure-Java consolidation)

- The entire toolchain is now one Kotlin/JVM codebase: the Go querier is retired and the single `research4jar` CLI (a Java 8+ fat jar) indexes, queries, and serves MCP. Query semantics, JSON shapes, flags, help texts, and exit codes are byte-compatible with the Go CLI (verified by side-by-side invocation and the full e2e suite driven through the JVM binary).
- Queries now run on C SQLite (sqlite-jdbc): broad substring scans are ~7x faster than the previous pure-Go engine.
- `search_symbols` became a view over the base tables and derived columns are computed set-based: the session database shrank ~64% (1.07 GB -> 387 MB on a 99-jar Spring Boot classpath) and cold indexing dropped 12.3 s -> ~5-8 s. search-symbol/find-class/find-method use index-backed fast paths first (typical broad search 960 ms -> ~30 ms end to end) with new prefix ranking tiers above the contains tier.
- The indexer JVM defaults to -Xmx512m (peak RSS 2.09 GB -> ~0.8 GB); the runtime baseline dropped from Java 11 to Java 8.
- `research4jar index` skips the `mvn dependency:tree` provenance run when the classpath fingerprint is unchanged.

### Added (M6)

- Maven plugin (`mvn dev.research4jar:research4jar-maven-plugin:index`) and Gradle plugin (`id("dev.research4jar")` -> `research4jarIndex`): zero-install onboarding through the build tool, classpath and dependency graph taken in-process from the build itself — **Gradle dependency provenance ships for the first time**.
- CLI daemon: query commands round-trip in ~90 ms against a warm background JVM (token-authenticated loopback, 30-minute idle exit, byte-identical output; `RESEARCH4JAR_NO_DAEMON=1` opts out).
- Maven Central publishing configuration for `research4jar-core`/`-cli`/`-gradle-plugin`/`-maven-plugin` (Central Portal; publication pending namespace verification).

### Removed (M6)

- The Go querier, goreleaser archives, and the Kotlin/Go dual-writer session contract (tagged `pre-m6-go` before removal).


### Added (general Java agent retrieval)

- General Java queries: `find-class`, `find-method`, `list-packages`, `search-symbol`, and `open-symbol` let agents search and expand ordinary Java dependency facts, not just Spring-specific facts.
- Maven dependency provenance: `research4jar index` now captures Maven `dependency:tree` output into `.research4jar/dependencies.json` when run in a Maven project, and `why-dependency <coordinate|jar|class>` explains direct/transitive dependency paths.
- MCP retrieval workflow: new `search_symbols`, `open_symbol`, `why_dependency`, `find_class`, `find_method`, and `list_packages` tools guide agents through search → expand → explain instead of reading jars blindly.
- The generated `CLAUDE.md` guidance now describes Java/Maven dependency fact retrieval while keeping the existing Spring-specific commands.

### Added (M5 shard lifecycle)

- `research4jar registry seed <dir> --coordinates <file>`: downloads Maven artifacts (Maven Central by default, `--repo` for internal repositories), indexes them, and exports the signed registry tree in one step. Download failures warn and skip so a partial seed still publishes.
- Official registry pipeline: `registry/spring-coordinates.txt` curates the seeded artifacts (Spring Boot 3.4/3.5 trains, framework, data, security, common companions — all coordinates verified against Maven Central) and the `registry-publish` workflow seeds and deploys the tree to GitHub Pages, signing with the `RESEARCH4JAR_SIGNING_KEY` secret when configured.
- Pure-Go index path: when the registry (plus local cache) covers every jar, `research4jar index` merges the session database, writes the project pointer, and appends the CLAUDE.md guidance itself — the JVM indexer never starts and a JRE is no longer required on fully covered machines. Partial coverage falls back to the JVM indexer as before. The session layout version is stamped and drift-guarded, and the e2e suite proves the path by indexing with `--indexer /nonexistent` and asserting byte-identical CLAUDE.md output across both writers.

- Shard registry: `research4jar registry export` publishes the local cache as a static file tree (`v<extractor>/<jar_sha256>.db` + `.sha256` sidecar + optional `.sig`) any HTTP host can serve; `research4jar registry keygen` generates an ed25519 signing keypair.
- Registry-backed indexing: `research4jar index --registry <url>` (or `RESEARCH4JAR_REGISTRY`) downloads missing shards instead of extracting them locally, verifying the checksum sidecar, the shard's embedded `shard_meta` identity, and — with `--registry-pubkey`/`RESEARCH4JAR_REGISTRY_PUBKEY` — an ed25519 signature. Misses and verification failures degrade to local extraction.
- Cache lifecycle: `research4jar cache stats` reports shard/session usage; `research4jar cache gc` removes stale-extractor-version shards and orphan files, evicts least-recently-used shards over `--max-size`, drops sessions idle past `--max-age`, and supports `--dry-run`. Cache hits now refresh `last_access_at` so LRU order reflects real use.
- Downloaded shards register in the manifest with `source='remote'` and their embedded Maven coordinate; a Go-side extractor-version constant is guarded against drift from `Research4JarVersions.kt` by test.
- End-to-end coverage: signed registry round-trip over a local HTTP server, prefetch-only indexing (zero local extraction), and cache GC.

### Added (M2 + distribution)

- Transitive `find-implementations`: query-time recursive traversal over superclass chains and subinterface graphs across all indexed jars (`--direct` restores declared-only matching).
- Meta-annotation expansion in `find-by-annotation`: querying `@Component` now returns `@Service`/`@Repository`/`@Controller` classes; results carry `matched_annotation` provenance (`--direct` opts out; `@AliasFor` merging is out of scope).
- New commands: `get-class`, `get-bean-definitions`, `explain-conditional`, `find-string`, `list-extension-points`.
- Session databases now merge `methods`, `bean_definitions`, `conditions`, `string_constants`, and method-target annotations, with a `session_meta` version stamp; pre-M2 sessions rebuild automatically on the next index run.
- `research4jar index`: drives the JVM indexer directly and auto-resolves the runtime classpath via `mvnw`/`mvn` or `gradlew`/`gradle` when `--jars` is omitted.
- `research4jar mcp`: stdio MCP server exposing indexing and all queries as tools for Cursor, Claude Code, and other MCP hosts.
- `make install` / `install.sh`: installs `research4jar` and the indexer under a prefix (default `~/.local`) for use from any project.

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
