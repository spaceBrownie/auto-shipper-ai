package com.autoshipper.vendor.handler.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterVendorRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:NotBlank @field:Email @field:Size(max = 255) val contactEmail: String
)
