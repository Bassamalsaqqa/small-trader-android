# Coordinated DRAFT contract revision 2

Previous Android Git head: `088f1f9d5fdb231b31d7bc1525092b500411cb5f`.
Previous manifest SHA-256: `a8d04a130bdb0bd909a7cfabdde93fb6f90c96cc07dbedfbd01971bdb07c6733`.
Revision 2 manifest SHA-256: `ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659`.
API prefix remains `/api/mobile/v1`; this is an explicit draft revision, not a silent frozen-mirror rewrite.

Read the revision 2 section of ADR-0001 for normative MFA retry, immutable staff/device identity, fresh tenant authorization, offline unavailable, nullable product tax association and parser boundary decisions. Backend correction evidence was independently read at Account commit `353ae4b252008d0fc1f0a532e11666a845757216`, `docs/integration/mobile-v1-correction-decisions.md`.

Width evidence from mature Account migrations: companies `2026_09_29_000001` (names 255), users `0001_01_01_000000` (Laravel default string 255), warehouses `2026_09_30_000019` (name 255/code 64), money accounts `2026_10_02_000036` and ledger accounts `2026_09_29_000013` (names 255; ledger code 32), tax rates `2026_10_02_000032` (name 255/code 32), units `2026_09_30_000018` (name 128), products `2026_09_30_000021` (name 255/SKU128), product barcodes `2026_09_30_000023` (128), customers `2026_10_02_000031` (name255/code64/phone64). No truncation. Default-unit DTO has no name field; base-unit and all-unit names use 128.

Bootstrap retains all canonical mapping requirements. OFF policy permits online bootstrap with complete mappings; missing walk-in/cash/warehouse mappings remain truthful configuration errors. Empty master lists do not imply sale readiness. Enabled policy shape is only a fully supplied proposal; explicit business review remains necessary. No numeric defaults are approved.

Financial protocol remains incomplete: public line tax selection must be added to online preview in a future reviewed amendment, with server resolution/validation of percentage and inclusive snapshots. Canonical SalesDraftIntent allows tax_rate_id, but the current public preview only has tax_rate. Null product tax pair means unknown product association, not zero tax. Explicit no-tax choice alone permits null line tax ID with zero. Offline tax identity likely requires a new event version. Never infer an ID from percentage or post the existing event by guessing a tax master. Immutable financial schemas and golden canonical/digest fixtures were not edited. Financial readiness is not claimed.

Validation: genuine pinned jsonschema/OpenAPI dependencies; normal and optimized Python each pass 210 counted checks, including 89 revision 2 positive/negative cases. Counts describe contract/fixture consistency, not server behavior. The validator uses full-string end checking to reject trailing-newline economic lexemes without changing financial schemas. Raw-byte, decoded UTF-16, escape and integer boundary requirements are explicitly documented; this revision does not implement Android/PHP parsers.

Coordination: backend mirror and consumer hash constants require separate parent-owned reviewed companion tasks. This work changes neither mirror nor consumers; supervisor receives the pushed exact head and manifest hash before mirroring. No deployment or merge is authorized.
