package com.autoshipper.fulfillment.domain

import org.junit.jupiter.api.Test

/**
 * Tests for the ShippingAddress embeddable — verifies field construction
 * and the nullable-default contract for backward compatibility.
 */
class ShippingAddressTest {

    @Test
    fun `constructor with all fields populates all properties`() {
        val addr = ShippingAddress(
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

        assert(addr.customerName == "John Doe") { "customerName mismatch" }
        assert(addr.address == "123 Main St") { "address mismatch" }
        assert(addr.address2 == "Apt 4B") { "address2 mismatch" }
        assert(addr.city == "Anytown") { "city mismatch" }
        assert(addr.province == "California") { "province mismatch" }
        assert(addr.provinceCode == "CA") { "provinceCode mismatch" }
        assert(addr.zip == "90210") { "zip mismatch" }
        assert(addr.country == "United States") { "country mismatch" }
        assert(addr.countryCode == "US") { "countryCode mismatch" }
        assert(addr.phone == "+1-555-123-4567") { "phone mismatch" }
    }

    @Test
    fun `default constructor creates instance with all nulls for backward compatibility`() {
        val addr = ShippingAddress()

        assert(addr.customerName == null) { "customerName should default to null" }
        assert(addr.address == null) { "address should default to null" }
        assert(addr.address2 == null) { "address2 should default to null" }
        assert(addr.city == null) { "city should default to null" }
        assert(addr.province == null) { "province should default to null" }
        assert(addr.provinceCode == null) { "provinceCode should default to null" }
        assert(addr.zip == null) { "zip should default to null" }
        assert(addr.country == null) { "country should default to null" }
        assert(addr.countryCode == null) { "countryCode should default to null" }
        assert(addr.phone == null) { "phone should default to null" }
    }

    @Test
    fun `partial construction with only required CJ fields`() {
        val addr = ShippingAddress(
            customerName = "Jane Smith",
            address = "456 Oak Ave",
            city = "Springfield",
            province = "Illinois",
            zip = "62701",
            country = "United States",
            countryCode = "US",
            phone = "+1-555-987-6543"
        )

        assert(addr.customerName == "Jane Smith") { "customerName mismatch" }
        assert(addr.address2 == null) { "address2 should be null when not provided" }
        assert(addr.provinceCode == null) { "provinceCode should be null when not provided" }
    }
}
