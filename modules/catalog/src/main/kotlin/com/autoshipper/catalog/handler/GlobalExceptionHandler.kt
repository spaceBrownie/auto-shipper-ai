package com.autoshipper.catalog.handler

import com.autoshipper.catalog.domain.InvalidSkuTransitionException
import com.autoshipper.catalog.domain.ProviderUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(InvalidSkuTransitionException::class)
    fun handleInvalidTransition(
        ex: InvalidSkuTransitionException
    ): ResponseEntity<Map<String, String?>> {
        logger.warn("Invalid SKU state transition: {}", ex.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf(
                "error" to "INVALID_STATE_TRANSITION",
                "message" to ex.message
            )
        )
    }

    @ExceptionHandler(ProviderUnavailableException::class)
    fun handleProviderUnavailable(
        ex: ProviderUnavailableException
    ): ResponseEntity<Map<String, String?>> {
        logger.error("External provider unavailable", ex)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
            mapOf(
                "error" to "PROVIDER_UNAVAILABLE",
                "message" to ex.message
            )
        )
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(
        ex: NoSuchElementException
    ): ResponseEntity<Map<String, String?>> {
        logger.warn("Resource not found: {}", ex.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            mapOf(
                "error" to "NOT_FOUND",
                "message" to ex.message
            )
        )
    }
}
