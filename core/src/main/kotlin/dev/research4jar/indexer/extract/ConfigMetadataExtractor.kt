package dev.research4jar.indexer.extract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.zip.ZipFile

class ConfigMetadataExtractor(
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private val ENTRIES = listOf(
            "META-INF/spring-configuration-metadata.json",
            "META-INF/additional-spring-configuration-metadata.json",
        )
    }

    fun extract(zip: ZipFile): Pair<List<ConfigProperty>, List<String>> {
        val properties = mutableListOf<ConfigProperty>()
        val warnings = mutableListOf<String>()

        for (name in ENTRIES) {
            val entry = zip.getEntry(name) ?: continue
            try {
                val root = zip.getInputStream(entry).use(objectMapper::readTree)
                val entries = root.path("properties")
                if (!entries.isArray) continue
                entries.forEach { node ->
                    parseProperty(node)?.let(properties::add)
                }
            } catch (exception: Exception) {
                warnings += "failed to parse $name: ${exception.message ?: exception.javaClass.simpleName}"
            }
        }

        return properties.sortedWith(
            compareBy<ConfigProperty> { it.name }
                .thenBy { it.sourceFqn ?: "" }
                .thenBy { it.typeFqn ?: "" }
                .thenBy { it.defaultValue ?: "" },
        ) to warnings
    }

    private fun parseProperty(node: JsonNode): ConfigProperty? {
        val name = nullableText(node.get("name")) ?: return null
        val separator = name.lastIndexOf('.')
        return ConfigProperty(
            prefix = if (separator < 0) null else name.substring(0, separator),
            name = name,
            typeFqn = nullableText(node.get("type")),
            defaultValue = defaultValue(node.get("defaultValue")),
            description = nullableText(node.get("description")),
            sourceFqn = nullableText(node.get("sourceType")),
        )
    }

    private fun nullableText(node: JsonNode?): String? =
        if (node == null || node.isNull) null else node.asText()

    private fun defaultValue(node: JsonNode?): String? = when {
        node == null || node.isNull -> null
        node.isTextual || node.isNumber || node.isBoolean -> node.asText()
        else -> objectMapper.writeValueAsString(node)
    }
}
