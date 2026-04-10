package com.autoshipper.portfolio.proxy

import com.autoshipper.portfolio.domain.DemandSignalProvider
import com.autoshipper.portfolio.domain.RawCandidate
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal

@Component
@Profile("!local")
class CjDropshippingAdapter(
    @Value("\${cj-dropshipping.api.base-url:}") private val baseUrl: String,
    @Value("\${cj-dropshipping.api.access-token:}") private val accessToken: String
) : DemandSignalProvider {

    private val logger = LoggerFactory.getLogger(CjDropshippingAdapter::class.java)
    private val restClient by lazy { RestClient.builder().baseUrl(baseUrl).build() }

    override fun sourceType(): String = "CJ_DROPSHIPPING"

    override fun fetch(): List<RawCandidate> {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            logger.warn("CJ Dropshipping API credentials are blank — skipping fetch")
            return emptyList()
        }

        logger.info("Fetching products from CJ Dropshipping API")

        val categories = listOf("Kitchen & Dining", "Electronics", "Sports & Outdoors", "Home & Garden")
        val candidates = mutableListOf<RawCandidate>()

        for (category in categories) {
            try {
                val response = restClient.get()
                    .uri { uri ->
                        uri.path("/product/listV2")
                            .queryParam("keyWord", category)
                            .queryParam("countryCode", "US")
                            .queryParam("verifiedWarehouse", 1)
                            .queryParam("page", 1)
                            .queryParam("size", 20)
                            .build()
                    }
                    .header("CJ-Access-Token", accessToken)
                    .retrieve()
                    .body(JsonNode::class.java)

                val products = response?.path("data")?.path("list")
                if (products != null && products.isArray) {
                    var candidatesFromCategory = 0
                    for (product in products) {
                        val inventoryNum = product.get("warehouseInventoryNum")
                            ?.let { if (!it.isNull) it.asInt(-1) else null }

                        if (inventoryNum == null || inventoryNum <= 0) {
                            val pid = product.path("pid").asText("unknown")
                            logger.debug("Excluding CJ product {} — warehouseInventoryNum is {} (zero, null, or absent)", pid, inventoryNum)
                            continue
                        }

                        candidates.add(mapProduct(product, category, inventoryNum))
                        candidatesFromCategory++
                    }
                    val totalProducts = products.size()
                    logger.info("CJ category '{}': {} total products, {} passed warehouse inventory filter", category, totalProducts, candidatesFromCategory)
                }
            } catch (e: Exception) {
                logger.warn("Failed to fetch CJ products for category '{}': {}", category, e.message)
            }
        }

        logger.info("CJ Dropshipping returned {} candidates", candidates.size)
        return candidates
    }

    private fun mapProduct(product: JsonNode, category: String, warehouseInventoryNum: Int): RawCandidate {
        val sellPrice = product.path("sellPrice").asDouble(0.0)
        return RawCandidate(
            productName = product.path("productNameEn").asText("Unknown Product"),
            category = product.path("categoryName").asText(category),
            description = product.path("description").asText(""),
            sourceType = sourceType(),
            supplierUnitCost = Money.of(BigDecimal.valueOf(sellPrice), Currency.USD),
            estimatedSellingPrice = Money.of(
                BigDecimal.valueOf(sellPrice).multiply(BigDecimal("2.5")),
                Currency.USD
            ),
            demandSignals = mapOf(
                "cj_pid" to product.path("pid").asText(""),
                "cj_category_id" to product.path("categoryId").asText(""),
                "cj_product_image" to product.path("productImage").asText(""),
                "cj_warehouse_inventory_num" to warehouseInventoryNum.toString()
            )
        )
    }
}
