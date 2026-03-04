package com.autoshipper.pricing.domain.service

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

class PricingDecisionListenerTest {

    @Test
    fun `onPricingDecision uses TransactionalEventListener with AFTER_COMMIT phase`() {
        val method = PricingDecisionListener::class.java.getMethod(
            "onPricingDecision",
            com.autoshipper.shared.events.PricingDecision::class.java
        )
        val annotation = method.getAnnotation(TransactionalEventListener::class.java)

        assertNotNull(annotation, "onPricingDecision must be annotated with @TransactionalEventListener")
        assert(annotation.phase == TransactionPhase.AFTER_COMMIT) {
            "Expected AFTER_COMMIT phase but got ${annotation.phase}. " +
                "Side effects (Shopify sync, SKU state transitions) must not run inside the pricing transaction."
        }
    }
}
