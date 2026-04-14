## Execution Plan

**Meta-controller:** 4 agents, deliberative mode, decompose depth 3
**Implementation plan:** 5 tiers, 31 tasks (11 production, 9 fixture/config, 11 test)

### Round 0 (Orchestrator — foundation)
- T1.1: Create `V22__supplier_mapping_warehouse_country.sql` (new-file, Tier 1)
- T4.2: Create `product-list-zero-inventory.json` (new-file, Tier 4)
- T4.3: Create `product-list-null-inventory.json` (new-file, Tier 4)
- T4.4: Create `product-list-mixed-inventory.json` (new-file, Tier 4)
- T4.5: Add `default-logistic-name` to `application.yml` (modify, Tier 4)

### Round 1 (Orchestrator — data model changes)
- T1.2: Extend `SupplierProductMapping` with `warehouseCountryCode` (modify, Tier 1)
- T1.3: Update `SupplierProductMappingResolver` native SQL + row mapping (modify, Tier 1)
- T1.4: Add `warehouseCountryCode` to `SupplierOrderRequest` (modify, Tier 1)
- Compile check: `./gradlew compileKotlin`

### Round 2 (2 parallel agents — production code)
**Agent A (Order Routing — fulfillment module):**
- T2.1: Inject Spring ObjectMapper into CjSupplierOrderAdapter (modify, Tier 2)
- T2.2: Add logisticName @Value to CjSupplierOrderAdapter (modify, Tier 2)
- T2.3: Replace hardcoded fromCountryCode with derivation (modify, Tier 2)
- T2.4: Change mapOf to mutableMapOf, add conditional logisticName (modify, Tier 2)
- T2.5: Add INFO log for routing (modify, Tier 2)
- T2.6: Add @PostConstruct warning (modify, Tier 2)
- T2.7: Update SupplierOrderPlacementService to pass warehouseCountryCode (modify, Tier 2)
- T4.1: Update product-list-success.json with warehouseInventoryNum (modify, Tier 4)

**Agent B (Discovery Filtering — portfolio module):**
- T3.1: Add verifiedWarehouse=1 query param (modify, Tier 3)
- T3.2: Add inventory extraction with NullNode guard (modify, Tier 3)
- T3.3: Update mapProduct() signature + demandSignals (modify, Tier 3)
- T3.4: Add INFO log for filtering stats (modify, Tier 3)

Compile check: `./gradlew compileKotlin`

### Round 3 (2 parallel agents — tests)
**Agent C (Discovery tests — portfolio module):**
- T5.1: Update happy-path test (verifiedWarehouse param + inventory signal)
- T5.2: Zero inventory test
- T5.3: Null/absent inventory test
- T5.4: Mixed inventory filtering test

**Agent D (Order routing tests — fulfillment module):**
- T5.5: Update adapter() helper for new constructor
- T5.6: fromCountryCode=US test
- T5.7: fromCountryCode=CN fallback test
- T5.8: logisticName configured test
- T5.9: logisticName blank test
- T5.10: warehouseCountryCode flow in service test
- T5.11: Verify existing tests pass

Full test suite: `./gradlew test`

### Postmortem constraints for all agents
- PM-017: All `get()?.asText()` must use NullNode guard: `?.let { if (!it.isNull) it.asText() else null }`
- PM-012: All `@Value` must use `${key:}` empty defaults
- PM-014: WireMock fixtures based on verified CJ API docs (countryCode param, warehouseInventoryNum field, createOrderV2 body)
- PM-019: No bare `ObjectMapper()` — inject Spring-managed bean
