import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm") version "2.1.21"
    id("de.benediktritter.maven-plugin-development") version "0.4.3"
}

group = "dev.research4jar"
version = "0.1.0"

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

mavenPlugin {
    artifactId.set("research4jar-maven-plugin")
    goalPrefix.set("research4jar")
}

dependencies {
    implementation(project(":core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.0")

    compileOnly("org.apache.maven:maven-plugin-api:3.9.9")
    compileOnly("org.apache.maven:maven-core:3.9.9")
    compileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:3.15.1")
}
