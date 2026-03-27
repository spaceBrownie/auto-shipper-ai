package com.autoshipper.fulfillment.proxy.supplier

import com.autoshipper.fulfillment.domain.ShippingAddress
import org.junit.jupiter.api.Test

/**
 * Contract tests for the SupplierOrderAdapter interface and its data types.
 * Verifies that the interface, request, and result types are correctly defined
 * for supplier abstraction (BR-6).
 */
class SupplierOrderAdapterContractTest {

    // --- BR-6: Supplier abstraction ---

    @Test
    fun `SupplierOrderAdapter is an interface`() {
        assert(SupplierOrderAdapter::class.java.isInterface) {
            "SupplierOrderAdapter must be an interface for supplier abstraction"
        }
    }

    @Test
    fun `SupplierOrderAdapter declares placeOrder method`() {
        val method = SupplierOrderAdapter::class.java.declaredMethods.find { it.name == "placeOrder" }
        assert(method != null) { "SupplierOrderAdapter must declare placeOrder()" }
    }

    @Test
    fun `SupplierOrderRequest carries all fields needed for CJ order`() {
        val request = SupplierOrderRequest(
            orderNumber = "ORDER-001",
            shippingAddress = ShippingAddress(
                customerName = "Test User",
                address = "1 Test St",
                city = "Testville",
                province = "TS",
                zip = "00000",
                country = "US",
                countryCode = "US",
                phone = "555-0000"
            ),
            products = listOf(
                SupplierOrderProduct(vid = "VID-001", quantity = 3)
            ),
            logisticName = "CJPacket",
            fromCountryCode = "CN"
        )

        assert(request.orderNumber == "ORDER-001") { "orderNumber" }
        assert(request.shippingAddress.customerName == "Test User") { "shippingAddress.customerName" }
        assert(request.products.size == 1) { "products size" }
        assert(request.products[0].vid == "VID-001") { "products[0].vid" }
        assert(request.products[0].quantity == 3) { "products[0].quantity must be 3, not 1" }
        assert(request.logisticName == "CJPacket") { "logisticName" }
        assert(request.fromCountryCode == "CN") { "fromCountryCode" }
    }

    @Test
    fun `SupplierOrderResult carries supplier order ID and status`() {
        val result = SupplierOrderResult(
            supplierOrderId = "CJ-ORD-12345",
            status = "CREATED"
        )

        assert(result.supplierOrderId == "CJ-ORD-12345") { "supplierOrderId" }
        assert(result.status == "CREATED") { "status" }
    }

    @Test
    fun `SupplierOrderProduct carries vid and quantity`() {
        val product = SupplierOrderProduct(vid = "CJ-VID-001", quantity = 5)

        assert(product.vid == "CJ-VID-001") { "vid" }
        assert(product.quantity == 5) { "quantity must be 5, not hardcoded 1" }
    }

    // --- NFR-2: Idempotency via orderNumber ---

    @Test
    fun `orderNumber in request enables CJ-side deduplication`() {
        val orderId = java.util.UUID.randomUUID()
        val request = SupplierOrderRequest(
            orderNumber = orderId.toString(),
            shippingAddress = ShippingAddress(),
            products = listOf(SupplierOrderProduct(vid = "VID-001", quantity = 3)),
            logisticName = "CJPacket",
            fromCountryCode = "CN"
        )

        // The orderNumber should be the internal order ID for cross-reference deduplication
        assert(request.orderNumber == orderId.toString()) {
            "orderNumber should be the internal order ID for CJ-side deduplication"
        }
    }
}
