# Paired Android <-> Laravel Financial Harness

Synthetic-only paired verification harness connecting the Android repository (`ProductionTraderRepository`, `OutboxRepositoryImpl`, `SaleSyncCoordinatorImpl`, `RestrictedOfflineWorkspace`, `SmallTraderReadClient`) to a disposable loopback Laravel HTTP instance over real HTTP (`OkHttpTransport`).

## Invariants

- **Pinned clean backend**: `tools/paired/backend.php` refuses to run unless `PAIRED_BACKEND_ROOT` resolves to a clean git checkout at commit `220f5c78fcedcbd2b045d0e9100317a11da07504`.
- **Owned disposable MariaDB slot (`3342`)**: Requires `PAIRED_OWNED_SLOT=3342`, `PAIRED_EXECUTE=synthetic-only`, `PHASE8_ALLOW_DISPOSABLE_DB=1`, `PHASE8_TEST_DB_HOST=127.0.0.1`, and `PHASE8_TEST_DB_PORT=3342`. `boot.php` verifies `Tests\Support\EnforcesDisposableDatabaseOwnership` (including `PHASE8_DISPOSABLE_DB_PROOF` / `PHASE8_DISPOSABLE_DB_NONCE`) on every PHP request.
- **Process-local test-only flags**: `backend.php` passes explicit `MOBILE_*` flags and a per-run `PAIRED_HTTP_NONCE` (verified on `/paired-ready`) to child processes without loading any checkout `.env`. Production defaults stay off.
- **Private handoff and credentials**: `credentials.json`, `handoff.json`, `environment.json`, and `gradle.log` are written inside a `0700` temp directory (`umask 0077`) and never printed to stdout. `PairedProductionSaleTest` enforces non-symlink `0600` POSIX permissions on `handoff.json` and `credentials.json`.

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

## Cleanup & Private Evidence

- By default, `backend.php` drops the disposable MariaDB schema (`$schema->drop()`), stops the loopback HTTP server (`127.0.0.1:18400`), and prints `Private owned artifacts: <dir>` so private diagnostics (`gradle.log`) can be inspected without leaking secrets.
- Set `PAIRED_CLEANUP_PRIVATE=1` to automatically delete `<dir>` after a passing run (`PAIRED_CLEANUP_PRIVATE=always` deletes `<dir>` even on failure).
- If retaining artifacts for inspection, remove them afterwards with `rm -rf <dir>`.
