package com.autoshipper.vendor.handler

import com.autoshipper.vendor.domain.VendorNotActivatedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class VendorExceptionHandler {

    private val logger = LoggerFactory.getLogger(VendorExceptionHandler::class.java)

    @ExceptionHandler(VendorNotActivatedException::class)
    fun handleVendorNotActivated(
        ex: VendorNotActivatedException
    ): ResponseEntity<Map<String, String?>> {
        logger.warn("Vendor activation rejected: {}", ex.message)
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            mapOf(
                "error" to "VENDOR_NOT_ACTIVATED",
                "message" to ex.message
            )
        )
    }
}
