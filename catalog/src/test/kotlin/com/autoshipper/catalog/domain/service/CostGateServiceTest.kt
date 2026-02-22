package com.autoshipper.catalog.domain.service

import com.autoshipper.catalog.domain.Address
import com.autoshipper.catalog.domain.PackageDimensions
import com.autoshipper.catalog.domain.ProviderUnavailableException
import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.persistence.CostEnvelopeEntity
import com.autoshipper.catalog.persistence.CostEnvelopeRepository
import com.autoshipper.catalog.proxy.carrier.CarrierRateProvider
import com.autoshipper.catalog.proxy.payment.StripeProcessingFeeProvider
import com.autoshipper.catalog.proxy.platform.ShopifyPlatformFeeProvider
import com.autoshipper.shared.events.CostEnvelopeVerified
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal

@ExtendWith(MockitoExtension::class)
class CostGateServiceTest {

    @Mock lateinit var skuService: SkuService
    @Mock lateinit var upsRateProvider: CarrierRateProvider
    @Mock lateinit var stripeProvider: StripeProcessingFeeProvider
    @Mock lateinit var shopifyProvider: ShopifyPlatformFeeProvider
    @Mock lateinit var costEnvelopeRepository: CostEnvelopeRepository
    @Mock lateinit var eventPublisher: ApplicationEventPublisher

    private lateinit var costGateService: CostGateService

    private val skuId = SkuId.new()
    private val origin = Address("123 Main St", "Chicago", "IL", "60601", "US")
    private val destination = Address("456 Oak Ave", "Los Angeles", "CA", "90001", "US")
    private val dims = PackageDimensions(
        lengthCm = BigDecimal("30"),
        widthCm = BigDecimal("20"),
        heightCm = BigDecimal("10"),
        weightKg = BigDecimal("2")
    )

    private fun usd(amount: Double) = Money.of(amount, Currency.USD)

    @BeforeEach
    fun setUp() {
        whenever(upsRateProvider.carrierName).thenReturn("UPS")
        costGateService = CostGateService(
            skuService = skuService,
            carrierRateProviders = listOf(upsRateProvider),
            stripeProcessingFeeProvider = stripeProvider,
            shopifyPlatformFeeProvider = shopifyProvider,
            costEnvelopeRepository = costEnvelopeRepository,
            eventPublisher = eventPublisher
        )
    }

    @Test
    fun `happy path - all providers return values, envelope constructed, SKU transitions to StressTesting, event published`() {
        whenever(upsRateProvider.getRate(any(), any(), any())).thenReturn(usd(8.50))
        whenever(stripeProvider.getFee(any())).thenReturn(usd(3.20))
        whenever(shopifyProvider.getFee()).thenReturn(usd(2.00))
        whenever(costEnvelopeRepository.save(any<CostEnvelopeEntity>())).thenAnswer { it.arguments[0] }

        val result = costGateService.verify(
            skuId = skuId,
            vendorQuote = usd(10.0),
            packageDims = dims,
            origin = origin,
            destination = destination,
            cacEstimate = usd(5.0),
            jurisdiction = "US-IL",
            warehouseCost = usd(2.0),
            customerServiceCost = usd(0.75),
            packagingCost = usd(0.50),
            returnHandlingCost = usd(1.0),
            refundAllowanceRate = Percentage.of(5.0),
            chargebackAllowanceRate = Percentage.of(2.0),
            taxesAndDuties = usd(2.25),
            estimatedOrderValue = usd(100.0)
        )

        assertNotNull(result)
        assertEquals(skuId, result.skuId)
        assertEquals(usd(8.50), result.outboundShipping)
        assertEquals(usd(3.20), result.processingFee)
        assertEquals(usd(2.00), result.platformFee)

        // Verify refund allowance = 100 * 5% = 5.00
        assertEquals(usd(5.0), result.refundAllowance)

        // Verify chargeback allowance = 100 * 2% = 2.00
        assertEquals(usd(2.0), result.chargebackAllowance)

        verify(costEnvelopeRepository).save(any())
        verify(eventPublisher).publishEvent(any<CostEnvelopeVerified>())
    }

    @Test
    fun `when carrier provider throws ProviderUnavailableException, it propagates`() {
        whenever(upsRateProvider.getRate(any(), any(), any()))
            .thenThrow(ProviderUnavailableException("UPS", RuntimeException("connection refused")))

        assertThrows<ProviderUnavailableException> {
            costGateService.verify(
                skuId = skuId,
                vendorQuote = usd(10.0),
                packageDims = dims,
                origin = origin,
                destination = destination,
                cacEstimate = usd(5.0),
                jurisdiction = "US-IL",
                warehouseCost = usd(2.0),
                customerServiceCost = usd(0.75),
                packagingCost = usd(0.50),
                returnHandlingCost = usd(1.0),
                refundAllowanceRate = Percentage.of(5.0),
                chargebackAllowanceRate = Percentage.of(2.0),
                taxesAndDuties = usd(2.25),
                estimatedOrderValue = usd(100.0)
            )
        }

        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `when vendor quote currency differs from carrier rate currency, currency mismatch exception is thrown`() {
        val eurRate = Money.of(8.50, com.autoshipper.shared.money.Currency.EUR)
        whenever(upsRateProvider.getRate(any(), any(), any())).thenReturn(eurRate)
        whenever(stripeProvider.getFee(any())).thenReturn(usd(3.20))
        whenever(shopifyProvider.getFee()).thenReturn(usd(2.00))

        // CostEnvelope.Verified init block validates all components share same currency
        // vendor quote is USD, carrier rate is EUR — this triggers CurrencyMismatchException on fullyBurdened sum
        assertThrows<Exception> {
            costGateService.verify(
                skuId = skuId,
                vendorQuote = usd(10.0),
                packageDims = dims,
                origin = origin,
                destination = destination,
                cacEstimate = usd(5.0),
                jurisdiction = "US-IL",
                warehouseCost = usd(2.0),
                customerServiceCost = usd(0.75),
                packagingCost = usd(0.50),
                returnHandlingCost = usd(1.0),
                refundAllowanceRate = Percentage.of(5.0),
                chargebackAllowanceRate = Percentage.of(2.0),
                taxesAndDuties = usd(2.25),
                estimatedOrderValue = usd(100.0)
            )
        }
    }
}
