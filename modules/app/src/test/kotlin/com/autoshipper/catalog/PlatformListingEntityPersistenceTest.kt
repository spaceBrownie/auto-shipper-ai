package com.autoshipper.catalog

import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.persistence.PlatformListingEntity
import com.autoshipper.catalog.persistence.PlatformListingRepository
import com.autoshipper.catalog.persistence.SkuRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID

/**
 * Round-trip persistence tests for the new `shopifyInventoryItemId` column
 * on `PlatformListingEntity` (FR-030 / RAT-53 — V23 migration).
 *
 * Uses the same running-Postgres pattern as MarginSweepIntegrationTest
 * (see PM-004 for Docker-API incompatibility with Testcontainers in this repo).
 */
@SpringBootTest
@ActiveProfiles("test")
class PlatformListingEntityPersistenceTest {

    @Autowired lateinit var repository: PlatformListingRepository
    @Autowired lateinit var skuRepository: SkuRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var transactionTemplate: TransactionTemplate

    @AfterEach
    fun cleanup() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE platform_listings, sku_state_history, skus CASCADE"
        )
    }

    private fun seedSku(): UUID {
        val sku = transactionTemplate.execute {
            skuRepository.save(Sku(name = "FR-030 Test ${UUID.randomUUID()}", category = "Electronics"))
        }!!
        return sku.id
    }

    private fun newEntity(
        inventoryItemId: String?,
        platform: String = "SHOPIFY_TEST_ROUND_TRIP",
        skuId: UUID = seedSku()
    ): PlatformListingEntity = PlatformListingEntity(
        skuId = skuId,
        platform = platform,
        externalListingId = "ext-${UUID.randomUUID()}",
        externalVariantId = "var-${UUID.randomUUID()}",
        currentPriceAmount = BigDecimal("49.9900"),
        currency = "USD",
        status = "ACTIVE",
        shopifyInventoryItemId = inventoryItemId
    )

    /**
     * T-05: Non-null inventory_item_id round-trips through save + reload.
     */
    @Test
    fun `T-05 — persistsNonNullInventoryItemId`() {
        val entity = newEntity(inventoryItemId = "gid://shopify/InventoryItem/123")
        val saved = transactionTemplate.execute {
            repository.saveAndFlush(entity)
        }!!

        // Force a new read (clear persistence context by re-fetching in a fresh tx).
        val reloaded = transactionTemplate.execute {
            repository.findById(saved.id).orElseThrow()
        }!!

        assertThat(reloaded.shopifyInventoryItemId)
            .isEqualTo("gid://shopify/InventoryItem/123")
    }

    /**
     * T-06: Null inventory_item_id round-trips as Kotlin null — not "" and not "null".
     */
    @Test
    fun `T-06 — persistsNullInventoryItemId`() {
        val entity = newEntity(inventoryItemId = null)
        val saved = transactionTemplate.execute {
            repository.saveAndFlush(entity)
        }!!

        val reloaded = transactionTemplate.execute {
            repository.findById(saved.id).orElseThrow()
        }!!

        assertThat(reloaded.shopifyInventoryItemId).isNull()
        // Defense-in-depth: the column in raw SQL is NULL, not the literal string "null".
        val raw = jdbcTemplate.queryForObject(
            "SELECT shopify_inventory_item_id FROM platform_listings WHERE id = ?",
            String::class.java,
            saved.id
        )
        assertThat(raw).isNull()
    }

    /**
     * T-07: Field is updatable (closes Phase 3 Risk #1 — @Column(updatable=true) implicit).
     */
    @Test
    fun `T-07 — fieldIsUpdatable`() {
        val initial = newEntity(inventoryItemId = null)
        val saved = transactionTemplate.execute {
            repository.saveAndFlush(initial)
        }!!

        // Mutate in a new transaction — replicates how the listener updates later.
        transactionTemplate.execute {
            val loaded = repository.findById(saved.id).orElseThrow()
            loaded.shopifyInventoryItemId = "999"
            repository.saveAndFlush(loaded)
        }

        val reloaded = transactionTemplate.execute {
            repository.findById(saved.id).orElseThrow()
        }!!

        assertThat(reloaded.shopifyInventoryItemId).isEqualTo("999")
    }
}
