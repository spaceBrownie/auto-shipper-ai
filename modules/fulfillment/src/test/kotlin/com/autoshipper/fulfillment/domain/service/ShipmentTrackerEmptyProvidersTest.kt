package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.carrier.CarrierTrackingProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.context.ApplicationEventPublisher

@ExtendWith(MockitoExtension::class)
class ShipmentTrackerEmptyProvidersTest {

    @Mock
    lateinit var orderRepository: OrderRepository

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @Mock
    lateinit var delayAlertService: DelayAlertService

    private lateinit var tracker: ShipmentTracker

    @BeforeEach
    fun setUp() {
        tracker = ShipmentTracker(
            orderRepository = orderRepository,
            carrierProviders = emptyList(),
            eventPublisher = eventPublisher,
            delayAlertService = delayAlertService
        )
    }

    @Test
    fun `pollAllShipments with no providers skips polling entirely`() {
        tracker.pollAllShipments()

        verifyNoInteractions(orderRepository)
        verifyNoInteractions(eventPublisher)
        verifyNoInteractions(delayAlertService)
    }

    @Test
    fun `warnIfNoProviders does not throw when provider list is empty`() {
        tracker.warnIfNoProviders()
    }

    @Test
    fun `warnIfNoProviders does not throw when providers are present`() {
        val mockProvider = mock<CarrierTrackingProvider> {
            on { carrierName } doReturn "UPS"
        }
        val trackerWithProviders = ShipmentTracker(
            orderRepository = orderRepository,
            carrierProviders = listOf(mockProvider),
            eventPublisher = eventPublisher,
            delayAlertService = delayAlertService
        )

        trackerWithProviders.warnIfNoProviders()
    }
}
