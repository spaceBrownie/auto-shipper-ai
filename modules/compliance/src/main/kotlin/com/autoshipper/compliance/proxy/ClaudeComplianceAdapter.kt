package com.autoshipper.compliance.proxy

import com.autoshipper.compliance.config.ComplianceConfig
import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.shared.identity.SkuId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Placeholder adapter for LLM-backed compliance analysis.
 * When compliance.llm-enabled=true, this will call the Claude API
 * for nuanced IP and claims analysis. Currently returns Cleared
 * as a stub.
 */
@Component
class ClaudeComplianceAdapter(
    private val config: ComplianceConfig
) {
    private val logger = LoggerFactory.getLogger(ClaudeComplianceAdapter::class.java)

    fun analyzeIp(skuId: SkuId, productName: String): ComplianceCheckResult {
        if (!config.llmEnabled) {
            logger.debug("LLM compliance disabled, skipping IP analysis for SKU {}", skuId)
            return ComplianceCheckResult.Cleared(skuId = skuId)
        }

        logger.info("LLM IP analysis requested for SKU {} — stub returning Cleared", skuId)
        // Future: call Claude API for deeper IP analysis
        return ComplianceCheckResult.Cleared(skuId = skuId)
    }

    fun analyzeClaims(skuId: SkuId, description: String): ComplianceCheckResult {
        if (!config.llmEnabled) {
            logger.debug("LLM compliance disabled, skipping claims analysis for SKU {}", skuId)
            return ComplianceCheckResult.Cleared(skuId = skuId)
        }

        logger.info("LLM claims analysis requested for SKU {} — stub returning Cleared", skuId)
        // Future: call Claude API for deeper claims analysis
        return ComplianceCheckResult.Cleared(skuId = skuId)
    }
}
