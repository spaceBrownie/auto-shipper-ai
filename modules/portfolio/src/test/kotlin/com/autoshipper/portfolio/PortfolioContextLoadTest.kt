package com.autoshipper.portfolio

import com.autoshipper.portfolio.domain.service.MarginSignalProvider
import com.autoshipper.portfolio.persistence.CandidateRejectionRepository
import com.autoshipper.portfolio.persistence.DemandCandidateRepository
import com.autoshipper.portfolio.persistence.DemandScanRunRepository
import com.autoshipper.portfolio.persistence.DiscoveryBlacklistRepository
import com.autoshipper.portfolio.persistence.ExperimentRepository
import com.autoshipper.portfolio.persistence.KillRecommendationRepository
import com.autoshipper.portfolio.persistence.ScalingFlagRepository
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles

/**
 * Smoke test that verifies the portfolio module's Spring context loads successfully.
 *
 * With JPA/Flyway/DataSource auto-configuration excluded and the "local" profile active,
 * only stub adapters and domain services load. JPA repositories are provided as mocks
 * since there is no database in this test. The JpaMarginSignalProvider (which requires
 * an EntityManager) is replaced by a mock of its interface.
 */
@SpringBootTest(classes = [TestPortfolioApplication::class])
@ActiveProfiles("test", "local")
class PortfolioContextLoadTest {

    @MockBean
    lateinit var experimentRepository: ExperimentRepository

    @MockBean
    lateinit var demandCandidateRepository: DemandCandidateRepository

    @MockBean
    lateinit var demandScanRunRepository: DemandScanRunRepository

    @MockBean
    lateinit var candidateRejectionRepository: CandidateRejectionRepository

    @MockBean
    lateinit var discoveryBlacklistRepository: DiscoveryBlacklistRepository

    @MockBean
    lateinit var killRecommendationRepository: KillRecommendationRepository

    @MockBean
    lateinit var scalingFlagRepository: ScalingFlagRepository

    @MockBean
    lateinit var marginSignalProvider: MarginSignalProvider

    @Test
    fun contextLoads() {
        // Verifies all portfolio beans instantiate successfully
    }
}
