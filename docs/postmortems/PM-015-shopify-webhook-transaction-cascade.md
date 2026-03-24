# PM-015: Shopify Webhook Transaction & Security Cascade

**Date:** 2026-03-21
**Severity:** Critical
**Status:** Resolved
**Author:** Auto-generated from session

## Summary

During implementation of FR-023 (Shopify order webhook listener, RAT-26), 8 bugs were discovered across 5 rounds of PR review. The bugs formed a cascade — fixes for earlier bugs introduced new bugs that required their own fixes. The most severe issues involved Spring transaction semantics: deferred flush causing exceptions to escape try-catch blocks, Hibernate session poisoning from caught exceptions, and synchronous `@TransactionalEventListener` blocking the HTTP response past Shopify's 5-second timeout. The incident exposed a structural enforcement gap: PM-001 had documented the deferred-flush pattern but its prevention only covered one manifestation (missing `REQUIRES_NEW` on `AFTER_COMMIT` listeners), leaving the merge-vs-persist variant unguarded for 18 days until it resurfaced here.

## Timeline

| Time | Event |
|------|-------|
| Session start | FR-023 implementation complete: 19 source files, 10 test files, all tests passing |
| E2E validation | Sub-agent validates all files, schemas, constraints — finds 1 minor issue (empty-secret HMAC bypass) |
| PR #32 created | Initial PR with 50 tests, 445 total passing |
| Review round 1 | Unblocked bot finds Bug #1: `Currency.valueOf()` crashes event listener on unsupported currency |
| Fix #1 applied | Try-catch around `Currency.valueOf()`, test added, pushed |
| Review round 2 | Unblocked bot finds Bug #2 (malformed timestamp) and Bug #3 (single-transaction line items) |
| Fix #2-#3 applied | `DateTimeParseException` catch; extracted `LineItemOrderCreator` with per-line-item `REQUIRES_NEW` |
| Review round 3 | Unblocked bot finds Bug #4: concurrent duplicate webhook PK race condition |
| Fix #4 applied | `DataIntegrityViolationException` catch around `save()` — **this fix itself had a bug** |
| PR #32 closed | Reopened as PR #33 with clean commit history |
| Review round 4 | Unblocked bot finds Bug #5 (synchronous AFTER_COMMIT blocks response) and Bug #6 (caught exception poisons Hibernate session) |
| Fix #5-#6 applied | `@Async` + `@EnableAsync`; extracted `WebhookEventPersister` with `REQUIRES_NEW` |
| Review round 5 | Unblocked bot finds Bug #7: `save()` defers INSERT past try-catch due to assigned-ID merge pattern (PM-001 class bug) |
| Fix #7 applied | `saveAndFlush()`, `Persistable` on all 23 assigned-ID entities, ArchUnit Rule 3, CLAUDE.md constraint #16 |
| Final E2E | All 7 fixes verified, all 16 CLAUDE.md constraints compliant, build green |

## The Eight Bugs

### Bug #1: Unsupported currency crashes event listener (High)

**Symptom:** `Currency.valueOf("JPY")` throws `IllegalArgumentException` — the shared `Currency` enum only contains USD, EUR, GBP, CAD. The exception crashes the entire `@TransactionalEventListener(AFTER_COMMIT)` handler.

**Why it's severe:** The deduplication record was already committed in the controller's transaction. Shopify received HTTP 200 and won't retry. The order is permanently lost.

**File:** `ShopifyOrderProcessingService.kt:39`

```kotlin
// BEFORE: uncaught
val currency = Currency.valueOf(channelOrder.currencyCode)

// AFTER: safe
val currency = try {
    Currency.valueOf(channelOrder.currencyCode)
} catch (e: IllegalArgumentException) {
    logger.error("Unsupported currency '{}' in Shopify order {}", ...)
    return
}
```

---

### Bug #2: Malformed timestamp throws uncaught exception (High)

**Symptom:** `Instant.parse(triggeredAt)` throws `DateTimeParseException` if `X-Shopify-Triggered-At` header contains a non-ISO-8601 value. Unhandled, this becomes HTTP 500.

**Why it's severe:** Shopify counts 500 responses as delivery failures and unsubscribes the webhook after 8 consecutive failures over 4 hours.

**File:** `ShopifyWebhookController.kt:51`

```kotlin
// BEFORE: uncaught
val eventTime = Instant.parse(triggeredAt)

// AFTER: safe — skips replay protection on parse failure
val eventTime = try {
    Instant.parse(triggeredAt)
} catch (e: DateTimeParseException) {
    logger.warn("Malformed X-Shopify-Triggered-At header '{}', skipping replay protection", triggeredAt)
    null
}
```

---

### Bug #3: Single transaction for all line items (Critical)

**Symptom:** All line items processed in one `REQUIRES_NEW` transaction. If `OrderService.create()` throws for any line item (e.g., inventory check fails via `require()`), the entire transaction is marked rollback-only — rolling back orders already created for other line items.

**Why it's severe:** The dedup record is already committed. Shopify won't retry. Multiple orders permanently lost because of one failing line item.

**Root cause chain:**
1. `ShopifyOrderProcessingService.onOrderReceived()` has `@Transactional(REQUIRES_NEW)` — one transaction for the entire method
2. `OrderService.create()` has `@Transactional` (default `REQUIRED`) — joins the caller's transaction
3. `require(inventoryChecker.isAvailable(...))` inside `create()` throws `IllegalArgumentException`
4. Spring marks the joined transaction as rollback-only
5. Even with try-catch, `UnexpectedRollbackException` fires on commit
6. All previously-created orders in the same transaction are rolled back

**Fix:** Extracted `LineItemOrderCreator` as a separate `@Component` with `@Transactional(REQUIRES_NEW)` on `processLineItem()`. Each line item gets its own independent transaction. A failure in one suspends (not poisons) the outer transaction.

```kotlin
// LineItemOrderCreator.kt (new file)
@Component
class LineItemOrderCreator(...) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processLineItem(index: Int, lineItem: ChannelLineItem, ...): Boolean { ... }
}

// ShopifyOrderProcessingService.kt — orchestrator with try-catch per line item
channelOrder.lineItems.forEachIndexed { index, lineItem ->
    try {
        lineItemOrderCreator.processLineItem(index, lineItem, ...)
    } catch (e: Exception) {
        logger.error("Failed to process line item {} in Shopify order {}: {}", ...)
    }
}
```

**Why a separate `@Component`:** Spring's proxy-based AOP cannot intercept self-calls within the same class. A `@Transactional(REQUIRES_NEW)` method called from within the same object bypasses the proxy — the `REQUIRES_NEW` is silently ignored. The method must live in a different Spring bean.

---

### Bug #4: Concurrent duplicate webhook PK race condition (High)

**Symptom:** Two concurrent requests with the same `X-Shopify-Event-Id` both pass `existsByEventId()` (returns false for both under READ_COMMITTED isolation), then both attempt `INSERT`. The second hits a primary key constraint violation → `DataIntegrityViolationException` → HTTP 500.

**Initial fix (itself buggy):** Try-catch around `webhookEventRepository.save()` in the controller. This fix was superseded by Bug #6 and Bug #7.

---

### Bug #5: Synchronous AFTER_COMMIT blocks HTTP response (High)

**Symptom:** `@TransactionalEventListener(AFTER_COMMIT)` fires synchronously in the commit thread, not asynchronously. The full order processing pipeline (JSON parsing, N SKU resolution queries, N vendor resolution queries, N order creations) runs before the HTTP 200 response is sent to Shopify.

**Why it's severe:** Shopify enforces a 5-second timeout. A multi-line-item order with multiple DB roundtrips easily exceeds this. After 8 timeouts, Shopify unsubscribes the webhook.

**Misconception:** The implementation plan (AD-2) described `@TransactionalEventListener(AFTER_COMMIT)` as "async processing." It is not. It is deferred-until-commit, but still synchronous in the same thread.

**File:** `ShopifyOrderProcessingService.kt` — no `@Async`, no async event multicaster configured.

**Fix:** Added `@Async` to `onOrderReceived()` and `@EnableAsync` to `AutoShipperApplication`. Processing now runs on a separate thread; the HTTP 200 returns immediately after commit.

---

### Bug #6: DataIntegrityViolationException poisons Hibernate session (Critical)

**Symptom:** When `webhookEventRepository.save()` throws `DataIntegrityViolationException` inside a `@Transactional` method, PostgreSQL marks the transaction as aborted (`current transaction is aborted, commands ignored until end of transaction block`). The catch block returns `ResponseEntity.ok(...)`, but the `@Transactional` proxy detects the rollback-only flag and throws `UnexpectedRollbackException` → HTTP 500.

**Root cause:** After a `PersistenceException` in JPA, the persistence context is in an undefined state and must not be reused. Catching the exception in application code does not un-poison the transaction.

**Fix:** Extracted `WebhookEventPersister` as a separate `@Component` with `@Transactional(REQUIRES_NEW)`. The constraint violation is contained in its own transaction. The controller's outer transaction remains clean.

```kotlin
@Component
class WebhookEventPersister(private val webhookEventRepository: WebhookEventRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun tryPersist(event: WebhookEvent): Boolean {
        return try {
            webhookEventRepository.saveAndFlush(event)
            true
        } catch (e: DataIntegrityViolationException) {
            false
        }
    }
}
```

---

### Bug #7: Deferred flush — assigned-ID merge pattern (Critical)

**Symptom:** Even with `WebhookEventPersister` isolating the transaction, `save()` still defers the INSERT. `WebhookEvent` has an assigned `@Id val eventId: String` (no `@GeneratedValue`, doesn't implement `Persistable`). Spring Data's `SimpleJpaRepository.save()` sees a non-null ID, determines `isNew() == false`, and calls `entityManager.merge()`. Hibernate issues a SELECT, finds nothing, and defers the INSERT to flush/commit time. The `DataIntegrityViolationException` fires from the proxy's commit logic — completely outside the try-catch.

**This is the same class of bug documented in PM-001** (deferred flush), but manifesting as an uncaught exception rather than a silent no-op.

**5 Whys:**
1. **Why** did the PK violation escape the try-catch? → The INSERT was deferred to commit time.
2. **Why** was the INSERT deferred? → Hibernate called `merge()` instead of `persist()`, deferring the flush.
3. **Why** did Hibernate call `merge()`? → Spring Data's `save()` checked `isNew()`, which returned `false`.
4. **Why** did `isNew()` return `false`? → The entity's `@Id` was non-null (assigned externally as a String), and without `Persistable`, `isNew()` checks `id == null`.
5. **Why** wasn't `Persistable` required? → PM-001's prevention only covered `AFTER_COMMIT` + `REQUIRES_NEW` pairing (ArchUnit Rule 1), not the merge-vs-persist pattern.

**Fix (three layers):**
1. `saveAndFlush()` instead of `save()` — forces immediate INSERT within the try-catch
2. `WebhookEvent` implements `Persistable<String>` — Spring Data calls `persist()` directly
3. All 23 assigned-ID entities across all modules now implement `Persistable` — project-wide prevention

---

### Bug #8: Empty-secret HMAC bypass (Medium)

**Symptom:** When `SHOPIFY_WEBHOOK_SECRETS` env var is unset, Spring resolves `${SHOPIFY_WEBHOOK_SECRETS:}` to `""`. For a `List<String>` property, this binds as `listOf("")` — a single-element list containing an empty string. The HMAC filter computes HMAC-SHA256 with key `""` for every request. Anyone computing HMAC with an empty key could bypass the filter.

**Fix:** Filter out blank secrets at construction time; reject all requests when no valid secrets are configured.

```kotlin
private val effectiveSecrets = secrets.filter { it.isNotBlank() }

init {
    if (effectiveSecrets.isEmpty()) {
        logger.warn("No Shopify webhook secrets configured — all webhooks will be rejected.")
    }
}
```

## The Fix Cascade

This incident is notable because fixes introduced new bugs:

```
Bug #4 (PK race condition)
  └─ Fix: try-catch around save() in controller
       └─ Bug #6 (caught exception poisons Hibernate session)
            └─ Fix: extract WebhookEventPersister with REQUIRES_NEW
                 └─ Bug #7 (save() defers INSERT past try-catch — PM-001 class bug)
                      └─ Fix: saveAndFlush() + Persistable on all entities
                           └─ ArchUnit Rule 3 + CLAUDE.md #16 (structural closure)
```

Each fix was correct in isolation but interacted with a deeper layer of Spring/JPA/Hibernate behavior. The cascade terminated only when the fix addressed the root mechanism (`merge()` vs `persist()` for assigned IDs) rather than a symptom.

## Impact

- **No production impact** — all bugs found during PR review before merge
- **Shopify webhook reliability at stake** — if any of Bugs #1, #2, #3, #5, #6, or #7 reached production, Shopify could have unsubscribed the webhook (8 consecutive failures) or orders could have been silently lost (dedup committed but processing failed)
- **Project-wide improvement** — the Persistable enforcement (23 entities) prevents a latent class of bugs across all modules, not just the webhook

## Lessons Learned

### What went well
- Unblocked bot PR reviews caught all 8 bugs before merge — automated review justified its value
- E2E validation sub-agent caught the empty-secret bypass before the first PR was even created
- Each bug was fixed, tested, and pushed within minutes of discovery
- The cascade pattern was recognized — each fix was validated before declaring the bug closed

### What could be improved
- **PM-001 prevention was incomplete:** PM-001 documented the deferred-flush pattern in March 2026 but only created one ArchUnit rule (AFTER_COMMIT + REQUIRES_NEW). The merge-vs-persist manifestation of the same root cause was not guarded. 18 days passed before it resurfaced.
- **`@TransactionalEventListener` is not async:** The implementation plan described AFTER_COMMIT as "async processing" — a misnomer that led to a design that would have exceeded Shopify's 5-second timeout. The distinction between "deferred-until-commit" and "asynchronous" must be documented.
- **Try-catch around JPA operations is insufficient:** Catching `DataIntegrityViolationException` inside a `@Transactional` method does not prevent transaction poisoning. This is a well-known JPA/Hibernate behavior but was not documented in CLAUDE.md constraints.
- **Unit tests with mocked repositories don't catch transaction semantics:** All tests passed with mocked dependencies. The deferred-flush, session-poisoning, and synchronous-listener bugs are invisible to unit tests — they only manifest with real JPA/Hibernate + PostgreSQL interactions.

## Prevention

### Structural enforcement (completed)

- [x] **ArchUnit Rule 3:** `@Entity` classes with assigned `@Id` (no `@GeneratedValue`) must implement `Persistable<T>` — prevents merge-vs-persist deferred flush pattern
- [x] **CLAUDE.md constraint #16:** Documents the Persistable requirement with rationale and PM-001 reference
- [x] **All 23 assigned-ID entities** retrofitted with `Persistable` implementation
- [x] **CI test count gates** expanded to search all modules (not just `modules/app/`) — prevents silent test skips in fulfillment module

### Documentation (completed)

- [x] **CLAUDE.md updated** with constraint #16
- [x] **ShopifyOrderProcessingService KDoc** explicitly documents @Async + AFTER_COMMIT + REQUIRES_NEW interaction

### Future considerations

- [ ] Add CLAUDE.md constraint: "Never catch `DataIntegrityViolationException` inside a `@Transactional` method — extract the operation into a separate `@Component` with `@Transactional(REQUIRES_NEW)` to isolate the persistence context"
- [ ] Add CLAUDE.md constraint: "`@TransactionalEventListener(AFTER_COMMIT)` is synchronous by default — add `@Async` when the listener's work must not block the caller's thread (e.g., webhook responses with external timeouts)"
- [ ] Consider integration tests that exercise real transaction behavior (not mocked repos) for webhook deduplication — unit tests cannot catch transaction semantics bugs
