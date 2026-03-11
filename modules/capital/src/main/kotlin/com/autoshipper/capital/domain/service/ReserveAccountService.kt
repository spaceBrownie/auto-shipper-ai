package com.autoshipper.capital.domain.service

import com.autoshipper.capital.config.CapitalConfig
import com.autoshipper.capital.domain.ReserveAccount
import com.autoshipper.capital.persistence.ReserveAccountRepository
import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class ReserveAccountService(
    private val reserveAccountRepository: ReserveAccountRepository,
    private val capitalConfig: CapitalConfig
) {
    private val logger = LoggerFactory.getLogger(ReserveAccountService::class.java)

    @Transactional
    fun creditFromOrder(orderId: OrderId, revenue: Money) {
        val account = getOrCreateAccount(revenue.currency)
        val reserveRate = capitalConfig.reserveRateMinPercent
            .divide(BigDecimal(100), 4, RoundingMode.HALF_UP)
        val creditAmount = revenue * reserveRate
        account.credit(creditAmount)
        reserveAccountRepository.save(account)
        logger.info("Credited {} to reserve from order {}", creditAmount, orderId)
    }

    @Transactional(readOnly = true)
    fun getBalance(): ReserveHealthStatus {
        val accounts = reserveAccountRepository.findAll()
        if (accounts.isEmpty()) {
            return ReserveHealthStatus(Money.of(BigDecimal.ZERO, Currency.USD), "HEALTHY")
        }
        val account = accounts.first()
        val balance = account.balance()
        val health = when {
            balance.normalizedAmount <= BigDecimal.ZERO -> "CRITICAL"
            else -> "HEALTHY"
        }
        return ReserveHealthStatus(balance, health)
    }

    private fun getOrCreateAccount(currency: Currency): ReserveAccount {
        val accounts = reserveAccountRepository.findAll()
        if (accounts.isNotEmpty()) return accounts.first()
        return reserveAccountRepository.save(
            ReserveAccount(
                balanceCurrency = currency,
                targetRateMin = capitalConfig.reserveRateMinPercent,
                targetRateMax = capitalConfig.reserveRateMaxPercent
            )
        )
    }
}

data class ReserveHealthStatus(val balance: Money, val health: String)
