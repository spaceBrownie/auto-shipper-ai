package com.autoshipper.fulfillment.domain.channel

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Parses Shopify orders/create webhook payloads into normalized ChannelOrder model.
 *
 * Uses Jackson get() (not path()) for field extraction to ensure null-coalescing
 * works correctly — get() returns null for missing fields, whereas path() returns
 * MissingNode whose asText() returns "" (never null). See CLAUDE.md constraint #15.
 */
@Component
class ShopifyOrderAdapter(
    private val objectMapper: ObjectMapper
) : ChannelOrderAdapter {

    override fun parse(rawPayload: String): ChannelOrder {
        val root = objectMapper.readTree(rawPayload)

        val orderId = root.get("id")?.let { if (!it.isNull) it.asText() else null }
            ?: error("Shopify order missing 'id' field")
        val orderNumber = root.get("name")?.let { if (!it.isNull) it.asText() else null } ?: ""
        val currencyCode = root.get("currency")?.let { if (!it.isNull) it.asText() else null } ?: "USD"

        // Customer email: prefer top-level email, fall back to customer.email,
        // then generate a deterministic fallback from customer ID.
        // Guard against JSON null values (NullNode.asText() returns "null" string).
        val customerNode = root.get("customer")
        val topLevelEmail = root.get("email")?.let { if (!it.isNull) it.asText() else null }
        val customerNodeEmail = customerNode?.get("email")?.let { if (!it.isNull) it.asText() else null }
        val customerEmail = topLevelEmail
            ?: customerNodeEmail
            ?: run {
                val customerId = customerNode?.get("id")?.let { if (!it.isNull) it.asText() else null } ?: "unknown"
                "unknown-$customerId@noemail.shopify"
            }

        val lineItemsNode = root.get("line_items")
        val lineItems = mutableListOf<ChannelLineItem>()

        if (lineItemsNode != null && lineItemsNode.isArray) {
            for (itemNode in lineItemsNode) {
                val productNode = itemNode.get("product_id")
                val productId = if (productNode != null && !productNode.isNull) productNode.asText() else continue
                val variantNode = itemNode.get("variant_id")
                val variantId = if (variantNode != null && !variantNode.isNull) variantNode.asText() else null
                val quantity = itemNode.get("quantity")?.asInt() ?: 1
                val price = itemNode.get("price")?.let { if (!it.isNull) it.asText() else null }?.let { BigDecimal(it) } ?: BigDecimal.ZERO
                val title = itemNode.get("title")?.let { if (!it.isNull) it.asText() else null } ?: ""

                lineItems.add(
                    ChannelLineItem(
                        externalProductId = productId,
                        externalVariantId = variantId,
                        quantity = quantity,
                        unitPrice = price,
                        title = title
                    )
                )
            }
        }

        val addrNode = root.get("shipping_address")
        val shippingAddress = if (addrNode != null && !addrNode.isNull) {
            ChannelShippingAddress(
                firstName = addrNode.get("first_name")?.let { if (!it.isNull) it.asText() else null },
                lastName = addrNode.get("last_name")?.let { if (!it.isNull) it.asText() else null },
                address1 = addrNode.get("address1")?.let { if (!it.isNull) it.asText() else null },
                address2 = addrNode.get("address2")?.let { if (!it.isNull) it.asText() else null },
                city = addrNode.get("city")?.let { if (!it.isNull) it.asText() else null },
                province = addrNode.get("province")?.let { if (!it.isNull) it.asText() else null },
                provinceCode = addrNode.get("province_code")?.let { if (!it.isNull) it.asText() else null },
                country = addrNode.get("country")?.let { if (!it.isNull) it.asText() else null },
                countryCode = addrNode.get("country_code")?.let { if (!it.isNull) it.asText() else null },
                zip = addrNode.get("zip")?.let { if (!it.isNull) it.asText() else null },
                phone = addrNode.get("phone")?.let { if (!it.isNull) it.asText() else null }
            )
        } else null

        return ChannelOrder(
            channelOrderId = orderId,
            channelOrderNumber = orderNumber,
            channelName = channelName(),
            customerEmail = customerEmail,
            currencyCode = currencyCode,
            lineItems = lineItems,
            shippingAddress = shippingAddress
        )
    }

    override fun channelName(): String = "shopify"
}
