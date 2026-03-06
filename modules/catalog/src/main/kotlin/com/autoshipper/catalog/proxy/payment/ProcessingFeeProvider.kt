package com.autoshipper.catalog.proxy.payment

import com.autoshipper.shared.money.Money

interface ProcessingFeeProvider {
    fun getFee(estimatedOrderValue: Money): Money
}
