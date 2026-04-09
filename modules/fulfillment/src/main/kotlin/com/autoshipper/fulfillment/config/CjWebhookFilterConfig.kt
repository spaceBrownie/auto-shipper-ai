package com.autoshipper.fulfillment.config

import com.autoshipper.fulfillment.handler.webhook.CjWebhookTokenVerificationFilter
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(CjWebhookProperties::class)
class CjWebhookFilterConfig {

    @Bean
    fun cjWebhookTokenFilter(properties: CjWebhookProperties): FilterRegistrationBean<CjWebhookTokenVerificationFilter> {
        val registration = FilterRegistrationBean<CjWebhookTokenVerificationFilter>()
        registration.filter = CjWebhookTokenVerificationFilter(properties.secret)
        registration.addUrlPatterns("/webhooks/cj/*")
        registration.order = 1
        return registration
    }
}
