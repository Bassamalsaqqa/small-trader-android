# Paired Android <-> Laravel Financial Harness

Synthetic-only paired verification harness connecting the Android repository (`ProductionTraderRepository`, `OutboxRepositoryImpl`, `SaleSyncCoordinatorImpl`, `RestrictedOfflineWorkspace`, `SmallTraderReadClient`) to a disposable loopback Laravel HTTP instance over real HTTP (`OkHttpTransport`).

## Invariants

- **Pinned clean backend**: `tools/paired/backend.php`, `tools/paired/fixture.php`, and `PairedProductionSaleTest` default to backend commit `220f5c78fcedcbd2b045d0e9100317a11da07504` and require a clean tracked checkout (`git status --porcelain --untracked-files=no` empty). When a finalized backend qualification fix advances `HEAD`, pass the exact 40-character lowercase git SHA explicitly via `PAIRED_BACKEND_SHA=<40-hex-sha>` (empty, non-hex, branch, or `HEAD` values fail closed; silent drift or bypass is prohibited).
- **Owned disposable MariaDB slot (`3342`)**: Requires `PAIRED_OWNED_SLOT=3342`, `PAIRED_EXECUTE=synthetic-only`, `PHASE8_ALLOW_DISPOSABLE_DB=1`, `PHASE8_TEST_DB_HOST=127.0.0.1`, and `PHASE8_TEST_DB_PORT=3342`. `boot.php` verifies `Tests\Support\EnforcesDisposableDatabaseOwnership` (including `PHASE8_DISPOSABLE_DB_PROOF` / `PHASE8_DISPOSABLE_DB_NONCE`) on every PHP request.
- **Process-local test-only flags**: `backend.php` passes explicit `MOBILE_*` flags and a per-run `PAIRED_HTTP_NONCE` (verified on `/paired-ready`) to child processes without loading any checkout `.env`. Production defaults stay off.
- **Canonical master fixture setup**: `fixture.php` creates companies, customers, units, products, taxes, and opening stock through canonical Laravel actions/services (`CreateCompanyAction`, `CustomerCatalogService`, `ProductCatalogService`, `PostOpeningStockAction`, `SaveTaxRateAction`) with zero raw financial history writes.
- **Exact post-run financial, snapshot, stock, and reconciliation verification**: `verify.php` independently derives expected exact quantities and line/invoice/tax/discount/payment totals and asserts every vector against canonical stored snapshots (`mobile_event_receipts`, `sales_invoices`, `sales_invoice_lines`, `customer_payments`, `customer_payment_allocations`, `posting_batches`/`posting_lines`), remaining stock (`92.750000` main / `1.000000` lowstock ILS, `93.625000` JOD, `96.000000` ILS no-tax), zero cross-tenant leakage on the foreign tenant, and all 6 domain reconciliations.
- **Private handoff and credentials**: `credentials.json`, `handoff.json`, and `gradle.log` are written inside a `0700` temp directory (`umask 0077`) and never printed to stdout. `PairedProductionSaleTest` enforces non-symlink `0600` POSIX permissions on `handoff.json` and `credentials.json`.

## Guarded Run Command

Run only when slot `3342` is explicitly assigned to your run:

```bash
PAIRED_OWNED_SLOT=3342 \
PAIRED_EXECUTE=synthetic-only \
PAIRED_BACKEND_ROOT=/path/to/account \
PHASE8_ALLOW_DISPOSABLE_DB=1 \
PHASE8_TEST_DB_HOST=127.0.0.1 \
PHASE8_TEST_DB_PORT=3342 \
PHASE8_TEST_DB_USERNAME=<disposable-db-user> \
PHASE8_TEST_DB_PASSWORD=<disposable-db-pass> \
JAVA_HOME=/path/to/jdk-21 \
ANDROID_HOME=/path/to/android-sdk \
php tools/paired/backend.php
```

If backend `HEAD` has advanced to a finalized qualification fix SHA `<40-hex-sha>`, include `PAIRED_BACKEND_SHA=<40-hex-sha>` explicitly in the invocation environment.

## Cleanup & Private Evidence

- By default, `backend.php` drops the disposable MariaDB schema (`$schema->drop()`), stops the loopback HTTP server (`127.0.0.1:18400`), and prints `Private owned artifacts: <dir>` so private diagnostics (`gradle.log`) can be inspected without leaking secrets.
- Set `PAIRED_CLEANUP_PRIVATE=1` to automatically delete `<dir>` after a passing run (`PAIRED_CLEANUP_PRIVATE=always` deletes `<dir>` even on failure).
- If retaining artifacts for inspection, remove them afterwards with `rm -rf <dir>`.

## Qualification boundary

The injected JVM KeyStore backend, boot identity and synthetic identity file are test seams. This harness does not prove hardware AndroidKeyStore, on-device BOOT_COUNT, physical process death or device acceptance.
