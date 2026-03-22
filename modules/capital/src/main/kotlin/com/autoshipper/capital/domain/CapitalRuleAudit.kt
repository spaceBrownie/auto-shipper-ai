package com.autoshipper.capital.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "capital_rule_audit")
class CapitalRuleAudit(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("_internalId")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "sku_id", nullable = false)
    val skuId: UUID,

    @Column(name = "rule", nullable = false, length = 50)
    val rule: String,

    @Column(name = "condition_value", nullable = false, length = 100)
    val conditionValue: String,

    @Column(name = "action", nullable = false, length = 50)
    val action: String,

    @Column(name = "fired_at", nullable = false, updatable = false)
    val firedAt: Instant = Instant.now()
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
}
