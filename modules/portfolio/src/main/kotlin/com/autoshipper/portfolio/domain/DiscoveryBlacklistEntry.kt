package com.autoshipper.portfolio.domain

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
@Table(name = "discovery_blacklist")
class DiscoveryBlacklistEntry(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("_internalId")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "keyword", nullable = false, unique = true, length = 255)
    val keyword: String,

    @Column(name = "reason", nullable = false)
    val reason: String,

    @Column(name = "added_at", nullable = false, updatable = false)
    val addedAt: Instant = Instant.now()
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
