package com.autoshipper.catalog.domain

data class Address(
    val street: String,
    val city: String,
    val stateOrProvince: String,
    val postalCode: String,
    /** ISO 3166-1 alpha-2 country code (e.g., "US", "CA") */
    val countryCode: String
) {
    init {
        require(countryCode.length == 2) { "countryCode must be ISO 3166-1 alpha-2 (2 characters), got: $countryCode" }
    }
}
