package com.autoshipper.capital.handler

import com.autoshipper.capital.domain.MarginSnapshot
import com.autoshipper.capital.domain.service.ReserveAccountService
import com.autoshipper.capital.domain.service.SkuPnlReport
import com.autoshipper.capital.domain.service.SkuPnlReporter
import com.autoshipper.capital.handler.dto.MarginSnapshotResponse
import com.autoshipper.capital.handler.dto.ReserveResponse
import com.autoshipper.capital.handler.dto.SkuPnlResponse
import com.autoshipper.capital.persistence.MarginSnapshotRepository
import com.autoshipper.shared.identity.SkuId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/capital")
class CapitalController(
    private val reserveAccountService: ReserveAccountService,
    private val skuPnlReporter: SkuPnlReporter,
    private val marginSnapshotRepository: MarginSnapshotRepository
) {

    @GetMapping("/reserve")
    fun getReserve(): ResponseEntity<ReserveResponse> {
        val status = reserveAccountService.getBalance()
        return ResponseEntity.ok(
            ReserveResponse(
                balanceAmount = status.balance.normalizedAmount.toPlainString(),
                balanceCurrency = status.balance.currency.name,
                health = status.health
            )
        )
    }

    @GetMapping("/skus/{id}/pnl")
    fun getSkuPnl(
        @PathVariable id: String,
        @RequestParam from: String,
        @RequestParam to: String
    ): ResponseEntity<SkuPnlResponse> {
        val skuId = SkuId.of(id)
        val report = skuPnlReporter.report(
            skuId,
            LocalDate.parse(from),
            LocalDate.parse(to)
        )
        return ResponseEntity.ok(report.toResponse())
    }

    @GetMapping("/skus/{id}/margin-history")
    fun getMarginHistory(
        @PathVariable id: String,
        @RequestParam from: String,
        @RequestParam to: String
    ): ResponseEntity<List<MarginSnapshotResponse>> {
        val skuId = SkuId.of(id)
        val snapshots = marginSnapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            skuId.value,
            LocalDate.parse(from),
            LocalDate.parse(to)
        )
        return ResponseEntity.ok(snapshots.map { it.toResponse() })
    }

    private fun MarginSnapshot.toResponse(): MarginSnapshotResponse = MarginSnapshotResponse(
        snapshotDate = snapshotDate.toString(),
        grossMarginPercent = grossMarginPercent(),
        netMarginPercent = netMarginPercent(),
        refundRate = refundRatePercent(),
        chargebackRate = chargebackRatePercent()
    )

    private fun SkuPnlReport.toResponse(): SkuPnlResponse = SkuPnlResponse(
        skuId = skuId.value.toString(),
        from = from.toString(),
        to = to.toString(),
        totalRevenueAmount = totalRevenue.normalizedAmount.toPlainString(),
        totalRevenueCurrency = totalRevenue.currency.name,
        totalCostAmount = totalCost.normalizedAmount.toPlainString(),
        totalCostCurrency = totalCost.currency.name,
        averageGrossMarginPercent = averageGrossMarginPercent.toPlainString(),
        averageNetMarginPercent = averageNetMarginPercent.toPlainString(),
        snapshotCount = snapshotCount
    )
}
