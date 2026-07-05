package dev.research4jar.cli

import java.io.PrintStream

/**
 * Help texts, VERBATIM from querier/cmd/research4jar/main.go (Go). Do not
 * reflow or "fix" spacing: tests and downstream tooling compare bytes.
 */

internal fun printHelp(out: PrintStream) {
    out.println(
        """Usage:
  research4jar index [--jars <DIR|GLOB|LIST>] [options]   Index a project's dependency jars.
                                                       Without --jars the classpath is resolved
                                                       via Maven/Gradle (wrapper preferred).
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

Common workflows:
  First run:           research4jar doctor && research4jar index && research4jar status
  Freshness check:     research4jar status --check-classpath
  Import/jar origin:   research4jar dep precise 'import org.example.Type;'
  Class origin only:   research4jar class DataSourceAutoConfiguration
  Broad exploration:   research4jar search-symbol DataSourceAutoConfiguration
                       research4jar open-symbol org.example.Type
  Dependency path:     research4jar dep why org.springframework.boot:spring-boot-autoconfigure

Options:
  --project-dir <PATH>  Project root. Defaults to searching upward from cwd.
  --format json|text    Output format (default: json).
  --page <N>            Result page (default: 1).
  --page-size <M>       Results per page (default: 20).
  --home <DIR>          Override Research4Jar home (manifest lookup; index target).
  --direct              Disable transitive/meta-annotation expansion.
  --no-source-grep      (dep precise/artifact/class) Skip bounded source/build-file usage search.
  --check-classpath     (status) Resolve the current runtime classpath and compare
                        its fingerprint with the last index.
  --indexer <PATH>      (index) Path to research4jar-index; auto-located otherwise.
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
                         still matches the indexed fingerprint.""",
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
