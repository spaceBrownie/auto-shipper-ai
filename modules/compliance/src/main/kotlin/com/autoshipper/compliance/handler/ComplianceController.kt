package com.autoshipper.compliance.handler

import com.autoshipper.compliance.domain.ComplianceAuditRecord
import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.service.ComplianceOrchestrator
import com.autoshipper.compliance.persistence.ComplianceAuditRepository
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/compliance")
class ComplianceController(
    private val orchestrator: ComplianceOrchestrator,
    private val auditRepository: ComplianceAuditRepository
) {

    @PostMapping("/skus/{id}/check")
    fun triggerCheck(
        @PathVariable id: String,
        @RequestBody request: ComplianceCheckRequest
    ): ResponseEntity<ComplianceCheckResponse> {
        val skuId = SkuId.of(id)
        val results = orchestrator.runChecks(
            skuId = skuId,
            productName = request.productName,
            productDescription = request.productDescription,
            category = request.category,
            vendorId = VendorId(UUID.fromString(request.vendorId))
        )
        return ResponseEntity.ok(ComplianceCheckResponse.from(skuId, results))
    }

    @GetMapping("/skus/{id}")
    fun getComplianceStatus(@PathVariable id: String): ResponseEntity<ComplianceStatusResponse> {
        val skuId = SkuId.of(id)
        val records = auditRepository.findBySkuIdOrderByCheckedAtDesc(skuId.value)
        if (records.isEmpty()) {
            return ResponseEntity.notFound().build()
        }
        return ResponseEntity.ok(ComplianceStatusResponse.from(skuId, records))
    }
}

data class ComplianceCheckRequest(
    val productName: String,
    val productDescription: String,
    val category: String,
    val vendorId: String
)

data class ComplianceCheckResponse(
    val skuId: String,
    val overallResult: String,
    val checks: List<CheckResultDto>
) {
    companion object {
        fun from(skuId: SkuId, results: List<ComplianceCheckResult>): ComplianceCheckResponse {
            val failures = results.filterIsInstance<ComplianceCheckResult.Failed>()
            return ComplianceCheckResponse(
                skuId = skuId.value.toString(),
                overallResult = if (failures.isEmpty()) "CLEARED" else "FAILED",
                checks = results.map { result ->
                    when (result) {
                        is ComplianceCheckResult.Cleared -> CheckResultDto(
                            checkType = result.checkType.name,
                            result = "CLEARED"
                        )
                        is ComplianceCheckResult.Failed -> CheckResultDto(
                            checkType = result.checkType.name,
                            result = "FAILED",
                            reason = result.reason.name,
                            detail = result.detail
                        )
                    }
                }
            )
        }
    }
}

data class CheckResultDto(
    val checkType: String,
    val result: String,
    val reason: String? = null,
    val detail: String? = null
)

data class ComplianceStatusResponse(
    val skuId: String,
    val latestResult: String,
    val auditHistory: List<AuditEntryDto>
) {
    companion object {
        fun from(skuId: SkuId, records: List<ComplianceAuditRecord>): ComplianceStatusResponse {
            return ComplianceStatusResponse(
                skuId = skuId.value.toString(),
                latestResult = records.first().result,
                auditHistory = records.map { record ->
                    AuditEntryDto(
                        checkType = record.checkType,
                        result = record.result,
                        failureReason = record.failureReason,
                        detail = record.detail,
                        checkedAt = record.checkedAt.toString()
                    )
                }
            )
        }
    }
}

data class AuditEntryDto(
    val checkType: String,
    val result: String,
    val failureReason: String? = null,
    val detail: String? = null,
    val checkedAt: String
)
