package dev.research4jar.cli

import dev.research4jar.query.MAX_PAGE_SIZE
import dev.research4jar.query.MAX_RESULT_WINDOW
import java.io.PrintStream

/**
 * Human-facing command help. Command-specific blocks intentionally advertise
 * only options that the corresponding parser accepts.
 */

internal fun printHelp(out: PrintStream) {
    out.println(
        """Usage:
  research4jar index [--jars <DIR|GLOB|LIST>] [options]   Index a project's dependency jars.
                                                       Without --jars the classpath is resolved
                                                       via Maven/Gradle (wrapper preferred).
  research4jar index-many --projects <DIR1,DIR2,...>      Index several projects in one process:
                     [--concurrency <N>] [options]     parallel classpath resolution under a
                                                       global cap, shared jar extraction cache.
  research4jar mcp                                        Run as an MCP stdio server (for Cursor,
                                                       Claude Code, and other MCP hosts).
  research4jar doctor [--source-build]                    Check required runtime/build tools and
                     [--format json|text]              print install guidance for users/agents.
  research4jar status [--format json|text]                Show project index, coverage, session,
                     [--check-classpath]               optional classpath freshness check,
                                                       manifest, and dependency provenance status.
  research4jar cache stats                                Cache usage: shards, sessions, orphans.
  research4jar cache gc [--max-size <N[K|M|G]>]           Collect garbage: stale extractor versions
                     [--max-age <N[d|h]>] [--dry-run]  and orphans always; LRU shards over the
                                                       size budget; sessions past the age limit.
  research4jar registry export <DIR> [--sign-key <PATH>]  Export local shards as a static registry
                                                       tree (any HTTP host can serve it).
  research4jar registry seed <DIR> --coordinates <FILE>   Download Maven artifacts, index them, and
                     [--repo <URL>] [--sign-key <PATH>] export the registry tree in one step.
  research4jar registry keygen <PATH>                     Generate an ed25519 signing keypair.

  research4jar dep precise <IMPORT|CLASS|COORD|JAR>       Resolve an import/class/jar to source jar,
                                                       dependency path, and source usages.
  research4jar dep why <COORD|JAR|CLASS>                  Explain why a Maven dependency is present.
  research4jar artifact <COORD|ARTIFACT|JAR>              Explicit artifact/jar lookup.
  research4jar class <NAME|FQN>                           Resolve class to source jar, dependency
                                                       path, and source usages.
  research4jar method <NAME|CLASS#METHOD>                 Explicit method lookup alias.

  research4jar find-config-properties <PREFIX>            Config properties under a prefix.
  research4jar find-implementations <FQN> [--direct]      Classes implementing/extending FQN.
                                                       Transitive by default.
  research4jar find-by-annotation <FQN> [--direct]        Classes annotated by FQN. Expands
                                                       meta-annotations by default.
  research4jar get-class <FQN>                            Full stored facts for one class.
  research4jar get-bean-definitions <FQN>                 @Bean definitions by type or config class.
  research4jar explain-conditional <FQN>                  Class- and method-level conditions.
  research4jar find-string <TEXT>                         Substring search over string constants.
  research4jar list-extension-points [KEY|MECHANISM]      SPI registrations summary or detail.
  research4jar find-class <NAME|PATTERN>                  Find classes by simple name, FQN, prefix,
                                                       or substring.
  research4jar find-method <NAME|PATTERN>                 Find methods by name or Class#method.
  research4jar list-packages [PREFIX]                     List packages grouped by source jar.
  research4jar search-symbol <TEXT>                       Search classes, methods, annotations,
                                                       SPI, config properties, and strings.
  research4jar open-symbol <FQN|CLASS#METHOD>             Expand a class or method search result.
  research4jar why-dependency <COORD|JAR|CLASS>           Explain why a Maven dependency is present.
  research4jar get-source <FQN|CLASS#METHOD>              Read a dependency class's source, or one
                                                       method's body. Local sources jar first,
                                                       CFR decompilation as fallback.
  research4jar search-source <TEXT> --in <COORD|JAR|FQN>  Substring-search one dependency jar's
                                                       sources (sources jar or decompiled).

Common workflows:
  First run:           research4jar doctor && research4jar index && research4jar status
  Freshness check:     research4jar status --check-classpath
  Import/jar origin:   research4jar dep precise 'import org.example.Type;'
  Class origin only:   research4jar class DataSourceAutoConfiguration
  Broad exploration:   research4jar search-symbol DataSourceAutoConfiguration
                       research4jar open-symbol org.example.Type
  Dependency path:     research4jar dep why org.springframework.boot:spring-boot-autoconfigure
  Read library source: research4jar get-source org.example.Type#method

Options:
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --format json|text    Output format (queries: json; doctor/status: text).
  --page <N>            Result page (default: 1; result window capped at $MAX_RESULT_WINDOW).
  --page-size <M>       Results per page (default: 20; maximum $MAX_PAGE_SIZE).
  --home <DIR>          Override Research4Jar home (manifest lookup; index target).
  --direct              Disable transitive/meta-annotation expansion.
  --no-source-grep      (dep precise/artifact/class) Skip bounded source/build-file usage search.
  --check-classpath     (status) Resolve the current runtime classpath and compare
                        its fingerprint with the last index.
  --indexer <PATH>      (index) Accepted for legacy compatibility; indexing is in-process.
  --registry <URL>      (index) Shard registry base URL; missing shards download
                        instead of extracting locally. Env: RESEARCH4JAR_REGISTRY.
  --registry-pubkey <H> (index) Hex ed25519 key; downloaded shards must carry a
                        valid signature. Env: RESEARCH4JAR_REGISTRY_PUBKEY.
  --version             Print the research4jar version.
  -h, --help            Show this help.

Notes:
  find-by-annotation expands meta-annotations (e.g. @Component finds @Service classes)
  but does not merge @AliasFor attribute aliases. find-implementations walks declared
  extends/implements chains across all indexed jars.""",
    )
}

internal fun printStatusHelp(out: PrintStream) {
    out.println(
        """Usage:
  research4jar status [--format json|text] [--project-dir <PATH>] [--home <DIR>] [--check-classpath]

Report whether the current project is indexed, where the project pointer and
session database are, jar coverage, manifest availability, and Maven dependency
provenance status. With --check-classpath, resolve the current Maven/Gradle
runtime classpath and compare its fingerprint with the last index.

Options:
  --format json|text     Output format (default: text).
  --project-dir <PATH>   Project root. Defaults to searching upward from cwd.
  --home <DIR>           Override Research4Jar home for manifest lookup.
  --check-classpath      Check whether the current runtime classpath fingerprint
                         still matches the indexed fingerprint.
  --no-snapshot-updates  Skip Maven SNAPSHOT metadata updates during the
                         --check-classpath resolution (Maven only).
  --build-arg <ARG>      Extra argument passed verbatim to the build tool during
                         the --check-classpath resolution. Repeatable.""",
    )
}

internal fun printDepHelp(out: PrintStream) {
    out.println(
        """Usage:
  research4jar dep precise <IMPORT|CLASS|COORD|JAR> [options]
  research4jar dep why <COORD|JAR|CLASS> [options]

Dependency tools answer where a dependency symbol comes from and why the jar is
present. Use "dep precise" for import/class/jar origin plus source usages; use
"dep why" for Maven dependency paths.

Options:
  --format json|text    Output format (default: json).
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --home <DIR>          Override Research4Jar home for manifest lookup.
  --page-size <M>       Max origins/source usage rows for dep precise (default 20).
  --no-source-grep      Skip bounded source/build-file usage search.""",
    )
}

internal fun printDepSubcommandHelp(subcommand: String, out: PrintStream) {
    when (subcommand) {
        "precise", "origin", "resolve" -> out.println(
            """Usage:
  research4jar dep precise <IMPORT|CLASS|COORD|JAR> [options]

Resolve an import line, class FQN/simple name, Class#method, Maven coordinate,
artifact id, or jar filename to the owning jar. The result includes dependency
provenance when available and bounded source/build-file usages.

Options:
  --format json|text    Output format (default: json).
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --home <DIR>          Override Research4Jar home for manifest lookup.
  --page-size <M>       Max origins/source usage rows to return (default 20).
  --no-source-grep      Skip bounded source/build-file usage search.""",
        )

        "why" -> out.println(
            """Usage:
  research4jar dep why <COORD|JAR|CLASS> [options]

Explain why a Maven dependency jar is present. Accepts group:artifact,
group:artifact:version, artifact id, jar filename, or a class FQN.

Options:
  --format json|text    Output format (default: json).
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --home <DIR>          Override Research4Jar home for manifest lookup.""",
        )

        else -> printDepHelp(out)
    }
}

internal fun printArtifactHelp(out: PrintStream) {
    out.println(
        """Usage:
  research4jar artifact <COORD|ARTIFACT|JAR> [options]

Resolve a Maven coordinate, artifact id, or jar filename to the indexed jar and
dependency provenance. Source/build-file usages are included by default.

Options:
  --format json|text    Output format (default: json).
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --home <DIR>          Override Research4Jar home for manifest lookup.
  --page-size <M>       Max origins/source usage rows to return (default 20).
  --no-source-grep      Skip bounded source/build-file usage search.""",
    )
}

internal fun printClassHelp(out: PrintStream) {
    out.println(
        """Usage:
  research4jar class <NAME|FQN> [options]

Resolve a class simple name or fully-qualified name to the owning jar,
dependency provenance, and bounded source/build-file usages. Use find-class for
fuzzy class search.

Options:
  --format json|text    Output format (default: json).
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --home <DIR>          Override Research4Jar home for manifest lookup.
  --page-size <M>       Max origins/source usage rows to return (default 20).
  --no-source-grep      Skip bounded source/build-file usage search.""",
    )
}

internal fun printMethodHelp(out: PrintStream) {
    out.println(
        """Usage:
  research4jar method <NAME|CLASS#METHOD> [options]

Find methods by method name, substring, or Class#method. This is a short alias
for find-method.

Options:
  --format json|text    Output format (default: json).
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --home <DIR>          Override Research4Jar home for manifest lookup.
  --page <N>            Result page (default 1).
  --page-size <M>       Results per page (default 20).""",
    )
}

internal fun printIndexHelp(out: PrintStream) {
    out.println(
        """Usage:
  research4jar index [--jars <DIR|GLOB|LIST>] [options]

Resolve a project's runtime classpath (or use explicit jars), extract missing
shards, and build/reuse its query session.

Options:
  --jars <VALUE>          Jar directory, glob, or comma-separated paths.
  --project-dir <PATH>    Project root (default: current directory).
  --home <DIR>            Override the Research4Jar data home.
  --no-snapshot-updates   Skip Maven SNAPSHOT metadata updates (Maven's
                          --no-snapshot-updates) during classpath and provenance
                          resolution. Maven only; Gradle projects ignore it.
  --build-arg <ARG>       Extra argument passed verbatim to the Maven/Gradle
                          classpath and provenance runs. Repeatable
                          (e.g. --build-arg -o --build-arg -Pprofile).
  --registry <URL>        Download covered shards from a registry.
  --registry-pubkey <HEX> Require valid ed25519 signatures from the registry.
  --indexer <PATH>        Accepted for legacy CLI compatibility; indexing is in-process.

On a Maven project the first index resolves the classpath and the dependency
tree in ONE Maven run; stats JSON reports classpath_ms, extract_ms,
provenance_ms, and total_ms alongside the classic fields. Multi-module
reactors are supported without a prior `mvn install`: one run compiles the
modules so sibling dependencies resolve from their build output, and the
index covers every module's external jars (module-to-module dependencies are
first-party code and stay out of the index).""",
    )
}

internal fun printIndexManyHelp(out: PrintStream) {
    out.println(
        """Usage:
  research4jar index-many --projects <DIR1,DIR2,...> [options]

Index several projects in one process. Classpath resolution (external
Maven/Gradle processes) runs in parallel up to --concurrency; jar extraction
then runs project-by-project through the shared pipeline, which is itself
parallel, memory-gated, and content-addressed — a jar shared by many projects
is extracted once. Prints one JSON summary with per-project stats; a failing
project is reported and does not stop the others (exit 1 if any failed).

Options:
  --projects <LIST>       Comma-separated project directories (required).
  --concurrency <N>       Max parallel build-tool resolutions
                          (default: min(4, CPU cores)).
  --home <DIR>            Override the Research4Jar data home.
  --no-snapshot-updates   Skip Maven SNAPSHOT metadata updates (Maven only).
  --build-arg <ARG>       Extra build-tool argument, repeatable.""",
    )
}

internal fun printDoctorHelp(out: PrintStream) {
    out.println(
        """Usage:
  research4jar doctor [--source-build] [--format json|text] [--project-dir <PATH>]

Check runtime, build-tool, and optional source-build prerequisites and print
actionable installation and verification guidance.""",
    )
}

internal fun printCacheHelp(out: PrintStream) {
    out.println(
        """Usage:
  research4jar cache stats [--home <DIR>]
  research4jar cache gc [--max-size <N[K|M|G]>] [--max-age <N[d|h]>]
                        [--dry-run] [--home <DIR>]

Inspect cache usage or reclaim stale extractor data, orphan files, old
sessions, and shards beyond the configured size budget. A max age of 0d or 0h
disables completed-session expiry.""",
    )
}

internal fun printRegistryHelp(out: PrintStream) {
    out.println(
        """Usage:
  research4jar registry export <DIR> [--sign-key <PATH>] [--home <DIR>]
  research4jar registry seed <DIR> --coordinates <FILE> [--repo <URL>]
                            [--sign-key <PATH>] [--home <DIR>]
  research4jar registry keygen <PATH>

Create signing keys, export cached shards, or seed a static shard registry
from Maven coordinates.""",
    )
}

internal fun printMcpHelp(out: PrintStream) {
    out.println(
        """Usage:
  research4jar mcp

Run the Model Context Protocol server over stdio. Configure an MCP host with
command "research4jar" and argument "mcp". Run `research4jar doctor` first
when indexing tools report missing local prerequisites.""",
    )
}

internal fun printQueryHelp(command: String, out: PrintStream) {
    val argument = when (command) {
        "list-extension-points" -> "[KEY|MECHANISM]"
        "list-packages" -> "[PREFIX]"
        "find-config-properties" -> "<PREFIX>"
        "find-implementations", "find-by-annotation", "get-class",
        "get-bean-definitions", "explain-conditional" -> "<FQN>"
        "find-string", "search-symbol" -> "<TEXT>"
        "find-class" -> "<NAME|PATTERN>"
        "find-method" -> "<NAME|CLASS#METHOD>"
        "open-symbol" -> "<FQN|CLASS#METHOD>"
        "why-dependency" -> "<COORD|JAR|CLASS>"
        "get-source" -> "<FQN|CLASS#METHOD>"
        "search-source" -> "<TEXT> --in <COORD|JAR|FQN>"
        else -> "<ARG>"
    }
    val description = when (command) {
        "find-config-properties" -> "Find Spring configuration properties under a prefix."
        "find-implementations" -> "Find implementations and subclasses across indexed jars."
        "find-by-annotation" -> "Find classes carrying an annotation or one of its meta-annotations."
        "get-class" -> "Show all indexed facts for one class."
        "get-bean-definitions" -> "Find @Bean definitions by bean type or configuration class."
        "explain-conditional" -> "Explain class- and method-level Spring activation conditions."
        "find-string" -> "Search bytecode string constants by substring."
        "list-extension-points" -> "List SPI extension points or registrations for one key."
        "find-class" -> "Find classes by name, package prefix, or substring."
        "find-method" -> "Find methods by name, substring, or Class#method."
        "list-packages" -> "List packages grouped by source jar."
        "search-symbol" -> "Search all indexed symbol kinds before opening a result."
        "open-symbol" -> "Expand a class or method returned by search-symbol."
        "why-dependency" -> "Explain which direct dependency introduced a jar or class."
        "get-source" ->
            "Read a dependency class's source, or one method's body via Class#method.\n" +
                "Local-first: the local Maven/Gradle sources jar when present, otherwise CFR\n" +
                "decompilation (the response's source_kind states which). --fetch downloads the\n" +
                "sources jar through the project's own Maven config; it is never the default."
        "search-source" ->
            "Substring-search one dependency jar's sources; --in picks the jar by Maven\n" +
                "coordinate, jar filename, or a class FQN it contains. Uses the local sources\n" +
                "jar when present, otherwise a cached one-time CFR decompile of the jar."
        else -> "Query the current Research4Jar project index."
    }
    val paginated = command in PAGINATED_QUERY_COMMANDS
    val options = buildList {
        add("  --project-dir <PATH>  Project root; defaults to searching upward from cwd.")
        add("  --home <DIR>          Override the Research4Jar data home.")
        add("  --format json|text    Output format (default: json).")
        if (paginated) {
            add("  --page <N>            Result page (default: 1; result window capped at $MAX_RESULT_WINDOW).")
            add("  --page-size <M>       Results per page (default: 20; maximum $MAX_PAGE_SIZE).")
        }
        if (command == "find-implementations" || command == "find-by-annotation") {
            add("  --direct              Restrict the lookup to directly declared matches.")
        }
        if (command == "search-source") {
            add("  --in <TARGET>         Required: coordinate, jar filename, or class FQN selecting one jar.")
        }
        if (command == "get-source") {
            add("  --in <TARGET>         Pin one jar (coordinate or filename) when the class ships in several.")
        }
        if (command == "get-source" || command == "search-source") {
            add("  --fetch               Download the sources jar via mvn dependency:get (opt-in).")
        }
    }.joinToString("\n")
    out.println(
        """Usage:
  research4jar $command $argument [options]

$description

Options:
$options""",
    )
}
