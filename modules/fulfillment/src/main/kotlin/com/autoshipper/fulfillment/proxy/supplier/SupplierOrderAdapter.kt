package com.autoshipper.fulfillment.proxy.supplier

import com.autoshipper.fulfillment.domain.ShippingAddress

/**
 * Abstraction for placing orders with external suppliers (CJ Dropshipping, Printful, etc.).
 * Each supplier implements this interface.
 */
interface SupplierOrderAdapter {
    fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult
}

data class SupplierOrderRequest(
    val orderNumber: String,
    val shippingAddress: ShippingAddress,
    val products: List<SupplierOrderProduct>,
    val logisticName: String,
    val fromCountryCode: String
)

data class SupplierOrderProduct(
    val vid: String,
    val quantity: Int
)

data class SupplierOrderResult(
    val supplierOrderId: String,
    val status: String
)
