package com.autoshipper.portfolio.domain

import java.math.BigDecimal

data class ScoredCandidate(
    val raw: RawCandidate,
    val demandScore: BigDecimal,
    val marginPotentialScore: BigDecimal,
    val competitionScore: BigDecimal,
    val compositeScore: BigDecimal,
    val passed: Boolean
)
