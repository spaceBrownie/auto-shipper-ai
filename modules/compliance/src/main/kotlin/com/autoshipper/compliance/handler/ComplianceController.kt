package com.autoshipper.compliance.handler

import com.autoshipper.catalog.domain.service.SkuService
import com.autoshipper.compliance.domain.service.ComplianceOrchestrator
import com.autoshipper.compliance.persistence.ComplianceAuditRepository
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/compliance/skus")
class ComplianceController(
    private val complianceOrchestrator: ComplianceOrchestrator,
    private val auditRepository: ComplianceAuditRepository,
    private val skuService: SkuService
) {

    @PostMapping("/{id}/check")
    fun triggerComplianceCheck(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: ManualCheckRequest?
    ): ResponseEntity<ComplianceStatusResponse> {
        val skuId = SkuId(id)
        val sku = skuService.findById(skuId)

        complianceOrchestrator.runChecks(
            skuId = skuId,
            productName = sku.name,
            productDescription = request?.productDescription ?: "",
            category = sku.category,
            vendorId = request?.vendorId?.let { VendorId.of(it) } ?: VendorId.new()
        )

        return getComplianceStatus(id)
    }

    @GetMapping("/{id}")
    fun getComplianceStatus(@PathVariable id: UUID): ResponseEntity<ComplianceStatusResponse> {
        val allRecords = auditRepository.findBySkuIdOrderByCheckedAtDesc(id)

        if (allRecords.isEmpty()) {
            return ResponseEntity.notFound().build()
        }

        // Scope latest result to the most recent check run only
        val latestRunId = allRecords.first().runId
        val latestRunRecords = allRecords.filter { it.runId == latestRunId }

        val latestResult = if (latestRunRecords.any { it.result == "FAILED" }) "FAILED" else "CLEARED"
        val latestReason = latestRunRecords.firstOrNull { it.result == "FAILED" }?.reason

        val response = ComplianceStatusResponse(
            skuId = id.toString(),
            latestResult = latestResult,
            latestReason = latestReason,
            auditHistory = allRecords.map { record ->
                AuditEntry(
                    checkType = record.checkType,
                    result = record.result,
                    reason = record.reason,
                    detail = record.detail,
                    checkedAt = record.checkedAt
                )
            }
        )

        return ResponseEntity.ok(response)
    }
}

data class ManualCheckRequest(
    val productDescription: String? = null,
    val vendorId: String? = null
)
