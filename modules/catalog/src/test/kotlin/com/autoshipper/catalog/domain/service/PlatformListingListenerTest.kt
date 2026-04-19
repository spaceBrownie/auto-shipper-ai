package com.autoshipper.catalog.domain.service

import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.persistence.*
import com.autoshipper.catalog.proxy.platform.PlatformAdapter
import com.autoshipper.catalog.proxy.platform.PlatformListingResult
import com.autoshipper.shared.events.SkuStateChanged
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.math.BigDecimal
import java.time.Instant
import java.util.*

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformListingListenerTest {

    @Mock
    private lateinit var platformAdapter: PlatformAdapter

    @Mock
    private lateinit var platformListingRepository: PlatformListingRepository

    @Mock
    private lateinit var skuRepository: SkuRepository

    @Mock
    private lateinit var stressTestResultRepository: StressTestResultRepository

    @Mock
    private lateinit var costEnvelopeRepository: CostEnvelopeRepository

    private lateinit var listener: PlatformListingListener
    private val skuId = SkuId.new()

    @BeforeEach
    fun setUp() {
        listener = PlatformListingListener(
            platformAdapter,
            platformListingRepository,
            skuRepository,
            stressTestResultRepository,
            costEnvelopeRepository
        )
    }

    private fun buildStressTestResult(): StressTestResultEntity {
        return StressTestResultEntity(
            skuId = skuId.value,
            currency = "USD",
            stressedShippingAmount = BigDecimal("6.00"),
            stressedCacAmount = BigDecimal("2.30"),
            stressedSupplierAmount = BigDecimal("5.50"),
            stressedRefundAmount = BigDecimal("2.50"),
            stressedChargebackAmount = BigDecimal("1.00"),
            stressedTotalCostAmount = BigDecimal("20.00"),
            estimatedPriceAmount = BigDecimal("49.99"),
            grossMarginPercent = BigDecimal("55.00"),
            netMarginPercent = BigDecimal("35.00"),
            passed = true,
            shippingMultiplierUsed = BigDecimal("2.0000"),
            cacIncreasePercentUsed = BigDecimal("15.0000"),
            supplierIncreasePercentUsed = BigDecimal("10.0000"),
            refundRatePercentUsed = BigDecimal("5.0000"),
            chargebackRatePercentUsed = BigDecimal("2.0000"),
            testedAt = Instant.now()
        )
    }

    private fun buildCostEnvelopeEntity(): CostEnvelopeEntity {
        return CostEnvelopeEntity(
            skuId = skuId.value,
            currency = "USD",
            supplierUnitCostAmount = BigDecimal("5.00"),
            inboundShippingAmount = BigDecimal("1.00"),
            outboundShippingAmount = BigDecimal("3.00"),
            platformFeeAmount = BigDecimal("1.00"),
            processingFeeAmount = BigDecimal("0.50"),
            packagingCostAmount = BigDecimal("0.50"),
            returnHandlingCostAmount = BigDecimal("0.50"),
            customerAcquisitionCostAmount = BigDecimal("2.00"),
            warehousingCostAmount = BigDecimal("0.50"),
            customerServiceCostAmount = BigDecimal("0.50"),
            refundAllowanceAmount = BigDecimal("0.50"),
            chargebackAllowanceAmount = BigDecimal("0.25"),
            taxesAndDutiesAmount = BigDecimal("0.25"),
            verifiedAt = Instant.now()
        )
    }

    @Test
    fun `LISTED transition creates platform listing`() {
        val sku = Sku(id = skuId.value, name = "Test Product", category = "Electronics")
        val stressResult = buildStressTestResult()
        val envelopeEntity = buildCostEnvelopeEntity()

        whenever(platformListingRepository.findBySkuIdAndPlatform(skuId.value, "SHOPIFY")).thenReturn(null)
        whenever(skuRepository.findById(skuId.value)).thenReturn(Optional.of(sku))
        whenever(stressTestResultRepository.findBySkuId(skuId.value)).thenReturn(listOf(stressResult))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(envelopeEntity)
        whenever(platformAdapter.listSku(any(), any())).thenReturn(
            PlatformListingResult(externalListingId = "shopify-prod-123", externalVariantId = "shopify-var-456")
        )
        whenever(platformListingRepository.save(any<PlatformListingEntity>())).thenAnswer { it.arguments[0] }

        val event = SkuStateChanged(skuId = skuId, fromState = "STRESS_TESTING", toState = "LISTED")
        listener.onSkuStateChanged(event)

        verify(platformAdapter).listSku(any(), eq(Money.of(BigDecimal("49.99"), Currency.USD)))

        val entityCaptor = argumentCaptor<PlatformListingEntity>()
        verify(platformListingRepository).save(entityCaptor.capture())
        val saved = entityCaptor.firstValue

        assertEquals(skuId.value, saved.skuId)
        assertEquals("SHOPIFY", saved.platform)
        assertEquals("shopify-prod-123", saved.externalListingId)
        assertEquals("shopify-var-456", saved.externalVariantId)
        assertEquals(BigDecimal("49.9900"), saved.currentPriceAmount)
        assertEquals("USD", saved.currency)
        assertEquals("ACTIVE", saved.status)
    }

    @Test
    fun `LISTED transition is idempotent when listing already exists`() {
        val existingEntity = PlatformListingEntity(
            skuId = skuId.value,
            platform = "SHOPIFY",
            externalListingId = "existing-prod",
            externalVariantId = "existing-var",
            currentPriceAmount = BigDecimal("49.99"),
            currency = "USD",
            status = "ACTIVE"
        )

        whenever(platformListingRepository.findBySkuIdAndPlatform(skuId.value, "SHOPIFY")).thenReturn(existingEntity)

        val event = SkuStateChanged(skuId = skuId, fromState = "STRESS_TESTING", toState = "LISTED")
        listener.onSkuStateChanged(event)

        verify(platformAdapter, never()).listSku(any(), any())
        verify(platformListingRepository, never()).save(any<PlatformListingEntity>())
    }

    @Test
    fun `PAUSED transition sets listing to DRAFT`() {
        val entity = PlatformListingEntity(
            skuId = skuId.value,
            platform = "SHOPIFY",
            externalListingId = "prod-123",
            externalVariantId = "var-456",
            currentPriceAmount = BigDecimal("49.99"),
            currency = "USD",
            status = "ACTIVE"
        )

        whenever(platformListingRepository.findBySkuId(skuId.value)).thenReturn(entity)
        whenever(platformListingRepository.save(any<PlatformListingEntity>())).thenAnswer { it.arguments[0] }

        val event = SkuStateChanged(skuId = skuId, fromState = "LISTED", toState = "PAUSED")
        listener.onSkuStateChanged(event)

        verify(platformAdapter).pauseSku("prod-123")
        assertEquals("DRAFT", entity.status)
    }

    @Test
    fun `TERMINATED transition sets listing to ARCHIVED`() {
        val entity = PlatformListingEntity(
            skuId = skuId.value,
            platform = "SHOPIFY",
            externalListingId = "prod-123",
            externalVariantId = "var-456",
            currentPriceAmount = BigDecimal("49.99"),
            currency = "USD",
            status = "ACTIVE"
        )

        whenever(platformListingRepository.findBySkuId(skuId.value)).thenReturn(entity)
        whenever(platformListingRepository.save(any<PlatformListingEntity>())).thenAnswer { it.arguments[0] }

        val event = SkuStateChanged(skuId = skuId, fromState = "LISTED", toState = "TERMINATED")
        listener.onSkuStateChanged(event)

        verify(platformAdapter).archiveSku("prod-123")
        assertEquals("ARCHIVED", entity.status)
    }

    @Test
    fun `PAUSED with no existing listing logs warning and does not call adapter`() {
        whenever(platformListingRepository.findBySkuId(skuId.value)).thenReturn(null)

        val event = SkuStateChanged(skuId = skuId, fromState = "LISTED", toState = "PAUSED")
        listener.onSkuStateChanged(event)

        verify(platformAdapter, never()).pauseSku(any())
    }

    @Test
    fun `TERMINATED with no existing listing logs warning and does not call adapter`() {
        whenever(platformListingRepository.findBySkuId(skuId.value)).thenReturn(null)

        val event = SkuStateChanged(skuId = skuId, fromState = "LISTED", toState = "TERMINATED")
        listener.onSkuStateChanged(event)

        verify(platformAdapter, never()).archiveSku(any())
    }

    @Test
    fun `LISTED with missing stress test result does not create listing`() {
        whenever(platformListingRepository.findBySkuIdAndPlatform(skuId.value, "SHOPIFY")).thenReturn(null)
        whenever(skuRepository.findById(skuId.value)).thenReturn(Optional.of(
            Sku(id = skuId.value, name = "Test", category = "Cat")
        ))
        whenever(stressTestResultRepository.findBySkuId(skuId.value)).thenReturn(emptyList())

        val event = SkuStateChanged(skuId = skuId, fromState = "STRESS_TESTING", toState = "LISTED")
        listener.onSkuStateChanged(event)

        verify(platformAdapter, never()).listSku(any(), any())
    }

    @Test
    fun `LISTED with missing cost envelope does not create listing`() {
        val stressResult = buildStressTestResult()

        whenever(platformListingRepository.findBySkuIdAndPlatform(skuId.value, "SHOPIFY")).thenReturn(null)
        whenever(skuRepository.findById(skuId.value)).thenReturn(Optional.of(
            Sku(id = skuId.value, name = "Test", category = "Cat")
        ))
        whenever(stressTestResultRepository.findBySkuId(skuId.value)).thenReturn(listOf(stressResult))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(null)

        val event = SkuStateChanged(skuId = skuId, fromState = "STRESS_TESTING", toState = "LISTED")
        listener.onSkuStateChanged(event)

        verify(platformAdapter, never()).listSku(any(), any())
    }

    @Test
    fun `unknown transition is a no-op`() {
        val event = SkuStateChanged(skuId = skuId, fromState = "IDEATION", toState = "VALIDATION_PENDING")
        listener.onSkuStateChanged(event)

        verifyNoInteractions(platformAdapter)
        verifyNoInteractions(skuRepository)
    }

    @Test
    fun `adapter failure on LISTED does not persist entity`() {
        val sku = Sku(id = skuId.value, name = "Test Product", category = "Electronics")
        val stressResult = buildStressTestResult()
        val envelopeEntity = buildCostEnvelopeEntity()

        whenever(platformListingRepository.findBySkuIdAndPlatform(skuId.value, "SHOPIFY")).thenReturn(null)
        whenever(skuRepository.findById(skuId.value)).thenReturn(Optional.of(sku))
        whenever(stressTestResultRepository.findBySkuId(skuId.value)).thenReturn(listOf(stressResult))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(envelopeEntity)
        whenever(platformAdapter.listSku(any(), any())).thenThrow(RuntimeException("Shopify API unavailable"))

        val event = SkuStateChanged(skuId = skuId, fromState = "STRESS_TESTING", toState = "LISTED")

        assertThrows(RuntimeException::class.java) {
            listener.onSkuStateChanged(event)
        }

        verify(platformListingRepository, never()).save(any<PlatformListingEntity>())
    }

    @Test
    fun `listener method has correct PM-005 annotations`() {
        val method = PlatformListingListener::class.java.methods
            .find { it.name == "onSkuStateChanged" }

        assertNotNull(method, "onSkuStateChanged method should exist")

        val txEventListener = method!!.getAnnotation(TransactionalEventListener::class.java)
        assertNotNull(txEventListener, "@TransactionalEventListener should be present")
        assertEquals(TransactionPhase.AFTER_COMMIT, txEventListener!!.phase)

        val transactional = method.getAnnotation(Transactional::class.java)
        assertNotNull(transactional, "@Transactional should be present")
        assertEquals(Propagation.REQUIRES_NEW, transactional!!.propagation)
    }

    // ---------------------------------------------------------------------
    // FR-030 / RAT-53 — inventory_item_id persistence (T-15, T-16, T-17)
    // ---------------------------------------------------------------------

    /** T-15: non-null inventoryItemId from adapter persists onto the entity. */
    @Test
    fun `T-15 — persistsInventoryItemIdOnListingCreation`() {
        val sku = Sku(id = skuId.value, name = "Test Product", category = "Electronics")
        val stressResult = buildStressTestResult()
        val envelopeEntity = buildCostEnvelopeEntity()

        whenever(platformListingRepository.findBySkuIdAndPlatform(skuId.value, "SHOPIFY")).thenReturn(null)
        whenever(skuRepository.findById(skuId.value)).thenReturn(Optional.of(sku))
        whenever(stressTestResultRepository.findBySkuId(skuId.value)).thenReturn(listOf(stressResult))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(envelopeEntity)
        whenever(platformAdapter.listSku(any(), any())).thenReturn(
            PlatformListingResult(
                externalListingId = "7890",
                externalVariantId = "v1",
                inventoryItemId = "inv_123"
            )
        )
        whenever(platformListingRepository.save(any<PlatformListingEntity>())).thenAnswer { it.arguments[0] }

        val event = SkuStateChanged(skuId = skuId, fromState = "STRESS_TESTING", toState = "LISTED")
        listener.onSkuStateChanged(event)

        val captor = argumentCaptor<PlatformListingEntity>()
        verify(platformListingRepository).save(captor.capture())
        assertEquals("inv_123", captor.firstValue.shopifyInventoryItemId)
    }

    /** T-16: null inventoryItemId from adapter persists as null (not "" and not "null"). */
    @Test
    fun `T-16 — persistsNullWhenAdapterReturnsNull`() {
        val sku = Sku(id = skuId.value, name = "Test Product", category = "Electronics")
        val stressResult = buildStressTestResult()
        val envelopeEntity = buildCostEnvelopeEntity()

        whenever(platformListingRepository.findBySkuIdAndPlatform(skuId.value, "SHOPIFY")).thenReturn(null)
        whenever(skuRepository.findById(skuId.value)).thenReturn(Optional.of(sku))
        whenever(stressTestResultRepository.findBySkuId(skuId.value)).thenReturn(listOf(stressResult))
        whenever(costEnvelopeRepository.findBySkuId(skuId.value)).thenReturn(envelopeEntity)
        whenever(platformAdapter.listSku(any(), any())).thenReturn(
            PlatformListingResult(
                externalListingId = "7890",
                externalVariantId = "v1",
                inventoryItemId = null
            )
        )
        whenever(platformListingRepository.save(any<PlatformListingEntity>())).thenAnswer { it.arguments[0] }

        val event = SkuStateChanged(skuId = skuId, fromState = "STRESS_TESTING", toState = "LISTED")
        listener.onSkuStateChanged(event)

        val captor = argumentCaptor<PlatformListingEntity>()
        verify(platformListingRepository).save(captor.capture())
        assertNull(captor.firstValue.shopifyInventoryItemId)
        // Defense-in-depth: not the string "null", not empty string.
        assertNotEquals("null", captor.firstValue.shopifyInventoryItemId)
        assertNotEquals("", captor.firstValue.shopifyInventoryItemId)
    }

    /**
     * T-17: Repeat invocation — the listener's existing idempotency guard
     * short-circuits when a listing already exists, so a second LISTED event
     * does NOT overwrite the already-persisted `shopifyInventoryItemId`.
     *
     * This covers the retry/at-least-once semantics Spring's
     * `TransactionalEventListener` (AFTER_COMMIT) may trigger via event replay.
     */
    @Test
    fun `T-17 — repeatInvocationDoesNotCorruptPersistedInventoryItemId`() {
        val existingEntity = PlatformListingEntity(
            skuId = skuId.value,
            platform = "SHOPIFY",
            externalListingId = "7890",
            externalVariantId = "v1",
            currentPriceAmount = BigDecimal("49.99"),
            currency = "USD",
            status = "ACTIVE",
            shopifyInventoryItemId = "inv_123"
        )

        whenever(platformListingRepository.findBySkuIdAndPlatform(skuId.value, "SHOPIFY"))
            .thenReturn(existingEntity)

        val event = SkuStateChanged(skuId = skuId, fromState = "STRESS_TESTING", toState = "LISTED")
        listener.onSkuStateChanged(event)

        // Idempotency guard engages — no adapter call, no save, no corruption.
        verify(platformAdapter, never()).listSku(any(), any())
        verify(platformListingRepository, never()).save(any<PlatformListingEntity>())
        // Existing row's inventory_item_id is preserved.
        assertEquals("inv_123", existingEntity.shopifyInventoryItemId)
    }
}
