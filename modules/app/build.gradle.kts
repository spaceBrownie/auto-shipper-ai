import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("org.flywaydb.flyway")
}

flyway {
    url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/autoshipper"
    user = System.getenv("DB_USERNAME") ?: "autoshipper"
    password = System.getenv("DB_PASSWORD") ?: "autoshipper"
    locations = arrayOf("classpath:db/migration")
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
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<BootRun>("bootRun") {
    if (System.getenv("SPRING_PROFILES_ACTIVE").isNullOrBlank() &&
        System.getProperty("spring.profiles.active").isNullOrBlank()
    ) {
        systemProperty("spring.profiles.active", "local")
    }
}
