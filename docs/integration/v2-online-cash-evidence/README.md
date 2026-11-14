# V2 Online Cash Client & Orchestrator Evidence (PR #23)

## Architectural Boundaries and Review Remediation

This directory records the verification evidence, schemas, and architectural boundaries for the Mobile V2 Online Cash client implementation.

### Boundary Preservation
1. **Separation from Durable Offline Outbox**:
   The offline queue coordinator (`SaleSyncCoordinator`) and gateway interface (`SaleSyncGateway`) remain solely responsible for 15-field durable offline sale events. Direct online cash transactions use the dedicated `OnlineCashSaleOrchestrator` with `PreparedOnlineSaleIntent`.
2. **Removal of Unwired Adapter**:
   `OnlineCashSaleSyncGateway` was removed. It had incorrectly attempted to pass 15-field offline events to the 7-key online cash endpoint and fabricated payment references via `paymentId ?: invoiceId`.
3. **No Synthetic Payment Fabrication**:
   For approved full cash or online sale commits, `OnlineCashSaleReceipt` strictly preserves `paymentId: ServerUlid?` as returned by the backend. When `payment_id` is null on the wire, the result is schema-honest (`PostedWithoutPaymentReference`) without fabricating synthetic identifiers.
4. **Normative Intent Profile and Bounded Wire Payload**:
   `PreparedOnlineSaleIntent` enforces strict 1 MiB bounding, strict UTF-8 validation (rejecting malformed/unmappable sequences), canonical JSON with exact decimal strings (no IEEE-754 floats), and pre-dispatch parity checks against tenant company, device, currency, and client event ID.
   The expected payload digest is computed strictly from `OnlineSaleIntentProfile.fromContextAndRequest(context, raw7KeyRequest)` yielding authoritative profile digest `0847` (`sha256:0847e10d89777e103252ebfa2d27f89a95832a5d70e01af57afe6423f703c630`).
5. **Lost-ACK Status-First Recovery & Non-Null Digest Invariant**:
   If an initial POST drops or times out, `AndroidV2OnlineCashClient` queries event status before attempting replay. When status is `posted`, `payload_digest` MUST be non-null and match the expected normative digest; missing or mismatched digests fail closed. When status is `unknown`, safe replay of the EXACT original prepared wire bytes is performed.
6. **Scope Generation Authentication Guard**:
   In-flight 401 Unauthorized responses check against the active session scope generation. A stale 401 callback from an older scope generation will not trigger an auth lock on a new session scope.
7. **Local Token Non-Denial**:
   Absence of a local staff session token yields `LocalTokenUnavailable` without dispatching HTTP or triggering server authentication lock callbacks.
8. **Isolated Smoke Pilot**:
   `SmallTraderOnlineCashPilotSmokeTest` requires explicit `SMALL_TRADER_CASH_PILOT_HANDOFF` and independent backend SHA (`SMALL_TRADER_EXPECTED_BACKEND_SHA`). Fallback to V1 read pilot handoff is completely removed.

## Test Artifacts and Historical Evidence
- `historical-76/`: Preserves 8 XML test execution reports (76 passing tests) executed prior to lease boundaries.
- `manifest.json`: Exact SHA-256 source and fixture attestation.
- `summary.json`: Test counts and lease execution gate status.
