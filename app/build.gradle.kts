plugins {
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    // io.spring.dependency-management and kotlin("jvm") applied by root subprojects block
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":catalog"))
    implementation(project(":pricing"))
    implementation(project(":vendor"))
    implementation(project(":fulfillment"))
    implementation(project(":capital"))
    implementation(project(":compliance"))
    implementation(project(":portfolio"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.test {
    useJUnitPlatform()
}
