package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.contract.ClientUuid
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
import java.util.Locale

/**
 * Provisioned fixture entity identifiers and normative decimal lexemes for online cash pilot.
 * Authoritatively mirrors the backend provision fixture schema.
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
 */
data class CashPilotStartupTls(
    val status: String,
    val caCertPath: String? = null,
    val caCertSha256: String? = null,
    val readinessUrl: String? = null,
    val readinessNonce: String? = null
)

/**
 * Strict CASH-specific handoff configuration parser and validator adhering to mobile-v2-client-handoff-schema.json.
 *
 * Invariants:
 * - Private constructor prevents arbitrary public dataclass bypass.
 * - Single bounded read (<= 64 KiB), hashed once on opened bytes.
 * - Strict 0600/0400 file and 0700 directory permissions with symlink ancestor rejection.
 * - Strict loopback HTTPS only (no plain HTTP, no off-host, no query/userinfo).
 * - Mandatory independent external environment variables: backend SHA, client SHA, handoff SHA.
 * - Financial execution strictly requires external lifecycle expected FINANCIAL_PAIRED_RUN and matching root approval SHA.
 * - All fixture IDs verified for parity with parsed_request and server_synthesized_context.
 * - Normative profile digest verified using fresh OnlineSaleIntentProfile computation.
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

        const val PHASE_PROVISIONED_AWAITING_TLS = "PROVISIONED_AWAITING_TLS_START"
        const val PHASE_ACTIVE_TLS_READY = "ACTIVE_TLS_READY"
        const val PHASE_READINESS_ONLY_VERIFIED = "READINESS_ONLY_VERIFIED"

        const val SCOPE_READINESS_ONLY = "ROOT_AUTHORIZED_READINESS_ONLY"
        const val SCOPE_PAIRED_EXECUTION = "ROOT_AUTHORIZED_PAIRED_EXECUTION"
        const val SCOPE_ONLINE_CASH_SALE = "mobile_v2_online_cash_sale"

        const val LIFECYCLE_FINANCIAL_PAIRED_RUN = "FINANCIAL_PAIRED_RUN"
        const val LIFECYCLE_READINESS_ONLY = "READINESS_ONLY"

        val HEX_40_REGEX = Regex("^[0-9a-f]{40}$", RegexOption.IGNORE_CASE)
        val HEX_64_REGEX = Regex("^[0-9a-f]{64}$", RegexOption.IGNORE_CASE)
        const val MAX_FILE_SIZE_BYTES = 64 * 1024L

        private val REQUIRED_TOP_LEVEL_KEYS = setOf(
            "schema_version", "handoff_scope", "lifecycle_phase", "created_at", "expires_at",
            "backend_runtime_pins", "endpoints", "fixture", "credentials_path", "raw_wire_sample",
            "server_synthesized_context", "canonical_intent_profile_digest", "historical_ca5a_reference", "startup_tls"
        )

        private val FORBIDDEN_INLINE_CREDENTIAL_KEYS = listOf(
            "raw_token", "token", "password", "staff_password", "secret", "bearer"
        )

        private val REQUIRED_RUNTIME_PINS = setOf(
            "qualified_core_head_sha", "backend_execution_head_sha", "qualified_app_tree_sha",
            "peer_android_approved_head_sha", "peer_android_executable_head_sha",
            "source_fixture_sha256", "guard_test_sha256", "cli_command_sha256",
            "cli_runner_sha256", "pairing_manifest_sha256"
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
            require(path.isEmpty() || path == "/" || path == "/api/mobile/v2") {
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

            val schemaVersion = root["schema_version"]?.asString()?.value ?: ""
            require(schemaVersion == "1.0.0") {
                "Unsupported schema_version '$schemaVersion'; expected '1.0.0'"
            }

            val handoffScope = root["handoff_scope"]?.asString()?.value ?: ""
            val allowedScopes = setOf(SCOPE_READINESS_ONLY, SCOPE_PAIRED_EXECUTION, SCOPE_ONLINE_CASH_SALE)
            require(handoffScope in allowedScopes) {
                "Invalid handoff_scope '$handoffScope'; must be one of $allowedScopes"
            }

            val lifecyclePhase = root["lifecycle_phase"]?.asString()?.value ?: ""
            val allowedPhases = setOf(PHASE_PROVISIONED_AWAITING_TLS, PHASE_ACTIVE_TLS_READY, PHASE_READINESS_ONLY_VERIFIED)
            require(lifecyclePhase in allowedPhases) {
                "Invalid lifecycle_phase '$lifecyclePhase'; must be one of $allowedPhases"
            }

            val createdAt = root["created_at"]?.asString()?.value ?: ""
            val expiresAt = root["expires_at"]?.asString()?.value ?: ""
            val expiry = try {
                Instant.parse(expiresAt)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid ISO-8601 UTC timestamp for expires_at: '$expiresAt'", e)
            }
            require(expiry.isAfter(Instant.now())) {
                "Handoff configuration TTL expired at $expiresAt (current time: ${Instant.now()})"
            }

            // Validate pins
            val pinsObj = root["backend_runtime_pins"]?.asObject()
                ?: throw RuntimeException("backend_runtime_pins must be an object")
            val missingPins = REQUIRED_RUNTIME_PINS - pinsObj.properties.keys
            if (missingPins.isNotEmpty()) {
                throw RuntimeException("Malformed client handoff artifact: missing pin(s): $missingPins")
            }
            val pinsMap = mutableMapOf<String, String>()
            for ((k, v) in pinsObj.properties) {
                val str = v.asString()?.value ?: throw RuntimeException("Pin '$k' must be a non-empty string")
                pinsMap[k] = str
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
            val baseUrl = endpointsObj["base_url"]?.asString()?.value ?: ""
            val loopbackBaseUrl = endpointsObj["loopback_base_url"]?.asString()?.value ?: ""
            validateHttpsOrigin(baseUrl)
            validateHttpsOrigin(loopbackBaseUrl)

            val routesObj = endpointsObj["routes"]?.asObject() ?: throw RuntimeException("endpoints.routes must be an object")
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

            // Fixture
            val fixtureObj = root["fixture"]?.asObject() ?: throw RuntimeException("fixture must be an object")
            val fixture = CashPilotFixture(
                schemaName = fixtureObj["schema_name"]?.asString()?.value ?: "",
                companyId = ServerUlid.parse(fixtureObj["company_public_id"]?.asString()?.value ?: ""),
                companyBaseCurrency = fixtureObj["company_base_currency"]?.asString()?.value ?: "",
                currencyMinorUnits = fixtureObj["currency_minor_units"]?.asNumber()?.value?.toInt() ?: 2,
                staffActorId = ServerUlid.parse(fixtureObj["staff_actor_public_id"]?.asString()?.value ?: ""),
                deviceId = ClientUuid.parse(fixtureObj["device_id"]?.asString()?.value ?: ""),
                customerId = ServerUlid.parse(fixtureObj["customer_public_id"]?.asString()?.value ?: ""),
                warehouseId = ServerUlid.parse(fixtureObj["warehouse_public_id"]?.asString()?.value ?: ""),
                productId = ServerUlid.parse(fixtureObj["product_public_id"]?.asString()?.value ?: ""),
                productUnitId = ServerUlid.parse(fixtureObj["product_unit_public_id"]?.asString()?.value ?: ""),
                taxRateId = ServerUlid.parse(fixtureObj["tax_rate_public_id"]?.asString()?.value ?: ""),
                cashAccountId = ServerUlid.parse(fixtureObj["cash_account_public_id"]?.asString()?.value ?: ""),
                sampleEventId = ClientUuid.parse(fixtureObj["sample_event_id"]?.asString()?.value ?: "")
            )

            // Raw wire sample
            val rawWireObj = root["raw_wire_sample"]?.asObject() ?: throw RuntimeException("raw_wire_sample must be an object")
            val rawPayloadJson = rawWireObj["raw_wire_payload_json"]?.asString()?.value ?: ""
            val rawPayloadSha = rawWireObj["raw_wire_payload_sha256"]?.asString()?.value ?: ""
            val calcPayloadSha = computeSha256(rawPayloadJson.toByteArray(Charsets.UTF_8))
            require(calcPayloadSha.equals(rawPayloadSha, ignoreCase = true)) {
                "Raw wire payload SHA-256 integrity mismatch: expected $rawPayloadSha, computed $calcPayloadSha"
            }
            val parsedReq = rawWireObj["parsed_request"]?.asObject() ?: throw RuntimeException("raw_wire_sample.parsed_request must be an object")
            val rawWireSample = CashPilotRawWireSample(
                rawWirePayloadJson = rawPayloadJson,
                rawWirePayloadSha256 = rawPayloadSha,
                parsedRequest = parsedReq
            )

            // Parity checks between parsed request and fixture
            require(parsedReq["customer_id"]?.asString()?.value == fixture.customerId.value) { "Customer ID parity mismatch" }
            require(parsedReq["warehouse_id"]?.asString()?.value == fixture.warehouseId.value) { "Warehouse ID parity mismatch" }
            require(parsedReq["device_id"]?.asString()?.value == fixture.deviceId.value) { "Device ID parity mismatch" }
            require(parsedReq["currency"]?.asString()?.value == fixture.companyBaseCurrency) { "Currency parity mismatch" }

            // Server context
            val ctxObj = root["server_synthesized_context"]?.asObject()
                ?: throw RuntimeException("server_synthesized_context must be an object")
            val ctxCompanyId = ServerUlid.parse(ctxObj["company_id"]?.asString()?.value ?: "")
            val ctxActorId = ServerUlid.parse(ctxObj["actor_id"]?.asString()?.value ?: "")
            val ctxMinorUnits = ctxObj["currency_minor_units"]?.asNumber()?.value?.toInt() ?: 2
            require(ctxCompanyId == fixture.companyId) { "Context company ID mismatch with fixture" }
            require(ctxActorId == fixture.staffActorId) { "Context actor ID mismatch with fixture" }
            require(ctxMinorUnits == fixture.currencyMinorUnits) { "Context minor units mismatch with fixture" }
            val serverContext = OnlineSaleContext(
                companyId = ctxCompanyId,
                actorId = ctxActorId,
                currencyMinorUnits = ctxMinorUnits
            )

            // Canonical profile digest computation & check
            val declaredDigestStr = root["canonical_intent_profile_digest"]?.asString()?.value ?: ""
            val profile = OnlineSaleIntentProfile.fromContextAndRequest(serverContext, parsedReq)
            val computedDigest = profile.computePayloadDigest()
            require(computedDigest.value == declaredDigestStr) {
                "Canonical intent profile digest mismatch: declared '$declaredDigestStr', computed '${computedDigest.value}'"
            }

            // Credentials path & loading
            val credentialsPath = root["credentials_path"]?.asString()?.value ?: ""
            require(credentialsPath.isNotBlank() && credentialsPath.startsWith("/")) {
                "credentials_path must be an absolute path starting with /"
            }
            val credentials = loadCredentials(File(credentialsPath), allowNonPosixForTesting)
            require(credentials.deviceId == fixture.deviceId) {
                "Credentials device_id (${credentials.deviceId}) does not match fixture device_id (${fixture.deviceId})"
            }

            // Historical ca5a reference (optional metadata)
            val historicalObj = root["historical_ca5a_reference"]?.asObject()
            val historicalMap = historicalObj?.properties?.mapValues { it.value.asString()?.value ?: "" }

            // Startup TLS
            val tlsObj = root["startup_tls"]?.asObject() ?: throw RuntimeException("startup_tls must be an object")
            val tlsStatus = tlsObj["status"]?.asString()?.value ?: ""
            val caCertPath = tlsObj["ca_cert_path"]?.asString()?.value
            val caCertSha256 = tlsObj["ca_cert_sha256"]?.asString()?.value
            val readinessUrl = tlsObj["readiness_url"]?.asString()?.value
            val readinessNonce = tlsObj["readiness_nonce"]?.asString()?.value

            val isProvisionOnly = lifecyclePhase == PHASE_PROVISIONED_AWAITING_TLS ||
                    tlsStatus == "UNAVAILABLE_AWAITING_TLS_START"

            if (!isProvisionOnly) {
                require(!caCertPath.isNullOrBlank()) { "startup_tls.ca_cert_path is required when ready" }
                val caFile = File(caCertPath)
                require(caFile.exists() && caFile.isFile) { "CA cert file not found: [REDACTED_FILE]" }
                if (!caCertSha256.isNullOrBlank()) {
                    val caBytes = readBoundedBytesOnce(caFile, allowNonPosixForTesting = allowNonPosixForTesting)
                    val actualCaSha = computeSha256(caBytes)
                    require(actualCaSha.equals(caCertSha256, ignoreCase = true)) {
                        "CA cert SHA-256 integrity mismatch: expected $caCertSha256, computed $actualCaSha"
                    }
                }
                require(!readinessUrl.isNullOrBlank()) { "startup_tls.readiness_url is required when ready" }
                validateHttpsOrigin(readinessUrl)
                require(!readinessNonce.isNullOrBlank()) { "startup_tls.readiness_nonce is required when ready" }
            }

            val startupTls = CashPilotStartupTls(
                status = tlsStatus,
                caCertPath = caCertPath,
                caCertSha256 = caCertSha256,
                readinessUrl = readinessUrl,
                readinessNonce = readinessNonce
            )

            val isReadinessOnly = handoffScope == SCOPE_READINESS_ONLY

            val isFinancialApproved = !isReadinessOnly &&
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
