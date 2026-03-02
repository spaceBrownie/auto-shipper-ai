package com.autoshipper.catalog.proxy.carrier

import com.autoshipper.catalog.domain.Address
import com.autoshipper.catalog.domain.PackageDimensions
import com.autoshipper.shared.money.Money

interface CarrierRateProvider {
    val carrierName: String
    fun getRate(origin: Address, destination: Address, dims: PackageDimensions): Money
}
