package com.autoshipper.fulfillment.domain.supplier

interface SupplierOrderAdapter {
    fun supplierName(): String
    fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult
}

data class SupplierOrderRequest(
    val orderNumber: String,
    val customerName: String,
    val address: String,
    val city: String,
    val province: String,
    val country: String,
    val countryCode: String,
    val zip: String,
    val phone: String,
    val supplierVariantId: String,
    val quantity: Int
)

sealed class SupplierOrderResult {
    data class Success(val supplierOrderId: String) : SupplierOrderResult()
    data class Failure(val reason: FailureReason, val message: String) : SupplierOrderResult()
}

enum class FailureReason {
    OUT_OF_STOCK,
    INVALID_ADDRESS,
    API_AUTH_FAILURE,
    NETWORK_ERROR,
    UNKNOWN
}
