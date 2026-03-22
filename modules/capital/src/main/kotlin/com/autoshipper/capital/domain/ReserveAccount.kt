package com.autoshipper.capital.domain

import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.Version
import org.springframework.data.domain.Persistable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "reserve_accounts")
class ReserveAccount(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("_internalId")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "balance_amount", nullable = false, precision = 19, scale = 4)
    var balanceAmount: BigDecimal = BigDecimal.ZERO,

    @Enumerated(EnumType.STRING)
    @Column(name = "balance_currency", nullable = false, length = 3)
    var balanceCurrency: Currency = Currency.USD,

    @Column(name = "target_rate_min", nullable = false, precision = 5, scale = 2)
    var targetRateMin: BigDecimal = BigDecimal("10.00"),

    @Column(name = "target_rate_max", nullable = false, precision = 5, scale = 2)
    var targetRateMax: BigDecimal = BigDecimal("15.00"),

    @Column(name = "last_updated_at", nullable = false)
    var lastUpdatedAt: Instant = Instant.now(),

    @Version
    var version: Long = 0
) : Persistable<UUID> {

    @Transient
    private var isNew: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = isNew

    @PostPersist
    @PostLoad
    fun markNotNew() {
        isNew = false
    }

    fun balance(): Money = Money.of(balanceAmount, balanceCurrency)

    fun credit(amount: Money) {
        require(amount.currency == balanceCurrency) {
            "Currency mismatch: expected $balanceCurrency, got ${amount.currency}"
        }
        balanceAmount = balanceAmount.add(amount.normalizedAmount)
        lastUpdatedAt = Instant.now()
    }

    fun setBalance(amount: Money) {
        require(amount.currency == balanceCurrency) { "Currency mismatch" }
        balanceAmount = amount.normalizedAmount
        lastUpdatedAt = Instant.now()
    }
}
