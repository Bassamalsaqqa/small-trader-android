# PR #24 Focused Android Compile & QA Evidence Report

**Branch**: `t3/android-bootstrap-tax-status-recovery`  
**Base Commit**: `e6b178c8056236f62078e699a6901f58fc449284`  
**Target Environment**: Isolated Android Worktree `t3-7ac8b73d-abb5d6e52b3b3cf9`  
**Toolchain**:
- JDK: OpenJDK 21.0.13+9 (`/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9`)
- Android SDK: API 35 (`/home/t3dev/Android/Sdk`)

---

## 1. Resource Flag Disclosures & Compliance Audit

> [!CAUTION]
> **Resource Constraint Discrepancy Disclosure**:
> The previous Gradle run executed with `-Dorg.gradle.workers.max=2` without `-XX:ActiveProcessorCount=2` rather than the strictly requested `max1 CPU2` (`-Dorg.gradle.workers.max=1 -XX:ActiveProcessorCount=2`).
> This actual flag configuration is disclosed here explicitly; approved constraints are not fabricated or falsely claimed.
> Per instructions, **NO Gradle execution is performed** in this turn; lease is released and process absence is acknowledged pending parent lease grant.

---

## 2. Historical Run Status & Guard Restoration

> [!WARNING]
> **Status of Previous 211 XML Run (`672cd425`)**:
> The 211 XML results and test execution from commit `672cd425` are marked and preserved as:
> **`HISTORICAL REJECTED GUARD WEAKENING RUN (NOT qualified release)`**.
> In that run, `OnlineCashSaleOrchestrator.checkOnlineCashSaleStatus` had temporarily allowed a nullable `status.payloadDigest` on status GET query with fallback to the local intent digest.

### Production Guard Restoration
- **Restored**: EXACT fail-closed non-null matching status digest check for posted records:
  ```kotlin
  // Status digest MUST be non-null and match expected normative digest
  if (status.payloadDigest == null || status.payloadDigest != intent.expectedNormativeProfileDigest) {
      return OnlineCashOrchestrationResult.StatusDigestMismatch(
          expected = intent.expectedNormativeProfileDigest,
          actual = status.payloadDigest
      )
  }
  ```
- **Receipt Construction**: Direct assignment of `status.payloadDigest` without local intent fallback.
- **Contract Rule**: Revision 3 wire schema allows nullable `payload_digest` for unknown/conflict records, but **NEVER authorizes confirming a posted financial record without an authoritative server payload digest**.
- A missing or null server digest on a posted record fails closed to `StatusDigestMismatch` or non-confirmed hold. The original intent and draft basket are strictly preserved; no fake paid receipt is created, and no new POST is dispatched.

---

## 3. Test Fixtures Correction & Explicit GET Status Regressions

Rather than weakening production logic to accommodate `null` mock payloads, test fixtures in `StaffOnlineSaleViewModelTest.kt` have been updated to supply the actual non-null normative profile digest computed from the prepared intent.

Four explicit regression tests have been added in `StaffOnlineSaleViewModelTest.kt`:
1. `testPaymentUnverifiedHold_checkPaymentStatus_postedNullDigest_neverConfirmedHoldKept`:
   - Server returns `status: "posted"` with non-null invoice and payment, but `"payload_digest": null`.
   - Asserts that hold is strictly KEPT (`isPaymentUnverifiedHold == true`), `receipt == null`, `lines.size == 1`, and `submissionError` indicates digest mismatch.
2. `testPaymentUnverifiedHold_checkPaymentStatus_postedWrongDigest_neverConfirmedHoldKept`:
   - Server returns `status: "posted"` with non-null invoice and payment, but mismatched `"payload_digest": "sha256:0000...0000"`.
   - Asserts that hold is strictly KEPT (`isPaymentUnverifiedHold == true`), `receipt == null`, `lines.size == 1`, and `submissionError` indicates digest mismatch.
3. `testPaymentUnverifiedHold_checkPaymentStatus_postedMatchingDigest_succeedsConfirmedFinancialSuccess`:
   - Server returns `status: "posted"` with non-null invoice and payment, and matching non-null `"payload_digest": "$actualNormativeDigest"`.
   - Asserts that hold is released (`isPaymentUnverifiedHold == false`), confirmed `receipt != null` with matching `receipt.payloadDigest`, and basket lines are cleared.
4. `testPaymentUnverifiedHold_checkPaymentStatus_unknownNullableDigest_remainsUnknownHold`:
   - Server returns `status: "unknown"` with `"payload_digest": null`, `official_invoice: null`.
   - Asserts that `receipt == null`, draft basket lines and prepared intent are preserved.

---

## 4. Preserved Raw Execution Logs

The following raw execution logs are archived and published in this evidence directory:
- [`first-compile-failure.log`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/first-compile-failure.log): Preserved raw output of the initial compile failure.
- [`raw-initial-test-failure-task-730.log`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/raw-initial-test-failure-task-730.log): Raw log showing the initial test run failure including null status fixture failure.
- [`raw-test-run-task-766.log`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/raw-test-run-task-766.log): Raw log of historical test execution.
- [`raw-lint-run-task-792.log`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/raw-lint-run-task-792.log): Raw log of historical `lintDebug` execution.

---

## 5. Other Verified Source Adjustments

- **`StaffWorkspaceViewModel.kt`**: Fixed `[NewApi]` lint error by replacing `java.time.Instant.now()` with `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)` configured with UTC timezone (minSdk 24 compatible).
- **`StaffWorkspaceComposeTest.kt`**: Added `@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")` so Compose tests execute against standard modern phone viewport dimensions matching `NativeVisualEvidenceTest.kt`.
- **`MainActivity.kt`**: Cleaned up unresolved preview handler references from prior rebasing.
