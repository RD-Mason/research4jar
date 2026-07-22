# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project intends to follow [Semantic Versioning](https://semver.org/).

## [0.4.0] - 2026-07-23

### Added

- `research4jar index-many --projects <dir1,dir2,...> [--concurrency N]` indexes several projects in one process: build-tool classpath resolutions run in parallel under the global cap (default min(4, cores)), extraction then flows project-by-project through the shared in-process pipeline — already core-saturating and memory-gated, so one JVM's limits bound the whole batch — and the content-addressed shard cache extracts each distinct jar exactly once across all projects (verified live: the second project's jars all arrive as cache hits when the first already indexed them). Prints one JSON summary with per-project stats and timings; a failing project is reported in the summary and on stderr without stopping the others (exit 1 if any failed). `--no-snapshot-updates`/`--build-arg` apply to every resolution.

- Multi-module Maven reactors index without a prior `mvn install`. Previously `research4jar index` at a reactor root failed outright on any module depending on an uninstalled sibling ("Could not find artifact ...") — and even with everything installed, the classpath output file was silently overwritten per module, so the index covered only the LAST module's dependencies. Now one Maven run executes the `compile` phase (so Maven's reactor resolves sibling modules from their `target/classes` build output — no install, no packaging, no tests) with per-module relative output files, and the index is the union of every module's external jars; sibling modules surface as class directories and stay out of the index, since first-party module code is already visible to the agent as source. Dependency provenance merges every module's tree — each module a depth-0 root, shared externals keeping their shortest path — so `dep why` answers across modules (verified against a real uninstalled 4-module reactor with Maven 3.9.16: old build exit 1, new build indexes all external jars and explains each one's owning module). `status --check-classpath` uses the same reactor resolution, so its fingerprint now reflects all modules too.

- A first index of a Maven project now answers the runtime classpath AND the dependency provenance tree from ONE Maven run (`dependency:build-classpath` and `dependency:tree` as goals of the same invocation) instead of two separate Maven processes — halving JVM startups, dependency resolution, and any SNAPSHOT metadata checks on the slowest step of onboarding a project. Same-batch A/B against the previous build on a wrapper with 0.5 s simulated resolver latency: 2 invocations -> 1, first-index wall time 3.0-3.5 s -> 2.3 s, provenance file content identical. If the tree half of the merged run fails, the classpath half still indexes and provenance degrades to the same warning as before (never a second Maven process); unchanged re-runs keep resolving the classpath only and reusing provenance exactly as before.
- `research4jar index` and `research4jar status --check-classpath` accept `--no-snapshot-updates` (passes Maven's `--no-snapshot-updates` to the classpath and provenance runs; Maven only — SNAPSHOT metadata checks against remote repositories are the dominant first-index cost on SNAPSHOT-heavy projects) and repeatable `--build-arg <ARG>` (passed verbatim to the Maven/Gradle invocation: `-o`, `-Pprofile`, `-s settings.xml`, …). The MCP `index_project` and `project_status` tools expose the same as `no_snapshot_updates` and `build_args`.
- The `index` stats JSON now reports phase timings alongside the classic fields: `classpath_ms` (build-tool resolution; 0 with `--jars`), `extract_ms` (the extraction pipeline, same value as the pre-existing `duration_ms`), `provenance_ms`, and `total_ms` (the real end-to-end command time — `duration_ms` never included classpath resolution or provenance capture). The stats line is now printed after provenance capture completes so `total_ms` is truthful. The MCP `index_project` result carries the same numbers in a `timings` object.

## [0.3.0] - 2026-07-19

### Added

- Multi-version jar conflict alerting: `research4jar index` audits the session for classes shipped by more than one classpath jar (package-info/module-info excluded) and warns on stderr with the offending jar pairs and shared-class counts — multi-version pairs (guava 33 AND 16, three kotlin-reflect versions) and shaded bundles both surface; the full pair list is in the index JSON output as `class_conflicts` (omitted when empty). The audit is computed once per session fingerprint and cached beside the session file, so warm re-index re-warns at file-read cost (~0 ms; the one-time compute is ~90 ms on a 222-jar session). `get-source`/`search-source` on a conflicted class prepend a `MULTI-VERSION WARNING` note listing every owning jar and which one was served — chosen by an explicit `--in <coordinate|jar>` pin (new on `get-source`, also as `in` on the MCP `get_source` tool), else the single version the project's captured Maven resolution names, else stable index order. Pinning a jar that does not ship the class fails loudly.
- Method-slice cache: `get-source Class#method` results are cached under `<data home>/sources/slices/` keyed by the source text's own hash plus the query, so repeated reads skip the JavaParser re-parse; a changed source (new jar version, re-decompile) misses cleanly and corrupted entries are recomputed. Warm daemon method reads drop 238 -> 177 ms (669 ms pre-daemon: 3.8x cumulative), big decompiled files (Guava `ImmutableList`) 661 -> 177 ms, and warm one-shot `RESEARCH4JAR_NO_DAEMON` reads 669 -> 467 ms — outputs byte-identical to baseline apart from the new conflict note.
- Source-read fidelity hardening for `get-source`/`search-source`: method slicing now includes enum constant-body implementations (an abstract-per-constant enum previously sliced to the abstract declaration alone) and record constructors in both compact and canonical form; sources-jar lookup also probes `.scala`/`.groovy` entries and maps Kotlin file classes (`FooKt` -> `Foo.kt`), so real sources are served where decompilation was silently used before; `search-source` reports files it skipped (over 5 MiB, or binary-looking) in a `note` field instead of silently omitting their matches, and rejects multi-line search text loudly (matching is per-line, so it could never hit). Unmatched slicing continues to fall back to the whole file with a note — never a partial result.
- `get-source` and `search-source` are served by the warm daemon (`--fetch` invocations stay cold: they shell out to the project's Maven and must observe the invocation's environment). Same-batch alternating A/B on the real 222-jar fixture: warm method read 669 -> 238 ms (2.8x), warm source search 448 -> 179 ms (2.5x), cold and `RESEARCH4JAR_NO_DAEMON` paths at parity, outputs byte-identical.
- `research4jar index` upgrades this tool's agent-guidance section in `CLAUDE.md`/`AGENTS.md`/`GEMINI.md` in place when it byte-matches a prior published version, so existing projects gain newly documented commands; user-edited sections are never rewritten.
- Dependency source retrieval: `research4jar get-source <FQN|Class#method>` prints a dependency class's source — or just one method's body (all overloads, with signatures and file line ranges) — and `research4jar search-source <text> --in <coordinate|jar|class-FQN>` substring-searches one jar's sources with file/line hits and the standard `--page/--page-size` semantics. Acquisition is local-first: the owning jar resolves through the existing index, `<artifact>-<version>-sources.jar` is looked up next to the indexed jar and in the local `~/.m2/repository` and `$GRADLE_USER_HOME/caches/modules-2/files-2.1` caches, `--fetch` (explicit opt-in, never default) downloads it via the project's own `mvn dependency:get`, and otherwise the class is decompiled with CFR (per class on demand; whole-jar once for search, with a stderr progress line). Responses state provenance via `source_kind: "sources-jar" | "decompiled"`. Inner classes (`Outer$Inner`, also accepted as `Outer.Inner`) map to the outer source file on both paths. Located sources jars and decompiled output are cached under `<data home>/sources/` keyed by the jar's content hash; that cache is not yet part of `cache stats`/`cache gc` orphan accounting (follow-up). Matching MCP tools `get_source` and `search_source` are exposed; both commands are served by the warm daemon (oversized responses fall back to the cold path automatically), except with `--fetch`, which shells out to Maven and must observe the invocation's environment. New runtime dependencies: `org.benf:cfr:0.152` (MIT) and `com.github.javaparser:javaparser-core:3.25.10` (Apache-2.0), both Java 8 compatible and loaded lazily only inside the source commands.

## [0.2.0] - 2026-07-18

- The class/method search accelerators are size-gated: sessions under ~1M method rows (roughly 300 jars) skip building classes_fts, the method-text domains, and the descriptor index — the legacy scans they replace cost only tens-to-hundreds of milliseconds there, and every query path falls back per-tier when the structures are absent. Combined with the always-on packed string domain, a small session now builds faster AND smaller than the pre-accelerator baseline (222-jar rebuild 3.2 vs 4.0s, session 277 vs 475MB) with byte-identical query output; big sessions keep every accelerator unchanged. The schema contract accepts both shapes and treats partial accelerator presence as corruption.
- Trigram search text is deduplicated into packed value domains (method names, method descriptors, string values — distinct-value sets saturate: a 1000-jar classpath has 29x more method rows than distinct names). The former per-row FTS shadows tokenized every row; the domains tokenize each distinct value once, packed 256 per FTS row with exact member re-verification, reversing through existing name/value indexes plus one new descriptor index. A 1000-jar session rebuild returns to pre-shadow parity (~16.8s, from ~32s with per-row shadows) while every query win and byte-identical output is preserved; sessions shrink further (222-jar: 464 -> ~380 MB vs the pre-shadow layout).
- Per-invocation overhead audit: the daemon's build identity (a walk over every fat-jar zip entry) is computed lazily behind cheap argv/env short-circuits instead of at class-load; the leased session connection is a compile-time delegate instead of a dynamic proxy; the daemon class graph stays unloaded under RESEARCH4JAR_NO_DAEMON; lease classes prefetch on a background thread; find-string's density probe doubles as the exact result total when it stops below the threshold. `research4jar version` and warm one-shot controls now measure at parity with the pre-takeover baseline (~92ms and ~±5ms respectively).

### Changed

- At an intermediate session-layout stage, session build became ~40% faster (3.4-3.6 s vs 4.2-7.1 s for a 222-jar, 184 MB classpath): class-name projections are computed inline in the merge `INSERT...SELECT` instead of a post-merge UPDATE pass that rewrote the largest table, and `ANALYZE` samples row statistics (`analysis_limit`) instead of scanning the full session. Session content is unchanged (verified table-by-table); the file also shrinks a few percent from less rewrite fragmentation. This measurement predates the full v8 trigram/range/derived-symbol structures and is not directly comparable with the final full-feature v8 result below.
- Every sqlite-touching invocation is ~220 ms faster on macOS: the CLI pins the sqlite-jdbc native library to a stable file under the data home (`<home>/lib/`) instead of letting the stock loader extract it to a fresh random temp path per process — each new path re-paid Gatekeeper validation. Falls back to the stock loader on any failure.
- `search-symbol` no longer pays a whole-view scan when the indexed tiers underfill a page (its worst case, ~1.35 s of pure query time on a 222-jar session): underfilled pages now cascade through per-kind contains scans that stop as soon as the page is assembled. Pages are provably identical to the previous single-query ordering (the old SQL is kept as the test oracle and a randomized parity test compares every page).
- Cold indexing streams the session merge while extraction is still running (extraction is submitted in sorted-shardId order, largest jars first, and the merge consumes shards as they land): 222-jar cold index ~7.4-7.8 s -> ~6.2 s end to end together with the other fixes. Sessions stay byte-identical to the non-streamed path (same sha256).
- Warm re-indexing drops to ~0.27 s of pipeline time (from ~0.7 s): shard-cache validation now resolves all classpath jars through one manifest connection instead of one JDBC connection per jar.
- The installed launcher enables Class Data Sharing opportunistically: it probes the JVM once for `-XX:+AutoCreateSharedArchive` (JDK 19+), caches the result keyed by JVM identity and CLI version, and lets the archive build itself on first use — measured ~30% off one-shot queries; JDK 8 and read-only homes fall back to plain `java` cleanly.
- Class, method, symbol, and string substring searches now choose between trigram FTS5 and the legacy scan from a capped density probe. Selective terms keep the indexed path, while dense posting lists avoid FTS rewrite/sort overhead; the route is performance-only and every page remains oracle-identical. On the synthetic mega-class case, method-owned string detail lookup fell from 1242 ms to 8 ms, and dense method search fell from 535 ms to 372 ms.
- The method trigram shadow indexes compact method names plus descriptors instead of repeating every 75-byte average `owner#name` symbol; owner matches reuse `classes_fts`, and safe single-`#` terms use an indexed owner-suffix/name-prefix intersection for delimiter-spanning matches. On the real 222-jar session this cut the shadow from 65.68 to 35.63 MiB and its build from 5.64 to 2.75 seconds.
- Method symbols are now projected from the owning class FQN plus compact method name instead of storing and indexing the repeated `owner#name` string. A one-bit `owner_resolved` value preserves NULL-name behavior for malformed orphan rows. A vacuumed A/B isolated 110.72 MiB of savings (56.89 MiB symbol index + 53.83 MiB table payload; the marker costs 0.34 MiB). Together with compact ordered source-shard keys, the final 222-jar session build measured 10.37 -> 6.72 seconds and 528.3 -> 297.56 MiB, with 222.7 MiB peak RSS.
- `search-symbol` page 1 now walks 15 mutually-exclusive score tiers and stops after `page_size+1`, instead of sorting a mega-UNION containing every lower-ranked method/string prefix. Deep pages use hits-driven indexed slices rather than materializing the entire preceding prefix or reverse-scanning methods. On the final real session, installed-launcher warm typical searches hold ~100–130 ms, a no-hit search is ~340–370 ms, and the 100000-result-window boundary page dropped from ~1.16 to ~0.40–0.43 seconds; randomized oracle tests preserve every row and page boundary.
- The CLI daemon now uses a build-identified `R4JD3` protocol and one atomically published, generation-tagged endpoint. A nonce-based HMAC challenge/response mutually authenticates both peers without sending the endpoint token over the socket or exposing cwd/argv to an impersonating listener. Bounded connection workers keep authentication responsive during a slow query; cross-process endpoint/start locks close duplicate-start, torn-read, stale-cleanup, and delayed-starter races. Total request/write deadlines plus strict request, frame, per-response, aggregate-response, and server-capture budgets bound slow or oversized peers; response bytes are committed only after a valid terminal frame, so a broken daemon can fall back cold without duplicate output. The server independently enforces the read-only daemon command/page-size allowlist. Custom homes retain isolated warm daemons; `status --check-classpath` stays cold to inherit the invoking shell's build-tool environment.
- CLI and registry JSON encoding now streams through Jackson directly into the destination stream instead of first materializing a second full response string. The daemon's shared bounded capture therefore applies while encoding, and MCP avoids an additional outer JSON-RPC copy.

- Small classpath changes no longer rebuild the whole session: when the shard diff is at most 25% of the new set, the previous session is cloned (copy-on-write via cp -c/--reflink where the filesystem supports it — a 20 GB session clones in milliseconds and shares unmodified blocks) and updated in place through per-shard id-range deletes and the ordinary merge for additions. Measured swaps of 3 jars: 1000-jar classpath 16.8 s -> ~0.8-2.2 s; 10000-jar classpath 192 s -> 8.9 s. Sessions built by different paths are logically equivalent (proven by canonicalized-dump tests); any delta failure falls back to a full rebuild.
- Consecutive delta sessions automatically rebase through a full build after eight generations. This bounds FTS segment/page fragmentation observed in long update chains while preserving the normal delta speedup.
- `find-string` runs on an FTS5 trigram index (external-content, kept in sync incrementally on the delta path and rebuilt once on full builds): selective terms of 3+ codepoints without LIKE wildcards are index-served with byte-identical results to the previous scan (oracle parity test); the superlinear scan component is gone — 1000-jar wall time 928 -> 433 ms, flat through 10000 jars. Session size cost ~+1.5%.
- The session layout version is now 9 (adds shard/range bookkeeping, delta depth, packed-domain trigram search structures, a method-owner string index, compact ordered INTEGER source-shard keys, and derived method symbols); existing sessions rebuild on first index.

### Fixed

- Indexing a large classpath no longer overflows the default 512 MB heap: several big jars extracting concurrently (the largest-first schedule clusters them at the front) each hold a multi-hundred-MB transient model. Extraction is now gated by an in-flight jar-bytes budget sized to a quarter of the heap — big jars serialize, small jars keep full parallelism. Found by a 1000-jar / 869 MB stress test (previously OOMed; now completes cold in ~26 s at -Xmx512m with ~217 MB steady RSS, and indexed query latency stays flat vs a 222-jar classpath).
- Session reuse validates the complete schema contract (tables, columns, indexes, FTS definitions, metadata, and the exact `search_symbols` view) before trusting a content-addressed file, so a partial/corrupt database is rebuilt instead of failing later in a query.
- Query and index readers now hold a fair cross-process session lease for their full connection/copy lifetime; GC takes the exclusive side before rechecking mtime and deleting. A stable per-directory lifecycle lock lets GC reclaim each deleted session's lock sidecars without inode-ABA races, while reference-counted local state prevents heap growth under session churn. This closes the touch/open/delete race that could leave a project pointer naming an unlinked session. `0`, `0d`, and `0h` consistently disable completed-session expiry, while abandoned build temporaries are still reclaimed.
- Exact method terms containing an embedded NUL on either side of `Class#method` now use full projected equality instead of SQLite's NUL-truncated string functions, preserving exact ranking and pagination for malformed-but-indexable symbols.
- Pagination is bounded to 1000 rows per page and a 100000-result window, with checked `Long` offset arithmetic in the core, CLI, and MCP schemas. Extreme values can no longer overflow into SQLite's negative (unlimited) `LIMIT` behavior or materialize an unbounded search-symbol prefix.
- MCP advertises only implemented options, negotiates only its supported protocol version, reports the real CLI version, exposes `home` consistently, and supports classpath freshness checks. Command-family and query-specific help now describes only options the command consumes; the legacy no-op `--indexer` flag is labeled accurately.

### Added

- Stale sessions are now reclaimed automatically: every `research4jar index` run removes session databases unused for more than 30 days (override with `RESEARCH4JAR_SESSION_MAX_AGE`, e.g. `7d`, `12h`, or `off`). Previously each classpath change stranded a multi-hundred-MB session that only a manual `cache gc --max-age` would delete. A session's mtime now tracks last use — the index reuse path and query engine refresh it (throttled to once per five minutes on the query side) — so actively used sessions never age out, and a swept session rebuilds from cached shards in seconds.
- Abandoned hidden session-build temporaries older than the safety window are reported as cache orphans and reclaimed by both automatic sweeping and `cache gc`; recent build leases and unrelated visible `.tmp` files are preserved.
- Indexing now writes the agent usage guidance to `AGENTS.md` and `GEMINI.md` alongside `CLAUDE.md` (idempotent in each file), so Codex, Cursor, Copilot, Gemini CLI, and other agents that follow those conventions discover the tool with zero configuration, not just Claude Code.

## [0.1.0] - 2026-07-05

### Changed (M6 pure-Java consolidation)

- The entire toolchain is now one Kotlin/JVM codebase: the Go querier is retired and the single `research4jar` CLI (a Java 8+ fat jar) indexes, queries, and serves MCP. Query semantics, JSON shapes, flags, help texts, and exit codes are byte-compatible with the Go CLI (verified by side-by-side invocation and the full e2e suite driven through the JVM binary).
- Queries now run on C SQLite (sqlite-jdbc): broad substring scans are ~7x faster than the previous pure-Go engine.
- `search_symbols` became a view over the base tables and derived columns are computed set-based: the session database shrank ~64% (1.07 GB -> 387 MB on a 99-jar Spring Boot classpath) and cold indexing dropped 12.3 s -> ~5-8 s. search-symbol/find-class/find-method use index-backed fast paths first (typical broad search 960 ms -> ~30 ms end to end) with new prefix ranking tiers above the contains tier.
- The indexer JVM defaults to -Xmx512m (peak RSS 2.09 GB -> ~0.8 GB); the runtime baseline dropped from Java 11 to Java 8.
- `research4jar index` skips the `mvn dependency:tree` provenance run when the classpath fingerprint is unchanged.

### Added (M6)

- Maven plugin (`mvn dev.research4jar:research4jar-maven-plugin:index`) and Gradle plugin (`id("dev.research4jar")` -> `research4jarIndex`): zero-install onboarding through the build tool, classpath and dependency graph taken in-process from the build itself — **Gradle dependency provenance ships for the first time**.
- CLI daemon: typical query commands round-trip in ~100–130 ms against a warm background JVM (token-authenticated loopback, 30-minute idle exit, byte-identical output; `RESEARCH4JAR_NO_DAEMON=1` opts out).
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
- Historical M5 registry fast path: the former Go CLI could merge a fully covered classpath without starting the separate JVM indexer. M6 replaced both writers with the single Java 8+ Kotlin/JVM CLI. The current full-coverage path still builds the session, project pointer, and agent guidance from cached/downloaded shards without local JAR extraction or a separate indexer process; partial coverage locally extracts only cache/registry misses.

- Shard registry: `research4jar registry export` publishes the local cache as a static file tree (`v<extractor>/<jar_sha256>.db` + `.sha256` sidecar + optional `.sig`) any HTTP host can serve; `research4jar registry keygen` generates an ed25519 signing keypair.
- Registry-backed indexing: `research4jar index --registry <url>` (or `RESEARCH4JAR_REGISTRY`) downloads missing shards instead of extracting them locally, verifying the checksum sidecar, the shard's embedded `shard_meta` identity, and — with `--registry-pubkey`/`RESEARCH4JAR_REGISTRY_PUBKEY` — an ed25519 signature. Misses and verification failures degrade to local extraction.
- Cache lifecycle: `research4jar cache stats` reports shard/session usage; `research4jar cache gc` removes stale-extractor-version shards and orphan files, evicts least-recently-used shards over `--max-size`, drops sessions idle past `--max-age`, and supports `--dry-run`. Cache hits now refresh `last_access_at` so LRU order reflects real use.
- Downloaded shards register in the manifest with `source='remote'` and their embedded Maven coordinate. During M5 the former Go querier mirrored the extractor-version constant and tested it against `Research4JarVersions.kt`; M6 removed that duplicate constant with the Go codebase.
- End-to-end coverage: signed registry round-trip over a local HTTP server, prefetch-only indexing (zero local extraction), and cache GC.

### Added (M2 + distribution)

- Transitive `find-implementations`: query-time recursive traversal over superclass chains and subinterface graphs across all indexed jars (`--direct` restores declared-only matching).
- Meta-annotation expansion in `find-by-annotation`: querying `@Component` now returns `@Service`/`@Repository`/`@Controller` classes; results carry `matched_annotation` provenance (`--direct` opts out; `@AliasFor` merging is out of scope).
- New commands: `get-class`, `get-bean-definitions`, `explain-conditional`, `find-string`, `list-extension-points`.
- Session databases now merge `methods`, `bean_definitions`, `conditions`, `string_constants`, and method-target annotations, with a `session_meta` version stamp; pre-M2 sessions rebuild automatically on the next index run.
- `research4jar index`: runs the indexing pipeline and auto-resolves the runtime classpath via `mvnw`/`mvn` or `gradlew`/`gradle` when `--jars` is omitted.
- `research4jar mcp`: stdio MCP server exposing indexing and all queries as tools for Cursor, Claude Code, and other MCP hosts.
- `make install` / `install.sh`: installs the `research4jar` CLI under a prefix (default `~/.local`) for use from any project.

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

## Pre-0.1.0 development snapshot — 2026-06-07

### Added

- Kotlin indexer and pure-Go read-only querier.
- Content-addressed SQLite shards, manifest, project session database, and project pointer.
- Spring configuration metadata and auto-configuration list extraction.
- `find-config-properties`, deterministic writes, incremental cache hits, and malformed-jar handling.
