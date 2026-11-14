package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import com.example.data.mobile.contract.v2.OnlineSaleContext
import com.example.data.mobile.contract.v2.OnlineSaleIntentProfile
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Instant
import java.util.Arrays
import java.util.Locale

/**
 * Provisioned fixture entity identifiers and normative decimal lexemes for online cash pilot.
 * Authoritatively mirrors the backend provision fixture schema (13 required fields, no defaults).
 */
data class CashPilotFixture(
    val schemaName: String,
    val companyId: ServerUlid,
    val companyBaseCurrency: String,
    val currencyMinorUnits: Int,
    val staffActorId: ServerUlid,
    val deviceId: ClientUuid,
    val customerId: ServerUlid,
    val warehouseId: ServerUlid,
    val productId: ServerUlid,
    val productUnitId: ServerUlid,
    val taxRateId: ServerUlid,
    val cashAccountId: ServerUlid,
    val sampleEventId: ClientUuid
)

/**
 * Private runtime credentials loaded exclusively from a separate 0600 file outside checkout.
 * Accurately models exact backend provision fields: raw_token, token_hash, device_id, staff_email, schema.
 */
data class CashPilotCredentials(
    val rawToken: String,
    val tokenHash: String,
    val deviceId: ClientUuid,
    val staffEmail: String? = null,
    val schema: String? = null
) {
    override fun toString(): String =
        "CashPilotCredentials(rawToken=[REDACTED], tokenHash=[REDACTED], deviceId=$deviceId, staffEmail=[REDACTED_EMAIL], schema='$schema')"
}

/**
 * Authoritative endpoints and routes structure from backend handoff artifact.
 */
data class CashPilotEndpoints(
    val baseUrl: String,
    val loopbackBaseUrl: String,
    val onlineCashSaleRoute: String,
    val offlineEventStatusRoute: String,
    val readinessProbeRoute: String
)

/**
 * Exact retained raw wire sample and integrity hash.
 */
data class CashPilotRawWireSample(
    val rawWirePayloadJson: String,
    val rawWirePayloadSha256: String,
    val parsedRequest: StrictJsonObject
)

/**
 * Supervised startup TLS transport parameters.
 * Mandates distinct loopback host readiness URL (127.0.0.1) and Android emulator readiness URL (10.0.2.2).
 */
data class CashPilotStartupTls(
    val status: String,
    val caCertPath: String? = null,
    val caCertSha256: String? = null,
    val readinessUrl: String? = null,
    val androidReadinessUrl: String? = null,
    val readinessNonce: String? = null
)

/**
 * Strict CASH-specific handoff configuration parser and validator adhering to mobile-v2-client-handoff-schema.json.
 *
 * Invariants:
 * - 14 top-level required fields, 13 required fixture fields, 11 required backend runtime pins.
 * - Additional properties strictly forbidden across all objects (additionalProperties: false).
 * - Zero default fallbacks: currency_minor_units (0..6), currency, prices, and tax rates strictly validated.
 * - Private constructor prevents arbitrary public dataclass bypass.
 * - Single bounded read (<= 64 KiB), hashed once on opened bytes.
 * - Strict 0600/0400 file and 0700 directory permissions with symlink ancestor rejection (NOFOLLOW).
 * - Strict loopback HTTPS only (no plain HTTP, no off-host, no query/userinfo).
 * - Mandatory independent external environment variables: backend SHA, client SHA, handoff SHA.
 * - Client handoff schema SHA-256 pinned to authoritative edaef0309765e10d6462b36b5c660eb02f65c057c810ab72f756f7664e3ca275.
 * - Legacy scopes strictly removed; only ROOT_AUTHORIZED_LOCAL_PROVISION_ONLY, ROOT_AUTHORIZED_READINESS_ONLY,
 *   and ROOT_AUTHORIZED_PAIRED_EXECUTION permitted.
 * - Under PROVISION_ONLY: overall status is strictly NOT_RUN (never false PASS on 0 executed).
 * - Under READY: missing any CA/URL/nonce/TTL fails closed immediately (no downgrade).
 * - Financial execution strictly requires external lifecycle expected FINANCIAL_PAIRED_RUN and matching whole-file root approval SHA (no HMAC).
 * - Raw wire sample validated for exact canonical JCS UTF-8 bytes and SHA-256 wire integrity.
 * - All fixture IDs verified for parity with parsed_request and server_synthesized_context.
 * - Normative profile digest verified using fresh OnlineSaleIntentProfile computation and asserted distinct from raw wire SHA.
 */
class CashPilotHandoffConfig private constructor(
    val schemaVersion: String,
    val handoffScope: String,
    val lifecyclePhase: String,
    val createdAt: String,
    val expiresAt: String,
    val backendPins: Map<String, String>,
    val backendExecutionHeadSha: String,
    val peerAndroidHeadSha: String,
    val endpoints: CashPilotEndpoints,
    val fixture: CashPilotFixture,
    val credentialsPath: String,
    val credentials: CashPilotCredentials,
    val rawWireSample: CashPilotRawWireSample,
    val serverContext: OnlineSaleContext,
    val canonicalIntentProfileDigest: PayloadDigest,
    val historicalCa5aReference: Map<String, String>?,
    val startupTls: CashPilotStartupTls,
    val handoffFileSha256: String,
    val isProvisionOnly: Boolean,
    val isReadinessOnly: Boolean,
    val isFinancialApproved: Boolean,
    val baseUrl: String
) {
    override fun toString(): String =
        "CashPilotHandoffConfig(schemaVersion='$schemaVersion', scope='$handoffScope', " +
                "lifecyclePhase='$lifecyclePhase', baseUrl='$baseUrl', " +
                "backendExecutionHead='$backendExecutionHeadSha', peerAndroidHead='$peerAndroidHeadSha', " +
                "credentialsPath='[REDACTED_PATH]', credentials=$credentials, " +
                "isProvisionOnly=$isProvisionOnly, isReadinessOnly=$isReadinessOnly, isFinancialApproved=$isFinancialApproved)"

    companion object {
        const val ENV_CASH_PILOT_HANDOFF = "SMALL_TRADER_CASH_PILOT_HANDOFF"
        const val ENV_EXPECTED_BACKEND_SHA = "SMALL_TRADER_EXPECTED_BACKEND_SHA"
        const val ENV_EXPECTED_CLIENT_SHA = "SMALL_TRADER_EXPECTED_CLIENT_SHA"
        const val ENV_EXPECTED_HANDOFF_SHA = "SMALL_TRADER_EXPECTED_HANDOFF_SHA"
        const val ENV_EXPECTED_LIFECYCLE = "SMALL_TRADER_EXPECTED_LIFECYCLE"
        const val ENV_ROOT_APPROVED_HANDOFF_SHA = "SMALL_TRADER_ROOT_APPROVED_HANDOFF_SHA"

        const val EXPECTED_CLIENT_HANDOFF_SCHEMA_SHA256 =
            "edaef0309765e10d6462b36b5c660eb02f65c057c810ab72f756f7664e3ca275"

        const val PHASE_PROVISIONED_AWAITING_TLS = "PROVISIONED_AWAITING_TLS_START"
        const val PHASE_ACTIVE_TLS_READY = "ACTIVE_TLS_READY"
        const val PHASE_READINESS_ONLY_VERIFIED = "READINESS_ONLY_VERIFIED"

        const val SCOPE_LOCAL_PROVISION_ONLY = "ROOT_AUTHORIZED_LOCAL_PROVISION_ONLY"
        const val SCOPE_READINESS_ONLY = "ROOT_AUTHORIZED_READINESS_ONLY"
        const val SCOPE_PAIRED_EXECUTION = "ROOT_AUTHORIZED_PAIRED_EXECUTION"

        const val TLS_STATUS_UNAVAILABLE = "UNAVAILABLE_AWAITING_TLS_START"
        const val TLS_STATUS_ACTIVE_READY = "ACTIVE_TLS_READY"
        const val TLS_STATUS_READINESS_ONLY = "READINESS_ONLY_VERIFIED"

        const val LIFECYCLE_FINANCIAL_PAIRED_RUN = "FINANCIAL_PAIRED_RUN"
        const val LIFECYCLE_READINESS_ONLY = "READINESS_ONLY"

        val HEX_40_REGEX = Regex("^[0-9a-f]{40}$", RegexOption.IGNORE_CASE)
        val HEX_64_REGEX = Regex("^[0-9a-f]{64}$", RegexOption.IGNORE_CASE)
        val ISO_DATETIME_REGEX = Regex("^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$")
        val CROCKFORD_ULID_REGEX = Regex("^[0-7][0-9A-HJKMNP-TV-Z]{25}$")
        val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val CURRENCY_REGEX = Regex("^[A-Z]{3}$")

        const val MAX_FILE_SIZE_BYTES = 64 * 1024L

        val REQUIRED_TOP_LEVEL_KEYS = setOf(
            "schema_version", "handoff_scope", "lifecycle_phase", "created_at", "expires_at",
            "backend_runtime_pins", "endpoints", "fixture", "credentials_path", "raw_wire_sample",
            "server_synthesized_context", "canonical_intent_profile_digest", "historical_ca5a_reference", "startup_tls"
        )

        private val FORBIDDEN_INLINE_CREDENTIAL_KEYS = listOf(
            "raw_token", "token", "password", "staff_password", "secret", "bearer"
        )

        val REQUIRED_RUNTIME_PINS = setOf(
            "qualified_core_head_sha", "backend_execution_head_sha", "qualified_app_tree_sha",
            "peer_android_approved_head_sha", "peer_android_executable_head_sha",
            "source_fixture_sha256", "guard_test_sha256", "cli_command_sha256",
            "cli_runner_sha256", "pairing_manifest_sha256", "client_handoff_schema_sha256"
        )

        val REQUIRED_FIXTURE_KEYS = setOf(
            "schema_name", "company_public_id", "company_base_currency", "currency_minor_units",
            "staff_actor_public_id", "device_id", "customer_public_id", "warehouse_public_id",
            "product_public_id", "product_unit_public_id", "tax_rate_public_id", "cash_account_public_id",
            "sample_event_id"
        )

        val REQUIRED_STARTUP_TLS_KEYS = setOf(
            "status", "ca_cert_path", "ca_cert_sha256", "readiness_url", "android_readiness_url", "readiness_nonce"
        )

        val REQUIRED_ENDPOINTS_KEYS = setOf(
            "base_url", "loopback_base_url", "routes"
        )

        val REQUIRED_ROUTES_KEYS = setOf(
            "online_cash_sale", "offline_event_status", "readiness_probe"
        )

        val REQUIRED_SERVER_CONTEXT_KEYS = setOf(
            "company_id", "actor_id", "currency_minor_units"
        )

        val REQUIRED_HISTORICAL_CA5A_KEYS = setOf(
            "ca5a_manifest_sha256", "ca5a_canonical_digest"
        )

        val REQUIRED_RAW_WIRE_SAMPLE_KEYS = setOf(
            "raw_wire_payload_json", "raw_wire_payload_sha256", "parsed_request"
        )

        val REQUIRED_PARSED_REQUEST_KEYS = setOf(
            "client_event_id", "device_id", "customer_id", "warehouse_id", "currency", "lines", "payment"
        )

        val REQUIRED_PAYMENT_KEYS = setOf(
            "settlement_mode", "payment_method", "cash_account_id", "amount"
        )

        val REQUIRED_LINE_KEYS = setOf(
            "product_id", "product_unit_id", "conversion_to_base", "quantity", "unit_price",
            "tax_rate_id", "tax_rate", "tax_inclusive"
        )

        fun computeSha256(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            return digest.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        }

        fun validatePrivateFile(
            file: File,
            maxSizeBytes: Long = MAX_FILE_SIZE_BYTES,
            allowNonPosixForTesting: Boolean = false
        ) {
            require(file.exists()) { "File does not exist: [REDACTED_FILE]" }
            require(file.isFile) { "Expected regular file: [REDACTED_FILE]" }
            require(file.length() <= maxSizeBytes) {
                "File size (${file.length()} bytes) exceeds maximum bounded limit of $maxSizeBytes bytes"
            }

            var cur: File? = file
            while (cur != null) {
                if (Files.isSymbolicLink(cur.toPath())) {
                    throw SecurityException("Security violation: symlinks prohibited in private path: [REDACTED_PATH]")
                }
                cur = cur.parentFile
            }

            val posixView = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java)
            if (posixView == null) {
                if (!allowNonPosixForTesting) {
                    throw SecurityException("Security violation: non-POSIX filesystem rejected without explicit test seam")
                }
                return
            }

            val perms = posixView.readAttributes().permissions()
            val groupOrOther = setOf(
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE
            )
            val badPerms = perms.intersect(groupOrOther)
            if (badPerms.isNotEmpty()) {
                throw SecurityException("Security violation: file must be 0600 or 0400 (group/others denied), got $badPerms")
            }
            if (!perms.contains(PosixFilePermission.OWNER_READ)) {
                throw SecurityException("Security violation: file must be owner-readable")
            }

            val parent = file.parentFile
            if (parent != null) {
                val parentView = Files.getFileAttributeView(parent.toPath(), PosixFileAttributeView::class.java)
                if (parentView != null) {
                    val parentPerms = parentView.readAttributes().permissions()
                    val badParentPerms = parentPerms.intersect(groupOrOther)
                    if (badParentPerms.isNotEmpty()) {
                        throw SecurityException("Security violation: parent directory must be 0700 (group/others denied), got $badParentPerms")
                    }
                }
            }
        }

        fun readBoundedBytesOnce(
            file: File,
            maxSizeBytes: Long = MAX_FILE_SIZE_BYTES,
            allowNonPosixForTesting: Boolean = false
        ): ByteArray {
            validatePrivateFile(file, maxSizeBytes, allowNonPosixForTesting)
            return Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { stream ->
                val buf = ByteArray(maxSizeBytes.toInt() + 1)
                var total = 0
                while (true) {
                    val r = stream.read(buf, total, buf.size - total)
                    if (r == -1) break
                    total += r
                    if (total > maxSizeBytes) {
                        throw IllegalArgumentException("File content exceeds bounded limit of $maxSizeBytes bytes")
                    }
                }
                buf.copyOf(total)
            }
        }

        fun validateHttpsOrigin(rawUrl: String) {
            val uri = try {
                URI(rawUrl)
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed URL: '$rawUrl'", e)
            }
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "Strict loopback HTTPS only; plain HTTP prohibited: '$rawUrl'"
            }
            val host = uri.host ?: throw IllegalArgumentException("Missing host in URL: '$rawUrl'")
            val approvedHosts = setOf("127.0.0.1", "localhost", "10.0.2.2")
            require(host in approvedHosts) {
                "Prohibited off-host URL origin: host '$host' is not in approved loopback set $approvedHosts"
            }
            require(uri.userInfo == null) {
                "Prohibited userinfo in URL: '$rawUrl'"
            }
            require(uri.query == null) {
                "Prohibited query in URL: '$rawUrl'"
            }
            val path = uri.path ?: ""
            require(path.isEmpty() || path == "/" || path == "/api/mobile/v2" || path == "/_harness/readiness") {
                "Prohibited unexpected path in base URL: '$path'"
            }
        }

        fun fromFile(
            file: File,
            expectedBackendSha: String? = null,
            expectedClientSha: String? = null,
            expectedHandoffSha: String? = null,
            expectedLifecycle: String? = null,
            rootApprovedHandoffSha: String? = null,
            allowNonPosixForTesting: Boolean = false
        ): CashPilotHandoffConfig {
            val backendShaEnv = expectedBackendSha ?: System.getenv(ENV_EXPECTED_BACKEND_SHA)?.trim()
            require(!backendShaEnv.isNullOrBlank() && HEX_40_REGEX.matches(backendShaEnv)) {
                "Safety invariant violation: $ENV_EXPECTED_BACKEND_SHA is mandatory (40 hex lowercase); never trust handoff alone"
            }

            val clientShaEnv = expectedClientSha ?: System.getenv(ENV_EXPECTED_CLIENT_SHA)?.trim()
            require(!clientShaEnv.isNullOrBlank() && HEX_40_REGEX.matches(clientShaEnv)) {
                "Safety invariant violation: $ENV_EXPECTED_CLIENT_SHA is mandatory (40 hex lowercase); never trust handoff alone"
            }

            val handoffShaEnv = expectedHandoffSha ?: System.getenv(ENV_EXPECTED_HANDOFF_SHA)?.trim()
            require(!handoffShaEnv.isNullOrBlank() && HEX_64_REGEX.matches(handoffShaEnv)) {
                "Safety invariant violation: $ENV_EXPECTED_HANDOFF_SHA is mandatory (64 hex lowercase); never trust handoff alone"
            }

            val handoffBytes = readBoundedBytesOnce(file, allowNonPosixForTesting = allowNonPosixForTesting)
            val actualHandoffSha = computeSha256(handoffBytes)
            require(actualHandoffSha.equals(handoffShaEnv, ignoreCase = true)) {
                "Handoff file SHA-256 mismatch with independent expectation: expected $handoffShaEnv, got $actualHandoffSha"
            }

            return parseBytes(
                bytes = handoffBytes,
                actualHandoffSha = actualHandoffSha,
                expectedBackendSha = backendShaEnv,
                expectedClientSha = clientShaEnv,
                expectedLifecycle = expectedLifecycle ?: System.getenv(ENV_EXPECTED_LIFECYCLE)?.trim(),
                rootApprovedHandoffSha = rootApprovedHandoffSha ?: System.getenv(ENV_ROOT_APPROVED_HANDOFF_SHA)?.trim(),
                allowNonPosixForTesting = allowNonPosixForTesting
            )
        }

        fun parseBytes(
            bytes: ByteArray,
            actualHandoffSha: String,
            expectedBackendSha: String,
            expectedClientSha: String,
            expectedLifecycle: String? = null,
            rootApprovedHandoffSha: String? = null,
            allowNonPosixForTesting: Boolean = false
        ): CashPilotHandoffConfig {
            val jsonStr = String(bytes, Charsets.UTF_8)
            val root = StrictJsonParser.parse(jsonStr).asObject()

            for (key in FORBIDDEN_INLINE_CREDENTIAL_KEYS) {
                if (root[key] != null) {
                    throw SecurityException("Security violation: inline credential key '$key' forbidden in handoff JSON")
                }
            }

            val missingKeys = REQUIRED_TOP_LEVEL_KEYS - root.properties.keys
            if (missingKeys.isNotEmpty()) {
                throw RuntimeException("Malformed client handoff artifact: missing top-level field(s): $missingKeys")
            }
            val extraKeys = root.properties.keys - REQUIRED_TOP_LEVEL_KEYS
            if (extraKeys.isNotEmpty()) {
                throw RuntimeException("Malformed client handoff artifact: unexpected top-level field(s): $extraKeys")
            }

            val schemaVersion = root["schema_version"]?.asString()?.value ?: ""
            require(schemaVersion == "1.0.0") {
                "Unsupported schema_version '$schemaVersion'; expected '1.0.0'"
            }

            val handoffScope = root["handoff_scope"]?.asString()?.value ?: ""
            val allowedScopes = setOf(SCOPE_LOCAL_PROVISION_ONLY, SCOPE_READINESS_ONLY, SCOPE_PAIRED_EXECUTION)
            require(handoffScope in allowedScopes) {
                "Invalid handoff_scope '$handoffScope'; must be one of $allowedScopes"
            }

            val lifecyclePhase = root["lifecycle_phase"]?.asString()?.value ?: ""
            val allowedPhases = setOf(PHASE_PROVISIONED_AWAITING_TLS, PHASE_ACTIVE_TLS_READY, PHASE_READINESS_ONLY_VERIFIED)
            require(lifecyclePhase in allowedPhases) {
                "Invalid lifecycle_phase '$lifecyclePhase'; must be one of $allowedPhases"
            }

            val createdAt = root["created_at"]?.asString()?.value ?: ""
            require(ISO_DATETIME_REGEX.matches(createdAt)) {
                "Malformed created_at timestamp: '$createdAt'"
            }
            val expiresAt = root["expires_at"]?.asString()?.value ?: ""
            require(ISO_DATETIME_REGEX.matches(expiresAt)) {
                "Malformed expires_at timestamp: '$expiresAt'"
            }
            val createdInstant = try { Instant.parse(createdAt) } catch (_: Exception) { null }
            val expiresInstant = try { Instant.parse(expiresAt) } catch (_: Exception) { null }
            if (createdInstant != null && expiresInstant != null) {
                require(expiresInstant.isAfter(createdInstant)) {
                    "Malformed client handoff artifact: expires_at ($expiresAt) must be strictly after created_at ($createdAt)"
                }
                require(Instant.now().isBefore(expiresInstant)) {
                    "Safety invariant violation: handoff TTL expired at $expiresAt"
                }
            }

            // Backend runtime pins (exact 11 pins, no extra keys)
            val pinsObj = root["backend_runtime_pins"]?.asObject()
                ?: throw RuntimeException("backend_runtime_pins must be an object")
            val missingPins = REQUIRED_RUNTIME_PINS - pinsObj.properties.keys
            if (missingPins.isNotEmpty()) {
                throw RuntimeException("Missing backend_runtime_pins: $missingPins")
            }
            val extraPins = pinsObj.properties.keys - REQUIRED_RUNTIME_PINS
            if (extraPins.isNotEmpty()) {
                throw RuntimeException("Unexpected backend_runtime_pins: $extraPins")
            }
            val pinsMap = pinsObj.properties.mapValues { it.value.asString()?.value ?: "" }

            val sha40PinKeys = setOf(
                "qualified_core_head_sha", "backend_execution_head_sha", "qualified_app_tree_sha",
                "peer_android_approved_head_sha", "peer_android_executable_head_sha"
            )
            for (k in sha40PinKeys) {
                val v = pinsMap[k] ?: ""
                require(HEX_40_REGEX.matches(v)) { "Pin '$k' must be 40 hex characters: '$v'" }
            }
            val sha256PinKeys = setOf(
                "client_handoff_schema_sha256", "source_fixture_sha256", "guard_test_sha256",
                "cli_command_sha256", "cli_runner_sha256", "pairing_manifest_sha256"
            )
            for (k in sha256PinKeys) {
                val v = pinsMap[k] ?: ""
                require(HEX_64_REGEX.matches(v)) { "Pin '$k' must be 64 hex characters: '$v'" }
            }

            // Pinned client handoff schema SHA-256 verification
            val clientHandoffSchemaSha = pinsMap["client_handoff_schema_sha256"] ?: ""
            require(clientHandoffSchemaSha.equals(EXPECTED_CLIENT_HANDOFF_SCHEMA_SHA256, ignoreCase = true)) {
                "client_handoff_schema_sha256 mismatch: expected $EXPECTED_CLIENT_HANDOFF_SCHEMA_SHA256, got $clientHandoffSchemaSha"
            }

            val backendExecutionHeadSha = pinsMap["backend_execution_head_sha"] ?: ""
            val peerAndroidHeadSha = pinsMap["peer_android_executable_head_sha"] ?: ""
            require(backendExecutionHeadSha.equals(expectedBackendSha, ignoreCase = true)) {
                "Backend execution HEAD pin mismatch: expected $expectedBackendSha, got $backendExecutionHeadSha"
            }
            require(peerAndroidHeadSha.equals(expectedClientSha, ignoreCase = true)) {
                "Peer Android executable HEAD pin mismatch: expected $expectedClientSha, got $peerAndroidHeadSha"
            }

            // Endpoints
            val endpointsObj = root["endpoints"]?.asObject() ?: throw RuntimeException("endpoints must be an object")
            val missingEndpoints = REQUIRED_ENDPOINTS_KEYS - endpointsObj.properties.keys
            if (missingEndpoints.isNotEmpty()) {
                throw RuntimeException("Missing endpoints keys: $missingEndpoints")
            }
            val extraEndpoints = endpointsObj.properties.keys - REQUIRED_ENDPOINTS_KEYS
            if (extraEndpoints.isNotEmpty()) {
                throw RuntimeException("Unexpected endpoints keys: $extraEndpoints")
            }
            val baseUrl = endpointsObj["base_url"]?.asString()?.value ?: ""
            val loopbackBaseUrl = endpointsObj["loopback_base_url"]?.asString()?.value ?: ""
            validateHttpsOrigin(baseUrl)
            validateHttpsOrigin(loopbackBaseUrl)

            val routesObj = endpointsObj["routes"]?.asObject() ?: throw RuntimeException("endpoints.routes must be an object")
            val missingRoutes = REQUIRED_ROUTES_KEYS - routesObj.properties.keys
            if (missingRoutes.isNotEmpty()) {
                throw RuntimeException("Missing routes keys: $missingRoutes")
            }
            val extraRoutes = routesObj.properties.keys - REQUIRED_ROUTES_KEYS
            if (extraRoutes.isNotEmpty()) {
                throw RuntimeException("Unexpected routes keys: $extraRoutes")
            }
            val onlineCashRoute = routesObj["online_cash_sale"]?.asString()?.value ?: ""
            val offlineStatusRoute = routesObj["offline_event_status"]?.asString()?.value ?: ""
            val readinessRoute = routesObj["readiness_probe"]?.asString()?.value ?: ""
            val endpoints = CashPilotEndpoints(
                baseUrl = baseUrl,
                loopbackBaseUrl = loopbackBaseUrl,
                onlineCashSaleRoute = onlineCashRoute,
                offlineEventStatusRoute = offlineStatusRoute,
                readinessProbeRoute = readinessRoute
            )

            // Fixture (exact 13 required fields, no defaults, no extra keys)
            val fixtureObj = root["fixture"]?.asObject() ?: throw RuntimeException("fixture must be an object")
            val missingFixture = REQUIRED_FIXTURE_KEYS - fixtureObj.properties.keys
            if (missingFixture.isNotEmpty()) {
                throw RuntimeException("Missing fixture field(s): $missingFixture")
            }
            val extraFixture = fixtureObj.properties.keys - REQUIRED_FIXTURE_KEYS
            if (extraFixture.isNotEmpty()) {
                throw RuntimeException("Unexpected fixture field(s): $extraFixture")
            }

            val schemaName = fixtureObj["schema_name"]?.asString()?.value
                ?: throw RuntimeException("fixture missing 'schema_name'")
            val companyPubId = fixtureObj["company_public_id"]?.asString()?.value
                ?: throw RuntimeException("fixture missing 'company_public_id'")
            val baseCurrency = fixtureObj["company_base_currency"]?.asString()?.value
                ?: throw RuntimeException("fixture missing 'company_base_currency'")
            require(CURRENCY_REGEX.matches(baseCurrency)) {
                "fixture 'company_base_currency' must match $CURRENCY_REGEX: '$baseCurrency'"
            }
            val minorUnitsElem = fixtureObj["currency_minor_units"]
                ?: throw RuntimeException("fixture missing 'currency_minor_units'")
            val fixtureMinorUnits = minorUnitsElem.asNumber()?.value?.toInt()
                ?: throw RuntimeException("fixture 'currency_minor_units' must be an integer (no defaults)")
            require(fixtureMinorUnits in 0..6) {
                "fixture 'currency_minor_units' must be in 0..6, got $fixtureMinorUnits"
            }
            val staffPubId = fixtureObj["staff_actor_public_id"]?.asString()?.value
                ?: throw RuntimeException("fixture missing 'staff_actor_public_id'")
            val devId = fixtureObj["device_id"]?.asString()?.value
                ?: throw RuntimeException("fixture missing 'device_id'")
            val custPubId = fixtureObj["customer_public_id"]?.asString()?.value
                ?: throw RuntimeException("fixture missing 'customer_public_id'")
            val whPubId = fixtureObj["warehouse_public_id"]?.asString()?.value
                ?: throw RuntimeException("fixture missing 'warehouse_public_id'")
            val prodPubId = fixtureObj["product_public_id"]?.asString()?.value
                ?: throw RuntimeException("fixture missing 'product_public_id'")
            val prodUnitPubId = fixtureObj["product_unit_public_id"]?.asString()?.value
                ?: throw RuntimeException("fixture missing 'product_unit_public_id'")
            val taxRatePubId = fixtureObj["tax_rate_public_id"]?.asString()?.value
                ?: throw RuntimeException("fixture missing 'tax_rate_public_id'")
            val cashAccPubId = fixtureObj["cash_account_public_id"]?.asString()?.value
                ?: throw RuntimeException("fixture missing 'cash_account_public_id'")
            val sampleEvtId = fixtureObj["sample_event_id"]?.asString()?.value
                ?: throw RuntimeException("fixture missing 'sample_event_id'")

            val fixture = CashPilotFixture(
                schemaName = schemaName,
                companyId = ServerUlid.parse(companyPubId),
                companyBaseCurrency = baseCurrency,
                currencyMinorUnits = fixtureMinorUnits,
                staffActorId = ServerUlid.parse(staffPubId),
                deviceId = ClientUuid.parse(devId),
                customerId = ServerUlid.parse(custPubId),
                warehouseId = ServerUlid.parse(whPubId),
                productId = ServerUlid.parse(prodPubId),
                productUnitId = ServerUlid.parse(prodUnitPubId),
                taxRateId = ServerUlid.parse(taxRatePubId),
                cashAccountId = ServerUlid.parse(cashAccPubId),
                sampleEventId = ClientUuid.parse(sampleEvtId)
            )

            // Raw wire sample
            val rawWireObj = root["raw_wire_sample"]?.asObject() ?: throw RuntimeException("raw_wire_sample must be an object")
            val missingRawWire = REQUIRED_RAW_WIRE_SAMPLE_KEYS - rawWireObj.properties.keys
            if (missingRawWire.isNotEmpty()) {
                throw RuntimeException("Missing raw_wire_sample key(s): $missingRawWire")
            }
            val extraRawWire = rawWireObj.properties.keys - REQUIRED_RAW_WIRE_SAMPLE_KEYS
            if (extraRawWire.isNotEmpty()) {
                throw RuntimeException("Unexpected raw_wire_sample key(s): $extraRawWire")
            }
            val rawPayloadJson = rawWireObj["raw_wire_payload_json"]?.asString()?.value
                ?: throw RuntimeException("raw_wire_sample missing 'raw_wire_payload_json'")
            val rawPayloadSha = rawWireObj["raw_wire_payload_sha256"]?.asString()?.value
                ?: throw RuntimeException("raw_wire_sample missing 'raw_wire_payload_sha256'")
            require(HEX_64_REGEX.matches(rawPayloadSha)) {
                "raw_wire_payload_sha256 must be 64 lowercase hex characters: '$rawPayloadSha'"
            }
            val calcPayloadSha = computeSha256(rawPayloadJson.toByteArray(Charsets.UTF_8))
            require(calcPayloadSha.equals(rawPayloadSha, ignoreCase = true)) {
                "Raw wire payload SHA-256 integrity mismatch: expected $rawPayloadSha, computed $calcPayloadSha"
            }
            val parsedReq = rawWireObj["parsed_request"]?.asObject()
                ?: throw RuntimeException("raw_wire_sample.parsed_request must be an object")

            val missingParsed = REQUIRED_PARSED_REQUEST_KEYS - parsedReq.properties.keys
            if (missingParsed.isNotEmpty()) {
                throw RuntimeException("Missing parsed_request key(s): $missingParsed")
            }
            val extraParsed = parsedReq.properties.keys - REQUIRED_PARSED_REQUEST_KEYS
            if (extraParsed.isNotEmpty()) {
                throw RuntimeException("Unexpected parsed_request key(s): $extraParsed")
            }

            // Strict RFC 8785 canonical byte equality check against raw_wire_payload_json
            val canonicalParsedBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(parsedReq)
            val rawWireBytes = rawPayloadJson.toByteArray(Charsets.UTF_8)
            require(Arrays.equals(canonicalParsedBytes, rawWireBytes)) {
                "raw_wire_sample parsed_request does not canonically match raw_wire_payload_json bytes"
            }

            val rawWireSample = CashPilotRawWireSample(
                rawWirePayloadJson = rawPayloadJson,
                rawWirePayloadSha256 = rawPayloadSha,
                parsedRequest = parsedReq
            )

            // Parity checks between parsed request wire IDs and fixture
            require(parsedReq["customer_id"]?.asString()?.value == fixture.customerId.value) { "Customer ID parity mismatch" }
            require(parsedReq["warehouse_id"]?.asString()?.value == fixture.warehouseId.value) { "Warehouse ID parity mismatch" }
            require(parsedReq["device_id"]?.asString()?.value == fixture.deviceId.value) { "Device ID parity mismatch" }
            require(parsedReq["currency"]?.asString()?.value == fixture.companyBaseCurrency) { "Currency parity mismatch" }

            // Validate lines
            val linesArray = parsedReq["lines"]?.asArray()
                ?: throw RuntimeException("parsed_request missing 'lines' array")
            require(linesArray.size > 0) { "parsed_request lines must have at least one line" }
            for (i in 0 until linesArray.size) {
                val lineObj = linesArray[i].asObject()
                val missingLineKeys = REQUIRED_LINE_KEYS - lineObj.properties.keys
                if (missingLineKeys.isNotEmpty()) {
                    throw RuntimeException("Line $i missing key(s): $missingLineKeys")
                }
                val extraLineKeys = lineObj.properties.keys - REQUIRED_LINE_KEYS
                if (extraLineKeys.isNotEmpty()) {
                    throw RuntimeException("Line $i unexpected key(s): $extraLineKeys")
                }
            }

            // Validate payment: full cash settlement with non-null ULID
            val paymentObj = parsedReq["payment"]?.asObject()
                ?: throw RuntimeException("parsed_request missing 'payment' object")
            val missingPaymentKeys = REQUIRED_PAYMENT_KEYS - paymentObj.properties.keys
            if (missingPaymentKeys.isNotEmpty()) {
                throw RuntimeException("payment missing key(s): $missingPaymentKeys")
            }
            val extraPaymentKeys = paymentObj.properties.keys - REQUIRED_PAYMENT_KEYS
            if (extraPaymentKeys.isNotEmpty()) {
                throw RuntimeException("payment unexpected key(s): $extraPaymentKeys")
            }
            val settlementMode = paymentObj["settlement_mode"]?.asString()?.value
            require(settlementMode == "full") {
                "Cash pilot payment settlement_mode must be 'full', got '$settlementMode'"
            }
            val paymentMethod = paymentObj["payment_method"]?.asString()?.value
            require(paymentMethod == "cash") {
                "Cash pilot payment payment_method must be 'cash', got '$paymentMethod'"
            }
            val cashAccId = paymentObj["cash_account_id"]?.asString()?.value
                ?: throw RuntimeException("payment missing 'cash_account_id'")
            require(CROCKFORD_ULID_REGEX.matches(cashAccId)) {
                "payment 'cash_account_id' must be a valid non-null ULID: '$cashAccId'"
            }
            require(cashAccId == fixture.cashAccountId.value) {
                "payment cash_account_id ($cashAccId) mismatch vs fixture (${fixture.cashAccountId.value})"
            }

            // Server synthesized context (no defaults!)
            val ctxObj = root["server_synthesized_context"]?.asObject()
                ?: throw RuntimeException("server_synthesized_context must be an object")
            val missingCtx = REQUIRED_SERVER_CONTEXT_KEYS - ctxObj.properties.keys
            if (missingCtx.isNotEmpty()) {
                throw RuntimeException("Missing server_synthesized_context key(s): $missingCtx")
            }
            val extraCtx = ctxObj.properties.keys - REQUIRED_SERVER_CONTEXT_KEYS
            if (extraCtx.isNotEmpty()) {
                throw RuntimeException("Unexpected server_synthesized_context key(s): $extraCtx")
            }
            val ctxCompanyIdStr = ctxObj["company_id"]?.asString()?.value
                ?: throw RuntimeException("server_synthesized_context missing 'company_id'")
            val ctxActorIdStr = ctxObj["actor_id"]?.asString()?.value
                ?: throw RuntimeException("server_synthesized_context missing 'actor_id'")
            val ctxMinorUnitsElem = ctxObj["currency_minor_units"]
                ?: throw RuntimeException("server_synthesized_context missing 'currency_minor_units'")
            val ctxMinorUnits = ctxMinorUnitsElem.asNumber()?.value?.toInt()
                ?: throw RuntimeException("server_synthesized_context 'currency_minor_units' must be an integer (no defaults)")
            require(ctxMinorUnits in 0..6) {
                "server_synthesized_context 'currency_minor_units' must be in 0..6, got $ctxMinorUnits"
            }

            val ctxCompanyId = ServerUlid.parse(ctxCompanyIdStr)
            val ctxActorId = ServerUlid.parse(ctxActorIdStr)
            require(ctxCompanyId == fixture.companyId) { "Context company ID mismatch with fixture" }
            require(ctxActorId == fixture.staffActorId) { "Context actor ID mismatch with fixture" }
            require(ctxMinorUnits == fixture.currencyMinorUnits) { "Context minor units mismatch with fixture" }
            val serverContext = OnlineSaleContext(
                companyId = ctxCompanyId,
                actorId = ctxActorId,
                currencyMinorUnits = ctxMinorUnits
            )

            // Canonical intent profile digest computation & verification
            val declaredDigestStr = root["canonical_intent_profile_digest"]?.asString()?.value
                ?: throw RuntimeException("Missing 'canonical_intent_profile_digest'")
            require(declaredDigestStr.startsWith("sha256:") && declaredDigestStr.length == 71) {
                "canonical_intent_profile_digest must begin with 'sha256:' followed by 64 hex characters: '$declaredDigestStr'"
            }
            val profile = OnlineSaleIntentProfile.fromContextAndRequest(serverContext, parsedReq)
            val computedDigest = profile.computePayloadDigest()
            require(computedDigest.value == declaredDigestStr) {
                "Canonical intent profile digest mismatch: declared '$declaredDigestStr', computed '${computedDigest.value}'"
            }
            // Ensure profile digest is distinct from raw wire payload hash
            require(computedDigest.value != rawPayloadSha && !computedDigest.value.endsWith(rawPayloadSha)) {
                "Safety invariant violation: canonical intent profile digest must be distinct from raw wire payload sha256"
            }

            // Credentials path & loading
            val credentialsPath = root["credentials_path"]?.asString()?.value ?: ""
            require(credentialsPath.isNotBlank() && credentialsPath.startsWith("/") && credentialsPath.endsWith(".json")) {
                "credentials_path must be an absolute path ending in .json starting with /"
            }
            val credentials = loadCredentials(File(credentialsPath), allowNonPosixForTesting)
            require(credentials.deviceId == fixture.deviceId) {
                "Credentials device_id (${credentials.deviceId}) does not match fixture device_id (${fixture.deviceId})"
            }

            // Historical ca5a reference
            val historicalObj = root["historical_ca5a_reference"]?.asObject()
                ?: throw RuntimeException("historical_ca5a_reference must be an object")
            val missingHist = REQUIRED_HISTORICAL_CA5A_KEYS - historicalObj.properties.keys
            if (missingHist.isNotEmpty()) {
                throw RuntimeException("Missing historical_ca5a_reference key(s): $missingHist")
            }
            val extraHist = historicalObj.properties.keys - REQUIRED_HISTORICAL_CA5A_KEYS
            if (extraHist.isNotEmpty()) {
                throw RuntimeException("Unexpected historical_ca5a_reference key(s): $extraHist")
            }
            val historicalMap = historicalObj.properties.mapValues { it.value.asString()?.value ?: "" }
            for ((hk, hv) in historicalMap) {
                require(HEX_64_REGEX.matches(hv)) { "Historical key '$hk' must be 64 lowercase hex: '$hv'" }
            }

            // Startup TLS (exact 6 keys required, no extra keys)
            val tlsObj = root["startup_tls"]?.asObject() ?: throw RuntimeException("startup_tls must be an object")
            val missingTls = REQUIRED_STARTUP_TLS_KEYS - tlsObj.properties.keys
            if (missingTls.isNotEmpty()) {
                throw RuntimeException("Missing startup_tls key(s): $missingTls")
            }
            val extraTls = tlsObj.properties.keys - REQUIRED_STARTUP_TLS_KEYS
            if (extraTls.isNotEmpty()) {
                throw RuntimeException("Unexpected startup_tls key(s): $extraTls")
            }

            val tlsStatus = tlsObj["status"]?.asString()?.value ?: ""
            val allowedTlsStatuses = setOf(TLS_STATUS_UNAVAILABLE, TLS_STATUS_ACTIVE_READY, TLS_STATUS_READINESS_ONLY)
            require(tlsStatus in allowedTlsStatuses) {
                "Invalid startup_tls.status '$tlsStatus'; must be one of $allowedTlsStatuses"
            }

            val caCertPath = tlsObj["ca_cert_path"]?.asString()?.value
            val caCertSha256 = tlsObj["ca_cert_sha256"]?.asString()?.value
            val readinessUrl = tlsObj["readiness_url"]?.asString()?.value
            val androidReadinessUrl = tlsObj["android_readiness_url"]?.asString()?.value
            val readinessNonce = tlsObj["readiness_nonce"]?.asString()?.value

            val isProvisionOnly = handoffScope == SCOPE_LOCAL_PROVISION_ONLY ||
                    lifecyclePhase == PHASE_PROVISIONED_AWAITING_TLS ||
                    tlsStatus == TLS_STATUS_UNAVAILABLE

            if (isProvisionOnly) {
                require(caCertPath == null && caCertSha256 == null && readinessUrl == null && androidReadinessUrl == null && readinessNonce == null) {
                    "startup_tls fields (ca_cert_path, ca_cert_sha256, readiness_url, android_readiness_url, readiness_nonce) must all be null when awaiting start"
                }
            } else {
                require(!caCertPath.isNullOrBlank()) { "startup_tls.ca_cert_path is required when ready" }
                val caFile = File(caCertPath)
                require(caFile.exists() && caFile.isFile) { "CA cert file not found: [REDACTED_FILE]" }
                require(!caCertSha256.isNullOrBlank() && HEX_64_REGEX.matches(caCertSha256)) {
                    "startup_tls.ca_cert_sha256 is required and must be 64-hex when ready"
                }
                val caBytes = readBoundedBytesOnce(caFile, allowNonPosixForTesting = allowNonPosixForTesting)
                val actualCaSha = computeSha256(caBytes)
                require(actualCaSha.equals(caCertSha256, ignoreCase = true)) {
                    "CA cert SHA-256 integrity mismatch: expected $caCertSha256, computed $actualCaSha"
                }

                require(!readinessUrl.isNullOrBlank()) { "startup_tls.readiness_url is required when ready" }
                validateHttpsOrigin(readinessUrl)
                val hostUri = URI(readinessUrl)
                require(hostUri.host == "127.0.0.1" || hostUri.host == "localhost") {
                    "startup_tls.readiness_url must be host loopback (127.0.0.1), got '${hostUri.host}'"
                }

                require(!androidReadinessUrl.isNullOrBlank()) { "startup_tls.android_readiness_url is required when ready" }
                validateHttpsOrigin(androidReadinessUrl)
                val androidUri = URI(androidReadinessUrl)
                require(androidUri.host == "10.0.2.2") {
                    "startup_tls.android_readiness_url must be Android emulator loopback (10.0.2.2), got '${androidUri.host}'"
                }

                require(!readinessNonce.isNullOrBlank() && readinessNonce.length in 32..64) {
                    "startup_tls.readiness_nonce is required when ready (32-64 hex chars)"
                }
            }

            val startupTls = CashPilotStartupTls(
                status = tlsStatus,
                caCertPath = caCertPath,
                caCertSha256 = caCertSha256,
                readinessUrl = readinessUrl,
                androidReadinessUrl = androidReadinessUrl,
                readinessNonce = readinessNonce
            )

            val isReadinessOnly = handoffScope == SCOPE_READINESS_ONLY ||
                    lifecyclePhase == PHASE_READINESS_ONLY_VERIFIED ||
                    tlsStatus == TLS_STATUS_READINESS_ONLY

            val isFinancialApproved = handoffScope == SCOPE_PAIRED_EXECUTION &&
                    !isProvisionOnly &&
                    !isReadinessOnly &&
                    expectedLifecycle == LIFECYCLE_FINANCIAL_PAIRED_RUN &&
                    !rootApprovedHandoffSha.isNullOrBlank() &&
                    actualHandoffSha.equals(rootApprovedHandoffSha, ignoreCase = true)

            val activeBaseUrl = loopbackBaseUrl.ifBlank { baseUrl }

            return CashPilotHandoffConfig(
                schemaVersion = schemaVersion,
                handoffScope = handoffScope,
                lifecyclePhase = lifecyclePhase,
                createdAt = createdAt,
                expiresAt = expiresAt,
                backendPins = pinsMap,
                backendExecutionHeadSha = backendExecutionHeadSha,
                peerAndroidHeadSha = peerAndroidHeadSha,
                endpoints = endpoints,
                fixture = fixture,
                credentialsPath = credentialsPath,
                credentials = credentials,
                rawWireSample = rawWireSample,
                serverContext = serverContext,
                canonicalIntentProfileDigest = computedDigest,
                historicalCa5aReference = historicalMap,
                startupTls = startupTls,
                handoffFileSha256 = actualHandoffSha,
                isProvisionOnly = isProvisionOnly,
                isReadinessOnly = isReadinessOnly,
                isFinancialApproved = isFinancialApproved,
                baseUrl = activeBaseUrl
            )
        }

        private fun loadCredentials(credFile: File, allowNonPosixForTesting: Boolean): CashPilotCredentials {
            val bytes = readBoundedBytesOnce(credFile, allowNonPosixForTesting = allowNonPosixForTesting)
            val root = StrictJsonParser.parse(String(bytes, Charsets.UTF_8)).asObject()

            val rawToken = root["raw_token"]?.asString()?.value
                ?: throw RuntimeException("Credentials missing 'raw_token'")
            val tokenHash = root["token_hash"]?.asString()?.value
                ?: throw RuntimeException("Credentials missing 'token_hash'")
            val devIdStr = root["device_id"]?.asString()?.value
                ?: throw RuntimeException("Credentials missing 'device_id'")

            val computedTokenHash = computeSha256(rawToken.toByteArray(Charsets.UTF_8))
            require(computedTokenHash.equals(tokenHash, ignoreCase = true)) {
                "Runtime credentials token hash verification failed"
            }

            return CashPilotCredentials(
                rawToken = rawToken,
                tokenHash = tokenHash,
                deviceId = ClientUuid.parse(devIdStr),
                staffEmail = root["staff_email"]?.asString()?.value,
                schema = root["schema"]?.asString()?.value
            )
        }
    }
}
