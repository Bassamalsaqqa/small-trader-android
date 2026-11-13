# Startup Legacy Upgrade and Recovery Health Gate Evidence

Base: PR21 a57dcc54fd758e5b2f0da524602a59689a7d403f (qualified PR21 encrypted outbox slice).

## Scope & Intent
This slice resolves GitHub PR 21 review `5895663783`:
1. **User-visible legacy upgrade and recovery health gate**:
   - Inspects outbox storage health off-main thread on startup (`OutboxHealthInspector`).
   - Categorizes queue state into typed, privacy-preserving statuses: `HEALTHY`, `NEW_EMPTY`, `ENCRYPTED_LOCKED`, `KEY_UNAVAILABLE`, `CORRUPT`, `LEGACY_PLAINTEXT`, `MIXED_QUEUE`.
   - Never exposes foreign tenant identifiers (`company_id`, company name), foreign event counts, or client event UUIDs in error messages or UI.
   - Preserves all unmigrated/unrecoverable records on disk without deletion, key re-generation, or auto-unlock.
2. **Critical Trust Boundary**:
   - Pre-existing Room v1 plaintext database has NO cryptographic installation provenance. Mere local staff login, current installation UUID, or a user checkbox is NOT proof of same-installation/owner recovery authority.
   - Conversion remains CLOSED: no invented server recovery grant, no "Convert" button that blindly blesses unverified plaintext, no auto-calling of `convertLegacyPayloads()`.
   - Exposes an explicit reviewed-recovery-required guidance screen (`StaffOutboxRecoveryScreen`) while preserving all records on disk.
   - Honest integrity limits: large queues are sampled with a bounded probe (`sampleEventsForHealthProbe(limit = 10)`); does not perform unbounded full scans or claim whole-queue integrity from a sample probe.
   - Safe retry is offered without key generation or destructive reset.
3. **Room Schema Integrity**:
   - Room v1 identity hash `6eddfecc8dabbb7ec370b858ec36714a` remains strictly intact with zero schema diff.

## Test Summary
- **15 suites, 140 tests**: 140 passed, 0 failures, 0 errors, 0 skipped.
- **Python Contract Validation**: 303 checks passed in normal mode; 303 checks passed in optimized (`-O`) mode.
- **Android Lint**: 0 errors, 63 baseline warnings (`lintDebug`).
- **On-Device Status**: Real Android Keystore, device process kill, instrumentation, and actual transfer/restore remain **BLOCKED / UNVERIFIED** (no approved device host).

## Files in this Directory
- `summary.json`: Test suite names and counts.
- `python-normal.log`: Python mobile contract validator output (normal).
- `python-optimized.log`: Python mobile contract validator output (-O).
- `lint-results-debug.xml`: Android lint results (0 errors).
- `TEST-*.xml`: Individual JUnit test suite reports.
