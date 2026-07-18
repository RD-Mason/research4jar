package dev.research4jar.query

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CompactConstructorDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration

/**
 * Slices single method bodies out of a Java source file so `Class#method`
 * answers cost tokens proportional to the method, not the file. Only the
 * source commands reference this object, so JavaParser classes stay unloaded
 * on the query hot path.
 */
internal data class MethodSlices(val slices: List<SourceSlice>, val note: String)

internal object SourceSlicer {
    // BLEEDING_EDGE accepts every syntax level JavaParser knows; dependency
    // sources and CFR output may use newer constructs than the tool's own
    // Java 8 runtime baseline.
    private fun parser(): JavaParser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE),
    )

    /**
     * Returns every overload of [methodName] declared by [binaryName]'s type
     * (constructors match the simple class name or `<init>`). An unparseable
     * file or an unmatched method returns no slices plus a note; the caller
     * then falls back to the whole file.
     */
    fun slice(source: String, binaryName: String, methodName: String): MethodSlices {
        val parsed = parser().parse(source)
        val unit = parsed.result.orElse(null)
        if (!parsed.isSuccessful || unit == null) {
            val problem = parsed.problems.firstOrNull()?.message ?: "unparseable source"
            return MethodSlices(
                emptyList(),
                "could not parse the source for method slicing ($problem); returning the whole file",
            )
        }

        val (target, targetNote) = resolveTargetType(unit, binaryName)
        val simpleName = binaryName.substringAfterLast('$').substringAfterLast('.')
        val matches: List<Match> = if (target != null) {
            matchesIn(target, methodName, simpleName)
        } else {
            // Type chain unresolved (anonymous/local segment or renamed
            // decompile): match the method anywhere in the file instead.
            matchesAnywhere(unit, methodName)
        }
        if (matches.isEmpty()) {
            return MethodSlices(
                emptyList(),
                joinNonEmpty(
                    targetNote,
                    "method \"$methodName\" not found in $binaryName; returning the whole file",
                ),
            )
        }

        val lines = source.split("\n")
        val slices = matches
            .mapNotNull { sliceOf(it.node, it.signature, lines) }
            .sortedBy(SourceSlice::startLine)
        return MethodSlices(slices, targetNote)
    }

    /**
     * Walks `Outer$Inner$Deep` down from the top-level type. Numeric segments
     * (anonymous/local classes) and unmatched names stop the walk and report
     * a note; the caller then searches the whole file.
     */
    private fun resolveTargetType(
        unit: CompilationUnit,
        binaryName: String,
    ): Pair<TypeDeclaration<*>?, String> {
        val afterPackage = binaryName.substringAfterLast('.')
        val segments = afterPackage.split('$')
        var current: TypeDeclaration<*> = unit.types.firstOrNull { it.nameAsString == segments[0] }
            ?: unit.types.firstOrNull()
            ?: return null to "no type declarations found in the source file"
        for (segment in segments.drop(1)) {
            val next = current.members
                .filterIsInstance<TypeDeclaration<*>>()
                .firstOrNull { it.nameAsString == segment }
            if (next == null) {
                return null to
                    "inner type \"$segment\" of $binaryName not found; matching the method anywhere in the file"
            }
            current = next
        }
        return current to ""
    }

    /** One sliceable declaration plus its display signature. */
    private data class Match(val node: Node, val signature: String)

    private fun methodMatch(method: MethodDeclaration): Match =
        Match(method, method.getDeclarationAsString(true, true, true))

    private fun compactMatch(compact: CompactConstructorDeclaration): Match {
        val modifiers = compact.modifiers.joinToString(" ") { it.keyword.asString() }
        val prefix = if (modifiers.isEmpty()) "" else "$modifiers "
        return Match(compact, prefix + compact.nameAsString + " (compact constructor)")
    }

    private fun matchesIn(
        type: TypeDeclaration<*>,
        methodName: String,
        simpleName: String,
    ): List<Match> {
        val methods = type.members
            .filterIsInstance<MethodDeclaration>()
            .filter { it.nameAsString == methodName }
            .map(::methodMatch)
            .toMutableList()
        // Enum constants may carry the real implementations in their class
        // bodies (an abstract-per-constant enum declares only the abstract
        // method at type level); omitting them would under-read the source.
        if (type is EnumDeclaration) {
            for (entry in type.entries) {
                entry.classBody
                    .filterIsInstance<MethodDeclaration>()
                    .filter { it.nameAsString == methodName }
                    .forEach { method ->
                        methods += Match(
                            method,
                            entry.nameAsString + "." + method.getDeclarationAsString(true, true, true),
                        )
                    }
            }
        }
        if (methods.isNotEmpty()) return methods
        if (methodName == simpleName || methodName == "<init>") {
            val constructors = type.members
                .filterIsInstance<ConstructorDeclaration>()
                .map { Match(it, it.getDeclarationAsString(true, true, true)) }
                .toMutableList()
            // A record's compact constructor is not a ConstructorDeclaration;
            // without it `Record#Record` would miss the validation body.
            if (type is RecordDeclaration) {
                constructors += type.compactConstructors.map(::compactMatch)
            }
            return constructors
        }
        return emptyList()
    }

    private fun matchesAnywhere(
        unit: CompilationUnit,
        methodName: String,
    ): List<Match> {
        val methods = unit.findAll(MethodDeclaration::class.java)
            .filter { it.nameAsString == methodName }
            .map(::methodMatch)
        if (methods.isNotEmpty()) return methods
        val constructors = unit.findAll(ConstructorDeclaration::class.java)
            .filter { it.nameAsString == methodName || methodName == "<init>" }
            .map { Match(it, it.getDeclarationAsString(true, true, true)) }
        if (constructors.isNotEmpty()) return constructors
        return unit.findAll(CompactConstructorDeclaration::class.java)
            .filter { it.nameAsString == methodName || methodName == "<init>" }
            .map(::compactMatch)
    }

    /**
     * The slice is cut from the raw source lines (annotations and formatting
     * intact), extended upward to cover an attached Javadoc/leading comment.
     */
    private fun sliceOf(node: Node, signature: String, lines: List<String>): SourceSlice? {
        val range = node.range.orElse(null) ?: return null
        var startLine = range.begin.line
        node.comment.flatMap(Node::getRange).ifPresent { comment ->
            if (comment.begin.line < startLine) startLine = comment.begin.line
        }
        val endLine = range.end.line
        if (startLine < 1 || endLine > lines.size || startLine > endLine) return null
        return SourceSlice(
            signature = signature,
            startLine = startLine,
            endLine = endLine,
            source = lines.subList(startLine - 1, endLine).joinToString("\n"),
        )
    }

    private fun joinNonEmpty(first: String, second: String): String = when {
        first.isEmpty() -> second
        second.isEmpty() -> first
        else -> "$first; $second"
    }
}
