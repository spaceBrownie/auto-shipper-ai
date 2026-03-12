package com.autoshipper.capital.domain.service

import com.autoshipper.capital.config.CapitalConfig
import com.autoshipper.capital.domain.ReserveAccount
import com.autoshipper.capital.persistence.ReserveAccountRepository
import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.math.BigDecimal

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReserveAccountServiceTest {

    @Mock
    private lateinit var reserveAccountRepository: ReserveAccountRepository

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

    private lateinit var service: ReserveAccountService

    @BeforeEach
    fun setUp() {
        service = ReserveAccountService(reserveAccountRepository, capitalConfig)
    }

    @Test
    fun `creditFromOrder adds correct percentage of revenue to balance`() {
        val account = ReserveAccount(balanceCurrency = Currency.USD)
        whenever(reserveAccountRepository.findAll()).thenReturn(listOf(account))
        whenever(reserveAccountRepository.save(any<ReserveAccount>())).thenAnswer { it.arguments[0] }

        val revenue = Money.of(BigDecimal("100"), Currency.USD)
        service.creditFromOrder(OrderId.new(), revenue)

        // 10% of 100 = 10
        assertEquals(0, BigDecimal("10.0000").compareTo(account.balanceAmount))
    }

    @Test
    fun `getBalance returns correct balance and HEALTHY status`() {
        val account = ReserveAccount(balanceCurrency = Currency.USD)
        account.balanceAmount = BigDecimal("500.0000")
        whenever(reserveAccountRepository.findAll()).thenReturn(listOf(account))

        val status = service.getBalance()

        assertEquals(Money.of(BigDecimal("500"), Currency.USD), status.balance)
        assertEquals("HEALTHY", status.health)
    }

    @Test
    fun `getBalance returns CRITICAL when balance is zero`() {
        val account = ReserveAccount(balanceCurrency = Currency.USD)
        account.balanceAmount = BigDecimal.ZERO
        whenever(reserveAccountRepository.findAll()).thenReturn(listOf(account))

        val status = service.getBalance()

        assertEquals("CRITICAL", status.health)
    }

    @Test
    fun `getBalance returns HEALTHY default when no accounts exist`() {
        whenever(reserveAccountRepository.findAll()).thenReturn(emptyList())

        val status = service.getBalance()

        assertEquals(Money.of(BigDecimal.ZERO, Currency.USD), status.balance)
        assertEquals("HEALTHY", status.health)
    }

    @Test
    fun `multiple credits accumulate correctly`() {
        val account = ReserveAccount(balanceCurrency = Currency.USD)
        whenever(reserveAccountRepository.findAll()).thenReturn(listOf(account))
        whenever(reserveAccountRepository.save(any<ReserveAccount>())).thenAnswer { it.arguments[0] }

        service.creditFromOrder(OrderId.new(), Money.of(BigDecimal("100"), Currency.USD))
        service.creditFromOrder(OrderId.new(), Money.of(BigDecimal("200"), Currency.USD))

        // 10% of 100 + 10% of 200 = 10 + 20 = 30
        assertEquals(0, BigDecimal("30.0000").compareTo(account.balanceAmount))
    }

    @Test
    fun `creates new account if none exists`() {
        val newAccount = ReserveAccount(balanceCurrency = Currency.USD)
        whenever(reserveAccountRepository.findAll())
            .thenReturn(emptyList())
            .thenReturn(listOf(newAccount))
        whenever(reserveAccountRepository.save(any<ReserveAccount>())).thenReturn(newAccount)

        val revenue = Money.of(BigDecimal("100"), Currency.USD)
        service.creditFromOrder(OrderId.new(), revenue)

        // 10% of 100 = 10
        assertEquals(0, BigDecimal("10.0000").compareTo(newAccount.balanceAmount))
    }
}
