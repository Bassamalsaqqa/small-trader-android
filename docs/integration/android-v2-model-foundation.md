# Android V2 Model Foundation Provenance and Architectural Specification

## Overview

This specification documents the pure Kotlin contract models, serialization invariants, settlement qualification boundaries, and golden fixture parity implemented under `com.example.data.mobile.contract.v2` for Small Trader Mobile V1 Contract Revision 3 (API v2 / `event_version` 2).

## Contract Manifest and Revision Qualification

- **Contract Version**: `mobile-v1`
- **Contract Revision**: `3` (Draft)
- **Manifest SHA-256**: `e8e3fe672a8f7aceb3152c3dd990b4fb137fa517b24038e468659e0fdf5a87a7`
- **Contract Tree Parity**: The candidate contract tree (`docs/integration/mobile-v1/**` and `tools/validate-mobile-contract.py`) from PR #11 candidate commit `6bb11d9863dabc95143907c0268b0f7d7ab8df8d` is preserved byte-for-byte unchanged.
- **Merge Base**: Clean merge commit `09e77f0598b22eee33bcaabbfcc747388864f0bc` merging PR #11 candidate onto PR #6 base `456bfd39a362f9d3de745f1cf12d918263202dc9`.
- **Validation Engine**: All 303 contract, schema, fixture, regression, and negative checks pass in both Python `3.10+` normal mode and `-O` optimized mode (`tools/validate-mobile-contract.py`).

## Authoritative Golden Parity and Cryptographic Digests

1. **V2 Offline Sale Intake Event**:
   - Fixture: `docs/integration/mobile-v1/fixtures/golden-offline-sale-intake-v2.json`
   - Event Version: `2`
   - Canonical RFC 8785 Payload Digest: `sha256:18f0403bbda6e05eab2805eb20c9c9f9e22d86fb922eda1482db85d24dba30a3`
   - Envelope Bindings: `client_event_id`, `device_id`, and `captured_at` match inner event exactly.
   - Preserves unit mapping `product_unit_id == unit_id`.

2. **Online Sale Canonical Intent Profile**:
   - Fixture: `docs/integration/mobile-v1/fixtures/golden-online-sale-intent-profile.json`
   - Canonical RFC 8785 Payload Digest: `sha256:93b9aa677275388defe694390ab756d4b08f122087ec084b8dbd8b421ffc45fd`
   - Canonical JCS UTF-16 Code-Unit Sorted JSON:
     ```json
     {"actor_id":"01JCV8X9M8T1V2W3X4Y5Z67890","client_event_id":"3fa85f64-5717-4562-b3fc-2c963f66afa6","company_id":"01JCV8X9M2NQW3TYZ4R5S6789A","currency":"ILS","currency_minor_units":2,"customer_id":"01JCV8X9M4K1P2Q3R4S5T6V7W8","device_id":"8e2b8bc6-f80c-4034-8c88-59368d1f2e10","lines":[{"conversion_to_base":"1.000000","product_id":"01JCV8X9M6A1B2C3D4E5F6G7H8","product_unit_id":"01JCV8X9MBV4W5X6Y7Z8012345","quantity":"2.000000","tax_inclusive":true,"tax_rate":"16.000000","tax_rate_id":"01J9Z5K3MN0000000000000030","unit_price":"25.000000"}],"payment":{"amount":"50.000000","cash_account_id":"01JCV8X9MAJ3K4M5N6P7Q8R9S0","payment_method":"cash","settlement_mode":"full"},"warehouse_id":"01JCV8X9M9A2B3C4D5E6F7G8H9"}
     ```

## Architectural and Serialization Invariants

### 1. Wire Validation Before Projection
- Raw preview requests are strictly validated prior to domain object projection or field coercion.
- Unknown root fields, unknown line item fields, and unknown payment fields trigger immediate fail-closed rejection.
- Line items require `tax_rate_id` to be explicitly declared as a key. Missing `tax_rate_id` throws `IntentProfileValidationError` and never silently defaults to implicit NoTax.

### 2. ExplicitNoTax Policy
- Line item `tax_rate_id` is nullable (`String?`).
- When `tax_rate_id == null`, `tax_rate` must be `"0.000000"` and `tax_inclusive` must be `false`.
- If `tax_rate_id == null` with a non-zero tax rate or `tax_inclusive == true`, construction and projection fail closed.
- If `tax_rate_id` is present, it must be a strict 26-character Crockford Base32 ULID starting with `[0-7]`. Verification of whether the ULID is active and belongs to the company tenant is authoritative on the Laravel backend (`account`) and is not proven client-side.

### 3. Packaging Conversion and Exact Scale
- `conversion_to_base`: strictly positive decimal lexeme with up to 14 integer digits and up to 6 decimal places (positive 6-scale).
- Quantities and packaging ratios maintain 6-decimal precision independent of base currency minor units.
- Tax rates maintain up to 6 decimal places (percentage 0..100) independent of base currency minor units.

### 4. Settlement Modes and Positive Grand Total Policy
- **First-Release Financial Policy**: Grand totals must be strictly positive (`grand_total > 0`). Zero or negative grand totals are rejected across all modes (`full`, `unpaid`, and offline cash). No zero-invoice or zero-receipt workarounds exist.
- **Settlement Modes**:
  * `unpaid`: `amount == "0"`, with omitted or explicit null cash fields (`payment_method: null`, `cash_account_id: null`). Normalizes explicitly to null in the intent profile, preserving the captured zero string lexeme (e.g. `"0.000"`).
  * `partial`: `0 < amount < grand_total`, requires `payment_method: "cash"` and a valid `cash_account_id`.
  * `full`: `amount == grand_total` (exact match). Excess payment is unallocated customer credit, not change, and is rejected under first-release policy.
- **Offline Cash Tender**:
  * `amount_tendered == amount_settled + change_returned`
  * `amount_settled == grand_total`
  * `mode == "full"`, `payment_method == "cash"`
  * Net retained settled amount is adapted to the canonical receipt (`receipt_amount == amount_settled`), preserving tender and change in a separate audit container.

### 5. Immutability and Defensive Copying
- All models are immutable Kotlin classes compatible with Android minSdk 24 (using `java.util.Collections.unmodifiableList` and `java.util.Calendar`/`GregorianCalendar` for RFC 3339 timestamps; zero `java.time` dependencies).
- Collections are defensively copied upon construction and `copy()` to prevent mutable source alias attacks.
- Exact validated string decimal lexemes and UTF-8 bytes are preserved.

## Unwired Limits and Project Boundaries

To maintain strict contract isolation and outbox state security, the following components are deliberately **NOT** modified or activated:
1. **Room Generic BLOB Storage Capability**: Existing Room generic offline storage is preserved as-is. No Room DAOs, repositories, entities, schemas, or migrations are modified or claimed for active v2 capture.
2. **Offline Mode**: Offline capture remains **OFF** in UI and runtime settings.
3. **No UI / ViewModels**: No UI screens, composables, or ViewModels are wired to v2 models in this phase.
4. **No HTTP / Transport**: No HTTP clients, worker daemons, or auth token managers are wired to v2 endpoints.
5. **Preservation of V1 Models**: All existing V1 contract models, queued bytes, digests, and UUID primitives in `com.example.data.mobile.contract` remain untouched and operational.
