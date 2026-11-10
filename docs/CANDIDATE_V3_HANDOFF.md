# Android review candidate handoff

PR: https://github.com/Bassamalsaqqa/small-trader-android/pull/3 (draft, targets main).
Active checkout: `/home/t3dev/projects/small-trader-android`, branch `ux/stitch-native-prototype`.
Candidate source revision: `9ed37778c7bee4a535944e7f7e2a3ee54086db72` (reviewed and verified).

## Review & Candidate APK Artifacts

- **Review Benchmark APK:** `app/round2/validation/small-trader-review-9ed3777.apk`
  - Build variant: `benchmark` (R8 minified, resource shrunk, Android debug certificate).
  - Source Commit: `9ed37778c7bee4a535944e7f7e2a3ee54086db72`
  - Size: 2,132,291 bytes (~2.03 MB / 2.1 MB).
  - SHA-256: `8c62f4a68017f5b2eef6603256d92c8397a8c7c815fb63ad5771004b4f8fde2c`.
  - AndroidManifest SHA-256: `cec768c60a8942c2b662bfd0e18f76d873128bb19df1cd483ae8d0c4e0aeb5de`.
  - classes.dex SHA-256: `925858256480f95435c88920c270e9f61da653bbadb4f9f124e0bb78efe464c7`.
  - Build mirror: `app/build/outputs/apk/benchmark/app-benchmark.apk`.
- **Preserved Previous Review APK:** `app/round2/validation/small-trader-review-benchmark.apk`
  - Source Commit: `923902435b0603f99357ebeb520f24807863c49a`
  - Size: 2,132,291 bytes.
  - SHA-256: `bf70833ee6f34cbd35e400e21192cca7607018df349cd2e91269e5b7ad344d91`.
  - classes.dex SHA-256: `08ef3771c9f39b388e04bd1d90e051ea66029bf719d59e748983ed5ba67c94d9`.
- **Preserved Baseline APK:** `app/round2/validation/small-trader-candidate-benchmark.apk`
  - Source Commit: `df4935c6d21db524652f8114baf0693d7b39f1a2`
  - Size: 2,132,291 bytes.
  - SHA-256: `07907c8c84c35eba03a0f3c9524eb8f0ac2455708ace685222ca13548409f9cb`.
  - classes.dex SHA-256: `5c3f4606df5510054040d93ac1776a1ffdba0e5cad7d6e6f1d2a56d2328ce802`.
- **Toolchain Verified:** OpenJDK Temurin 21.0.13+9, Gradle 9.3.1, AGP 9.1.1, compileSdk 36.1, minSdk 24, targetSdk 36.

## Current local verification

Saved XML reports in `app/build/test-results/testDebugUnitTest/`: **140 tests total: 139 passed, 1 skipped, 0 failures/errors**. Includes 11 dedicated accounting regression tests in `MerchantPurchaseAccountingRegressionTest.kt` verifying mandatory line identity, multi-line same-SKU selection, disabled Confirm on fully received lines, idempotent replay conflict protection, duplicate SKU pricing, read-only derived totals, coherent draft preservation, zero-cost unit pricing, cent rounding, and actual `MainActivity` recreation. Lint debug completed successfully with 0 errors. Navigation captures and assertions cover 360dp/393dp, font scales 1/1.5/2, EN/AR, light/dark. Captures are under `app/round2/validation/native-renders-v03/`; these are Robolectric evidence, not device acceptance.

See [review remediation](PR3_REVIEW_REMEDIATION.md) for test commands, search harness results and per-finding limitations. Search measurements are host JVM measurements, not Android latency. APK sizes from different variants do not establish causal shrinkage gains.

## Remaining acceptance

No Android device/emulator was available. Device startup, scrolling/jank, memory, checkout responsiveness, TalkBack operation, keyboard/orientation/Back workflows and Windows Pixel acceptance remain unverified. Feature-sized ViewModel decomposition remains partial; state and purchase-dialog extraction are bounded steps, not completion of the broader refactor.

State remains an in-memory demo and resets on process restart. Scanning, printing and role switching remain simulated. Laravel/backend synchronization was not changed. Do not merge or deploy without owner approval.

For Windows review, copy the candidate APK above and install it on the intended test device. Verify AR RTL/EN LTR, manual themes, 360dp/393dp at all three font scales, payment failure/retry, draft resume, multiple purchase lines, partial receiving and replay protection. Use the Device panel for agent-driven interaction.

## Historical evidence and workspace

`docs/candidate-v3/` preserves the original 9336da6-era evidence (104 passed, one skipped); its hashes and counts describe that historical revision, not the current source. Earlier APKs remain preserved in the previous checkout and `app/round2/artifacts/baseline_0a965c0/`.

The previous T3 checkout `/home/t3dev/.t3/worktrees/small-trader-android/t3-fba3c97b` is detached at `3808ae2` and retained because earlier thread handoffs and artifacts reference it. Root crop/slice PNGs and local `app/round2/` artifacts are intentionally untracked and preserved. No destructive cleanup was performed. PRs #1 and #2 remain superseded; PR #3 is the cumulative review surface.
