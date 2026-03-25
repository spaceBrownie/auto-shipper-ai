package com.autoshipper.catalog.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.client.RestClient

@Configuration
@Profile("!local")
class ExternalApiConfig {

    private val logger = LoggerFactory.getLogger(ExternalApiConfig::class.java)

    @Bean("upsRestClient")
    fun upsRestClient(
        @Value("\${ups.api.base-url:}") baseUrl: String
    ): RestClient {
        if (baseUrl.isBlank()) {
            logger.warn("ups.api.base-url is blank — returning unconfigured RestClient")
            return RestClient.builder()
                .baseUrl("http://unconfigured")
                .defaultHeader("Accept", "application/json")
                .build()
        }
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Accept", "application/json")
            .build()
    }

    @Bean("fedexRestClient")
    fun fedexRestClient(
        @Value("\${fedex.api.base-url:}") baseUrl: String
    ): RestClient {
        if (baseUrl.isBlank()) {
            logger.warn("fedex.api.base-url is blank — returning unconfigured RestClient")
            return RestClient.builder()
                .baseUrl("http://unconfigured")
                .defaultHeader("Accept", "application/json")
                .build()
        }
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Accept", "application/json")
            .build()
    }

    @Bean("uspsRestClient")
    fun uspsRestClient(
        @Value("\${usps.api.base-url:}") baseUrl: String
    ): RestClient {
        if (baseUrl.isBlank()) {
            logger.warn("usps.api.base-url is blank — returning unconfigured RestClient")
            return RestClient.builder()
                .baseUrl("http://unconfigured")
                .defaultHeader("Accept", "application/json")
                .build()
        }
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Accept", "application/json")
            .build()
    }

    @Bean("stripeRestClient")
    fun stripeRestClient(
        @Value("\${stripe.api.base-url:}") baseUrl: String
    ): RestClient {
        if (baseUrl.isBlank()) {
            logger.warn("stripe.api.base-url is blank — returning unconfigured RestClient")
            return RestClient.builder()
                .baseUrl("http://unconfigured")
                .defaultHeader("Accept", "application/json")
                .build()
        }
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Accept", "application/json")
            .build()
    }

    @Bean("shopifyRestClient")
    fun shopifyRestClient(
        @Value("\${shopify.api.base-url:}") baseUrl: String
    ): RestClient {
        if (baseUrl.isBlank()) {
            logger.warn("shopify.api.base-url is blank — returning unconfigured RestClient")
            return RestClient.builder()
                .baseUrl("http://unconfigured")
                .defaultHeader("Accept", "application/json")
                .build()
        }
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Accept", "application/json")
            .build()
    }
}
