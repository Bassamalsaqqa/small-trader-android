# Android Sync Coordinator Foundation Specification

## 1. Scope Constraints & Unwired Holds

This specification defines the local orchestration sync-coordinator baseline for Contract Revision 3 (API v2 / `event_version = 2`) offline sales events in `small-trader-android`.

### Scope Boundaries
- **In-Scope**:
  - Pure Kotlin sync coordinator interface and implementation (`SaleSyncCoordinator`, `SaleSyncCoordinatorImpl`).
  - Typed synchronization gateway interface (`SaleSyncGateway`) and typed outcome contracts (`SyncStatusOutcome`, `SyncSubmitOutcome`, `ServerSaleAck`) modeling the proposed v2 synchronization API without inventing mock HTTP status codes.
  - Coordination against existing SQLite Room `OutboxRepository` using lease-bounded Compare-And-Set (`claimNextBatch`) across qualified `(company_id, actor_id, device_id)` tenant/user/device scope.
  - Bounded batch processing in `1..100` (conforming to `OutboxRepository.MAX_BATCH_SIZE`).
  - Concurrent coordinator protection: CAS claims ensure two concurrent coordinators cannot drain or submit the same claimed record.
  - Mandatory gateway status reconciliation **BEFORE** any submit for each claimed record (including `UNKNOWN_ACK` records recovering from timeouts).
  - Authoritative server receipt verification: for `Posted` status, strictly validates exact `companyId`, `clientEventId`, `payloadDigest`, valid Crockford ULIDs for `invoiceUlid` and `paymentUlid`, and non-blank `serverAcknowledgedAt` prior to confirming delivery. Malformed or mismatched server acknowledgements are never confirmed and original payloads are never replaced.
  - Canonical bare event submission: for `NotFound` status, submits **ONLY** the original canonical bare event bytes (`event.payloadBytes.clone()`), matching `clientEventId` and canonical `payloadDigest`. Zero local draft, token, or receipt creation; zero local repricing or rekeying. Outer envelope construction remains the responsibility of future network adapters; the coordinator never reserializes event models.
  - Legacy V1 event protection: legacy `event_version != 2` records are parked via `markClaimConflict` with an explicit diagnostic reason. Zero silent upgrades and zero submission of V1 records to V2 endpoints.
  - 401 Authentication denial policy: on `AuthDenied`, executes `lockQueueOnAuthDenied` across the entire `(company_id, actor_id, device_id)` scope (persisting across restarts even on empty queues), preserves existing states and payload bytes, and terminates the pass immediately. Capture never unlocks auth, and no automatic unlock or logout deletion is performed.
  - 403 Membership denial policy: on `MembershipDenied`, fails closed, marks the claimed record as `CONFLICT` via claim-bound CAS, and halts further pass execution without retry loops.
  - 409 Business conflict policy: on `BusinessConflict`, marks the owned in-flight claimed record as `CONFLICT` via narrow, claim-bound `markClaimConflict(companyId, actorId, deviceId, clientEventId, claimToken, reason)` enforcing scope and lease CAS. Terminal `CONFIRMED` records are immutable and can never be demoted.
  - Transient network failure / timeout policy: moves in-flight record to `UNKNOWN_ACK` via `recordTimeout` with verified claim token, and stops pass without in-turn repeated retries.
  - Cancellation / interruption policy: if cancellation occurs while a gateway request may be in flight, records `UNKNOWN_ACK` via `recordTimeout` before propagating the fatal `CancellationException` / `InterruptedException` without swallowing fatal errors.
  - Defensive copying: all payload byte arrays are defensively cloned (`clone()`) at submission and entry to prevent mutable source aliasing.
  - Redaction & safety: all exception and diagnostic log strings redact potential credentials and tokens, preventing customer PII, tokens, lines, or digest disclosure across scopes.
  - Minimal outbox repository API addition: narrow `markClaimConflict` public method on `OutboxRepository` and `OutboxDao` with scope + lease CAS. Zero physical Room schema change, zero migration change.

- **Explicitly Excluded & Unwired Holds**:
  - **No HTTP / Network Implementation**: Zero actual HTTP client, Retrofit/OkHttp v2 route call, or remote network execution. Gateway interface is injected; test suites use in-memory fake gateways.
  - **No Background Worker / WorkManager**: No WorkManager worker, job scheduler, or background daemon registration.
  - **No UI / ViewModel Activation**: Jetpack Compose screens, `TraderViewModel`, and `TraderRepository` remain unwired to the coordinator.
  - **No Token Persistence / Master Authorization Proofs**: Gateway fresh server checks are authoritative caller context, not cryptographic proofs; offline lease/caps/business approval are not encoded as fake defaults.
  - **No Financial Math Duplication**: Model, bytes, and receipt mappings only; ledger arithmetic remains authoritative on the central Laravel backend.

---

## 2. Room Schema Identity & Zero-Migration Baseline

The Room database schema is frozen at Version 1 and exported to `app/schemas/com.example.data.mobile.outbox.OutboxDatabase/1.json`:
- **Identity Hash**: `6eddfecc8dabbb7ec370b858ec36714a`
- **Schema Version**: `1`
- **Physical Migration**: Zero. `OutboxEventEntity`, `OutboxDeliveryStateEntity`, and `OutboxScopeLockEntity` are unaltered.
- **DAO Invariant**: `OutboxDao.markClaimConflict` is a scoped query operating on existing table columns (`company_id`, `client_event_id`, `actor_id`, `device_id`, `claim_token`, `status = 'in_flight'`).
- **Destructive Fallback**: `fallbackToDestructiveMigration` is strictly forbidden.

---

## 3. Architecture & Lifecycle State Flow

```
                      +-------------------+
                      |   Claim Batch     |
                      | (Lease CAS 1..100)|
                      +---------+---------+
                                |
             +------------------+------------------+
             |                                     |
   [event_version == 2]                  [event_version != 2]
             |                                     |
+------------v------------+              +---------v---------+
|  Gateway checkStatus()  |              | Park as CONFLICT  |
+------------+------------+              |  (No v1->v2 post) |
             |                           +-------------------+
   +---------+---------+-------------------+-------------------+
   |                   |                   |                   |
[Posted]          [NotFound]       [BusinessConflict]    [AuthDenied /
   |                   |                   |          MembershipDenied]
(Validate Ack)  (submitSale:               |                   |
   |             Original Bytes)     (markClaimConflict) (lockQueue /
(confirmDelivery)      |                                  markConflict)
                       |
             +---------+---------+-------------------+
             |                   |                   |
         [Posted]       [BusinessConflict]     [TransientUnknown]
             |                   |                   |
       (Validate Ack             |             (recordTimeout ->
     & confirmDelivery)  (markClaimConflict)     UNKNOWN_ACK)
```

---

## 4. Verification Suite & Proof Boundaries

### Test Suite Summary
1. **`SaleSyncCoordinatorTest`** (12 tests):
   - Real disk-backed SQLite persist capture v2 -> claim -> status posted confirm.
   - Lost ACK after server post -> reopen -> status replay confirms one canonical server effect (clearly labeled fake counter).
   - NotFound status triggers submission of exact canonical bare event bytes without local repricing or rekeying.
   - Altered server digest, company ID, event UUID, invoice ULID, or payment ULID strictly rejected without confirmation or payload corruption.
   - 401 AuthDenied locks queue across scope, blocks subsequent claims, and preserves records and byte payloads intact.
   - Concurrent coordinators with overlapping leases: lease CAS ensures zero parallel drains and exactly 1 submit.
   - Stale lease recovery: late ACK from expired claim cannot confirm or corrupt active claim held by new worker.
   - 409 BusinessConflict transitions delivery state to `CONFLICT` while preserving original event bytes untouched.
   - Legacy V1 records parked as `CONFLICT` without network calls or silent upgrade.
   - Injected cancellation during in-flight gateway call transitions state to `UNKNOWN_ACK` before rethrowing fatal exception without swallowing.
   - 403 MembershipDenied fails closed without retry loops.
   - Defensive byte cloning prevents malicious or buggy gateway mutations from corrupting stored bytes.
2. **Old Outbox Suite** (32 tests): All legacy outbox tests pass unmodified.
3. **Outbox V2 Durable Storage Suite** (23 tests): All 23 tests pass unmodified.
4. **Contract Suite** (88 tests): Canonical serialization, decimal rounding, identifier primitives, golden fixtures, and drift tests pass unmodified.
5. **Merchant Purchase Accounting Suite** (11 tests): Passes unmodified.
6. **Python Suite** (303 checks): `./tools/validate-mobile-contract.py` passes in both normal and `-O` modes.
7. **Android Lint**: `lintDebug` passes with 0 errors.

---

## 5. Hardening Guarantees & Seven Review Findings

### 1. Discard Untrusted Gateway Reasons & DTO Leak Prevention
- All untrusted gateway error messages, reasons, and exception messages are completely discarded.
- Fixed local constants (`SyncDiagnostics`) are used for persisted state, scope lock reasons, error messages, and sync result summaries:
  - `SyncDiagnostics.BUSINESS_CONFLICT`
  - `SyncDiagnostics.MEMBERSHIP_DENIED`
  - `SyncDiagnostics.AUTH_DENIED`
  - `SyncDiagnostics.INVALID_SERVER_ACK`
  - `SyncDiagnostics.DIGEST_CORRUPTED`
  - `SyncDiagnostics.LEGACY_EVENT_VERSION`
  - `SyncDiagnostics.TRANSIENT_GATEWAY_ERROR`
  - `SyncDiagnostics.TASK_CANCELLED`
- Typed `GatewayDiagnosticCategory` metadata is preserved for caller routing while custom safe `toString()` overrides on `SyncStatusOutcome` and `SyncSubmitOutcome` prevent secret leakage from raw DTO representations.

### 2. Fatal Error Propagation & Interruption Lifecycle
- Coordinator catches specific `CancellationException` and `InterruptedException` alongside general `Exception`.
- Fatal JVM Errors (`OutOfMemoryError`, `LinkageError`, `AssertionError`, `ThreadDeath`) are never caught or suppressed, propagating immediately without heavy Room/SQLite cleanup.
- Thread interrupt status (`Thread.currentThread().interrupt()`) is explicitly restored after best-effort local DB cleanup.

### 3. Pre-Dispatch Scoped Re-Read & Backend Idempotency Boundary
- Before **EVERY** network dispatch (`checkStatus` and especially `submitSale`), the coordinator re-reads the scoped record from the database to verify:
  1. Record delivery state status is still `IN_FLIGHT`.
  2. Record claim token matches the coordinator's current `claimToken`.
  3. Claim lease has not expired (`claimExpiresAt > clock()`).
  4. Persisted scope lock is not active (`isQueueLocked == false`).
- Stale or expired claims are suppressed from dispatch.
- **Race Between Guard and Wire (Distributed Idempotency Notice)**: Local CAS eliminates client-side double dispatch races up to the moment of wire transmission. However, between the pre-dispatch guard check and actual HTTP transmission/receipt, network delays or process freezes may still occur. Therefore, central backend idempotency (via unique `client_event_id` and idempotent ledger posting) remains strictly required; local CAS cannot unilaterally promise exactly-once execution across distributed networks.

### 4. Early Batch Stop & Narrow CAS Batch Cleanup
- On early batch termination (transient error, auth denial, membership denial, cancellation), remaining owned in-flight records in the batch are safely released back to `pending` via `releaseClaimedBatch(companyId, actorId, deviceId, claimToken)` using narrow CAS.
- Records claimed by another worker, confirmed, parked, or auth-blocked are never touched or overwritten.
- Legacy V1 events and malformed UUIDs are safely parked via narrow raw-string key scope+token guarded `parkClaimConflict`.

### 5. Strict RFC 3339 / ISO 8601 Server Acknowledged At Validation
- `Rfc3339Validator` provides strict, calendar-accurate validation compatible with Android API 24+ without relying on Java 8 `java.time` desugaring.
- Calendar bounds `[2020..2100]`, non-lenient month/day validation (rejecting invalid dates like Feb 30/31), and full timezone offset support (`Z`, `z`, `[+-]HH:MM`) conform to RFC 3339 without improperly restricting the server to UTC-only.
- Invalid timestamps fail closed via `UNKNOWN_ACK` without storing false confirmations or corrupting immutable bytes, enabling clean recovery on subsequent passes.

### 6. Raw Payload Digest Integrity Guard
- Before checking status or dispatching submission, the coordinator recomputes the SHA-256 digest of the raw byte payload (`HashUtils.sha256Hex(record.event.payloadBytes)`).
- If the computed digest does not match the stored `payloadDigest`, the corrupted record is immediately parked as `CONFLICT` with zero network submission, preserving the immutable original bytes for forensic investigation.

### 7. Accurate Metrics & Testing Proof Boundaries
- Outcome metrics (`confirmedCount`, `conflictCount`, `timeoutCount`) increment strictly upon successful repository CAS transitions.
- Testing boundaries: Fake gateways and atomic counters model local state invariants and single canonical server effects in test; they do NOT constitute network token authorization proofs, hardware power-loss tolerance, or MariaDB distributed ledger guarantees.

---

### Testing Proof Boundaries
> [!IMPORTANT]
> **Proof Clarification**: Passing tests verify local SQLite integrity, lease-bounded CAS isolation, and status reconciliation in user-space. They do **not** constitute hardware power-loss proof, kernel-level process termination proof, or central MariaDB ledger settlement proof (which belongs authoritatively to the central Laravel backend).

