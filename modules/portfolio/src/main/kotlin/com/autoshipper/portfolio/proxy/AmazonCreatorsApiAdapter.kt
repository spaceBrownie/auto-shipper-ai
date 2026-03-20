package com.autoshipper.portfolio.proxy

import com.autoshipper.portfolio.domain.DemandSignalProvider
import com.autoshipper.portfolio.domain.RawCandidate
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
@Profile("!local")
@ConditionalOnProperty(name = ["amazon-creators.enabled"], havingValue = "true", matchIfMissing = false)
class AmazonCreatorsApiAdapter(
    @Value("\${amazon-creators.api.base-url:}") private val baseUrl: String,
    @Value("\${amazon-creators.api.credential-id:}") private val credentialId: String,
    @Value("\${amazon-creators.api.credential-secret:}") private val credentialSecret: String,
    @Value("\${amazon-creators.api.partner-tag:}") private val partnerTag: String,
    @Value("\${amazon-creators.api.marketplace:}") private val marketplace: String
) : DemandSignalProvider {

    private val logger = LoggerFactory.getLogger(AmazonCreatorsApiAdapter::class.java)
    private val restClient by lazy { RestClient.builder().baseUrl(baseUrl).build() }
    private val tokenLock = ReentrantLock()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiresAt: Long = 0

    override fun sourceType(): String = "AMAZON_CREATORS_API"

    override fun fetch(): List<RawCandidate> {
        if (baseUrl.isBlank() || credentialId.isBlank() || credentialSecret.isBlank()) {
            logger.warn("Amazon Creators API credentials are blank — skipping fetch")
            return emptyList()
        }

        logger.info("Fetching products from Amazon Creators API")

        val token = getAccessToken()
        val searchTerms = listOf("kitchen gadgets", "phone accessories", "fitness equipment", "home organization")
        val candidates = mutableListOf<RawCandidate>()

        for (term in searchTerms) {
            try {
                val requestBody = mapOf(
                    "partnerTag" to partnerTag,
                    "partnerType" to "Associates",
                    "marketplace" to marketplace,
                    "keywords" to term,
                    "itemCount" to 10,
                    "resources" to listOf(
                        "itemInfo.title",
                        "itemInfo.classifications",
                        "offersV2.listings.price",
                        "offersV2.listings.listingCount",
                        "browseNodeInfo.browseNodes.salesRank"
                    )
                )

                val response = restClient.post()
                    .uri("/paapi5/searchitems")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode::class.java)

                val items = response?.path("searchResult")?.path("items")
                if (items != null && items.isArray) {
                    for (item in items) {
                        candidates.add(mapItem(item, term))
                    }
                }
            } catch (e: Exception) {
                logger.warn("Amazon Creators API search for '{}' failed: {}", term, e.message)
            }
        }

        logger.info("Amazon Creators API returned {} candidates", candidates.size)
        return candidates
    }

    private fun getAccessToken(): String {
        tokenLock.withLock {
            if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
                return cachedToken!!
            }

            val tokenClient = RestClient.builder()
                .baseUrl("https://api.amazon.com")
                .build()

            val response = tokenClient.post()
                .uri("/auth/o2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&client_id=$credentialId&client_secret=$credentialSecret")
                .retrieve()
                .body(JsonNode::class.java)

            cachedToken = response?.get("access_token")?.asText()
                ?: throw IllegalStateException("Failed to obtain Amazon access token")
            val expiresIn = response.path("expires_in").asLong(3600)
            tokenExpiresAt = System.currentTimeMillis() + (expiresIn - 60) * 1000

            logger.info("Obtained Amazon Creators API access token (expires in {}s)", expiresIn)
            return cachedToken!!
        }
    }

    private fun mapItem(item: JsonNode, searchTerm: String): RawCandidate {
        val title = item.path("itemInfo").path("title").path("displayValue").asText("Unknown")
        val asin = item.path("asin").asText("")
        val category = item.path("itemInfo").path("classifications")
            .path("binding").path("displayValue").asText("General")
        val price = item.path("offersV2").path("listings")
            .firstOrNull()?.path("price")?.path("amount")?.asDouble()
        val sellerCount = item.path("offersV2").path("listings")
            .firstOrNull()?.path("listingCount")?.asInt(0) ?: 0
        val bsr = item.path("browseNodeInfo").path("browseNodes")
            .firstOrNull()?.path("salesRank")?.asLong(0) ?: 0

        return RawCandidate(
            productName = title,
            category = category,
            description = "Amazon search: $searchTerm",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = if (price != null && price > 0) {
                Money.of(BigDecimal.valueOf(price), Currency.USD)
            } else null,
            demandSignals = mapOf(
                "asin" to asin,
                "bsr" to bsr.toString(),
                "seller_count" to sellerCount.toString(),
                "search_term" to searchTerm
            )
        )
    }
}
