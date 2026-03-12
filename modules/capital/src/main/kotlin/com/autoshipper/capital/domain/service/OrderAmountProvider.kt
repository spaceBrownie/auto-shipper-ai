package com.autoshipper.capital.domain.service

import com.autoshipper.shared.money.Money
import java.util.UUID

interface OrderAmountProvider {
    fun getOrderAmount(orderId: UUID): Money?
}
