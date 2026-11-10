# Final reset validation

Branch `ux/merchant-experience-reset`, stacked on Phase 1 `f8629644617d147b1d0920634b3eaed4e410cfb5`. **Automated checks pass; UX acceptance remains pending.**

## Actual final execution

Reused Temurin 21.0.13+9 and SDK android-36.1. App Kotlin plugin 2.2.10 and Robolectric 4.16.1 remain unchanged. No emulator, backend, dependency or host-isolation changes. Existing ignored debug key and SDK properties reused.

```bash
JAVA_HOME=/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9 \
JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 \
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' --console=plain
```

Final combined invocation: **exit 0, BUILD SUCCESSFUL in 1m 33s**, 56 tasks (11 executed, 45 up-to-date). APK signature independently verified with SDK apksigner (JDK on PATH): **exit 0**, v2 signature, one signer. Initial signer attempt without Java on PATH failed before verification; corrected PATH verification succeeded.

| Suite | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: |
| ExampleUnitTest | 39 | 0 | 0 |
| MerchantHomeTest | 5 | 0 | 0 |
| MerchantNavigationTest | 11 | 0 | 0 |
| MerchantRoleTest | 2 | 0 | 0 |
| MerchantPrivacyTest | 5 | 0 | 0 |
| MerchantCheckoutTest | 6 | 0 | 0 |
| MerchantSaleLifecycleTest | 2 | 0 | 0 |
| NewSaleScreenDesignTest | 1 | 0 | 0 |
| PhaseOneWorkflowTest | 1 | 0 | 0 |
| ExampleRobolectricTest (existing ignored placeholder) | 0 | 1 | 0 |
| **Total (73 tests)** | **72** | **1** | **0** |

Lead parsed the final XMLs directly. Original final report XMLs and command logs are preserved in repository-root `evidence/`. Source hashes in SOURCE_MANIFEST.json and binary details in ARTIFACT.json identify the exact candidate. No source is changed after the final checks; commit follows validation of those same bytes.

## Why checks were repeated

AGY initially achieved 70 passing tests/one skip; QA_INITIAL_REPORT.md preserves that historical result and its compile/test API/scroll fixes. Final independent source review found two genuine missed workflows: empty-cart saved drafts were unreachable and Credit mode could leak into the next sale through saved Compose state. Lead added direct draft access, scrolling empty review, UI-only sale generation and keyed checkout composition/state. The lifecycle regression initially reproduced the credit leak after an incomplete fix; the corrected scoping passed both focused tests (exit 0, 57s). A full suite/lint/APK rerun was therefore justified by production changes. The earlier APK/hash is superseded, not delivered as final.

Other QA fixes: native navigation extension imports; nullable credit balance formatting; historical invoice row compatibility; real callback/API/tag mappings; scroll-to-node assertions; no-data party creation recovery; old pending-action consumption. No tests disabled or financial assertions weakened. Existing navigation-only expectations changed to intended roots. Repository and seeded financial data unchanged.

## Lint disposition

Final **0 errors / 62 warnings**. Issue identities match committed Phase 1 after normalizing checkout paths: no added or removed warnings.

| Category | Count | Disposition |
| --- | ---: | --- |
| GradleDependency | 22 | Existing newer-version notices; scoped maintenance later, no implied security verdict |
| NewerVersionAvailable | 9 | Existing version notices; no toolchain chase in UX task |
| AndroidGradlePluginVersion | 2 | Existing Gradle/AGP upgrade notices; compatibility project later |
| UnusedResources | 21 | Optional XML asset/localization cleanup; preserved |
| IconLauncherShape | 5 | Existing legacy launcher PNG silhouettes; brand follow-up |
| UnusedAttribute | 1 | Existing API33 predictive-back manifest flag ignored on older OS versions |
| RedundantLabel | 1 | Harmless duplicate application/activity label |
| VectorRaster | 1 | Existing unused shop illustration size; asset cleanup |

No suppressions or dependency upgrades. None blocks this demo build/install; none certifies accessibility or visual polish.

## Review, device and acceptance limits

Independent shell findings were reconciled against actual code; a claimed missing PRODUCT/SUPPLIER route was false and a regression now covers the real dispatch. Earlier role/editor/aggregate/cost/result defects were fixed and covered. Final independent sale findings and corrections are in FINAL_REVIEW.md.

No authentic Pixel/device screenshots, native visual acceptance, camera frames or hardware performance evidence. Robolectric semantic tests establish tested state/actions only, not pixel layouts. AR/EN, dark/light, font scale 1.5/2, 360/393/440dp, landscape/tablet, insets/IME, touch geometry, TalkBack and speed require owner Android review. No screenshot is fabricated. See HANDOFF.md and WINDOWS_ACCEPTANCE.md for precise retained functionality and boundaries.
