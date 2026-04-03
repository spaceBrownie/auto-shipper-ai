package com.autoshipper.fulfillment.proxy.platform

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
@Profile("!local")
class ShopifyFulfillmentAdapter(
    @Qualifier("shopifyRestClient") private val shopifyRestClient: RestClient,
    @Value("\${shopify.api.access-token:}") private val accessToken: String
) : ShopifyFulfillmentPort {

    private val logger = LoggerFactory.getLogger(ShopifyFulfillmentAdapter::class.java)
    private val objectMapper = ObjectMapper()

    @CircuitBreaker(name = "shopify-fulfillment")
    @Retry(name = "shopify-fulfillment")
    override fun createFulfillment(shopifyOrderGid: String, trackingNumber: String, carrier: String): Boolean {
        if (accessToken.isBlank()) {
            logger.warn("Shopify access token is blank — cannot create fulfillment")
            return false
        }

        val mutation = """
            mutation fulfillmentCreateV2(${'$'}fulfillment: FulfillmentV2Input!) {
              fulfillmentCreateV2(fulfillment: ${'$'}fulfillment) {
                fulfillment {
                  id
                  status
                }
                userErrors {
                  field
                  message
                }
              }
            }
        """.trimIndent()

        val variables = mapOf(
            "fulfillment" to mapOf(
                "lineItemsByFulfillmentOrder" to listOf(
                    mapOf("fulfillmentOrderId" to shopifyOrderGid)
                ),
                "trackingInfo" to mapOf(
                    "company" to carrier,
                    "number" to trackingNumber
                ),
                "notifyCustomer" to true
            )
        )

        val graphqlBody = mapOf(
            "query" to mutation,
            "variables" to variables
        )

        val responseBody = shopifyRestClient.post()
            .uri("/admin/api/2024-01/graphql.json")
            .header("X-Shopify-Access-Token", accessToken)
            .header("Content-Type", "application/json")
            .body(objectMapper.writeValueAsString(graphqlBody))
            .retrieve()
            .body(String::class.java)
            ?: run {
                logger.warn("Empty response from Shopify GraphQL API for order {}", shopifyOrderGid)
                return false
            }

        val root = objectMapper.readTree(responseBody)

        // Check for top-level errors (auth failures, etc.)
        val errors = root.get("errors")
        if (errors != null && !errors.isNull && errors.isArray && errors.size() > 0) {
            val errorMessage = errors[0]?.get("message")?.let { if (!it.isNull) it.asText() else null }
                ?: "Unknown Shopify error"
            logger.warn("Shopify GraphQL error for order {}: {}", shopifyOrderGid, errorMessage)
            return false
        }

        val data = root.get("data")
        val fulfillmentCreateV2 = data?.get("fulfillmentCreateV2")

        // NullNode guard per CLAUDE.md #17
        val userErrors = fulfillmentCreateV2?.get("userErrors")
        if (userErrors != null && !userErrors.isNull && userErrors.isArray && userErrors.size() > 0) {
            val firstError = userErrors[0]
            val field = firstError?.get("field")?.let { if (!it.isNull) it.toString() else null }
            val message = firstError?.get("message")?.let { if (!it.isNull) it.asText() else null }
            logger.warn("Shopify fulfillment userError for order {}: field={}, message={}", shopifyOrderGid, field, message)
            return false
        }

        val fulfillment = fulfillmentCreateV2?.get("fulfillment")
        if (fulfillment == null || fulfillment.isNull) {
            logger.warn("Shopify fulfillment response has null fulfillment and no userErrors for order {}", shopifyOrderGid)
            return false
        }

        val fulfillmentId = fulfillment.get("id")?.let { if (!it.isNull) it.asText() else null }
        logger.info("Shopify fulfillment created for order {}: fulfillmentId={}", shopifyOrderGid, fulfillmentId)
        return true
    }
}
