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
class StubCjDropshippingProvider : DemandSignalProvider {

    override fun sourceType(): String = "CJ_DROPSHIPPING"

    override fun fetch(): List<RawCandidate> = listOf(
        RawCandidate(
            productName = "Bamboo Kitchen Utensil Set (6-piece)",
            category = "Kitchen & Dining",
            description = "Eco-friendly bamboo cooking utensils with silicone handles",
            sourceType = sourceType(),
            supplierUnitCost = Money.of(BigDecimal("4.20"), Currency.USD),
            estimatedSellingPrice = Money.of(BigDecimal("24.99"), Currency.USD),
            demandSignals = mapOf("cj_sales_count" to "1500", "cj_rating" to "4.7")
        ),
        RawCandidate(
            productName = "LED Desk Lamp with Wireless Charger",
            category = "Electronics",
            description = "Adjustable LED desk lamp with built-in Qi wireless charging pad",
            sourceType = sourceType(),
            supplierUnitCost = Money.of(BigDecimal("8.50"), Currency.USD),
            estimatedSellingPrice = Money.of(BigDecimal("39.99"), Currency.USD),
            demandSignals = mapOf("cj_sales_count" to "3200", "cj_rating" to "4.5")
        ),
        RawCandidate(
            productName = "Portable Blender USB Rechargeable",
            category = "Kitchen & Dining",
            description = "Personal-size blender with USB-C charging, 380ml capacity",
            sourceType = sourceType(),
            supplierUnitCost = Money.of(BigDecimal("5.80"), Currency.USD),
            estimatedSellingPrice = Money.of(BigDecimal("29.99"), Currency.USD),
            demandSignals = mapOf("cj_sales_count" to "8700", "cj_rating" to "4.3")
        ),
        RawCandidate(
            productName = "Silicone Collapsible Water Bottle",
            category = "Sports & Outdoors",
            description = "BPA-free collapsible silicone water bottle, 600ml",
            sourceType = sourceType(),
            supplierUnitCost = Money.of(BigDecimal("2.10"), Currency.USD),
            estimatedSellingPrice = Money.of(BigDecimal("14.99"), Currency.USD),
            demandSignals = mapOf("cj_sales_count" to "4500", "cj_rating" to "4.2")
        )
    )
}
