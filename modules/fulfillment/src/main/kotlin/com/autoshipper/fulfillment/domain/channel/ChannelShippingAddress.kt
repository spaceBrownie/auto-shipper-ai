package com.autoshipper.fulfillment.domain.channel

import com.autoshipper.fulfillment.domain.ShippingAddress

/**
 * Channel-agnostic shipping address representation extracted from a sales channel webhook.
 * Mapped to the domain ShippingAddress when creating orders.
 */
data class ChannelShippingAddress(
    val customerName: String?,
    val address1: String?,
    val address2: String?,
    val city: String?,
    val province: String?,
    val provinceCode: String?,
    val zip: String?,
    val country: String?,
    val countryCode: String?,
    val phone: String?
)

/**
 * Maps channel-agnostic shipping address to the domain embeddable.
 * Note: address1 maps to address (the domain field name).
 */
fun ChannelShippingAddress.toShippingAddress(): ShippingAddress = ShippingAddress(
    customerName = customerName,
    address = address1,
    address2 = address2,
    city = city,
    province = province,
    provinceCode = provinceCode,
    zip = zip,
    country = country,
    countryCode = countryCode,
    phone = phone
)
