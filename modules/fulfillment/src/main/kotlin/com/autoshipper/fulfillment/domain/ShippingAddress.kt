package com.autoshipper.fulfillment.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
class ShippingAddress(
    @Column(name = "shipping_customer_name") var customerName: String? = null,
    @Column(name = "shipping_address")       var address: String? = null,
    @Column(name = "shipping_city")          var city: String? = null,
    @Column(name = "shipping_province")      var province: String? = null,
    @Column(name = "shipping_country")       var country: String? = null,
    @Column(name = "shipping_country_code")  var countryCode: String? = null,
    @Column(name = "shipping_zip")           var zip: String? = null,
    @Column(name = "shipping_phone")         var phone: String? = null
)
