package com.autoshipper.portfolio.proxy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

class AmazonAdapterDeactivationTest {

    @Test
    fun `AmazonCreatorsApiAdapter has ConditionalOnProperty annotation`() {
        val annotation = AmazonCreatorsApiAdapter::class.java
            .getAnnotation(ConditionalOnProperty::class.java)
        assertNotNull(annotation, "AmazonCreatorsApiAdapter should have @ConditionalOnProperty")
        assertTrue(
            annotation!!.name.contains("amazon-creators.enabled"),
            "Annotation should reference 'amazon-creators.enabled'"
        )
        assertTrue(
            annotation.havingValue == "true",
            "Annotation should require havingValue='true'"
        )
        assertTrue(
            !annotation.matchIfMissing,
            "matchIfMissing should be false"
        )
    }

    @Test
    fun `StubAmazonCreatorsApiProvider has ConditionalOnProperty annotation`() {
        val annotation = StubAmazonCreatorsApiProvider::class.java
            .getAnnotation(ConditionalOnProperty::class.java)
        assertNotNull(annotation, "StubAmazonCreatorsApiProvider should have @ConditionalOnProperty")
        assertTrue(
            annotation!!.name.contains("amazon-creators.enabled"),
            "Annotation should reference 'amazon-creators.enabled'"
        )
        assertTrue(
            annotation.havingValue == "true",
            "Annotation should require havingValue='true'"
        )
        assertTrue(
            !annotation.matchIfMissing,
            "matchIfMissing should be false"
        )
    }
}
