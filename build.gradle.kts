plugins {
    kotlin("jvm") version "1.9.25" apply false
    kotlin("plugin.spring") version "1.9.25" apply false
    kotlin("plugin.jpa") version "1.9.25" apply false
    id("org.springframework.boot") version "3.3.4" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "com.autoshipper"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    // Apply Spring BOM to all modules except the pure-Kotlin shared module
    if (name != "shared") {
        apply(plugin = "io.spring.dependency-management")
        configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
            imports {
                mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.4")
            }
        }
    }

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }
}
