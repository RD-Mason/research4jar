import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm") version "2.1.21"
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "dev.research4jar"
version = project.property("VERSION_NAME").toString()

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.named<KotlinJvmCompile>("compileKotlin") {
    compilerOptions {
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
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
    implementation(project(":core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("research4jar") {
            id = "dev.research4jar"
            implementationClass = "dev.research4jar.gradle.Research4JarPlugin"
            displayName = "Research4Jar"
            description = "Index dependency jars into a local, queryable fact database " +
                "for coding agents; runs in-process with native Gradle dependency provenance."
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    coordinates(project.property("GROUP").toString(), "research4jar-gradle-plugin", project.property("VERSION_NAME").toString())
}
