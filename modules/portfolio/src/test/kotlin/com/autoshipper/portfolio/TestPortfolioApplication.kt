package com.autoshipper.portfolio

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * Minimal Spring Boot application class for portfolio module integration tests.
 * The portfolio module is a library with no main application class, so tests
 * need this entry point to bootstrap a Spring context.
 */
@SpringBootApplication(scanBasePackages = ["com.autoshipper.portfolio"])
class TestPortfolioApplication
