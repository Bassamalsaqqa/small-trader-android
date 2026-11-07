# Reset candidate — Windows Pixel_8a_T3 acceptance

**Owner acceptance pending.** This is a new candidate stacked on unmerged Phase 1. Neither the HTML concepts nor automated tests certify this mobile design.

## Install the candidate

Use the NEW APK from the reset worktree's `app/build/outputs/apk/debug/app-debug.apk`, linked in the lead's delivery. Do not accidentally reuse Phase 1's APK from the original project checkout. The candidate is 18,959,577 bytes, SHA-256 `01951c61958752bfac698dd52f657a5811c96fd02fbc7b777fbdb760b6de3e3f`. Commit attribution is in the lead delivery and source hashes in SOURCE_MANIFEST.json.

1. Save that APK to Windows Downloads.
2. In Android Studio Device Manager, start your existing `Pixel_8a_T3`. No new emulator is needed.
3. Drag the APK onto its window, then open Small Trader from the app drawer.
4. Verify the file if needed: `Get-FileHash "$env:USERPROFILE\Downloads\app-debug.apk" -Algorithm SHA256`, comparing the delivery hash.

If you prefer installation through the existing Windows SDK:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
# Choose Pixel_8a_T3's actual SERIAL from the list.
& $adb -s SERIAL install -r "$env:USERPROFILE\Downloads\app-debug.apk"
```

If a signing mismatch is reported, keep any old screenshots/evidence before manually uninstalling the previous demo through emulator Settings. Do not delete the old installation automatically. Reset candidate reuses the Phase 1 Linux debug key, so upgrading that supplied APK should normally retain signing compatibility. A separately Windows-built APK may use a different key.

## First merchant comparison

Try each task without coaching first. Note hesitation, wrong taps, backtracking, keyboard burden and what the result means. Record the actual emulator, language, theme, font scale, commit/APK hash with screenshots; timings are unmeasured until you record them.

1. **Home:** Find New sale/Resume sale and New purchase. Is one next action obvious? Are banners, repeated menus and dashboard clutter gone?
2. **Sell:** Add two products, adjust quantity, scan/manual lookup in the explicitly simulated scanner, clear a failed search. Open basket, continue to payment. Check large product names and mixed Arabic/Western ILS amounts.
3. **Cash:** Enter too little, correct using Exact or bills; inspect change. Exact must only prefill. Confirm once; inspect compact result and expanded invoice details. New sale must start/resume correctly; share/copy/print capabilities must be truthful.
4. **Optional sale choices:** Open Options for customer, discount, note, held drafts and quotation. These must remain accessible without covering the routine path in explanatory copy. Quote and saving a draft must not change cash or stock.
5. **Credit:** Choose a customer, review debt/limit consequence, confirm full credit. No new-partial mode or owner credit override is supported. Seeded #INV-1036 demonstrates existing partial status only.
6. **Interruptions:** Go to Inventory/People mid-sale, change their searches, return using Back. Sale query/category/customer/discount/basket must survive. Cancel + sheet and money chooser without losing origin or posting.
7. **Quick actions:** + to New product/New customer/New supplier/Receive payment/Pay supplier. Should land in form or appropriate party chooser, never an unrelated dashboard. Confirm collection must show the updated customer account/result.
8. **Purchasing:** Locate Purchases from Inventory and the named Home action. New bill is financial and text-only; recording it does not receive stock. Existing linked-SKU examples have bounded physical receipt guards. A stock adjustment is a separate action.
9. **Permissions:** Via avatar Profile demo roles, switch to Cashier. Purchases/supplier/expense/report actions, aggregate customer totals and cost editors must stay hidden/blocked. Individual customer balances needed for collection remain usable. Restore Owner.
10. **Mobile stress:** Repeat key tasks in Arabic/English, light/dark, enlarged text 1.5x/2x, portrait/landscape, keyboard open and Android system Back. Check bottom CTA visibility, touch targets, TalkBack labels and long titles. If available, compare 360/393/440dp and a tablet window; no such coverage is currently certified.

## Boundaries and next gate

In-memory demo data resets on process restart; About holds this information. Scanner and printer remain simulations. No live authentication, backend transactions, production posting, durable sync or real camera qualification. Store financial records and inventory movements remain separate. Raw developer copy in legacy domains may remain until later scoped work.

Report **accept or revise the shell/sales direction** after trying the candidate. Purchase/product/people broad redesign, real scanner integration, merge and deployment remain gated. The next milestone is authentic Android visual acceptance, not a higher automated test count.
