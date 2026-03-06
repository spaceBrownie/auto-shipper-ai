package com.autoshipper.catalog.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.client.RestClient

@Configuration
@Profile("!local")
class ExternalApiConfig {

    @Bean("upsRestClient")
    fun upsRestClient(
        @Value("\${ups.api.base-url}") baseUrl: String
    ): RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("Accept", "application/json")
        .build()

    @Bean("fedexRestClient")
    fun fedexRestClient(
        @Value("\${fedex.api.base-url}") baseUrl: String
    ): RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("Accept", "application/json")
        .build()

    @Bean("uspsRestClient")
    fun uspsRestClient(
        @Value("\${usps.api.base-url}") baseUrl: String
    ): RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("Accept", "application/json")
        .build()

    @Bean("stripeRestClient")
    fun stripeRestClient(
        @Value("\${stripe.api.base-url}") baseUrl: String
    ): RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("Accept", "application/json")
        .build()

    @Bean("shopifyRestClient")
    fun shopifyRestClient(
        @Value("\${shopify.api.base-url}") baseUrl: String
    ): RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("Accept", "application/json")
        .build()
}
