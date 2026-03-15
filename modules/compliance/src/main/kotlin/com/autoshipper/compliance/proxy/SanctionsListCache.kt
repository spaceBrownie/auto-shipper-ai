package com.autoshipper.compliance.proxy

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class SanctionsListCache {

    private val logger = LoggerFactory.getLogger(SanctionsListCache::class.java)

    private val sanctionedVendorIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun getSanctionedVendorIds(): Set<String> = sanctionedVendorIds.toSet()

    fun addSanctionedVendor(vendorId: String) {
        sanctionedVendorIds.add(vendorId)
    }

    fun clearAndLoad(vendorIds: Collection<String>) {
        sanctionedVendorIds.clear()
        sanctionedVendorIds.addAll(vendorIds)
        logger.info("Sanctions list refreshed with {} entries", vendorIds.size)
    }

    @Scheduled(cron = "0 0 0 * * *")
    fun refreshAtMidnight() {
        logger.info("Refreshing sanctions list at midnight (no-op until external source configured)")
        // Future: load from external sanctions list file or API
    }
}
