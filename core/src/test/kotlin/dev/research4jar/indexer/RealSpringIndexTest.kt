package dev.research4jar.indexer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.Filter
import org.springframework.boot.SpringApplication
import org.springframework.boot.actuate.beans.BeansEndpoint
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.context.ApplicationContext
import org.springframework.web.filter.GenericFilterBean
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealSpringIndexTest {
    @Test
    fun `indexes a real Spring Boot application dependency directory`() {
        val root = Files.createTempDirectory("research4jar-real-spring")
        val jars = root.resolve("jars")
        val project = root.resolve("project")
        val home = root.resolve("home")
        Files.createDirectories(jars)

        val dependencyJars = listOf(
            SpringApplication::class.java,
            DataSourceProperties::class.java,
            BeansEndpoint::class.java,
            ApplicationContext::class.java,
            GenericFilterBean::class.java,
            Filter::class.java,
        ).map { Path.of(it.protectionDomain.codeSource.location.toURI()) }
            .distinct()
        dependencyJars.forEach { source ->
            Files.copy(
                source,
                jars.resolve(source.name),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }

        val output = ByteArrayOutputStream()
        val originalOut = System.out
        try {
            System.setOut(PrintStream(output, true, StandardCharsets.UTF_8))
            main(
                arrayOf(
                    "--jars",
                    jars.toString(),
                    "--project-dir",
                    project.toString(),
                    "--home",
                    home.toString(),
                ),
            )
        } finally {
            System.setOut(originalOut)
        }

        val stats = jacksonObjectMapper().readTree(output.toString(StandardCharsets.UTF_8))
        assertEquals(dependencyJars.size, stats["jars_total"].asInt())
        assertEquals(dependencyJars.size, stats["jars_indexed"].asInt())
        assertEquals(dependencyJars.size, stats["jars_newly_indexed"].asInt())
        assertEquals(0, stats["jars_skipped"].asInt())
        assertTrue(stats["jars_missing"].isEmpty)

        val pointer = jacksonObjectMapper()
            .readTree(project.resolve(".research4jar/project.json").toFile())
        assertEquals(2, pointer["schema_version"].asInt())
        assertEquals(2, pointer["extractor_version"].asInt())
        val session = Path.of(pointer["session_db_path"].asText())
        DriverManager.getConnection("jdbc:sqlite:$session").use { connection ->
            val implementations = connection.prepareStatement(
                """
                SELECT c.fqn
                FROM classes c
                JOIN class_interfaces ci ON ci.class_id = c.id
                WHERE ci.interface_fqn = ?
                ORDER BY c.fqn
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "jakarta.servlet.Filter")
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString(1))
                    }
                }
            }
            assertTrue(GenericFilterBean::class.java.name in implementations)

            val configurationProperties = connection.prepareStatement(
                """
                SELECT c.fqn
                FROM annotations a
                JOIN classes c ON c.id = a.target_id
                WHERE a.target_kind = 'class' AND a.annotation_fqn = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(
                    1,
                    "org.springframework.boot.context.properties.ConfigurationProperties",
                )
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString(1))
                    }
                }
            }
            assertTrue(DataSourceProperties::class.java.name in configurationProperties)
        }

        val shardFiles = Files.list(home.resolve("shards")).use { stream ->
            stream.filter { it.fileName.toString().endsWith("@2.db") }.toList()
        }
        assertEquals(dependencyJars.size, shardFiles.size)
        assertTrue(
            shardFiles.any { shard ->
                DriverManager.getConnection("jdbc:sqlite:$shard").use { connection ->
                    connection.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM bean_definitions",
                    ).use { rows -> rows.next() && rows.getInt(1) > 0 }
                }
            },
        )
        assertTrue(
            shardFiles.any { shard ->
                DriverManager.getConnection("jdbc:sqlite:$shard").use { connection ->
                    connection.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM conditions",
                    ).use { rows -> rows.next() && rows.getInt(1) > 0 }
                }
            },
        )
        assertTrue(
            shardFiles.any { shard ->
                DriverManager.getConnection("jdbc:sqlite:$shard").use { connection ->
                    connection.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM string_constants",
                    ).use { rows -> rows.next() && rows.getInt(1) > 0 }
                }
            },
        )
    }
}
