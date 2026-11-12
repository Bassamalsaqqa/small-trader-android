# Android integration candidate

Draft PR 19 assembles the reviewed Android branches without merging to main. Its source heads are PR 18 (`46c2cf7`), PR 15 (`d7e4504`), PR 7 (`cfb331d`), PR 8 (`6452196`), and PR 9 (`4a249ca`), including their stacked ancestors.

The candidate contains revision 3 financial models and manifest `e8e3fe672a8f7aceb3152c3dd990b4fb137fa517b24038e468659e0fdf5a87a7`. Runtime staff authentication and lookup continue using frozen API v1 representations. No financial API v2 gateway, capture UI, or background worker is activated. Room schema version 1 and identity `6eddfecc8dabbb7ec370b858ec36714a` are unchanged.

An accepted token-denial event locks the exact company/actor/device outbox scope, including an empty queue. Original event bytes, digests, and confirmed records are preserved. Login and successful reads never unlock the queue. Any future unlock policy requires separate qualification; a manager workflow is not implemented here.

The synchronous bridge requires a background caller and rejects main-thread calls before opening a repository. Staff coordinator operations already run on IO. The application-context repository is created lazily on the first accepted denial; Room construction/open failures reach the coordinator's locked error path rather than occurring during Compose setup. There is no main-thread blocking fallback and no fatal-error catch.

Supervisor verification before the bridge follow-up: 392 focused tests across 37 classes, zero failures/errors/skips. The contract subset is **88**, not 89 tests. Follow-up coverage adds main-thread misuse rejection before repository initialization and uses the actual lazy bridge to inject repository failure. Supervisor follow-up run: 393 tests across 37 classes, zero failures/errors/skips; lintDebug zero errors and 63 warnings. Contract validators passed all 303 checks in both normal and optimized modes.

Remaining acceptance gates:

- Physical-device layout, Arabic RTL/focus/IME/TalkBack, hardware Keystore and real endpoint/TLS validation.
- Backend draft integration, financial grants and real atomic posting/receipt uniqueness across API versions, absence-row races, rollback and concurrency proof.
- Historical currency-precision policy and offline business policy; offline capture stays disabled.
- Backend PR 88's O(n) catalog hash snapshot scan. This is not simply an unindexed product-search query. Backend PR 92's dual-OFF boundary is already qualified; financial routes and grants are separate pending work.

Platform CA and hostname verification are used; custom certificate pinning is neither configured nor required by this candidate. Functional tests and source reviews do not grant owner merge, deployment, or release approval.
