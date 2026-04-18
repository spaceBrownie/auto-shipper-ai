package com.autoshipper.fulfillment.handler.webhook

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers {@link WebhookArchivalFilter} on the webhook URL patterns.
 *
 * Gated by {@code autoshipper.webhook-archival.enabled} — defaults to
 * disabled so production never archives webhook payloads to disk.
 *
 * Order is numerically less than {@link com.autoshipper.fulfillment.config.ShopifyWebhookFilterConfig}
 * (which registers the HMAC filter at order 1), so archival runs FIRST
 * and captures payloads even when HMAC verification later rejects them.
 */
@Configuration
@ConditionalOnProperty(
    name = ["autoshipper.webhook-archival.enabled"],
    havingValue = "true",
)
class WebhookArchivalFilterConfig {

    @Bean
    fun webhookArchivalFilterRegistration(
        filter: WebhookArchivalFilter,
    ): FilterRegistrationBean<WebhookArchivalFilter> {
        val registration = FilterRegistrationBean<WebhookArchivalFilter>()
        registration.setFilter(filter)
        registration.setUrlPatterns(listOf("/webhooks/shopify/*", "/webhooks/cj/*"))
        // ShopifyHmacVerificationFilter is registered at order = 1.
        // Archival must run BEFORE HMAC verification so we capture
        // payloads that fail HMAC, hence: 1 - 10 = -9.
        registration.order = ARCHIVAL_FILTER_ORDER
        return registration
    }

    companion object {
        private const val ARCHIVAL_FILTER_ORDER = -9
    }
}
