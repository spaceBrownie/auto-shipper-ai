package com.autoshipper.fulfillment.domain.channel

import java.math.BigDecimal

/**
 * Normalized order representation produced by a ChannelOrderAdapter.
 * Channel-agnostic — the processing service operates on this model, not on channel-specific JSON.
 */
data class ChannelOrder(
    val channelOrderId: String,
    val channelOrderNumber: String,
    val channelName: String,
    val customerEmail: String,
    val currencyCode: String,
    val lineItems: List<ChannelLineItem>,
    val shippingAddress: ChannelShippingAddress? = null
)

data class ChannelLineItem(
    val externalProductId: String,
    val externalVariantId: String?,
    val quantity: Int,
    val unitPrice: BigDecimal,
    val title: String
)
