package dev.research4jar.indexer.extract

import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.zip.ZipFile

object SpiExtractor {
    private const val IMPORTS =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    private const val FACTORIES = "META-INF/spring.factories"
    private const val SERVICES_PREFIX = "META-INF/services/"

    fun extract(zip: ZipFile): List<SpiRegistration> {
        val registrations = mutableSetOf<SpiRegistration>()

        zip.getEntry(IMPORTS)?.let { entry ->
            readProviderLines(zip, entry.name).forEach { implementation ->
                registrations += SpiRegistration("autoconfig.imports", null, implementation)
            }
        }

        zip.getEntry(FACTORIES)?.let { entry ->
            val properties = Properties()
            zip.getInputStream(entry).use(properties::load)
            properties.stringPropertyNames().forEach { key ->
                properties.getProperty(key)
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach { implementation ->
                        registrations += SpiRegistration("spring.factories", key, implementation)
                    }
            }
        }

        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith(SERVICES_PREFIX) }
            .forEach { entry ->
                val serviceFqn = entry.name.removePrefix(SERVICES_PREFIX).trim()
                if (serviceFqn.isNotEmpty()) {
                    readProviderLines(zip, entry.name).forEach { implementation ->
                        registrations += SpiRegistration("services", serviceFqn, implementation)
                    }
                }
            }

        return registrations.sortedWith(
            compareBy<SpiRegistration>({ it.mechanism }, { it.key ?: "" }, { it.implFqn }),
        )
    }

    private fun readProviderLines(zip: ZipFile, entryName: String): List<String> =
        zip.getInputStream(zip.getEntry(entryName))
            .bufferedReader(StandardCharsets.UTF_8)
            .useLines { lines ->
                lines.map { it.substringBefore('#').trim() }
                    .filter(String::isNotEmpty)
                    .toList()
            }
}
