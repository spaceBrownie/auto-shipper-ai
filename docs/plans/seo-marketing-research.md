# SEO & Marketing Strategy Research — RAT-14

> **Date:** 2026-03-30
> **Status:** Research complete, pending implementation planning
> **Linear:** RAT-14, RAT-38, RAT-39, RAT-40
> **Context:** Nathan identified marketing/SEO as the last gap before autonomous revenue. This document captures deep research across 6 parallel tracks to inform implementation.

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Channel Prioritization](#2-channel-prioritization)
3. [Google Shopping Free Listings](#3-google-shopping-free-listings)
4. [Shopify SEO Architecture](#4-shopify-seo-architecture)
5. [Content Strategy & Google E-E-A-T](#5-content-strategy--google-e-e-a-t)
6. [Programmatic SEO at Scale](#6-programmatic-seo-at-scale)
7. [Pinterest Automation](#7-pinterest-automation)
8. [Email Marketing](#8-email-marketing)
9. [SEO Measurement & Feedback Loops](#9-seo-measurement--feedback-loops)
10. [Channels Evaluated and Rejected](#10-channels-evaluated-and-rejected)
11. [Architecture Integration](#11-architecture-integration)
12. [Implementation Phases](#12-implementation-phases)
13. [Anti-Patterns & Guardrails](#13-anti-patterns--guardrails)

---

## 1. Executive Summary

The zero-capital model mandates zero ad spend — all traffic must be organic. Research across 6 tracks identified a clear implementation path with 4 viable channels ordered by automation level and compounding behavior:

| Priority | Channel | Automation Level | Compounds? | Time to Traffic |
|----------|---------|-----------------|------------|-----------------|
| #1 | Google Shopping free listings | Fully automated (Shopify native) | Yes | 1-3 days |
| #2 | Email flows (Klaviyo/Shopify) | Fully automated after setup | Yes (list grows) | Immediate |
| #3 | Pinterest organic | Fully automated via API | Yes (3-6mo pin life) | 2-4 weeks |
| #4 | SEO content (blog, collections) | AI-generated + automated publishing | Yes (strongest) | 2-6 months |

**Key finding:** Google doesn't penalize AI content for being AI — it penalizes thin, templated, low-information-gain content. The system needs a **product data enrichment pipeline** before content generation, not just better prompts.

**Expected traffic trajectory (conservative, single store):**

| Timeline | Monthly Sessions | Primary Sources |
|----------|-----------------|-----------------|
| Month 1 | 100-500 | Google Shopping, direct |
| Month 2 | 500-2,000 | Google Shopping + Pinterest ramp |
| Month 3 | 2,000-5,000 | Pinterest compounding + email re-engagement |
| Month 6 | 5,000-15,000 | SEO blog content indexing + Pinterest maturity |
| Month 12 | 15,000-50,000 | SEO compounding + all channels mature |

---

## 2. Channel Prioritization

### Ranking Criteria

For an autonomous system, the critical factor is **automation level** — can it run without human intervention? Followed by **compounding** — does effort accumulate or require constant replenishment?

### Full Ranking Matrix

| Channel | ROI Potential | Automation | Compounds? | Viable? |
|---------|-------------|------------|------------|---------|
| Google Shopping (free) | Very High | Full | Yes | **Yes** |
| Email (Klaviyo/Shopify) | Very High | Full | Yes (list) | **Yes** |
| Pinterest | High | Full (API) | Yes (3-6mo) | **Yes** |
| SEO/Blog content | High | AI-generated | Yes (strongest) | **Yes** |
| YouTube Shorts | Medium | Semi (FFmpeg+API) | Moderate | **Phase 2** |
| Google Discover | Medium | Byproduct of SEO | No | **Byproduct** |
| Instagram Reels | Low-Medium | Semi (repurpose) | No | **Defer** |
| TikTok | Low | Low | No | **No** |
| Reddit marketing | Low | Not automatable | No | **Discovery only** |
| Quora/Forums | Very Low | Not automatable | No | **Skip** |

### Why These 4 Channels

1. **Google Shopping**: One-time setup via Shopify's native Google & YouTube app. Products auto-sync. Free listings appear in Shopping tab, Search, Images, Lens, YouTube. Captures highest-intent traffic. Traffic in 1-3 days.

2. **Email**: Abandoned cart emails alone recover 5-15% of abandoned carts. Welcome series, post-purchase flows, and browse abandonment are all set-and-forget. Klaviyo free tier: 250 contacts, 500 sends/month. Converts existing traffic — every other channel becomes more effective with email in place.

3. **Pinterest**: Visual search engine, not social media. Pin shelf life is 3-6 months (vs hours on TikTok/Instagram). API v5 supports full programmatic pin creation. 15-25 pins/day is optimal. Rich Pins auto-pull product data from Shopify. Best categories: home decor, kitchen, fashion, fitness, pets.

4. **SEO Content**: Strongest compounding channel. Buying guides, comparison pages, and collection pages target commercial investigation queries. Takes 2-6 months to build but delivers growing traffic indefinitely. Programmatic content generation at scale is viable if done with genuine data enrichment.

---

## 3. Google Shopping Free Listings

### Setup (One-Time, ~2 Hours)

1. Install Shopify's **Google & YouTube** app (free, by Google)
2. Connect/create Google Merchant Center account
3. Product catalog auto-syncs (title, description, images, price, availability)
4. Products auto-submitted for free listings

### What Auto-Syncs

| Google Field | Shopify Source |
|---|---|
| `title` | `product.title` |
| `description` | `product.body_html` (stripped) |
| `image_link` | First product image |
| `price` | Variant price |
| `availability` | Inventory status |
| `brand` | `product.vendor` |
| `gtin` | `variant.barcode` |
| `product_type` | `product.product_type` |

### Key Gap: GTIN/UPC

Google increasingly requires GTINs for product matching. Products without GTINs get lower ranking. Many CJ dropship products lack GTINs.

**Action:** During vendor onboarding/cost gate phase, query suppliers for GTIN/UPC data. Store in product catalog. Submit `identifier_exists: false` for unbranded/custom products.

### Free Listings Performance

- Appear below paid ads in Shopping tab
- ~30-40% of Shopping tab clicks go to free listings
- Free listing CTR: ~1-2% (vs 3-5% for paid)
- Appear across Shopping tab, Search, Images, Lens, YouTube
- For zero-spend stores, this is the highest-value Google channel

### Ongoing Optimization

- Monitor disapproved products via Content API for Shopping (7,000 req/min — very generous)
- Optimize product titles for search keywords (not just bare product names)
- Ensure accurate availability data (ties into RAT-39 inventory sync)

---

## 4. Shopify SEO Architecture

### What's Controllable via Admin API

| Feature | API Access | Endpoint |
|---|---|---|
| Product SEO title/description | Yes | `productUpdate` mutation, `seo { title, description }` |
| Product body HTML | Yes | `productUpdate` mutation, `descriptionHtml` |
| Product handle (URL slug) | Yes | Set on creation |
| Blog post creation | Yes | `articleCreate` mutation |
| Page creation | Yes | `pageCreate` / REST `pages.json` |
| Collection SEO + description | Yes | `collectionUpdate` mutation |
| Schema markup via metafields | Yes | `productUpdate` with metafields, render via Liquid |
| URL redirects (301) | Yes | REST `redirects.json` |
| Smart Collections | Yes | REST `smart_collections.json` with tag/price rules |

### Fixed Limitations (Cannot Change)

- URL prefixes are hardcoded: `/products/`, `/collections/`, `/pages/`, `/blogs/`
- No nested URL paths (e.g., `/products/category/item` impossible)
- No regex-based redirects
- Sitemap is auto-generated, cannot be customized
- Canonical tags auto-set (correct behavior, but not overridable via API — theme edit required)

### Current Listing Gap

The current `ShopifyListingAdapter` sends minimal data when creating a listing:
- `title` = bare SKU name (no SEO optimization)
- `status` = "active"
- Variant with price and SKU

**Missing:** `body_html`, `tags`, `metafields_global_title_tag`, `metafields_global_description_tag`, `images`, structured data.

Marketing module needs to either extend `PlatformAdapter` or create a separate `ShopifyContentAdapter` to enrich listings post-creation.

### Schema Markup Strategy

Shopify's Dawn theme injects `Product` + `Offer` schema by default. Missing (must add via metafields + Liquid):

| Schema Type | Impact | How to Add |
|---|---|---|
| `AggregateRating` + `Review` | High (star ratings in SERPs = 35-50% CTR boost) | Reviews app (Judge.me, Loox) or custom Liquid |
| `BreadcrumbList` | Medium (breadcrumb trails in SERPs) | Theme Liquid edit (one-time) |
| `FAQPage` | Medium (FAQ accordion in SERPs) | Metafield per product + Liquid snippet |
| `gtin`, `mpn`, `sku` | Medium (Google Shopping eligibility) | Add to theme's JSON-LD from product data |

**Implementation:** Store FAQ and additional schema as JSON metafields per product via API. One-time theme edit to render them. Then fully API-driven per product.

### robots.txt

Customizable since 2024 via `robots.txt.liquid` in theme editor. Default blocks `/admin`, `/cart`, `/checkouts`, internal search, combined collection filters. One-time setup.

### Core Web Vitals

With Dawn theme + minimal apps: LCP 1.5-2.5s, INP <100ms, CLS <0.05 (all "good"). Biggest killers: third-party app scripts. **Rule: minimize Shopify apps.** Every app script adds 200-500ms.

---

## 5. Content Strategy & Google E-E-A-T

### Google's Current Stance on AI Content

Google does not penalize content for being AI-generated. It penalizes content that is **unhelpful** — regardless of authorship. The key distinction:

**What gets penalized:**
- Descriptions that restate the product title in different words
- Generic superlatives ("This amazing product is perfect for everyone!")
- Content that could apply to any product in the category
- Pages with no unique information beyond the manufacturer's site

**What ranks well (AI or human):**
- Specific use-case scenarios ("This 20oz tumbler fits in Honda Civic cup holders")
- Honest trade-off analysis ("The ceramic coating adds weight — 14oz vs 9oz for steel")
- Answering real buyer questions not in the manufacturer spec
- Contextual sizing/compatibility information
- Comparison with alternatives at different price points

### The Information Gain Requirement

Every product page must contain "information gain" — something the user cannot find on the supplier's site or Amazon. For an autonomous system, this comes from:

1. **Computed relative positioning**: "At $X per ounce, this is 23% below category average"
2. **Compatibility data**: "Fits standard US mailbox for easy returns"
3. **Competitive comparison**: Automated vs. top alternatives in category
4. **Category-specific FAQ**: From real "People Also Ask" data, not generic questions
5. **Trend context**: "Trending +200% on Google Trends this quarter"

### Product Data Enrichment Pipeline (Pre-Content-Generation)

Before generating any content, build a structured profile per product:

| Data Point | Source | Purpose |
|---|---|---|
| Manufacturer specs | Supplier API / product data | Base content |
| Price percentile in category | Computed from catalog | Relative positioning |
| Competitor complaints | Amazon reviews (scrape PAA) | FAQ content |
| Compatibility data | Product attributes | Use-case matching |
| Search demand signals | Google Trends, GSC | Keyword targeting |
| Category averages | Computed from catalog | Differentiation claims |

### Content Types by Effectiveness (Ranked)

1. **Buying guides** ("How to Choose a [Category]") — High intent, demonstrates expertise, links to products
2. **"Best X for Y" collections** ("Best Running Shoes for Flat Feet 2026") — Very high commercial intent
3. **Problem-solution articles** ("How to Fix [Problem Your Product Solves]") — Top-of-funnel with product integration
4. **Product comparisons** ("X vs Y: Which Is Better for [Use Case]?") — High intent, natural internal linking
5. **How-to/tutorial content** ("How to Use [Product] for [Task]") — Builds authority, reduces returns
6. **Seasonal content** ("Best [Category] for Summer 2026") — Time-sensitive but high intent

### Content Uniqueness at Scale

The system generates content for thousands of products. To avoid thin content penalties:

**Template diversity:** Use 5-10 template structures selected by product type:
- Technical products → spec-heavy descriptions with comparison tables
- Lifestyle products → use-case narrative descriptions
- Commodity products → differentiation-focused ("why this one vs the 50 others")

**Uniqueness enforcement:**
- Vary sentence structures deliberately (rotate 5+ opening patterns)
- Include product-specific data that makes each description genuinely different
- Conditional content blocks (only show compatibility section if data exists)
- Pairwise similarity check before publishing — flag if >70% similar to existing page
- Minimum unique content: 100 words for <$25 products, 300 words for $25-100, 500+ for >$100

### Reviews Strategy (E-E-A-T "Experience" Signal)

Reviews are one of the strongest ranking signals for product pages:
- Add unique, constantly refreshed content
- Contain natural long-tail keywords
- Provide E-E-A-T "Experience" component
- Enable star ratings in SERPs (35-50% CTR improvement)

**Implementation:**
- Post-purchase review request flow (email 7-14 days after delivery)
- Use Judge.me or Loox for Shopify (provide Schema markup automatically)
- Photo/video reviews carry more weight
- "First reviewer" incentive (small discount on next purchase)

---

## 6. Programmatic SEO at Scale

### Collection Pages as SEO Pages

Shopify collections are the most effective vehicle for programmatic SEO. Each gets its own URL, title, meta, and rich content area.

**Smart Collection generation pattern:**
```
Base Category × Modifier = Collection Page
```

| Base Category | Modifier Type | Example |
|---|---|---|
| Product type | Price range | "Kitchen gadgets under $30" |
| Product type | Use case | "Desk accessories for home office" |
| Product type | Audience | "Tech gifts for teens" |
| Product type | Attribute | "Wireless earbuds with noise cancelling" |
| Product type | Season | "Summer outdoor cooking tools" |

**Minimum threshold:** Only create collection if 3+ products match. Implement via Shopify Smart Collections API with tag/price rules.

### Internal Linking Architecture

Hub-and-spoke model creates topical authority:

```
Category Hub (e.g., "Kitchen")
  ├── Collection: "Kitchen Gadgets Under $30"
  │     ├── Product A (links to 3-5 related products + parent collection)
  │     ├── Product B
  │     └── Product C
  ├── Collection: "Kitchen Gadgets for Small Spaces"
  ├── Blog: "How to Choose Kitchen Gadgets"
  └── Comparison: "Best Kitchen Gadgets 2026"
```

**Automated linking rules:**
1. Product → parent collection(s) via breadcrumbs (always)
2. Product → 3-5 related products in same category (always)
3. Product → comparison page if exists (when available)
4. Blog/guide → relevant collection + top products (always)
5. Collection → sibling collections (always)

**Related product selection algorithm:**
```
score = (category_match × 0.4) + (price_similarity × 0.2) +
        (attribute_overlap × 0.3) + (margin_priority × 0.1)
```

### Out-of-Stock Product Handling (SEO)

| Scenario | Action |
|---|---|
| Temporarily out of stock | Keep page live, `availability: OutOfStock` in schema, show "Notify me" |
| Permanently discontinued, has replacement | 301 redirect to replacement |
| Permanently discontinued, has traffic | Keep live with "Discontinued" badge + links to alternatives |
| Permanently discontinued, no traffic | `noindex` after 90 days |
| Seasonal | Keep page live, update with "Back in [season]" |

**Never 404 a page with backlinks or organic traffic.**

### Keyword Research Automation

Free/low-cost sources for automated keyword discovery:

| Source | Method | Already in System? |
|---|---|---|
| Google Trends | API / RSS feed | Yes (DemandScanJob) |
| Google Autocomplete | HTTP scraping | No |
| Amazon Autocomplete | HTTP scraping | No |
| "People Also Ask" | SERP scraping | No |
| Reddit API | Product discussion mining | Yes (DemandSignalProvider) |
| Google Search Console | Actual query data (post-listing) | No (implement in SEO module) |

**Cross-pollination:** Discovery module's Google Trends data feeds keyword targeting. GSC data feeds back into discovery scoring (categories with proven organic traction get boosted).

---

## 7. Pinterest Automation

### Why Pinterest

Pinterest is a **visual search engine**, not a social network. Content shelf life: 3-6 months (vs hours on Instagram/TikTok). Highest-compounding organic social channel for e-commerce.

### API Capabilities (Pinterest API v5)

- **Authentication:** OAuth 2.0 with refresh tokens
- **Pin creation:** `POST /v5/pins` — image, title, description, link, board_id
- **Video upload:** `POST /v5/media` + reference in pin
- **Board management:** `POST /v5/boards`
- **Analytics:** `GET /v5/pins/{pin_id}/analytics`
- **Rate limits:** 1,000 calls/min per token. Practical pin creation: 15-25 pins/day (spam threshold)

### Rich Pins (One-Time Setup)

Rich Pins auto-pull product data (price, availability) from Shopify OG meta tags. Setup:
1. Shopify already includes OG tags
2. Validate at developers.pinterest.com/tools/url-debugger/
3. Apply — enabled for entire domain
4. All future pins linking to products become Rich Pins automatically

### Board Strategy

- 15-30 niche boards per account
- Board names = keyword-rich ("Modern Minimalist Home Office Decor" not "Office Stuff")
- Each board: 50+ pins to appear authoritative
- 2-3 boards per product category with different keyword angles

### Posting Strategy

- **Frequency:** 15-25 pins/day (mix of fresh content and repins)
- **Timing:** 8-11 PM EST, 2-4 PM EST. Weekends 25-30% higher engagement
- **Content:** Lifestyle/context images outperform product-on-white by 3-5x
- **No scheduling API parameter** — system must call create endpoint at desired publish time (Spring `@Scheduled` fits perfectly)

### Best Product Categories for Pinterest

Home decor, fashion/jewelry, beauty, kitchen/cooking, fitness, pet products, tech accessories, art prints, garden/outdoor, wedding items.

---

## 8. Email Marketing

### Why Email is #2 Priority

Email converts existing traffic. Without it, every other channel is less effective. Abandoned cart emails alone recover 5-15% of abandoned carts.

### Implementation (Klaviyo Free Tier)

- **Free:** 250 contacts, 500 sends/month
- **Native Shopify integration** (one-click install)
- **REST API** for list management, flow triggering, campaign creation
- **Key automated flows:**
  1. Welcome series (3-5 emails after signup)
  2. Abandoned cart (1hr, 24hr, 72hr after abandonment)
  3. Post-purchase (review request + cross-sell, 7-14 days after delivery)
  4. Browse abandonment (visited product, didn't add to cart)
  5. Win-back (inactive customer re-engagement)

### Email Collection (Zero Spend)

- Exit-intent popups (built into Shopify themes or free apps)
- Spin-to-win discount wheels (5-15% visitor conversion)
- Content upgrades (buying guide in exchange for email)
- Post-purchase automatic enrollment

### Integration with Commerce Engine

Fulfillment module already emits `OrderCreated`, `OrderShipped`, `OrderDelivered` events. These feed email sequences via Klaviyo webhook triggers or direct API calls.

---

## 9. SEO Measurement & Feedback Loops

### Google Search Console API (Primary Data Source)

- **Data:** Impressions, clicks, CTR, position — segmented by query, page, date, device, country
- **Rate limits:** 1,200 requests/day per site (very generous for a single store)
- **Data delay:** 2-3 days (acceptable for SEO timescales)
- **Retention:** 16 months
- **Auth:** OAuth 2.0 with service account

### PageSpeed Insights API

- **Free:** 25,000 queries/day with API key
- **Data:** Performance score, LCP, CLS, INP, Speed Index
- **Weekly monitoring** of all product pages — trivial within limits

### SEO Status Classification

For each product page, classify based on GSC data:

| Status | Criteria | Action |
|---|---|---|
| Dark | 0 impressions in 30 days | Investigate indexing, improve content |
| Emerging | <100 impressions, position >20 | Build supporting content, internal links |
| Striking distance | Impressions >100, position 8-20 | **Optimize title/meta for ranking queries** |
| Ranking | Position <8, clicks >0 | Maintain, build more content around this |
| Winning | Position <5, CTR >3%, converting | Scale — more products in this category |

### Striking Distance Optimization (Highest ROI)

Products ranking at position 8-20 with meaningful impressions are the highest-value automated optimization target:

1. Query GSC for `position >= 8 AND position <= 20 AND impressions >= 50`
2. Check if the ranking query appears in the page's title tag — if not, incorporate it
3. Rewrite meta description to include the query naturally
4. Rate-limit: max 1 title change per product per 14 days
5. Track before/after: store old title, new title, date, target query
6. Rollback if position drops >5 spots after 14-21 days

### SEO Metrics for Portfolio Ranker

Add these signals to the existing `PriorityRanker`:

| Metric | Weight | Source |
|---|---|---|
| Organic traffic volume (clicks 30d) | 0.30 | GSC |
| Organic traffic trend (14d/14d) | 0.20 | GSC |
| SEO conversion rate | 0.15 | GSC clicks + Shopify orders |
| Striking distance opportunity count | 0.15 | GSC position analysis |
| Keyword breadth (distinct queries) | 0.10 | GSC |
| Ranking velocity (week-over-week) | 0.10 | GSC |

### SEO Channel Kill Rules

SEO failure should trigger channel switching, not SKU termination:

| Signal | Threshold | Action |
|---|---|---|
| Page indexed >30 days, impressions <50 | SEO unlikely | Try Pinterest/social |
| Page indexed >60 days, clicks <5 | SEO failed | Reallocate effort |
| Avg position >50 after 45 days | Not competitive | Stop SEO investment |
| Position declining 3 consecutive weeks | Losing ground | Diagnose or reallocate |

### Monitoring Schedule

| Job | Frequency | API |
|-----|-----------|-----|
| Search performance pull | Daily | GSC Search Analytics |
| Striking distance analysis | Weekly | GSC (stored data) |
| Index coverage check | Daily | GSC URL Inspection |
| Page speed monitoring | Weekly | PageSpeed Insights |
| Ranking trend analysis | Weekly | Computed from stored data |
| Title/meta optimization | Bi-weekly | Shopify GraphQL + GSC data |
| SEO score for portfolio ranker | Daily | Computed from stored data |

---

## 10. Channels Evaluated and Rejected

### TikTok — Rejected

- TikTok Shop requires shipping within 3 business days + US warehouse (incompatible with CJ dropship)
- Content creation requires physical product samples or sophisticated AI video generation
- API access for automated posting is gated and granted selectively
- Content shelf life: 24-72 hours (no compounding)
- Algorithm is volatile — views swing wildly

### Reddit Marketing — Rejected (Keep as Discovery)

- Direct promotion banned on most subreddits; accounts get shadowbanned
- Bot detection is sophisticated
- Community is hostile to automated/promotional content
- **Value:** Reddit as demand signal source (already implemented in `DemandSignalProvider`) is far more valuable than Reddit as marketing channel

### Quora/Forums — Rejected

- No content creation API (manual only)
- Quora traffic declining since 2023 (AI answers replacing it)
- Not automatable, declining returns

### YouTube Shorts — Deferred to Phase 2

- Technical pipeline is buildable (FFmpeg + AI voiceover + YouTube API)
- YouTube Data API v3 already in the stack
- Rate limit: ~6 uploads/day (1,600 units per upload, 10,000 units/day quota)
- Quality may be low initially — worth prototyping after core channels are running
- Can repurpose to Instagram Reels via Graph API (25 publishes/24hr)

---

## 11. Architecture Integration

### New Module: `marketing`

```
modules/marketing/
  src/main/kotlin/com/autoshipper/marketing/
    domain/service/
      SeoOptimizationListener.kt         # SkuStateChanged(LISTED) → optimize listing
      PricingUpdateListener.kt           # PricingDecision.Adjusted → refresh copy
      MarketingEffortAllocator.kt        # PriorityRanker rankings → allocate SEO effort
      ContentGenerationService.kt        # Product data → enriched content
      CollectionGeneratorService.kt      # Category × modifier → smart collections
      StrikingDistanceOptimizer.kt       # GSC data → title/meta rewrites
      SeoPerformanceTracker.kt           # @Scheduled → GSC data ingestion
      PinterestPinScheduler.kt           # @Scheduled → 15-25 pins/day at optimal times
    proxy/
      ShopifyContentAdapter.kt           # PUT product (body_html, tags, metafields, SEO)
      GoogleSearchConsoleAdapter.kt      # Search Analytics, URL Inspection, Sitemaps
      PageSpeedInsightsAdapter.kt        # CWV monitoring
      PinterestAdapter.kt               # Pin creation, board management, analytics
      ClaudeContentGenerator.kt          # AI content generation (shares infra with compliance)
      KlaviyoAdapter.kt                  # Email flow management
    persistence/
      SeoSnapshotEntity.kt               # Daily per-product SEO metrics
      SeoOptimizationLogEntity.kt        # Title/meta change tracking with before/after
      PinterestPinEntity.kt              # Pin tracking and analytics
      ContentPublicationEntity.kt        # Blog/guide/collection publication log
    config/
      MarketingConfig.kt                 # @ConfigurationProperties
    handler/
      MarketingController.kt             # REST endpoints for dashboard
```

### Domain Events

**Consumes:**
| Event | Source | Action |
|---|---|---|
| `SkuStateChanged(LISTED)` | Catalog | Trigger SEO optimization + Pinterest pinning |
| `SkuStateChanged(PAUSED/TERMINATED)` | Catalog | Stop marketing, handle SEO (redirect/noindex) |
| `PricingDecision.Adjusted` | Pricing | Update listing copy if price-dependent |
| `OrderConfirmed` | Fulfillment | Conversion attribution |
| `OrderFulfilled` | Fulfillment | Post-purchase email (review request) |

**Produces:**
| Event | Purpose | Consumer |
|---|---|---|
| `SeoOptimizationCompleted` | Signal listing enriched | Portfolio |
| `MarketingPerformanceSnapshot` | SEO metrics for scoring | Portfolio `CandidateScoringService` |
| `ContentPublished` | Track content generated | Portfolio reporting |

### Cross-Module Integration

- **marketing → portfolio:** `MarketingPerformanceSnapshot` feeds `PriorityRanker`. Categories with proven organic traction get boosted in `CandidateScoringService`.
- **marketing → catalog:** When a listed SKU gets optimized, the marketing module enriches it via `ShopifyContentAdapter` (separate from `PlatformAdapter`).
- **portfolio → marketing:** When `ScalingFlagService` flags a SKU, marketing increases investment (more content, better optimization, Pinterest priority).
- **marketing → pricing:** Organic traffic volume as a `PricingSignal` — high traffic suggests strong demand, potentially supporting price optimization.

### CLAUDE.md Constraints to Enforce

- Event listeners: PM-005 double-annotation pattern (`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`)
- `@Scheduled` orchestrator jobs NOT `@Transactional` (constraint 9)
- External API adapters: `@Value` with empty defaults (constraint 13)
- JSON parsing: `get()` + NullNode guard (constraints 15, 17)
- `SeoSnapshotEntity` with date-based assigned ID: implement `Persistable<T>` (constraint 16)
- No `internal constructor` on `@Component/@Service` classes (constraint 14)

---

## 12. Implementation Phases

### Phase 1: Foundation (Week 1-2) — Immediate Revenue Path

**Google Shopping free listings:**
- Install Shopify Google & YouTube app
- Ensure product data includes GTIN/barcode where available
- Verify product feed sync in Merchant Center
- **No code changes needed** — Shopify native

**Email infrastructure:**
- Install Klaviyo free tier
- Configure 3 automated flows: welcome, abandoned cart, post-purchase review request
- Set up exit-intent popup for email collection
- Integration: consume `OrderFulfilled` event to trigger review request timing

### Phase 2: Listing Enrichment (Week 3-4) — SEO Foundation

**Enrich existing and new product listings:**
- Build `ShopifyContentAdapter` for updating `body_html`, `metafields`, `tags`
- Build product data enrichment pipeline (spec analysis, relative positioning, category comparisons)
- Build `ClaudeContentGenerator` for SEO-optimized product descriptions
- Listen to `SkuStateChanged(LISTED)` → trigger enrichment
- Add FAQ schema via metafields
- One-time theme setup: Liquid snippet to render FAQ schema from metafields

**Content quality guardrails:**
- Pairwise similarity check (<70% threshold)
- Minimum unique content thresholds by price tier
- Product-specific data injection (not just prompt engineering)

### Phase 3: Pinterest Pipeline (Week 5-6) — First Growth Channel

- Build `PinterestAdapter` (API v5 — pin creation, boards, analytics)
- Build `PinterestPinScheduler` (`@Scheduled` posting 15-25 pins/day)
- Create boards per product category (keyword-optimized board names)
- Set up Rich Pins (one-time validation)
- Pin content: product images with lifestyle context + SEO-optimized titles/descriptions

### Phase 4: Programmatic SEO (Week 7-10) — Content Engine

**Smart collection generation:**
- Cross category tags with modifiers (use-case, price, audience)
- Validate search demand before creation (Google Trends cross-reference)
- Minimum 3 products per collection
- Generate collection descriptions with Claude

**Blog content automation:**
- Build buying guide generator (category → structured guide)
- Build comparison page generator (top products in category → vs. page)
- Publish via Shopify Article API
- Internal linking engine: product ↔ guide ↔ collection

### Phase 5: Measurement & Optimization (Week 11-12) — Feedback Loop

**SEO monitoring module:**
- Build `GoogleSearchConsoleAdapter` (Search Analytics + URL Inspection)
- Build `PageSpeedInsightsAdapter` (weekly CWV checks)
- Build `SeoPerformanceTracker` (daily GSC data ingestion job)
- Build `StrikingDistanceOptimizer` (bi-weekly title/meta rewrites)

**Portfolio integration:**
- Add SEO score to `PriorityRanker`
- Feed organic traffic data back into `CandidateScoringService`
- SEO channel kill rules (60 days, <5 clicks → reallocate)

### Phase 6: Video Pipeline (Month 4+) — Expansion

- FFmpeg product image → Ken Burns video generation
- AI voiceover (ElevenLabs API) for product features
- YouTube Shorts upload via Data API v3
- Repurpose to Instagram Reels via Graph API

---

## 13. Anti-Patterns & Guardrails

### Content Anti-Patterns (Enforce in Code)

- [ ] Never publish product descriptions without data enrichment step
- [ ] Never use the same description template for >20% of products
- [ ] Never create a collection page with <3 products
- [ ] Never create two pages targeting keyword patterns differing by only one word
- [ ] Never publish content with pairwise similarity >70% to existing pages
- [ ] Never 404 a page with backlinks or organic traffic
- [ ] Never fake reviews or testimonials
- [ ] Never create blog content that doesn't link to products

### SEO Anti-Patterns

- [ ] Never serve different content to Googlebot vs users (cloaking)
- [ ] Never change a product title/meta that's performing well (position <5, CTR >4%)
- [ ] Never change a title more than once per 14 days (need measurement time)
- [ ] Never generate keyword-stuffed content (natural language only)
- [ ] Never create doorway pages (keyword-substitution-only variants)

### Pinterest Anti-Patterns

- [ ] Never exceed 30 pins/day (spam classification risk)
- [ ] Never use only product-on-white images (lifestyle context outperforms 3-5x)
- [ ] Never create boards with <50 pins

### Technical Guardrails

- [ ] Monitor pages-indexed / pages-submitted ratio in GSC — investigate if <80%
- [ ] Track content quality score before publishing — reject below threshold
- [ ] Log all SEO title/meta changes with before/after for rollback capability
- [ ] Rate-limit Shopify API calls within plan limits (2 req/sec REST, 1000 points/sec GraphQL)

---

## Open Questions for Implementation Planning

1. **Content generation model:** Claude API directly, or a lighter model for bulk descriptions with Claude for buying guides?
2. **Pinterest image strategy:** Use supplier product images (product-on-white) initially, or invest in AI-generated lifestyle context images?
3. **Collection generation scope:** How aggressively should we cross categories × modifiers? Start with top 5 categories only?
4. **Blog publication frequency:** Start with 2-4 posts/week as research suggests, or ramp slower?
5. **Email provider:** Klaviyo free tier (250 contacts) vs Shopify Email (10,000 emails free/month)?
6. **Multi-storefront (RAT-40):** Should marketing module be storefront-aware from day 1, or retrofit later?
