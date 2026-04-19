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
                // Emit JVM parameter names (javac -parameters equivalent). Required so
                // Spring's StandardReflectionParameterNameDiscoverer can resolve
                // @PathVariable/@RequestParam names from reflection without explicit
                // value = "…" annotations. Matches Spring Boot's default assumption.
                javaParameters.set(true)
            }
        }

        tasks.withType<Test>().configureEach {
            testLogging {
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStackTraces = true
                showCauses = true
            }
        }
    }
}

// ---------------------------------------------------------------------------
// FR-030 / RAT-53 — Dev-store key audit task
// ---------------------------------------------------------------------------
//
// `devStoreAuditKeys` validates the operator's local `.env` before a Shopify
// dev-store + Stripe test-mode run (see docs/live-e2e-runbook.md Section 0).
//
// Exit-code policy:
//   Gradle's default exit code for any `GradleException` thrown inside a task
//   body is `1`. Emitting a distinct exit code (e.g. `2` for "missing .env"
//   vs `1` for "invalid key") would require `System.exit(2)`, which bypasses
//   Gradle's build lifecycle (breaks `--dry-run`, daemon reuse, build-scan
//   reporting). We deliberately keep both failure modes at exit code 1 and
//   document the distinct failure reason in stdout. CI pipelines and the
//   Round 3 test suite should grep stdout for the specific failure reason
//   (e.g. ".env not found" vs "sk_live_" vs "must end with .myshopify.com").
//
// Masking policy:
//   Secret values are NEVER printed in full. The task prints only the last 4
//   characters prefixed with `****`. Values of length <= 4 are printed as
//   `****` with no suffix. Applies to both success and failure output.
// ---------------------------------------------------------------------------
tasks.register("devStoreAuditKeys") {
    group = "verification"
    description = "Validates .env configuration for safe dev-store runs (FR-030 / RAT-53)"
    doLast {
        val envFile = rootDir.resolve(".env")
        if (!envFile.exists()) {
            logger.error(
                "ERROR: no .env file found at {}. See docs/live-e2e-runbook.md Section 0.",
                envFile.absolutePath,
            )
            throw GradleException("devStoreAuditKeys: .env not found")
        }

        val env: Map<String, String> = envFile.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx < 0) {
                    null
                } else {
                    val key = line.substring(0, idx).trim()
                    val raw = line.substring(idx + 1).trim()
                    val value = when {
                        raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"") ->
                            raw.substring(1, raw.length - 1)
                        raw.length >= 2 && raw.startsWith("'") && raw.endsWith("'") ->
                            raw.substring(1, raw.length - 1)
                        else -> raw
                    }
                    key to value
                }
            }
            .toMap()

        fun mask(v: String?): String {
            if (v == null) return "****"
            return if (v.length <= 4) "****" else "****${v.takeLast(4)}"
        }

        val failures = mutableListOf<String>()

        // STRIPE_SECRET_KEY — live-mode guard first, then test-prefix check.
        val stripeKey = env["STRIPE_SECRET_KEY"].orEmpty()
        when {
            stripeKey.isBlank() -> failures.add(
                "STRIPE_SECRET_KEY must be set and start with sk_test_ (found: blank)",
            )
            stripeKey.startsWith("sk_live_") -> failures.add(
                "STRIPE_SECRET_KEY is in LIVE mode (sk_live_) — refusing to proceed",
            )
            !stripeKey.startsWith("sk_test_") -> failures.add(
                "STRIPE_SECRET_KEY must start with sk_test_ (found: ${mask(stripeKey)})",
            )
        }

        // SHOPIFY_API_BASE_URL — must end with .myshopify.com (case-insensitive).
        val shopifyBaseUrl = env["SHOPIFY_API_BASE_URL"].orEmpty()
        if (shopifyBaseUrl.isBlank() ||
            !shopifyBaseUrl.lowercase().trimEnd('/').endsWith(".myshopify.com")
        ) {
            failures.add(
                "SHOPIFY_API_BASE_URL must end with .myshopify.com (found: ${mask(shopifyBaseUrl)})",
            )
        }

        // SHOPIFY_WEBHOOK_SECRETS — non-blank.
        val webhookSecrets = env["SHOPIFY_WEBHOOK_SECRETS"].orEmpty()
        if (webhookSecrets.isBlank()) {
            failures.add("SHOPIFY_WEBHOOK_SECRETS must be set")
        }

        // CJ_ACCESS_TOKEN — non-blank.
        val cjToken = env["CJ_ACCESS_TOKEN"].orEmpty()
        if (cjToken.isBlank()) {
            failures.add("CJ_ACCESS_TOKEN must be set")
        }

        // Sandbox-vs-dry-run invariant (warning only — no deterministic CJ sandbox prefix).
        val dryRunRaw = env["AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN"].orEmpty().trim().lowercase()
        val dryRunEnabled = dryRunRaw == "true"
        if (!dryRunEnabled && cjToken.isNotBlank()) {
            logger.warn(
                "WARN: CJ appears to be production account and dry-run is OFF — ensure you " +
                    "have a sandbox account or set AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN=true",
            )
        }

        if (failures.isNotEmpty()) {
            failures.forEach { logger.error(it) }
            throw GradleException("devStoreAuditKeys FAILED (${failures.size} violation(s))")
        }

        // Derive the Shopify domain (host portion) for the success summary — still masked via last-4.
        val shopifyDomain = shopifyBaseUrl
            .substringAfter("://", shopifyBaseUrl)
            .substringBefore('/')
            .trim()

        logger.lifecycle(
            "devStoreAuditKeys: PASS — Stripe ${mask(stripeKey)}, " +
                "Shopify $shopifyDomain (last4: ${mask(shopifyDomain)}), " +
                "CJ token ${mask(cjToken)}, " +
                "webhook secrets ${mask(webhookSecrets)}. Ready for dev-store run.",
        )
    }
}
