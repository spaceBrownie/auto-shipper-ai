package com.autoshipper.fulfillment.proxy.supplier

import com.autoshipper.fulfillment.domain.supplier.SupplierOrderAdapter
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderRequest
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderResult
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.util.UUID

@Configuration
@Profile("local")
class StubSupplierOrderConfiguration {
    @Bean
    fun stubSupplierOrderAdapter(): SupplierOrderAdapter = object : SupplierOrderAdapter {
        override fun supplierName(): String = "CJ_DROPSHIPPING"
        override fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult =
            SupplierOrderResult.Success(supplierOrderId = "stub_cj_${UUID.randomUUID()}")
    }
}
