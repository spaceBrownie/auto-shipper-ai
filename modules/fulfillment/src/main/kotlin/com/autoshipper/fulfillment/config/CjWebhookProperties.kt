package com.autoshipper.fulfillment.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cj-dropshipping.webhook")
data class CjWebhookProperties(
    val secret: String = ""
)
