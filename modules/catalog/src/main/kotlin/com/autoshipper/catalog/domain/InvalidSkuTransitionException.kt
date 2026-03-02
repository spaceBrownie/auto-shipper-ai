package com.autoshipper.catalog.domain

class InvalidSkuTransitionException(from: SkuState, to: SkuState) :
    RuntimeException("Invalid SKU transition from ${from.toDiscriminator()} to ${to.toDiscriminator()}")
