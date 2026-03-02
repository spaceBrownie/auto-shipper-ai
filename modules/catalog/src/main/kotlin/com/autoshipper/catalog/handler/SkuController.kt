package com.autoshipper.catalog.handler

import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.domain.service.SkuService
import com.autoshipper.catalog.handler.dto.CreateSkuRequest
import com.autoshipper.catalog.handler.dto.SkuResponse
import com.autoshipper.catalog.handler.dto.TransitionSkuRequest
import com.autoshipper.shared.identity.SkuId
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/skus")
class SkuController(private val skuService: SkuService) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreateSkuRequest): ResponseEntity<SkuResponse> {
        val sku = skuService.create(request.name, request.category)
        return ResponseEntity.status(HttpStatus.CREATED).body(SkuResponse.from(sku))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<SkuResponse> {
        val sku = skuService.findById(SkuId.of(id))
        return ResponseEntity.ok(SkuResponse.from(sku))
    }

    @GetMapping
    fun list(@RequestParam(required = false) state: String?): ResponseEntity<List<SkuResponse>> {
        val skus = if (state != null) {
            val skuState = SkuState.fromDiscriminator(state.uppercase())
            skuService.findByState(skuState)
        } else {
            skuService.findAll()
        }
        return ResponseEntity.ok(skus.map { SkuResponse.from(it) })
    }

    @PostMapping("/{id}/state")
    fun transition(
        @PathVariable id: String,
        @Valid @RequestBody request: TransitionSkuRequest
    ): ResponseEntity<SkuResponse> {
        val targetState = SkuState.fromDiscriminator(request.state.uppercase())
        val sku = skuService.transition(SkuId.of(id), targetState)
        return ResponseEntity.ok(SkuResponse.from(sku))
    }
}
