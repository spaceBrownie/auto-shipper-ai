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
    override fun createFulfillment(shopifyOrderId: String, trackingNumber: String, carrier: String): Boolean {
        if (accessToken.isBlank()) {
            logger.warn("Shopify access token is blank — cannot create fulfillment")
            return false
        }

        // Step 1: Query fulfillment orders for this Shopify order.
        // channelOrderId stores the numeric Shopify order ID (e.g., "820982911946154500").
        // fulfillmentCreateV2 requires a FulfillmentOrder GID, not an Order GID.
        val orderGid = "gid://shopify/Order/$shopifyOrderId"
        val fulfillmentOrderGids = queryFulfillmentOrders(orderGid)
        if (fulfillmentOrderGids.isEmpty()) {
            logger.warn("No fulfillment orders found for Shopify order {} (GID={})", shopifyOrderId, orderGid)
            return false
        }

        // Step 2: Create fulfillment using the FulfillmentOrder GID(s)
        return executeFulfillmentCreate(fulfillmentOrderGids, trackingNumber, carrier, shopifyOrderId)
    }

    private fun queryFulfillmentOrders(orderGid: String): List<String> {
        val query = """
            query {
              order(id: "$orderGid") {
                fulfillmentOrders(first: 10) {
                  edges {
                    node {
                      id
                      status
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val graphqlBody = mapOf("query" to query)

        val responseBody = shopifyRestClient.post()
            .uri("/admin/api/2024-01/graphql.json")
            .header("X-Shopify-Access-Token", accessToken)
            .header("Content-Type", "application/json")
            .body(objectMapper.writeValueAsString(graphqlBody))
            .retrieve()
            .body(String::class.java)
            ?: run {
                logger.warn("Empty response from Shopify when querying fulfillment orders for {}", orderGid)
                return emptyList()
            }

        val root = objectMapper.readTree(responseBody)

        // Check for top-level errors
        val errors = root.get("errors")
        if (errors != null && !errors.isNull && errors.isArray && errors.size() > 0) {
            val errorMessage = errors[0]?.get("message")?.let { if (!it.isNull) it.asText() else null }
                ?: "Unknown Shopify error"
            logger.warn("Shopify GraphQL error querying fulfillment orders for {}: {}", orderGid, errorMessage)
            return emptyList()
        }

        val edges = root.get("data")?.get("order")?.get("fulfillmentOrders")?.get("edges")
        if (edges == null || edges.isNull || !edges.isArray) {
            logger.warn("No fulfillmentOrders edges in Shopify response for {}", orderGid)
            return emptyList()
        }

        return edges.mapNotNull { edge ->
            val node = edge.get("node")
            val id = node?.get("id")?.let { if (!it.isNull) it.asText() else null }
            val status = node?.get("status")?.let { if (!it.isNull) it.asText() else null }
            // Only include fulfillment orders that are open/in-progress
            if (id != null && status in listOf("OPEN", "IN_PROGRESS", "SCHEDULED")) id else null
        }
    }

    private fun executeFulfillmentCreate(
        fulfillmentOrderGids: List<String>,
        trackingNumber: String,
        carrier: String,
        shopifyOrderId: String
    ): Boolean {
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
                "lineItemsByFulfillmentOrder" to fulfillmentOrderGids.map { gid ->
                    mapOf("fulfillmentOrderId" to gid)
                },
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
                logger.warn("Empty response from Shopify GraphQL API for order {}", shopifyOrderId)
                return false
            }

        val root = objectMapper.readTree(responseBody)

        // Check for top-level errors (auth failures, etc.)
        val errors = root.get("errors")
        if (errors != null && !errors.isNull && errors.isArray && errors.size() > 0) {
            val errorMessage = errors[0]?.get("message")?.let { if (!it.isNull) it.asText() else null }
                ?: "Unknown Shopify error"
            logger.warn("Shopify GraphQL error for order {}: {}", shopifyOrderId, errorMessage)
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
            logger.warn("Shopify fulfillment userError for order {}: field={}, message={}", shopifyOrderId, field, message)
            return false
        }

        val fulfillment = fulfillmentCreateV2?.get("fulfillment")
        if (fulfillment == null || fulfillment.isNull) {
            logger.warn("Shopify fulfillment response has null fulfillment and no userErrors for order {}", shopifyOrderId)
            return false
        }

        val fulfillmentId = fulfillment.get("id")?.let { if (!it.isNull) it.asText() else null }
        logger.info("Shopify fulfillment created for order {}: fulfillmentId={}", shopifyOrderId, fulfillmentId)
        return true
    }
}
