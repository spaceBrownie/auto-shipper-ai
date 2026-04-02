# SEO Product Page Templates & Content Generation Strategy

> **Date:** 2026-03-31
> **Status:** Research complete
> **Depends on:** `docs/plans/seo-marketing-research.md` (channel strategy)
> **Purpose:** Detailed, implementation-ready templates for product page SEO, content generation by product type, Shopify API integration, programmatic collection pages, and anti-pattern avoidance.

---

## Table of Contents

1. [Product Page SEO Template Structure](#1-product-page-seo-template-structure)
2. [Content Generation Templates by Product Type](#2-content-generation-templates-by-product-type)
3. [Shopify-Specific SEO Implementation](#3-shopify-specific-seo-implementation)
4. [Programmatic SEO Collection Page Templates](#4-programmatic-seo-collection-page-templates)
5. [Anti-Pattern Research](#5-anti-pattern-research)
6. [Competitive Analysis of Top Dropshipping Stores](#6-competitive-analysis-of-top-dropshipping-stores)
7. [Implementation Guidance for Auto Shipper AI](#7-implementation-guidance-for-auto-shipper-ai)

---

## 1. Product Page SEO Template Structure

### 1.1 Title Tag Format

The SEO title (meta title) is the most important on-page ranking signal. Shopify allows setting a separate SEO title from the product name, which means the on-site product name can be clean and human-friendly while the SEO title is keyword-optimized.

**Format:** `{Primary Keyword} {Product Name} - {Key Attribute} | {Brand}`

**Rules:**
- Maximum 60 characters (Google truncates beyond this)
- Primary keyword at the beginning (highest weight position)
- Never include price or promotional language (changes frequently, looks spammy)
- Use pipe `|` or dash `-` as separators
- Brand name at end (only if brand has recognition; omit for white-label/unbranded)

**Template patterns by product type:**

| Product Type | Title Format | Example |
|---|---|---|
| Technical | `{Product} {Key Spec} - {Use Case} \| {Brand}` | `Wireless Earbuds 40hr Battery - Running & Gym \| SoundCore` |
| Lifestyle | `{Adjective} {Product} for {Space/Occasion} \| {Brand}` | `Minimalist Desk Lamp for Home Office \| LightWell` |
| Commodity | `{Product} {Differentiator} - {Quantity/Pack} \| {Brand}` | `Stainless Steel Water Bottle 32oz - Double Wall \| HydroFlask` |
| Gift | `{Product} - {Recipient} Gift {Occasion} \| {Brand}` | `Leather Journal - Dad Gift Father's Day 2026 \| CraftNote` |
| Seasonal | `{Product} for {Season/Event} {Year} \| {Brand}` | `Solar String Lights for Summer Patio 2026 \| GlowUp` |

**Title generation prompt data requirements:**
- `product_name`: From SKU catalog
- `primary_keyword`: From keyword research / Google Trends data
- `key_attribute`: Extracted from product specs (top differentiating spec)
- `product_type`: From catalog category classification
- `brand_name`: From vendor data
- `target_audience`: From demand signal analysis

### 1.2 Meta Description Template

The meta description does not directly affect ranking but has a major impact on CTR. Google displays up to 155 characters. The meta description should sell the click.

**Format:** `{Action verb} {product} with {key benefit}. {Specific detail/proof point}. {Trust signal}. {Soft CTA}.`

**Template patterns:**

| Product Type | Meta Description Template |
|---|---|
| Technical | `Shop the {Product} with {Key Spec}. {Unique data point}. {N}+ reviews. Free shipping on orders over $X.` |
| Lifestyle | `Transform your {space} with {Product}. {Visual/sensory detail}. Rated {X}/5 by {N} customers. Ships free.` |
| Commodity | `Get {Product} at {price positioning}. {Pack/quantity detail}. {Shipping speed}. {Return policy}.` |
| Gift | `The perfect {occasion} gift. {Product} — {emotional hook}. Gift-ready packaging. {Shipping guarantee}.` |
| Seasonal | `Ready for {season}? {Product} {key benefit}. {Urgency cue}. In stock now. {Trust signal}.` |

**CTR-optimizing patterns to inject:**
- Price if competitive: `$XX.XX` (triggers Google price display)
- Availability: `In Stock` or `Ships Today`
- Review snippet: `Rated 4.8/5` (if review data exists)
- Free shipping threshold
- Specific numbers (dimensions, capacity, battery life)
- Question format when appropriate: `Looking for {solution}?`

**Example output:**
```
Shop the SoundCore Pro Wireless Earbuds with 40-hour battery life. 23% lighter than AirPods Pro at half the price. 4.8/5 from 1,200+ reviews. Free shipping.
```

### 1.3 H1/H2/H3 Hierarchy on Page

The heading hierarchy structures both the visual layout and the semantic meaning of the page for search engines. Each product page should follow this hierarchy:

```
H1: {Product Name}                              [one per page, matches/closely matches title tag]
│
├── [Above the fold: images, price, buy button, short description]
│
├── H2: Why Choose the {Product Name}           [benefit-driven narrative section]
│   ├── H3: {Benefit 1}                         [e.g., "40-Hour Battery Life"]
│   ├── H3: {Benefit 2}                         [e.g., "IPX7 Waterproof Rating"]
│   └── H3: {Benefit 3}                         [e.g., "Custom EQ via App"]
│
├── H2: Specifications                           [technical details table]
│
├── H2: What's in the Box                        [contents list, if applicable]
│
├── H2: Frequently Asked Questions               [FAQ accordion with schema]
│   ├── H3: {Question 1}
│   ├── H3: {Question 2}
│   └── H3: {Question 3}
│
├── H2: Customer Reviews                         [review widget area]
│
└── H2: You May Also Like                        [related products section]
```

**Rules:**
- Exactly one H1 per page (the product name)
- H1 should include the primary keyword naturally
- H2s define major content sections
- H3s subdivide within H2 sections
- Never skip levels (no H1 -> H3 without H2)
- Each heading should be descriptive, not generic ("Product Details" is weaker than "SoundCore Pro Specifications")

### 1.4 Product Description Structure

#### Above the Fold (First Viewport)

The above-the-fold area must contain everything needed for a purchase decision:

```
┌─────────────────────────────────────────────────────┐
│  [Product Images]          │  H1: Product Name       │
│  [Gallery / 360 view]      │  ★★★★★ (4.8) 1,200 reviews │
│                            │  $XX.XX                 │
│                            │  [Color/Size selectors] │
│                            │  [Add to Cart]          │
│                            │                         │
│                            │  Short description:     │
│                            │  2-3 sentences max.     │
│                            │  Key benefit + primary  │
│                            │  use case. No fluff.    │
│                            │                         │
│                            │  ✓ Free shipping        │
│                            │  ✓ 30-day returns       │
│                            │  ✓ In stock             │
└─────────────────────────────────────────────────────┘
```

**Short description template (above fold):**
```
The {Product Name} delivers {primary benefit} for {target user/use case}.
{Specific differentiator with data point}. {Social proof or trust signal}.
```

Example:
```
The SoundCore Pro delivers studio-quality sound with 40 hours of battery life
for runners and gym-goers. At 5.2g per earbud, it's 23% lighter than AirPods
Pro. Rated 4.8/5 by over 1,200 verified buyers.
```

#### Below the Fold (Scrollable Content)

This is where the SEO-heavy content lives. Structure:

**Section 1: Benefits Narrative (200-400 words)**
- 3-5 benefits, each with a descriptive H3 heading
- Feature -> Benefit -> Proof pattern for each
- Include use-case scenarios: "Whether you're commuting on the subway or running in the rain..."
- Inject computed data: price-per-unit, category comparisons, compatibility info

**Section 2: Specifications Table**
```html
<table>
  <tr><th>Specification</th><th>Value</th></tr>
  <tr><td>Battery Life</td><td>40 hours (case) / 8 hours (earbuds)</td></tr>
  <tr><td>Water Resistance</td><td>IPX7 (sweat, rain, submersion up to 1m)</td></tr>
  <tr><td>Weight</td><td>5.2g per earbud / 48g total with case</td></tr>
  <tr><td>Connectivity</td><td>Bluetooth 5.3, multipoint (2 devices)</td></tr>
  <tr><td>Driver Size</td><td>11mm dynamic</td></tr>
  <tr><td>Noise Cancellation</td><td>Hybrid ANC, up to -35dB</td></tr>
</table>
```

**Section 3: FAQ Accordion (3-7 questions)**
See section 1.5 below.

**Section 4: Related Products**
See section 1.7 below.

### 1.5 FAQ Section

FAQs serve three purposes: (1) answer real buyer objections, (2) capture long-tail search queries, (3) earn FAQ rich snippets in SERPs via FAQPage schema.

**Question generation strategy by source:**

| Source | Question Type | Example |
|---|---|---|
| Product attributes | Compatibility/sizing | "Will this fit in a standard car cup holder?" |
| Category "People Also Ask" | Common concerns | "Are wireless earbuds safe for daily use?" |
| Competitor reviews (complaints) | Objection handling | "Does the battery really last 40 hours?" |
| Shipping/returns | Purchase confidence | "What's the return policy?" |
| Comparison queries | Decision support | "How does this compare to [competitor]?" |
| Use-case specific | Scenario validation | "Can I wear these while swimming?" |

**Question generation prompt data requirements:**
- Product specifications (for compatibility/sizing questions)
- Category name (for "People Also Ask" research)
- Price point (for value-comparison questions)
- Key features (for feature-validation questions)
- Shipping/return policies (store-level, injected into every product)

**FAQ template structure per product:**
- 2 product-specific questions (from specs/features)
- 1-2 category-level questions (from PAA data, reworded per product)
- 1 shipping/returns question (store-level, product-tailored)
- 1-2 comparison/use-case questions (if competitor data available)

**Minimum: 3 questions. Target: 5-7 questions. Maximum: 10 questions.**

Answers should be 40-80 words each. Longer answers lose the rich snippet and look like padding.

### 1.6 Specifications Table Format

The specifications table should be:
- HTML `<table>` (not div-based) for accessibility and crawlability
- Two columns: Specification Name | Value
- Grouped by category for products with many specs (e.g., "Physical", "Performance", "Connectivity")
- Include units for all measurements
- Bold or highlight the key differentiating specs

**Data source:** Product data from supplier/vendor enriched during cost gate phase. The enrichment pipeline should extract structured specs from supplier data and normalize units.

**Minimum spec count by product type:**

| Product Type | Minimum Specs | Priority Specs |
|---|---|---|
| Electronics | 8-12 | Battery, weight, connectivity, dimensions |
| Home/Kitchen | 5-8 | Material, dimensions, weight, capacity |
| Fashion/Accessories | 4-6 | Material, dimensions, care instructions |
| Health/Fitness | 6-8 | Ingredients/materials, dimensions, weight |
| Tools | 6-10 | Power, dimensions, weight, materials |

### 1.7 Related Products Section

The related products section serves two purposes: internal linking (SEO) and cross-selling (revenue). Display 4-8 related products.

**Selection algorithm (ranked by weight):**

```
score = (same_category × 0.35)
      + (complementary_use_case × 0.25)
      + (similar_price_range × 0.15)
      + (shared_tags × 0.15)
      + (margin_priority × 0.10)
```

**Rules:**
- Never show out-of-stock products in related section
- Never show products from different niche stores (if multi-storefront)
- Prefer products with reviews/ratings over those without
- Include 1-2 "good-better-best" options at different price points
- Label: "You May Also Like" or "Customers Also Viewed" (not "Related Products" which is generic)

### 1.8 Breadcrumb Structure

Breadcrumbs serve dual purpose: navigation and SEO (BreadcrumbList schema shows clickable paths in SERPs).

**Format:**
```
Home > {Collection/Category} > {Product Name}
```

**Examples:**
```
Home > Kitchen Gadgets > SoundCore Pro Wireless Earbuds
Home > Kitchen Gadgets Under $30 > Bamboo Utensil Set
Home > Gifts for Dad > Leather Journal Notebook
```

**Rules:**
- Always include Home as the first item
- Use the product's primary collection as the category
- If product belongs to multiple collections, use the one with the most SEO value (highest traffic)
- Each breadcrumb item must be a clickable link
- Product name (final item) is not linked (current page)

### 1.9 Schema.org Markup

Every product page should emit the following schema types as JSON-LD in the `<head>`:

#### Product + Offer Schema

```json
{
  "@context": "https://schema.org",
  "@type": "Product",
  "name": "{{ product.title }}",
  "image": [
    "{{ product.images[0] }}",
    "{{ product.images[1] }}"
  ],
  "description": "{{ product.description | strip_html | truncate: 500 }}",
  "sku": "{{ variant.sku }}",
  "mpn": "{{ product.mpn }}",
  "gtin13": "{{ variant.barcode }}",
  "brand": {
    "@type": "Brand",
    "name": "{{ product.vendor }}"
  },
  "category": "{{ product.product_type }}",
  "offers": {
    "@type": "Offer",
    "url": "{{ product.url }}",
    "priceCurrency": "{{ shop.currency }}",
    "price": "{{ variant.price }}",
    "priceValidUntil": "{{ 90_days_from_now }}",
    "availability": "https://schema.org/InStock",
    "itemCondition": "https://schema.org/NewCondition",
    "seller": {
      "@type": "Organization",
      "name": "{{ shop.name }}"
    },
    "shippingDetails": {
      "@type": "OfferShippingDetails",
      "shippingRate": {
        "@type": "MonetaryAmount",
        "value": "0",
        "currency": "USD"
      },
      "deliveryTime": {
        "@type": "ShippingDeliveryTime",
        "handlingTime": {
          "@type": "QuantitativeValue",
          "minValue": 1,
          "maxValue": 3,
          "unitCode": "DAY"
        },
        "transitTime": {
          "@type": "QuantitativeValue",
          "minValue": 5,
          "maxValue": 12,
          "unitCode": "DAY"
        }
      }
    }
  }
}
```

#### AggregateRating Schema (only when reviews exist)

```json
{
  "@type": "AggregateRating",
  "ratingValue": "4.8",
  "reviewCount": "1247",
  "bestRating": "5",
  "worstRating": "1"
}
```

This is nested inside the Product schema. Only include when `reviewCount > 0`. Google penalizes fake/placeholder ratings.

#### FAQPage Schema

```json
{
  "@context": "https://schema.org",
  "@type": "FAQPage",
  "mainEntity": [
    {
      "@type": "Question",
      "name": "Does the SoundCore Pro work with Android and iPhone?",
      "acceptedAnswer": {
        "@type": "Answer",
        "text": "Yes. The SoundCore Pro connects via Bluetooth 5.3 and is fully compatible with both Android (6.0+) and iOS (14+) devices. It supports multipoint connection to two devices simultaneously."
      }
    },
    {
      "@type": "Question",
      "name": "Can I wear these while swimming?",
      "acceptedAnswer": {
        "@type": "Answer",
        "text": "The SoundCore Pro has an IPX7 waterproof rating, which means it can withstand submersion in up to 1 meter of water for 30 minutes. Suitable for swimming in pools but not recommended for diving or saltwater."
      }
    }
  ]
}
```

#### BreadcrumbList Schema

```json
{
  "@context": "https://schema.org",
  "@type": "BreadcrumbList",
  "itemListElement": [
    {
      "@type": "ListItem",
      "position": 1,
      "name": "Home",
      "item": "https://store.example.com/"
    },
    {
      "@type": "ListItem",
      "position": 2,
      "name": "Wireless Earbuds",
      "item": "https://store.example.com/collections/wireless-earbuds"
    },
    {
      "@type": "ListItem",
      "position": 3,
      "name": "SoundCore Pro Wireless Earbuds",
      "item": "https://store.example.com/products/soundcore-pro-wireless-earbuds"
    }
  ]
}
```

**Impact data from current research:**
- Product + Offer schema: Required for Google Shopping rich results
- AggregateRating: 35-50% CTR improvement when star ratings display
- FAQPage: FAQ accordion rich snippets in SERPs (occupies more SERP real estate)
- BreadcrumbList: Breadcrumb trail in SERPs instead of raw URL
- Combined: Sites with complete schema markup achieve ~58% more clicks and ~32% higher conversion rate. Schema-compliant pages are cited 3.1x more frequently in AI Overviews.

---

## 2. Content Generation Templates by Product Type

Each product type uses a different content template to avoid pattern repetition across the catalog. The system classifies products during the discovery/cost-gate phase and selects the appropriate template.

### 2.1 Technical Products (Electronics, Tools, Gadgets)

**Template: Spec-Heavy with Performance Context**

The reader is comparing specifications. Lead with data, contextualize with real-world performance.

**Structural outline:**

```
SHORT DESCRIPTION (above fold, 2-3 sentences):
  "{Product} delivers {headline spec} for {use case}.
   {Comparison data point vs category average}.
   {Social proof}."

BENEFIT SECTION (H2: "Performance That Matters"):
  H3: {Primary Spec as Benefit}
    Feature → real-world scenario → comparison to alternatives
    Data: "{spec value} — that's {X%} better than the category average of {Y}"

  H3: {Secondary Spec as Benefit}
    Feature → practical impact → specific scenario
    Data: "{spec value} means {real-world outcome}"

  H3: {Tertiary Spec as Benefit}
    Feature → problem it solves → who benefits most

COMPATIBILITY SECTION (H2: "Works With"):
  Table: Device/platform compatibility
  Notes on adapters/accessories needed

SPECIFICATIONS TABLE (H2: "Full Specifications"):
  Complete spec table, 8-12 rows minimum
  Group by: Performance, Physical, Connectivity, Power

WHAT'S IN THE BOX (H2):
  Bulleted list of included items

FAQ (H2: "Frequently Asked Questions"):
  Q1: Performance validation ("Does the battery really last X hours?")
  Q2: Compatibility ("Works with [platform/device]?")
  Q3: Comparison ("How does this compare to [competitor]?")
  Q4: Durability/warranty ("What happens if it breaks?")
  Q5: Use-case specific ("Can I use this for [activity]?")
```

**Data injection points:**
- `{headline_spec}`: Highest-differentiating specification from product data
- `{category_average}`: Computed from catalog data across same product type
- `{comparison_competitor}`: Top-selling alternative in same price range
- `{compatibility_list}`: Extracted from product specs + computed device compatibility
- `{price_per_unit}`: Computed (e.g., price per gram, per hour of battery, per lumen)

**Uniqueness drivers:**
- Computed category-relative positioning (every product's position is unique)
- Specific compatibility data (varies per product)
- Price-per-unit calculations (unique to each product's specs + price)
- Comparison table against specific alternatives (changes per product)

### 2.2 Lifestyle Products (Home Decor, Fashion, Accessories)

**Template: Use-Case Narrative with Sensory Language**

The reader is buying an experience, not specifications. Lead with visual/emotional language and specific scenarios.

**Structural outline:**

```
SHORT DESCRIPTION (above fold, 2-3 sentences):
  "{Evocative adjective} {product} that {transforms/elevates} your {space/look}.
   {Sensory detail — texture, light, feel}.
   {Social proof or editorial-style endorsement}."

SCENE-SETTING SECTION (H2: "Transform Your {Space}"):
  H3: {Setting 1} — e.g., "In the Living Room"
    Narrative paragraph: how the product looks/feels in this specific context
    Specific dimensions/proportions in context ("The 14-inch diameter sits perfectly on a standard end table")

  H3: {Setting 2} — e.g., "As a Gift"
    Gifting context: who it's for, what occasion, unboxing experience

  H3: {Setting 3} — e.g., "Styling Tips"
    Pairing suggestions with complementary products (internal links!)
    Color coordination notes

MATERIALS & CARE (H2: "Crafted to Last"):
  Material details with sensory description
  Care instructions
  Sustainability notes if applicable

SIZING / DIMENSIONS (H2: "Will It Fit?"):
  Precise measurements with real-world context
  ("At 14" x 8" x 6", it's roughly the size of a shoebox")
  Room/space recommendations

FAQ (H2: "Common Questions"):
  Q1: Material quality ("Is this real leather/wood/ceramic?")
  Q2: Sizing in context ("How big is this in person?")
  Q3: Gift readiness ("Does it come gift-wrapped?")
  Q4: Color accuracy ("Does the color match the photos?")
  Q5: Maintenance ("How do I clean/care for this?")
```

**Data injection points:**
- `{space_type}`: Inferred from product category (living room, bedroom, office, etc.)
- `{material_details}`: From supplier product data
- `{dimensions_in_context}`: Computed comparison to common household objects
- `{complementary_products}`: From related products algorithm (internal linking)
- `{color_options}`: From variant data

**Uniqueness drivers:**
- Specific scene-setting narratives (vary by product dimensions, materials, colors)
- Real-world size comparisons (unique per product's exact dimensions)
- Styling/pairing suggestions (unique combinations from catalog)
- Sensory material descriptions (vary by material type)

### 2.3 Commodity Products (Basic Items, Consumables, Replacements)

**Template: Differentiation-Focused with Value Proof**

The reader knows they need this type of product. The question is "why this one?" Lead with what sets it apart from the 50 identical options.

**Structural outline:**

```
SHORT DESCRIPTION (above fold, 2-3 sentences):
  "{Product} — {primary differentiator} at {value positioning}.
   {Quantified advantage over generic alternatives}.
   {Pack/quantity detail}."

DIFFERENTIATION SECTION (H2: "Why This {Product}"):
  H3: {Differentiator 1} — e.g., "Double-Wall Insulation"
    What the feature does differently from standard versions
    Quantified: "Keeps drinks cold for 24 hours vs 6 hours for single-wall"

  H3: {Differentiator 2} — e.g., "BPA-Free Tritan Plastic"
    Material advantage over commodity alternatives
    Health/safety angle if applicable

  H3: {Value Proposition}
    Price-per-unit comparison with alternatives
    Pack/bundle economics ("At $X per bottle, that's 40% less than buying individually")

COMPARISON TABLE (H2: "{Product} vs Alternatives"):
  Table comparing this product against 2-3 alternatives:
  | Feature | This Product | Alternative A | Alternative B |
  Highlight where this product wins
  Be honest about trade-offs (builds trust, avoids penalty for misleading claims)

SPECIFICATIONS (H2: "Details"):
  Concise spec table, 5-8 rows

FAQ (H2: "Quick Answers"):
  Q1: Durability/lifespan ("How long does this last?")
  Q2: Compatibility/fit ("Does this fit standard [application]?")
  Q3: Quantity/pack details ("How many in a pack?")
  Q4: Replacement frequency ("How often should I replace this?")
```

**Data injection points:**
- `{primary_differentiator}`: Top feature that distinguishes from generic alternatives
- `{price_per_unit}`: Computed from price / quantity
- `{category_average_price}`: Computed from catalog
- `{alternative_products}`: 2-3 alternatives from catalog or competitor data
- `{lifespan_estimate}`: From product data or category default

**Uniqueness drivers:**
- Comparison tables with specific alternatives (unique per product)
- Price-per-unit math (unique per product's exact price and quantity)
- Category-relative value positioning (unique per product's position in category)
- Honest trade-off analysis (specific to each product's strengths/weaknesses)

### 2.4 Gift Products (Items Positioned for Gifting)

**Template: Recipient-Focused with Occasion Mapping**

The reader is buying for someone else. Lead with the recipient and occasion, not the product specs.

**Structural outline:**

```
SHORT DESCRIPTION (above fold, 2-3 sentences):
  "The perfect {occasion} gift for {recipient type}.
   {Product} — {emotional hook / why they'll love it}.
   Gift-ready packaging included."

RECIPIENT SECTION (H2: "Perfect For"):
  H3: "For the {Persona 1}" — e.g., "For the Coffee Lover"
    Why this product fits this person
    Specific scenario: "Imagine their face when they unwrap..."

  H3: "For the {Persona 2}" — e.g., "For the Minimalist"
    Different angle on same product for different recipient

OCCASION MAPPING (H2: "Great For Every Occasion"):
  Bulleted list of occasions with brief context:
  - Father's Day: "{Why this fits}"
  - Birthday: "{Why this fits}"
  - Housewarming: "{Why this fits}"
  - "Just Because": "{Why this fits}"

WHAT THEY GET (H2: "What's Included"):
  Detailed unboxing description
  Packaging quality notes
  Any personalization options

PAIR IT WITH (H2: "Complete the Gift"):
  2-3 complementary products that make a gift set
  Links to related products (internal linking)

FAQ (H2: "Gift Buying Questions"):
  Q1: Gift packaging ("Does it come gift-wrapped?")
  Q2: Delivery timing ("Will it arrive before [occasion]?")
  Q3: Returns for gifts ("Can the recipient exchange it?")
  Q4: Personalization ("Can I add a message?")
```

**Data injection points:**
- `{recipient_types}`: Inferred from product category + attributes
- `{occasions}`: Generated based on product type + current season
- `{complementary_gifts}`: From related products algorithm, filtered for gift-appropriateness
- `{delivery_estimate}`: From fulfillment data
- `{price_gift_range}`: Positioned within common gift budget ranges ($25, $50, $100)

**Uniqueness drivers:**
- Recipient persona narratives (unique per product's features)
- Occasion-specific framing (varies by product + current date proximity to holidays)
- Gift set suggestions from catalog (unique combinations)
- Delivery timing context (computed from fulfillment data)

### 2.5 Seasonal Products (Time-Sensitive Items)

**Template: Time-Sensitive with Urgency and Planning Context**

The reader is shopping for a specific season/event. Lead with timeliness and preparation context.

**Structural outline:**

```
SHORT DESCRIPTION (above fold, 2-3 sentences):
  "Get ready for {season/event} with {product}.
   {Key seasonal benefit}. {Availability/stock signal}.
   {Shipping speed for season deadline}."

SEASONAL CONTEXT (H2: "{Season} Essentials"):
  H3: "Why You Need This for {Season}"
    Seasonal use case with specific scenario
    Weather/activity context

  H3: "When to Order"
    Planning timeline ("Order by {date} for {event} delivery")
    Stock availability signal

SEASONAL COMPARISON (H2: "Best {Category} for {Season} {Year}"):
  Brief comparison with alternatives
  Position this product within the seasonal selection
  Link to seasonal collection page

CARE & STORAGE (H2: "Season-Long Performance"):
  Seasonal maintenance tips
  Off-season storage advice
  Durability expectations for the season

FAQ (H2: "Seasonal Questions"):
  Q1: Timing ("When should I set this up?")
  Q2: Weather durability ("Can this handle rain/snow/heat?")
  Q3: Storage ("How do I store this after {season}?")
  Q4: Replacement ("Will I need a new one next year?")
```

**Data injection points:**
- `{season}`: Current or upcoming season based on date
- `{seasonal_deadline}`: Computed from current date + shipping time
- `{weather_context}`: Season-appropriate weather references
- `{seasonal_collection_link}`: Link to the relevant seasonal collection
- `{year}`: Current year (content freshness signal)

**Uniqueness drivers:**
- Time-specific urgency cues (vary by current date)
- Delivery deadline calculations (unique per product's shipping time + current date)
- Year in titles/headings (marks content as current)
- Seasonal comparison positioning (unique per product within seasonal catalog)

### 2.6 Content Uniqueness Enforcement Rules

To prevent thin/duplicate content penalties across the catalog:

| Rule | Threshold | Enforcement |
|---|---|---|
| Pairwise content similarity | < 70% between any two product pages | Compute before publishing; reject if exceeded |
| Template rotation | No template used for > 20% of products | Track template assignment; force rotation |
| Minimum unique words | 100 words for < $25, 300 for $25-100, 500+ for > $100 | Validate word count after generation |
| Data injection points | Minimum 5 unique data points per product | Validate enrichment data before content generation |
| Opening sentence patterns | Rotate 5+ patterns; no pattern used > 15% of the time | Track pattern usage; enforce round-robin |
| Category-specific content | At least 1 section unique to product's category | Enforce via template selection |
| Conditional sections | Only render sections where data exists | No placeholder/"coming soon" sections |

---

## 3. Shopify-Specific SEO Implementation

### 3.1 Setting SEO Title/Description via Shopify API

Shopify stores SEO metadata as metafields in the `global` namespace. The Shopify GraphQL Admin API exposes these through the `productUpdate` mutation.

**GraphQL mutation for SEO fields:**

```graphql
mutation UpdateProductSEO($input: ProductUpdateInput!) {
  productUpdate(product: $input) {
    product {
      id
      title
      seo {
        title
        description
      }
    }
    userErrors {
      field
      message
    }
  }
}
```

**Variables:**
```json
{
  "input": {
    "id": "gid://shopify/Product/{PRODUCT_ID}",
    "seo": {
      "title": "Wireless Earbuds 40hr Battery - Running & Gym | SoundCore",
      "description": "Shop the SoundCore Pro Wireless Earbuds with 40-hour battery life. 23% lighter than AirPods Pro at half the price. 4.8/5 from 1,200+ reviews. Free shipping."
    }
  }
}
```

**Alternative metafield approach (more control):**

```graphql
mutation UpdateProductSEOMetafields($input: ProductUpdateInput!) {
  productUpdate(product: $input) {
    product {
      id
      metafields(first: 5) {
        edges {
          node {
            namespace
            key
            value
          }
        }
      }
    }
    userErrors {
      field
      message
    }
  }
}
```

**Variables:**
```json
{
  "input": {
    "id": "gid://shopify/Product/{PRODUCT_ID}",
    "metafields": [
      {
        "namespace": "global",
        "key": "title_tag",
        "value": "Wireless Earbuds 40hr Battery - Running & Gym | SoundCore",
        "type": "single_line_text_field"
      },
      {
        "namespace": "global",
        "key": "description_tag",
        "value": "Shop the SoundCore Pro Wireless Earbuds...",
        "type": "single_line_text_field"
      }
    ]
  }
}
```

**Hiding a page from search engines (for noindex):**

```json
{
  "namespace": "seo",
  "key": "hidden",
  "value": "1",
  "type": "number_integer"
}
```

### 3.2 Updating Product Body HTML

The product description (body_html) is set via the `descriptionHtml` field:

```graphql
mutation UpdateProductContent($input: ProductUpdateInput!) {
  productUpdate(product: $input) {
    product {
      id
      descriptionHtml
    }
    userErrors {
      field
      message
    }
  }
}
```

**Variables:**
```json
{
  "input": {
    "id": "gid://shopify/Product/{PRODUCT_ID}",
    "descriptionHtml": "<h2>Why Choose the SoundCore Pro</h2><h3>40-Hour Battery Life</h3><p>...</p>"
  }
}
```

### 3.3 Metafield Namespaces for SEO Data

Recommended metafield namespace structure for the marketing module:

| Namespace | Key | Type | Purpose |
|---|---|---|---|
| `global` | `title_tag` | `single_line_text_field` | SEO title (meta title) |
| `global` | `description_tag` | `single_line_text_field` | Meta description |
| `seo` | `hidden` | `number_integer` | 1 = noindex/nofollow |
| `custom` | `faq_json` | `json` | FAQ data for schema rendering |
| `custom` | `specs_json` | `json` | Structured specifications |
| `custom` | `breadcrumb_collection` | `single_line_text_field` | Primary collection handle for breadcrumbs |
| `custom` | `related_products` | `list.product_reference` | Curated related product IDs |
| `custom` | `content_template` | `single_line_text_field` | Which template was used (for tracking) |
| `custom` | `content_generated_at` | `date_time` | When content was last generated |
| `custom` | `seo_score` | `number_integer` | Internal SEO quality score (0-100) |

**FAQ metafield format:**
```json
{
  "questions": [
    {
      "question": "Does the SoundCore Pro work with Android and iPhone?",
      "answer": "Yes. The SoundCore Pro connects via Bluetooth 5.3 and is fully compatible with both Android (6.0+) and iOS (14+) devices."
    },
    {
      "question": "Can I wear these while swimming?",
      "answer": "The SoundCore Pro has an IPX7 waterproof rating, suitable for swimming in pools. Not recommended for diving or saltwater."
    }
  ]
}
```

**Storing FAQ via API:**
```json
{
  "namespace": "custom",
  "key": "faq_json",
  "value": "{\"questions\":[{\"question\":\"...\",\"answer\":\"...\"}]}",
  "type": "json"
}
```

### 3.4 Dawn Theme Modifications

Shopify's Dawn theme (the default free theme) includes basic Product + Offer schema but needs modifications for FAQ accordion, breadcrumbs, and review schema. These are one-time theme edits.

#### FAQ Accordion + Schema Snippet

Create a new Liquid snippet `snippets/product-faq.liquid`:

```liquid
{% assign faq_data = product.metafields.custom.faq_json.value %}
{% if faq_data and faq_data.questions.size > 0 %}
  <div class="product-faq">
    <h2>Frequently Asked Questions</h2>
    {% for item in faq_data.questions %}
      <details class="faq-item">
        <summary><h3>{{ item.question }}</h3></summary>
        <div class="faq-answer">
          <p>{{ item.answer }}</p>
        </div>
      </details>
    {% endfor %}
  </div>

  <script type="application/ld+json">
    {
      "@context": "https://schema.org",
      "@type": "FAQPage",
      "mainEntity": [
        {% for item in faq_data.questions %}
          {
            "@type": "Question",
            "name": {{ item.question | json }},
            "acceptedAnswer": {
              "@type": "Answer",
              "text": {{ item.answer | json }}
            }
          }{% unless forloop.last %},{% endunless %}
        {% endfor %}
      ]
    }
  </script>
{% endif %}
```

Include in `sections/main-product.liquid`:
```liquid
{% render 'product-faq' %}
```

#### Breadcrumb Snippet

Create `snippets/breadcrumbs.liquid`:

```liquid
{% assign breadcrumb_collection_handle = product.metafields.custom.breadcrumb_collection.value
   | default: collection.handle
   | default: product.collections.first.handle %}

{% if breadcrumb_collection_handle %}
  {% assign bc_collection = collections[breadcrumb_collection_handle] %}
{% endif %}

<nav aria-label="Breadcrumb" class="breadcrumb">
  <ol>
    <li><a href="/">Home</a></li>
    {% if bc_collection %}
      <li><a href="{{ bc_collection.url }}">{{ bc_collection.title }}</a></li>
    {% endif %}
    <li aria-current="page">{{ product.title }}</li>
  </ol>
</nav>

<script type="application/ld+json">
  {
    "@context": "https://schema.org",
    "@type": "BreadcrumbList",
    "itemListElement": [
      {
        "@type": "ListItem",
        "position": 1,
        "name": "Home",
        "item": "{{ shop.url }}"
      }
      {% if bc_collection %},
      {
        "@type": "ListItem",
        "position": 2,
        "name": {{ bc_collection.title | json }},
        "item": "{{ shop.url }}{{ bc_collection.url }}"
      },
      {
        "@type": "ListItem",
        "position": 3,
        "name": {{ product.title | json }},
        "item": "{{ shop.url }}{{ product.url }}"
      }
      {% else %},
      {
        "@type": "ListItem",
        "position": 2,
        "name": {{ product.title | json }},
        "item": "{{ shop.url }}{{ product.url }}"
      }
      {% endif %}
    ]
  }
</script>
```

#### Enhanced Product Schema Snippet

Extend Dawn's default product schema in `snippets/product-schema.liquid`:

```liquid
<script type="application/ld+json">
{
  "@context": "https://schema.org",
  "@type": "Product",
  "name": {{ product.title | json }},
  "image": [
    {% for image in product.images %}
      {{ image | image_url: width: 1200 | prepend: "https:" | json }}{% unless forloop.last %},{% endunless %}
    {% endfor %}
  ],
  "description": {{ product.description | strip_html | truncatewords: 100 | json }},
  "sku": {{ product.selected_or_first_available_variant.sku | json }},
  {% if product.selected_or_first_available_variant.barcode != blank %}
    "gtin13": {{ product.selected_or_first_available_variant.barcode | json }},
  {% endif %}
  "brand": {
    "@type": "Brand",
    "name": {{ product.vendor | json }}
  },
  "category": {{ product.type | json }},
  "offers": {
    "@type": "Offer",
    "url": {{ request.origin | append: product.url | json }},
    "priceCurrency": {{ cart.currency.iso_code | json }},
    "price": {{ product.selected_or_first_available_variant.price | money_without_currency | remove: "," | json }},
    "priceValidUntil": "{{ 'now' | date: '%s' | plus: 7776000 | date: '%Y-%m-%d' }}",
    "availability": "https://schema.org/{% if product.available %}InStock{% else %}OutOfStock{% endif %}",
    "itemCondition": "https://schema.org/NewCondition",
    "seller": {
      "@type": "Organization",
      "name": {{ shop.name | json }}
    }
  }
  {% if product.metafields.reviews.rating.value %}
  ,"aggregateRating": {
    "@type": "AggregateRating",
    "ratingValue": {{ product.metafields.reviews.rating.value | json }},
    "reviewCount": {{ product.metafields.reviews.rating_count.value | default: 0 | json }},
    "bestRating": "5",
    "worstRating": "1"
  }
  {% endif %}
}
</script>
```

### 3.5 Collection Page SEO

Collections are the primary vehicle for programmatic SEO. Each collection gets:
- Custom SEO title and meta description via `collectionCreate`/`collectionUpdate` GraphQL mutations
- Rich HTML description (`descriptionHtml`) split into above-products and below-products sections
- FAQ schema via metafields (same pattern as products)

**Collection creation with SEO via GraphQL:**

```graphql
mutation CreateCollection($input: CollectionInput!) {
  collectionCreate(input: $input) {
    collection {
      id
      handle
      title
      seo {
        title
        description
      }
    }
    userErrors {
      field
      message
    }
  }
}
```

**Variables for a smart collection:**
```json
{
  "input": {
    "title": "Kitchen Gadgets Under $30",
    "descriptionHtml": "<p>Discover our curated selection of kitchen gadgets under $30...</p><!-- split --><h2>How to Choose Kitchen Gadgets</h2><p>When selecting kitchen tools on a budget...</p><h2>FAQ</h2>...",
    "seo": {
      "title": "Kitchen Gadgets Under $30 - Best Budget Picks 2026",
      "description": "Shop 50+ kitchen gadgets under $30. Quality tools from trusted brands. Free shipping on orders over $35. Updated weekly."
    },
    "ruleSet": {
      "appliedDisjunctively": false,
      "rules": [
        {
          "column": "TAG",
          "relation": "EQUALS",
          "condition": "kitchen"
        },
        {
          "column": "VARIANT_PRICE",
          "relation": "LESS_THAN",
          "condition": "30.00"
        }
      ]
    },
    "metafields": [
      {
        "namespace": "custom",
        "key": "faq_json",
        "type": "json",
        "value": "{\"questions\":[{\"question\":\"What are the best kitchen gadgets under $30?\",\"answer\":\"Our top picks include...\"}]}"
      }
    ]
  }
}
```

**Note on collection description split:** The `<!-- split -->` HTML comment in `descriptionHtml` is a Dawn theme convention. Content before the split renders above the product grid; content after renders below. This lets you have a brief keyword-rich intro above products and longer SEO content below.

### 3.6 Blog/Article SEO via Shopify API

Blog content (buying guides, comparisons, how-tos) is created via the `articleCreate` GraphQL mutation.

```graphql
mutation CreateArticle($article: ArticleCreateInput!) {
  articleCreate(article: $article) {
    article {
      id
      handle
      title
    }
    userErrors {
      field
      message
    }
  }
}
```

**Variables:**
```json
{
  "article": {
    "blogId": "gid://shopify/Blog/{BLOG_ID}",
    "title": "How to Choose the Best Kitchen Gadgets in 2026",
    "handle": "best-kitchen-gadgets-2026",
    "body": "<h2>What to Look For</h2><p>...</p><h2>Our Top Picks</h2>...",
    "tags": ["buying-guide", "kitchen", "gadgets"],
    "isPublished": true,
    "image": {
      "url": "https://cdn.shopify.com/...",
      "altText": "Kitchen gadgets laid out on a marble countertop"
    },
    "metafields": [
      {
        "namespace": "global",
        "key": "title_tag",
        "type": "single_line_text_field",
        "value": "Best Kitchen Gadgets 2026: Buying Guide | StoreName"
      },
      {
        "namespace": "global",
        "key": "description_tag",
        "type": "single_line_text_field",
        "value": "Compare the top kitchen gadgets of 2026. Expert picks for every budget. Updated monthly."
      }
    ]
  }
}
```

**Blog content types and their SEO targeting:**

| Content Type | Title Pattern | Target Query Pattern | Internal Links |
|---|---|---|---|
| Buying Guide | "How to Choose {Category} in {Year}" | "best {category}", "how to choose {category}" | Links to 5-10 products + parent collection |
| Comparison | "{Product A} vs {Product B}: Which Is Better?" | "{product a} vs {product b}" | Links to both products + alternatives |
| Problem-Solution | "How to Fix {Problem}" | "how to {solve problem}" | Links to products that solve the problem |
| Best-Of | "Best {Category} for {Use Case} ({Year})" | "best {category} for {use case}" | Links to top 5-10 products + collection |
| Tutorial | "How to Use {Product} for {Task}" | "how to use {product}" | Links to the product + accessories |

### 3.7 Internal Linking Patterns Within Shopify URL Constraints

Shopify's URL structure is fixed:
- Products: `/products/{handle}`
- Collections: `/collections/{handle}`
- Blog posts: `/blogs/{blog-handle}/{article-handle}`
- Pages: `/pages/{handle}`

**Automated internal linking rules:**

1. **Product -> Collections:** Every product description should link to its parent collection(s) using natural anchor text in the benefits section. Example: `<a href="/collections/wireless-earbuds">Browse all wireless earbuds</a>`

2. **Product -> Related Products:** The related products section links to 4-8 products. Use descriptive anchor text, not "View Product."

3. **Product -> Blog/Guide:** If a buying guide exists for the product's category, link to it from the product description. Example: `<a href="/blogs/guides/how-to-choose-wireless-earbuds">Read our wireless earbuds buying guide</a>`

4. **Collection -> Products:** The collection description (above products) can highlight 2-3 featured products with links.

5. **Collection -> Collections:** Link to sibling/parent collections. Example: On "Wireless Earbuds Under $50", link to "All Wireless Earbuds" and "Wireless Earbuds for Running."

6. **Blog -> Products + Collections:** Every blog post must link to relevant products (inline) and the parent collection (CTA at bottom).

7. **Blog -> Blog:** Cross-link related guides. "Read our comparison of AirPods vs SoundCore Pro" from within a buying guide.

**Link density guideline:** 2-3 internal links per 300 words of content. More than 5 per 300 words risks looking spammy.

### 3.8 URL Redirect Management

When products are discontinued or collections change, use Shopify's redirect API to preserve SEO equity:

```
POST /admin/api/2026-01/redirects.json
{
  "redirect": {
    "path": "/products/old-product-handle",
    "target": "/products/replacement-product-handle"
  }
}
```

**Redirect rules:**
- Discontinued product with replacement: 301 redirect to replacement
- Discontinued product, no replacement: 301 redirect to parent collection
- Rebranded collection: 301 redirect old handle to new handle
- Never 404 a URL with backlinks or organic traffic

---

## 4. Programmatic SEO Collection Page Templates

### 4.1 Category x Modifier Pattern

The core pattern for programmatic collection generation:

```
Base Category  x  Modifier Type  =  Collection Page
```

**Modifier types and examples:**

| Modifier Type | Pattern | Example Collection |
|---|---|---|
| Price Range | `{Category} Under ${Amount}` | "Kitchen Gadgets Under $30" |
| Use Case | `{Category} for {Activity}` | "Wireless Earbuds for Running" |
| Audience | `{Category} for {Persona}` | "Tech Gifts for Teens" |
| Season | `{Season} {Category} {Year}` | "Summer Outdoor Lighting 2026" |
| Attribute | `{Attribute} {Category}` | "Waterproof Bluetooth Speakers" |
| Material | `{Material} {Category}` | "Bamboo Kitchen Utensils" |
| Color | `{Color} {Category}` | "Rose Gold Desk Accessories" |
| Size | `{Size} {Category}` | "Compact Travel Organizers" |
| Problem | `{Category} for {Problem}` | "Ergonomic Office Accessories for Back Pain" |
| Comparison | `Best {Category} {Year}` | "Best Wireless Earbuds 2026" |

**Smart collection rules mapping:**

| Modifier Type | Shopify Rule Column | Relation | Condition |
|---|---|---|---|
| Price Range (under) | `VARIANT_PRICE` | `LESS_THAN` | Amount |
| Price Range (over) | `VARIANT_PRICE` | `GREATER_THAN` | Amount |
| Use Case | `TAG` | `EQUALS` | Use-case tag |
| Audience | `TAG` | `EQUALS` | Audience tag |
| Season | `TAG` | `EQUALS` | Season tag |
| Attribute | `TAG` | `EQUALS` | Attribute tag |
| Material | `TAG` | `EQUALS` | Material tag |
| Category | `TYPE` | `EQUALS` | Product type |
| Brand | `VENDOR` | `EQUALS` | Vendor name |

**Compound rules example (Kitchen Gadgets Under $30):**
```json
{
  "ruleSet": {
    "appliedDisjunctively": false,
    "rules": [
      { "column": "TAG", "relation": "EQUALS", "condition": "kitchen" },
      { "column": "VARIANT_PRICE", "relation": "LESS_THAN", "condition": "30.00" }
    ]
  }
}
```

### 4.2 Minimum Content Requirements Per Collection

To avoid thin content penalties, each collection page must meet these minimums:

| Requirement | Minimum | Target |
|---|---|---|
| Products in collection | 3 | 8+ |
| Description word count (total) | 150 words | 300+ words |
| Above-grid description | 30-50 words | 50-70 words |
| Below-grid description | 100-200 words | 200-300 words |
| FAQ questions | 2 | 3-5 |
| Internal links in description | 2 | 3-5 |
| Unique content (not shared with similar collections) | 60% | 80%+ |

**If a collection has fewer than 3 products, do not create it.** Wait until the catalog grows. A thin collection page with 1-2 products hurts more than it helps.

### 4.3 Collection Description Template Structure

**Above the product grid (50-70 words):**
```
Discover our curated selection of {collection_title}. Whether you're looking for
{use_case_1} or {use_case_2}, we've hand-picked {product_count} {category} items
that meet our quality standards — every product in this collection has been verified
for {quality_signal}. {Trust element: "Trusted by X customers" or "Updated weekly"}.
```

**Below the product grid (200-300 words):**
```
H2: How to Choose {Category} {Modifier Context}

{Paragraph: 80-120 words giving genuine buying advice for this specific
 combination. What to look for, what to avoid, key specs that matter for
 this use case/price range/audience.}

H2: What Makes Our {Category} Different

{Paragraph: 60-80 words on curation criteria. Every product passes our
 cost gate with verified margins. Links to 2-3 highlighted products.}

H2: Frequently Asked Questions

Q: What is the best {category} {modifier}?
A: {Answer referencing top 1-2 products with links}

Q: How do I choose the right {category} for {use case}?
A: {Genuine buying advice, 40-60 words}

Q: {Category-specific question}
A: {Answer, 40-60 words}

{Paragraph: 40-60 words linking to related collections and buying guide
 blog post if one exists.}
```

### 4.4 Auto-Generating Collection Titles That Target Real Searches

**Process:**

1. **Extract base categories** from the product catalog (product_type field)
2. **Cross-reference with Google Trends** data already flowing through `DemandScanJob`
3. **Generate modifier combinations** from the modifier types table above
4. **Validate search demand** for each combination:
   - Check Google Trends relative interest (must be > 0)
   - Check Google Autocomplete for the phrase (confirms real search behavior)
   - Reject combinations with zero search signal
5. **Check product count** — only create if 3+ products match the rules
6. **Generate title** using the pattern: `{Modifier} {Category}` or `{Category} for {Modifier}`

**Title optimization rules:**
- Use the exact phrase people search for (match Google Autocomplete suggestions)
- Include year for seasonal/best-of collections ("Best Kitchen Gadgets 2026")
- Keep under 60 characters
- Front-load the primary keyword
- Use singular vs plural based on search volume (check both)

**Anti-cannibalization rule:** Before creating a new collection, check existing collections for keyword overlap. If two collections would target the same primary keyword with only a modifier difference of one word, merge them or choose the higher-volume variant.

### 4.5 Smart Collection Creation via Shopify API

**REST API (still supported, simpler for basic collections):**

```
POST /admin/api/2026-01/smart_collections.json
```

```json
{
  "smart_collection": {
    "title": "Kitchen Gadgets Under $30",
    "body_html": "<p>Discover our curated selection...</p><!-- split --><h2>How to Choose...</h2>...",
    "rules": [
      {
        "column": "tag",
        "relation": "equals",
        "condition": "kitchen"
      },
      {
        "column": "variant_price",
        "relation": "less_than",
        "condition": "30.00"
      }
    ],
    "disjunctive": false,
    "sort_order": "best-selling",
    "published": true
  }
}
```

**GraphQL API (preferred for new development):**

```graphql
mutation CreateSmartCollection($input: CollectionInput!) {
  collectionCreate(input: $input) {
    collection {
      id
      handle
      title
      productsCount {
        count
      }
    }
    userErrors {
      field
      message
    }
  }
}
```

**Note:** After creation, the collection is unpublished by default. Use `publishablePublish` mutation to make it visible:

```graphql
mutation PublishCollection($id: ID!, $input: [PublicationInput!]!) {
  publishablePublish(id: $id, input: $input) {
    publishable {
      availablePublicationsCount {
        count
      }
    }
    userErrors {
      field
      message
    }
  }
}
```

### 4.6 Internal Linking Hub-and-Spoke Architecture

```
                    ┌──────────────────────────┐
                    │    Category Hub Page      │
                    │  /collections/kitchen     │
                    │  (links to all spokes)    │
                    └─────────┬────────────────┘
                              │
            ┌─────────────────┼─────────────────┐
            │                 │                  │
   ┌────────▼────────┐  ┌────▼─────────┐  ┌────▼───────────────┐
   │ Price Collection │  │ Use Case     │  │ Audience           │
   │ "Under $30"     │  │ "For Small   │  │ "Gifts for         │
   │                 │  │  Kitchens"   │  │  Home Chefs"       │
   └───────┬─────────┘  └──────┬───────┘  └──────┬─────────────┘
           │                   │                  │
     ┌─────┼─────┐      ┌─────┼─────┐     ┌─────┼─────┐
     │     │     │      │     │     │     │     │     │
    [P1]  [P2]  [P3]  [P1]  [P4]  [P5]  [P2]  [P4]  [P6]
```

**Link flow:**
- Hub -> all spoke collections (in hub description)
- Spoke -> hub (in breadcrumbs)
- Spoke -> sibling spokes (in description: "Also see...")
- Products -> parent spoke(s) (in breadcrumbs + description)
- Products -> 4-8 related products (related products section)
- Blog guides -> hub + relevant spokes + highlighted products
- Spokes -> relevant blog guides ("Read our buying guide")

**A product can appear in multiple spoke collections** (e.g., a $25 bamboo spatula appears in "Kitchen Under $30", "Bamboo Kitchen Utensils", and "Eco-Friendly Kitchen"). This is correct behavior — the product has a single canonical URL (`/products/bamboo-spatula`) and the collections each link to that canonical URL.

---

## 5. Anti-Pattern Research

### 5.1 AI Content Red Flags Google Detects in 2025-2026

Google's scaled content abuse detection system (internally called "Firefly" / `QualityCopiaFireflySiteSignal`) targets patterns, not AI authorship per se. The following patterns trigger detection:

**Structural homogeneity:**
- Same heading structure across > 20% of pages
- Same sentence patterns (e.g., every description starts with "Introducing the...")
- Same paragraph count and length distribution
- Same ratio of bullet points to prose

**Content vacuity:**
- Descriptions that restate the product title in different words
- Generic superlatives: "amazing," "incredible," "perfect for everyone"
- Content that could apply to any product in the category (swap test: if you can replace the product name with a competitor's and the description still makes sense, it fails)
- No specific data points, measurements, or verifiable claims

**Velocity signals:**
- Publishing 50+ pages per day with similar templates
- Sudden spike in page count (from 100 to 5,000 pages in a week)
- All pages published at the same timestamp

**Engagement signals:**
- High bounce rate (> 80%) across programmatic pages
- Low time-on-page (< 10 seconds) across programmatic pages
- Low CTR despite impressions (< 0.5%)
- Zero internal navigation (users leave from the landing page)

### 5.2 Programmatic SEO Patterns That Get Flagged

**Doorway pages:** Pages that exist only to rank for a keyword variation and funnel users to a single destination. Example: "Best earbuds for running," "Best earbuds for cycling," "Best earbuds for hiking" all with 85%+ identical content and the same product recommendations.

**Template-only pages:** Pages where the only difference is a swapped variable (city name, color name, product name) in an otherwise identical template. The "40% rule": if more than 40% of a page is shared boilerplate across similar pages, it risks classification as scaled content abuse.

**Orphan pages:** Programmatic pages with no internal links pointing to them. Google interprets these as low-value pages the site itself doesn't consider important enough to link to.

**Cannibalization clusters:** Multiple pages targeting nearly identical keywords. Google will choose one and suppress the rest, but the existence of cannibalization signals low-quality programmatic generation.

### 5.3 Internal Linking Anti-Patterns

**Excessive reciprocal linking:** Every page linking to every other page in the category. This dilutes link equity and looks automated.

**Identical anchor text:** Using the same anchor text for every link to a page. Example: every product links back to the collection with "Shop Kitchen Gadgets." Vary the anchor text.

**Footer/sidebar link farms:** Hundreds of links in the footer or sidebar to every collection page. Keep navigation links to high-level categories; use contextual links in content for deeper pages.

**Link-to-content ratio:** Pages with more links than actual content. A 200-word page with 15 links is a red flag.

### 5.4 Content Similarity Thresholds

Google does not publish exact thresholds, but the following are derived from case studies and confirmed by SEO research in 2025:

| Similarity Level | Risk | Guidance |
|---|---|---|
| > 85% identical | Very High | Will be detected and suppressed. Effectively duplicate. |
| 70-85% identical | High | Likely flagged as near-duplicate. One page will be chosen, others suppressed. |
| 50-70% identical | Moderate | May pass but offers minimal unique value. Aim higher. |
| 30-50% identical | Low | Acceptable — shared category context with unique product content. |
| < 30% identical | Minimal | Each page is clearly distinct. Target this. |

**Our enforcement rule: < 70% similarity between any two pages.** This is measured as cosine similarity on TF-IDF vectors of the page content (excluding navigation, footer, and common template chrome).

### 5.5 Safe Scaling Protocol

Based on research from successful programmatic SEO implementations:

1. **Pilot phase (first 50 pages):** Monitor indexation daily. Target > 80% indexed within 14 days. If < 60%, stop and audit content quality.

2. **Growth phase (50-500 pages):** Scale at 20-30% per month maximum. Monitor engagement metrics weekly. Engagement on programmatic pages must be within 30% of hand-crafted content metrics.

3. **Mature phase (500+ pages):** Implement automated quality scoring. Pages scoring below 40/100 should be noindexed or rewritten before they drag down site quality signals.

**Red-line indicators (stop creating new pages immediately):**
- Indexation ratio drops below 40%
- Engagement metrics 50%+ below site average
- Site-wide organic traffic declines after page launches
- Manual action notice from Google Search Console
- Crawl errors spike above 10% of submitted URLs
- Duplicate content warnings on > 20% of pages

---

## 6. Competitive Analysis of Top Dropshipping Stores

### 6.1 Warmly (warmly.com) -- Home Decor

**Monthly traffic:** ~269,000 sessions/month (26% growth in last 6 months)
**Monthly revenue estimate:** $566,000-$1,100,000
**Primary traffic sources:** Pinterest (primary), organic search

**What they do well:**
- **Pinterest-first strategy:** Visually appealing pins with lifestyle context, not product-on-white images. Keyword-optimized pin descriptions. Active community engagement on Pinterest.
- **Collection organization:** Deep niche collections (not just "Lighting" but "Minimalist Floor Lamps," "Scandinavian Table Lamps," "Industrial Pendant Lights")
- **Visual storytelling:** Product pages feature room-setting photographs showing the product in context. This creates desire, not just information.

**Key takeaway for Auto Shipper AI:** Pinterest works exceptionally well for home decor. Lifestyle context images are essential — product-on-white underperforms 3-5x. Collection depth matters more than breadth.

### 6.2 Meowingtons (meowingtons.com) -- Cat Products + Cat-Themed Fashion

**Monthly organic traffic:** ~20,900 organic visitors
**Primary traffic sources:** Blog SEO + social media

**What they do well:**
- **Blog content engine:** Extensive blog producing cat-care articles, product guides, and lifestyle content. Each article links to relevant products.
- **Collection descriptions:** Every collection page has a detailed introduction paragraph, not just product grid. This is a basic SEO practice many stores skip.
- **Cross-selling breadth:** Products for cats (toys, accessories) AND products for cat owners (apparel, mugs, jewelry). Every customer has multiple cross-sell paths.
- **Niche authority:** Deep expertise signal in a narrow category. Google rewards topical depth.

**Key takeaway for Auto Shipper AI:** Blog content is the primary organic traffic driver for niche stores. Collection descriptions are a low-effort, high-impact SEO win. Niche depth beats breadth for SEO authority.

### 6.3 Notebook Therapy (notebooktherapy.com) -- Japanese Stationery

**Primary traffic sources:** Pinterest (2.5M+ monthly Pinterest views), Instagram, organic search
**SEO approach:** Long-tail keyword targeting ("Japanese bullet journal," "kawaii stationery")

**What they do well:**
- **Long-tail keyword targeting:** Rank for specific niche queries rather than competing for broad terms. "Japanese bullet journal" is far easier to rank for than "journal."
- **Visual platform dominance:** Pinterest and Instagram as primary channels, with SEO as the compounding long-term play.
- **Aesthetic consistency:** Every product photo, pin, and page maintains a consistent visual identity. This builds brand recognition and increases repeat visits.

**Key takeaway for Auto Shipper AI:** Long-tail keywords are the realistic SEO path for new stores. Compete on specificity, not volume. Pinterest is the highest-ROI social channel for visually appealing products.

### 6.4 BURGA (burga.com) -- Phone Cases + Accessories

**SEO approach:** Tangential content marketing

**What they do well:**
- **Non-obvious keyword targeting:** Rank for queries tangentially related to products. Example: "why are my AirPods flashing red" drives traffic that converts to AirPods case purchases. The content genuinely helps users, and the product link is natural, not forced.
- **Premium positioning of commodity products:** Phone cases are commodities. BURGA positions them as fashion accessories through editorial photography and lifestyle branding. This justifies premium pricing and higher margins.
- **Editorial-quality imagery:** Magazine-style product photography that elevates perceived value.

**Key takeaway for Auto Shipper AI:** Tangential content is an underexploited strategy. Write content that solves problems your products' users have, even if it's not directly about the product. The traffic converts because the audience is right.

### 6.5 Modelones (modelones.com) -- Gel Nail Polish

**SEO approach:** Direct keyword targeting for product terms

**What they do well:**
- **SEO-optimized product descriptions:** Every product targets specific keywords like "gel polish" and "gel nails" with descriptions written for search, not just for browsers.
- **Organic-first growth:** Modelones grew primarily through organic search, not paid ads. Their product pages rank for high-intent commercial queries.
- **Category depth:** Deep collections within the gel polish niche — by color, by type, by occasion.

**Key takeaway for Auto Shipper AI:** For commodity products where the brand has search volume, SEO-optimized product titles and descriptions are sufficient. Not every product needs 500 words of content — but every product needs the right 100 words targeting the right keywords.

### 6.6 Common Patterns Across Successful Stores

| Pattern | Prevalence | Implementation Priority |
|---|---|---|
| Collection descriptions (not just grids) | 5/5 stores | High — immediate low-effort win |
| Blog content driving organic traffic | 4/5 stores | High — medium-effort, strong compounding |
| Pinterest as a primary channel | 3/5 stores | High — fully automatable |
| Long-tail keyword targeting | 4/5 stores | High — realistic for new stores |
| Product pages with genuine unique content | 5/5 stores | Critical — foundation of all SEO |
| Niche depth over breadth | 5/5 stores | Critical — architectural decision |
| Cross-selling/related products | 4/5 stores | Medium — revenue + internal linking |
| Tangential content marketing | 2/5 stores | Medium — high-value but harder to automate |
| Visual platform investment | 4/5 stores | Medium — Pinterest automates, others don't |

---

## 7. Implementation Guidance for Auto Shipper AI

### 7.1 Current Gap Analysis

The existing `ShopifyListingAdapter` (`modules/catalog/src/main/kotlin/com/autoshipper/catalog/proxy/platform/ShopifyListingAdapter.kt`) currently sends to Shopify:

| Field | Current | Required for SEO |
|---|---|---|
| `title` | Product name (bare) | SEO-optimized title with keywords |
| `product_type` | Category | Category (correct) |
| `status` | "active" | "active" (correct) |
| `variant.price` | Price | Price (correct) |
| `variant.sku` | SKU ID | SKU ID (correct) |
| `body_html` | **Missing** | Full SEO product description |
| `tags` | **Missing** | Category, modifier, attribute tags |
| `metafields (SEO)` | **Missing** | title_tag, description_tag |
| `metafields (FAQ)` | **Missing** | FAQ JSON for schema rendering |
| `metafields (specs)` | **Missing** | Structured specifications |
| `images` | **Missing** | Product images with alt text |
| `variant.barcode` | **Missing** | GTIN/UPC for Google Shopping |

### 7.2 Recommended Architecture

The marketing module should NOT modify `PlatformAdapter` or `ShopifyListingAdapter`. Instead, create a separate `ShopifyContentAdapter` that enriches products after they're listed.

**Flow:**
```
catalog: SkuStateChanged(LISTED) event
    │
    ▼
marketing: SeoOptimizationListener
    │
    ├── 1. ProductDataEnricher: gather enrichment data
    │      (category averages, competitor data, specs analysis)
    │
    ├── 2. ContentGenerator: generate content using Claude API
    │      (select template by product type, inject data points)
    │
    ├── 3. ContentQualityValidator: check uniqueness, word count, data points
    │      (reject if similarity > 70%, below word count minimum, < 5 data points)
    │
    └── 4. ShopifyContentAdapter: push enriched content to Shopify
           (body_html, metafields, tags, SEO title/description)
```

### 7.3 Content Generation Pipeline

**Step 1: Classify product type**
```
Input: product category, attributes, price point
Output: template_type (TECHNICAL | LIFESTYLE | COMMODITY | GIFT | SEASONAL)
```

**Step 2: Gather enrichment data**
```
Input: product specs, category, price
Output: EnrichmentData {
  category_average_price: Money,
  price_percentile: Int,           // "23% below category average"
  competitor_count: Int,           // "compared to 47 alternatives"
  key_differentiators: List<String>, // computed from specs vs category
  compatibility_data: List<String>,  // from product attributes
  trending_score: Int,              // from Google Trends via DemandScanJob
  faq_seeds: List<String>           // from category PAA data
}
```

**Step 3: Generate content**
```
Input: product data + enrichment data + template_type
Prompt to Claude API: structured prompt with template instructions + data injection
Output: GeneratedContent {
  seo_title: String,               // max 60 chars
  meta_description: String,        // max 155 chars
  body_html: String,               // full product description
  faq_json: String,                // FAQ questions and answers
  tags: List<String>,              // for collection matching
  specs_table_html: String         // specifications table
}
```

**Step 4: Validate content quality**
```
Input: GeneratedContent + existing_catalog_content
Checks:
  - Pairwise similarity < 70% vs all existing product pages
  - Word count meets minimum for price tier
  - Minimum 5 unique data points injected
  - No banned phrases (generic superlatives, etc.)
  - SEO title <= 60 chars, meta description <= 155 chars
  - FAQ has 3-7 questions with 40-80 word answers
Output: PASS | FAIL (with specific failure reasons)
```

**Step 5: Push to Shopify**
```
Input: validated GeneratedContent + product ID
Actions:
  - productUpdate: descriptionHtml, tags
  - metafieldsSet: title_tag, description_tag, faq_json, specs_json, breadcrumb_collection
Output: SeoOptimizationCompleted event
```

### 7.4 Tagging Strategy for Smart Collections

Products need tags for smart collection rules to work. The tagging system should apply tags during the enrichment step:

| Tag Category | Tag Format | Examples | Source |
|---|---|---|---|
| Product Category | `category:{value}` | `category:kitchen`, `category:fitness` | Product type |
| Use Case | `use-case:{value}` | `use-case:running`, `use-case:home-office` | Extracted from product data |
| Material | `material:{value}` | `material:bamboo`, `material:stainless-steel` | Product specs |
| Audience | `audience:{value}` | `audience:teens`, `audience:professionals` | Inferred from category + price |
| Season | `season:{value}` | `season:summer`, `season:christmas` | Current season + product type |
| Price Tier | `price-tier:{value}` | `price-tier:under-25`, `price-tier:25-50` | Computed from price |
| Gift Suitable | `gift:{value}` | `gift:fathers-day`, `gift:birthday` | Inferred from product type + season |

**Total tags per product: 5-12.** More than 15 tags per product reduces tag signal quality.

### 7.5 Collection Generation Schedule

Collections should be generated after the catalog reaches sufficient depth:

| Trigger | Action |
|---|---|
| Category reaches 3+ products | Generate base category collection |
| Category reaches 5+ products | Generate price-range sub-collections |
| Category reaches 8+ products | Generate use-case and audience sub-collections |
| Seasonal event approaching (30 days) | Generate seasonal collections for categories with 3+ products |
| Blog guide published for category | Generate "Best {Category}" collection linking to guide |

### 7.6 Content Refresh Strategy

SEO content is not set-and-forget. The measurement loop should trigger refreshes:

| Trigger | Action |
|---|---|
| Price change (PricingDecision.Adjusted) | Update meta description if price is mentioned |
| Product out of stock > 7 days | Update availability schema, add "Notify Me" CTA |
| Product back in stock | Restore availability schema, remove "Notify Me" |
| GSC data shows striking distance (position 8-20) | Optimize title for ranking query |
| GSC data shows no impressions after 30 days | Rewrite content, different keyword angle |
| Review count changes significantly | Update AggregateRating schema |
| Seasonal transition | Update seasonal content, collection assignments |
| Content age > 6 months | Refresh year references, check data accuracy |

---

## Sources

- [Shopify productUpdate GraphQL mutation documentation](https://shopify.dev/docs/api/admin-graphql/latest/mutations/productUpdate)
- [Shopify collectionCreate GraphQL mutation documentation](https://shopify.dev/docs/api/admin-graphql/latest/mutations/collectioncreate)
- [Shopify articleCreate GraphQL mutation documentation](https://shopify.dev/docs/api/admin-graphql/latest/mutations/articleCreate)
- [Shopify Smart Collection REST API documentation](https://shopify.dev/docs/api/admin-rest/latest/resources/smartcollection)
- [Shopify SEO metadata optimization guide](https://shopify.dev/docs/apps/build/marketing-analytics/optimize-storefront-seo)
- [Shopify metafields documentation](https://shopify.dev/docs/apps/build/metafields)
- [Schema.org Product type specification](https://schema.org/Product)
- [Programmatic SEO: Scale Without Google Penalties (2025)](https://guptadeepak.com/the-programmatic-seo-paradox-why-your-fear-of-creating-thousands-of-pages-is-both-valid-and-obsolete/)
- [Does Google Penalize AI Content? SEO Risks in 2026](https://digitalmonkmarketing.com/does-google-penalize-ai-content-2026/)
- [Scaled Content Abuse: Google's AI Page Crackdown Guide](https://www.digitalapplied.com/blog/scaled-content-abuse-google-march-update-ai-pages-decimated)
- [Shopify Collection Page SEO Guide (LOGEIX)](https://logeix.com/shopify-seo/collection-pages)
- [Product Schema on Shopify: JSON-LD Templates That Scale](https://www.netprofitmarketing.com/product-schema-on-shopify-json-ld-templates-that-scale/)
- [Shopify SEO Product Page Optimization Guide 2026](https://www.digitalapplied.com/blog/shopify-seo-2026-product-page-optimization-guide)
- [Shopify Programmatic SEO Guide](https://www.shopify.com/blog/programmatic-seo)
- [SEO Product Descriptions (Semrush)](https://www.semrush.com/blog/seo-product-description/)
- [Adding FAQPage Rich Results in Shopify with JSON-LD (Ilana Davis)](https://www.ilanadavis.com/blogs/articles/adding-faqpage-rich-results-in-shopify-with-json-ld-for-seo)
- [Successful Dropshipping Store Examples (SaleHoo)](https://www.salehoo.com/learn/successful-dropshipping-examples)
- [Most Successful Shopify Dropshipping Stores 2025 (PageFly)](https://pagefly.io/blogs/shopify/top-shopify-dropshipping-stores)
- [E-commerce Schema Markup Guide 2026 (Koanthic)](https://koanthic.com/en/e-commerce-schema-markup-complete-guide-examples-2026/)
- [Shopify Internal Links SEO Best Practices](https://www.shopify.com/blog/internal-links-seo)
- [SEO for High-Ticket Dropshipping Stores (DropshipLifestyle)](https://www.dropshiplifestyle.com/seo-for-high-ticket-dropshipping-stores/)
