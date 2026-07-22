# Research4Jar

[![CI](https://github.com/RD-Mason/research4jar/actions/workflows/ci.yml/badge.svg)](https://github.com/RD-Mason/research4jar/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**Give your coding agent X-ray vision into Java dependency jars — locally, in milliseconds.**

Large Java and Spring Boot applications hide critical behavior inside dependency jars: auto-configuration, conditional bean activation, configuration properties, SPI registrations, class hierarchies. Agents either guess at these from model memory or burn tokens re-reading bytecode. Research4Jar extracts the facts once into a local SQLite index and answers precise questions — *which jar owns this import? what conditions gate this auto-configuration? who implements this interface across all 300 jars?* — in milliseconds, with provenance and coverage attached to every answer.

Everything runs on your machine. No telemetry, no cloud calls, no accounts. The only runtime requirement is the Java 8+ JVM your project already uses.

> Early-stage software under active development. Data contracts and commands may still evolve before the first stable release.

## Quick start

Download `research4jar-cli.jar` from the [Releases page](https://github.com/RD-Mason/research4jar/releases) (one jar, every platform), or build from source with `./install.sh` (installs a `research4jar` launcher to `~/.local`).

```bash
cd your-maven-or-gradle-project
research4jar index          # resolves the classpath via mvnw/gradlew, indexes every jar
                            # (java -jar research4jar-cli.jar index works the same)

research4jar search-symbol DataSourceAutoConfiguration     # broad search: classes, methods,
                                                           # annotations, config keys, SPI, strings
research4jar dep precise 'import org.springframework.context.ApplicationContext;'
research4jar explain-conditional org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
research4jar get-source 'com.fasterxml.jackson.databind.ObjectMapper#readTree'   # read the real implementation
```

That one `index` run also wires up your agents (next section). Re-runs are incremental: unchanged jars hit the content-addressed cache, an unchanged classpath reuses its session instantly, and small dependency changes update the previous index by copy-on-write delta in seconds — even on thousand-jar classpaths.

## Agent integration — zero configuration

`research4jar index` appends usage guidance to the project's **`CLAUDE.md`, `AGENTS.md`, and `GEMINI.md`** (idempotently — one section per file, existing content untouched). Claude Code, Codex, Cursor, Copilot, Gemini CLI, and anything else that reads those conventions discovers the tool on its next session and self-serves from there, including re-indexing after dependency changes.

For MCP hosts, `research4jar mcp` runs a stdio server exposing indexing and every query as tools:

```bash
# Claude Code
claude mcp add research4jar -- research4jar mcp
```

```json
// Cursor — .cursor/mcp.json (project) or ~/.cursor/mcp.json (global)
{ "mcpServers": { "research4jar": { "command": "research4jar", "args": ["mcp"] } } }
```

The recommended agent flow: `project_status` (is there an index? what does it cover?) → `search_symbols` (broad) → `open_symbol` (expand one hit) → `dependency_precise` / `class_origin` for "which jar owns this and where does the project consume it" → `get_source` to read the implementation itself. `check_environment` mirrors `research4jar doctor --format json` for automated setup.

## What can I ask?

Start from the task, not the command:

| Task | Command |
| --- | --- |
| Is the project indexed? Did the classpath change since? | `status`, `status --check-classpath` |
| Index several repos/services in one go | `index-many --projects <dir1,dir2,...> [--concurrency N]` |
| Indexing is slow because of SNAPSHOT checks | `index --no-snapshot-updates` (Maven), `--build-arg -o` for full offline |
| Which jar/dependency is behind this import, class, or coordinate? | `dep precise '<import\|class\|coordinate\|jar>'` |
| Why is this dependency on the classpath at all? | `dep why '<coordinate\|jar\|class>'` |
| I only have a word or name fragment | `search-symbol '<text>'`, then `open-symbol '<fqn\|Class#method>'` |
| Read a dependency class's actual source | `get-source <fqn>` (local sources jar, else built-in decompiler) |
| Read just one method's implementation | `get-source 'Class#method'` — exact body slices, all overloads |
| Is this class shipped by several jars (version conflict)? | `index` warns on stderr; `get-source` notes every owner, `--in <coordinate\|jar>` pins one |
| Grep inside one dependency's sources | `search-source '<text>' --in <coordinate\|jar>` |
| Who implements this interface / extends this class? | `find-implementations <fqn>` (transitive by default) |
| What classes carry this annotation? | `find-by-annotation <fqn>` (meta-annotations expanded: `@Component` finds `@Service`) |
| What Spring config properties exist under a prefix? | `find-config-properties spring.datasource` |
| Under what conditions does this auto-configuration activate? | `explain-conditional <fqn>` |
| What beans of this type are defined, and where? | `get-bean-definitions <fqn>` |
| Everything indexed about one class | `get-class <fqn>` |
| Where does this string constant appear in bytecode? | `find-string '<text>'` |
| What SPI/extension points are registered? | `list-extension-points` |
| Plain class/method/package search | `find-class`, `find-method`, `list-packages` |

All query commands take `--format json|text` and `--project-dir`; search/list commands page with `--page`/`--page-size` (max 1000 rows per page) and report `has_more`. Every JSON response carries a `coverage` block (`jars_total` / `jars_indexed` / `jars_missing`) so an empty result is distinguishable from an unindexed jar — trust it when interpreting "not found".

## How it works

One Kotlin/JVM codebase behind one CLI, four layers:

1. **Extraction** — jar metadata and bytecode are read with ASM; jar code is never loaded or executed.
2. **Shards** — each jar becomes an immutable, content-addressed SQLite shard (`<jar_sha256>@<extractor_version>`), built once and cached globally.
3. **Session** — the shards matching a project's classpath merge into one session database. Symbol references stay unresolved until query time, so relationships cross jar boundaries.
4. **Query** — the CLI opens the session read-only (C SQLite via sqlite-jdbc) and returns JSON with provenance. Substring search runs on deduplicated trigram FTS indexes on large sessions and falls back to plain scans on small ones — whichever is faster, results byte-identical either way.

### Measured performance (v0.4.0, Apple Silicon)

| | 222 jars / 184 MB | 1,000 jars / 869 MB |
| --- | --- | --- |
| First index (cold, full extraction) | ~7.5 s | ~30 s |
| Re-index, nothing changed | ~0.3 s | ~0.4 s |
| Dependency source read, warm (`get-source Class#method`) | ~0.18 s | ~0.18 s (per-class, size-independent) |
| Re-index after swapping a few jars (delta) | ~1 s | ~2 s |
| Session size on disk | 277 MB | 1.5 GB |
| Warm query via daemon | 100–130 ms | 100–130 ms |
| One-shot query (no daemon) | ~0.45 s | ~0.45 s |
| Worst case: broad search with zero hits | ~1.4 s | ~1.6 s |
| Long-lived MCP host, point query | ~2 ms | ~2 ms |

Indexing runs under a 512 MiB default heap regardless of classpath size (stress-validated to 10,000 jars). The first CLI query starts a background daemon (TCP loopback, nonce/HMAC mutually authenticated, 30-minute idle exit) so subsequent one-shots skip JVM startup; `RESEARCH4JAR_NO_DAEMON=1` opts out, and MCP hosts never need it. The installed launcher additionally enables Class Data Sharing on JDK 19+ (probed once, cached, plain-`java` fallback everywhere else).

## Local-first by design

- **Zero network by default.** Indexing and querying never leave the machine. The only network path is the opt-in shard registry below, and it is off unless you configure it.
- **No telemetry, no accounts.** Nothing is collected, nothing phones home.
- **Untrusted-input safe.** Jars are parsed, never executed; sessions open read-only; the daemon binds loopback only and mutually authenticates both ends with per-endpoint HMAC secrets (0600 files) — a local impostor can neither serve your queries nor read your command lines.
- **Self-contained state.** Everything lives under one data directory (`RESEARCH4JAR_HOME`, platform default under your user profile) and is content-addressed, rebuildable, and garbage-collected automatically.

## Requirements

| To… | You need |
| --- | --- |
| Run Research4Jar | Any Java 8+ runtime (the JVM your project already builds with). `index` without `--jars` also wants the project's `mvnw`/`gradlew` or `mvn`/`gradle` on PATH |
| Build from source | JDK 17+ and `make` (the shipped CLI is still Java 8 bytecode) |
| Run the full verification suite | The above plus `jar`/`javac`, Python 3, and the `sqlite3` CLI |

`research4jar doctor [--source-build] [--format json]` checks the machine and prints exact install commands; agents can loop on `doctor --format json` until `"ok": true`.

## Cache lifecycle

The global cache grows one shard per unique jar and one session per classpath fingerprint; `research4jar cache stats` shows both. Maintenance is mostly automatic — every `index` run sweeps sessions unused for 30+ days (`RESEARCH4JAR_SESSION_MAX_AGE=7d|12h|off` to tune) and abandoned build temporaries. Manual control:

```bash
research4jar cache gc                 # stale extractor versions + orphan files
research4jar cache gc --max-size 5G   # also evict least-recently-used shards over 5 GiB
research4jar cache gc --max-age 30d   # also drop idle session databases
research4jar cache gc --dry-run       # report without deleting
```

Sessions always rebuild from shards on the next `index`, so session GC is safe; active queries hold a cross-process lease, so nothing is unlinked mid-read. Evicted shards re-extract (or re-download) on demand.

## Optional: a shard registry for your team

Shards are byte-deterministic, so they can be built once and shared over any static HTTP host — an internal Nexus box, object storage, an intranet file server. Point `index` at a registry and cold indexing turns into verified downloads plus a session merge:

```bash
research4jar index --registry https://shards.internal.example      # or RESEARCH4JAR_REGISTRY
research4jar index --registry ... --registry-pubkey <hex>          # require ed25519 signatures
```

Downloads verify against a checksum sidecar, the shard's embedded jar identity, and (when configured) an ed25519 signature; any miss or verification failure falls back to local extraction — the registry is an accelerator, never a correctness or availability dependency. Publish one from any machine's cache with `registry export`, or seed straight from Maven coordinates (including an internal repository via `--repo`) with `registry seed`. See `research4jar --help` for the exact flags and `registry keygen` for signing.

## Current capabilities

- Deep Spring facts: configuration metadata, `spring.factories`/auto-configuration imports, `@Bean` definitions, conditions, meta-annotation expansion
- General Java retrieval: classes, methods, packages, annotations, class hierarchies, SPI registrations, bytecode string constants
- Dependency source reading: `get-source` serves real sources from local Maven/Gradle caches (opt-in `--fetch` through your own Maven), falls back to bundled CFR decompilation with per-class caching, and slices exact method bodies (slice results cached by content hash); `search-source` greps inside one dependency's sources — responses declare `source_kind` so agents know fidelity
- Multi-version conflict alerting: `index` audits the classpath for classes shipped by more than one jar (hidden multi-version and shaded-bundle overlaps) and warns with the offending jar pairs; source reads on a conflicted class list every owning jar and support pinning one with `--in`
- Dependency provenance: which jar owns a symbol, which Maven dependency introduced it, and where the project's own sources consume it (`dep precise`, `dep why`; Maven captured by the CLI, Gradle natively by the build plugin)
- Build-tool integration tuned for onboarding: a first Maven index resolves the classpath and the provenance tree in ONE Maven run; multi-module reactors index without a prior `mvn install` (external jars only — sibling modules are your own source); `--no-snapshot-updates` and `--build-arg` control the underlying Maven/Gradle invocation; `index-many --projects a,b,c` indexes several projects in one process under a global concurrency cap with a shared extraction cache; the stats JSON breaks down `classpath_ms` / `extract_ms` / `provenance_ms` / `total_ms`
- Deterministic content-addressed shards, incremental re-indexing, copy-on-write session deltas, automatic cache hygiene
- CLI + MCP stdio server + Maven/Gradle build plugins (`research4jar:index` goal, `research4jarIndex` task) from one Java 8+ fat jar, on Linux/macOS/Windows

## Known limitations

- `find-by-annotation` reports attributes as written; `@AliasFor` aliases are not merged.
- `find-string` is substring matching, not ranked full-text search.
- Gradle dependency provenance requires the Gradle build plugin; the standalone CLI captures provenance for Maven only.
- Spring Boot fat jars are not unpacked automatically; extract `BOOT-INF/lib/*.jar` first.

## Verification

```bash
make test    # all module tests
make e2e     # golden end-to-end suite (fixtures, determinism, registry, daemon, MCP)
```

## Project history

[M0](docs/M0.md) walking skeleton → [M1](docs/M1.md) ASM extraction + cross-jar queries → M2 query-time graph traversal and the broad command set → M3/M4 classpath discovery and MCP → M5 shard lifecycle and registry → [M6](docs/M6.md) pure-Java consolidation (one Kotlin codebase, daemon, build plugins). Since then: packed-domain trigram search, incremental session deltas, and the hardened daemon protocol — see the [CHANGELOG](CHANGELOG.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), and [SECURITY.md](SECURITY.md).

## License

Licensed under the [Apache License 2.0](LICENSE).
