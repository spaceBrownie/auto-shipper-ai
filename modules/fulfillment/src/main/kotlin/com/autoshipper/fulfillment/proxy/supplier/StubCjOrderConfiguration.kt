package com.autoshipper.fulfillment.proxy.supplier

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.util.UUID

@Configuration
@Profile("local")
class StubCjOrderConfiguration {
    @Bean
    fun stubSupplierOrderAdapter(): SupplierOrderAdapter = object : SupplierOrderAdapter {
        override fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult =
            SupplierOrderResult(
                supplierOrderId = "stub_cj_${UUID.randomUUID()}",
                status = "CREATED"
            )
    }
}
