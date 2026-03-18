package com.autoshipper.portfolio.domain

interface DemandSignalProvider {
    fun sourceType(): String
    fun fetch(): List<RawCandidate>
}
