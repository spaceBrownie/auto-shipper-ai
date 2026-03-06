package com.autoshipper.vendor.handler

import com.autoshipper.shared.identity.VendorId
import com.autoshipper.vendor.domain.service.VendorActivationService
import com.autoshipper.vendor.domain.service.VendorReliabilityScorer
import com.autoshipper.vendor.handler.dto.*
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/vendors")
class VendorController(
    private val activationService: VendorActivationService,
    private val reliabilityScorer: VendorReliabilityScorer
) {
    @PostMapping
    fun register(@Valid @RequestBody request: RegisterVendorRequest): ResponseEntity<VendorResponse> {
        val vendor = activationService.register(request.name, request.contactEmail)
        return ResponseEntity.status(HttpStatus.CREATED).body(VendorResponse.from(vendor))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<VendorResponse> {
        val vendor = activationService.findById(VendorId.of(id))
        return ResponseEntity.ok(VendorResponse.from(vendor))
    }

    @GetMapping
    fun list(): ResponseEntity<List<VendorResponse>> {
        val vendors = activationService.findAll()
        return ResponseEntity.ok(vendors.map { VendorResponse.from(it) })
    }

    @PatchMapping("/{id}/checklist")
    fun updateChecklist(
        @PathVariable id: String,
        @RequestBody request: UpdateChecklistRequest
    ): ResponseEntity<VendorResponse> {
        val vendor = activationService.updateChecklist(
            vendorId = VendorId.of(id),
            slaConfirmed = request.slaConfirmed,
            defectRateDocumented = request.defectRateDocumented,
            scalabilityConfirmed = request.scalabilityConfirmed,
            fulfillmentTimesConfirmed = request.fulfillmentTimesConfirmed,
            refundPolicyConfirmed = request.refundPolicyConfirmed
        )
        return ResponseEntity.ok(VendorResponse.from(vendor))
    }

    @PostMapping("/{id}/activate")
    fun activate(@PathVariable id: String): ResponseEntity<VendorResponse> {
        val vendor = activationService.activate(VendorId.of(id))
        return ResponseEntity.ok(VendorResponse.from(vendor))
    }

    @PostMapping("/{id}/score")
    fun computeScore(
        @PathVariable id: String,
        @RequestBody request: ComputeScoreRequest
    ): ResponseEntity<VendorScoreResponse> {
        val score = reliabilityScorer.compute(
            vendorId = VendorId.of(id),
            onTimeRate = request.onTimeRate,
            defectRate = request.defectRate,
            avgResponseTimeHours = request.avgResponseTimeHours
        )
        return ResponseEntity.ok(VendorScoreResponse.from(score))
    }
}
