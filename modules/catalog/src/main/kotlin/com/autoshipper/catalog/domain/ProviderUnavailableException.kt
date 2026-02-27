package com.autoshipper.catalog.domain

class ProviderUnavailableException(
    provider: String,
    cause: Throwable
) : RuntimeException("External provider '$provider' is unavailable: ${cause.message}", cause)
