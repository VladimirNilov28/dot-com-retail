/**
 * # ByteCore E-commerce Backend Build Configuration
 *
 * Main Gradle configuration for the ByteCore backend application.
 *
 * This file defines:
 *
 * - Project metadata
 * - Java version and build configuration
 * - Application dependencies
 * - Testing environment
 * - Code quality tools
 * - Security scanning
 * - Production packaging
 *
 *
 * ## Technology Stack
 *
 * Backend:
 *
 * - Java 21
 * - Spring Boot 4.0.7
 * - Spring Web (MVC)
 * - GraphQL with Netflix DGS
 * - PostgreSQL
 * - Spring Data JPA / Hibernate
 * - Flyway
 * - Apache Kafka (spring-kafka)
 *
 *
 * ## Development Approach
 *
 * The project follows TDD principles:
 *
 * ```
 * Test
 *  |
 * Implementation
 *  |
 * Refactoring
 * ```
 *
 * Testing layers:
 *
 * - Unit tests:
 *   Business logic validation.
 *
 * - Integration tests:
 *   Application + infrastructure validation
 *   using Testcontainers.
 *
 *
 * ## Database Strategy
 *
 * Runtime database access:
 *
 * ```
 * Spring Web (MVC)
 *       |
 *       v
 *  Spring Data JPA
 *       |
 *       v
 *  PostgreSQL
 * ```
 *
 * Flyway owns the schema (source of truth); JPA/Hibernate is
 * configured with ddl-auto=validate and never generates or
 * alters schema.
 *
 * Database migrations:
 *
 * ```
 * Flyway
 *    |
 *    v
 * db/migration
 *
 * V1__create_users.sql
 * V2__create_products.sql
 * ```
 *
 *
 * ## Messaging Architecture
 *
 * Kafka is used for asynchronous communication via plain
 * spring-kafka listeners (@KafkaListener) — no reactive stack.
 *
 * Example:
 *
 * ```
 * Order Created
 *       |
 *       v
 * Kafka Event
 *       |
 *       +--> Inventory
 *       |
 *       +--> Notifications
 * ```
 *
 *
 * ## Build Commands
 *
 * Run application:
 *
 * ```
 * ./gradlew bootRun
 * ```
 *
 * Run tests:
 *
 * ```
 * ./gradlew test
 * ```
 *
 * Generate coverage:
 *
 * ```
 * ./gradlew jacocoTestReport
 * ```
 *
 * Format code:
 *
 * ```
 * ./gradlew spotlessApply
 * ```
 *
 * Check formatting (CI):
 *
 * ```
 * ./gradlew spotlessCheck
 * ```
 *
 * Build application:
 *
 * ```
 * ./gradlew build
 * ```
 *
 *
 * ## CI/CD Pipeline
 *
 * Prepared for Jenkins automation:
 *
 * ```
 * Git
 *  |
 * Jenkins
 *  |
 * Gradle Build
 *  |
 * Tests + Quality Checks
 *  |
 * Docker Image
 *  |
 * Kubernetes
 * ```
 *
 *
 * @author ByteCore Team
 * @since 0.0.1
 */

import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.KotlinClosure2

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
// Dependency Versions
// =====================================================

extra["netflixDgsVersion"] = "11.1.0"

// =====================================================
// Dependencies
// =====================================================

dependencies {

    // ---------------------
    // Core
    // ---------------------

    implementation(
        "org.springframework.boot:spring-boot-starter-actuator",
    )

    implementation(
        "org.springframework.boot:spring-boot-starter-web",
    )

    implementation(
        "org.springframework.boot:spring-boot-starter-validation",
    )

    // ---------------------
    // Security
    // ---------------------

    implementation(
        "org.springframework.boot:spring-boot-starter-security",
    )

    implementation(
        "org.springframework.boot:spring-boot-starter-oauth2-client",
    )

    // ---------------------
    // Database
    // ---------------------

    // JPA / Hibernate (schema owned by Flyway, ddl-auto=validate)
    implementation(
        "org.springframework.boot:spring-boot-starter-data-jpa",
    )

    runtimeOnly(
        "org.postgresql:postgresql",
    )

    // Database migrations
    implementation("org.springframework.boot:spring-boot-starter-flyway")

//    implementation(
//        "org.flywaydb:flyway-core"
//    )
//

    implementation(
        "org.flywaydb:flyway-database-postgresql",
    )

    // ---------------------
    // GraphQL
    // ---------------------

    implementation(
        "com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter",
    )

    implementation(
        "com.netflix.graphql.dgs:graphql-dgs-platform-dependencies"
    )

    implementation("com.graphql-java:graphql-java-extended-scalars:24.0")
    implementation("com.tailrocks.graphql:graphql-datetime-dgs-starter:6.0.0")

    // ---------------------
    // Messaging
    // ---------------------

    // Plain blocking Kafka listeners (@KafkaListener) — no reactor-kafka
    implementation(
        "org.springframework.boot:spring-boot-starter-kafka",
    )

    // ---------------------
    // API Documentation
    // ---------------------

    implementation(
        "org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2",
    )

    // ---------------------
    // Configuration Metadata
    // ---------------------

    annotationProcessor(
        "org.springframework.boot:spring-boot-configuration-processor",
    )

    // ---------------------
    // Lombok
    // ---------------------

    compileOnly(
        "org.projectlombok:lombok",
    )

    annotationProcessor(
        "org.projectlombok:lombok",
    )

    // ---------------------
    // Development
    // ---------------------

    // Automatic restart during development
    developmentOnly(
        "org.springframework.boot:spring-boot-devtools",
    )

    // Starts docker-compose services automatically
    developmentOnly(
        "org.springframework.boot:spring-boot-docker-compose",
    )

    // ---------------------
    // Testing / TDD
    // ---------------------

    testImplementation(
        "org.springframework.boot:spring-boot-starter-test",
    )

    // JUnit 5
    testImplementation(
        "org.junit.jupiter:junit-jupiter",
    )

    // Spring Security testing
    testImplementation(
        "org.springframework.security:spring-security-test",
    )

    // Mocking
    testImplementation(
        "org.mockito:mockito-core",
    )

    // Fluent assertions
    testImplementation(
        "org.assertj:assertj-core",
    )

    // ---------------------
    // Integration Testing
    // ---------------------

    testImplementation(
        "org.springframework.boot:spring-boot-testcontainers",
    )

    testImplementation(
        "org.testcontainers:testcontainers-junit-jupiter",
    )

    testImplementation(
        "org.testcontainers:testcontainers-postgresql",
    )

    testImplementation(
        "org.testcontainers:testcontainers-kafka",
    )

    // GraphQL tests
    testImplementation(
        "com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter-test",
    )

    // JUnit launcher
    testRuntimeOnly(
        "org.junit.platform:junit-platform-launcher",
    )

    // Lombok in tests
    testCompileOnly(
        "org.projectlombok:lombok",
    )

    testAnnotationProcessor(
        "org.projectlombok:lombok",
    )
}

// =====================================================
// Dependency Management
// =====================================================

dependencyManagement {

    imports {

        mavenBom(
            "com.netflix.graphql.dgs:graphql-dgs-platform-dependencies:${property("netflixDgsVersion")}",
        )
    }
}

// =====================================================
// Tests Configuration
// =====================================================

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// This section causes useful test output to go to the terminal.
tasks.test {

    // Full output flag: ./gradlew test -Pfull
    val fullOutput = project.hasProperty("full")

    // Colors on by default. Disable with -Pnocolor or NO_COLOR env var.
    val useColor = !project.hasProperty("nocolor") && System.getenv("NO_COLOR") == null
    val reset = if (useColor) "\u001B[0m" else ""
    val green = if (useColor) "\u001B[32m" else ""
    val red = if (useColor) "\u001B[31m" else ""
    val bold = if (useColor) "\u001B[1m" else ""

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
    afterSuite(
        KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
            if (descriptor.parent == null) {
                val htmlReport =
                    layout.buildDirectory
                        .file("reports/tests/test/index.html")
                        .get()
                        .asFile

                val summaryColor = if (result.failedTestCount > 0) red else green

                println()
                println(
                    "${bold}${summaryColor}Summary: ${result.resultType}, total: ${result.testCount}, " +
                        "failed: ${result.failedTestCount}, skipped: ${result.skippedTestCount}$reset",
                )
                println("Full report: file://$htmlReport")
            }
        }),
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
// Code Formatting (Google Java Style)
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
        // change when Spotless updates its default GJF version.
        googleJavaFormat("1.28.0")
        // .aosp() // uncomment for 4-space indent instead of Google's default 2-space

        removeUnusedImports()

        // Import groups in order; trailing "" is the catch-all group
        // for anything not matching the explicit prefixes above it.
        importOrder("java", "javax", "org.springframework", "ee.bytecore", "")

        trimTrailingWhitespace()
        endWithNewline()
    }

    format("misc") {

        target(
            "*.md",
            "*.yml",
            "*.yaml",
        )

        trimTrailingWhitespace()

        endWithNewline()
    }

    kotlinGradle {

        target("*.gradle.kts")

        ktlint()

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
    archiveFileName.set(
        "bytecore-backend.jar",
    )
}

tasks.build {
    dependsOn(tasks.spotlessApply)
}
