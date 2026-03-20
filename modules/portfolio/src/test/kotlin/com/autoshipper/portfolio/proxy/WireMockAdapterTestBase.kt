package com.autoshipper.portfolio.proxy

import com.autoshipper.portfolio.domain.RawCandidate
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.assertj.core.api.Assertions.assertThat

/**
 * Shared base for WireMock-based adapter integration tests.
 * Provides fixture loading and common RawCandidate assertions.
 */
abstract class WireMockAdapterTestBase {

    /**
     * Loads a fixture file from the classpath under wiremock/.
     * @param path relative to wiremock/ (e.g. "cj/product-list-success.json")
     */
    protected fun loadFixture(path: String): String {
        return this::class.java.classLoader
            .getResource("wiremock/$path")
            ?.readText()
            ?: throw IllegalArgumentException("Fixture not found: wiremock/$path")
    }

    /**
     * Asserts that all candidates have non-blank productName, correct sourceType,
     * and non-null demandSignals map.
     */
    protected fun assertValidRawCandidates(
        candidates: List<RawCandidate>,
        expectedSource: String,
        expectedMinCount: Int = 1
    ) {
        assertThat(candidates).hasSizeGreaterThanOrEqualTo(expectedMinCount)
        candidates.forEach { candidate ->
            assertThat(candidate.productName).isNotBlank()
            assertThat(candidate.sourceType).isEqualTo(expectedSource)
            assertThat(candidate.demandSignals).isNotNull()
            assertThat(candidate.demandSignals).isNotEmpty()
        }
    }

    /**
     * Asserts that a specific demand signal key exists and has a non-blank value.
     */
    protected fun assertSignalPresent(candidate: RawCandidate, key: String) {
        assertThat(candidate.demandSignals).containsKey(key)
        assertThat(candidate.demandSignals[key]).isNotBlank()
    }
}
