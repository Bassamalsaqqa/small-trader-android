# Explicit Android session persistence

Baseline: `7786561ed956920ff12c86104a67e58123ba23cc`.

Sign-in defaults to an unchecked bilingual “Keep me signed in” control. Only opted-in successful sessions persist through the existing encrypted session store. Every new credential attempt clears the previous remembered token before acquiring a challenge, including unsuccessful opt-in or opt-out attempts. MFA refreshes preserve the current choice. Company switching preserves session-only behavior. Passwords remain transient; username/email and password fields register native Android Autofill Framework hints. Installation identity and outbox files are preserved.

Stitch project `8554078570650146519`: recovery reference `87901fdebaed40cab5ca37c02f0bf306`, retrieved and inspected; generated sign-in reference `bfe44f39cd3d438290b9cb2f433f9c46`, inspected for checkbox placement only. Unrequested generated PIN/helper content was not implemented. Existing native layout is retained with a 48dp toggle row.

Verification: broad JVM run executed 799 tests (792 passed, 1 failed, 6 skipped). The failure was the logout deletion-fault fixture now hitting the new pre-challenge purge; the fixture was corrected to restore its already-saved session before testing logout. Final focused session and staff UI run executed 158 tests, all passed, including that correction. JUnit does not count assertion invocations; the test diff adds 49 explicit assertion call sites. Release lint passed with 61 warnings and no errors. Final release build passed. No full broad rerun after the fixture-only correction. Device IME/password-manager behavior is not verified; Compose/autofill checks are JVM evidence only.

Reproduce with JDK 21 and Android SDK, using:

```sh
./gradlew --offline --max-workers=2 --console=plain \
  -Psmalltrader.staffWorkspaceOrigin=https://account.palsync.net \
  -Psmalltrader.staffReadWorkspace=false \
  -Psmalltrader.allowInsecureDebugSigningForRelease=true \
  :app:testDebugUnitTest --tests 'com.example.data.mobile.session.*' \
  --tests 'com.example.ui.staff.*' :app:lintRelease :app:assembleRelease
```

For the broad run omit both `--tests` selectors. Release version is 1.3 (code 4), full shell enabled, HTTP loopback disabled, origin verified in generated release configuration and APK DEX. Candidate signing certificate SHA-256: `f0995a82c2051d776a9307fcd75bb7d5862576714f1bd500885be2da0ca3e683`, matching the previous review candidate (explicitly authorized debug candidate signer).

No schema changes, push, merge, GitHub Actions, deployment, production access, or device UI actions. Backend workspace-route failure remains outside this Android change. Pre-existing untracked `app/round2/` restored byte-for-byte to the prior preserved artifact copy; generated renders and verification evidence are archived outside the source tree.
