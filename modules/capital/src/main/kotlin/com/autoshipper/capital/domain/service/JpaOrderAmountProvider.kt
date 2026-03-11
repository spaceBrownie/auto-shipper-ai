package com.autoshipper.capital.domain.service

import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

@Service
class JpaOrderAmountProvider(
    @PersistenceContext private val entityManager: EntityManager
) : OrderAmountProvider {

    override fun getOrderAmount(orderId: UUID): Money? {
        val results = entityManager.createNativeQuery(
            "SELECT total_amount, total_currency FROM orders WHERE id = :orderId"
        ).setParameter("orderId", orderId).resultList

        if (results.isEmpty()) return null

        @Suppress("UNCHECKED_CAST")
        val row = results.first() as Array<*>
        val amount = row[0] as BigDecimal
        val currency = Currency.valueOf(row[1] as String)
        return Money.of(amount, currency)
    }
}
