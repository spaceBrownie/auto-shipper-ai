package com.autoshipper.catalog.handler

import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.domain.service.SkuService
import com.autoshipper.shared.identity.SkuId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Gated dev-only admin endpoint used for the Shopify dev-store gate-zero walkthrough (FR-030 / RAT-53).
 *
 * Double-gated:
 *  1. `@ConditionalOnProperty("autoshipper.admin.dev-listing-enabled")` — when false (default),
 *     this bean is not instantiated and the endpoint returns 404 from Spring's default handler.
 *  2. HTTP Basic auth against the shared secret `autoshipper.admin.dev-token`. Defense in depth:
 *     if the token is blank (misconfiguration), ALL requests are rejected with 401.
 *
 * Token comparison uses `MessageDigest.isEqual` for constant-time comparison.
 *
 * Repeat-call semantics: if the SKU is already in `LISTED` state, the endpoint returns 202 Accepted
 * with the same body as a fresh transition (idempotent). Caller gets the same observable state
 * regardless of whether they are first or retrying (test-spec T-40).
 *
 * This controller MUST NOT exist in production — operators flip the gate for dev-store testing only.
 */
@RestController
@ConditionalOnProperty(name = ["autoshipper.admin.dev-listing-enabled"], havingValue = "true")
@RequestMapping("/admin/dev")
class DevAdminController(
    private val skuService: SkuService,
    @Value("\${autoshipper.admin.dev-token:}") private val devToken: String,
) {
    private val logger = LoggerFactory.getLogger(DevAdminController::class.java)

    @PostMapping("/sku/{id}/list")
    fun transitionToListed(
        @PathVariable id: UUID,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authHeader: String?,
    ): ResponseEntity<Map<String, Any>> {
        if (!authenticated(authHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "unauthorized"))
        }

        val skuId = SkuId(id)
        val currentSku = skuService.findById(skuId)

        if (currentSku.currentState() is SkuState.Listed) {
            logger.info("DevAdminController: sku={} already LISTED; returning 202 (idempotent)", id)
            return ResponseEntity.accepted().body(mapOf("skuId" to id.toString(), "state" to "LISTED"))
        }

        logger.info("DevAdminController: transitioning sku={} to LISTED", id)
        skuService.transition(skuId, SkuState.Listed)
        return ResponseEntity.accepted().body(mapOf("skuId" to id.toString(), "state" to "LISTED"))
    }

    private fun authenticated(authHeader: String?): Boolean {
        if (devToken.isBlank()) return false // defense in depth — misconfigured gate fails closed
        if (authHeader == null || !authHeader.startsWith("Basic ")) return false

        val decoded = try {
            String(
                Base64.getDecoder().decode(authHeader.removePrefix("Basic ").trim()),
                StandardCharsets.UTF_8,
            )
        } catch (_: IllegalArgumentException) {
            return false
        }

        val presentedToken = decoded.substringAfter(":", missingDelimiterValue = "")
        if (presentedToken.isEmpty()) return false

        return MessageDigest.isEqual(
            presentedToken.toByteArray(StandardCharsets.UTF_8),
            devToken.toByteArray(StandardCharsets.UTF_8),
        )
    }
}
