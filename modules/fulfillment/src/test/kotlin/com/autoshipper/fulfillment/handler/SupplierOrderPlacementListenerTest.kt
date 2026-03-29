package com.autoshipper.fulfillment.handler

import com.autoshipper.fulfillment.domain.service.SupplierOrderPlacementService
import com.autoshipper.shared.events.OrderConfirmed
import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.identity.SkuId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class SupplierOrderPlacementListenerTest {

    @Mock
    lateinit var supplierOrderPlacementService: SupplierOrderPlacementService

    @InjectMocks
    lateinit var listener: SupplierOrderPlacementListener

    @Test
    fun `onOrderConfirmed delegates to placement service with correct orderId`() {
        val orderId = UUID.randomUUID()
        val skuId = UUID.randomUUID()
        val event = OrderConfirmed(
            orderId = OrderId(orderId),
            skuId = SkuId(skuId)
        )

        listener.onOrderConfirmed(event)

        verify(supplierOrderPlacementService).placeSupplierOrder(orderId)
    }
}
