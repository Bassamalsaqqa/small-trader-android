# Phase 1 reconciled validation evidence

Branch: `ux/native-phase1`. Baseline: `ce2bb6ef8c480048036187892850fa6fb8853f6f`.
Status: implementation handoff for visual acceptance; UX approval is pending.

## Executed checks

The delegated QA agent finished the assigned checks and wrote its final response/report. T3 still marked the response running; the lead collected the response and on-disk evidence and requested cancellation of the stale run. No further useful checks were identified. The separate AGY review crashed at startup; a fresh read-only Codex review completes closeout review. No successful tests were repeated during closeout.

Local JDK: Temurin 21.0.13+9; SDK platform android-36.1. Toolchain and ignored local.properties/debug.keystore are user-owned. No emulator, host isolation/security or backend changes.

Commands reported by QA (each exit 0):

```bash
export JAVA_HOME=/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9
export PATH=$JAVA_HOME/bin:$PATH
export JAVA_TOOL_OPTIONS="-XX:ActiveProcessorCount=2"
./gradlew :app:assembleDebug --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8"
./gradlew :app:testDebugUnitTest --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8"
./gradlew :app:lintDebug --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8"
```

The lead independently parsed the XML reports and inspected the APK/output metadata. APK and reports postdate the final production Kotlin changes. Closeout changes are documentation/evidence only unless a subsequent review finding is explicitly recorded.

| Suite | Passed | Skipped | Failed/errors |
| --- | ---: | ---: | ---: |
| ExampleUnitTest | 39 | 0 | 0 |
| NewSaleScreenDesignTest (Compose semantics, Robolectric SDK 35) | 1 | 0 | 0 |
| PhaseOneWorkflowTest (tender editing without posting) | 1 | 0 | 0 |
| ExampleRobolectricTest (existing @Ignore) | 0 | 1 | 0 |
| Total | 41 | 1 | 0 |

Original XML reports are preserved in [phase1-evidence](phase1-evidence). The APK is an ignored build output; its exact size/hash are in [artifact.json](phase1-evidence/artifact.json). The shell wrapper executable bit is retained so the documented Linux build command works.

## Lint disposition: 0 errors, 62 warnings

The original QA summary incorrectly called these 57 unused-resource and 5 icon warnings. The following counts come from parsing actual XML. All warning location files are byte-identical to baseline, verified using git object contents; no baseline build was needed. No lint suppression was added.

| Category | Count | Disposition |
| --- | ---: | --- |
| UnusedAttribute | 1 | Accept existing API-33 predictive-back manifest flag. Older supported Android versions ignore it. Keep for modern Android behavior. |
| RedundantLabel | 1 | Optional manifest cleanup: activity repeats application label; harmless and unchanged. |
| VectorRaster | 1 | Large unused shop illustration. Defer deletion/size optimization to asset cleanup; no current Compose reference. |
| AndroidGradlePluginVersion | 2 | Gradle/AGP newer-version notices. Plan a separate compatibility-tested toolchain upgrade; not a runtime or security finding. |
| GradleDependency | 22 | Dependency update notices, including unused catalog integrations. Defer upgrades to a scoped maintenance change; do not infer security defects from newer-version notices. |
| NewerVersionAvailable | 9 | Additional dependency/plugin update notices. Same maintenance disposition. |
| UnusedResources | 21 | Existing XML colors/strings/illustration not referenced by Compose. Optional resource/localization cleanup; preserve now. |
| IconLauncherShape | 5 | Existing legacy launcher PNG silhouette. Revisit during brand/launcher visual acceptance; no launch/build blocker. |

None requires correction to compile/install this demo slice. Optional asset/manifest cleanup and dependency maintenance remain tracked work. Lint does not establish accessibility, visual quality, device stability or absence of security issues.

## Unverified acceptance areas

No genuine emulator screenshots or before/after device captures were obtained. The Bassam/Windows device host is unavailable through the current tools. Robolectric semantic assertions are not pixel review. Arabic/English mixed-direction runs, font scaling, compact/landscape layouts, keyboard behavior, TalkBack, contrast and performance require real Android acceptance. No universal 48dp/large-font compliance is claimed.

Known workflow boundaries: in-memory reset on process restart; no live auth/backend; simulated scanner/printer; clipboard statement/quotation export; new checkout supports full cash or zero-cash credit, while partial invoices are seeded/reviewable. Tablet catalog retains bottom basket drawer. See PHASE1_REVIEW.md and PHASE1_WINDOWS_ACCEPTANCE.md.
