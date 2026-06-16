import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.1.21"
    application
}

group = "dev.research4jar"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

tasks.named<KotlinJvmCompile>("compileTestKotlin") {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named<JavaCompile>("compileTestJava") {
    options.release.set(17)
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.0")
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.springframework.boot:spring-boot-autoconfigure:3.5.13")
    testImplementation("org.springframework.boot:spring-boot-test:3.5.13")
    testImplementation("org.springframework.boot:spring-boot-actuator:3.5.13")
    testImplementation("org.springframework.boot:spring-boot-actuator-autoconfigure:3.5.13")
    testImplementation("org.springframework.boot:spring-boot-starter-web:3.5.13")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator:3.5.13")
    testImplementation("org.springframework:spring-context:6.2.17")
    testImplementation("org.springframework:spring-web:6.2.17")
    testImplementation("jakarta.servlet:jakarta.servlet-api:6.1.0")
    testImplementation("org.assertj:assertj-core:3.27.6")
}

application {
    mainClass = "dev.research4jar.indexer.MainKt"
    applicationName = "research4jar-index"
}

tasks.jar {
    archiveBaseName = "research4jar-index"
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.test {
    useJUnitPlatform()
}

fun multiReleaseVersion(entryName: String): Int? {
    val prefix = "META-INF/versions/"
    if (!entryName.startsWith(prefix)) return null
    return entryName.removePrefix(prefix).substringBefore('/').toIntOrNull()
}

tasks.register("verifyJava11Runtime") {
    group = "verification"
    description = "Verifies the installed indexer runtime is loadable on Java 11."
    dependsOn(tasks.named("installDist"))
    inputs.dir(layout.buildDirectory.dir("install/research4jar-index/lib"))

    doLast {
        val maxMajor = 55
        val libDir = layout.buildDirectory.dir("install/research4jar-index/lib").get().asFile
        val offenders = mutableListOf<String>()
        libDir.listFiles { file -> file.extension == "jar" }
            ?.sortedBy { it.name }
            ?.forEach { jar ->
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .forEach entryLoop@{ entry ->
                            val release = multiReleaseVersion(entry.name)
                            if (release != null && release > 11) return@entryLoop
                            zip.getInputStream(entry).use { input ->
                                val header = ByteArray(8)
                                val read = input.readNBytes(header, 0, header.size)
                                if (read != header.size) {
                                    offenders += "${jar.name}!/${entry.name}: truncated class header"
                                    return@entryLoop
                                }
                                val major = ((header[6].toInt() and 0xff) shl 8) or
                                    (header[7].toInt() and 0xff)
                                if (major > maxMajor) {
                                    offenders += "${jar.name}!/${entry.name}: classfile major $major"
                                }
                            }
                        }
                }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Runtime distribution contains classes newer than Java 11:\n" +
                    offenders.take(20).joinToString("\n") +
                    if (offenders.size > 20) "\n... and ${offenders.size - 20} more" else "",
            )
        }
    }
}

tasks.check {
    dependsOn(tasks.named("verifyJava11Runtime"))
}
