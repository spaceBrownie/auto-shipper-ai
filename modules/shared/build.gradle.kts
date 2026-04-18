plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // Gradle TestKit — used by FR-030 DevStoreAuditKeysTaskTest
    testImplementation(gradleTestKit())
    // YAML loader used by FR-030 DevStoreConfigDefaultsTest to assert application.yml defaults
    testImplementation("org.yaml:snakeyaml:2.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}
