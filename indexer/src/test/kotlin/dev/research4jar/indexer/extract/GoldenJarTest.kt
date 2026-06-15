package dev.research4jar.indexer.extract

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.context.weaving.LoadTimeWeaverAwareProcessor
import org.springframework.web.filter.GenericFilterBean
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoldenJarTest {
    private val extractor = JarExtractor(jacksonObjectMapper())

    @Test
    fun `spring boot autoconfigure golden facts remain stable`() {
        val extracted = extractJarContaining(DataSourceProperties::class.java)

        assertEquals(
            156,
            extracted.spiRegistrations.count { it.mechanism == "autoconfig.imports" },
        )
        assertTrue(
            extracted.annotations.any {
                it.targetKind == "class" &&
                    it.classFqn == DataSourceProperties::class.java.name &&
                    it.annotationFqn ==
                    "org.springframework.boot.context.properties.ConfigurationProperties" &&
                    it.attributes == """{"value":"spring.datasource"}"""
            },
        )
        assertTrue(
            extracted.beanDefinitions.any {
                it.configFqn ==
                    "org.springframework.boot.autoconfigure.jdbc.DataSourceConfiguration\$Hikari" &&
                    it.beanName == "dataSource" &&
                    it.beanTypeFqn == "com.zaxxer.hikari.HikariDataSource"
            },
        )
        assertTrue(
            extracted.conditions.any {
                it.classFqn ==
                    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration" &&
                    it.type == "OnClass" &&
                    it.refValue ==
                    """{"value":["javax.sql.DataSource","org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType"]}"""
            },
        )
    }

    @Test
    fun `spring framework golden facts cover direct SPI and constants`() {
        val context = extractJarContaining(LoadTimeWeaverAwareProcessor::class.java)
        assertTrue(
            context.classes.any {
                it.fqn == LoadTimeWeaverAwareProcessor::class.java.name &&
                    "org.springframework.beans.factory.config.BeanPostProcessor" in it.interfaces
            },
        )

        val web = extractJarContaining(GenericFilterBean::class.java)
        assertTrue(
            web.classes.any {
                it.fqn == GenericFilterBean::class.java.name &&
                    "jakarta.servlet.Filter" in it.interfaces
            },
        )
        assertTrue(
            web.stringConstants.any {
                it.classFqn == "org.springframework.http.HttpHeaders" && it.value == "Accept"
            },
        )
    }

    private fun extractJarContaining(type: Class<*>): ExtractedJar {
        val path = Path.of(type.protectionDomain.codeSource.location.toURI())
        return ZipFile(path.toFile()).use(extractor::extract)
    }
}
