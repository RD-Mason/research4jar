package dev.springdep.indexer.extract

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtractorsTest {
    @Test
    fun `extracts both metadata sources coordinate and tolerates one bad json file`() {
        val jar = Files.createTempFile("springdep-extractor", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.writeEntry(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                """
                # comment
                example.ImportsConfig
                example.SharedConfig

                """.trimIndent(),
            )
            zip.writeEntry(
                "META-INF/spring.factories",
                """
                org.springframework.boot.autoconfigure.EnableAutoConfiguration=example.LegacyConfig,\
                 example.SharedConfig
                """.trimIndent(),
            )
            zip.writeEntry(
                "META-INF/spring-configuration-metadata.json",
                """
                {
                  "groups": [],
                  "properties": [
                    {
                      "name": "spring.datasource.enabled",
                      "type": "java.lang.Boolean",
                      "defaultValue": true,
                      "description": "Whether it is enabled.",
                      "sourceType": "example.DataSourceProperties"
                    },
                    {
                      "name": "single",
                      "defaultValue": ["a", "b"]
                    }
                  ],
                  "hints": []
                }
                """.trimIndent(),
            )
            zip.writeEntry(
                "META-INF/additional-spring-configuration-metadata.json",
                "{ invalid json",
            )
            zip.writeEntry(
                "META-INF/maven/com.example/demo/pom.properties",
                "groupId=com.example\nartifactId=demo\nversion=1.2.3\n",
            )
        }

        val extracted = ZipFile(jar.toFile()).use(JarExtractor(jacksonObjectMapper())::extract)
        assertEquals("com.example:demo:1.2.3", extracted.coordinate)
        assertEquals(
            listOf(
                SpiRegistration("autoconfig.imports", null, "example.ImportsConfig"),
                SpiRegistration("autoconfig.imports", null, "example.SharedConfig"),
                SpiRegistration(
                    "spring.factories",
                    "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
                    "example.LegacyConfig",
                ),
                SpiRegistration(
                    "spring.factories",
                    "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
                    "example.SharedConfig",
                ),
            ),
            extracted.spiRegistrations,
        )
        assertEquals(2, extracted.configProperties.size)
        val dataSource = extracted.configProperties.single {
            it.name == "spring.datasource.enabled"
        }
        assertEquals("spring.datasource", dataSource.prefix)
        assertEquals("true", dataSource.defaultValue)
        assertEquals("example.DataSourceProperties", dataSource.sourceFqn)
        val single = extracted.configProperties.single { it.name == "single" }
        assertNull(single.prefix)
        assertEquals("""["a","b"]""", single.defaultValue)
        assertTrue(extracted.warnings.single().contains("additional-spring-configuration-metadata.json"))
    }

    @Test
    fun `malformed Maven coordinates do not fail jar extraction`() {
        val jar = Files.createTempFile("springdep-bad-coordinate", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.writeEntry(
                "META-INF/maven/example/demo/pom.properties",
                """groupId=\uZZZZ""",
            )
            zip.writeEntry(
                "META-INF/spring-configuration-metadata.json",
                """{"properties":[{"name":"demo.enabled"}]}""",
            )
        }

        val extracted = ZipFile(jar.toFile()).use(JarExtractor(jacksonObjectMapper())::extract)
        assertNull(extracted.coordinate)
        assertEquals("demo.enabled", extracted.configProperties.single().name)
    }

    @Test
    fun `ASM extraction captures direct facts without loading classes`() {
        val jar = Files.createTempFile("springdep-bytecode", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("example/DemoConfig.class"))
            zip.write(demoConfigurationClass())
            zip.closeEntry()
            zip.writeEntry(
                "META-INF/spring.factories",
                """
                example.Factory=example.One,\
                 example.Two
                another.Factory=example.Three
                """.trimIndent(),
            )
            zip.writeEntry(
                "META-INF/services/example.Service",
                """
                # provider comment
                example.Provider
                example.SecondProvider # inline comment
                """.trimIndent(),
            )
        }

        val extracted = ZipFile(jar.toFile()).use(JarExtractor(jacksonObjectMapper())::extract)
        val extractedClass = extracted.classes.single()
        assertEquals("example.DemoConfig", extractedClass.fqn)
        assertEquals("class", extractedClass.kind)
        assertEquals(listOf("jakarta.servlet.Filter"), extractedClass.interfaces)
        assertEquals("DemoConfig.java", extractedClass.sourceFile)
        assertEquals(listOf("bean"), extracted.methods.map(ExtractedMethod::name))

        val classAnnotations = extracted.annotations.filter { it.targetKind == "class" }
        assertEquals(
            setOf(
                "org.springframework.boot.context.properties.ConfigurationProperties",
                "org.springframework.boot.autoconfigure.condition.ConditionalOnClass",
            ),
            classAnnotations.map(ExtractedAnnotation::annotationFqn).toSet(),
        )
        assertEquals(
            """{"value":["java.lang.String"]}""",
            classAnnotations.single {
                it.annotationFqn.endsWith("ConditionalOnClass")
            }.attributes,
        )
        assertEquals("namedBean", extracted.beanDefinitions.single().beanName)
        assertEquals("java.lang.String", extracted.beanDefinitions.single().beanTypeFqn)
        assertEquals(
            setOf("OnClass", "OnProperty"),
            extracted.conditions.map(ExtractedCondition::type).toSet(),
        )
        assertEquals(
            setOf("field-value", "method-value"),
            extracted.stringConstants.map(ExtractedStringConstant::value).toSet(),
        )
        assertEquals(
            listOf(
                SpiRegistration("services", "example.Service", "example.Provider"),
                SpiRegistration("services", "example.Service", "example.SecondProvider"),
                SpiRegistration("spring.factories", "another.Factory", "example.Three"),
                SpiRegistration("spring.factories", "example.Factory", "example.One"),
                SpiRegistration("spring.factories", "example.Factory", "example.Two"),
            ),
            extracted.spiRegistrations,
        )
    }

    @Test
    fun `unsupported class version becomes a warning without failing the jar`() {
        val jar = Files.createTempFile("springdep-unsupported-class", ".jar")
        val unsupported = demoConfigurationClass().copyOf().apply {
            this[6] = 0x7f
            this[7] = 0xff.toByte()
        }
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("example/DemoConfig.class"))
            zip.write(demoConfigurationClass())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("future/FutureClass.class"))
            zip.write(unsupported)
            zip.closeEntry()
        }

        val extracted = ZipFile(jar.toFile()).use(JarExtractor(jacksonObjectMapper())::extract)
        assertEquals(listOf("example.DemoConfig"), extracted.classes.map(ExtractedClass::fqn))
        assertTrue(extracted.warnings.single().contains("future/FutureClass.class"))
    }

    private fun demoConfigurationClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC,
            "example/DemoConfig",
            null,
            "java/lang/Object",
            arrayOf("jakarta/servlet/Filter"),
        )
        writer.visitSource("DemoConfig.java", null)
        writer.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "KEY",
            "Ljava/lang/String;",
            null,
            "field-value",
        ).visitEnd()
        writer.visitAnnotation(
            "Lorg/springframework/boot/context/properties/ConfigurationProperties;",
            true,
        ).apply {
            visit("prefix", "demo")
            visitEnd()
        }
        writer.visitAnnotation(
            "Lorg/springframework/boot/autoconfigure/condition/ConditionalOnClass;",
            true,
        ).apply {
            visitArray("value").apply {
                visit(null, Type.getType("Ljava/lang/String;"))
                visitEnd()
            }
            visitEnd()
        }
        writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "bean",
            "()Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitAnnotation("Lorg/springframework/context/annotation/Bean;", true).apply {
                visitArray("name").apply {
                    visit(null, "namedBean")
                    visitEnd()
                }
                visitEnd()
            }
            visitAnnotation(
                "Lorg/springframework/boot/autoconfigure/condition/ConditionalOnProperty;",
                true,
            ).apply {
                visit("name", "demo.enabled")
                visit("havingValue", "true")
                visit("matchIfMissing", false)
                visitEnd()
            }
            visitCode()
            visitLdcInsn("method-value")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC,
            "syntheticMethod",
            "()V",
            null,
            null,
        ).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun ZipOutputStream.writeEntry(name: String, body: String) {
        putNextEntry(ZipEntry(name))
        write(body.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }
}
