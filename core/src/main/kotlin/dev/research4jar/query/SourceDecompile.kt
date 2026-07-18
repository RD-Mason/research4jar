package dev.research4jar.query

import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.api.SinkReturns
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * CFR-backed decompilation for the source-retrieval fallback. All output is
 * captured through an in-memory [OutputSinkFactory] — CFR must never write to
 * stdout, which carries the command's JSON. Only [SourceRetrieval] references
 * this object, and only after the sources-jar ladder missed, so CFR classes
 * stay unloaded on the query hot path.
 */
internal object SourceDecompiler {
    /**
     * Decompiles one outer class out of [jar] (inner classes are inlined by
     * CFR) and returns the Java text, or null when CFR emitted nothing for
     * [outerFqn].
     */
    fun decompileClass(jar: Path, outerFqn: String): String? {
        // jarfilter prunes the jar's top-level class list by a regex over the
        // dotted binary name. Anchors keep the semantics identical whether
        // CFR applies find() or matches().
        val filtered = decompile(jar, "^" + Pattern.quote(outerFqn) + "$")
        filtered[outerFqn]?.let { return it }
        // Defensive: if the anchored filter matched nothing (a CFR filter
        // semantics change would silently return an empty jar analysis),
        // retry as a plain quoted substring before giving up.
        return decompile(jar, Pattern.quote(outerFqn))[outerFqn]
    }

    /**
     * Decompiles every class in [jar], streaming each outer class's Java text
     * to [emit] (fqn, java). Returns the number of classes emitted.
     */
    fun decompileJar(jar: Path, emit: (String, String) -> Unit): Int {
        var count = 0
        drive(jar, jarFilter = null) { fqn, java ->
            emit(fqn, java)
            count++
        }
        return count
    }

    private fun decompile(jar: Path, jarFilter: String): Map<String, String> {
        val results = LinkedHashMap<String, String>()
        drive(jar, jarFilter) { fqn, java -> results[fqn] = java }
        return results
    }

    private fun drive(jar: Path, jarFilter: String?, emit: (String, String) -> Unit) {
        val sinkFactory = object : OutputSinkFactory {
            override fun getSupportedSinks(
                sinkType: OutputSinkFactory.SinkType,
                available: Collection<OutputSinkFactory.SinkClass>,
            ): List<OutputSinkFactory.SinkClass> =
                if (sinkType == OutputSinkFactory.SinkType.JAVA &&
                    available.contains(OutputSinkFactory.SinkClass.DECOMPILED)
                ) {
                    listOf(OutputSinkFactory.SinkClass.DECOMPILED)
                } else {
                    // Progress/summary/exception messages are swallowed below;
                    // STRING is the class every sink type supports.
                    listOf(OutputSinkFactory.SinkClass.STRING)
                }

            @Suppress("UNCHECKED_CAST")
            override fun <T> getSink(
                sinkType: OutputSinkFactory.SinkType,
                sinkClass: OutputSinkFactory.SinkClass,
            ): OutputSinkFactory.Sink<T> {
                if (sinkType == OutputSinkFactory.SinkType.JAVA &&
                    sinkClass == OutputSinkFactory.SinkClass.DECOMPILED
                ) {
                    val sink = OutputSinkFactory.Sink<SinkReturns.Decompiled> { decompiled ->
                        val fqn = if (decompiled.packageName.isEmpty()) {
                            decompiled.className
                        } else {
                            decompiled.packageName + "." + decompiled.className
                        }
                        emit(fqn, decompiled.java)
                    }
                    return sink as OutputSinkFactory.Sink<T>
                }
                return OutputSinkFactory.Sink { }
            }
        }
        val options = HashMap<String, String>()
        options["silent"] = "true"
        if (jarFilter != null) {
            options["jarfilter"] = jarFilter
        }
        CfrDriver.Builder()
            .withOptions(options)
            .withOutputSink(sinkFactory)
            .build()
            .analyse(listOf(jar.toString()))
    }
}
