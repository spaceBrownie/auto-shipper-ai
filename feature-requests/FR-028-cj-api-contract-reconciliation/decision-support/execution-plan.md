## Execution Plan

**Meta-controller:** 3 agents (orchestrator + 2 parallel), chunks of 12, deliberative mode
**Implementation plan:** 5 layers, 27 tasks

### Postmortem Constraints (from PM scan)
- PM-020 #145: This FR directly addresses the "audit all remaining CJ adapters" prevention item
- PM-020 #147: This FR creates `cj_shopping_api.yaml` as specified
- PM-014 #111-112: All fixtures must cite API doc URL in `_comment` header
- PM-013 #138: WireMock contract tests must validate against realistic fixtures

### Round 0 (Orchestrator — Layer 1: API Verification)
Serial, cannot be parallelized — each step depends on the prior.
- 1.1: Get fresh CJ access token
- 1.2: Fetch CJ Shopping API documentation (WebFetch)
- 1.3: Verify createOrderV2 endpoint (live call or docs)
- 1.4: Verify order query endpoints (docs)
- 1.5: Verify webhook payload structure (docs)
- 1.6: Write `docs/api/cj_shopping_api.yaml`

### Round 1 (Layers 2+3 — 2 parallel agents)
**Agent A (Production Code — Layer 2, 6 tasks):**
- 2.1: Reconcile CjSupplierOrderAdapter request body
- 2.2: Reconcile CjSupplierOrderAdapter response parsing
- 2.3: Reconcile CjTrackingProcessingService webhook parsing
- 2.4: Reconcile CjTrackingWebhookController webhook structure
- 2.5: Reconcile CjWebhookTokenVerificationFilter auth mechanism
- 2.6: Reconcile CjCarrierMapper carrier names

**Agent B (Fixtures — Layer 3, 7 tasks):**
- 3.1-3.5: Fix fulfillment fixtures (create-order-*.json)
- 3.6-3.7: Fix portfolio error fixtures (error-401.json, error-429.json)

### Round 2 (Layer 4 — Tests, 1 agent)
- 4.1-4.6: Fix all 6 test classes
- 4.7: Run `./gradlew test`

### Round 3 (Layer 5 — Cleanup)
- 5.1: `./gradlew clean test` — final verification gate

### Override Notes
None — following meta-controller recommendation of 3 agents. Layer 1 runs as orchestrator
(serial API calls), Layers 2+3 use 2 parallel agents, Layer 4 uses 1 agent.
