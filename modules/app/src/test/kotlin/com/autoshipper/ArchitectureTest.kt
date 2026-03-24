package com.autoshipper

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Persistable
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Structural enforcement of postmortem prevention rules.
 *
 * These rules encode hard-won lessons from PM-001 through PM-011 as compile-time
 * checks. Each rule references the postmortem(s) that motivated it.
 */
class ArchitectureTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.autoshipper")

    private val testClasses = ClassFileImporter()
        .withImportOption(ImportOption.OnlyIncludeTests())
        .importPackages("com.autoshipper")

    /**
     * Rule 1: @TransactionalEventListener(phase = AFTER_COMMIT) must pair with
     * @Transactional(propagation = REQUIRES_NEW).
     *
     * Without REQUIRES_NEW, writes in AFTER_COMMIT listeners are silently discarded
     * because Spring's TransactionSynchronizationManager retains stale state from the
     * just-committed transaction.
     *
     * Violations found by: PM-001, PM-005, PM-006, PM-007
     */
    @Test
    fun `AFTER_COMMIT listeners must use REQUIRES_NEW transaction`() {
        val rule = ArchRuleDefinition.methods()
            .that().areAnnotatedWith(TransactionalEventListener::class.java)
            .should(haveRequiresNewWhenAfterCommit())
            .because(
                "PM-001/PM-005: @TransactionalEventListener(AFTER_COMMIT) handlers that write " +
                "to the database must use @Transactional(propagation = REQUIRES_NEW). Without it, " +
                "writes are silently discarded."
            )

        rule.check(productionClasses)
    }

    /**
     * Rule 2: No @Testcontainers annotation in test code.
     *
     * All integration tests use the running PostgreSQL instance configured in
     * application-test.yml. Testcontainers dependencies have been removed (RAT-18).
     * Using @Testcontainers would cause compilation errors and, historically,
     * silently skipped tests due to Docker API incompatibilities (PM-004, PM-006).
     *
     * Violations found by: PM-004, PM-006, RAT-18
     */
    @Test
    fun `test classes must not use Testcontainers annotation`() {
        val rule = ArchRuleDefinition.classes()
            .should(notUseTestcontainers())
            .because(
                "RAT-18: All integration tests use the running Postgres from application-test.yml. " +
                "@Testcontainers has been removed — do not reintroduce it."
            )

        rule.check(testClasses)
    }

    /**
     * Rule 3: @Entity classes with assigned (non-generated) @Id must implement Persistable.
     *
     * Spring Data JPA's SimpleJpaRepository.save() calls entityInformation.isNew(entity)
     * to decide between persist() and merge(). For non-Persistable entities, isNew() checks
     * if the @Id field is null. Assigned IDs (UUID.randomUUID(), external strings) are never
     * null, so isNew() returns false and save() calls merge() — which issues a SELECT before
     * INSERT. In REQUIRES_NEW transactions (like WebhookEventPersister), merge()'s deferred
     * flush means the DataIntegrityViolationException for duplicates is never thrown within
     * the catch block, breaking deduplication.
     *
     * Implementing Persistable<T> with a @Transient isNew flag fixes this: the entity
     * reports isNew=true on construction, so save() calls persist() directly.
     *
     * Violations found by: PM-001 (deferred flush pattern)
     */
    @Test
    fun `entities with assigned IDs must implement Persistable`() {
        val rule = ArchRuleDefinition.classes()
            .that().areAnnotatedWith(jakarta.persistence.Entity::class.java)
            .should(implementPersistableIfAssignedId())
            .because(
                "PM-001: @Entity classes whose @Id field lacks @GeneratedValue must implement " +
                "Persistable<T>. Without it, Spring Data calls merge() instead of persist() " +
                "for assigned IDs, causing unnecessary SELECT-before-INSERT and breaking " +
                "deduplication in REQUIRES_NEW transactions."
            )

        rule.check(productionClasses)
    }

    // --- Custom conditions ---

    private fun haveRequiresNewWhenAfterCommit(): ArchCondition<JavaMethod> {
        return object : ArchCondition<JavaMethod>(
            "have @Transactional(propagation = REQUIRES_NEW) when phase is AFTER_COMMIT"
        ) {
            override fun check(method: JavaMethod, events: ConditionEvents) {
                val listenerAnnotation = method.annotations
                    .firstOrNull { it.rawType.name == TransactionalEventListener::class.java.name }
                    ?: return

                // Check if phase is AFTER_COMMIT
                val phase = try {
                    listenerAnnotation.get("phase").orElse(null)
                } catch (e: Exception) {
                    null
                }

                // Default phase for @TransactionalEventListener is AFTER_COMMIT
                val isAfterCommit = phase == null ||
                    phase == TransactionPhase.AFTER_COMMIT ||
                    phase.toString().contains("AFTER_COMMIT")

                if (!isAfterCommit) return

                // Check for @Transactional(propagation = REQUIRES_NEW)
                val txAnnotation = method.annotations
                    .firstOrNull { it.rawType.name == Transactional::class.java.name }

                if (txAnnotation == null) {
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            "${method.fullName} is annotated with @TransactionalEventListener(AFTER_COMMIT) " +
                            "but is missing @Transactional(propagation = REQUIRES_NEW)"
                        )
                    )
                    return
                }

                val propagation = try {
                    txAnnotation.get("propagation").orElse(null)
                } catch (e: Exception) {
                    null
                }

                val isRequiresNew = propagation == Propagation.REQUIRES_NEW ||
                    propagation?.toString()?.contains("REQUIRES_NEW") == true

                if (!isRequiresNew) {
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            "${method.fullName} is annotated with @TransactionalEventListener(AFTER_COMMIT) " +
                            "but @Transactional does not use propagation = REQUIRES_NEW (found: $propagation)"
                        )
                    )
                }
            }
        }
    }

    private fun implementPersistableIfAssignedId(): ArchCondition<JavaClass> {
        return object : ArchCondition<JavaClass>(
            "implement Persistable if @Id field has no @GeneratedValue"
        ) {
            override fun check(javaClass: JavaClass, events: ConditionEvents) {
                // Find the @Id field
                val idField = javaClass.allFields.firstOrNull { field ->
                    field.annotations.any { it.rawType.name == "jakarta.persistence.Id" }
                } ?: return // No @Id field — not our concern

                // Check if it has @GeneratedValue
                val hasGeneratedValue = idField.annotations.any {
                    it.rawType.name == "jakarta.persistence.GeneratedValue"
                }

                if (hasGeneratedValue) return // Database-generated ID — merge/persist distinction is moot

                // Assigned ID — must implement Persistable
                if (!javaClass.isAssignableTo(Persistable::class.java)) {
                    events.add(
                        SimpleConditionEvent.violated(
                            javaClass,
                            "${javaClass.name} has an assigned @Id (no @GeneratedValue) " +
                            "but does not implement Persistable<T>. This causes Spring Data " +
                            "to call merge() instead of persist(), leading to unnecessary " +
                            "SELECT-before-INSERT and potential deferred flush bugs."
                        )
                    )
                }
            }
        }
    }

    private fun notUseTestcontainers(): ArchCondition<JavaClass> {
        return object : ArchCondition<JavaClass>(
            "not use @Testcontainers annotation"
        ) {
            override fun check(
                javaClass: JavaClass,
                events: ConditionEvents
            ) {
                val hasTestcontainers = javaClass.annotations
                    .any { it.rawType.name == "org.testcontainers.junit.jupiter.Testcontainers" }

                if (hasTestcontainers) {
                    events.add(
                        SimpleConditionEvent.violated(
                            javaClass,
                            "${javaClass.name} uses @Testcontainers. " +
                            "Use the running Postgres from application-test.yml instead (RAT-18)."
                        )
                    )
                }
            }
        }
    }
}
