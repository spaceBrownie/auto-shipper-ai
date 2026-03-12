package com.autoshipper.capital.persistence

import com.autoshipper.capital.domain.ReserveAccount
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ReserveAccountRepository : JpaRepository<ReserveAccount, UUID>
