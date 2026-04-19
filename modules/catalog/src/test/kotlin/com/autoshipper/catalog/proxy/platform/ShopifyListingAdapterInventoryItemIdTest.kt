package com.autoshipper.catalog.proxy.platform

import com.autoshipper.catalog.domain.CostEnvelope
import com.autoshipper.catalog.domain.LaunchReadySku
import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.domain.StressTestedMargin
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.http.HttpClient
import java.time.Instant

/**
 * Inventory-item-id extraction boundary tests for [ShopifyListingAdapter.listSku].
 *
 * Covers FR-030 / RAT-53 test-spec rows T-08 through T-14 — every JSON shape
 * the field `variants[0].inventory_item_id` can take (present number, present
 * string, absent, JSON null, empty variants array, missing variants key,
 * multi-variant disambiguation). T-10 is the CLAUDE.md #17 NullNode guard test.
 */
class ShopifyListingAdapterInventoryItemIdTest {

    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()
    }

    private fun loadFixture(path: String): String =
        this::class.java.classLoader
            .getResource("wiremock/$path")
            ?.readText()
            ?: throw IllegalArgumentException("Fixture not found: wiremock/$path")

    private fun adapter(): ShopifyListingAdapter {
        val httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        val restClient = RestClient.builder()
            .baseUrl(wireMock.baseUrl())
            .requestFactory(requestFactory)
            .build()
        // CLAUDE.md #20 — in tests, explicit ObjectMapper() is acceptable
        // (production code receives the Spring-managed bean via DI). Plain
        // ObjectMapper matches the existing ShopifyListingAdapterWireMockTest pattern.
        val mapper: ObjectMapper = ObjectMapper()
        return ShopifyListingAdapter(restClient, "test-access-token", mapper)
    }

    private fun buildLaunchReadySku(): LaunchReadySku {
        val skuId = SkuId.new()
        val sku = Sku(id = skuId.value, name = "Test Product", category = "Electronics")
        val envelope = CostEnvelope.Verified.create(
            skuId = skuId,
            supplierUnitCost = Money.of(BigDecimal("5.00"), Currency.USD),
            inboundShipping = Money.of(BigDecimal("1.00"), Currency.USD),
            outboundShipping = Money.of(BigDecimal("3.00"), Currency.USD),
            platformFee = Money.of(BigDecimal("1.00"), Currency.USD),
            processingFee = Money.of(BigDecimal("0.50"), Currency.USD),
            packagingCost = Money.of(BigDecimal("0.50"), Currency.USD),
            returnHandlingCost = Money.of(BigDecimal("0.50"), Currency.USD),
            customerAcquisitionCost = Money.of(BigDecimal("2.00"), Currency.USD),
            warehousingCost = Money.of(BigDecimal("0.50"), Currency.USD),
            customerServiceCost = Money.of(BigDecimal("0.50"), Currency.USD),
            refundAllowance = Money.of(BigDecimal("0.50"), Currency.USD),
            chargebackAllowance = Money.of(BigDecimal("0.25"), Currency.USD),
            taxesAndDuties = Money.of(BigDecimal("0.25"), Currency.USD),
            verifiedAt = Instant.now()
        )
        val margin = StressTestedMargin(Percentage.of(BigDecimal("35.00")))
        return LaunchReadySku(sku, envelope, margin)
    }

    private fun stubProductCreateWith(fixture: String) {
        wireMock.stubFor(
            post(urlEqualTo("/admin/api/2024-01/products.json"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture(fixture))
                )
        )
    }

    private val price = Money.of(BigDecimal("29.99"), Currency.USD)

    /** T-08 — happy path: inventory_item_id is a JSON number matching real Shopify wire shape. */
    @Test
    fun `T-08 — persistsInventoryItemIdFromVariantsResponse`() {
        stubProductCreateWith("shopify/product-create-with-inventory-item.json")

        val result = adapter().listSku(buildLaunchReadySku(), price)

        assertThat(result.inventoryItemId).isEqualTo("42857134217")
        // Sanity: existing fields still populated.
        assertThat(result.externalListingId).isEqualTo("7890123456789")
        assertThat(result.externalVariantId).isEqualTo("42857134218")
    }

    /** T-09 — variants[0] has no inventory_item_id key → Kotlin null. */
    @Test
    fun `T-09 — returnsNullInventoryItemIdWhenFieldAbsent`() {
        stubProductCreateWith("shopify/product-create-missing-inventory-item.json")

        val result = adapter().listSku(buildLaunchReadySku(), price)

        assertThat(result.inventoryItemId).isNull()
        // Product + variant ids must still populate — absence of one field doesn't break others.
        assertThat(result.externalListingId).isEqualTo("7890123456789")
        assertThat(result.externalVariantId).isEqualTo("42857134218")
    }

    /**
     * T-10 — CLAUDE.md constraint #17 NullNode guard (LOAD-BEARING).
     *
     * Shopify returns `"inventory_item_id": null`. The adapter must return Kotlin
     * null, NOT the string "null". Without the `if (!it.isNull) it.asText() else null`
     * guard, Jackson's NullNode.asText() returns the literal string "null", which
     * would silently corrupt every downstream inventory-check lookup.
     */
    @Test
    fun `T-10 — returnsNullInventoryItemIdWhenFieldIsJsonNull`() {
        stubProductCreateWith("shopify/product-create-variant-inventory-item-null.json")

        val result = adapter().listSku(buildLaunchReadySku(), price)

        // THE assertion that closes the PM-014 / NullNode boundary for this field:
        assertThat(result.inventoryItemId).isNull()
        assertThat(result.inventoryItemId).isNotEqualTo("null")
    }

    /** T-11 — variants is an empty array → null, no crash. */
    @Test
    fun `T-11 — returnsNullInventoryItemIdWhenVariantsArrayEmpty`() {
        stubProductCreateWith("shopify/product-create-variants-empty.json")

        val result = adapter().listSku(buildLaunchReadySku(), price)

        assertThat(result.inventoryItemId).isNull()
        assertThat(result.externalVariantId).isNull()
        // Product id still present.
        assertThat(result.externalListingId).isEqualTo("7890123456789")
    }

    /** T-12 — variants key absent entirely → null, no crash. */
    @Test
    fun `T-12 — returnsNullInventoryItemIdWhenVariantsKeyMissing`() {
        stubProductCreateWith("shopify/product-create-no-variants.json")

        val result = adapter().listSku(buildLaunchReadySku(), price)

        assertThat(result.inventoryItemId).isNull()
        assertThat(result.externalVariantId).isNull()
        assertThat(result.externalListingId).isEqualTo("7890123456789")
    }

    /**
     * T-13 — inventory_item_id as a JSON string gets passed through asText() unchanged.
     *
     * Documented behaviour: Jackson's `asText()` coerces all scalar JSON types
     * (number, string, boolean) to String. The adapter therefore returns the
     * string "12345" when Shopify emits either `12345` (number) or `"12345"` (string).
     */
    @Test
    fun `T-13 — extractsInventoryItemIdAsStringEvenWhenJsonNumber`() {
        stubProductCreateWith("shopify/product-create-variant-inventory-item-string.json")

        val result = adapter().listSku(buildLaunchReadySku(), price)

        assertThat(result.inventoryItemId).isEqualTo("12345")
    }

    /**
     * T-14 — multi-variant response: only variants[0] is read for inventory_item_id.
     *
     * The happy-path fixture `product-create-with-inventory-item.json` has TWO
     * variants (ids 42857134217 and 99999999999). Assert the adapter extracts
     * the FIRST variant's id, not the second — catches the regression where a
     * stray `last()` or `maxOf { ... }` is introduced.
     */
    @Test
    fun `T-14 — readsOnlyFirstVariantNotSecond`() {
        stubProductCreateWith("shopify/product-create-with-inventory-item.json")

        val result = adapter().listSku(buildLaunchReadySku(), price)

        assertThat(result.inventoryItemId).isEqualTo("42857134217")
        assertThat(result.inventoryItemId).isNotEqualTo("99999999999")
    }
}
