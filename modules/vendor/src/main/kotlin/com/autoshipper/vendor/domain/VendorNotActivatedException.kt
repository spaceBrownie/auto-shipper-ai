package com.autoshipper.vendor.domain

class VendorNotActivatedException(
    missingItems: List<String>
) : RuntimeException(
    "Vendor cannot be activated. Missing checklist items: ${missingItems.joinToString(", ")}"
)
