package com.autoshipper.fulfillment.config

import com.autoshipper.fulfillment.handler.webhook.ShopifyHmacVerificationFilter
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(ShopifyWebhookProperties::class)
class ShopifyWebhookFilterConfig {

    @Bean
    fun shopifyHmacFilter(properties: ShopifyWebhookProperties): FilterRegistrationBean<ShopifyHmacVerificationFilter> {
        val registration = FilterRegistrationBean<ShopifyHmacVerificationFilter>()
        registration.filter = ShopifyHmacVerificationFilter(properties.secrets)
        registration.addUrlPatterns("/webhooks/shopify/*")
        registration.order = 1
        return registration
    }
}
