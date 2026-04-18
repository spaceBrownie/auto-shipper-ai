# The Hidden Single-Thread Trap in Spring's Default @Scheduled Executor

*Auto Shipper AI Engineering | 2026-04-18*

**TL;DR** — Spring's `@Scheduled` executor is single-threaded by default. If you have multiple scheduled jobs, a slow one silently queues every other job behind it. In our case, a 6-hour margin sweep running long at scale would delay the 15-minute vendor SLA monitor — meaning breach detection, auto-refunds, and SKU pausing could lag by tens of minutes. The fix is three lines of Spring config. Here's how we found it, why it matters, and what to do about it.

---

## The setup

We run seven scheduled jobs across five modules in a Spring Boot monolith:

```
VendorSlaMonitor     — every 15 minutes   (vendor breach detection → auto-refund)
ShipmentTracker      — every 30 minutes   (carrier API polling → delivery status)
MarginSweepJob       — every 6 hours      (kill rule evaluation across all active SKUs)
ReserveCalcJob       — nightly at 02:00   (capital reserve reconciliation)
DemandScanJob        — nightly at 03:00   (product discovery across 4 signal sources)
KillWindowMonitor    — nightly at 01:00   (sustained-loss SKU identification)
WebhookEventCleanupJob — nightly at 03:00 (dedup table housekeeping)
```

Each job lives in a different module and has no awareness of the others. They're registered independently with `@Scheduled`. This works fine in development and passes all tests because test environments mock the underlying work and each job completes in milliseconds.

The problem is invisible until you run under load.

## What Spring actually does

Spring's default scheduler is this:

```kotlin
// What Spring creates when you use @EnableScheduling with no custom TaskScheduler bean
ThreadPoolTaskScheduler().apply {
    poolSize = 1  // single thread
}
```

One thread. All seven jobs share it. When a job is scheduled to fire, Spring checks if the thread is free. If it's not — because another job is running — the new job waits.

This is documented, technically. But it's easy to miss and the consequences aren't obvious until you think through the timing.

## The failure scenario

Here's what happens at 10x scale with `MarginSweepJob`:

```kotlin
// modules/capital/src/main/kotlin/com/autoshipper/capital/domain/service/MarginSweepJob.kt

@Scheduled(fixedRate = 21_600_000) // every 6 hours
fun sweep() {
    val activeSkuIds = skuProvider.getActiveSkuIds()  // loads ALL active SKUs
    logger.info("Margin sweep started for {} active SKUs", activeSkuIds.size)

    for (skuId in activeSkuIds) {
        try {
            skuProcessor.process(skuId, today)  // DB read + kill rule evaluation per SKU
        } catch (e: Exception) {
            logger.error("Failed to sweep SKU {}", skuId, e)
        }
    }
    logger.info("Margin sweep complete")
}
```

At 1,000 active SKUs, each `process()` call involves at least two database reads (margin snapshots + kill rule evaluation). Conservatively, 20ms per SKU = 20 seconds. At 10,000 SKUs, that's ~200 seconds — over 3 minutes just in database time, before any processing.

Meanwhile, `VendorSlaMonitor` is scheduled to fire every 15 minutes:

```kotlin
// modules/vendor/src/main/kotlin/com/autoshipper/vendor/domain/service/VendorSlaMonitor.kt

@Scheduled(fixedRate = 900_000) // every 15 minutes
@Transactional
fun monitor() {
    // evaluates 30-day SLA breach rate for each vendor
    // emits VendorSlaBreached event if threshold exceeded
    // → triggers auto-refund of active orders
    // → triggers auto-pause of linked SKUs
}
```

If `MarginSweepJob` is running when `VendorSlaMonitor`'s 15-minute window fires, the monitor waits. Not for 15 minutes — for however long the sweep takes. At scale, the "15-minute SLA detection window" becomes "15 minutes plus sweep duration."

The breach still gets caught. The auto-refund still fires. But later than it should. In a system where auto-refunding stranded orders is a customer-facing commitment, this delay is a correctness issue.

## Why tests don't catch this

The jobs work correctly in isolation. Unit tests mock the underlying services, so each job completes in under a millisecond. Integration tests run one job at a time. There's no test that runs all seven jobs concurrently against a slow database, because that would be a flaky performance test, not a functional one.

The problem only manifests under real load with real I/O times. By the time you see it, you're in production.

## The other job that has this problem

`ShipmentTracker` is the same pattern, different failure mode:

```kotlin
// modules/fulfillment/src/main/kotlin/com/autoshipper/fulfillment/domain/service/ShipmentTracker.kt

@Scheduled(fixedRate = 1_800_000) // every 30 minutes
fun track() {
    // loads ALL active orders — no pagination
    // calls carrier API for each order's tracking number
    // UPS/FedEx/USPS rate-limit at ~1 req/sec on standard tier
}
```

At 1,000 active orders: 1,000 carrier API calls per 30-minute window = 48,000 calls per day. At 1 req/sec, just getting through the API calls takes ~17 minutes. The tracker would barely finish before the next cycle starts.

Two problems compound here: the unbounded query (loads everything into memory) and the carrier API rate ceiling. Fix one without fixing the other and you still have a problem.

## The fix: split the pools

The solution has two parts.

**Part 1: Give the scheduler more than one thread.**

```kotlin
@Configuration
class SchedulerConfig {
    @Bean
    fun taskScheduler(): TaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 4
            threadNamePrefix = "scheduled-"
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
        }
}
```

Four threads means four jobs can run simultaneously. The safety-critical monitors (`VendorSlaMonitor`, `ShipmentTracker`) no longer block behind the batch sweeps (`MarginSweepJob`, `DemandScanJob`).

**Part 2: Fix the unbounded queries** (RAT-52).

Thread pool isolation reduces contention but doesn't fix the root issue — a sweep job that takes 20 minutes is still a 20-minute job, just on its own thread. The real fix is cursor-based pagination:

```kotlin
// Before: loads everything
val activeSkuIds = skuProvider.getActiveSkuIds()

// After: processes in batches of 50, commits independently
var cursor: UUID? = null
do {
    val batch = skuProvider.getActiveSkuIdsBatch(cursor, batchSize = 50)
    batch.forEach { skuId ->
        skuProcessor.process(skuId, today)
    }
    cursor = batch.lastOrNull()
    Thread.sleep(100) // breathe between batches
} while (batch.size == 50)
```

The same applies to `ShipmentTracker`: process the `N` highest-priority orders per run (oldest `last_checked_at` first), not all of them. Add a Resilience4j rate limiter per carrier at 0.9 req/sec.

## Why we caught this now

We were doing a strategic scalability review — specifically asking "what breaks first at 10x?" The scheduler thread question came up while auditing the interaction between `MarginSweepJob`'s frequency and `VendorSlaMonitor`'s timing contract.

The signal: `VendorSlaMonitor` fires every 15 minutes, but its action (auto-refund + SKU pause) is a promise we make when a vendor breaches SLA. If the sweep runs long, that promise becomes "15 minutes, unless the sweep is running." At scale, the sweep is often running.

The graph query that surfaced it:

```
QUERY: scale 10x bottleneck
→ DemandScanJob (single-threaded, nightly)
→ MarginSweepJob (full table load, every 6h)
→ ShipmentTracker (no pagination, every 30m)
→ VendorSlaMonitor (15m window, same thread pool)
```

## The thing worth generalizing

If you use `@Scheduled` in Spring Boot and have more than two or three jobs, check your scheduler configuration. If you don't have a `TaskScheduler` bean defined, you're on a single thread.

The jobs most dangerous to leave on the default pool are the ones where timing matters: health monitors, SLA checks, anything with a "fires every N minutes" contract that downstream behavior depends on. Those should get their own thread pool, isolated from long-running batch operations.

The quick audit: look for `@Scheduled(fixedRate = ...)` annotations with small intervals (< 30 minutes) and ask whether any other job with a longer execution time shares the same pool. If yes — and there's no custom `TaskScheduler` bean — you have this problem.

---

## Try it yourself

**Check your current scheduler thread count:**

```bash
# grep for @Scheduled annotations
grep -r "@Scheduled" src/main/kotlin --include="*.kt" | grep -v test
```

**Check if a custom TaskScheduler bean exists:**

```bash
grep -r "TaskScheduler\|ThreadPoolTaskScheduler" src/main/kotlin --include="*.kt"
```

If the second command returns nothing, you're on a single thread.

**Add thread pool isolation:**

```kotlin
// In any @Configuration class
@Bean
fun taskScheduler(): TaskScheduler =
    ThreadPoolTaskScheduler().apply {
        poolSize = 4  // tune to your job count
        threadNamePrefix = "scheduled-"
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
    }
```

**Validate it works:** add a `logger.info("Running on thread: ${Thread.currentThread().name}")` to two jobs that overlap. After the fix, you should see `scheduled-1` and `scheduled-2` running simultaneously rather than `scheduled-1` appearing sequentially for both.

**Fix the unbounded queries while you're there:** any `findAll()` or `findAllActive()` being called inside a `@Scheduled` method on an append-only table is a ticking clock. Replace with cursor-based pagination before it becomes a production incident.

---

*Tags: spring-boot, scheduling, concurrency, kotlin, commerce-automation*
