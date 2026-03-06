# FR-008: Fulfillment Orchestration

## Problem Statement

Customer trust is a long-term balance sheet asset. Fulfillment must be transparent, proactive, and self-correcting. Orders must be routed to vendors and carriers automatically, customers must receive real-time tracking updates and proactive delay alerts, and SLA breaches must trigger automatic refunds — with no manual intervention required.

## Business Requirements

- Inventory availability must be validated before payment is captured
- Orders must be routed automatically to the appropriate vendor based on SKU assignment
- Real-time shipment tracking must be integrated via carrier APIs (UPS, FedEx, USPS)
- Transparent delivery timelines must be communicated at point of sale
- Automated proactive delay alerts sent to customers when carrier data indicates delay
- If a vendor SLA breach is confirmed on an order, an auto-refund must be triggered via Stripe
- Clear, accessible refund policy must be implemented with a defined processing window
- Returns and reverse logistics must be tracked and costs recorded per SKU

## Success Criteria

- `Order` aggregate persisted with status: `Pending`, `Confirmed`, `Shipped`, `Delivered`, `Refunded`, `Returned`
- `InventoryCheckService` validates stock before payment capture (Shopify inventory API)
- `OrderRouter` assigns orders to vendors and initiates fulfillment
- `ShipmentTracker` polls carrier APIs on schedule and updates order status
- `DelayAlertService` detects delays from carrier data and triggers customer notification
- Auto-refund via Stripe on `VendorSlaBreached` event correlated to an active order
- `OrderFulfilled` domain event published on delivery confirmation
- REST: `POST /api/orders`, `GET /api/orders/{id}`, `GET /api/orders/{id}/tracking`
- Integration test: end-to-end order → shipment → delivery flow with WireMock carrier stubs

## Non-Functional Requirements

- Carrier tracking polling every 30 minutes via `@Scheduled`
- Idempotent order creation (duplicate prevention via idempotency key)
- Refund processing within 24 hours of confirmed SLA breach
- All outbound customer notifications logged to `customer_notifications` table

## Dependencies

- FR-001 (shared-domain-primitives) — `OrderId`, `VendorSlaBreached`, `OrderFulfilled`
- FR-002 (project-bootstrap) — Spring Boot, `@Scheduled`
- FR-004 (catalog-cost-gate) — `CarrierRateProvider` for rate quoting; fulfillment defines separate `CarrierTrackingProvider` for shipment tracking
- FR-007 (vendor-governance) — `VendorSlaBreached` event triggers auto-refund
