package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.config.ComplianceConfig
import com.autoshipper.compliance.domain.ComplianceAuditRecord
import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceCheckType
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.compliance.persistence.ComplianceAuditRepository
import com.autoshipper.shared.events.ComplianceCleared
import com.autoshipper.shared.events.ComplianceFailed
import com.autoshipper.shared.events.SkuReadyForComplianceCheck
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComplianceOrchestratorTest {

    @Mock private lateinit var auditRepository: ComplianceAuditRepository
    @Mock private lateinit var eventPublisher: ApplicationEventPublisher
    @Mock private lateinit var entityManager: EntityManager
    @Mock private lateinit var nativeQuery: Query

    private val skuId = SkuId(UUID.randomUUID())
    private val vendorId = VendorId(UUID.randomUUID())

    private lateinit var orchestrator: ComplianceOrchestrator

    @BeforeEach
    fun setUp() {
        whenever(auditRepository.save(any<ComplianceAuditRecord>())).thenAnswer { it.arguments[0] }
        whenever(entityManager.createNativeQuery(any<String>())).thenReturn(nativeQuery)
        whenever(nativeQuery.setParameter(any<String>(), any())).thenReturn(nativeQuery)
        // Default: vendor is ACTIVE
        whenever(nativeQuery.resultList).thenReturn(listOf("ACTIVE"))

        val config = ComplianceConfig(autoCheckEnabled = true)
        orchestrator = ComplianceOrchestrator(
            IpCheckService(),
            ClaimsCheckService(),
            ProcessorCheckService(),
            SourcingCheckService(entityManager),
            auditRepository,
            eventPublisher,
            config
        )
    }

    @Test
    fun `all checks pass emits ComplianceCleared`() {
        val results = orchestrator.runChecks(skuId, "Bamboo Board", "A durable cutting board", "Kitchen", vendorId)

        assertEquals(4, results.size)
        assertTrue(results.all { it is ComplianceCheckResult.Cleared })

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertInstanceOf(ComplianceCleared::class.java, captor.value)
        assertEquals(skuId, (captor.value as ComplianceCleared).skuId)
    }

    @Test
    fun `ip check failure emits ComplianceFailed`() {
        val results = orchestrator.runChecks(skuId, "Nike Running Shoes", "Great shoes", "Footwear", vendorId)

        val failures = results.filterIsInstance<ComplianceCheckResult.Failed>()
        assertTrue(failures.any { it.reason == ComplianceFailureReason.IP_INFRINGEMENT })

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertInstanceOf(ComplianceFailed::class.java, captor.value)
        assertEquals("IP_INFRINGEMENT", (captor.value as ComplianceFailed).reason)
    }

    @Test
    fun `claims check failure emits ComplianceFailed`() {
        val results = orchestrator.runChecks(skuId, "Health Supplement", "This miracle product", "Health", vendorId)

        val failures = results.filterIsInstance<ComplianceCheckResult.Failed>()
        assertTrue(failures.any { it.reason == ComplianceFailureReason.MISLEADING_CLAIMS })

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertInstanceOf(ComplianceFailed::class.java, captor.value)
    }

    @Test
    fun `processor check failure for prohibited category`() {
        val results = orchestrator.runChecks(skuId, "Good Product", "A product", "Firearms & Weapons", vendorId)

        val failures = results.filterIsInstance<ComplianceCheckResult.Failed>()
        assertTrue(failures.any { it.reason == ComplianceFailureReason.PROCESSOR_PROHIBITED })
    }

    @Test
    fun `sourcing check fails for inactive vendor`() {
        whenever(nativeQuery.resultList).thenReturn(listOf("SUSPENDED"))

        val results = orchestrator.runChecks(skuId, "Good Product", "A product", "Kitchen", vendorId)

        val failures = results.filterIsInstance<ComplianceCheckResult.Failed>()
        assertTrue(failures.any { it.reason == ComplianceFailureReason.GRAY_MARKET_SOURCE })
    }

    @Test
    fun `sourcing check fails for unknown vendor`() {
        whenever(nativeQuery.resultList).thenReturn(emptyList<String>())

        val results = orchestrator.runChecks(skuId, "Good Product", "A product", "Kitchen", vendorId)

        val failures = results.filterIsInstance<ComplianceCheckResult.Failed>()
        assertTrue(failures.any { it.reason == ComplianceFailureReason.GRAY_MARKET_SOURCE })
    }

    @Test
    fun `all results are persisted to audit table`() {
        orchestrator.runChecks(skuId, "Product", "Description", "Category", vendorId)

        verify(auditRepository, times(4)).save(any<ComplianceAuditRecord>())
    }

    @Test
    fun `auto check disabled skips compliance check on event`() {
        val disabledConfig = ComplianceConfig(autoCheckEnabled = false)
        val disabledOrchestrator = ComplianceOrchestrator(
            IpCheckService(), ClaimsCheckService(), ProcessorCheckService(),
            SourcingCheckService(entityManager), auditRepository, eventPublisher, disabledConfig
        )

        disabledOrchestrator.onSkuReadyForComplianceCheck(
            SkuReadyForComplianceCheck(skuId, "Product", "Desc", "Cat", vendorId)
        )

        verify(eventPublisher, never()).publishEvent(any())
        verify(auditRepository, never()).save(any<ComplianceAuditRecord>())
    }

    @Test
    fun `multiple failures emits single ComplianceFailed event`() {
        // Nike (IP) + miracle (claims) = two failures
        val results = orchestrator.runChecks(skuId, "Nike Miracle Shoes", "This miracle product", "Kitchen", vendorId)

        val failures = results.filterIsInstance<ComplianceCheckResult.Failed>()
        assertTrue(failures.size >= 2)

        // Should still emit exactly one event
        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher, times(1)).publishEvent(captor.capture())
        assertInstanceOf(ComplianceFailed::class.java, captor.value)
    }
}
