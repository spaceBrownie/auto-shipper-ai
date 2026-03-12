package com.autoshipper.capital.domain.service

import java.util.UUID

interface ActiveSkuProvider {
    fun getActiveSkuIds(): List<UUID>
}
