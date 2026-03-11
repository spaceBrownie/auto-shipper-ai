package com.autoshipper.capital.domain.service

import jakarta.persistence.PersistenceContext
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class JpaActiveSkuProvider(
    @PersistenceContext private val entityManager: EntityManager
) : ActiveSkuProvider {

    @Suppress("UNCHECKED_CAST")
    override fun getActiveSkuIds(): List<UUID> {
        return entityManager.createNativeQuery(
            "SELECT id FROM skus WHERE current_state_discriminator IN ('LISTED', 'SCALED')"
        ).resultList as List<UUID>
    }
}
