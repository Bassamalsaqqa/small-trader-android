# Candidate v3 Handoff & Validation Summary

**Canonical Prototype Status:** `VERIFIED_LOCAL_QA_ROUND2_V3_OWNER_DEVICE_ACCEPTANCE_PENDING`
**Consolidated Branch:** `ux/stitch-native-prototype`
**Consolidated Pull Request:** PR #3 (retargeted to `main`)
**Last Verified Local QA Timestamp:** 2026-11-08T23:22:00+02:00

---

## 1. Canonical Candidate APK Deliverable

The canonical review APK is placed outside the build path under `app/round2/validation/` alongside the full evidence suite:

| Attribute | Value |
| :--- | :--- |
| **Local Canonical Review Path** | `app/round2/validation/small-trader-candidate-benchmark.apk` |
| **Build Output Mirror** | `app/build/outputs/apk/benchmark/app-benchmark.apk` |
| **Size (Bytes)** | `2,132,291` bytes |
| **SHA-256 Digest** | `07907c8c84c35eba03a0f3c9524eb8f0ac2455708ace685222ca13548409f9cb` |
| **AndroidManifest.xml SHA-256** | `cec768c60a8942c2b662bfd0e18f76d873128bb19df1cd483ae8d0c4e0aeb5de` |
| **Signature Status** | Valid Android APK Signature Scheme v2 (Debug certificate `CN=Android Debug, O=Android, C=US`) |
| **Certificate SHA-256** | `f0995a82c2051d776a9307fcd75bb7d5862576714f1bd500885be2da0ca3e683` |
| **Actual Evidence Folder** | `app/round2/validation/native-renders-v03/` |
| **Baseline Release APK (`0a965c0`)** | `app/round2/artifacts/baseline_0a965c0/app-release.apk` (`2,246,979` bytes, SHA-256 `e8a87c6db5b6b011d8bacab4aadfb724f357e463bcc654c115f8d6792fbb49ea`) |
| **Baseline Debug APK (`0a965c0`)** | `app/round2/artifacts/baseline_0a965c0/app-debug.apk` (`19,576,028` bytes, SHA-256 `ad22db34b92bcf7caee196b6d21ea4f5a342371946312a02506a721d7b3dcba2`) |

*(Note on APK sizes: Absolute sizes are recorded without claiming causal size gain between candidate benchmark and baseline release APKs due to differing build configurations.)*

---

## 2. Test Execution & Verified Saved XML Totals

Unit and domain invariant tests were executed and recorded without rerun absent concrete source changes (audit of saved evidence):

- **Total Test Suites:** 13
- **Total Test Cases:** 105
- **Passed:** 104
- **Failed:** 0
- **Errors:** 0
- **Ignored / Skipped:** 1 (`com.example.ExampleRobolectricTest.testPackageName` — pre-existing `@Ignore` placeholder)

### Suite Breakdown
- `com.example.ExampleRobolectricTest`: 1 total (0 passed, 1 skipped)
- `com.example.ExampleUnitTest`: 40 passed
- `com.example.MerchantCheckoutTest`: 6 passed
- `com.example.MerchantHomeTest`: 5 passed
- `com.example.MerchantNavigationTest`: 11 passed
- `com.example.MerchantPrivacyTest`: 5 passed
- `com.example.MerchantRoleTest`: 2 passed
- `com.example.MerchantSaleLifecycleTest`: 2 passed
- `com.example.NativeVisualEvidenceTest`: 16 passed
- `com.example.NewSaleScreenDesignTest`: 1 passed
- `com.example.PeopleHubPagerTest`: 7 passed
- `com.example.PhaseOneWorkflowTest`: 1 passed
- `com.example.Round2NavigationTest`: 8 passed

Raw XML test reports are committed under [`docs/candidate-v3/test-results/`](candidate-v3/test-results/).

---

## 3. Lint Audit & Categorization

Android Lint (`:app:lintDebug`) report recorded under [`docs/candidate-v3/lint-results-debug.xml`](candidate-v3/lint-results-debug.xml):

- **Lint Errors:** 0 (clean; 2 fatal `BidiSpoofing` errors in `BusinessExtraScreens.kt` were fixed prior to v3 freeze)
- **Lint Warnings:** 61 (all pre-existing / deferred), broken down into the following exact categories:
  - `22 GradleDependency`
  - `21 UnusedResources`
  - `9 NewerVersionAvailable`
  - `5 IconLauncherShape`
  - `2 AndroidGradlePluginVersion`
  - `1 UnusedAttribute`
  - `1 VectorRaster`

---

## 4. Source Manifest & Visual Evidence Verification

1. **Source Manifest (71 files):** All 71 tracked source, resource, and test files listed in [`docs/candidate-v3/SOURCE_MANIFEST_V3.json`](candidate-v3/SOURCE_MANIFEST_V3.json) match the working tree byte-for-byte and SHA-256 digest.
   - *Scope Note:* The 71 files cover all production source code, unit tests, and layout XMLs modified in Phase 1 / Round 2. It intentionally excludes the untouched default instrumentation test stub (`app/src/androidTest/java/com/example/ExampleInstrumentedTest.kt`).
   - *Reproducibility Note:* This manifest certifies file-level source integrity; it is not a claim of bit-for-bit reproducible build proof across differing toolchains or host build environments.
2. **Visual Evidence (16 RNG PNG Renders):** Preserved in local archive `round2/validation/native-renders-v03/` and verified matching [`docs/candidate-v3/VALIDATION_V3.json`](candidate-v3/VALIDATION_V3.json) across all 16 captures, including light/dark Arabic (RTL) & English (LTR), POS grid, settlement invoice net-paid view, and 360dp stress tests.

---

## 5. Frozen Elements & Known Prototype Limitations

1. **Frozen Bottom Navigation Bar:**
   - Function `SmallTraderBottomBar` in [`app/src/main/java/com/example/ui/components/CommonComponents.kt`](../app/src/main/java/com/example/ui/components/CommonComponents.kt) (lines 726–899, 174 lines, 6,914 chars) is byte-for-byte identical to `origin/ux/stitch-native-prototype` HEAD.
   - Minor label text clipping occurs on narrow 360dp screens at 2.0x font scaling in bottom navigation tabs. Per specification, this component remains intentionally frozen and untouched.
2. **In-Memory Demo State:**
   - Seeded from `SampleCatalog`. Data modifications reset upon app process restart.
3. **Simulated Hardware:**
   - Barcode scanning and Bluetooth 58mm/80mm thermal receipt printing are interactive UI simulations ready for hardware driver binding.
4. **Acceptance Status:**
   - Windows physical/AVD `Pixel_8a_T3` acceptance has not yet occurred and is pending owner review.

---

## 6. Owner Windows Manual Installation & Acceptance Instructions

To test the canonical candidate APK on a Windows machine with Android SDK platform-tools installed:

1. **Locate or Copy APK:**
   Copy `round2/validation/small-trader-round2-v3-review.apk` (or `app/build/outputs/apk/debug/app-debug.apk`) to your Windows environment.
2. **Connect Device / Start Emulator:**
   Ensure your Android device or emulator (e.g., Pixel 8a AVD) is detected:
   ```cmd
   adb devices
   ```
3. **Install Debug APK:**
   ```cmd
   adb install -r path\to\small-trader-round2-v3-review.apk
   ```
4. **Launch Application:**
   ```cmd
   adb shell am start -n com.aistudio.smalltrader.psdukx/com.example.MainActivity
   ```
5. **Acceptance Verification Checklist:**
   - Verify Arabic RTL layout and font rendering on Home screen.
   - Test POS catalog item selection, quick add, and checkout tender calculation.
   - Verify Invoices history net-debt settlement display.
   - Check People Hub tabs and lateral navigation drawers.
   - Observe bottom navigation bar appearance on standard density and at 2.0x font scaling.

---

## 7. Consolidated Single PR Strategy

- **PR #3** (`ux/stitch-native-prototype`) has been retargeted to `main` as the sole cumulative pull request containing all prototype work.
- Older stacked draft PRs (PR #1 and PR #2) are superseded and closed with cross-references to PR #3, leaving a clean, single-PR review surface without history rewrites.
