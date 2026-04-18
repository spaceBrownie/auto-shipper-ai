package com.autoshipper.shared.devstoreaudit

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * T-62: .env.example committed-file hygiene.
 *
 * Reads `.env.example` at the repo root, asserts the four FR-030 / RAT-53 keys
 * exist, and asserts NO real secret patterns have slipped into the committed file.
 */
class DotEnvExampleTest {

    private fun locateEnvExample(): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = dir.resolve(".env.example")
            if (Files.exists(candidate)) return candidate
            dir = dir.parent
        }
        error(".env.example not found from ${System.getProperty("user.dir")} upward")
    }

    @Test
    fun `T-62 env example contains required FR-030 keys and no real secrets`() {
        val envExample = locateEnvExample()
        val text = Files.readString(envExample)

        val requiredKeys = listOf(
            "DEV_ADMIN_TOKEN=",
            "AUTOSHIPPER_ADMIN_DEV_LISTING_ENABLED=false",
            "AUTOSHIPPER_WEBHOOK_ARCHIVAL_ENABLED=false",
            "AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN=false",
        )
        requiredKeys.forEach { key ->
            assert(text.contains(key)) {
                ".env.example missing required key line: '$key'"
            }
        }

        // Security: real-secret pattern detection.
        val liveStripeRegex = Regex("""sk_live_[A-Za-z0-9]+""")
        assert(!liveStripeRegex.containsMatchIn(text)) {
            ".env.example contains a LIVE Stripe key (sk_live_*) — refuse to commit"
        }
        val realTestStripeRegex = Regex("""sk_test_[A-Za-z0-9]{20,}""")
        assert(!realTestStripeRegex.containsMatchIn(text)) {
            ".env.example contains what looks like a real Stripe test key — use a <placeholder> instead"
        }
        val realShopifyTokenRegex = Regex("""shpat_[A-Fa-f0-9]{32,}""")
        assert(!realShopifyTokenRegex.containsMatchIn(text)) {
            ".env.example contains what looks like a real Shopify access token — use a <placeholder> instead"
        }
        val realWebhookSecretRegex = Regex("""whsec_[A-Za-z0-9+/=]{20,}""")
        assert(!realWebhookSecretRegex.containsMatchIn(text)) {
            ".env.example contains what looks like a real Shopify webhook secret — use a <placeholder> instead"
        }
    }
}

/**
 * T-63: application.yml default values.
 *
 * Parses the committed `application.yml` directly with SnakeYAML and asserts that
 * the four FR-030 / RAT-53 config keys resolve to production-safe defaults when
 * no environment variable is set.
 *
 * Why not @SpringBootTest? The full app context requires Postgres + all module
 * beans and lives in `:app`, which conflicts with Gradle TestKit's SLF4J binding
 * (see DevStoreAuditKeysTaskTest). A direct YAML parse covers the default-values
 * contract without that coupling. The actual Spring resolution is exercised by
 * AutoShipperApplicationTest in `:app`.
 */
class DevStoreConfigDefaultsTest {

    private fun locateApplicationYml(): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = dir.resolve("modules/app/src/main/resources/application.yml")
            if (Files.exists(candidate)) return candidate
            dir = dir.parent
        }
        error("application.yml not found from ${System.getProperty("user.dir")} upward")
    }

    /**
     * Extract a placeholder default from a Spring `${VAR:default}` expression.
     * Returns null if the value is not a placeholder string.
     */
    @Suppress("UNCHECKED_CAST")
    private fun traverse(root: Map<String, Any?>, path: List<String>): Any? {
        var cur: Any? = root
        for (key in path) {
            if (cur !is Map<*, *>) return null
            cur = (cur as Map<String, Any?>)[key]
        }
        return cur
    }

    private fun placeholderDefault(value: Any?): String? {
        val str = value as? String ?: return null
        val match = Regex("""\$\{[^:}]+:([^}]*)}""").matchEntire(str) ?: return null
        return match.groupValues[1]
    }

    @Test
    fun `T-63 FR-030 config defaults are production-safe`() {
        val ymlPath = locateApplicationYml()
        val yaml = Yaml().load<Map<String, Any?>>(Files.newBufferedReader(ymlPath))

        // autoshipper.admin.dev-listing-enabled → ${AUTOSHIPPER_ADMIN_DEV_LISTING_ENABLED:false}
        val devListingRaw = traverse(yaml, listOf("autoshipper", "admin", "dev-listing-enabled"))
        val devListingDefault = placeholderDefault(devListingRaw)
            ?: error("autoshipper.admin.dev-listing-enabled must be a Spring placeholder with a default; was $devListingRaw")
        assert(devListingDefault == "false") {
            "autoshipper.admin.dev-listing-enabled default must be 'false', was '$devListingDefault'"
        }

        // autoshipper.webhook-archival.enabled → ${AUTOSHIPPER_WEBHOOK_ARCHIVAL_ENABLED:false}
        val archivalRaw = traverse(yaml, listOf("autoshipper", "webhook-archival", "enabled"))
        val archivalDefault = placeholderDefault(archivalRaw)
            ?: error("autoshipper.webhook-archival.enabled must be a Spring placeholder with a default; was $archivalRaw")
        assert(archivalDefault == "false") {
            "autoshipper.webhook-archival.enabled default must be 'false', was '$archivalDefault'"
        }

        // autoshipper.cj.dev-store-dry-run → ${AUTOSHIPPER_CJ_DEV_STORE_DRY_RUN:false}
        val dryRunRaw = traverse(yaml, listOf("autoshipper", "cj", "dev-store-dry-run"))
        val dryRunDefault = placeholderDefault(dryRunRaw)
            ?: error("autoshipper.cj.dev-store-dry-run must be a Spring placeholder with a default; was $dryRunRaw")
        assert(dryRunDefault == "false") {
            "autoshipper.cj.dev-store-dry-run default must be 'false', was '$dryRunDefault'"
        }

        // autoshipper.admin.dev-token → ${DEV_ADMIN_TOKEN:} — default is blank.
        val tokenRaw = traverse(yaml, listOf("autoshipper", "admin", "dev-token"))
        val tokenDefault = placeholderDefault(tokenRaw)
            ?: error("autoshipper.admin.dev-token must be a Spring placeholder with an (empty) default; was $tokenRaw")
        assert(tokenDefault.isBlank()) {
            "autoshipper.admin.dev-token default must be blank, was '$tokenDefault'"
        }
    }
}
