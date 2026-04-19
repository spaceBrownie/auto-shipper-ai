package com.autoshipper.shared.devstoreaudit

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the root-level Gradle `devStoreAuditKeys` task (FR-030 / RAT-53).
 *
 * Approach: copy-task-body. The production task lives in the root `build.gradle.kts`.
 * Inlining it here lets us isolate the validation logic under Gradle TestKit without
 * pulling in the full project (which would require Postgres, all modules, etc.).
 *
 * If the production task body diverges, THIS TEST LOCAL COPY MUST BE UPDATED IN LOCKSTEP.
 * The copy lives in [TASK_BODY] below. A secondary test (`T-production-parity`) asserts
 * the production build.gradle.kts still contains the marker strings we rely on; this
 * surfaces drift without requiring a full rewrite when only formatting changes.
 *
 * Exit-code policy: The production task throws GradleException for both "missing .env"
 * and "invalid key" — both exit 1. Tests distinguish via stdout substrings.
 *
 * This test lives in `:shared` (not `:app`) because Gradle TestKit's transitive
 * dependencies include a `gradle-api` jar that ships a SLF4J binding which
 * collides with Spring Boot's Logback in @SpringBootTest contexts. `:shared` has
 * no Spring dependency and is therefore safe.
 */
class DevStoreAuditKeysTaskTest {

    @TempDir lateinit var projectDir: Path

    private fun writeBuildFile() {
        Files.writeString(
            projectDir.resolve("settings.gradle.kts"),
            """rootProject.name = "devstoreaudit-test"""",
        )
        Files.writeString(projectDir.resolve("build.gradle.kts"), TASK_BODY)
    }

    private fun writeEnv(content: String) {
        Files.writeString(projectDir.resolve(".env"), content)
    }

    private fun runTask(expectFailure: Boolean = false): BuildResult {
        val runner = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments("devStoreAuditKeys", "--stacktrace")
            .forwardOutput()
        return if (expectFailure) runner.buildAndFail() else runner.build()
    }

    private val validEnv = """
        STRIPE_SECRET_KEY=sk_test_abcdef1234567890
        SHOPIFY_API_BASE_URL=https://my-devstore.myshopify.com
        SHOPIFY_WEBHOOK_SECRETS=whsec_dev_1
        CJ_ACCESS_TOKEN=cj_token_abc1234
        AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN=true
    """.trimIndent()

    @Test
    fun `T-53 valid env passes and prints PASS`() {
        writeBuildFile()
        writeEnv(validEnv)
        val result = runTask()
        assert(result.output.contains("PASS")) {
            "Expected 'PASS' in output, got:\n${result.output}"
        }
    }

    @Test
    fun `T-54 sk_live key fails with LIVE-mode guard`() {
        writeBuildFile()
        writeEnv(
            validEnv.replace(
                "STRIPE_SECRET_KEY=sk_test_abcdef1234567890",
                "STRIPE_SECRET_KEY=sk_live_abcdef1234567890",
            ),
        )
        val result = runTask(expectFailure = true)
        assert(result.output.contains("sk_live_") || result.output.contains("LIVE mode")) {
            "Expected sk_live live-mode failure marker in output:\n${result.output}"
        }
    }

    @Test
    fun `T-55 wrong Stripe prefix fails with explanatory message`() {
        writeBuildFile()
        writeEnv(
            validEnv.replace(
                "STRIPE_SECRET_KEY=sk_test_abcdef1234567890",
                "STRIPE_SECRET_KEY=xxx_test_abcdef1234567890",
            ),
        )
        val result = runTask(expectFailure = true)
        assert(result.output.contains("must start with sk_test_")) {
            "Expected 'must start with sk_test_' in output:\n${result.output}"
        }
    }

    @Test
    fun `T-56 non-myshopify URL fails`() {
        writeBuildFile()
        writeEnv(
            validEnv.replace(
                "SHOPIFY_API_BASE_URL=https://my-devstore.myshopify.com",
                "SHOPIFY_API_BASE_URL=https://myshop.mycompany.com",
            ),
        )
        val result = runTask(expectFailure = true)
        assert(result.output.contains("must end with .myshopify.com")) {
            "Expected '.myshopify.com' failure in output:\n${result.output}"
        }
    }

    @Test
    fun `T-57 blank webhook secrets fails`() {
        writeBuildFile()
        writeEnv(
            validEnv.replace(
                "SHOPIFY_WEBHOOK_SECRETS=whsec_dev_1",
                "SHOPIFY_WEBHOOK_SECRETS=",
            ),
        )
        val result = runTask(expectFailure = true)
        assert(result.output.contains("SHOPIFY_WEBHOOK_SECRETS must be set")) {
            "Expected 'SHOPIFY_WEBHOOK_SECRETS must be set' in output:\n${result.output}"
        }
    }

    @Test
    fun `T-58 missing CJ access token fails`() {
        writeBuildFile()
        val withoutCj = validEnv.lines()
            .filterNot { it.startsWith("CJ_ACCESS_TOKEN=") }
            .joinToString("\n")
        writeEnv(withoutCj)
        val result = runTask(expectFailure = true)
        assert(result.output.contains("CJ_ACCESS_TOKEN must be set")) {
            "Expected 'CJ_ACCESS_TOKEN must be set' in output:\n${result.output}"
        }
    }

    @Test
    fun `T-59 full secret value is never printed, only last-4 mask`() {
        writeBuildFile()
        val stripeValue = "sk_test_abcdef1234567890wxyz"
        val env = """
            STRIPE_SECRET_KEY=$stripeValue
            SHOPIFY_API_BASE_URL=https://my-devstore.myshopify.com
            SHOPIFY_WEBHOOK_SECRETS=whsec_dev_secret_value_1
            CJ_ACCESS_TOKEN=cj_token_abcd1234_full
            AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN=true
        """.trimIndent()
        writeEnv(env)
        val result = runTask()
        assert(!result.output.contains(stripeValue)) {
            "Full Stripe key value leaked in output:\n${result.output}"
        }
        assert(!result.output.contains("abcdef1234567890")) {
            "Middle of Stripe key leaked (only last-4 allowed):\n${result.output}"
        }
        assert(!result.output.contains("cj_token_abcd1234_full")) {
            "Full CJ token leaked in output:\n${result.output}"
        }
        assert(result.output.contains("****")) {
            "Expected '****' masking prefix in output:\n${result.output}"
        }
        val stripeLast4 = stripeValue.takeLast(4)
        assert(result.output.contains("****$stripeLast4")) {
            "Expected masked Stripe last-4 ****$stripeLast4 in output:\n${result.output}"
        }
    }

    @Test
    fun `T-60 comment lines are never echoed to output`() {
        writeBuildFile()
        val withComment = """
            # SECRET_NOTES=do-not-echo-this-string-abc123
            $validEnv
        """.trimIndent()
        writeEnv(withComment)
        val result = runTask()
        assert(!result.output.contains("do-not-echo-this-string-abc123")) {
            "Comment body leaked into task output:\n${result.output}"
        }
        assert(!result.output.contains("SECRET_NOTES")) {
            "Comment key leaked into task output:\n${result.output}"
        }
    }

    @Test
    fun `T-61 missing env file fails with distinguishable message`() {
        writeBuildFile()
        // Do NOT write .env.
        val result = runTask(expectFailure = true)
        assert(result.output.contains("no .env file found") || result.output.contains(".env not found")) {
            "Expected 'no .env file found' distinguishable message in output:\n${result.output}"
        }
        assert(!result.output.contains("must start with sk_test_")) {
            "Missing-env failure must not overlap with key-validation messages"
        }
    }

    @Test
    fun `T-production-parity — production build script still contains task marker strings`() {
        var dir: Path? = Path.of(System.getProperty("user.dir"))
        var script: String? = null
        while (dir != null) {
            val candidate = dir.resolve("build.gradle.kts")
            if (Files.exists(candidate)) {
                val text = Files.readString(candidate)
                if (text.contains("devStoreAuditKeys")) {
                    script = text
                    break
                }
            }
            dir = dir.parent
        }
        assert(script != null) { "Could not locate root build.gradle.kts with devStoreAuditKeys task" }

        val markers = listOf(
            "tasks.register(\"devStoreAuditKeys\")",
            "STRIPE_SECRET_KEY",
            "sk_live_",
            "sk_test_",
            "SHOPIFY_API_BASE_URL",
            ".myshopify.com",
            "SHOPIFY_WEBHOOK_SECRETS",
            "CJ_ACCESS_TOKEN",
            "no .env file found",
        )
        markers.forEach { marker ->
            assert(script!!.contains(marker)) {
                "Production build.gradle.kts is missing marker '$marker' — " +
                    "DevStoreAuditKeysTaskTest.TASK_BODY likely needs to be updated."
            }
        }
    }

    companion object {
        /**
         * Inlined copy of the devStoreAuditKeys task for Gradle TestKit isolation.
         * Keep semantically equivalent to the production task in root build.gradle.kts.
         */
        private val TASK_BODY = """
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
                        return if (v.length <= 4) "****" else "****${'$'}{v.takeLast(4)}"
                    }

                    val failures = mutableListOf<String>()

                    val stripeKey = env["STRIPE_SECRET_KEY"].orEmpty()
                    when {
                        stripeKey.isBlank() -> failures.add(
                            "STRIPE_SECRET_KEY must be set and start with sk_test_ (found: blank)",
                        )
                        stripeKey.startsWith("sk_live_") -> failures.add(
                            "STRIPE_SECRET_KEY is in LIVE mode (sk_live_) — refusing to proceed",
                        )
                        !stripeKey.startsWith("sk_test_") -> failures.add(
                            "STRIPE_SECRET_KEY must start with sk_test_ (found: ${'$'}{mask(stripeKey)})",
                        )
                    }

                    val shopifyBaseUrl = env["SHOPIFY_API_BASE_URL"].orEmpty()
                    if (shopifyBaseUrl.isBlank() ||
                        !shopifyBaseUrl.lowercase().trimEnd('/').endsWith(".myshopify.com")
                    ) {
                        failures.add(
                            "SHOPIFY_API_BASE_URL must end with .myshopify.com (found: ${'$'}{mask(shopifyBaseUrl)})",
                        )
                    }

                    val webhookSecrets = env["SHOPIFY_WEBHOOK_SECRETS"].orEmpty()
                    if (webhookSecrets.isBlank()) {
                        failures.add("SHOPIFY_WEBHOOK_SECRETS must be set")
                    }

                    val cjToken = env["CJ_ACCESS_TOKEN"].orEmpty()
                    if (cjToken.isBlank()) {
                        failures.add("CJ_ACCESS_TOKEN must be set")
                    }

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
                        throw GradleException("devStoreAuditKeys FAILED (${'$'}{failures.size} violation(s))")
                    }

                    val shopifyDomain = shopifyBaseUrl
                        .substringAfter("://", shopifyBaseUrl)
                        .substringBefore('/')
                        .trim()

                    logger.lifecycle(
                        "devStoreAuditKeys: PASS — Stripe ${'$'}{mask(stripeKey)}, " +
                            "Shopify ${'$'}shopifyDomain (last4: ${'$'}{mask(shopifyDomain)}), " +
                            "CJ token ${'$'}{mask(cjToken)}, " +
                            "webhook secrets ${'$'}{mask(webhookSecrets)}. Ready for dev-store run.",
                    )
                }
            }
        """.trimIndent()
    }
}
