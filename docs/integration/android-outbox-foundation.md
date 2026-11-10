# Android Durable Offline Sales Room Outbox Foundation

## 1. Scope & Architectural Boundaries

This integration foundation implements the local-first, durable SQLite Room outbox storage baseline for offline sales events in `small-trader-android`.

### Scope Constraints
- **In-Scope**:
  - Room persistent immutable economic event entity (`OutboxEventEntity`) storing exact captured UTF-8 JSON bytes, cryptographic SHA-256 digest (`PayloadDigest`), and composite primary key `(company_id, client_event_id)`.
  - Separate mutable delivery projection entity (`OutboxDeliveryStateEntity`) tracking lifecycle state, attempts, leases, and authoritative server entity mappings (`invoice_ulid`, `payment_ulid`, `server_ulid`).
  - Persisted explicit scope lock entity (`OutboxScopeLockEntity`) keyed by `(company_id, actor_id, device_id)`, ensuring queue locking on HTTP 401 authentication denial persists even when the queue has zero events, across disk reopens, and locks subsequent captures.
  - Atomic capture transaction writing validated byte-preserved `OfflineSaleEvent` plus initial delivery state (`PENDING`, or `AUTH_BLOCKED` if scope is locked).
  - Strict immutability and terminal state protection: captured events cannot be repriced, rekeyed, or deleted. Replays with identical digests return existing records idempotently; differing digests trigger a local `CONFLICT` state without replacing original event bytes. Confirmed records (`CONFIRMED`) can never be demoted by late timeouts, altered captures, or auth denials.
  - Scoped reads and mutations (`company_id`, `actor_id`, `device_id`): public `getRecord` requires full scope. Cross-tenant same-UUID captures succeed as isolated independent events; same-company cross-actor/device collisions return `CaptureResult.ScopeRejected` with zero data leakage.
  - Lease-bounded Compare-And-Set (CAS) claim mechanism with overflow-safe arithmetic and bounded parameters (batch size 1..100, lease 1..300s).
  - Timeout safety: `recordTimeout` requires `in_flight` status and matching `claimToken`, transitioning records to `UNKNOWN_ACK` and never demoting confirmed records.
  - Strict server confirmation: delivery confirmation via worker claim requires active lease and verified server acknowledgement matching original event and payload digest, storing both `invoice_ulid` and `payment_ulid`. Re-ack with identical mapping is an idempotent replay with no database mutation; conflicting mapping is rejected.
  - Explicit status reconciliation path: `reconcileDeliveryConfirmation` allows reconciling server confirmation without worker claim tokens from `in_flight` or `unknown_ack`.
  - Strict UTF-8 validation with `REPORT` error action rejecting malformed byte sequences without silent replacement characters; defensive copying of byte arrays to prevent mutable source aliasing.
  - Clock injection for deterministic, race-free tests without `Thread.sleep`.
  - Frozen exported Room schema Version 1 (`app/schemas/com.example.data.mobile.outbox.OutboxDatabase/1.json`) with no destructive migration fallback (`fallbackToDestructiveMigration` strictly omitted).
  - Focused unit test suite covering local durability, disk reopen, rollback atomicity, replay, conflict, claim CAS, stale recovery, auth lock, tenant isolation, and terminal state immutability.
- **Explicitly Excluded**:
  - No live sale capture activation.
  - No network sync transport or HTTP calls implemented in this layer.
  - No background worker / WorkManager registration.
  - No UI or ViewModel wiring (`TraderRepository`, `TraderViewModel`, Compose screens untouched).
  - No changes to `AndroidManifest.xml` or runtime auth token storage.
  - Offline sales policy remains disabled (`enabled: false`).

---

## 2. Authority & Durability Clarification

> [!IMPORTANT]
> **Laravel Authoritative Ledger Boundary**: The central Laravel backend (`account`) remains the sole and authoritative posting authority for accounting general ledgers, inventory lot tracking, and official fiscal invoices.
>
> The Room outbox implemented here provides **local client durability and state-machine guarantees only**. Passing tests verify local SQLite integrity, ACID transaction rollback, and disk reopen persistence. They do **not** constitute hardware power-loss proof, kernel-level process termination proof, or backend ledger settlement proof.

---

## 3. Data Model: Separation of Immutable Payload, Delivery State & Scope Lock

The outbox architecture separates immutable event bytes from mutable delivery state and persisted auth locks:

### Table: `outbox_events` (Immutable Economic Event)
- `company_id` (TEXT, PK): Server Crockford ULID tenant identifier.
- `client_event_id` (TEXT, PK): Stable client RFC 4122 UUID.
- `actor_id` (TEXT): Server Crockford ULID authenticated user.
- `device_id` (TEXT): Client UUID physical device binding.
- `device_lease_id` (TEXT): Client UUID device lease binding.
- `captured_at` (TEXT): ISO-8601 / RFC 3339 timestamp.
- `payload_digest` (TEXT): `sha256:<64 hex>` cryptographic hash.
- `payload_bytes` (BLOB): Exact raw UTF-8 canonical JSON bytes captured at time of sale.
- `event_version` (INTEGER): Schema version (default 1).
- **Primary Key**: Composite `(company_id, client_event_id)`.
- **Indices**:
  - Composite index `index_outbox_events_scope` on `(company_id, actor_id, device_id)`.

### Table: `outbox_delivery_states` (Mutable Delivery Projection)
- `company_id` (TEXT, PK): Matching tenant ULID.
- `client_event_id` (TEXT, PK): Foreign key referencing `outbox_events(company_id, client_event_id)` (`ON DELETE RESTRICT, ON UPDATE RESTRICT`).
- `actor_id` (TEXT): Matching user ULID.
- `device_id` (TEXT): Matching device UUID.
- `status` (TEXT): One of `pending`, `in_flight`, `unknown_ack`, `confirmed`, `conflict`, `auth_blocked`.
- `attempt_count` (INTEGER): Number of delivery attempts (starts at 0).
- `last_attempt_at` (INTEGER, NULLABLE): Epoch millis of last attempt.
- `claim_token` (TEXT, NULLABLE): Ephemeral UUID identifying the active drain worker.
- `claim_expires_at` (INTEGER, NULLABLE): Epoch millis when claim lease expires.
- `invoice_ulid` (TEXT, NULLABLE): Authoritative server sales invoice ULID.
- `payment_ulid` (TEXT, NULLABLE): Authoritative server customer payment ULID.
- `server_ulid` (TEXT, NULLABLE): Authoritative server entity ULID (alias for invoice ULID).
- `server_acknowledged_at` (TEXT, NULLABLE): Timestamp of server confirmation.
- `error_message` (TEXT, NULLABLE): Reason for timeout, conflict, or auth denial.
- `created_at` (INTEGER): Local capture epoch millis.
- `updated_at` (INTEGER): Last state mutation epoch millis.
- **Primary Key**: Composite `(company_id, client_event_id)`.
- **Indices**:
  - Composite index `index_outbox_states_scope_status` on `(company_id, actor_id, device_id, status)`.
  - Index `index_outbox_states_status_claim` on `(status, claim_expires_at)`.

### Table: `outbox_scope_locks` (Persisted Auth Denial Lock)
- `company_id` (TEXT, PK): Tenant identifier.
- `actor_id` (TEXT, PK): Authenticated user identifier.
- `device_id` (TEXT, PK): Physical device binding.
- `locked_at` (INTEGER): Timestamp when lock was engaged.
- `reason` (TEXT): Rejection / auth denial reason.
- **Primary Key**: Composite `(company_id, actor_id, device_id)`.

---

## 4. Lifecycle State Machine & Transition Rules

```
                      +-------------------+
                      |   captureSale()   |
                      +---------+---------+
                                |
             +------------------+------------------+
             |                                     |
    (Scope not locked)                      (Scope locked)
             |                                     |
             v                                     v
   +-------------------+                 +-------------------+
   |      PENDING      |                 |   AUTH_BLOCKED    |
   +---------+---------+                 +-------------------+
             |                                     ^
     claimNextBatch()                              |
      (CAS lease)                                  | lockQueueOnAuthDenied()
             |                                     | (HTTP 401)
             v                                     |
   +-------------------+                           |
   |     IN_FLIGHT     |---------------------------+
   +----+---------+----+                           |
        |         |                                |
        |         | recordTimeout()                |
        |         | (network timeout)              |
        |         v                                |
        |   +-------------+                        |
        |   | UNKNOWN_ACK |------------------------+
        |   +-----+-------+
        |         |
        |         | reconcileDeliveryConfirmation() / re-claim
        v         v
   +-------------------+
   |     CONFIRMED     | (TERMINAL: immutable mapping, rejects conflicts/timeouts)
   +-------------------+

   [Special Paths]
   captureSale(same client_event_id, differing digest) -> CONFLICT (TERMINAL)
   captureSale(same client_event_id, differing actor/device) -> ScopeRejected (NO leakage)
```

### State Definitions
1. **`PENDING`**: Validated offline sale captured locally; waiting to be picked up by drain worker.
2. **`IN_FLIGHT`**: Claimed by a worker with an active lease duration (1..300s) and unique `claim_token`.
3. **`UNKNOWN_ACK`**: Delivery attempted but network timed out or failed without a definitive server response. Eligible for re-claim once the lease expires.
4. **`CONFIRMED`**: Server returned verified response matching `client_event_id`, `company_id`, and `payload_digest`, with valid 26-char Crockford ULIDs for `invoice_ulid` and `payment_ulid`. Terminal state; cannot be altered or demoted.
5. **`CONFLICT`**: Client re-presented existing `client_event_id` with an altered payload digest. The original event bytes remain unchanged; delivery state is marked `CONFLICT`.
6. **`AUTH_BLOCKED`**: Authentication was denied (HTTP 401). Queue is frozen via persisted scope lock to prevent token leakage or runaway retries until manager re-authentication occurs.

---

## 5. Multi-Tenant, Actor & Device Scope Isolation

- **Composite Key Parity**: Event identity is `(company_id, client_event_id)`. Two distinct companies capturing with identical UUIDs are stored and managed completely independently without collision.
- **Public Query Scoping**: `getRecord(companyId, actorId, deviceId, clientEventId)` enforces that records can only be retrieved by the exact matching tenant, user, and device scope.
- **Cross-Scope Collision Safety**: If Actor 2 / Device 2 within Company A attempts to capture with a `client_event_id` already owned by Actor 1 / Device 1, the capture is rejected as `CaptureResult.ScopeRejected`. No existing record payload, bytes, or digest is leaked, and Actor 1's record remains in `PENDING` without being marked as `CONFLICT`.

---

## 6. Authentication Denial & Persisted Scope Lock

When authentication fails at the network gate (HTTP 401):
- `lockQueueOnAuthDenied(companyId, actorId, deviceId, reason)` transactionally inserts an `OutboxScopeLockEntity` record and transitions active records (`pending`, `in_flight`, `unknown_ack`) to `auth_blocked`.
- **Empty Queue Protection**: The lock row in `outbox_scope_locks` persists even if zero events were queued at the time of auth denial.
- **Disk Reopen Persistence**: The scope lock persists across database closure and process restart.
- **Post-Lock Captures**: Any capture attempted while the scope is locked is immediately assigned `status = AUTH_BLOCKED` and `errorMessage = "Captured while queue locked due to auth denial"`. The cash and canonical event bytes are safely preserved on disk, but worker drains are blocked.
- **Re-Authentication**: `unlockQueueAfterReauth(companyId, actorId, deviceId)` transactionally deletes the scope lock and restores all `auth_blocked` records to `pending`.

---

## 7. Server Confirmation & Exact Mapping Replay

- **Worker Confirmation**: `confirmDelivery` requires a non-blank `claimToken`, status `IN_FLIGHT`, and unexpired lease. Requires valid 26-character Crockford ULIDs for `invoice_ulid` and `payment_ulid`.
- **Exact Mapping Replay**: If an already-confirmed record is re-acknowledged with the exact same invoice and payment mappings, the method returns `true` idempotently with zero database mutation (timestamps and records remain unchanged).
- **Conflicting Mapping Rejection**: If an already-confirmed record receives a differing invoice or payment ULID, the confirmation is rejected with `InvalidAcknowledgementException`, strictly preserving the original server confirmation.
- **Status Reconciliation**: `reconcileDeliveryConfirmation` provides an explicit separate path for polling status queries from `in_flight` or `unknown_ack` without requiring a worker claim token.

---

## 8. Payload Integrity, Strict Decoding & Clock Injection

- **Strict UTF-8 Decoding**: `captureSale` uses a `CharsetDecoder` configured with `CodingErrorAction.REPORT` for both malformed input and unmappable characters. Any invalid byte sequence throws `IllegalArgumentException` without silent Unicode replacement character (`\uFFFD`) substitution.
- **Defensive Byte Copying**: Byte arrays (`rawUtf8Json`, `payloadBytes`) are cloned defensively upon capture, storage, and retrieval to prevent caller mutation from altering canonical digests.
- **Privacy Enforcement**: Validation error messages identify events solely by `client_event_id` and never log sensitive prices, line items, or customer details.
- **Deterministic Test Clock**: `OutboxRepositoryImpl` accepts an optional `clock: () -> Long` parameter (defaulting to system time), allowing deterministic time manipulation in tests without `Thread.sleep` races.

---

## 9. Durability & Schema Strategy

- **Unreleased PR Initial Version 1**: This PR establishes the baseline Version 1 schema (`app/schemas/com.example.data.mobile.outbox.OutboxDatabase/1.json`, identity hash `6eddfecc8dabbb7ec370b858ec36714a`). No deployed schema has been mutated.
- **Fail-Closed Future Upgrades**: `fallbackToDestructiveMigration()` is strictly omitted. Any unhandled future schema change must fail fast rather than silently dropping un-synced economic events. All future migrations will be gated by real schema version increments and migration scripts.
- **Startup Safety**: Database initialization never drops or resets queue tables.

---

## 10. Verification & Test Suite Summary

The foundation is covered by 31 focused unit tests running under Robolectric 4.16.1 against real SQLite database instances:

| Test Class | Tests | Key Scenarios Covered |
| :--- | :--- | :--- |
| `OutboxDurabilityReopenTest` | 2 | Real disk-backed SQLite file persistence across `close()` and `reopen()`; verification that database open does not truncate existing queue records. |
| `OutboxTransactionRollbackTest` | 2 | Atomic transaction rollback on disk write failure / SQLite exception; composite foreign key constraint enforcement between delivery state and parent event. |
| `OutboxIdempotencyAndConflictTest` | 5 | Same-event lost-ACK replay returns `Duplicate` without mutation; altered payload digest flags `Conflict` without replacing original event bytes; strict UTF-8 rejection of malformed bytes; defensive byte copying; privacy check avoiding sensitive payload logging. |
| `OutboxClaimCasAndStaleRecoveryTest` | 5 | Compare-And-Set (CAS) claim prevents parallel drains across concurrent workers; deterministic stale lease recovery without `Thread.sleep`; timeout transitions to `UNKNOWN_ACK`; late confirmation with stale claim token is rejected; batch/lease boundary and overflow validation. |
| `OutboxConfirmationVerificationTest` | 10 | Valid server acknowledgement confirms record and sets invoice/payment ULIDs; mismatched company ID rejected; tampered payload digest rejected; invalid server ULID rejected; events in `CONFLICT` state rejected; unclaimed `PENDING`/`AUTH_BLOCKED` states rejected; confirmed record exact mapping replay preserves original without mutation; conflicting mapping rejected; confirmed record cannot be demoted by late timeout, conflict, or auth denial; explicit status reconciliation path. |
| `OutboxTenantIsolationTest` | 5 | Cross-tenant read/claim isolation; cross-actor logout leakage prevention; device-level claim boundaries; cross-tenant same UUID collision succeeds independently; same-tenant other actor/device collision rejected with zero data leakage. |
| `OutboxAuthDenialPreservationTest` | 2 | HTTP 401 auth denial locks queue, moves active records to `AUTH_BLOCKED`, preserves pending and conflict records, and restores queue upon re-authentication; persisted empty-queue auth lock across disk reopen and subsequent capture. |

### Test Count Parity Summary
- **Outbox Unit Tests**: **31 passed** (0 failures, 0 errors, 0 skipped).
- **Contract Unit Tests**: **33 passed** (`BoundaryCompatibilityTest`, `CanonicalJsonSerializerTest`, `DecimalPrimitivesAndRoundingTest`, `IdentifierPrimitivesTest`, `OfflineSaleContractGoldenParityTest`).
- **Checkout & Lifecycle Unit Tests**: **8 passed** (`MerchantCheckoutTest`, `MerchantSaleLifecycleTest`).
- **Total Selected Unit Tests**: **72 passed**.
- **Lint**: `lintDebug` passed (BUILD SUCCESSFUL).
- **Python Contract Validation Suite**: **120 checks passed** (`tools/validate-mobile-contract.py`).
- **Contract Manifest SHA-256**: `a8d04a130bdb0bd909a7cfabdde93fb6f90c96cc07dbedfbd01971bdb07c6733` (frozen and verified).
