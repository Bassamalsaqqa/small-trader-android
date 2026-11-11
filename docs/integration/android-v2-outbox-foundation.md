# Android Outbox Foundation V2 Durable Storage Specification

## 1. Scope Constraints & Unwired Holds

This integration specification defines the local-first, durable SQLite Room outbox storage baseline for Contract Revision 3 (API v2 / `event_version = 2`) offline sales events in `small-trader-android`.

### Scope Boundaries
- **In-Scope**:
  - Room persistent immutable economic event entity (`OutboxEventEntity`) storing exact captured UTF-8 JSON bytes, cryptographic SHA-256 digest (`PayloadDigest`), stable `event_version = 2`, and composite primary key `(company_id, client_event_id)`.
  - Preservation of Room schema Version 1 identity hash `6eddfecc8dabbb7ec370b858ec36714a` with strictly zero physical migration, zero destructive schema fallback, and zero fake upgrade.
  - Dedicated V2 capture entry points on `OutboxRepository`:
    - `captureSaleV2(companyId, actorId, deviceId, currencyMinorUnits, intakeRequest, rawUtf8Json)`
    - `captureSaleV2(companyId, actorId, deviceId, currencyMinorUnits, event, rawUtf8Json)`
    - `captureSaleV2(companyId, actorId, deviceId, currencyMinorUnits, rawUtf8Json)`
  - Explicit scope parameter guard (`companyId`, `actorId`, `deviceId`): mandatory validation matching the captured event identity before querying or disclosing any existing database rows (zero foreign scope data or digest leakage).
  - Mandatory qualified `currencyMinorUnits` context (in `0..6`): strictly rejects null, negative, or > 6; no fallback to 2 or currency guessing.
  - Money semantics and exact minor units qualification validated before database write:
    - Payment settled must be strictly positive (`amountSettled > 0`) and represent an integer count of minor units.
    - Cash tender conservation: `amountTendered == amountSettled + changeReturned`, with tendered and change conforming to `currencyMinorUnits` scale.
    - Sum of line totals must exactly equal `amountSettled`.
    - Each `lineTotal` and `taxAmount` must conform to `currencyMinorUnits` scale without fractions beyond minor units.
    - `quantity`, `unitPrice`, and `taxRate` preserve up to 6 decimal scale without silent rounding.
  - Exact byte identity: canonical UTF-8 JSON bytes stored verbatim in `payload_bytes` with defensive copying to eliminate mutable source aliasing.
  - Malformed UTF-8 rejection using `CodingErrorAction.REPORT` without silent replacement characters.
  - Strict JSON parsing: rejects unknown keys in envelope or event and rejects lines missing `tax_rate_id` before database write.
  - Unified entity identity spanning v1 and v2:
    - Same identity + matching payload digest: idempotent duplicate replay (existing record unmodified).
    - Same identity + altered version or bytes: scope-owned conflict, transitioning delivery status to `CONFLICT` without replacing or corrupting original event bytes.
    - Terminal `CONFIRMED` record immutability: altered captures (v1 or v2) can never demote a confirmed record from `CONFIRMED`.
    - Cross-scope collision (same company, foreign actor or device): generic rejection (`CaptureResult.ScopeRejected`) with zero existing digests or state leakage.
    - Cross-tenant isolation: identical `client_event_id` under a different `company_id` is completely independent and succeeds.
  - Persistent auth lock respect: captures occurring while auth is denied (`isScopeLocked`) are stored atomically as `AUTH_BLOCKED`, preserving cash and bytes while preventing worker drains; capture never unlocks auth.
  - Full participation of V2 stored records in existing claim (`claimNextBatch`), timeout (`recordTimeout`), delivery confirmation (`confirmDelivery`), and status reconciliation (`reconcileDeliveryConfirmation`) machinery without worker HTTP activation.
- **Explicitly Excluded & Unwired Holds**:
  - **No Live Capture Activation**: Offline sales policy remains unapproved and disabled (`enabled: false`).
  - **No UI / ViewModel Wiring**: Compose screens, `TraderViewModel`, and `TraderRepository` remain unwired to V2 capture.
  - **No HTTP / Network Transport**: No network calls, WorkManager registration, or remote background workers implemented in this layer.
  - **No Token / Master Proof Invention**: Typed V2 event validation does not pretend token/lease/master authorization proofs; store does not authorize cash sales or offline credit policy.

---

## 2. Room Schema Identity & Zero-Migration Baseline

The Room database schema is frozen at Version 1 and exported to `app/schemas/com.example.data.mobile.outbox.OutboxDatabase/1.json`:
- **Identity Hash**: `6eddfecc8dabbb7ec370b858ec36714a`
- **Schema Version**: `1`
- **Physical Migration**: Zero. `OutboxEventEntity` already includes `event_version INTEGER NOT NULL`. V2 events are persisted with `event_version = 2` within the existing table structure.
- **Destructive Fallback**: `fallbackToDestructiveMigration` is strictly forbidden.

---

## 3. Data Model & V2 Invariants

### Immutable Event Table (`outbox_events`)
- Composite Primary Key: `(company_id, client_event_id)`
- Fields: `company_id`, `client_event_id`, `actor_id`, `device_id`, `device_lease_id`, `captured_at`, `payload_digest`, `event_version`, `payload_bytes` (BLOB)
- Invariant: Once written, event bytes, digest, and version are permanently immutable.

### Delivery State Table (`outbox_delivery_states`)
- Composite Primary Key: `(company_id, client_event_id)`
- Foreign Key: references `outbox_events(company_id, client_event_id)` with `RESTRICT`
- Status machine: `pending`, `in_flight`, `unknown_ack`, `confirmed`, `conflict`, `auth_blocked`

### Scope Lock Table (`outbox_scope_locks`)
- Primary Key: `(company_id, actor_id, device_id)`
- Persists authentication denial across restarts even when the queue has zero records.

---

## 4. Verification Suite & Proof Boundaries

### Test Suite Summary
1. **`OutboxV2DurableStorageTest`** (13 tests):
   - Real disk-backed SQLite reopen byte preservation, version 2 verification, and digest parity.
   - Idempotent duplicate replay.
   - Cross-version same-ID conflict preservation (v1 vs v2, v2 vs v1).
   - Terminal `CONFIRMED` record immutability against altered capture demotion.
   - Cross-scope zero-leak generic rejection.
   - Cross-tenant UUID independence.
   - Empty queue persistent auth lock and atomic `AUTH_BLOCKED` capture.
   - ACID transaction failure full rollback with zero orphan state.
   - Currency overprecision rejection before database write (`lineTotal`, `taxAmount`, `amountSettled`, `amountTendered`, `changeReturned`).
   - Scope parameter mismatch rejection before database access.
   - Strict UTF-8 rejection of malformed byte sequences without replacement characters.
   - Timeout and status reconciliation preserving exact UUID and payload bytes without reserialization.
2. **Old Outbox Suite** (32 tests): All 32 legacy V1 outbox tests pass unmodified.
3. **Contract Suite** (88 tests): Canonical serialization, decimal rounding, identifier primitives, golden fixtures, and drift tests pass.
4. **Python Suite** (303 checks): `./tools/validate-mobile-contract.py` passes in both normal and `-O` modes.
5. **Android Lint**: `lintDebug` passes with 0 errors.

### Testing Proof Boundaries
> [!IMPORTANT]
> **Proof Clarification**: Passing tests verify local SQLite integrity, ACID transaction rollback, and disk reopen persistence in user-space. They do **not** constitute hardware power-loss proof, kernel-level process termination proof, or backend general ledger settlement proof (which belongs authoritatively to the central Laravel ledger).
