package com.autoshipper.fulfillment.proxy.supplier

import com.autoshipper.fulfillment.domain.ShippingAddress

interface SupplierOrderAdapter {
    fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult
}

data class SupplierOrderRequest(
    val orderNumber: String,
    val shippingAddress: ShippingAddress?,
    val supplierProductId: String,
    val supplierVariantId: String,
    val quantity: Int
)

sealed class SupplierOrderResult {
    data class Success(val supplierOrderId: String) : SupplierOrderResult()
    data class Failure(val reason: String) : SupplierOrderResult()
}
