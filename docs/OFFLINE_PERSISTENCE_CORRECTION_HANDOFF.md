# Offline persistence correction handoff

Baseline: `b44455ad9cf8c01918f7ac7bbb26c09b6d85f462` on `t3/android-searchable-selectors-pr22`.
Offline activation remains OFF. This correction is not release acceptance.

## Implemented

- Authenticated app composition explicitly supplies the SQLite lease store; production repository construction no longer defaults to an in-memory store.
- Lease/counter payloads use existing native AES-GCM crypto with company/actor/device/lease/version/completeness AAD and existing payload byte bounds. Plaintext legacy lease blobs fail closed; economic outbox rows are preserved.
- Complete catalog pages are encrypted and stored atomically with their completeness marker. An explicit coroutine operation accepts caller-supplied page/product limits, reports progress, and preserves the prior snapshot on cancellation or failure. Subsequent pages request the first page's version and reject mismatches; acquisition checks the returned lease version. Startup restores saved pages without a full catalog network preload.
- A Room transaction serializes lifetime-capacity checking, local numbering and immutable capture across repository/database instances. Confirmed and rejected cash consume lifetime capacity. The original same-process lease baseline is retained on repeated acquisition.
- Captured timestamps derive from lease issuance and elapsed realtime with the request-start anchor. An authenticated process-continuity marker deliberately invalidates lease use after process restart; it is not a reboot identifier. Expiry observed by the repository removes authorization, preventing a wall-clock rewind from reviving it. Saved catalog and economic records remain durable.
- Queue UI updates obtain one scoped snapshot on IO rather than three main-thread decrypt scans. Existing rejected-cash records and separate local/server identities are retained.

## Unfinished product and qualification gates

- Capability/network/acquisition/preparation product callers and their explicit owner-policy configuration are not fully wired. No new policy limits or activation were invented.
- Cross-process offline lease continuation is deliberately unavailable: restart requires online reacquisition. A reviewed OS boot-continuity mechanism and persisted counter-baseline reconciliation across reacquisition are still needed. Reacquisition may conservatively double-count server-confirmed local sales; it must not be represented as full lifetime-budget acceptance.
- Queue reads are a single IO scan, still unbounded over scoped history. SQL-bounded summaries/Flow and aggregate performance qualification remain outstanding.
- Public low-level `captureSaleV2` is still a storage API; callers must use the new lease transaction for policy enforcement. This is not a non-bypassable offline authorization boundary.
- Durable catalog capture provenance after concurrent browsing/refresh and completeness/blob corruption behavior still require independent review.
- Full acceptance coverage remains outstanding: production factory/Keystore disk composition, >50-product last-page restart, max-count-one confirmation, blob-limit corruption, in-flight logout/company switch, and ILS/JOD exact-tax/quantity cases. The focused tests do not establish all six original findings closed.
- Paired Laravel contract/full qualification, recovery/performance, physical-device and signing acceptance are not performed. No CI, push, merge, production/backend database, hosting, signing or device actions occurred.

## Verification

Final affected-source invocation: `:app:testDebugUnitTest --tests com.example.data.ProductionOfflineSaleTest` — BUILD SUCCESSFUL, 10 tests, 0 failures/errors/skips. Room main-thread queries are disabled in this suite. It covers two disk database instances contending for the last slot, simulated confirmed-state lifetime retention, encrypted metadata/scope tampering, expiry followed by rewind, and the existing offline capture/recovery cases.

Necessary regressions from the preceding invocation: `OutboxMigrationTest` — 1 passed; `OutboxV2DurableStorageTest` — 23 passed. These storage/migration paths were unaffected by the subsequent catalog-read guard and test-harness correction. They were not repeated.

Earlier runs exposed four outdated receipt assertions, then two Robolectric looper-harness failures after disabling main-thread queries. Assertions and the harness were corrected; the final affected suite above is green. The unchanged 108-test selection was not rerun. No assertion-count claim is made: JUnit reports test-case counts, not assertion totals.

All invocations used offline Gradle, one worker, a 1536 MiB Gradle heap and `ActiveProcessorCount=2`. Final invocation took 2m55s. Compilation passed; existing unrelated deprecation/opt-in warnings remain. No lint/static-analysis or device test claim is made. `git diff --check` passed. No migration files changed.

Transient local logs: `/tmp/offline-fix-gradle.log`, `/tmp/offline-fix-final-gradle.log`, `/tmp/offline-fix-verification.log`. Pre-existing untracked `app/round2/` and `docs/HUDAV1_CANDIDATE_INTEGRATION.md` were preserved and excluded from the commit.
