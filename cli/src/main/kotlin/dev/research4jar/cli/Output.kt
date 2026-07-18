package dev.research4jar.cli

import com.fasterxml.jackson.annotation.JsonProperty
import dev.research4jar.envcheck.Check
import dev.research4jar.envcheck.Report
import dev.research4jar.envcheck.Status
import dev.research4jar.query.BeanDefinitionsResponse
import dev.research4jar.query.ClassSearchResponse
import dev.research4jar.query.ConfigPropertiesResponse
import dev.research4jar.query.Coverage
import dev.research4jar.query.DependencyPreciseResponse
import dev.research4jar.query.DependencyWhyResponse
import dev.research4jar.query.ExtensionPointsResponse
import dev.research4jar.query.MethodSearchResponse
import dev.research4jar.query.PackageListResponse
import dev.research4jar.query.ProjectStatusResponse
import dev.research4jar.query.SearchSymbolResponse
import dev.research4jar.query.SourceResponse
import dev.research4jar.query.SourceSearchResponse
import dev.research4jar.query.StringSearchResponse
import dev.research4jar.query.SymbolResponse
import dev.research4jar.registry.GoJson
import java.io.PrintStream

/**
 * Output plumbing for the JVM CLI, ported from the printing half of
 * querier/cmd/research4jar/main.go: fail(), printJSON, printText and the
 * per-response tabwriter tables. Byte parity with the Go CLI is the contract
 * (tests/e2e.sh via tests/run-e2e-java.sh).
 */

/** The stdout/stderr pair a command run writes to (daemon-ready: no globals). */
class CliIO(val out: PrintStream, val err: PrintStream)

/** What fail() encodes on stdout before exiting (Go errorResponse). */
data class ErrorResponse(
    @JsonProperty("error") val error: String,
    @JsonProperty("message") val message: String,
)

/**
 * Raised where the Go CLI calls fail(code, message, exitCode); runCli catches
 * it, prints the error JSON exactly like Go's fail, and returns the exit
 * code instead of exiting the process.
 */
class CliFailure(
    val code: String,
    val messageText: String,
    val exitCode: Int,
) : RuntimeException(messageText)

/** Go fail(): encode {error, message} on stdout, exit with the code. */
internal fun fail(code: String, message: String, exitCode: Int): Nothing =
    throw CliFailure(code, message, exitCode)

/** Go %v on an error. */
internal fun errMessage(exception: Throwable): String =
    exception.message ?: exception.toString()

/** Mirrors the Go CLI's printJSON: 2-space indent, no HTML escaping, trailing newline. */
internal fun printJson(out: PrintStream, response: Any) {
    GoJson.encodeIndent(response, out)
}

internal fun emitResponse(response: Any, format: String, io: CliIO) {
    if (format == "text") {
        printText(response, io.out)
        return
    }
    printJson(io.out, response)
}

/** Go strconv.Quote for the strings embedded in CLI messages. */
internal fun strconvQuote(value: String): String {
    val quoted = StringBuilder("\"")
    for (character in value) {
        when (character) {
            '"' -> quoted.append("\\\"")
            '\\' -> quoted.append("\\\\")
            '\n' -> quoted.append("\\n")
            '\r' -> quoted.append("\\r")
            '\t' -> quoted.append("\\t")
            else -> if (character < ' ') {
                quoted.append(String.format("\\x%02x", character.code))
            } else {
                quoted.append(character)
            }
        }
    }
    return quoted.append('"').toString()
}

internal fun valueOrText(value: String, fallback: String): String =
    value.ifEmpty { fallback }

internal fun valueOrDash(value: String?): String = value ?: "-"

/**
 * Port of Go text/tabwriter as the CLI configures it:
 * NewWriter(out, minwidth=0, tabwidth=4, padding=2, padchar=' ', flags=0).
 * Rows buffer until [flush]; the trailing cell of each row is never padded,
 * and column widths are computed per block of consecutive rows that have a
 * (non-final) cell in that column, exactly like Go's recursive format().
 */
internal class GoTabWriter(private val out: PrintStream, private val padding: Int = 2) {
    private val rows = mutableListOf<List<String>>()

    fun row(vararg cells: String) {
        // Go writes rows as one tab-joined string, so a tab embedded in a
        // value splits into an extra cell; re-splitting keeps that semantic.
        rows += cells.joinToString("\t").split("\t")
    }

    fun flush() {
        format(mutableListOf(), 0, rows.size)
        rows.clear()
    }

    private fun format(widths: MutableList<Int>, first: Int, last: Int) {
        val column = widths.size
        var line0 = first
        var current = first
        while (current < last) {
            if (column >= rows[current].size - 1) {
                current++
                continue
            }
            // Cell exists in this column: emit rows before the block, then
            // measure the block (consecutive rows with a non-final cell here).
            writeRows(widths, line0, current)
            line0 = current
            var width = 0 // minwidth
            while (current < last) {
                val line = rows[current]
                if (column >= line.size - 1) break
                val cellWidth = runeCount(line[column]) + padding
                if (cellWidth > width) width = cellWidth
                current++
            }
            widths.add(width)
            format(widths, line0, current)
            widths.removeAt(widths.size - 1)
            line0 = current
        }
        writeRows(widths, line0, last)
    }

    private fun writeRows(widths: List<Int>, first: Int, last: Int) {
        for (index in first until last) {
            val line = rows[index]
            for ((cellIndex, cell) in line.withIndex()) {
                out.print(cell)
                if (cellIndex < widths.size) {
                    repeat(widths[cellIndex] - runeCount(cell)) { out.print(' ') }
                }
            }
            out.print('\n')
        }
    }

    private fun runeCount(text: String): Int = text.codePointCount(0, text.length)
}

// --- printText and the per-response tables (Go printText and helpers) ---

internal fun printText(response: Any, out: PrintStream) {
    when (response) {
        is ConfigPropertiesResponse -> printConfigPropertiesText(response, out)
        is SymbolResponse -> printSymbolsText(response, out)
        is BeanDefinitionsResponse -> printBeansText(response, out)
        is StringSearchResponse -> printStringsText(response, out)
        is ExtensionPointsResponse -> printExtensionsText(response, out)
        is ClassSearchResponse -> printClassSearchText(response, out)
        is MethodSearchResponse -> printMethodSearchText(response, out)
        is PackageListResponse -> printPackagesText(response, out)
        is SearchSymbolResponse -> printSearchSymbolsText(response, out)
        is DependencyWhyResponse -> printWhyDependencyText(response, out)
        is DependencyPreciseResponse -> printDependencyPreciseText(response, out)
        is ProjectStatusResponse -> printProjectStatusText(response, out)
        is SourceResponse -> printSourceText(response, out)
        is SourceSearchResponse -> printSourceSearchText(response, out)
        else ->
            // Nested detail responses (get-class, explain-conditional) read best
            // as structured JSON even in text mode. Go leaves the encoder's
            // default HTML escaping on for this branch.
            out.print(GoJson.marshalIndent(response) + "\n")
    }
}

private fun printClassSearchText(response: ClassSearchResponse, out: PrintStream) {
    val writer = GoTabWriter(out)
    writer.row("FQN", "KIND", "SCORE", "REASON", "SOURCE JAR")
    for (result in response.results) {
        writer.row(
            result.fqn, valueOrDash(result.kind), result.score.toString(),
            result.matchReason, result.sourceJar,
        )
    }
    writer.flush()
    printSearchSummary(response.page, response.pageSize, response.total, response.hasMore, response.coverage, out)
}

private fun printMethodSearchText(response: MethodSearchResponse, out: PrintStream) {
    val writer = GoTabWriter(out)
    writer.row("METHOD", "RETURN", "SCORE", "REASON", "SOURCE JAR")
    for (result in response.results) {
        writer.row(
            "${result.classFqn}#${result.name}${result.descriptor}",
            valueOrDash(result.returnFqn), result.score.toString(),
            result.matchReason, result.sourceJar,
        )
    }
    writer.flush()
    printSearchSummary(response.page, response.pageSize, response.total, response.hasMore, response.coverage, out)
}

private fun printPackagesText(response: PackageListResponse, out: PrintStream) {
    val writer = GoTabWriter(out)
    writer.row("PACKAGE", "CLASSES", "SOURCE JAR")
    for (result in response.results) {
        writer.row(result.packageName, result.classes.toString(), result.sourceJar)
    }
    writer.flush()
    printSearchSummary(response.page, response.pageSize, response.total, response.hasMore, response.coverage, out)
}

private fun printSearchSymbolsText(response: SearchSymbolResponse, out: PrintStream) {
    val writer = GoTabWriter(out)
    writer.row("KIND", "NAME", "OWNER", "SCORE", "REASON", "SOURCE JAR")
    for (result in response.results) {
        writer.row(
            result.kind, result.name, valueOrDash(result.owner),
            result.score.toString(), result.matchReason, result.sourceJar,
        )
    }
    writer.flush()
    printSearchSummary(response.page, response.pageSize, response.total, response.hasMore, response.coverage, out)
}

private fun printWhyDependencyText(response: DependencyWhyResponse, out: PrintStream) {
    val writer = GoTabWriter(out)
    writer.row("COORDINATE", "DIRECT", "DEPTH", "DIRECT DEPENDENCY", "PARENT", "PATH")
    for (result in response.results) {
        writer.row(
            result.coordinate,
            result.direct.toString(),
            result.depth.toString(),
            valueOrText(result.directDependency, "-"),
            valueOrText(result.parent, "-"),
            result.path.joinToString(" -> "),
        )
    }
    writer.flush()
    out.print(
        "\ntotal ${response.total}; coverage " +
            "${response.coverage.jarsIndexed}/${response.coverage.jarsTotal} jars, " +
            "${response.coverage.jarsMissing.size} missing\n",
    )
}

private fun printDependencyPreciseText(response: DependencyPreciseResponse, out: PrintStream) {
    out.print("input: ${response.normalized} (${response.inputKind})\n\n")
    val writer = GoTabWriter(out)
    writer.row("SYMBOL/PACKAGE", "COORDINATE", "JAR", "MATCH")
    for (origin in response.origins) {
        val name = origin.fqn.ifEmpty { origin.packageName }
        writer.row(
            valueOrText(name, "-"),
            valueOrText(origin.coordinate, "-"),
            valueOrText(origin.jarFilename, origin.sourceJar),
            origin.matchReason,
        )
    }
    writer.flush()

    val dependencies = response.dependencies
    if (!dependencies.isNullOrEmpty()) {
        out.print("\n")
        val table = GoTabWriter(out)
        table.row("COORDINATE", "DIRECT", "DIRECT DEPENDENCY", "PARENT", "PATH")
        for (result in dependencies) {
            table.row(
                result.coordinate,
                result.direct.toString(),
                valueOrText(result.directDependency, "-"),
                valueOrText(result.parent, "-"),
                result.path.joinToString(" -> "),
            )
        }
        table.flush()
    } else if (!response.dependencyGraphAvailable) {
        out.print("\ndependency graph: " + response.dependencyGraphError + "\n")
    }

    if (response.sourceUsages.isNotEmpty()) {
        out.print("\n")
        val usages = GoTabWriter(out)
        usages.row("LOCATION", "MATCH", "LINE")
        for (usage in response.sourceUsages) {
            usages.row("${usage.path}:${usage.line}", usage.match, usage.text)
        }
        usages.flush()
    }

    out.print(
        "\norigins ${response.total}, dependencies ${response.dependenciesTotal}, " +
            "source_usages ${response.sourceUsages.size}, " +
            "source_usages_has_more ${response.sourceUsagesHasMore}; coverage " +
            "${response.coverage.jarsIndexed}/${response.coverage.jarsTotal} jars, " +
            "${response.coverage.jarsMissing.size} missing\n",
    )
}

// Text mode prints the raw source under a compact provenance header — the
// token-efficient shape for agents that asked for text.
private fun printSourceText(response: SourceResponse, out: PrintStream) {
    val symbol = if (response.method.isEmpty()) response.fqn else "${response.fqn}#${response.method}"
    out.print("// $symbol (${response.sourceKind}: ${response.sourcePath})\n")
    if (response.note.isNotEmpty()) {
        out.print("// note: ${response.note}\n")
    }
    if (response.slices.isEmpty()) {
        out.print(response.source)
        if (!response.source.endsWith("\n")) out.print("\n")
        return
    }
    for (slice in response.slices) {
        out.print("// lines ${slice.startLine}-${slice.endLine}: ${slice.signature}\n")
        out.print(slice.source)
        out.print("\n")
    }
}

private fun printSourceSearchText(response: SourceSearchResponse, out: PrintStream) {
    out.print(
        "in: ${valueOrText(response.coordinate, response.jarFilename)} " +
            "(${response.sourceKind}: ${response.sourcePath})\n\n",
    )
    val writer = GoTabWriter(out)
    writer.row("LOCATION", "TEXT")
    for (hit in response.results) {
        writer.row("${hit.file}:${hit.line}", hit.text)
    }
    writer.flush()
    if (response.note.isNotEmpty()) {
        out.print("note: ${response.note}\n")
    }
    printSearchSummary(response.page, response.pageSize, response.total, response.hasMore, response.coverage, out)
}

private fun printProjectStatusText(response: ProjectStatusResponse, out: PrintStream) {
    out.print("Research4Jar project status\n")
    out.print("Project: " + valueOrText(response.projectDir, "-") + "\n")
    out.print("Indexed: ${response.indexed}\n")
    if (response.projectIndexPath.isNotEmpty()) {
        out.print("Project index: ${response.projectIndexPath}\n")
    }
    if (response.classpathFingerprint.isNotEmpty()) {
        out.print("Classpath fingerprint: ${response.classpathFingerprint}\n")
    }
    if (response.builtAtUtc.isNotEmpty()) {
        out.print("Built at: ${response.builtAtUtc}\n")
    }
    if (response.sessionDbPath.isNotEmpty()) {
        out.print("Session DB: ${response.sessionDbPath}\n")
        out.print("Session DB exists: ${response.sessionDbExists}\n")
    }
    if (response.manifestPath.isNotEmpty()) {
        out.print("Manifest: ${response.manifestPath}\n")
        out.print("Manifest exists: ${response.manifestExists}\n")
    }
    if (response.indexed) {
        out.print(
            "Coverage: ${response.coverage.jarsIndexed}/${response.coverage.jarsTotal} " +
                "jars indexed, ${response.coverage.jarsMissing.size} missing\n",
        )
    }
    val provenance = response.dependencyProvenance
    out.print("Dependency provenance: ${provenance.available}\n")
    if (provenance.available) {
        out.print("Dependency build tool: " + valueOrText(provenance.buildTool, "-") + "\n")
        out.print("Dependency artifacts: ${provenance.artifacts}\n")
    }
    if (provenance.path.isNotEmpty()) {
        out.print("Dependency provenance path: ${provenance.path}\n")
    }
    val check = response.classpathCheck
    if (check != null) {
        out.print("Classpath check: ${check.checked}\n")
        if (check.currentFingerprint.isNotEmpty()) {
            out.print("Current classpath fingerprint: ${check.currentFingerprint}\n")
        }
        if (check.indexedFingerprint.isNotEmpty()) {
            out.print("Indexed classpath fingerprint: ${check.indexedFingerprint}\n")
        }
        if (check.dependencyResolution.isNotEmpty()) {
            out.print("Classpath resolution: ${check.dependencyResolution}\n")
        }
        if (check.jarsResolved > 0) {
            out.print("Classpath jars resolved: ${check.jarsResolved}\n")
        }
        if (check.jarsUnique > 0) {
            out.print("Classpath unique jars: ${check.jarsUnique}\n")
        }
        if (check.error.isNotEmpty()) {
            out.print("Classpath check error: ${check.error}\n")
        } else {
            out.print("Classpath up to date: ${check.upToDate}\n")
        }
    }
    if (response.nextSteps.isNotEmpty()) {
        out.print("Next steps:\n")
        for (step in response.nextSteps) {
            out.print("  - $step\n")
        }
    }
}

private fun printConfigPropertiesText(response: ConfigPropertiesResponse, out: PrintStream) {
    val writer = GoTabWriter(out)
    writer.row("NAME", "TYPE", "DEFAULT", "SOURCE JAR")
    for (property in response.results) {
        writer.row(
            property.name,
            valueOrDash(property.type),
            valueOrDash(property.default),
            property.sourceJar,
        )
    }
    writer.flush()
    printSummary(response.page, response.pageSize, response.total, response.coverage, out)
}

private fun printSymbolsText(response: SymbolResponse, out: PrintStream) {
    val writer = GoTabWriter(out)
    writer.row("FQN", "SOURCE JAR", "MATCHED", "ATTRIBUTES")
    for (result in response.results) {
        val attributes = result.attributes?.takeUnless(String::isEmpty) ?: "-"
        val matched = result.matchedAnnotation.ifEmpty { "-" }
        writer.row(result.fqn, result.sourceJar, matched, attributes)
    }
    writer.flush()
    printSummary(response.page, response.pageSize, response.total, response.coverage, out)
}

private fun printBeansText(response: BeanDefinitionsResponse, out: PrintStream) {
    val writer = GoTabWriter(out)
    writer.row("BEAN", "TYPE", "CONFIG CLASS", "CONDITIONS", "SOURCE JAR")
    for (bean in response.results) {
        writer.row(
            bean.beanName,
            valueOrDash(bean.beanTypeFqn),
            bean.configFqn,
            bean.conditions.size.toString(),
            bean.sourceJar,
        )
    }
    writer.flush()
    printSummary(response.page, response.pageSize, response.total, response.coverage, out)
}

private fun printStringsText(response: StringSearchResponse, out: PrintStream) {
    val writer = GoTabWriter(out)
    writer.row("VALUE", "CLASS", "METHOD", "SOURCE JAR")
    for (constant in response.results) {
        writer.row(
            constant.value,
            constant.classFqn,
            valueOrDash(constant.method),
            constant.sourceJar,
        )
    }
    writer.flush()
    printSummary(response.page, response.pageSize, response.total, response.coverage, out)
}

private fun printExtensionsText(response: ExtensionPointsResponse, out: PrintStream) {
    val writer = GoTabWriter(out)
    val results = response.results
    if (results != null) {
        writer.row("MECHANISM", "KEY", "IMPLEMENTATION", "SOURCE JAR")
        for (registration in results) {
            writer.row(
                registration.mechanism,
                valueOrDash(registration.key),
                registration.implFqn,
                registration.sourceJar,
            )
        }
    } else {
        writer.row("MECHANISM", "KEY", "IMPLEMENTATIONS")
        for (point in response.points.orEmpty()) {
            writer.row(
                point.mechanism,
                valueOrDash(point.key),
                point.implementations.toString(),
            )
        }
    }
    writer.flush()
    printSummary(response.page, response.pageSize, response.total, response.coverage, out)
}

private fun printSummary(page: Int, pageSize: Int, total: Int, coverage: Coverage, out: PrintStream) {
    out.print(
        "\npage $page, page size $pageSize, total $total; coverage " +
            "${coverage.jarsIndexed}/${coverage.jarsTotal} jars, " +
            "${coverage.jarsMissing.size} missing\n",
    )
}

private fun printSearchSummary(
    page: Int,
    pageSize: Int,
    returned: Int,
    hasMore: Boolean,
    coverage: Coverage,
    out: PrintStream,
) {
    out.print(
        "\npage $page, page size $pageSize, returned $returned, has_more $hasMore; coverage " +
            "${coverage.jarsIndexed}/${coverage.jarsTotal} jars, " +
            "${coverage.jarsMissing.size} missing\n",
    )
}

// --- doctor text output (Go printDoctorText) ---

internal fun printDoctorText(report: Report, out: PrintStream) {
    out.print("Research4Jar environment\n")
    if (report.projectDir.isNotEmpty()) {
        out.print("Project: ${report.projectDir}\n")
    }
    out.print("\n")
    val writer = GoTabWriter(out)
    writer.row("STATUS", "CHECK", "FOUND", "VERSION", "REQUIRED FOR")
    for (check in report.checks) {
        writer.row(
            doctorStatus(check.status),
            check.name,
            valueOrText(check.found, "-"),
            valueOrText(check.version, "-"),
            check.requiredFor.joinToString(", "),
        )
    }
    writer.flush()

    for (check in report.checks) {
        if (check.status == Status.OK) continue
        printDoctorProblem(check, out)
    }
    if (report.ok) {
        out.print("\nOK: required environment checks passed.\n")
    } else {
        out.print(
            "\nMissing requirements found. Install the missing tools above, " +
                "then rerun research4jar doctor.\n",
        )
    }
}

private fun printDoctorProblem(check: Check, out: PrintStream) {
    out.print("\n${doctorStatus(check.status)}: ${check.name}\n")
    out.print("  " + check.message + "\n")
    if (check.userInstall.isNotEmpty()) {
        out.print("  User install: " + check.userInstall + "\n")
    }
    if (check.agentInstall.isNotEmpty()) {
        out.print("  Agent install:\n")
        for (command in check.agentInstall) {
            out.print("    $command\n")
        }
    }
    if (check.verify.isNotEmpty()) {
        out.print("  Verify:\n")
        for (command in check.verify) {
            out.print("    $command\n")
        }
    }
}

private fun doctorStatus(status: Status): String = when (status) {
    Status.OK -> "OK"
    Status.WARNING -> "WARN"
    else -> "MISSING"
}
