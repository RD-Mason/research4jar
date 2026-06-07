package dev.springdep.indexer.extract

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActuatorOracleTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `static bean and condition facts agree with live actuator endpoints`() {
        val extracted = extractOracleClasses()
        assertTrue(
            extracted.beanDefinitions.any {
                it.configFqn == OracleAutoConfiguration::class.java.name &&
                    it.beanName == "oracleBean" &&
                    it.beanTypeFqn == OracleBean::class.java.name
            },
        )
        assertTrue(
            extracted.conditions.any {
                it.classFqn == OracleAutoConfiguration::class.java.name &&
                    it.type == "OnProperty" &&
                    it.refValue ==
                    """{"havingValue":"true","name":["enabled"],"prefix":"oracle"}"""
            },
        )

        val application = SpringApplication(OracleApplication::class.java)
        application.setDefaultProperties(
            mapOf(
                "server.port" to "0",
                "management.endpoints.web.exposure.include" to "beans,conditions",
                "oracle.enabled" to "true",
                "debug" to "true",
                "spring.main.banner-mode" to "off",
            ),
        )
        application.run().use { context ->
            val port = context.environment.getRequiredProperty("local.server.port")
            val beans = getJson("http://127.0.0.1:$port/actuator/beans")
            val conditions = getJson("http://127.0.0.1:$port/actuator/conditions")

            assertTrue(beans.toString().contains("\"oracleBean\""))
            assertTrue(
                conditions.toString().contains(OracleAutoConfiguration::class.java.name) ||
                    conditions.toString().contains(OracleAutoConfiguration::class.java.simpleName),
            )
            assertTrue(conditions.toString().contains("positiveMatches"))
        }
    }

    private fun getJson(url: String) = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
        .send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        .also { assertEquals(200, it.statusCode(), it.body()) }
        .body()
        .let(objectMapper::readTree)

    private fun extractOracleClasses(): ExtractedJar {
        val jar = Files.createTempFile("springdep-actuator-oracle", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            listOf(OracleAutoConfiguration::class.java, OracleBean::class.java).forEach { type ->
                val entryName = type.name.replace('.', '/') + ".class"
                zip.putNextEntry(ZipEntry(entryName))
                type.classLoader.getResourceAsStream(entryName)!!.use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return ZipFile(jar.toFile()).use(JarExtractor(objectMapper)::extract)
    }
}

@SpringBootApplication
@Import(OracleAutoConfiguration::class)
class OracleApplication

@AutoConfiguration
@ConditionalOnProperty(prefix = "oracle", name = ["enabled"], havingValue = "true")
class OracleAutoConfiguration {
    @Bean
    fun oracleBean() = OracleBean()
}

class OracleBean
