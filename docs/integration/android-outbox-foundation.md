# Android Durable Offline Sales Room Outbox Foundation

## 1. Scope & Architectural Boundaries

This integration foundation implements the local-first, durable SQLite Room outbox storage baseline for offline sales events in `small-trader-android`.

### Scope Constraints
- **In-Scope**:
  - Room persistent immutable economic event entity (`OutboxEventEntity`) storing exact captured UTF-8 JSON bytes, cryptographic SHA-256 digest (`PayloadDigest`), and multi-tenant identity.
  - Separate mutable delivery projection entity (`OutboxDeliveryStateEntity`) tracking lifecycle state, attempts, leases, and server acknowledgements.
  - Atomic capture transaction writing validated byte-preserved `OfflineSaleEvent` plus initial `PENDING` projection before returning success.
  - Strict immutability: captured events cannot be repriced, rekeyed, or deleted. Replays with identical digests return existing records idempotently; differing digests trigger a local `CONFLICT` state with no payload replacement.
  - Scoped reads and claims (`company_id`, `actor_id`, `device_id`), preventing cross-user or cross-tenant leakage across logouts.
  - Lease-bounded Compare-And-Set (CAS) claim mechanism preventing parallel drains across concurrent workers and recovering abandoned in-flight records upon lease expiry.
  - Timeout safety: timeouts transition records to `UNKNOWN_ACK` and never confirm prematurely.
  - Strict server confirmation: delivery confirmation requires verified server acknowledgement matching original `client_event_id`, `company_id`, and `payload_digest` with a valid 26-character Crockford Base32 server ULID.
  - Queue locking on HTTP 401 authentication denial (`AUTH_BLOCKED`), preserving all pending and conflict records without deletion.
  - Frozen exported Room schema Version 1 (`app/schemas/com.example.data.mobile.outbox.OutboxDatabase/1.json`) with no destructive migration fallback.
  - Real upgrade migration (`MIGRATION_1_2`) preserving all queued records without loss.
  - Comprehensive unit test suite covering local durability, disk reopen, rollback atomicity, replay, conflict, claim CAS, stale recovery, auth lock, tenant isolation, and schema upgrade.
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

## 3. Data Model: Separation of Immutable Payload & Mutable Delivery State

The outbox architecture separates immutable event bytes from mutable delivery state to ensure cryptographic digest integrity cannot be corrupted by delivery attempts:

### Table: `outbox_events` (Immutable Economic Event)
- `client_event_id` (TEXT, PK): Stable client RFC 4122 UUID.
- `company_id` (TEXT): Server Crockford ULID tenant identifier.
- `actor_id` (TEXT): Server Crockford ULID authenticated user.
- `device_id` (TEXT): Client UUID physical device binding.
- `device_lease_id` (TEXT): Client UUID device lease binding.
- `captured_at` (TEXT): ISO-8601 / RFC 3339 timestamp.
- `payload_digest` (TEXT): `sha256:<64 hex>` cryptographic hash.
- `payload_bytes` (BLOB): Exact raw UTF-8 canonical JSON bytes captured at time of sale.
- `event_version` (INTEGER): Schema version (default 1).
- **Indices**:
  - Unique index `index_outbox_events_company_event` on `(company_id, client_event_id)`.
  - Composite index `index_outbox_events_scope` on `(company_id, actor_id, device_id)`.

### Table: `outbox_delivery_states` (Mutable Delivery Projection)
- `client_event_id` (TEXT, PK): Foreign key referencing `outbox_events.client_event_id` (`ON DELETE RESTRICT, ON UPDATE RESTRICT`).
- `company_id` (TEXT): Matching tenant ULID.
- `actor_id` (TEXT): Matching user ULID.
- `device_id` (TEXT): Matching device UUID.
- `status` (TEXT): One of `pending`, `in_flight`, `unknown_ack`, `confirmed`, `conflict`, `auth_blocked`.
- `attempt_count` (INTEGER): Number of delivery attempts (starts at 0).
- `last_attempt_at` (INTEGER, NULLABLE): Epoch millis of last attempt.
- `claim_token` (TEXT, NULLABLE): Ephemeral UUID identifying the active drain worker.
- `claim_expires_at` (INTEGER, NULLABLE): Epoch millis when claim lease expires.
- `server_ulid` (TEXT, NULLABLE): Authoritative server entity ULID (e.g. invoice or sale ID).
- `server_acknowledged_at` (TEXT, NULLABLE): Timestamp of server confirmation.
- `error_message` (TEXT, NULLABLE): Reason for timeout, conflict, or auth denial.
- `created_at` (INTEGER): Local capture epoch millis.
- `updated_at` (INTEGER): Last state mutation epoch millis.
- **Indices**:
  - Composite index `index_outbox_states_scope_status` on `(company_id, actor_id, device_id, status)`.
  - Index `index_outbox_states_status_claim` on `(status, claim_expires_at)`.

---

## 4. Lifecycle State Machine & Transition Rules

```
                      +-------------------+
                      |   captureSale()   |
                      +---------+---------+
                                |
                                v
                      +-------------------+
                      |      PENDING      |<------------------+
                      +---------+---------+                   |
                                |                             |
                        claimNextBatch()                      |
                         (CAS lease)                          |
                                |                             |
                                v                             |
                      +-------------------+            unlockQueueAfterReauth()
                      |     IN_FLIGHT     |                   |
                      +----+---------+----+                   |
                           |         |                        |
         confirmDelivery() |         | recordTimeout()        |
     (verified server ack) |         | (network timeout)      |
                           |         |                        |
                           v         v                        |
            +--------------+   +-----+-------------+          |
            |  CONFIRMED   |   |    UNKNOWN_ACK    |          |
            +--------------+   +-----+-------------+          |
                                     |                        |
                                     | (lease expires,        |
                                     |  re-claimed)           |
                                     +------------------------+
                                     |
               lockQueueOnAuthDenied() (HTTP 401)
                                     |
                                     v
                           +-------------------+
                           |   AUTH_BLOCKED    |
                           +-------------------+

   [Special Path]
   captureSale(same client_event_id, differing digest)
                     |
                     v
             +---------------+
             |   CONFLICT    | (Original event payload preserved, NO replacement)
             +---------------+
```

### State Definitions
1. **`PENDING`**: Validated offline sale captured locally; waiting to be picked up by drain worker.
2. **`IN_FLIGHT`**: Claimed by a worker with an active lease duration (e.g., 30s) and unique `claim_token`.
3. **`UNKNOWN_ACK`**: Delivery attempted but network timed out or failed without a definitive server response. Eligible for re-claim once the lease expires.
4. **`CONFIRMED`**: Server returned verified response matching `client_event_id`, `company_id`, and `payload_digest`, with valid 26-char Crockford ULID `server_ulid`.
5. **`CONFLICT`**: Client re-presented existing `client_event_id` with an altered payload digest. The original event bytes remain unchanged; delivery state is marked `CONFLICT`.
6. **`AUTH_BLOCKED`**: Authentication was denied (HTTP 401). Queue is frozen to prevent token leakage or runaway retries until manager re-authentication occurs.

---

## 5. Claim CAS, Parallel Drain Prevention & Stale Lease Recovery

To prevent concurrent drain workers from sending duplicate HTTP requests:
1. **Compare-And-Set (CAS)**:
   ```sql
   UPDATE outbox_delivery_states
   SET status = 'in_flight',
       claim_token = :claimToken,
       claim_expires_at = :claimExpiresAt,
       attempt_count = attempt_count + 1,
       last_attempt_at = :nowMillis,
       updated_at = :nowMillis
   WHERE client_event_id = :clientEventId
     AND company_id = :companyId
     AND actor_id = :actorId
     AND device_id = :deviceId
     AND (
       status = 'pending'
       OR (status IN ('in_flight', 'unknown_ack') AND (claim_expires_at IS NULL OR claim_expires_at < :nowMillis))
     )
   ```
2. **Parallel Drain Prevention**: If Worker 1 claims a record, its status becomes `in_flight` and `claim_expires_at = now + leaseDuration`. A concurrent Worker 2 attempting to claim the same record matches 0 rows and receives an empty batch.
3. **Abandoned In-Flight Recovery**: If Worker 1 crashes or is killed mid-transmission, once `nowMillis > claim_expires_at`, Worker 2's query detects the expired lease and reclaims the record with a fresh `claim_token` and incremented `attempt_count`.

---

## 6. Multi-Tenant, Actor & Device Isolation

To satisfy data security and privacy requirements:
- Every query, claim, and confirmation operation is strictly filtered by `(company_id, actor_id, device_id)`.
- **No Cross-User Logout Leakage**: If Actor 1 logs out and Actor 2 logs in on the same physical device, Actor 2's claims and queries return zero records from Actor 1's queue. When Actor 1 logs back in, their pending records are intact and claimable.
- **No Cross-Tenant Contamination**: Records belonging to Company A are invisible and unclaimable by workers operating on Company B.

---

## 7. Authentication Denial & Queue Lock

When authentication fails at the network gate (HTTP 401):
- `lockQueueOnAuthDenied(companyId, actorId, deviceId, reason)` transitions all active records (`pending`, `in_flight`, `unknown_ack`) to `auth_blocked`.
- `conflict` and `confirmed` records are preserved untouched.
- No records are deleted or discarded.
- While locked, `claimNextBatch()` throws `QueueLockedException`.
- Once re-authenticated by an authorized manager, `unlockQueueAfterReauth()` transitions `auth_blocked` records back to `pending`, restoring the queue without data loss.

---

## 8. Durability, Backup Exclusions & Migration Strategy

### Room Configuration Guarantees
- **No Destructive Fallbacks**: `fallbackToDestructiveMigration()` is strictly omitted from `OutboxDatabase`. Schema mismatches fail-fast rather than dropping un-synchronized offline transactions.
- **Startup Safety**: Database open operations never delete, reset, or truncate tables.
- **Exported Schema**: Schema Version 1 is exported to `app/schemas/com.example.data.mobile.outbox.OutboxDatabase/1.json` for CI validation.

### Backup Exclusion Notice (Before Live Wiring)
> [!WARNING]
> **Android Auto Backup Exclusion Requirement**:
> The SQLite database file (`small_trader_outbox.db`) and its journal/WAL files (`small_trader_outbox.db-wal`, `small_trader_outbox.db-shm`) **must be explicitly excluded** from Android Auto Backup and Google Drive device transfer (`backup_rules.xml` and `data_extraction_rules.xml`) before live runtime wiring is activated.
>
> If outbox database files are backed up and restored to a different device or instance, restored `client_event_id`s could collide or leak cached economic state across physical devices.
>
> **Notice**: The current application manifest and backup rules have **not** been modified in this foundation PR and are **not claimed safe for live backup**. Backup exclusions must be wired alongside runtime sync activation.

### Upgrade Strategy
- Version 1 schema is frozen.
- Version 2 migration (`OutboxMigrations.MIGRATION_1_2`) demonstrates schema evolution by adding `routing_tag TEXT DEFAULT NULL` to `outbox_delivery_states`.
- Unit test `OutboxMigrationUpgradeTest` verifies that applying `MIGRATION_1_2` preserves all queued events and delivery projections with zero data loss.

---

## 9. Verification & Test Suite Summary

The foundation is covered by 19 targeted unit tests running under Robolectric 4.16.1 against real SQLite database instances:

| Test Class | Tests | Key Scenarios Covered |
| :--- | :--- | :--- |
| `OutboxDurabilityReopenTest` | 2 | Real disk-backed SQLite file persistence across `close()` and `reopen()`; verification that database open does not truncate existing queue records. |
| `OutboxTransactionRollbackTest` | 2 | Atomic transaction rollback on disk write failure / SQLite exception; foreign key constraint enforcement between delivery state and parent event. |
| `OutboxIdempotencyAndConflictTest` | 2 | Same-event lost-ACK replay returns `Duplicate` without mutation; altered payload digest flags `Conflict` without replacing original event bytes. |
| `OutboxClaimCasAndStaleRecoveryTest` | 3 | Compare-And-Set (CAS) claim prevents parallel drains across concurrent workers; expired lease recovery reclaims abandoned in-flight records; network timeout transitions to `UNKNOWN_ACK` without premature confirmation. |
| `OutboxConfirmationVerificationTest` | 5 | Valid server acknowledgement confirms record and sets server ULID; mismatched company ID rejected; tampered payload digest rejected; invalid server ULID rejected; events in `CONFLICT` state rejected from confirmation. |
| `OutboxTenantIsolationTest` | 3 | Cross-tenant read/claim isolation; cross-actor logout leakage prevention; device-level claim boundaries. |
| `OutboxAuthDenialPreservationTest` | 1 | HTTP 401 auth denial locks queue, moves active records to `AUTH_BLOCKED`, preserves pending and conflict records, and restores queue upon re-authentication. |
| `OutboxMigrationUpgradeTest` | 1 | Real SQLite schema migration from Version 1 to Version 2 preserving queued records intact without data loss. |

### Test Count Parity Summary
- **Outbox Unit Tests**: 19 passed.
- **Contract Unit Tests**: 33 passed (`BoundaryCompatibilityTest`, `CanonicalJsonSerializerTest`, `DecimalPrimitivesAndRoundingTest`, `IdentifierPrimitivesTest`, `OfflineSaleContractGoldenParityTest`).
- **Checkout & Lifecycle Unit Tests**: 8 passed (`MerchantCheckoutTest`, `MerchantSaleLifecycleTest`).
- **Total JVM Unit Tests**: **60 passed**.
- **Python Contract Validation Suite**: **120 checks passed** (`tools/validate-mobile-contract.py`).
- **Contract Manifest SHA-256**: `a8d04a130bdb0bd909a7cfabdde93fb6f90c96cc07dbedfbd01971bdb07c6733` (frozen and verified).
