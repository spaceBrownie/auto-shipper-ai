package com.autoshipper.catalog.domain.service

import com.autoshipper.catalog.domain.*
import com.autoshipper.catalog.persistence.SkuRepository
import com.autoshipper.catalog.persistence.SkuStateHistory
import com.autoshipper.catalog.persistence.SkuStateHistoryRepository
import com.autoshipper.shared.events.SkuStateChanged
import com.autoshipper.shared.events.SkuTerminated
import com.autoshipper.shared.identity.SkuId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class SkuService(
    private val skuRepository: SkuRepository,
    private val historyRepository: SkuStateHistoryRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    fun create(name: String, category: String): Sku {
        val sku = Sku(name = name, category = category)
        return skuRepository.save(sku)
    }

    fun transition(skuId: SkuId, newState: SkuState): Sku {
        val sku = skuRepository.findById(skuId.value)
            .orElseThrow { NoSuchElementException("SKU not found: $skuId") }

        val fromState = sku.currentState()
        SkuStateMachine.validate(fromState, newState)

        sku.applyTransition(newState)
        val saved = skuRepository.save(sku)

        historyRepository.save(
            SkuStateHistory(
                skuId = skuId.value,
                fromState = fromState.toDiscriminator(),
                toState = newState.toDiscriminator()
            )
        )

        eventPublisher.publishEvent(
            SkuStateChanged(skuId = skuId, fromState = fromState.toDiscriminator(), toState = newState.toDiscriminator())
        )

        if (newState is SkuState.Terminated) {
            eventPublisher.publishEvent(SkuTerminated(skuId = skuId, reason = newState.reason.name))
        }

        return saved
    }

    @Transactional(readOnly = true)
    fun findById(skuId: SkuId): Sku =
        skuRepository.findById(skuId.value)
            .orElseThrow { NoSuchElementException("SKU not found: $skuId") }

    @Transactional(readOnly = true)
    fun findByState(state: SkuState): List<Sku> =
        skuRepository.findByCurrentStateDiscriminator(state.toDiscriminator())

    @Transactional(readOnly = true)
    fun findAll(): List<Sku> = skuRepository.findAll()
}
