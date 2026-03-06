package com.autoshipper.fulfillment.proxy.carrier

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Instant

@Configuration
@Profile("local")
class StubCarrierTrackingConfiguration {

    @Bean
    fun stubUpsTrackingProvider(): CarrierTrackingProvider =
        StubCarrierTrackingProvider("UPS")

    @Bean
    fun stubFedExTrackingProvider(): CarrierTrackingProvider =
        StubCarrierTrackingProvider("FedEx")

    @Bean
    fun stubUspsTrackingProvider(): CarrierTrackingProvider =
        StubCarrierTrackingProvider("USPS")
}

class StubCarrierTrackingProvider(
    override val carrierName: String
) : CarrierTrackingProvider {

    override fun getTrackingStatus(trackingNumber: String): TrackingStatus =
        TrackingStatus(
            currentLocation = "Stub Warehouse, US",
            estimatedDelivery = Instant.now().plusSeconds(86_400),
            delivered = false,
            delayed = false
        )
}
