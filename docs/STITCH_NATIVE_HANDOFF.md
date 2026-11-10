# Stitch native Android prototype — review handoff

Status: implementation review candidate. General Stitch direction approved; final UX approval and Windows Pixel_8a_T3 device acceptance pending. This document supersedes broad completion claims in individual agent reports.

## Implementation

Native Kotlin / Jetpack Compose / Material 3 retained. Home, navigation and quick actions, catalog/basket/review/cash-credit/receipt, customer and supplier accounts/collections/payments, inventory/product detail/purchasing/eligible goods receipt, expenses, reports, profile and preferences use the Stitch-led design system. IBM Plex Sans Arabic regular/medium/semibold/bold are bundled with their license. Arabic RTL, English LTR and light/dark modes remain available.

The accounting repository was not cloned, modified or run. The Android repository's data, model and ViewModel files are unchanged from f4713c0. This is an in-memory demo: process restart resets data. User roles simulate permissions; they are not live authentication. Barcode input and thermal printing remain simulations. Sharing uses Android text sharing and clipboard. No cloud sync, SQLite durability, production posting, PDF/WhatsApp integration or live hardware connection is claimed.

Purchases are financial bills. Stock receiving is separate and allowed only for a linked expected SKU/quantity, once; financial-only text bills cannot receive stock. Product cost must be explicitly entered, including an intentional zero. Checkout supports full cash or full credit; partial payment remains a historical invoice status. Customer/supplier advances remain distinct from debts/payables.

## Validation record

The integrated run executed 82 tests: 81 passed, one pre-existing ignored placeholder; zero failures/errors. Lint completed with zero errors and 61 existing warnings. After inventory review fixes and payment-copy cleanup, the full 82-test suite, lint and APK assembly passed again; consult the delivery VALIDATION.json for final artifact provenance rather than intermediate hashes in agent reports.

Lint warnings: 22 GradleDependency, 9 NewerVersionAvailable, 2 AndroidGradlePluginVersion, 21 UnusedResources, 5 IconLauncherShape, 1 VectorRaster, 1 UnusedAttribute. Dependency/launcher/resource maintenance remains deferred; no warning baseline or new suppressions were introduced.

Robolectric Native Graphics renders exercise actual Compose code, including 360dp at 1.5x/2x font scale and full-shell Home/catalog. They exposed and helped fix vertical money wrapping, fixed-height search clipping and an unbounded navigation-height constraint. Capture assertions and source review do not certify device IME, TalkBack, hardware or frame performance. Earlier defective renders are retained as audit evidence; current accepted-for-inspection captures are native-renders-v04. Some feature renders predate later copy changes.

## Reproduce locally

Use a JDK 21 and Android SDK configured for this checkout. The constrained T3 build uses user-owned tools:

```bash
JAVA_HOME=/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9 \
ANDROID_HOME=/home/t3dev/.local/share/android-toolchain/sdk \
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Xss1m' \
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  --no-daemon --no-configuration-cache --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Xss1m -Dfile.encoding=UTF-8' \
  -Dorg.gradle.vfs.watch=false --console=plain
```

NativeVisualEvidenceTest writes to `build/native-renders` by default; set TRADER_VISUAL_OUTPUT_DIR for an alternative output location. No emulator is installed on HP-worker, and no sandbox/security/network/virtualization configuration was changed.

## Test on Windows Pixel_8a_T3

1. Start the existing Pixel_8a_T3 AVD in Android Studio Device Manager.
2. Download the review APK to Windows and drag it onto the running emulator, or use `adb -s <serial-from-adb-devices> install -r small-trader-stitch-review.apk`.
3. Open التاجر الصغير. Package: `com.aistudio.smalltrader.psdukx`.
4. If Android reports INSTALL_FAILED_UPDATE_INCOMPATIBLE, uninstall the previous demo through emulator Settings first; this resets its demo state.
5. Check Home, start a sale, add/change quantities, review, assign a customer/discount, enter exact/overpaid cash and confirm once. Inspect receipt, share/copy and simulated print. Repeat full-credit with a named customer, including over-limit rejection.
6. Check customer debt/advance, collection and visible feedback; supplier payment; purchase bill versus eligible once-only goods receipt; explicit product cost and deliberate stock recount. Test cashier role restrictions.
7. Switch English and dark mode; test Back/draft recovery and 1.5x/2x text. Check keyboard, bottom actions, touch targets and scrolling on the actual device. Process restart resets demo data.

No merge, deployment, GitHub CI or production transaction is authorized by this handoff. Draft PR remains for owner review.

Final review APK SHA-256: `e393031cfafefe51526e620dd4ada8a8fc5dd38a0d4395ac1d75c220372fc5d0` (20542340 bytes). See STITCH_NATIVE_VALIDATION.json.
