# Shopify Dev Store — Captured Webhook Payloads

**Origin:** FR-030 (RAT-53) — `WebhookArchivalFilter` writes raw webhook request bodies here
during dev-store test runs when `autoshipper.webhook-archival.enabled=true`.

**Purpose:** Ground-truth payloads from a real Shopify development store, used to regenerate
WireMock fixtures and prevent PM-013-style fixture drift (adapters that silently parse
against fabricated API shapes).

## Naming convention

```
{YYYY-MM-DD}/{topic-or-path-slug}-{epoch-ms}.json
```

Examples:
- `2026-04-18/orders-create-1729276800123.json`
- `2026-04-18/webhooks-cj-tracking-1729276801456.json`

## PII redaction policy

**Before committing any file in this directory, redact:**

- Buyer email addresses → `redacted@example.com`
- Buyer names → `Test Buyer`
- Shipping addresses → keep structure, replace with a plausible test address (e.g. Shopify's
  example addresses or `1 Test St, City, Country`)
- Phone numbers → `+15555550100`
- Any `customer.id`, `note_attributes`, or free-text fields that may contain PII
- Stripe / payment tokens, `gateway_reference` values

**Do NOT redact:**
- Line-item SKU codes, product ids, variant ids, inventory_item_ids
- Order timing fields, currency, amounts
- Shopify internal structure (financial_status, fulfillment_status, etc.)

This directory is committed to the repository. Run a `grep` for common PII patterns before
each commit — see runbook Section 0 for the exact audit command.

## Not a live mirror

These are point-in-time captures, not a streaming feed. The `autoshipper.webhook-archival.enabled`
property is OFF by default in production; it is only flipped on during explicitly scoped
dev-store test runs.
