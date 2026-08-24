/**
 * ByteCore E-commerce Backend
 *
 * Spring Boot 4 backend: Web (MVC), GraphQL (Netflix DGS), PostgreSQL via
 * Spring Data JPA, Flyway-owned schema (ddl-auto=validate), Kafka via plain
 * spring-kafka listeners (@KafkaListener).
 *
 * Commands:
 *   ./gradlew bootRun            run the application
 *   ./gradlew test               run tests
 *   ./gradlew test -Pgroup=unit  run tests tagged "unit" (also: graphql, integration, e2e; comma-separated)
 *   ./gradlew jacocoTestReport   generate coverage
 *   ./gradlew spotlessApply      format code
 *   ./gradlew spotlessCheck      check formatting (CI)
 *   ./gradlew build              build application
 *
 * @author ByteCore Team
 * @since 0.0.1
 */

import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// =====================================================
// Plugins
// =====================================================

plugins {
    java

    // Spring Boot build lifecycle
    id("org.springframework.boot") version "4.0.7"

    // Spring dependency version management
    id("io.spring.dependency-management") version "1.1.7"

    // Code formatting
    id("com.diffplug.spotless") version "7.0.3"

    // Test coverage
    id("jacoco")

    // Static analysis
    id("org.sonarqube") version "6.2.0.5505"

    // Dependency vulnerability scanning
    id("org.owasp.dependencycheck") version "12.1.3"
}

// =====================================================
// Project Metadata
// =====================================================

group = "ee.bytecore"
version = "0.0.1-SNAPSHOT"
description = "ByteCore e-commerce backend"

// =====================================================
// Java Configuration
// =====================================================

java {
    toolchain {
        // Project uses Java 21 LTS
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// =====================================================
// Repositories
// =====================================================

repositories {
    mavenCentral()
}

// =====================================================
// Dependencies
// =====================================================

dependencies {
    // ---------------------
    // Core
    // ---------------------
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // ---------------------
    // Security
    // ---------------------
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    // ---------------------
    // Database
    // ---------------------

    // JPA / Hibernate (schema owned by Flyway, ddl-auto=validate)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    // Database migrations
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // ---------------------
    // GraphQL
    // ---------------------
    implementation(platform("com.netflix.graphql.dgs:graphql-dgs-platform-dependencies:12.0.1"))
    implementation("com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter")
    implementation("com.graphql-java:graphql-java-extended-scalars")

    // DGS requires json-path 3.0.0 (Jackson 3 support); Spring Boot's own
    // dependency management otherwise downgrades it to the Jackson 2 era 2.10.0.
    implementation("com.jayway.jsonpath:json-path:3.0.0")

    // ---------------------
    // Messaging
    // ---------------------

    // Plain blocking Kafka listeners (@KafkaListener) — no reactor-kafka
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    // ---------------------
    // API Documentation
    // ---------------------
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // ---------------------
    // Configuration Metadata
    // ---------------------
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // ---------------------
    // Lombok
    // ---------------------
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // ---------------------
    // Development
    // ---------------------

    // Automatic restart during development
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // Starts docker-compose services automatically
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // ---------------------
    // Testing / TDD
    // ---------------------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-starter-graphql-test")

    // ---------------------
    // Integration Testing
    // ---------------------
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")

    // GraphQL tests
    testImplementation(platform("com.netflix.graphql.dgs:graphql-dgs-platform-dependencies:12.0.1"))
    testImplementation("com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter-test")

    // JUnit launcher
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Test compilation reuses the main Lombok configuration instead of
// redeclaring the dependency for testCompileOnly/testAnnotationProcessor.
configurations {
    testCompileOnly.get().extendsFrom(compileOnly.get())
    testAnnotationProcessor.get().extendsFrom(annotationProcessor.get())
}

// =====================================================
// Tests Configuration
// =====================================================

tasks.named<Test>("test") {
    useJUnitPlatform()

    // Filter by tag: ./gradlew test -Pgroup=unit  (comma-separated: -Pgroup=unit,graphql)
    val groups = providers.gradleProperty("group").orNull

    if (!groups.isNullOrBlank()) {
        useJUnitPlatform {
            includeTags(
                *groups
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toTypedArray(),
            )
        }
    }
}

// This section causes useful test output to go to the terminal.
tasks.test {
    // Full output flag: ./gradlew test -Pfull
    val fullOutput = project.hasProperty("full")

    // Colors on by default. Disable with -Pnocolor or NO_COLOR env var.
    val useColor = !project.hasProperty("nocolor") && System.getenv("NO_COLOR") == null
    val reset = if (useColor) "[0m" else ""
    val green = if (useColor) "[32m" else ""
    val red = if (useColor) "[31m" else ""
    val bold = if (useColor) "[1m" else ""

    testLogging {
        events("passed", "skipped", "failed") // , "standardOut", "standardError"

        showExceptions = true
        exceptionFormat = if (fullOutput) TestExceptionFormat.FULL else TestExceptionFormat.SHORT
        showCauses = true
        showStackTraces = fullOutput

        // Application logs (Tomcat, Hikari, JPA, etc.) only in full mode: ./gradlew test -Pfull
        showStandardStreams = fullOutput
    }

    // Print a short summary at the end + a link to the full HTML report
    addTestListener(
        object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}

            override fun beforeTest(testDescriptor: TestDescriptor) {}

            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}

            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                if (suite.parent == null) {
                    val htmlReport =
                        layout.buildDirectory
                            .file("reports/tests/test/index.html")
                            .get()
                            .asFile

                    val summaryColor = if (result.failedTestCount > 0) red else green

                    println()
                    println(
                        "${bold}${summaryColor}Summary: ${result.resultType}, " +
                            "total: ${result.testCount}, " +
                            "failed: ${result.failedTestCount}, " +
                            "skipped: ${result.skippedTestCount}$reset",
                    )
                    println("Full report: file://$htmlReport")
                }
            }
        },
    )

    finalizedBy(tasks.jacocoTestReport)
}

// =====================================================
// JaCoCo Coverage
// =====================================================

jacoco {
    toolVersion = "0.8.13"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
}

// =====================================================
// Code Formatting (Palantir Java Format)
// =====================================================

spotless {
    // Only format files changed relative to main — avoids reformatting
    // the entire legacy codebase on first run. Remove this line if you
    // want spotless to check/format the whole project every time.
    ratchetFrom("origin/main")

    java {
        target("src/**/*.java")
        targetExclude("**/build/**", "**/generated/**")

        // Version pinned explicitly so formatting doesn't silently
        // change when Spotless updates its default Palantir version.
        // Palantir Java Format uses 2-space indentation by default and
        // keeps Spring/JPA/DGS/JUnit annotations clean and readable.
        palantirJavaFormat("2.50.0")
        formatAnnotations()

        removeUnusedImports()

        // Import groups in order; trailing "" is the catch-all group
        // for anything not matching the explicit prefixes above it.
        importOrder("java", "javax", "org.springframework", "ee.bytecore", "")

        trimTrailingWhitespace()
        endWithNewline()
    }

    format("misc") {
        target("*.md", "*.yml", "*.yaml")

        trimTrailingWhitespace()
        endWithNewline()
    }

    kotlinGradle {
        target("*.gradle.kts")

        // Version pinned explicitly, same reasoning as googleJavaFormat above.
        // Rules below mirror Google's Kotlin style guide (4-space indent, 100-col lines).
        ktlint("1.5.0")
            .editorConfigOverride(
                mapOf(
                    "indent_size" to "4",
                    "max_line_length" to "100",
                    "insert_final_newline" to "true",
                ),
            )

        trimTrailingWhitespace()
        endWithNewline()
    }
}

// Formatting check runs as part of `check` (and therefore CI),
// separate from spotlessApply which is for local dev use.
tasks.named("check") {
    dependsOn(tasks.spotlessCheck)
}

// =====================================================
// Build Artifact
// =====================================================

tasks.bootJar {
    // Stable name for Docker image builds
    archiveFileName.set("bytecore-backend.jar")
}

tasks.build {
    dependsOn(tasks.spotlessApply)
}
