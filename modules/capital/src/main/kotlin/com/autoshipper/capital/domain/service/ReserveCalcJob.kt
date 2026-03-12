package com.autoshipper.capital.domain.service

import com.autoshipper.capital.config.CapitalConfig
import com.autoshipper.capital.persistence.CapitalOrderRecordRepository
import com.autoshipper.capital.persistence.ReserveAccountRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

@Service
class ReserveCalcJob(
    private val reserveAccountRepository: ReserveAccountRepository,
    private val orderRecordRepository: CapitalOrderRecordRepository,
    private val capitalConfig: CapitalConfig
) {
    private val logger = LoggerFactory.getLogger(ReserveCalcJob::class.java)

    @Scheduled(cron = "0 0 2 * * *") // nightly at 02:00
    @Transactional
    fun reconcile() {
        reconcile(Instant.now())
    }

    @Transactional
    fun reconcile(now: Instant) {
        val accounts = reserveAccountRepository.findAll()
        if (accounts.isEmpty()) {
            logger.info("No reserve accounts to reconcile")
            return
        }

        val account = accounts.first()
        val reserveRate = capitalConfig.reserveRateMinPercent
            .divide(BigDecimal(100), 4, RoundingMode.HALF_UP)

        val allOrders = orderRecordRepository.findAll()
        val expectedBalance = allOrders
            .filter { !it.refunded }
            .fold(BigDecimal.ZERO) { acc, order ->
                acc.add(order.totalAmount.multiply(reserveRate))
            }

        val currentBalance = account.balanceAmount
        if (currentBalance.compareTo(expectedBalance) != 0) {
            logger.warn(
                "Reserve balance drift detected: current={}, expected={}. Correcting.",
                currentBalance, expectedBalance
            )
            account.balanceAmount = expectedBalance.setScale(4, RoundingMode.HALF_UP)
            account.lastUpdatedAt = Instant.now()
            reserveAccountRepository.save(account)
        }

        val totalRevenue = allOrders
            .filter { !it.refunded }
            .fold(BigDecimal.ZERO) { acc, o -> acc.add(o.totalAmount) }
        if (totalRevenue > BigDecimal.ZERO) {
            val reservePercent = account.balanceAmount
                .multiply(BigDecimal(100))
                .divide(totalRevenue, 2, RoundingMode.HALF_UP)

            if (reservePercent < capitalConfig.reserveRateMinPercent) {
                logger.warn(
                    "Reserve below minimum threshold: {}% < {}%",
                    reservePercent, capitalConfig.reserveRateMinPercent
                )
            }
        }

        logger.info("Reserve reconciliation complete. Balance: {}", account.balanceAmount)
    }
}
