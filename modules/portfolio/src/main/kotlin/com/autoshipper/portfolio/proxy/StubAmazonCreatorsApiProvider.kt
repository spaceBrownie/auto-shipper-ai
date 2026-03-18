package com.autoshipper.portfolio.proxy

import com.autoshipper.portfolio.domain.DemandSignalProvider
import com.autoshipper.portfolio.domain.RawCandidate
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
@Profile("local")
class StubAmazonCreatorsApiProvider : DemandSignalProvider {

    override fun sourceType(): String = "AMAZON_CREATORS_API"

    override fun fetch(): List<RawCandidate> = listOf(
        RawCandidate(
            productName = "LED Desk Lamp with Wireless Charger",
            category = "Electronics",
            description = "Adjustable LED lamp with 3 color modes and wireless charging base",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = Money.of(BigDecimal("35.99"), Currency.USD),
            demandSignals = mapOf(
                "bsr" to "2500",
                "seller_count" to "8",
                "review_count" to "1200",
                "asin" to "B09STUB001"
            )
        ),
        RawCandidate(
            productName = "Collapsible Silicone Water Bottle 600ml",
            category = "Sports & Outdoors",
            description = "Lightweight foldable water bottle for hiking and travel",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = Money.of(BigDecimal("18.99"), Currency.USD),
            demandSignals = mapOf(
                "bsr" to "4200",
                "seller_count" to "15",
                "review_count" to "850",
                "asin" to "B09STUB002"
            )
        ),
        RawCandidate(
            productName = "Magnetic Phone Mount for Car",
            category = "Automotive",
            description = "Universal magnetic car phone holder with dashboard mount",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = Money.of(BigDecimal("12.99"), Currency.USD),
            demandSignals = mapOf(
                "bsr" to "800",
                "seller_count" to "45",
                "review_count" to "5600",
                "asin" to "B09STUB003"
            )
        )
    )
}
