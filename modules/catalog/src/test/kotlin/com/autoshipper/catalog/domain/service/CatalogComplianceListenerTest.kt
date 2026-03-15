package com.autoshipper.catalog.domain.service

import com.autoshipper.shared.events.ComplianceCleared
import com.autoshipper.shared.events.ComplianceFailed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

class CatalogComplianceListenerTest {

    @Test
    fun `onComplianceCleared has AFTER_COMMIT and REQUIRES_NEW annotations`() {
        val method = CatalogComplianceListener::class.java.getMethod("onComplianceCleared", ComplianceCleared::class.java)

        val telAnnotation = method.getAnnotation(TransactionalEventListener::class.java)
        assertNotNull(telAnnotation, "Missing @TransactionalEventListener")
        assertEquals(TransactionPhase.AFTER_COMMIT, telAnnotation.phase)

        val txAnnotation = method.getAnnotation(Transactional::class.java)
        assertNotNull(txAnnotation, "Missing @Transactional")
        assertEquals(Propagation.REQUIRES_NEW, txAnnotation.propagation)
    }

    @Test
    fun `onComplianceFailed has AFTER_COMMIT and REQUIRES_NEW annotations`() {
        val method = CatalogComplianceListener::class.java.getMethod("onComplianceFailed", ComplianceFailed::class.java)

        val telAnnotation = method.getAnnotation(TransactionalEventListener::class.java)
        assertNotNull(telAnnotation, "Missing @TransactionalEventListener")
        assertEquals(TransactionPhase.AFTER_COMMIT, telAnnotation.phase)

        val txAnnotation = method.getAnnotation(Transactional::class.java)
        assertNotNull(txAnnotation, "Missing @Transactional")
        assertEquals(Propagation.REQUIRES_NEW, txAnnotation.propagation)
    }
}
