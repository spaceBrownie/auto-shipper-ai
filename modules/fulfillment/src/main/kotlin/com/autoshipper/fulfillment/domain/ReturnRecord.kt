package com.autoshipper.fulfillment.domain

import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import jakarta.persistence.*
import org.springframework.data.domain.Persistable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "return_records")
class ReturnRecord(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("_internalId")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    @Column(name = "reason", nullable = false)
    val reason: String,

    @Column(name = "returned_at", nullable = false)
    val returnedAt: Instant = Instant.now(),

    @Column(name = "return_handling_cost_amount", nullable = false)
    val returnHandlingCostAmount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "return_handling_cost_currency", nullable = false)
    val returnHandlingCostCurrency: Currency,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
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

    fun returnHandlingCost(): Money = Money.of(returnHandlingCostAmount, returnHandlingCostCurrency)
}
