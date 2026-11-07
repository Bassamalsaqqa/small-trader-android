# Shell implementation handoff — stages 2/3

Source implementation only; **not built, unit-tested, linted, device-observed or visually accepted**. Lead owns those checks after concurrent writers finish. Worktree `/home/t3dev/.t3/worktrees/small-trader-android/t3-fba3c97b`, branch `ux/merchant-experience-reset`, baseline HEAD `f8629644617d147b1d0920634b3eaed4e410cfb5`. No commits, pushes, PRs, backend accesses, installs or device/system changes.

## Files owned and changed

Paths under `app/src/main/java/com/example/`:

- `MainActivity.kt`: shell wiring, saveable screen/form scopes, action chooser, destination render guard, consumed Scaffold insets. Existing safeDrawing Scaffold remains owner; content consumes its padding before IME handling; native navigation bar consumes navigation-bar inset. Device/IME validation pending.
- `model/TraderModels.kt`: PEOPLE and PURCHASES navigation enum entries only.
- `viewmodel/TraderViewModel.kt`: four-root policy, selected root, quick sheet/pending task, action launch and consume, separate sale/inventory search strings, navigation/role-change guards. Financial calculations/posting untouched.
- `ui/components/CommonComponents.kt`: short header, avatar account sheet, About, native four-destination NavigationBar with central nonselected + IconButton. Old secondary navigation bars are no longer composed. Language/theme/management/printer preview live in account sheet. No permanent demo banner/subtitle, no connection dot. About states reset-on-restart; printer entry says simulation.
- New `ui/navigation/TraderNavigationPolicy.kt`, `ui/shell/QuickActionSheet.kt`, `ui/screens/PeopleHubScreen.kt`, `ui/screens/PurchasesScreen.kt`.
- `ui/screens/ProductsCatalogScreen.kt`: first-level Purchases button, optional create-launch/consume parameters, scalar create draft retention; existing guarded cost section retained.
- `ui/screens/SuppliersScreen.kt`: optional create/payment-launch/consume parameters, scalar draft scopes, supplier render guards.
- `ui/screens/CustomersScreen.kt`: scalar form state restoration and stable collection operation ID within resumed draft.
- `ui/screens/BusinessExtraScreens.kt`: expense render guard and scalar form state restoration.

Tests: new `app/src/test/java/com/example/MerchantNavigationTest.kt` and `MerchantRoleTest.kt`; intended navigation-only assertions/comments updated inside `ExampleUnitTest.navigationAndBackBehavior_acrossAllModules_preservesUnfinishedBasketAndRootHierarchy`. NEW_SALE is now child; PEOPLE replaces CUSTOMERS as root; Inventory becomes root; legacy management routes preserve explicit child history. Financial assertions were not weakened.

## Callback contract

- Home `onOpenPurchase` (optional at end, supplied by Home writer) -> `openMerchantAction(PURCHASE)` -> PURCHASES plus existing bill dialog. Home restock callback now navigates Inventory, without stock mutation.
- NewSale `onProceedToInvoiceReview` -> CASH_PAYMENT; new optional `onOpenSaleOptions` -> INVOICE_REVIEW (supplied by sale writer). Existing review/payment/result signatures remain.
- Inventory `launchCreate`, `onLaunchConsumed`, `onOpenPurchases`; Suppliers `launchCreate`, `onLaunchConsumed`; SupplierDetail `launchPayment`, `onLaunchConsumed` are named optional parameters. Launch effects consume once after opening local native dialog.
- + sale resumes without clearing; purchase opens bill form; collect opens customer chooser then collection; pay opens supplier chooser then existing payment dialog. Product/supplier creation opens existing form, customer/expense creation opens existing global dialog. All restricted launch events are guarded.

## State and truthful limits

Back dismisses native local dialogs/sheets via their own dismissal handlers; ViewModel handles quick sheet, pending chooser and global overlays before child history. Root navigation retains active basket/customer/discount/tender; sale and Inventory query strings are separate (category remains existing shared state). Sheet cancel never posts. Navigation and rendering check restricted supplier/purchase/expense/report destinations. Role switch dismisses restricted pending work and removes restricted history. Demo permissions remain UI/repository simulation, not server authorization.

Scalar creation/purchase/expense/collection/payment draft inputs use saveable scopes. Cancel retains inputs in-session; successful creation uses changed record count as the next draft key. This is bounded UI retention, not durable accounting storage. Explicit discard UI is deferred. Existing product/supplier callbacks return Unit and close their local form after submission; repository failure feedback remains in the parent. Old saved scopes may remain until activity destruction. Party choosers currently scroll names without search. Large-font, RTL mixed numbers, predictive Back/IME precedence and rapid transitions need actual native checks.

Purchases lists actual `purchaseBills`: received vs pending, and explicit text-only receiving unavailable. Supplier detail retains bounded GRN operations; no stock command added. Legacy reports/settings/history/detail/edit/preview paths remain. Existing longer copy in legacy domain screens was not broadly redesigned in stages 2/3.

No Home or sell-owned source/tests edited. No repository changes. No real scanner/printer/backend/storage integration. Repository ID-only sale replay payload limitation remains unchanged. Stage 4+ remains gated by owner direction acceptance.

## Verification handoff

`git diff --check` executed successfully. New tests cover overlay cancel and origin, search retention, root reselect, sale-child history, purchase form Back, nonposting collection chooser, cashier direct destinations/actions, role-switch eviction. They are **written, not executed**; no UI semantics/device evidence is claimed. Lead must integrate Home/Sale optional callbacks, build, run full unit/lint checks, produce identifiable APK and obtain authentic owner device evidence. This handoff does not certify an installable APK or visual gate pass.


## Lead integration corrections (not executed yet)

After shell handoff, lead separated sale/inventory category state in addition to query state and restores both on Back. Money task choosers now remain on the originating screen until a party is selected; cancel no longer navigates to a generic party module. Added two navigation regressions for these cases. Local ignored SDK properties and debug key were reused from the Phase 1 checkout for upcoming builds; no host/toolchain modification.
