package com.autoshipper.portfolio.proxy

import com.autoshipper.portfolio.domain.DemandSignalProvider
import com.autoshipper.portfolio.domain.RawCandidate
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("local")
class StubGoogleTrendsProvider : DemandSignalProvider {

    override fun sourceType(): String = "GOOGLE_TRENDS"

    override fun fetch(): List<RawCandidate> = listOf(
        RawCandidate(
            productName = "Bamboo Kitchen Set",
            category = "Home & Kitchen",
            description = "Trending search: bamboo kitchen set",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = null,
            demandSignals = mapOf("approx_traffic" to "10000+", "trend_date" to "2026-03-17")
        ),
        RawCandidate(
            productName = "Portable Neck Fan",
            category = "Electronics",
            description = "Trending search: portable neck fan",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = null,
            demandSignals = mapOf("approx_traffic" to "50000+", "trend_date" to "2026-03-17")
        ),
        RawCandidate(
            productName = "Reusable Produce Bags",
            category = "Home & Kitchen",
            description = "Trending search: reusable produce bags",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = null,
            demandSignals = mapOf("approx_traffic" to "5000+", "trend_date" to "2026-03-17")
        )
    )
}
