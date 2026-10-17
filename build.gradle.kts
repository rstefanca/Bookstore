plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "cz.codingmonkey"
version = "0.0.1-SNAPSHOT"
description = "Bookstore: Spring Boot REST API + PostgreSQL + Spring Modulith + Testcontainers"

java {
    toolchain {
        // Java 25 (LTS); Gradle downloads Temurin 25 if it isn't installed.
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

repositories {
    mavenCentral()
}

configurations.all {
    // Log4j 2 instead of Spring Boot's default Logback.
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
}

extra["springModulithVersion"] = "2.1.1"
extra["springAiVersion"] = "2.0.1"
// Latest stable Log4j 2 (Spring Boot 4.1.1 manages 2.25.x; Log4j 3 is still in beta).
extra["log4j2.version"] = "2.26.1"
// OpenAPI spec + Swagger UI; 3.1.x is built against Spring Boot 4.1 (not managed by the Boot BOM).
extra["springdocVersion"] = "3.1.1"

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

springBoot {
    // META-INF/build-info.properties, shown by /actuator/info. Without the build time, so an unchanged build stays
    // up to date.
    buildInfo {
        excludes.set(setOf("time"))
    }
}

// Mockito's agent, attached at JVM start: loading it dynamically is deprecated and will be blocked by later JDKs.
val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-log4j2")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${property("springdocVersion")}")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    implementation("org.springframework.ai:spring-ai-transformers")
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-docs")
    testImplementation("org.springframework.modulith:spring-modulith-events-core")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}
