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

### Testing Proof Boundaries
> [!IMPORTANT]
> **Proof Clarification**: Passing tests verify local SQLite integrity, lease-bounded CAS isolation, and status reconciliation in user-space. They do **not** constitute hardware power-loss proof, kernel-level process termination proof, or central MariaDB ledger settlement proof (which belongs authoritatively to the central Laravel backend).
