import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    dependencies {
        classpath("org.flywaydb:flyway-database-postgresql:11.8.2")
    }
}

plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.spring") version "2.2.21" apply false
    kotlin("plugin.jpa") version "2.2.21" apply false
    id("org.springframework.boot") version "3.3.4" apply false
    id("org.flywaydb.flyway") version "11.8.2" apply false
}

allprojects {
    group = "com.autoshipper"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        // Use Gradle-native BOM constraints for Spring-managed dependency versions.
        if (name != "shared") {
            dependencies {
                add("implementation", platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))
                add("testImplementation", platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))
            }
        }

        configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
                // Kotlin 2.3 warning compatibility mode for constructor-parameter annotations
                freeCompilerArgs.add("-Xannotation-default-target=param-property")
            }
        }
    }
}
