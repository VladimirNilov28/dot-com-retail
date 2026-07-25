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
 * - Spring WebFlux
 * - GraphQL with Netflix DGS
 * - PostgreSQL
 * - R2DBC
 * - Flyway
 * - Apache Kafka
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
 * Spring WebFlux
 *       |
 *       v
 *     R2DBC
 *       |
 *       v
 *  PostgreSQL
 * ```
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
 * Kafka is used for asynchronous communication.
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
        "org.springframework.boot:spring-boot-starter-actuator"
    )

    implementation(
        "org.springframework.boot:spring-boot-starter-webflux"
    )

    implementation(
        "org.springframework.boot:spring-boot-starter-validation"
    )



    // ---------------------
    // Security
    // ---------------------

    implementation(
        "org.springframework.boot:spring-boot-starter-security"
    )

    implementation(
        "org.springframework.boot:spring-boot-starter-oauth2-client"
    )



    // ---------------------
    // Database
    // ---------------------

    // Reactive database access
    implementation(
        "org.springframework.boot:spring-boot-starter-data-r2dbc"
    )


    // Required for Flyway migrations
    implementation(
        "org.springframework.boot:spring-boot-starter-jdbc"
    )

    runtimeOnly(
        "org.postgresql:postgresql"
    )


    runtimeOnly(
        "org.postgresql:r2dbc-postgresql"
    )


    // Database migrations
    implementation(
        "org.flywaydb:flyway-core"
    )


    implementation(
        "org.flywaydb:flyway-database-postgresql"
    )



    // ---------------------
    // GraphQL
    // ---------------------

    implementation(
        "com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter"
    )



    // ---------------------
    // Messaging
    // ---------------------

    implementation(
        "org.springframework.boot:spring-boot-starter-kafka"
    )



    // ---------------------
    // API Documentation
    // ---------------------

    implementation(
        "org.springdoc:springdoc-openapi-starter-webflux-ui:3.0.2"
    )



    // ---------------------
    // Configuration Metadata
    // ---------------------

    annotationProcessor(
        "org.springframework.boot:spring-boot-configuration-processor"
    )



    // ---------------------
    // Lombok
    // ---------------------

    compileOnly(
        "org.projectlombok:lombok"
    )

    annotationProcessor(
        "org.projectlombok:lombok"
    )



    // ---------------------
    // Development
    // ---------------------

    // Automatic restart during development
    developmentOnly(
        "org.springframework.boot:spring-boot-devtools"
    )


    // Starts docker-compose services automatically
    developmentOnly(
        "org.springframework.boot:spring-boot-docker-compose"
    )



    // ---------------------
    // Testing / TDD
    // ---------------------

    testImplementation(
        "org.springframework.boot:spring-boot-starter-test"
    )


    // JUnit 5
    testImplementation(
        "org.junit.jupiter:junit-jupiter"
    )


    // Reactor Mono / Flux testing
    testImplementation(
        "io.projectreactor:reactor-test"
    )


    // Spring Security testing
    testImplementation(
        "org.springframework.security:spring-security-test"
    )


    // Mocking
    testImplementation(
        "org.mockito:mockito-core"
    )


    // Fluent assertions
    testImplementation(
        "org.assertj:assertj-core"
    )



    // ---------------------
    // Integration Testing
    // ---------------------

    testImplementation(
        "org.springframework.boot:spring-boot-testcontainers"
    )


    testImplementation(
        "org.testcontainers:testcontainers-junit-jupiter"
    )


    testImplementation(
        "org.testcontainers:testcontainers-postgresql"
    )


    testImplementation(
        "org.testcontainers:testcontainers-kafka"
    )


    testImplementation(
        "org.testcontainers:testcontainers-r2dbc"
    )



    // GraphQL tests
    testImplementation(
        "com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter-test"
    )


    // JUnit launcher
    testRuntimeOnly(
        "org.junit.platform:junit-platform-launcher"
    )


    // Lombok in tests
    testCompileOnly(
        "org.projectlombok:lombok"
    )

    testAnnotationProcessor(
        "org.projectlombok:lombok"
    )
}



// =====================================================
// Dependency Management
// =====================================================

dependencyManagement {

    imports {

        mavenBom(
            "com.netflix.graphql.dgs:graphql-dgs-platform-dependencies:${property("netflixDgsVersion")}"
        )
    }
}



// =====================================================
// Tests Configuration
// =====================================================

tasks.test {

    useJUnitPlatform()


    testLogging {

        events(
            "passed",
            "failed",
            "skipped"
        )


        exceptionFormat =
            TestExceptionFormat.FULL


        showStandardStreams = true
    }


    finalizedBy(
        tasks.jacocoTestReport
    )
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
// Code Formatting
// =====================================================

spotless {

    java {

        googleJavaFormat()
    }


    format("misc") {

        target(
            "*.md",
            "*.yml",
            "*.yaml"
        )


        trimTrailingWhitespace()

        endWithNewline()
    }
}



// =====================================================
// Build Artifact
// =====================================================

tasks.bootJar {

    // Stable name for Docker image builds
    archiveFileName.set(
        "bytecore-backend.jar"
    )
}

tasks.build {
    dependsOn(tasks.spotlessApply)
}