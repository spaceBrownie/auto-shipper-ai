package com.autoshipper.fulfillment.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
class ShippingAddress(
    @Column(name = "shipping_customer_name")
    val customerName: String? = null,

    @Column(name = "shipping_address")
    val address: String? = null,

    @Column(name = "shipping_address2")
    val address2: String? = null,

    @Column(name = "shipping_city")
    val city: String? = null,

    @Column(name = "shipping_province")
    val province: String? = null,

    @Column(name = "shipping_province_code")
    val provinceCode: String? = null,

    @Column(name = "shipping_zip")
    val zip: String? = null,

    @Column(name = "shipping_country")
    val country: String? = null,

    @Column(name = "shipping_country_code")
    val countryCode: String? = null,

    @Column(name = "shipping_phone")
    val phone: String? = null
)
