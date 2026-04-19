package com.autoshipper

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Structural assertions for the V23 Flyway migration that adds the
 * `platform_listings.shopify_inventory_item_id` column + lookup index.
 *
 * Implementation note: the project already validates migrations end-to-end
 * via `@SpringBootTest` + `application-test.yml` (ddl-auto=validate) — every
 * existing integration test implicitly exercises V23 against real Postgres
 * when it boots. This test adds targeted, fast-to-run structural assertions
 * on the SQL so Phase 4 test-spec rows T-01..T-04 are closed without
 * requiring a running container.
 */
class V23MigrationTest {

    private val migrationFile: File =
        File("../app/src/main/resources/db/migration/V23__platform_listings_inventory_item_id.sql")
            .let { candidate ->
                // Resolve against either the app module dir (module-local test run)
                // or the repo root (if invoked by :app:test from repo root).
                if (candidate.exists()) candidate
                else File("modules/app/src/main/resources/db/migration/V23__platform_listings_inventory_item_id.sql")
            }

    private val sql: String by lazy {
        assertThat(migrationFile)
            .withFailMessage("V23 migration file not found at ${migrationFile.absolutePath}")
            .exists()
        migrationFile.readText()
    }

    /**
     * T-01: Column `shopify_inventory_item_id` is added with VARCHAR type,
     *       length 64, nullable (no NOT NULL constraint).
     */
    @Test
    fun `T-01 — migration adds shopify_inventory_item_id column with VARCHAR(64) nullable`() {
        val normalized = sql.replace(Regex("\\s+"), " ").lowercase()

        assertThat(normalized)
            .withFailMessage("Expected ADD COLUMN shopify_inventory_item_id in V23: $sql")
            .contains("alter table platform_listings")
            .contains("add column shopify_inventory_item_id")
            .contains("varchar(64)")

        // Nullability: must not contain NOT NULL adjacent to our column name.
        val addColumnClause = Regex(
            "add\\s+column\\s+shopify_inventory_item_id[^,;]*",
            RegexOption.IGNORE_CASE
        ).find(sql)?.value ?: ""
        assertThat(addColumnClause.lowercase())
            .withFailMessage("shopify_inventory_item_id must be nullable (no NOT NULL in clause: '$addColumnClause')")
            .doesNotContain("not null")
    }

    /**
     * T-02: Lookup index `idx_platform_listings_inventory_item` is created
     *       on the new column.
     */
    @Test
    fun `T-02 — migration creates lookup index on shopify_inventory_item_id`() {
        val normalized = sql.replace(Regex("\\s+"), " ").lowercase()

        assertThat(normalized)
            .withFailMessage("Expected CREATE INDEX idx_platform_listings_inventory_item in V23: $sql")
            .contains("create index idx_platform_listings_inventory_item")
            .contains("on platform_listings(shopify_inventory_item_id)")
    }

    /**
     * T-03: Flyway migration version + naming convention — filename must
     *       follow V{N}__{description}.sql and live in the canonical location.
     */
    @Test
    fun `T-03 — migration filename follows Flyway V23 naming convention`() {
        assertThat(migrationFile.name)
            .matches(Regex("V23__[a-z_]+\\.sql").toPattern())
        assertThat(migrationFile.parentFile.name).isEqualTo("migration")
        assertThat(migrationFile.parentFile.parentFile.name).isEqualTo("db")
    }

    /**
     * T-04: Additive migration — does NOT drop or rename existing columns,
     *       so rows persisted before V23 survive the migration with NULL
     *       in the new column (real-behavior confirmation via source text).
     */
    @Test
    fun `T-04 — migration is additive (no DROP or ALTER of existing columns)`() {
        val lowered = sql.lowercase()

        assertThat(lowered)
            .withFailMessage("V23 must be strictly additive — found DROP/RENAME: $sql")
            .doesNotContain("drop column")
            .doesNotContain("drop table")
            .doesNotContain("rename column")
            .doesNotContain("alter column ") // whitespace guards against "alter column ..."

        // And the only DDL targeting platform_listings should be the ADD COLUMN.
        val platformListingsOps = Regex(
            "alter\\s+table\\s+platform_listings\\s+[^;]+;",
            RegexOption.IGNORE_CASE
        ).findAll(sql).map { it.value }.toList()

        assertThat(platformListingsOps)
            .withFailMessage("Expected exactly one ALTER TABLE platform_listings statement in V23")
            .hasSize(1)
        assertThat(platformListingsOps[0].lowercase()).contains("add column")
    }
}
