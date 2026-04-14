# FR-028: CJ API Contract Reconciliation

**Linear ticket:** RAT-47
**Status:** Specification (Phase 2)
**Created:** 2026-04-14

---

## Problem Statement

During FR-027 (RAT-45), a live API call to CJ's `listV2` endpoint revealed that the pre-existing `CjDropshippingAdapter` was built against a fabricated API response structure. Seven field-level mismatches were found (PM-020): `data.list[]` vs real `data.content[].productList[]`, `pid` vs `id`, `productNameEn` vs `nameEn`, `sellPrice` as number vs string range, `productImage` vs `bigImage`, `categoryName` vs `threeCategoryName`, and `pageNum`/`total` vs `pageNumber`/`totalRecords`. The adapter was returning zero candidates against the live API because `data.list` is null in real responses.

FR-027 fixed `CjDropshippingAdapter` and its 6 WireMock fixtures. A verified OpenAPI YAML spec was created at `docs/api/cj_product_api.yaml` with 15/16 Product API endpoints verified against live API calls. However, **all remaining CJ code has NOT been audited**. The same pattern of fabricated API contracts likely exists across the order placement, tracking webhook, and carrier mapping code paths.

### Scope of unverified code

**4 production classes** in the `fulfillment` module send requests to or parse responses from CJ APIs without any verified API documentation:

1. **`CjSupplierOrderAdapter`** -- Sends `createOrderV2` requests with field names (`shippingCustomerName`, `shippingCountryCode`, `fromCountryCode`, `products[].vid`) that have never been verified against CJ's Shopping API documentation. Response parsing assumes `data.orderId` -- the field name and nesting are unverified.

2. **`CjTrackingProcessingService`** -- Parses CJ tracking webhook payloads assuming a `params` object containing `orderId`, `trackingNumber`, and `logisticName`. This webhook payload structure has never been verified against CJ's webhook documentation.

3. **`CjTrackingWebhookController`** -- Same unverified webhook structure assumptions as `CjTrackingProcessingService`.

4. **`CjCarrierMapper`** -- Maps carrier strings (`usps`, `ups`, `fedex`, `dhl`, `4px`, `yanwen`, `yunexpress`, `cainiao`, `ems`) that may not match CJ's actual `logisticName` values returned in webhooks or order responses.

**1 infrastructure class** implements authentication for CJ webhooks:

5. **`CjWebhookTokenVerificationFilter`** -- Assumes CJ sends a Bearer token in the `Authorization` header. The actual CJ webhook authentication mechanism has never been verified.

**5 WireMock fixtures** for order placement use response structures that are unverified:

- `create-order-success.json` -- assumes `data.orderId` and `data.orderNum` fields
- `create-order-auth-failure.json` -- assumes error code `1600001`
- `create-order-invalid-address.json` -- assumes error code `1600502`
- `create-order-null-fields.json` -- assumes `data.orderId: null` shape
- `create-order-out-of-stock.json` -- assumes error code `1600501`

**2 WireMock fixtures** for CJ error envelopes (in `portfolio` module):

- `error-401.json` -- assumes error code `1600001`
- `error-429.json` -- assumes error code `1600200` with message "Too much request"

**6 test classes** embed unverified webhook payload structures or assert against unverified response shapes:

- `CjSupplierOrderAdapterWireMockTest` (13 tests)
- `CjTrackingProcessingServiceTest` (12 tests)
- `CjTrackingWebhookControllerTest` (8 tests)
- `CjWebhookTokenVerificationFilterTest` (8 tests)
- `CjTrackingWebhookIntegrationTest` (3 tests)
- `CjCarrierMapperTest` (14 tests)

### Why this matters

The PM-020 precedent proved that WireMock tests passing against fabricated fixtures provides zero assurance of production correctness. Every CJ API interaction above could silently fail in production -- orders could fail to place, tracking webhooks could be rejected or misparse tracking numbers, and carrier names could fail to normalize. These are the revenue-critical paths: order placement and shipment tracking.

## Business Requirements

### BR-1: CJ Shopping API YAML spec created from live verification

Create `docs/api/cj_shopping_api.yaml` by fetching and reading the CJ Shopping (Order) API documentation at `https://developers.cjdropshipping.cn/en/api/api2/api/order.html` and related pages. The spec must document:

- **Order creation** (`createOrderV2`): request body schema (all field names, types, required/optional), response envelope schema (`code`, `result`, `message`, `data` structure), and error codes
- **Order query** endpoints: request/response schemas for any order lookup or list endpoints
- **Logistics/tracking** webhook: payload structure, authentication mechanism, field names and types

Each endpoint must be marked with `x-cj-verified: true` if verified against live API calls or documentation, following the convention established in `cj_product_api.yaml`. If a webhook payload structure cannot be verified via live call (webhooks are push-based), mark it `x-cj-verified: docs-only` with a note.

### BR-2: CjSupplierOrderAdapter request body reconciled

Compare the `createOrderV2` request body currently sent by `CjSupplierOrderAdapter` against the verified `cj_shopping_api.yaml` spec. For each field:

| Current field | Action |
|---|---|
| `orderNumber` | Verify field name exists in CJ API |
| `shippingCountryCode` | Verify field name and expected value format |
| `shippingCountry` | Verify field name exists (may be `shippingCountryName` or similar) |
| `shippingCustomerName` | Verify field name (may be `shippingCustomerName` or `consigneeName` or similar) |
| `shippingAddress` | Verify field name and whether CJ expects concatenated or structured address |
| `shippingCity` | Verify field name |
| `shippingProvince` | Verify field name (may be `shippingProvince` or `province` or `state`) |
| `shippingZip` | Verify field name (may be `shippingZip` or `zipCode` or `postalCode`) |
| `shippingPhone` | Verify field name |
| `fromCountryCode` | Verify field name |
| `products[].vid` | Verify field name (may be `vid` or `variantId` or `skuId`) |
| `products[].quantity` | Verify field name |
| `logisticName` | Verify field name and valid values |

Any field name mismatch must be corrected in the adapter. Any missing required fields documented in the CJ API must be added.

### BR-3: CjSupplierOrderAdapter response parsing reconciled

Compare the response parsing logic against the verified spec:

- **Success path**: Verify `data.orderId` is the correct field name and path for the supplier order ID. Verify `data.orderNum` exists (used in fixture but not parsed by adapter).
- **Error path**: Verify `code`, `result`, `message` envelope fields are correct. Verify error codes (`1600001` for auth, `1600502` for invalid address, `1600501` for out of stock) match CJ's documented error codes.

### BR-4: CJ tracking webhook payload structure reconciled

Verify the CJ tracking webhook payload structure against CJ's webhook documentation:

- **Envelope**: Verify top-level structure. Current code assumes `params` object at root level containing `orderId`, `trackingNumber`, `logisticName`. The actual structure may differ (e.g., fields may be at root level, or nested differently).
- **Field names**: Verify `orderId`, `trackingNumber`, `logisticName` are the actual field names CJ uses.
- **Additional fields**: Document any fields present in real webhook payloads that the current code ignores (e.g., `trackingStatus`, `logisticsTrackEvents`, `messageId`, `type`, `messageType`, `openId`).

Both `CjTrackingWebhookController` and `CjTrackingProcessingService` must be updated if the structure differs.

### BR-5: CJ webhook authentication mechanism reconciled

Verify how CJ authenticates webhook deliveries:

- **Current assumption**: Bearer token in `Authorization` header, compared via constant-time `MessageDigest.isEqual()`.
- **Possible alternatives**: HMAC signature verification, shared secret in query parameter, IP allowlisting, custom header (not `Authorization`).

If the authentication mechanism differs from Bearer token, `CjWebhookTokenVerificationFilter` must be rewritten to match the actual mechanism. If Bearer token is confirmed correct, add an `x-cj-verified: true` comment to the filter class.

### BR-6: CjCarrierMapper values reconciled

Verify the `logisticName` values CJ actually returns in webhook payloads and order responses. The current mapping table contains 9 entries (`usps`, `ups`, `fedex`, `dhl`, `4px`, `yanwen`, `yunexpress`, `cainiao`, `ems`). These values were assumed, not sourced from CJ documentation.

- Add any carrier names CJ documents that are missing from the mapper
- Remove any entries that CJ does not use
- Verify the casing CJ uses (e.g., does CJ send `"UPS"` or `"ups"` or `"Ups"`?)

### BR-7: All WireMock fixtures reconciled and annotated

After BR-1 through BR-6 are complete:

1. Update all 5 order placement fixtures to match the verified response structure
2. Update both CJ error envelope fixtures (`error-401.json`, `error-429.json`) to match verified error codes and messages
3. Add API documentation URL comment headers to all CJ WireMock fixtures, following the pattern established in FR-027:

```json
{
  "_comment": "Source: https://developers.cjdropshipping.cn/en/api/api2/api/order.html",
  "_comment_verified": "2026-04-XX against live CJ API / CJ documentation",
  ...
}
```

### BR-8: All test classes reconciled

After fixtures and adapters are updated:

1. Update `CjSupplierOrderAdapterWireMockTest` -- request body assertions must match corrected field names; response assertions must match corrected response structure
2. Update `CjTrackingProcessingServiceTest` -- inline webhook payloads must match verified webhook structure
3. Update `CjTrackingWebhookControllerTest` -- `validPayload` and all inline payloads must match verified webhook structure
4. Update `CjWebhookTokenVerificationFilterTest` -- if auth mechanism changes, tests must reflect the new mechanism
5. Update `CjTrackingWebhookIntegrationTest` -- `buildCjTrackingPayload()` must produce payloads matching verified structure
6. Update `CjCarrierMapperTest` -- test cases must cover verified carrier names from CJ, remove tests for carrier names CJ does not use

All tests must be green after reconciliation.

## Success Criteria

### SC-1: Verified Shopping API YAML spec exists

`docs/api/cj_shopping_api.yaml` exists, covers order creation and query endpoints, documents the webhook payload structure, and each endpoint/schema is marked with `x-cj-verified` status. Follows the same format as `docs/api/cj_product_api.yaml`.

### SC-2: Zero unverified CJ field names in production code

Every CJ API field name used in `CjSupplierOrderAdapter`, `CjTrackingProcessingService`, `CjTrackingWebhookController`, `CjWebhookTokenVerificationFilter`, and `CjCarrierMapper` traces back to a field documented in either `cj_product_api.yaml` or `cj_shopping_api.yaml`.

### SC-3: All WireMock fixtures have documentation headers

Every CJ WireMock fixture file (in both `fulfillment` and `portfolio` modules) includes a `_comment` field citing the API documentation source URL and a `_comment_verified` field with the verification date.

### SC-4: All fixtures match verified API response structures

Response bodies in all CJ WireMock fixtures match the schemas documented in the verified YAML specs. Field names, nesting, types, and error codes are consistent.

### SC-5: All tests green

`./gradlew test` passes with zero failures across all modules after reconciliation.

### SC-6: Webhook authentication verified

`CjWebhookTokenVerificationFilter` implements the authentication mechanism documented by CJ. If Bearer token is correct, a verification comment is added. If it differs, the filter is rewritten.

### SC-7: Carrier mapper covers verified carrier names

`CjCarrierMapper` contains entries for all `logisticName` values documented by CJ and does not contain entries for values CJ does not use.

## Non-Functional Requirements

### NFR-1: No API key exposure

CJ access tokens and webhook secrets must never appear in YAML specs, fixture files, test code, or commit messages. Use placeholder values (`"<CJ_ACCESS_TOKEN>"`) in documentation. Existing `.env`-based credential loading patterns must be preserved.

### NFR-2: Idempotent verification process

The YAML spec creation process must be repeatable. API documentation URLs must be recorded in the YAML spec so that future verification runs can re-fetch and compare. The `x-cj-verified` markers provide the audit trail.

### NFR-3: No runtime behavior change for correctly-implemented fields

If verification confirms that a field name is already correct, the code must not be changed. Changes are only made where the verified spec reveals a mismatch. This minimizes diff size and regression risk.

### NFR-4: Backward compatibility for webhook processing

If the webhook payload structure changes, the updated parsing must handle both the old and new structures during a transition period only if there is evidence of in-flight webhooks using the old structure. Otherwise, update to the verified structure directly -- the old structure was fabricated and never received real traffic.

### NFR-5: Fixture parity between src and build directories

After fixture changes, verify that `src/test/resources/wiremock/cj/` fixtures match what is compiled into `build/` and `bin/` directories. Stale compiled fixtures are a known issue in this project (both `build/` and `bin/test/` contain copies).

### NFR-6: No changes to non-CJ code

This feature must not modify any adapter, fixture, or test that does not interact with CJ APIs. Shopify, Stripe, Google Trends, Reddit, YouTube adapters and fixtures are out of scope.

### NFR-7: YAML spec format consistency

`cj_shopping_api.yaml` must follow the same OpenAPI 3.0.3 format, authentication model (`CJ-Access-Token` header), and annotation conventions (`x-cj-verified`, `x-cj-doc-source`, `x-cj-doc-notes`) as the established `cj_product_api.yaml`.

## Dependencies

### External Dependencies

| Dependency | Risk | Mitigation |
|---|---|---|
| CJ Shopping API documentation availability | Page may be down or restructured | Cache fetched documentation; fall back to API spec inference from live calls |
| CJ access token in `.env` for live API verification | Token may be expired or rate-limited | Refresh token before starting; space out live verification calls |
| CJ webhook documentation | Webhook payload structure may not be fully documented | Mark unverifiable fields as `x-cj-verified: docs-only`; note what remains unverified |
| CJ API rate limits | Live verification calls may hit rate limits | Use single calls per endpoint, not bulk testing; respect `429` responses |

### Internal Dependencies

| Dependency | Description |
|---|---|
| `docs/api/cj_product_api.yaml` | Established YAML spec format and verification conventions to follow |
| PM-020 postmortem | Documents the fabrication pattern and field-level mismatches found in Product API -- same audit methodology applies here |
| FR-027 fixture reconciliation | Established the `_comment` header pattern for WireMock fixtures |
| All 6 test classes listed above | Must remain green after changes; running `./gradlew test` is the gate |

### Constraint Dependencies

| CLAUDE.md Constraint | Relevance |
|---|---|
| #15 (Jackson get vs path) | Any corrected JSON parsing must use `get()` for null-coalescing |
| #17 (NullNode guard) | Any corrected `get()?.asText()` calls must use the `?.let { if (!it.isNull) it.asText() else null }` guard |
| #20 (No bare ObjectMapper) | Verify no bare `ObjectMapper()` in any touched class (already fixed in `CjSupplierOrderAdapter` by FR-027) |
| Feedback: WireMock fixtures | Per `feedback_wiremock_fixtures.md`: fixtures must be based on real API docs, not reverse-engineered from adapter code |

## Out of Scope

The following are explicitly not part of this feature:

1. **CJ Product API re-verification** -- `cj_product_api.yaml` and `CjDropshippingAdapter` were already reconciled in FR-027. No re-audit needed.
2. **New CJ API integrations** -- This feature audits existing code, not building new endpoints (e.g., CJ Freight Calculation API, order cancellation, return management).
3. **CJ Affiliate API** -- The CJ Affiliate adapter (`CjAffiliateAdapter` in portfolio module) uses a different API (`commission.cj.com`) and is not part of CJ Dropshipping. Out of scope.
4. **Runtime monitoring or alerting** -- Adding production monitoring for CJ API mismatches is a separate concern. This feature ensures code correctness at build time.
5. **Webhook registration or management** -- Configuring webhook URLs in CJ's dashboard is an operational task, not a code change.
6. **`DemandScanJob` changes** -- Listed in the filemap but already reconciled as part of FR-027. Only CJ adapter calls from `DemandScanJob` were affected and those flow through the already-fixed `CjDropshippingAdapter`.
