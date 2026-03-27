package com.autoshipper.fulfillment.domain.channel

import java.math.BigDecimal

/**
 * Shipping address as extracted from a sales channel webhook (e.g., Shopify).
 * Includes address1/address2 split which gets combined into ShippingAddress.address
 * by LineItemOrderCreator.
 */
data class ChannelShippingAddress(
    val customerName: String? = null,
    val address1: String? = null,
    val address2: String? = null,
    val city: String? = null,
    val province: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val zip: String? = null,
    val phone: String? = null
)

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
