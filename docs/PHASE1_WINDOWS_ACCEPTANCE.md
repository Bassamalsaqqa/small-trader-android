# Windows Pixel_8a_T3 visual acceptance

Status: **PENDING OWNER REVIEW — UX IS NOT APPROVED**.

## Install the exact reviewed APK

1. Download `app/build/outputs/apk/debug/app-debug.apk` from the T3 handoff file link onto Windows. This avoids needing to rebuild or copy the Linux debug keystore.
2. In PowerShell, verify the downloaded file:

```powershell
Get-FileHash "$env:USERPROFILE\Downloads\app-debug.apk" -Algorithm SHA256
```

Expected SHA-256: `2fc1efe1ee0f4f75c1c395ec2b34de392bf7a5af1a60923099181e637e309159`.

3. Open Android Studio > Device Manager and start your existing **Pixel_8a_T3** AVD. Do not create another emulator.
4. Drag the downloaded APK onto that emulator window to install it. Open **Small Trader / التاجر الصغير** from its app drawer.

If you prefer an explicit install command, use the existing Windows Android SDK platform-tools:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
# Replace SERIAL with the serial belonging to the running Pixel_8a_T3.
& $adb -s SERIAL install -r "$env:USERPROFILE\Downloads\app-debug.apk"
```

If installation reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the old installation uses a different signing key. Preserve any evidence you need first, then uninstall the old demo via emulator Settings and install this APK again. Do not uninstall automatically; uninstalling removes that installation's data. This app's repository data is in-memory and already resets after process restart.

## Acceptance walkthrough

Record language, theme, font size and orientation with each finding. Capture actual emulator screenshots before changing state. Do not use simulated screenshots as evidence.

- Home: Arabic RTL first; sale launcher, current basket resume, expected cash label, low-stock actions and secondary operations. Check that nothing is clipped or hidden under system bars.
- Sale: search Arabic/English/barcode, add items, adjust quantities, hit stock limit, search with no match, recover across categories, custom line, expand basket, review, Back and tab switches without losing the draft.
- Review: assign/remove customer, discounts, held draft save/resume, quotation warning and copy. Check bottom button spacing for a possible duplicate navigation inset.
- Cash: enter/correct tender using digits, decimal, backspace and clear; exact amount and bills; short cash feedback; complete full cash sale and inspect receipt/change/stock. Confirm full-credit requires a customer and adds no cash; test credit-limit rejection.
- Partial status: inspect seeded partially paid invoice #INV-1036. New partial-payment checkout is not implemented; do not mark it tested as supported.
- Customers: search/filter debt vs advance, detail/history, collect a positive amount, verify projected balance and actual demo statement/cash effects. Review statements and customer editing.
- Roles: switch via Profile demo controls to Cashier; verify owner Home totals stay hidden and unauthorized inventory/expense/report actions remain blocked. Restore Owner afterward.
- Repeat key screens in English LTR, dark mode, landscape and enlarged system font/display settings. Check keyboard visibility, touch targets, TalkBack labels, bidi currency/codes and scrolling to all actions.
- Smoke-check retained inventory, suppliers/bills/GRN, expenses, shifts/reports, settings, invoice history and simulated scanner/printer routes.

Expected boundaries: demo only, no backend or production posting, simulated scanner/printer, clipboard exports, no durable process-death recovery. Report usability problems before expanding the redesign.

## Source/build alternative

Fetch `ux/native-phase1` in your Windows checkout and open it in Android Studio with JDK 21 and SDK 36.1. Do not overwrite an existing local branch with uncommitted work. The checked-in debug signing configuration expects a local ignored `debug.keystore`; keep your existing keystore, or generate one locally using Android's standard debug alias/password if missing. A Windows-built APK will differ from the supplied reviewed binary, so record its commit and hash separately.
