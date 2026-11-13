# Local validation evidence

Toolchain: JDK 21 at /home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9; SDK /home/t3dev/Android/Sdk.

Commands:
```sh
./gradlew :app:testDebugUnitTest --tests 'com.example.data.mobile.*' --tests 'com.example.ui.staff.*' --tests 'com.example.MerchantRound3ComposeUiTest' :app:lintDebug --console=plain
python3 tools/validate-mobile-contract.py
python3 -O tools/validate-mobile-contract.py
```

The retired SmallTraderReadPilotSmokeTest is intentionally skipped. NativeVisualEvidenceTest was not selected. No device, instrumentation, process kill, hardware Keystore or actual backup transport proof is included.

assembleDebug was attempted and blocked at validateSigningDebug because the checkout's debug.keystore is absent. Signing configuration was preserved and no key was generated or private credential accessed.

The first broader run exposed a fixture-loader assumption that every exported table has an indices array. The loader was corrected to treat omitted indices as empty. Final XML and summary.json represent the corrected run. The Gradle log also records a KSP AWT service-disposal exception during compiler setup; final task outcome and test/lint XML, rather than that diagnostic, determine the reported result.

Final: 403 tests across 39 suites, 402 passed, 1 retired pilot skipped, 0 failures/errors. lintDebug succeeded: {'Warning': 63}. Both Python modes passed 303 checks. See summary.json for per-suite XML counts.
