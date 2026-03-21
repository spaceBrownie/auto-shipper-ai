package com.autoshipper.capital.domain.service

import com.autoshipper.capital.config.CapitalConfig
import com.autoshipper.capital.domain.CapitalOrderRecord
import com.autoshipper.capital.domain.ReserveAccount
import com.autoshipper.capital.persistence.CapitalOrderRecordRepository
import com.autoshipper.capital.persistence.ReserveAccountRepository
import com.autoshipper.shared.money.Currency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReserveCalcJobTest {

    @Mock
    private lateinit var reserveAccountRepository: ReserveAccountRepository

    @Mock
    private lateinit var orderRecordRepository: CapitalOrderRecordRepository

    private val capitalConfig = CapitalConfig(
        netMarginFloorPercent = BigDecimal("30"),
        sustainedDays = 7,
        refundRateMaxPercent = BigDecimal("5"),
        chargebackRateMaxPercent = BigDecimal("2"),
        cacVarianceMaxPercent = BigDecimal("15"),
        cacVarianceDays = 14,
        reserveRateMinPercent = BigDecimal("10"),
        reserveRateMaxPercent = BigDecimal("15")
    )

    private lateinit var reserveCalcJob: ReserveCalcJob

    @BeforeEach
    fun setUp() {
        reserveCalcJob = ReserveCalcJob(reserveAccountRepository, orderRecordRepository, capitalConfig)
    }

    @Test
    fun `reconcile corrects reserve balance excluding refunded orders`() {
        // 9 non-refunded orders at $100 each, 1 refunded at $100
        val skuId = UUID.randomUUID()
        val orders = (1..9).map { i ->
            CapitalOrderRecord(
                orderId = UUID.randomUUID(),
                skuId = skuId,
                totalAmount = BigDecimal("100.0000"),
                currency = Currency.USD,
                status = "DELIVERED",
                refunded = false,
                chargebacked = false,
                recordedAt = Instant.now()
            )
        } + CapitalOrderRecord(
            orderId = UUID.randomUUID(),
            skuId = skuId,
            totalAmount = BigDecimal("100.0000"),
            currency = Currency.USD,
            status = "DELIVERED",
            refunded = true,
            chargebacked = false,
            recordedAt = Instant.now()
        )

        // Reserve account with incorrect balance ($50 instead of expected $90)
        val account = ReserveAccount(balanceCurrency = Currency.USD)
        account.balanceAmount = BigDecimal("50.0000")

        whenever(reserveAccountRepository.findAll()).thenReturn(listOf(account))
        whenever(orderRecordRepository.findAll()).thenReturn(orders)
        whenever(reserveAccountRepository.save(any<ReserveAccount>())).thenAnswer { it.arguments[0] }

        reserveCalcJob.reconcile(Instant.now())

        // 9 non-refunded * $100 * 10% = $90.0000
        verify(reserveAccountRepository).save(any<ReserveAccount>())
        assertEquals(0, BigDecimal("90.0000").compareTo(account.balanceAmount),
            "Reserve balance should be 90.0000 (9 * 100 * 10%), not including the refunded order")
    }
}
