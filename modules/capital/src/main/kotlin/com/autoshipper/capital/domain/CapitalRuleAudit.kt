package com.autoshipper.capital.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "capital_rule_audit")
class CapitalRuleAudit(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
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
)
