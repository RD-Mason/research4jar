package dev.research4jar.indexer.extract

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Properties
import java.util.zip.ZipFile

class JarExtractor(
    objectMapper: ObjectMapper,
) {
    private val configMetadataExtractor = ConfigMetadataExtractor(objectMapper)
    private val bytecodeExtractor = BytecodeExtractor(objectMapper)

    fun extract(zip: ZipFile): ExtractedJar {
        val (configProperties, metadataWarnings) = configMetadataExtractor.extract(zip)
        val bytecode = bytecodeExtractor.extract(zip)
        return ExtractedJar(
            coordinate = extractCoordinate(zip),
            spiRegistrations = SpiExtractor.extract(zip),
            configProperties = configProperties,
            classes = bytecode.classes,
            methods = bytecode.methods,
            annotations = bytecode.annotations,
            beanDefinitions = bytecode.beanDefinitions,
            conditions = bytecode.conditions,
            stringConstants = bytecode.stringConstants,
            warnings = (metadataWarnings + bytecode.warnings).sorted(),
        )
    }

    private fun extractCoordinate(zip: ZipFile): String? {
        val entry = zip.entries().asSequence().firstOrNull {
            !it.isDirectory &&
                it.name.startsWith("META-INF/maven/") &&
                it.name.endsWith("/pom.properties")
        } ?: return null

        return try {
            val properties = Properties()
            zip.getInputStream(entry).use(properties::load)
            val group = properties.getProperty("groupId")?.takeIf(String::isNotBlank) ?: return null
            val artifact =
                properties.getProperty("artifactId")?.takeIf(String::isNotBlank) ?: return null
            val version = properties.getProperty("version")?.takeIf(String::isNotBlank) ?: return null
            "$group:$artifact:$version"
        } catch (_: Exception) {
            null
        }
    }
}
