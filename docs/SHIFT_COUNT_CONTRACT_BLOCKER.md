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
