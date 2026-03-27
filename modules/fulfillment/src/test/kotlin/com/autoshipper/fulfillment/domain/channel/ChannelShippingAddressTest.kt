package com.autoshipper.fulfillment.domain.channel

import org.junit.jupiter.api.Test

/**
 * Tests for ChannelShippingAddress — verifies the data class captures all fields
 * needed by downstream consumers (ShippingAddress embeddable, CJ API).
 */
class ChannelShippingAddressTest {

    @Test
    fun `constructor with all fields`() {
        val addr = ChannelShippingAddress(
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

        assert(addr.customerName == "John Doe") { "customerName" }
        assert(addr.address1 == "123 Main St") { "address1" }
        assert(addr.address2 == "Apt 4B") { "address2" }
        assert(addr.city == "Anytown") { "city" }
        assert(addr.province == "California") { "province" }
        assert(addr.provinceCode == "CA") { "provinceCode" }
        assert(addr.zip == "90210") { "zip" }
        assert(addr.country == "United States") { "country" }
        assert(addr.countryCode == "US") { "countryCode" }
        assert(addr.phone == "+1-555-123-4567") { "phone" }
    }

    @Test
    fun `all fields nullable`() {
        val addr = ChannelShippingAddress(
            customerName = null,
            address1 = null,
            address2 = null,
            city = null,
            province = null,
            provinceCode = null,
            zip = null,
            country = null,
            countryCode = null,
            phone = null
        )

        assert(addr.customerName == null) { "customerName should be null" }
        assert(addr.address1 == null) { "address1 should be null" }
        assert(addr.city == null) { "city should be null" }
    }

    @Test
    fun `data class supports equality and copy`() {
        val addr1 = ChannelShippingAddress(
            customerName = "John Doe",
            address1 = "123 Main St",
            address2 = null,
            city = "Anytown",
            province = "CA",
            provinceCode = "CA",
            zip = "90210",
            country = "US",
            countryCode = "US",
            phone = "555-1234"
        )
        val addr2 = addr1.copy(city = "New City")

        assert(addr1 != addr2) { "copy with different city should not be equal" }
        assert(addr2.city == "New City") { "copy should have updated city" }
        assert(addr2.customerName == "John Doe") { "copy should preserve other fields" }
    }
}
