package com.autoshipper.portfolio.proxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StubProviderTest {

    private val cjProvider = StubCjDropshippingProvider()
    private val googleTrendsProvider = StubGoogleTrendsProvider()
    private val amazonProvider = StubAmazonCreatorsApiProvider()

    @Test
    fun `CJ Dropshipping stub returns non-empty list`() {
        val candidates = cjProvider.fetch()
        assertTrue(candidates.isNotEmpty())
    }

    @Test
    fun `CJ Dropshipping stub returns correct source type`() {
        assertEquals("CJ_DROPSHIPPING", cjProvider.sourceType())
    }

    @Test
    fun `CJ Dropshipping stub - all candidates have supplierUnitCost set`() {
        val candidates = cjProvider.fetch()
        candidates.forEach { candidate ->
            assertNotNull(candidate.supplierUnitCost, "supplierUnitCost should be set for '${candidate.productName}'")
        }
    }

    @Test
    fun `Google Trends stub returns non-empty list`() {
        val candidates = googleTrendsProvider.fetch()
        assertTrue(candidates.isNotEmpty())
    }

    @Test
    fun `Google Trends stub returns correct source type`() {
        assertEquals("GOOGLE_TRENDS", googleTrendsProvider.sourceType())
    }

    @Test
    fun `Google Trends stub - all candidates have approx_traffic in demandSignals`() {
        val candidates = googleTrendsProvider.fetch()
        candidates.forEach { candidate ->
            assertTrue(
                candidate.demandSignals.containsKey("approx_traffic"),
                "approx_traffic should be present for '${candidate.productName}'"
            )
        }
    }

    @Test
    fun `Amazon Creators API stub returns non-empty list`() {
        val candidates = amazonProvider.fetch()
        assertTrue(candidates.isNotEmpty())
    }

    @Test
    fun `Amazon Creators API stub returns correct source type`() {
        assertEquals("AMAZON_CREATORS_API", amazonProvider.sourceType())
    }

    @Test
    fun `Amazon Creators API stub - all candidates have bsr and seller_count in demandSignals`() {
        val candidates = amazonProvider.fetch()
        candidates.forEach { candidate ->
            assertTrue(
                candidate.demandSignals.containsKey("bsr"),
                "bsr should be present for '${candidate.productName}'"
            )
            assertTrue(
                candidate.demandSignals.containsKey("seller_count"),
                "seller_count should be present for '${candidate.productName}'"
            )
        }
    }
}
