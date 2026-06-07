plugins {
    kotlin("jvm") version "2.1.21"
    application
}

group = "dev.springdep"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
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
    mainClass = "dev.springdep.indexer.MainKt"
    applicationName = "springdep-index"
}

tasks.jar {
    archiveBaseName = "springdep-index"
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.test {
    useJUnitPlatform()
}
