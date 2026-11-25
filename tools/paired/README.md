# Paired Android <-> Laravel Financial Harness

Synthetic-only paired verification connecting the Android client (`ProductionTraderRepository`, `OutboxRepositoryImpl`, `SaleSyncCoordinatorImpl`, `RestrictedOfflineWorkspace`, `SmallTraderReadClient`) to a disposable loopback Laravel HTTP instance over real HTTP (`OkHttpTransport`).

## Current Verification Status

- Verified **12 real sale vectors** across 3 companies (ILS exclusive/inclusive/no-tax, JOD inclusive/exclusive/no-tax), **6 backend domain reconciliations**, and foreign-tenant zero-leakage against Account's `d096` post-qualification runtime baseline (Account runtime business source unchanged since).
- Because `tools/paired/backend.php` defaults `PAIRED_BACKEND_SHA` to `220f5c78fcedcbd2b045d0e9100317a11da07504` and checks a clean tracked backend checkout, pass `PAIRED_BACKEND_SHA=<40-hex-sha>` explicitly for newer clean backend commits (e.g., `d096`).

## Invariants

- **Owned disposable MariaDB slot (`3342`):** Requires `PAIRED_OWNED_SLOT=3342`, `PAIRED_EXECUTE=synthetic-only`, `PHASE8_ALLOW_DISPOSABLE_DB=1`, `PHASE8_TEST_DB_HOST=127.0.0.1`, `PHASE8_TEST_DB_PORT=3342`. `boot.php` verifies `Tests\Support\EnforcesDisposableDatabaseOwnership` on every PHP request.
- **Process-local test flags:** `backend.php` passes explicit `MOBILE_*` flags and a per-run `PAIRED_HTTP_NONCE` without loading any checkout `.env`. Production defaults stay **OFF**.
- **Canonical fixtures & strict verify:** `fixture.php` creates master data via canonical Laravel actions/services; `verify.php` independently derives expected decimals and asserts stored snapshots (`mobile_event_receipts`, `sales_invoices`, `sales_invoice_lines`, `customer_payments`, `customer_payment_allocations`, `posting_batches`/`posting_lines`), remaining stock (`92.750000`/`1.000000`, `93.625000`, `96.000000`), zero foreign leakage, and all 6 domain reconciliations.
- **Private handoff & cleanup:** Temp dir (`0700`, `umask 0077`) holds `handoff.json`/`credentials.json` (`0600`) and `gradle.log`. By default `backend.php` drops the disposable schema and stops `127.0.0.1:18400` on exit; set `PAIRED_CLEANUP_PRIVATE=1` (or `always`) to remove the temp dir automatically.

## Guarded Run Command

Run only when slot `3342` is explicitly assigned:

```bash
PAIRED_OWNED_SLOT=3342 \
PAIRED_EXECUTE=synthetic-only \
PAIRED_BACKEND_ROOT=/path/to/account \
PAIRED_BACKEND_SHA=<40-hex-clean-backend-sha> \
PHASE8_ALLOW_DISPOSABLE_DB=1 \
PHASE8_TEST_DB_HOST=127.0.0.1 \
PHASE8_TEST_DB_PORT=3342 \
PHASE8_TEST_DB_USERNAME=<disposable-db-user> \
PHASE8_TEST_DB_PASSWORD=<disposable-db-pass> \
JAVA_HOME=/path/to/jdk-21 \
ANDROID_HOME=/path/to/android-sdk \
php tools/paired/backend.php
```

## Qualification Boundary

Injected JVM KeyStore backend, boot identity, and synthetic identity file are test seams; this harness does not establish hardware `AndroidKeyStore`, on-device `BOOT_COUNT`, physical process kill, or physical device acceptance.
