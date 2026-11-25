> **HISTORICAL MILESTONE RECORD (075d5bc):** Point-in-time offline capture review correction. See [README.md](../../README.md) for current status.

# Offline capture review correction

Baseline: `075d5bc105a46987451bb720ad6f24119c831d65`, branch `t3/android-searchable-selectors-pr22`.

Changes are Android-only. The existing two untracked artifacts (`app/round2/`, `docs/HUDAV1_CANDIDATE_INTEGRATION.md`) are preserved. No migration version change: the existing queue revision DDL now runs in Room's open callback.

- Selected packaging/default-unit sale price takes precedence over base price; base-price fallback multiplies by exact conversion. The durable lease authority checks the selected unit, conversion and price. Restricted UI displays the leased price without an editor.
- Live browsing updates its in-memory product flow without replacing an existing lease/catalog snapshot. Explicit preparation remains the persisted catalog transition.
- Offline capture currently supports walk-in customers only. Review/payment navigation gates a registered selection before cash entry, retains that selection, and explains how to continue online.
- The verified backend membership code is `FORBIDDEN_TENANT_MISMATCH`. Scoped reads route it through durable denial notification/bootstrap invalidation; sync maps it separately from an ordinary permission 403 and persists the scope lock. Room scope locks make restricted bootstrap grants unusable and block capture across reopen without deleting cash. Sync does not delete the bootstrap file; its persisted scope lock is the revocation authority checked on restricted workspace open and every capture.
- Queue cache uses ConcurrentHashMap and summary cache uses volatile publication. Queue revision schema setup runs on database open. Connectivity callbacks marshal state changes onto Main and ignore callbacks after disposal. Legacy test/API pending-sale helpers use the bounded scoped queue reader.
- Production v1 capture cannot bypass lease authorization. Online-profile UUID collisions receive an explicit conflict message before attempting offline-profile parsing.

Verification uses offline Gradle, ActiveProcessorCount=2, heap 1536m and one worker. Initial compile succeeded; initial focused run: 3 tests, zero failures/errors/skips. The next affected run had 7 tests: 6 passed and 1 failed on the revision trigger under an outer INSERT OR REPLACE. That regression was corrected by update-before-conditional-insert trigger logic, installed atomically in the open callback. Final affected rerun results are recorded below. JUnit does not instrument assertion totals.

Private evidence is pinned outside Git in local operator state, including pre-correction JUnit and existing continuation logs before overwrite. These local private pins are evidence only, not runtime dependencies.

Remaining qualification: real production Compose composition with owner-pilot opt-in, device/Keystore and boot identity behavior, observer lifecycle under actual Android connectivity callbacks, and dedicated online/offline profile-collision qualification. JVM composition tests bypass production pilot/open and native Keystore gates; they do not establish device acceptance. This correction does not claim full-product release qualification. Feature remains OFF by default. No CI, push, merge, signing, backend DB, Hostinger, device, deployment or production-data action was performed.

Final trigger-fix rerun: 5 tests, 0 failures, 0 errors, 0 skips (2 production capture/customer cases, 1 Room migration case, 2 auth-lock preservation cases). The two changed scoped-read cases passed in the preceding run and were not repeated after the database-only correction. Production/source and JVM-test compilation succeeded. `git diff --check` passed. No lint/full-product suite, APK/signing build, backend reconciliation or device qualification was run.
