package com.autoshipper.portfolio.persistence

import com.autoshipper.portfolio.domain.DiscoveryBlacklistEntry
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DiscoveryBlacklistRepository : JpaRepository<DiscoveryBlacklistEntry, UUID> {

    fun existsByKeyword(keyword: String): Boolean
}
