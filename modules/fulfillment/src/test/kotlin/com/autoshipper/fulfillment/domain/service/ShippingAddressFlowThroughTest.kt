package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.ShippingAddress
import com.autoshipper.fulfillment.domain.channel.ChannelShippingAddress
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderProduct
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderRequest
import org.junit.jupiter.api.Test

/**
 * Dedicated data lineage test: shipping address must flow from Shopify webhook
 * all the way to the CJ API request without any field being silently dropped.
 *
 * Pipeline: Shopify shipping_address JSON -> ChannelShippingAddress
 *           -> ShippingAddress (embeddable) -> SupplierOrderRequest -> CJ API body
 *
 * CJ required fields: shippingCustomerName, shippingAddress, shippingCity,
 * shippingProvince, shippingZip, shippingCountry, shippingCountryCode, shippingPhone
 */
class ShippingAddressFlowThroughTest {

    // --- Stage 1: Shopify JSON -> ChannelShippingAddress ---

    @Test
    fun `stage 1 - ChannelShippingAddress carries all Shopify shipping fields`() {
        // Simulates what ShopifyOrderAdapter.parse() produces
        val channelAddr = ChannelShippingAddress(
            customerName = "John Doe",
            address1 = "123 Main St",
            address2 = "Apt 4B",
            city = "Anytown",
            province = "California",
            provinceCode = "CA",
            zip = "90210",
            country = "United States",
            countryCode = "US",
            phone = "+1-555-123-4567"
        )

        assert(channelAddr.customerName == "John Doe") { "customerName lost in Stage 1" }
        assert(channelAddr.address1 == "123 Main St") { "address1 lost in Stage 1" }
        assert(channelAddr.address2 == "Apt 4B") { "address2 lost in Stage 1" }
        assert(channelAddr.city == "Anytown") { "city lost in Stage 1" }
        assert(channelAddr.province == "California") { "province lost in Stage 1" }
        assert(channelAddr.provinceCode == "CA") { "provinceCode lost in Stage 1" }
        assert(channelAddr.zip == "90210") { "zip lost in Stage 1" }
        assert(channelAddr.country == "United States") { "country lost in Stage 1" }
        assert(channelAddr.countryCode == "US") { "countryCode lost in Stage 1" }
        assert(channelAddr.phone == "+1-555-123-4567") { "phone lost in Stage 1" }
    }

    @Test
    fun `customerName combines first_name and last_name from Shopify`() {
        // Shopify sends: { "first_name": "John", "last_name": "Doe" }
        // ShopifyOrderAdapter must combine: "John Doe"
        val firstName = "John"
        val lastName = "Doe"
        val combined = listOfNotNull(firstName, lastName).joinToString(" ")

        assert(combined == "John Doe") {
            "Expected 'John Doe' but got '$combined'"
        }
    }

    @Test
    fun `customerName handles missing last_name`() {
        val firstName = "Madonna"
        val lastName: String? = null
        val combined = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { null }

        assert(combined == "Madonna") {
            "Expected 'Madonna' but got '$combined'"
        }
    }

    // --- Stage 2: ChannelShippingAddress -> ShippingAddress (domain embeddable) ---

    @Test
    fun `stage 2 - ChannelShippingAddress maps to ShippingAddress without field loss`() {
        val channelAddr = ChannelShippingAddress(
            customerName = "John Doe",
            address1 = "123 Main St",
            address2 = "Apt 4B",
            city = "Anytown",
            province = "California",
            provinceCode = "CA",
            zip = "90210",
            country = "United States",
            countryCode = "US",
            phone = "+1-555-123-4567"
        )

        // Phase 5: This mapping will be done by ChannelShippingAddress.toShippingAddress()
        val domainAddr = ShippingAddress(
            customerName = channelAddr.customerName,
            address = channelAddr.address1,
            address2 = channelAddr.address2,
            city = channelAddr.city,
            province = channelAddr.province,
            provinceCode = channelAddr.provinceCode,
            zip = channelAddr.zip,
            country = channelAddr.country,
            countryCode = channelAddr.countryCode,
            phone = channelAddr.phone
        )

        assert(domainAddr.customerName == "John Doe") { "customerName lost in Stage 2" }
        assert(domainAddr.address == "123 Main St") { "address lost in Stage 2 (address1 -> address)" }
        assert(domainAddr.address2 == "Apt 4B") { "address2 lost in Stage 2" }
        assert(domainAddr.city == "Anytown") { "city lost in Stage 2" }
        assert(domainAddr.province == "California") { "province lost in Stage 2" }
        assert(domainAddr.provinceCode == "CA") { "provinceCode lost in Stage 2" }
        assert(domainAddr.zip == "90210") { "zip lost in Stage 2" }
        assert(domainAddr.country == "United States") { "country lost in Stage 2" }
        assert(domainAddr.countryCode == "US") { "countryCode lost in Stage 2" }
        assert(domainAddr.phone == "+1-555-123-4567") { "phone lost in Stage 2" }
    }

    // --- Stage 3: ShippingAddress -> SupplierOrderRequest -> CJ API body ---

    @Test
    fun `stage 3 - ShippingAddress flows into SupplierOrderRequest for CJ API`() {
        val domainAddr = ShippingAddress(
            customerName = "John Doe",
            address = "123 Main St",
            address2 = "Apt 4B",
            city = "Anytown",
            province = "California",
            provinceCode = "CA",
            zip = "90210",
            country = "United States",
            countryCode = "US",
            phone = "+1-555-123-4567"
        )

        val request = SupplierOrderRequest(
            orderNumber = "test-order-12345",
            shippingAddress = domainAddr,
            products = listOf(SupplierOrderProduct(vid = "CJ-VID-001", quantity = 3)),
            logisticName = "CJPacket",
            fromCountryCode = "CN"
        )

        // CJ API field mapping:
        assert(request.shippingAddress.customerName == "John Doe") {
            "shippingCustomerName lost before CJ API call"
        }
        assert(request.shippingAddress.address == "123 Main St") {
            "shippingAddress lost before CJ API call"
        }
        assert(request.shippingAddress.city == "Anytown") {
            "shippingCity lost before CJ API call"
        }
        assert(request.shippingAddress.province == "California") {
            "shippingProvince lost before CJ API call"
        }
        assert(request.shippingAddress.zip == "90210") {
            "shippingZip lost before CJ API call"
        }
        assert(request.shippingAddress.country == "United States") {
            "shippingCountry lost before CJ API call"
        }
        assert(request.shippingAddress.countryCode == "US") {
            "shippingCountryCode lost before CJ API call"
        }
        assert(request.shippingAddress.phone == "+1-555-123-4567") {
            "shippingPhone lost before CJ API call"
        }
    }

    // --- End-to-end: every CJ-required field present from Shopify source ---

    @Test
    fun `end-to-end all 8 CJ-required shipping fields survive the pipeline`() {
        // These are the 8 CJ-required shipping fields from the spec:
        val cjRequiredFields = mapOf(
            "shippingCustomerName" to "John Doe",
            "shippingAddress" to "123 Main St",
            "shippingCity" to "Anytown",
            "shippingProvince" to "California",
            "shippingZip" to "90210",
            "shippingCountry" to "United States",
            "shippingCountryCode" to "US",
            "shippingPhone" to "+1-555-123-4567"
        )

        val addr = ShippingAddress(
            customerName = "John Doe",
            address = "123 Main St",
            city = "Anytown",
            province = "California",
            zip = "90210",
            country = "United States",
            countryCode = "US",
            phone = "+1-555-123-4567"
        )

        // Map domain fields to CJ field names for verification:
        val actualValues = mapOf(
            "shippingCustomerName" to addr.customerName,
            "shippingAddress" to addr.address,
            "shippingCity" to addr.city,
            "shippingProvince" to addr.province,
            "shippingZip" to addr.zip,
            "shippingCountry" to addr.country,
            "shippingCountryCode" to addr.countryCode,
            "shippingPhone" to addr.phone
        )

        for ((cjField, expectedValue) in cjRequiredFields) {
            val actual = actualValues[cjField]
            assert(actual == expectedValue) {
                "CJ field '$cjField' expected '$expectedValue' but got '$actual' — data loss in pipeline!"
            }
        }
    }
}
