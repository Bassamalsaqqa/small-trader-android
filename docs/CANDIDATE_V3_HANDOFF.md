# Android review candidate handoff

PR: https://github.com/Bassamalsaqqa/small-trader-android/pull/3 (draft, targets main).
Active checkout: `/home/t3dev/projects/small-trader-android`, branch `ux/stitch-native-prototype`.
Candidate source revision: `3a4e6c747161806447f3f0d98777333a8ef79ea7`.

## Candidate APK

- Path: `app/round2/validation/small-trader-candidate-benchmark.apk`
- Build variant: `benchmark`, R8/resource shrinking enabled, Android debug certificate; local prototype only.
- Size: 2,132,291 bytes.
- SHA-256: `07907c8c84c35eba03a0f3c9524eb8f0ac2455708ace685222ca13548409f9cb`.
- Manifest SHA-256: `cec768c60a8942c2b662bfd0e18f76d873128bb19df1cd483ae8d0c4e0aeb5de`.
- Build mirror: `app/build/outputs/apk/benchmark/app-benchmark.apk`.

## Current local verification

Saved XML reports in `app/build/test-results/testDebugUnitTest/`: 129 tests, 128 passed, one skipped, zero failures/errors. Lint debug completed successfully. Navigation captures and assertions cover 360dp/393dp, font scales 1/1.5/2, EN/AR, light/dark. Captures are under `app/round2/validation/native-renders-v03/`; these are Robolectric evidence, not device acceptance.

See [review remediation](PR3_REVIEW_REMEDIATION.md) for test commands, search harness results and per-finding limitations. Search measurements are host JVM measurements, not Android latency. APK sizes from different variants do not establish causal shrinkage gains.

## Remaining acceptance

No Android device/emulator was available. Device startup, scrolling/jank, memory, checkout responsiveness, TalkBack operation, keyboard/orientation/Back workflows and Windows Pixel acceptance remain unverified. Feature-sized ViewModel decomposition remains partial; state and purchase-dialog extraction are bounded steps, not completion of the broader refactor.

State remains an in-memory demo and resets on process restart. Scanning, printing and role switching remain simulated. Laravel/backend synchronization was not changed. Do not merge or deploy without owner approval.

For Windows review, copy the candidate APK above and install it on the intended test device. Verify AR RTL/EN LTR, manual themes, 360dp/393dp at all three font scales, payment failure/retry, draft resume, multiple purchase lines, partial receiving and replay protection. Use the Device panel for agent-driven interaction.

## Historical evidence and workspace

`docs/candidate-v3/` preserves the original 9336da6-era evidence (104 passed, one skipped); its hashes and counts describe that historical revision, not the current source. Earlier APKs remain preserved in the previous checkout and `app/round2/artifacts/baseline_0a965c0/`.

The previous T3 checkout `/home/t3dev/.t3/worktrees/small-trader-android/t3-fba3c97b` is detached at `3808ae2` and retained because earlier thread handoffs and artifacts reference it. Root crop/slice PNGs and local `app/round2/` artifacts are intentionally untracked and preserved. No destructive cleanup was performed. PRs #1 and #2 remain superseded; PR #3 is the cumulative review surface.
