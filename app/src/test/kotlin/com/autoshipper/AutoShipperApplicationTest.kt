package com.autoshipper

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class AutoShipperApplicationTest {

    @Test
    fun contextLoads() {
        // Verifies Spring context loads against local PostgreSQL (application-test.yml)
    }
}
