# Startup Legacy Upgrade and Recovery Health Gate Evidence

Base: PR21 a57dcc54fd758e5b2f0da524602a59689a7d403f (qualified PR21 encrypted outbox slice).
Followup: PR22 e25d73bcf7ec2c808557b79ad95be93d26fcdacd and review 5897977595 resolution.

## Scope & Intent
This slice resolves GitHub PR 21 review `5895663783` and independent review `5897977595`:
1. **User-visible legacy upgrade and recovery health gate**:
   - Inspects outbox storage health off-main thread on startup (`OutboxHealthInspector`).
   - Categorizes queue state into typed, privacy-preserving statuses (10 distinct states):
     - `NEW_EMPTY`: Queue has zero records in scope. Ready for encrypted captures when encryption key capability is confirmed; zero key generation needed.
     - `AUTH_BLOCKED`: Queue is locked due to HTTP 401 authentication denial. Fails operational readiness even if queue is empty or records are healthy; no autounlock.
     - `ENCRYPTED_LOCKED`: Records are encrypted, but Keystore key is locked or permanently invalidated.
     - `KEY_UNAVAILABLE`: Dedicated outbox encryption key does not exist or keystore is unavailable.
     - `LEGACY_PLAINTEXT`: Queue contains unencrypted legacy payloads that require authorized recovery before processing.
     - `MIXED_QUEUE`: Queue contains conflicting record formats (mixed encrypted and plaintext) or invalid envelope versions.
     - `CORRUPT`: Storage records failed cryptographic authentication, digest checks, or exceeded maximum envelope size.
     - `STORAGE_UNAVAILABLE`: Local database file or Room storage could not be opened or queried.
     - `PARTIAL_UNCHECKED`: Queue contains unverified offline records beyond probe limit or was sampled during active scan mutation. Not operational.
     - `HEALTHY`: Entire scoped queue has been verified and authenticated across all pages. Ready for processing.
   - Never exposes foreign tenant identifiers (`company_id`, company name), foreign event counts, or client event UUIDs in error messages or UI.
   - Preserves all unmigrated/unrecoverable records on disk without deletion, key re-generation, or auto-unlock.
2. **Critical Trust Boundary & Recovery Invariants**:
   - Pre-existing Room v1 plaintext database has NO cryptographic installation provenance. Mere local staff login, current installation UUID, or a user checkbox is NOT proof of same-installation/owner recovery authority.
   - Conversion remains CLOSED: no invented server recovery grant, no "Convert" button that blindly blesses unverified plaintext, no auto-calling of `convertLegacyPayloads()`.
   - Exposes an explicit reviewed-recovery-required guidance screen (`StaffOutboxRecoveryScreen`) while preserving all records on disk.
   - Non-blocking read workspace: authorized staff workspace can navigate and browse product/customer catalogs in `READ_READY` phase without being blocked by unmigrated offline queues.
   - Safe retry is offered without key generation or destructive reset.
3. **Review 5897977595 Hardening**:
   - **Bounded Cancellable Paged Verification across ALL Scoped Rows**:
     - Resolves P1 stall where queues with 11+ valid records were permanently stuck in `PARTIAL_UNCHECKED`.
     - Implements cursor-based keyset pagination on `(captured_at, client_event_id)` in bounded chunks (`DEFAULT_PAGE_SIZE = 10`), avoiding oversized memory allocations.
     - Verifies 11/100+ clean rows, detects late corrupt/legacy rows, supports graceful cancellation mid-scan, and detects scan mutations (fails closed as `PARTIAL_UNCHECKED` when concurrent insertions occur, resuming to `HEALTHY` on subsequent retry).
   - **Global Key Capability Parity Across Tenants**:
     - Resolves P1 inconsistency where Company B claimed `NEW_EMPTY` (operational) in a database containing Company A's legacy records without a keystore key.
     - Since repository capture requires an existing retained key when `eventCount() > 0`, the inspector verifies `payloadCipher.prepare(true)` before granting `NEW_EMPTY`.
     - Preserves missing-key refusal with zero replacement key minting (`keyGenerationCount == 0`) and zero mutation of existing plaintext rows.
   - **Pre-Auth Derivation & Session Lifecycle**:
     - Derives authenticated immutable current ReadReady `(companyId, actorId, deviceId, generation)` before any inspector I/O.
     - Public/arbitrary invocations pre-auth execute ZERO inspector queries.
     - Stale asynchronous results are ignored across logout, company switch, and re-login.
     - Canceling company selector restores owned health warning and retry button without blocking catalog reads.
4. **Room Schema Integrity**:
   - Room v1 identity hash `6eddfecc8dabbb7ec370b858ec36714a` remains strictly intact with zero schema diff.

## Test Summary
- **Exact-source test inventory (Outbox & Staff Workspace)**: 15 suites, 165 tests (all passing source invariants).
  - `OutboxHealthGateTest`: 28 tests (including 11/100+ paged verification, late corrupt/legacy, scan mutation, cancellation, same-DB missing-key capture refusal).
  - `StaffOutboxRecoveryComposeTest`: 16 tests (including pre-auth zero-query gate, cancel company selection warning restoration, async generation barrier, catalog preservation).
  - Outbox storage & concurrency suites: 121 tests.
- **Project-wide inventory**: 58 suites, 617 tests.
- **Python Contract Validation**: 303 checks passed in normal mode; 303 checks passed in optimized (`-O`) mode.
- **Build / Execution Status**:
  - Backend PR95 currently owns the exclusive database/build lease.
  - Gradle compilation and Android instrumentation are not run in this source-ready turn until the supervisor grants the build lease.
  - Device/Keystore/upgrade/OEM remain **BLOCKED / UNVERIFIED** (no approved device host).

## Files in this Directory
- `summary.json`: Test suite names and updated test counts (15 suites, 165 tests).
- `python-normal.log`: Python mobile contract validator output (normal: 303 passed).
- `python-optimized.log`: Python mobile contract validator output (-O: 303 passed).
- `lint-results-debug.xml`: Android lint results baseline.
