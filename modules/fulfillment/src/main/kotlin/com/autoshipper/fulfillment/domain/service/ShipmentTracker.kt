package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShipmentDetails
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.carrier.CarrierTrackingProvider
import com.autoshipper.shared.events.OrderFulfilled
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ShipmentTracker(
    private val orderRepository: OrderRepository,
    private val carrierProviders: List<CarrierTrackingProvider>,
    private val eventPublisher: ApplicationEventPublisher,
    private val delayAlertService: DelayAlertService
) {
    private val logger = LoggerFactory.getLogger(ShipmentTracker::class.java)

    private val providersByName: Map<String, CarrierTrackingProvider> by lazy {
        carrierProviders.associateBy { it.carrierName.lowercase() }
    }

    @PostConstruct
    fun warnIfNoProviders() {
        if (carrierProviders.isEmpty()) {
            logger.warn(
                "No CarrierTrackingProvider beans registered — " +
                "shipment tracking polling will be inactive. " +
                "DELIVERED status transitions are unreachable until real carrier tracking adapters are built."
            )
        } else {
            logger.info("Registered {} carrier tracking providers: {}",
                carrierProviders.size, carrierProviders.map { it.carrierName })
        }
    }

    @Scheduled(fixedRate = 1_800_000)
    @Transactional
    fun pollAllShipments() {
        if (carrierProviders.isEmpty()) {
            logger.debug("No carrier tracking providers registered — skipping poll cycle")
            return
        }
        val shippedOrders = orderRepository.findByStatus(OrderStatus.SHIPPED)
        logger.info("Polling tracking status for {} shipped orders", shippedOrders.size)

        for (order in shippedOrders) {
            try {
                val carrier = order.shipmentDetails.carrier?.lowercase()
                val trackingNumber = order.shipmentDetails.trackingNumber

                if (carrier == null || trackingNumber == null) {
                    logger.warn("Order {} missing carrier or tracking number, skipping", order.id)
                    continue
                }

                val provider = providersByName[carrier]
                if (provider == null) {
                    logger.warn("No tracking provider found for carrier '{}', skipping order {}", carrier, order.id)
                    continue
                }

                val status = provider.getTrackingStatus(trackingNumber)

                order.shipmentDetails = ShipmentDetails(
                    trackingNumber = trackingNumber,
                    carrier = order.shipmentDetails.carrier,
                    estimatedDelivery = status.estimatedDelivery ?: order.shipmentDetails.estimatedDelivery,
                    lastKnownLocation = status.currentLocation ?: order.shipmentDetails.lastKnownLocation,
                    delayDetected = status.delayed
                )

                if (status.delivered && order.status == OrderStatus.SHIPPED) {
                    order.updateStatus(OrderStatus.DELIVERED)
                    orderRepository.save(order)
                    eventPublisher.publishEvent(
                        OrderFulfilled(
                            orderId = order.orderId(),
                            skuId = order.skuId()
                        )
                    )
                    logger.info("Order {} delivered (tracking: {})", order.id, trackingNumber)
                } else {
                    orderRepository.save(order)
                    if (status.delayed) {
                        delayAlertService.checkAndAlert(order)
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to poll tracking for order {}", order.id, e)
            }
        }
    }
}
