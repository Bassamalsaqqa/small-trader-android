# Android releases and updates

Start with a signed APK shared privately with a small pilot group. Use ordinary
Git tags and GitHub release notes; no custom updater or release service is needed.
This document defines the procedure, not approval to release the current prototype.

## Version and identity

- Keep `applicationId = com.aistudio.smalltrader.psdukx` and the same release
  signing certificate for pilot updates. A different application ID installs a
  separate app with separate data; an incompatible certificate rejects an update
  to the same package. Do not uninstall or clear app data to fix an update.
- In `app/build.gradle.kts`, increase `versionCode` for every distributed APK,
  including pilot candidates. Set `versionName` to `MAJOR.MINOR.PATCH`, optionally
  with a suffix such as `-pilot.1`. Use the matching Git tag with a leading `v`.
  These are naming examples, not existing releases. Baseline main currently uses
  code `1` and name `1.0`; choose the first pilot version explicitly.
- Android, the mobile API contract revision, and Laravel have independent versions.
  Record their tested combination; matching version numbers are unnecessary.

## Six steps for each release

1. **Prepare.** Make the version bump in a reviewed PR. Obtain approval before
   merging. Select the exact clean source commit to build; do not tag it yet.
2. **Verify.** Run the applicable regression tests and lint, recording commands,
   actual counts, failures and skips for that commit:

   ```bash
   ./gradlew :app:testDebugUnitTest :app:lintDebug
   ```

   Require passing checks. The known `NativeVisualEvidenceTest.capture_16` idle
   hang remains unresolved; report any excluded checks explicitly. A partial
   suite is not a full visual pass. Complete EN/AR, RTL and real-device acceptance
   before a live merchant pilot.
3. **Build and check signing.** Use `release`, never the debug-signed `benchmark`
   variant or `smalltrader.allowInsecureDebugSigningForRelease=true`. Have the
   owner supply the existing approved keystore through a protected local secret
   mechanism. Do not put passwords in commands, Git, release notes or logs.
   The current Gradle script reads environment variables `KEYSTORE_PATH`
   (fallback `MYAPP_UPLOAD_STORE_FILE`), `STORE_PASSWORD` and `KEY_PASSWORD`;
   alias is `upload`. It does not read password project properties. With those
   variables supplied securely:

   ```bash
   ./gradlew :app:assembleRelease
   apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
   sha256sum app/build/outputs/apk/release/app-release.apk
   ```

   Confirm the application ID, version and signer against the previous pilot.
   Retain the exact verified APK; do not silently rebuild it for distribution.
4. **Check the update and compatibility.** On a test device with the previous
   pilot installed, install the candidate in place. Verify that every implemented
   persistent draft, pending sale and tenant boundary survives. Once durable sync
   is wired, include unknown-ack recovery and migration tests with queued sales.
   Verify both the previous pilot APK and candidate against the proposed backend.
   Do not claim preservation of currently in-memory demo state across restarts.
5. **Approve and tag.** Present the artifact record and short notes below to the
   owner. Only after release approval, tag the exact verified commit and publish
   its notes/artifact through the approved private channel. Keep APKs out of Git.
   Do not create tags, publish or deploy merely by following this document.
6. **Install in place.** Share the approved APK with the pilot group. For a
   controlled test-device install:

   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

   Stop distribution if verification fails. Deliver a fix with a higher
   `versionCode`; do not uninstall, clear data or force an APK downgrade.

## Short release record

Copy this into the release notes; use real evidence, not sample commit hashes:

```text
Version/tag and versionCode: <version> / <code>
Changes: <one or two useful sentences>
Known limitations and test results: <checks, skips, device evidence>
Artifact: <source SHA> / <APK SHA-256> / <signer certificate SHA-256>
Compatibility: <backend SHA/tag, API contract revision, previous pilot tested>
Approval and private distribution: <owner decision and channel>
```

## Current limits and backend coordination

At main `79c9bb348f7ad4840842aa077f1820f75745d2a0`, the app uses an in-memory demo
repository. Contract, exact-decimal, Room outbox and picker changes are separate
unmerged drafts (PRs 4–7). Auth, HTTP and background synchronization are unwired;
offline financial capture is disabled. The line-tax contract gap and device
acceptance remain release blockers. This playbook does not establish readiness.

Laravel releases follow its existing deployment SOP and controlled release
checklist. The [Laravel release addendum draft](https://github.com/Bassamalsaqqa/account/blob/t3/laravel-release-guidance/docs/RELEASING.md)
([PR 90](https://github.com/Bassamalsaqqa/account/pull/90)) passed document review at
`97a1493c469887f67c6594aac33dfeedd19d0e8d`; it remains an unmerged draft. Document
review does not verify release artifacts or authorize deployment. Preserve compatibility
with the previous pilot APK. A server
rollback requires checking the current database schema; never assume reverting
code also reverts schema safely. Pending mobile sales must retain their identity
and bytes through updates or forward fixes.

References: [Android versioning](https://developer.android.com/studio/publish/versioning),
[app signing](https://developer.android.com/studio/publish/app-signing),
[apksigner](https://developer.android.com/tools/apksigner).
