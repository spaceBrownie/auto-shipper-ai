package com.autoshipper.catalog.proxy.platform

import com.autoshipper.shared.money.Money

data class PlatformFees(
    val transactionFee: Money,
    val listingFee: Money
)
