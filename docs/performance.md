# Performance checks

Run the latency probe against a built CLI and a local dependency classpath:

```bash
python3 tests/benchmark.py /absolute/path/research4jar-cli.jar \
  --jars /absolute/path/dependency-jars \
  --class-name org.objectweb.asm.ClassReader \
  --samples 30 --warmup 5 --output benchmark.json
```

`--jars` accepts the index command's directory, glob, or comma-separated list; use absolute paths. Choose a class that actually exists in that classpath. The probe fails if it cannot resolve the class, if a tool fails, or if a new source file is invisible after warming the source query cache. It builds a temporary index, creates 2,105 synthetic Java files, reports raw samples and P50/P95, and removes its temporary project afterward. It uses a 512 MiB heap and a long-lived MCP connection. Classpath resolution through Maven/Gradle and one-shot JVM startup are excluded from the query timings.

CI runs the probe on the installed core runtime classpath after e2e. The JSON report appears in the job log and at `build/benchmark.json` on the runner. Timings are observations rather than absolute pass/fail thresholds on shared runners. Unit/integration checks enforce the performance properties independently of CPU speed: unchanged sources are not reread, source edits invalidate answers, a source request does not block a daemon point lookup, MCP controls/queries remain responsive during builds, and cancellation releases processes and database connections.

## September 2026 local measurement

macOS ARM, GraalVM JDK 21.0.7, 12 dependency JARs / 24.41 MiB, 2,105 generated source files. The class query was `org.objectweb.asm.ClassReader`. The pre-change audit used 10 warm MCP samples; the optimized probe used 30 samples after five warmups. These small-fixture measurements diagnose overhead and are not thousand-JAR capacity claims.

| MCP query | Before P50 | After P50 | After P95 |
| --- | ---: | ---: | ---: |
| Find class | 4.57 ms | 4.27 ms | 4.91 ms |
| Missing symbol | 79.60 ms | 76.52 ms | 77.55 ms |
| Dependency precise, sources disabled | 1.39 ms | 1.25 ms | 2.70 ms |
| Dependency precise, default sources enabled | 53.30 ms | 8.86 ms | 10.83 ms |

The previous default source scan hit `file_budget`; the optimized scan covered the entire fixture and immediately found an added source file. Most of the source-query gain comes from reusing results after checking file metadata. The smaller changes in other rows should be treated as normal benchmark variation.

A separate fake-wrapper integration probe confirmed that MCP `ping` arrived in 3.38 ms while a build was active, compared with 2,250 ms behind a two-second wrapper in the audit. Cancelling the new build stopped its child process in 13.68 ms, suppressed the cancelled response, and allowed the next index request to finish. These are individual responsiveness probes, not percentile measurements.

## Resource bounds and semantics

- MCP has two ordinary-query workers, one source/detail worker, and one build worker; each queue holds at most 32 waiting calls. Full queues return a retryable tool error. Cancellation removes queued calls and interrupts running work; SQLite and build processes register cancellation actions. Progress is emitted only for a supplied token and at most ten times per second per call.
- Daemon response-slot and byte budgets are unchanged. Source cache writes and unpaged class expansions remain serialized; ordinary queries can use the other response slot.
- Source caches retain at most four project indexes, with 16 MiB of cached content and 2 MiB of query results per project. Inventories refresh on each call. Their metadata keys do not detect an in-place edit that preserves both size and modification time; restarting the host clears the cache. Incomplete or concurrently edited scans are never stored as complete query results.
- Source inventories cover up to 20,000 eligible files. Both signal tiers share one content pass and a 1.5-second cooperative budget; truncation is explicit. JAR `coverage` continues to describe JAR indexing, independently of source scan completeness.
- Build output is drained concurrently with the deadline. A bounded diagnostic tail retains Gradle classpath markers separately so verbose logs cannot silently discard early dependencies. Java 9+ cancellation stops discovered descendants; Java 8 can terminate the wrapper itself.
