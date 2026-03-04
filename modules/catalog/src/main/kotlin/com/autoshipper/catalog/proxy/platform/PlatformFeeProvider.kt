package com.autoshipper.catalog.proxy.platform

import com.autoshipper.shared.money.Money

interface PlatformFeeProvider {
    fun getFee(): Money
}
