# Android Releases & Updates

Lean pilot release playbook for `com.aistudio.smalltrader.psdukx`. Defines procedure only — **not** approval to distribute, deploy, or activate live endpoints.

## 1. Current Identity & Outbox Upgrade Rules

- **Current Version (`app/build.gradle.kts`):** `versionName = "1.4.1"`, `versionCode = 7`, `applicationId = "com.aistudio.smalltrader.psdukx"`, default origin `https://account.palsync.net`. Signed release artifact lives in **private userdata (NOT published on GitHub)**. Prior `v1.4` (`versionCode = 6`, SHA-256 `4694e5fb4eec8d9623e00b1519a744224f8496397f020e61f6394814528471fe`) APK is **superseded** by `v1.4.1` (`versionCode = 7`) and must not be distributed after receipt currency/tender remediation.
- **Signing Incompatibility & Pending Outbox Rule:** The release signing certificate is **incompatible** with debug installs (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). **Preserve pending offline outbox records; NEVER uninstall, clear app data, or downgrade an installation holding pending outbox sales.** Always bump `versionCode` for every distributed build.
- **Independent Versioning:** Android, mobile API contract revision, and Laravel backend version independently; record tested compatibility externally in release notes rather than pinning self-referential HEAD SHAs inside source docs.

## 2. Six-Step Release Procedure

1. **Prepare:** Bump `versionCode` / `versionName` in `app/build.gradle.kts` on a clean reviewed commit (do not tag yet). Confirm default-OFF pilot flags (`staffReadWorkspace=false`, `offlineOwnerPilot=false`, `staffAllowHttpLoopback=false`).
2. **Verify:** Run JVM/Robolectric suite, lint, contract validator, and paired harness (record exact counts/skips):
   ```bash
   ./gradlew :app:testDebugUnitTest :app:lintDebug
   python3 tools/validate-mobile-contract.py && python3 -O tools/validate-mobile-contract.py
   ```
   *(Device/KVM emulator checks, hardware Keystore, TalkBack, IME, CameraX, and Bluetooth ESC/POS remain unverified until run on physical hardware.)*
3. **Build & Verify Signed APK:** Never ship `benchmark` or `-Psmalltrader.allowInsecureDebugSigningForRelease=true`. Supply owner keystore via env vars (`KEYSTORE_PATH` or `MYAPP_UPLOAD_STORE_FILE`, `STORE_PASSWORD`, `KEY_PASSWORD`; alias `upload`). `validateReleaseOrigin` enforces strict HTTPS `STAFF_WORKSPACE_ORIGIN`.
   ```bash
   ./gradlew :app:assembleRelease
   apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
   sha256sum app/build/outputs/apk/release/app-release.apk
   ```
4. **Upgrade Compatibility Check:** Install in place (`adb install -r`) over the previous release-signed pilot build on a test device; verify Room outbox v1->v2->v3 (`OutboxDatabase`, nullable `currency_minor_units`) records, auth locks, and pending sales survive intact without destructive fallback.
5. **Approve & Tag:** Present the release record below to the Owner. Only after approval, tag the verified commit (`v<version>`) and share the signed APK privately (never commit APKs to Git).
6. **Install In Place:** Distribute via private channel (`adb install -r app/build/outputs/apk/release/app-release.apk`). On defect, ship a higher `versionCode`; never uninstall pending data.

## 3. Release Record Template (External Pinning)

```text
Version / versionCode: <versionName> / <versionCode>
Source & Compatibility: Android <commit>, Backend <commit/release>, API contract <revision>
Checks: <unit/paired/lint counts & skips; device acceptance status>
Artifact: <APK SHA-256> / Signer: <cert SHA-256>
Approval & Distribution: <Owner decision and private channel>
```

## 4. Current Release Limits

- **No deployment occurred** and backend database **restore is NOT VERIFIED**.
- Mobile API & offline capture remain **OFF** by default; **mobile document discounts are NOT implemented** (future proposal only; nonzero discounts rejected in production); shift-close submission remains **blocked**; physical device/KVM acceptance remains **unverified**.
- References: [Android versioning](https://developer.android.com/studio/publish/versioning), [App signing](https://developer.android.com/studio/publish/app-signing), [apksigner](https://developer.android.com/tools/apksigner).
