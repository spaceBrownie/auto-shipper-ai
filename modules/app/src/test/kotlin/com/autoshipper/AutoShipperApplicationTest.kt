package com.autoshipper

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Verifies that the Spring context loads successfully against the running
 * PostgreSQL instance configured in application-test.yml.
 *
 * Migrated from Testcontainers to running Postgres (RAT-18).
 */
@SpringBootTest
@ActiveProfiles("test")
class AutoShipperApplicationTest {

    @Test
    fun contextLoads() {
        // Verifies Spring context loads against the running PostgreSQL instance.
    }
}
