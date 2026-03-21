package com.autoshipper.fulfillment.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "shopify.webhook")
data class ShopifyWebhookProperties(
    val secrets: List<String> = emptyList(),
    val replayProtection: ReplayProtection = ReplayProtection()
) {
    data class ReplayProtection(
        val enabled: Boolean = false,
        val maxAgeSeconds: Long = 300
    )
}
