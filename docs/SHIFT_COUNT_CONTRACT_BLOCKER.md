# Shift cash observation contract review — BLOCKED

Date: 2026-11-21. Android baseline: d5237479be7f51599c6db62d5b74c95dbc65a0fc, branch t3/android-searchable-selectors-pr22.
Backend inspected read-only at 8c1fea407a9c288e5d2fbc495dada1a548386941 in integration-mobile-backend-supervision; concurrent backend dirty changes were not edited.

## Decision

Do not wire or release the requested shift-close workflow against this contract. It records an immutable audited cash-count observation, not a shift lifecycle transition or financial reconciliation. Both success/replay responses explicitly return observation_recorded=true and is_shift_closed=false. No accounting or stock posting occurs in ReportController::shiftClose. MobileShiftClosure rejects updates/deletes.

## Backend evidence and missing guarantees

Paths below are relative to the backend repository.

- app/Http/Controllers/Api/Mobile/V1/ReportController.php:124–141: summary contains company/date/currency and exact-decimal display amounts, but no cash account public ID. BootstrapController supplies an approved account independently; a changed approval between bootstrap and summary can associate summary account B with client account A. Summary needs explicit account identity bound to the count confirmation.
- ReportController.php:197–204 and 323–337: count precision and allowed account currency derive from current company base currency. Explicit account lookup requires currency_code=baseCurrency. No explicit observation currency is consumed or checked. Foreign-currency cash account observations are unsupported; adding client multi-currency options would invent support.
- ReportController.php:323–369: active account/ledger validation precedes existing-observation lookup. After a successful POST whose ACK is lost, deactivate its cash account and replay the original explicit account/key/count: account lookup fails and returns 422 before the persisted observation can be returned. A company base-currency change can similarly invalidate replay or even count precision before lookup. Preserve unknown outcome; never rotate the key or reinterpret the amount in a new currency.
- app/Support/Mobile/Receipt/NativeCommandReceiptManager.php:182–207: status looks only in NativeCommandReceipt; supported command types omit shift observations. ReportController persists MobileShiftClosure instead and does not create a native receipt. Thus current receipt-status cannot resolve this unknown ACK. Same-key POST replay works only while the earlier mutable validation still succeeds.
- routes/mobile-v1.php:116–117 requires reports.financial.view for both endpoints, even though the controller also accepts money.cash.view. Client eligibility must follow actual route admission, not the broader controller check.

Required backend review: bind summary and response to explicit account/currency; define supported currency policy; provide actor/device/company-scoped observation lookup or replay that recovers persisted observations despite account configuration changes under current authorized access. Preserve changed-payload 409 and immutable original economic context. Do not invent shift sessions, shift totals, or reconciliation posting.

## Inherited Android partial changes

Six inherited dirty files remain exactly as received: ProductionTraderRepository.kt, TraderRepository.kt, MobileMerchantApiClient.kt, TraderModels.kt, TraderUiState.kt, TraderViewModel.kt. app/round2 remains untouched. No reset, stash, removal, or allocation commit was performed. No preservation patch was needed because no inherited changes were removed. Initial full tracked diff SHA-256: 9a0055451248067903d208a90d158283c532a55c85325d6e3b1f3bd256d34617.

Allocation additions and shift scaffolding coexist in those files. Allocation API still rounds values to six decimals and durable payload/account identity remains unaccepted; this report does not accept that work. Shift scaffolding adds recordShiftClose, result/state fields and a draft operation ID, but no production UI submit caller. Its API retains an optional key with random fallback; result parsing invents defaults for missing expected cash/status/date; it rounds counts with HALF_UP; its post lambda uses invocation values instead of rehydrating the original stored payload. Existing Room command storage is useful infrastructure, not proof of a safe implemented workflow. Keep production shift submission unavailable until contract and durability tests are complete.

## UI reference and deferred implementation

Read ui-ux-design and Stitch skills and existing FINANCE_SETTINGS_IMPLEMENTATION/STITCH_NATIVE_HANDOFF guidance. Queried live Stitch project 15641450350510420845 and retrieved physical-count reference screen 69f62724d95344359b32b8ccb601348b (HTML file 3fefc36243d142f0b219adb25e17d899); related expected-cash reference 15c355e0e94442e484406687f3c38495. These are references only; no visual acceptance or new UI generation is claimed.

Once unblocked: retain existing Compose reports/cash navigation, compact EN/AR RTL form with at least 44dp targets, explicit account/currency, actual physical count, optional notes and one primary “Record cash count” action. Confirmation and receipt must say observation recorded; never “shift closed”. Show summary unavailable/restricted states truthfully and retain unresolved original counts across relaunch. No bank/cost data should be introduced.

## Verification and operational limits

Source review only. No runtime tests/assertions, JVM suite, lint, release build, signer check, device work or APK were run/produced because implementation is blocked. A documentation-only commit is the sole new tracked change. Inherited dirty work remains deliberately uncommitted. No gate flips, offline policy changes, production access, network writes to production, pushes, merges, deployment, GitHub Actions, or subagents. No overall Android/allocation acceptance is claimed.

## 6. Evidence Overclaim Correction & Production Invariants (2026-11-24)

### Lost ACK Evidence Correction
- **Previous Overclaim**: Earlier test evidence (`ShiftCloseRealOkHttpSecurityTest > testShiftClose_realFileRoom_lostAckReplay_sameImmutableIntent`) tested local Room command store replay of an already `COMPLETED` receipt across process/database restart. The first invocation succeeded with HTTP 200, Room marked the command status `COMPLETED`, and the reopen replayed the cached response without network writes. This demonstrated local restart recovery and idempotent client store lookup, NOT a true network-lost ACK recovery against a live server where an in-flight HTTP connection drops after server-side commit.
- **Coverage Gap**: True network-lost ACK against the Laravel backend requires server-side replay qualification where client encounters dropped transport (timeout / connection reset) on the initial POST, followed by replay with identical command ID, asserting HTTP 200/409 idempotent replay from backend `MobileShiftClosure` audit record. While disposable backend MariaDB was released by the concurrent integration worker, live paired integration tests were kept isolated to ensure zero concurrent database interference. Full end-to-end network drop simulation against the running service remains an acknowledged integration gap documented here.

### Production Invariant Fixes Implemented
1. **Physical Cash Count Enforcement (P1)**:
   - In production mode (`!isDemoSession`), the "Match Book" button (`shift_close_match_book_button` / "مطابقة الدفتري") is strictly omitted from `ShiftCloseDialog`. Merchants must count physical cash and explicitly enter the count in `shift_close_counted_cash_input`.
   - `openShiftCloseDialog` initializes `shiftCloseCountedCashInput` to `""` in production (no copying from `countedPhysicalCashText` or book cash).
   - Empty/blank cash count is rejected with validation error ("يرجى إدخال المبلغ الفعلي بالدرج").
   - Explicit zero (`"0"` or `"0.00"`) is explicitly valid and accepted.
   - Demo mode retains the "Match Book" button for sandbox inspection.

2. **Rejection of Unsupported Nonzero Discounts (P1)**:
   - In `ProductionTraderRepository.recordSale`: Any invoice with `discountExact != BigDecimal.ZERO` is rejected with `IllegalArgumentException` ("Unsupported nonzero discount in production V2 sale dispatch") BEFORE any outbox intent capture, JCS serialization, or network synchronization.
   - Preserves cached legacy intents in Room and unsaved drafts without mutation or deletion.
   - Does NOT introduce or fabricate V4 contract schemas.

## 7. Review Mitigation & True Lost-ACK Qualification Resolution (2026-11-24)

### Production Discount & Rounding UI/ViewModel Mitigation
1. **Hide Unsupported Production Controls**:
   - In `InvoiceReviewScreen.kt`, interactive discount presets (`DiscountAndRoundingCard`) and custom discount inputs are completely hidden in authenticated production sessions (`!state.currentSession.isDemoSession`).
   - Sandbox demo mode (`state.currentSession.isDemoSession`) strictly retains interactive presets (5%, 10%, 15%, 20%, Custom ILS) and rounding controls.
2. **Actionable Restored Draft Recovery**:
   - Restored drafts or legacy baskets containing nonzero discounts display `RestoredUnsupportedDiscountCard` (`unsupported_discount_recovery_card`) on `InvoiceReviewScreen.kt`.
   - The card features a clear bilingual warning ("خصم غير مدعوم في هذا الإصدار" / "Unsupported Discount in Current Version") and a prominent 44dp touch target action `remove_unsupported_discount_button` ("إزالة الخصم والمتابعة" / "Remove Discount & Proceed").
   - Clicking `remove_unsupported_discount_button` resets `discountPreset` to `NONE` and clears `customDiscountText`, resetting calculated discount to zero and instantly unblocking checkout while preserving basket items, quantities, and customer details intact.
   - The review checkout dock disables `proceed_to_cash_payment_button` and presents `dock_unsupported_discount_warning` until the discount is cleared.
3. **Multi-Layer ViewModel Guard**:
   - `TraderViewModel.navigateTo(TraderScreen.CASH_PAYMENT)` intercepts navigation attempts for production sessions with `calculatedDiscountIls > 0.005`, emitting bilingual error banner and rejecting screen transition.
   - `TraderViewModel.completeCashSale(asCreditDebt)` enforces Safeguard 0: rejects sale submission if `!state.currentSession.isDemoSession && state.calculatedDiscountIls > 0.005`.
   - `ProductionTraderRepository.recordSale` retains fail-closed `require(discountExact == BigDecimal.ZERO)` validation prior to outbox intent capture, JCS canonical serialization, or network dispatch.
4. **Honest Zero-Discount Positive Path Verification**:
   - Renamed test in `ShiftCloseRealOkHttpSecurityTest.kt` to `testRecordSale_productionZeroDiscount_passesDiscountValidationGate`.
   - Added positive end-to-end verification `testRecordSale_productionZeroDiscount_succeedsWithOutboxConfirmationAndGatewayResponse`: validates that a zero-discount sale with configured `SaleSyncGateway` and encrypted `OutboxRepositoryImpl` succeeds (`Result.isSuccess`), yields server invoice ULID & payment ULID, and confirms the event in Room outbox.

### Cross-Runtime Paired Write Contract Qualification (True Network-Lost Cash-Count ACK)
1. **Harness Execution**:
   - Upgraded Scenario 9 in `/tmp/paired_harness/harness_artifacts/PairedWriteContractSmokeTest.kt` to qualify a true network-lost ACK for shift cash count observation (`shift-count-lost-ack-001`).
   - Sourced disposable MariaDB environment on port `3342` (`/home/t3dev/.t3/userdata/attachments/disposable-mariadb-20261120/connection.env`), pointing to combined backend worktree `/home/t3dev/.t3/worktrees/account/t3-b5bdc8fe-f4f0327d3b7ad078` (`72ef1a532c3fa6136006a54c0cfb89a2c732dcd4`).
   - Sourced disposable schema `accounting_p8_tmp_fb3f864f20a9`, migrated, and booted web server on `127.0.0.1:8089`.
2. **Verified Invariants**:
   - **Post-Commit Transport Drop**: First OkHttp POST reaches Laravel and commits `MobileShiftClosure` in MariaDB. `droppingTransport` simulates dropped network response by throwing `IOException` on reading HTTP response.
   - **Room SQLite Process Restart Persistence**: Room database `db1.close()` is called and reopened via `db2 = OutboxDatabase.buildDatabase(...)`. Room command row is verified persistent in `SUBMITTED` state across restart.
   - **Idempotent Network Replay**: Retry dispatch with identical command ID & idempotency key hits real Laravel backend. Backend returns cached snapshot (HTTP 200).
   - **Room Completion**: Command transitions to `COMPLETED` in Room outbox.
   - **Database Row Count Truth**: Direct MariaDB query confirms exactly 1 row in `mobile_shift_closures` for `shift-count-lost-ack-001` (and 1 row for `shift-count-obs-001`).
   - **Native Command Receipts**: Exactly 1 row for `lost-ack-payment-001` and 1 row for `idem-key-replay-001`.
   - **Canonical Reconciliations**: All 6 canonical domain reconciliations (Accounting, Inventory, Sales, Payables, Money, Phase 7) PASS for both Company 1 (ILS) and Company 2 (JOD).
