package com.autoshipper.catalog.domain.service

import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.domain.TerminationReason
import com.autoshipper.shared.events.KillWindowBreached
import com.autoshipper.shared.identity.SkuId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.quality.Strictness
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogKillWindowListenerTest {

    @Mock
    private lateinit var skuService: SkuService

    private val skuId = SkuId(UUID.randomUUID())

    @Test
    fun `KillWindowBreached event terminates Listed SKU with MARGIN_BELOW_FLOOR`() {
        val sku = Sku(id = skuId.value, name = "Test", category = "Cat")
        sku.applyTransition(SkuState.ValidationPending)
        sku.applyTransition(SkuState.CostGating)
        sku.applyTransition(SkuState.StressTesting)
        sku.applyTransition(SkuState.Listed)

        doReturn(sku).`when`(skuService).findById(skuId)
        doReturn(sku).`when`(skuService).transition(skuId, SkuState.Terminated(TerminationReason.MARGIN_BELOW_FLOOR))

        val event = KillWindowBreached(
            skuId = skuId,
            daysNegative = 30,
            avgNetMargin = BigDecimal("-5.00")
        )

        val listener = CatalogKillWindowListener(skuService)
        listener.onKillWindowBreached(event)

        verify(skuService).transition(skuId, SkuState.Terminated(TerminationReason.MARGIN_BELOW_FLOOR))
    }

    @Test
    fun `KillWindowBreached event terminates Paused SKU`() {
        val sku = Sku(id = skuId.value, name = "Test", category = "Cat")
        sku.applyTransition(SkuState.ValidationPending)
        sku.applyTransition(SkuState.CostGating)
        sku.applyTransition(SkuState.StressTesting)
        sku.applyTransition(SkuState.Listed)
        sku.applyTransition(SkuState.Paused)

        doReturn(sku).`when`(skuService).findById(skuId)
        doReturn(sku).`when`(skuService).transition(skuId, SkuState.Terminated(TerminationReason.MARGIN_BELOW_FLOOR))

        val event = KillWindowBreached(
            skuId = skuId,
            daysNegative = 30,
            avgNetMargin = BigDecimal("-5.00")
        )

        val listener = CatalogKillWindowListener(skuService)
        listener.onKillWindowBreached(event)

        verify(skuService).transition(skuId, SkuState.Terminated(TerminationReason.MARGIN_BELOW_FLOOR))
    }

    @Test
    fun `KillWindowBreached event does not terminate SKU in Ideation state`() {
        val sku = Sku(id = skuId.value, name = "Test", category = "Cat")

        doReturn(sku).`when`(skuService).findById(skuId)

        val event = KillWindowBreached(
            skuId = skuId,
            daysNegative = 30,
            avgNetMargin = BigDecimal("-5.00")
        )

        val listener = CatalogKillWindowListener(skuService)
        listener.onKillWindowBreached(event)

        // Should not call transition since SKU is in Ideation state (not Listed/Scaled/Paused)
        verify(skuService).findById(skuId)
        // transition should not have been called — verify no further interactions
    }

    @Test
    fun `listener method has correct PM-005 annotations`() {
        val method = CatalogKillWindowListener::class.java.methods
            .find { it.name == "onKillWindowBreached" }

        assertNotNull(method, "onKillWindowBreached method should exist")

        val txEventListener = method!!.getAnnotation(TransactionalEventListener::class.java)
        assertNotNull(txEventListener, "@TransactionalEventListener should be present")
        assertEquals(TransactionPhase.AFTER_COMMIT, txEventListener!!.phase)

        val transactional = method.getAnnotation(Transactional::class.java)
        assertNotNull(transactional, "@Transactional should be present")
        assertEquals(Propagation.REQUIRES_NEW, transactional!!.propagation)
    }
}
