# JVM outbox scale qualification

Root executed four opt-in tests at `fc5725c7175b5d402972e38e4dfc6970099c6f3b`, all passing after one disclosed harness failure. Production sources remain unchanged from `85d8c4e9`. Each scenario seeds 1,000 actual encrypted canonical receipts. See `manifest.json` for commands, source/log hashes, observations and limits; XML preserves raw sample output.

The ten-sample scan p50 was 117.15 ms and empirical p95/p99 167.60 ms. These are not stable tail estimates or low-end hardware results. Capture wait (180.56 ms) includes an intentional 100 ms test hold. Cancellation terminated in 355.34 ms with stored ciphertext/delivery state preserved. The background scan measurement does not measure Compose/catalog UI responsiveness. Device/native Keystore/upgrade/OEM and financial acceptance remain open.

Normal suites skip this opt-in harness. Run with `OUTBOX_BENCHMARK_ENABLED=true`; do not treat a skipped invocation as performance evidence. No production behavior or activation changed.
