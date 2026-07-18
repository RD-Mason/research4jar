package dev.research4jar.registry

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.SerializableString
import com.fasterxml.jackson.core.io.CharacterEscapes
import com.fasterxml.jackson.core.io.SerializedString
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * JSON rendering that byte-matches Go's encoding/json: two-space indentation,
 * `"key": value` separators, `{}`/`[]` for empty containers, and `\n` line
 * endings. [marshalIndent] additionally applies the HTML escaping
 * (`<`, `>`, `&` to <-style sequences) json.Marshal performs by default —
 * registry.json is written with it. [encodeIndent] mirrors the Go CLI's
 * json.Encoder configured with SetEscapeHTML(false) and SetIndent("", "  "),
 * including the trailing newline, for command output parity.
 */
object GoJson {
    private val escapingMapper: ObjectMapper = JsonMapper.builder(
        JsonFactory().setCharacterEscapes(GoCharacterEscapes()),
    ).addModule(kotlinModule()).build()

    private val plainMapper: ObjectMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .build()

    /** Mirrors json.MarshalIndent(value, "", "  "): HTML-escaped, no trailing newline. */
    fun marshalIndent(value: Any): String =
        escapingMapper.writer(GoPrettyPrinter()).writeValueAsString(value)

    /** Mirrors the Go CLI's printJSON encoder: indented, unescaped HTML, trailing newline. */
    fun encodeIndent(value: Any): String {
        val output = ByteArrayOutputStream()
        encodeIndent(value, output)
        return String(output.toByteArray(), StandardCharsets.UTF_8)
    }

    /**
     * Streaming form of [encodeIndent]. The target is deliberately left open:
     * CLI daemons pass a bounded capture stream here, so Jackson must hit that
     * budget while serializing instead of first materializing an unbounded JSON
     * string on the daemon heap.
     */
    fun encodeIndent(value: Any, output: OutputStream) {
        plainMapper.factory.createGenerator(output).use { generator ->
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
            plainMapper.writer(GoPrettyPrinter()).writeValue(generator, value)
            generator.writeRaw('\n')
        }
    }
}

private class GoPrettyPrinter : DefaultPrettyPrinter() {
    init {
        val indenter = DefaultIndenter("  ", "\n")
        indentObjectsWith(indenter)
        indentArraysWith(indenter)
    }

    override fun createInstance(): DefaultPrettyPrinter = GoPrettyPrinter()

    override fun writeObjectFieldValueSeparator(generator: JsonGenerator) {
        generator.writeRaw(": ")
    }

    // Go writes empty containers with no inner padding; the default printer
    // emits "{ }" and "[ ]".
    override fun writeEndObject(generator: JsonGenerator, nrOfEntries: Int) {
        if (!_objectIndenter.isInline) {
            _nesting--
        }
        if (nrOfEntries > 0) {
            _objectIndenter.writeIndentation(generator, _nesting)
        }
        generator.writeRaw('}')
    }

    override fun writeEndArray(generator: JsonGenerator, nrOfValues: Int) {
        if (!_arrayIndenter.isInline) {
            _nesting--
        }
        if (nrOfValues > 0) {
            _arrayIndenter.writeIndentation(generator, _nesting)
        }
        generator.writeRaw(']')
    }
}

/**
 * Go escapes `<`, `>`, `&` (HTML mode) and renders control characters without
 * Jackson's `\b`/`\f` shorthands, always as lowercase `\u00xx`.
 */
private class GoCharacterEscapes : CharacterEscapes() {
    private val ascii: IntArray = CharacterEscapes.standardAsciiEscapesForJSON().also { table ->
        table['<'.code] = CharacterEscapes.ESCAPE_CUSTOM
        table['>'.code] = CharacterEscapes.ESCAPE_CUSTOM
        table['&'.code] = CharacterEscapes.ESCAPE_CUSTOM
        table[0x08] = CharacterEscapes.ESCAPE_CUSTOM
        table[0x0C] = CharacterEscapes.ESCAPE_CUSTOM
    }

    override fun getEscapeCodesForAscii(): IntArray = ascii

    override fun getEscapeSequence(ch: Int): SerializableString =
        SerializedString(String.format("\\u%04x", ch))
}
