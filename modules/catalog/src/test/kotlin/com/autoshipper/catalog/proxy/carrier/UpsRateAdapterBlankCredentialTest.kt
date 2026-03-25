package com.autoshipper.catalog.proxy.carrier

import com.autoshipper.catalog.domain.Address
import com.autoshipper.catalog.domain.PackageDimensions
import com.autoshipper.catalog.domain.ProviderUnavailableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.client.RestClient
import java.math.BigDecimal

/**
 * BR-2: Blank-credential guard for UpsRateAdapter.
 *
 * Per CLAUDE.md constraint #13, adapters receiving blank @Value injections must
 * guard with early return that throws ProviderUnavailableException.
 *
 * These tests instantiate UpsRateAdapter with blank credentials and verify
 * that getRate() throws ProviderUnavailableException before making any HTTP call.
 * Tests will FAIL until the guard is implemented in Phase 5.
 */
class UpsRateAdapterBlankCredentialTest {

    private val dummyRestClient: RestClient = RestClient.builder()
        .baseUrl("http://localhost:9999")
        .build()

    private val origin = Address(
        street = "123 Main St",
        city = "New York",
        stateOrProvince = "NY",
        postalCode = "10001",
        countryCode = "US"
    )

    private val destination = Address(
        street = "456 Oak Ave",
        city = "Los Angeles",
        stateOrProvince = "CA",
        postalCode = "90001",
        countryCode = "US"
    )

    private val dims = PackageDimensions(
        lengthCm = BigDecimal("30"),
        widthCm = BigDecimal("20"),
        heightCm = BigDecimal("15"),
        weightKg = BigDecimal("2.5")
    )

    @Test
    fun `getRate throws ProviderUnavailableException when clientId is blank`() {
        val adapter = UpsRateAdapter(
            upsRestClient = dummyRestClient,
            clientId = "",
            clientSecret = "valid-secret"
        )

        assertThrows<ProviderUnavailableException> {
            adapter.getRate(origin, destination, dims)
        }
    }

    @Test
    fun `getRate throws ProviderUnavailableException when clientSecret is blank`() {
        val adapter = UpsRateAdapter(
            upsRestClient = dummyRestClient,
            clientId = "valid-id",
            clientSecret = ""
        )

        assertThrows<ProviderUnavailableException> {
            adapter.getRate(origin, destination, dims)
        }
    }

    @Test
    fun `getRate throws ProviderUnavailableException when both credentials are blank`() {
        val adapter = UpsRateAdapter(
            upsRestClient = dummyRestClient,
            clientId = "",
            clientSecret = ""
        )

        assertThrows<ProviderUnavailableException> {
            adapter.getRate(origin, destination, dims)
        }
    }
}
