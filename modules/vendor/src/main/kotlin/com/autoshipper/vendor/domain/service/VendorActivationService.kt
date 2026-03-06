package com.autoshipper.vendor.domain.service

import com.autoshipper.shared.identity.VendorId
import com.autoshipper.vendor.domain.Vendor
import com.autoshipper.vendor.domain.VendorSkuAssignment
import com.autoshipper.vendor.persistence.VendorRepository
import com.autoshipper.vendor.persistence.VendorSkuAssignmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class VendorActivationService(
    private val vendorRepository: VendorRepository,
    private val assignmentRepository: VendorSkuAssignmentRepository
) {
    fun register(name: String, contactEmail: String): Vendor {
        val vendor = Vendor(name = name, contactEmail = contactEmail)
        return vendorRepository.save(vendor)
    }

    fun activate(vendorId: VendorId): Vendor {
        val vendor = vendorRepository.findById(vendorId.value)
            .orElseThrow { NoSuchElementException("Vendor not found: $vendorId") }
        vendor.activate()
        return vendorRepository.save(vendor)
    }

    fun updateChecklist(
        vendorId: VendorId,
        slaConfirmed: Boolean? = null,
        defectRateDocumented: Boolean? = null,
        scalabilityConfirmed: Boolean? = null,
        fulfillmentTimesConfirmed: Boolean? = null,
        refundPolicyConfirmed: Boolean? = null
    ): Vendor {
        val vendor = vendorRepository.findById(vendorId.value)
            .orElseThrow { NoSuchElementException("Vendor not found: $vendorId") }

        slaConfirmed?.let { vendor.checklist.slaConfirmed = it }
        defectRateDocumented?.let { vendor.checklist.defectRateDocumented = it }
        scalabilityConfirmed?.let { vendor.checklist.scalabilityConfirmed = it }
        fulfillmentTimesConfirmed?.let { vendor.checklist.fulfillmentTimesConfirmed = it }
        refundPolicyConfirmed?.let { vendor.checklist.refundPolicyConfirmed = it }

        return vendorRepository.save(vendor)
    }

    fun assignSku(vendorId: VendorId, skuId: UUID): VendorSkuAssignment {
        vendorRepository.findById(vendorId.value)
            .orElseThrow { NoSuchElementException("Vendor not found: $vendorId") }

        val assignment = VendorSkuAssignment(vendorId = vendorId.value, skuId = skuId)
        return assignmentRepository.save(assignment)
    }

    @Transactional(readOnly = true)
    fun findById(vendorId: VendorId): Vendor =
        vendorRepository.findById(vendorId.value)
            .orElseThrow { NoSuchElementException("Vendor not found: $vendorId") }

    @Transactional(readOnly = true)
    fun findAll(): List<Vendor> = vendorRepository.findAll()
}
