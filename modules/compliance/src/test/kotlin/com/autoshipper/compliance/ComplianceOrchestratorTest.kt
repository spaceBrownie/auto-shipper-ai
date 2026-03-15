package com.autoshipper.compliance

import com.autoshipper.compliance.config.ComplianceConfig
import com.autoshipper.compliance.domain.ComplianceAuditRecord
import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.compliance.domain.service.*
import com.autoshipper.compliance.persistence.ComplianceAuditRepository
import com.autoshipper.compliance.proxy.SanctionsListCache
import com.autoshipper.shared.events.ComplianceCleared
import com.autoshipper.shared.events.ComplianceFailed
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import kotlinx.coroutines.runBlocking
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

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComplianceOrchestratorTest {

    @Mock
    private lateinit var auditRepository: ComplianceAuditRepository

    @Mock
    private lateinit var eventPublisher: ApplicationEventPublisher

    private val config = ComplianceConfig(autoCheckEnabled = true)
    private val skuId = SkuId.new()
    private val vendorId = VendorId.new()
    private val productName = "Test Product"
    private val productDescription = "A simple product"
    private val category = "home_goods"

    // Use real check services — they are pure functions, no mocking needed
    private val ipCheckService = IpCheckService()
    private val claimsCheckService = ClaimsCheckService()
    private val processorCheckService = ProcessorCheckService()
    private val sanctionsListCache = SanctionsListCache()
    private val sourcingCheckService = SourcingCheckService(sanctionsListCache)

    private lateinit var orchestrator: ComplianceOrchestrator

    @BeforeEach
    fun setUp() {
        orchestrator = ComplianceOrchestrator(
            ipCheckService = ipCheckService,
            claimsCheckService = claimsCheckService,
            processorCheckService = processorCheckService,
            sourcingCheckService = sourcingCheckService,
            auditRepository = auditRepository,
            eventPublisher = eventPublisher,
            config = config
        )
    }

    @Test
    fun `all checks pass emits ComplianceCleared`() {
        orchestrator.runChecks(skuId, productName, productDescription, category, vendorId)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertTrue(captor.value is ComplianceCleared)
        assertEquals(skuId, (captor.value as ComplianceCleared).skuId)
    }

    @Test
    fun `IP check failure emits ComplianceFailed`() {
        orchestrator.runChecks(skuId, "Nike Running Shoes", productDescription, category, vendorId)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertTrue(captor.value is ComplianceFailed)
        assertEquals("IP_INFRINGEMENT", (captor.value as ComplianceFailed).reason)
    }

    @Test
    fun `claims check failure emits ComplianceFailed`() {
        orchestrator.runChecks(skuId, productName, "This product cures all diseases", category, vendorId)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertTrue(captor.value is ComplianceFailed)
        assertEquals("MISLEADING_CLAIMS", (captor.value as ComplianceFailed).reason)
    }

    @Test
    fun `processor check failure emits ComplianceFailed`() {
        orchestrator.runChecks(skuId, productName, productDescription, "gambling", vendorId)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertTrue(captor.value is ComplianceFailed)
        assertEquals("PROCESSOR_PROHIBITED", (captor.value as ComplianceFailed).reason)
    }

    @Test
    fun `sourcing check failure emits ComplianceFailed`() {
        sanctionsListCache.addSanctionedVendor(vendorId.value.toString())

        orchestrator.runChecks(skuId, productName, productDescription, category, vendorId)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertTrue(captor.value is ComplianceFailed)
        assertEquals("GRAY_MARKET_SOURCE", (captor.value as ComplianceFailed).reason)
    }

    @Test
    fun `audit records are written for all checks`() {
        orchestrator.runChecks(skuId, productName, productDescription, category, vendorId)

        verify(auditRepository, times(4)).save(any<ComplianceAuditRecord>())
    }

    @Test
    fun `audit records are written even when a check fails`() {
        orchestrator.runChecks(skuId, "Nike Shoes", productDescription, category, vendorId)

        verify(auditRepository, times(4)).save(any<ComplianceAuditRecord>())
    }

    @Test
    fun `checks run concurrently via coroutineScope`() = runBlocking {
        val results = orchestrator.runChecksConcurrently(
            skuId, productName, productDescription, category, vendorId
        )

        assertEquals(4, results.size)
        assertEquals("IP_CHECK", results[0].first)
        assertEquals("CLAIMS_CHECK", results[1].first)
        assertEquals("PROCESSOR_CHECK", results[2].first)
        assertEquals("SOURCING_CHECK", results[3].first)

        // All should be cleared with clean inputs
        results.forEach { (_, result) ->
            assertTrue(result is ComplianceCheckResult.Cleared)
        }
    }

    @Test
    fun `concurrent execution returns failures correctly`() = runBlocking {
        val results = orchestrator.runChecksConcurrently(
            skuId, "Nike Product", "This is a miracle cure", "gambling", vendorId
        )

        assertEquals(4, results.size)
        val failedChecks = results.filter { it.second is ComplianceCheckResult.Failed }
        assertEquals(3, failedChecks.size) // IP, claims, processor all fail
    }
}
