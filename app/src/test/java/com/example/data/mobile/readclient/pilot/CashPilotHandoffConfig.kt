package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonBoolean
import com.example.data.mobile.contract.StrictJsonNull
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import com.example.data.mobile.readclient.EndpointSecurity
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/**
 * Provisioned fixture entity identifiers and normative decimal lexemes for online cash pilot.
 * Supports exact backend provision manifest keys (e.g. company_public_id, product_unit_public_id).
 */
data class CashPilotFixture(
    val companyId: ServerUlid,
    val companyBaseCurrency: String,
    val staffActorId: ServerUlid,
    val deviceId: ClientUuid,
    val customerId: ServerUlid,
    val warehouseId: ServerUlid,
    val productId: ServerUlid,
    val productUnitId: ServerUlid,
    val taxRateId: ServerUlid,
    val taxRate: String,
    val unitPrice: String,
    val cashAccountId: ServerUlid,
    val sampleEventId: ClientUuid? = null,
    val currencyMinorUnits: Int = 2
)

/**
 * Private runtime credentials loaded exclusively from a separate 0600 file outside checkout.
 * Accurately models exact backend provision fields: raw_token, token_hash, device_id, staff_email, schema.
 */
data class CashPilotCredentials(
    val rawToken: String? = null,
    val tokenHash: String? = null,
    val staffEmail: String? = null,
    val staffPassword: String? = null,
    val deviceId: ClientUuid? = null,
    val deviceName: String? = null,
    val schema: String? = null,
    val companyId: ServerUlid? = null,
    val appVersion: String? = null,
    val platform: String? = null
) {
    override fun toString(): String =
        "CashPilotCredentials(rawToken=[REDACTED], tokenHash=${if (tokenHash != null) "[REDACTED]" else "null"}, " +
                "staffEmail=${if (staffEmail != null) "[REDACTED_EMAIL]" else "null"}, staffPassword=[REDACTED], " +
                "deviceId=$deviceId, deviceName='$deviceName', schema='$schema', appVersion='$appVersion', platform='$platform')"
}

/**
 * Strict CASH-specific handoff configuration parser and validator.
 *
 * Security & Lifecycle Invariants:
 * - Credentials (email/password/raw_token) MUST be loaded from a separate private credentials file (0600/0400).
 * - Inline passwords or tokens in the main handoff JSON are strictly forbidden and rejected.
 * - String representation [toString] overrides default data class behavior to redact all secrets and file paths.
 * - Strict JSON key allowlisting: unknown root keys in handoff fail closed.
 * - Accepts exact backend provision payload: {manifest, credentials_path, sample_request}.
 * - Origin validation: strict HTTPS absolute origin with no path, query, fragment, or userinfo.
 * - Independent pinning: requires an independently expected lead backend SHA by default.
 * - Distinct lifecycle grants: READINESS_ONLY vs FINANCIAL_PAIRED_RUN.
 *   FINANCIAL_PAIRED_RUN requires explicit root financial approval PIN from environment;
 *   untrusted handoff files cannot self-authorize financial mutation.
 */
data class CashPilotHandoffConfig(
    val status: String = STATUS_READY,
    val baseUrl: String,
    val backendCommitSha: String,
    val clientCommitSha: String? = null,
    val manifestDigest: String? = null,
    val schemaIdentity: String,
    val harnessNonce: String? = null,
    val ttlExpiresAt: String? = null,
    val lifecycleGrant: String = GRANT_READINESS_ONLY,
    val credentialsPath: String,
    val credentials: CashPilotCredentials,
    val fixture: CashPilotFixture,
    val allowHttpLoopback: Boolean = false,
    val caCertPath: String? = null,
    val testTruststorePath: String? = null,
    val testTruststorePassword: String? = null,
    val coordinatedFaultAvailable: Boolean = false,
    val stockProbesAvailable: Boolean = false,
    val ports: Map<String, Int> = emptyMap(),
    val pids: Map<String, Int> = emptyMap()
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        validateHttpsOrigin(baseUrl, allowHttpLoopback)

        require(HEX_40_REGEX.matches(backendCommitSha)) {
            "backendCommitSha must be a 40-character lowercase hex SHA, got '$backendCommitSha'"
        }
        if (clientCommitSha != null) {
            require(HEX_40_REGEX.matches(clientCommitSha)) {
                "clientCommitSha must be a 40-character lowercase hex SHA, got '$clientCommitSha'"
            }
        }
        if (manifestDigest != null) {
            require(HEX_64_REGEX.matches(manifestDigest)) {
                "manifestDigest must be a 64-character hex SHA-256, got '$manifestDigest'"
            }
        }
        require(schemaIdentity.isNotBlank()) {
            "schemaIdentity must not be blank"
        }
        require(lifecycleGrant in ALLOWED_GRANTS) {
            "Invalid lifecycleGrant '$lifecycleGrant'. Must be one of $ALLOWED_GRANTS"
        }

        // Future TTL validation if provided
        if (ttlExpiresAt != null) {
            val expiry = try {
                Instant.parse(ttlExpiresAt)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid ISO-8601 UTC timestamp for ttlExpiresAt: '$ttlExpiresAt'")
            }
            require(expiry.isAfter(Instant.now())) {
                "Handoff configuration TTL expired at $ttlExpiresAt (current time: ${Instant.now()})"
            }
        }
    }

    override fun toString(): String =
        "CashPilotHandoffConfig(status='$status', baseUrl='$baseUrl', backendCommitSha='$backendCommitSha', " +
                "clientCommitSha='$clientCommitSha', manifestDigest='$manifestDigest', " +
                "schemaIdentity='$schemaIdentity', lifecycleGrant='$lifecycleGrant', " +
                "credentialsPath='[REDACTED_PATH]', credentials=$credentials, fixture=$fixture, " +
                "ttlExpiresAt='$ttlExpiresAt', allowHttpLoopback=$allowHttpLoopback)"

    companion object {
        const val ENV_CASH_PILOT_HANDOFF = "SMALL_TRADER_CASH_PILOT_HANDOFF"
        const val ENV_EXPECTED_BACKEND_SHA = "SMALL_TRADER_EXPECTED_BACKEND_SHA"
        const val ENV_EXPECTED_HANDOFF_SHA = "SMALL_TRADER_EXPECTED_HANDOFF_SHA"
        const val ENV_EXPECTED_CLIENT_SHA = "SMALL_TRADER_EXPECTED_CLIENT_SHA"
        const val ENV_ROOT_FINANCIAL_APPROVAL_PIN = "SMALL_TRADER_ROOT_FINANCIAL_APPROVAL_PIN"

        const val STATUS_PROVISION_ONLY = "PROVISION_ONLY"
        const val STATUS_READY = "READY"
        const val STATUS_RUNNING = "RUNNING"

        const val GRANT_READINESS_ONLY = "READINESS_ONLY"
        const val GRANT_FINANCIAL_PAIRED_RUN = "FINANCIAL_PAIRED_RUN"
        val ALLOWED_GRANTS = setOf(GRANT_READINESS_ONLY, GRANT_FINANCIAL_PAIRED_RUN)

        val HEX_40_REGEX = Regex("^[0-9a-fA-F]{40}$")
        val HEX_64_REGEX = Regex("^[0-9a-fA-F]{64}$")
        const val MAX_FILE_SIZE_BYTES = 64 * 1024L

        private val ALLOWED_HANDOFF_KEYS = setOf(
            "status",
            "baseUrl",
            "backendCommitSha",
            "clientCommitSha",
            "manifestDigest",
            "schemaIdentity",
            "harnessNonce",
            "ttlExpiresAt",
            "lifecycleGrant",
            "credentialsPath",
            "credentials_path",
            "fixture",
            "manifest",
            "sample_request",
            "allowHttpLoopback",
            "caCertPath",
            "testTruststorePath",
            "testTruststorePassword",
            "coordinatedFaultAvailable",
            "stockProbesAvailable",
            "ports",
            "pids"
        )

        private val FORBIDDEN_INLINE_CREDENTIAL_KEYS = listOf(
            "raw_token", "token", "password", "staff_password",
            "secret", "totp_secret", "bearer", "staffEmail"
        )

        private val ALLOWED_CREDENTIALS_KEYS = setOf(
            "raw_token",
            "token_hash",
            "staff_email",
            "staff_password",
            "device_id",
            "device_name",
            "schema",
            "company_id",
            "app_version",
            "platform"
        )

        fun fromFile(
            file: File,
            expectedBackendSha: String? = null,
            requireIndependentSha: Boolean = true,
            expectedHandoffSha: String? = null,
            expectedClientSha: String? = null,
            rootApprovalPin: String? = null
        ): CashPilotHandoffConfig {
            validatePrivateFile(file, maxSizeBytes = MAX_FILE_SIZE_BYTES, enforceStrictFilePermissions = true)

            if (requireIndependentSha) {
                require(!expectedBackendSha.isNullOrBlank()) {
                    "Safety invariant violation: $ENV_EXPECTED_BACKEND_SHA is required; never trust handoff alone"
                }
            }

            val handoffShaPin = expectedHandoffSha ?: System.getenv(ENV_EXPECTED_HANDOFF_SHA)?.trim()
            if (!handoffShaPin.isNullOrBlank()) {
                require(HEX_64_REGEX.matches(handoffShaPin)) {
                    "Expected handoff SHA pin must be 64-character hex, got '$handoffShaPin'"
                }
                val actualSha = computeSha256(file)
                require(actualSha.equals(handoffShaPin, ignoreCase = true)) {
                    "Handoff file SHA-256 mismatch with independent lead expectation: expected $handoffShaPin, got $actualSha"
                }
            }

            val content = readBoundedUtf8(file, maxSizeBytes = MAX_FILE_SIZE_BYTES, enforceStrictFilePermissions = true)
            val root = StrictJsonParser.parse(content).asObject()

            val unexpectedKeys = root.properties.keys - ALLOWED_HANDOFF_KEYS
            require(unexpectedKeys.isEmpty()) {
                "Unexpected keys in handoff JSON: $unexpectedKeys"
            }

            for (key in FORBIDDEN_INLINE_CREDENTIAL_KEYS) {
                if (root[key] != null) {
                    throw SecurityException("Security violation: inline credential key '$key' forbidden in handoff JSON")
                }
            }

            val statusStr = optionalString(root, "status") ?: STATUS_READY
            val baseUrl = optionalString(root, "baseUrl") ?: "https://127.0.0.1:18443"
            val backendSha = requireString(root, "backendCommitSha")
            require(HEX_40_REGEX.matches(backendSha)) {
                "backendCommitSha must be 40-character hex, got '$backendSha'"
            }

            if (expectedBackendSha != null) {
                require(HEX_40_REGEX.matches(expectedBackendSha)) {
                    "Independent expected backend SHA must be 40-character hex, got '$expectedBackendSha'"
                }
                require(backendSha.equals(expectedBackendSha, ignoreCase = true)) {
                    "Backend commit SHA mismatch with independent expectation: expected $expectedBackendSha, got $backendSha"
                }
            }

            val clientSha = optionalString(root, "clientCommitSha")
            val effectiveClientSha = expectedClientSha ?: System.getenv(ENV_EXPECTED_CLIENT_SHA)?.trim()
            if (!effectiveClientSha.isNullOrBlank()) {
                require(HEX_40_REGEX.matches(effectiveClientSha)) {
                    "Independent expected client SHA must be 40-character hex, got '$effectiveClientSha'"
                }
                require(clientSha != null && clientSha.equals(effectiveClientSha, ignoreCase = true)) {
                    "Client commit SHA mismatch with independent expectation: expected $effectiveClientSha, got $clientSha"
                }
            }

            val manifestDigest = optionalString(root, "manifestDigest")
            val harnessNonce = optionalString(root, "harnessNonce")
            val ttlExpiresAt = optionalString(root, "ttlExpiresAt")

            val lifecycleGrant = optionalString(root, "lifecycleGrant") ?: GRANT_READINESS_ONLY
            require(lifecycleGrant in ALLOWED_GRANTS) {
                "Invalid lifecycleGrant '$lifecycleGrant'. Must be one of $ALLOWED_GRANTS"
            }

            if (lifecycleGrant == GRANT_FINANCIAL_PAIRED_RUN) {
                val effectivePin = rootApprovalPin ?: System.getenv(ENV_ROOT_FINANCIAL_APPROVAL_PIN)?.trim()
                if (effectivePin.isNullOrBlank()) {
                    throw SecurityException(
                        "Self-authorization refused: handoff requested '$GRANT_FINANCIAL_PAIRED_RUN' " +
                        "but independent environment variable '$ENV_ROOT_FINANCIAL_APPROVAL_PIN' is missing or blank. " +
                        "Untrusted handoff files cannot self-authorize financial execution."
                    )
                }
            }

            val credPathStr = optionalString(root, "credentialsPath")
                ?: optionalString(root, "credentials_path")
                ?: throw IllegalArgumentException("Missing credentialsPath or credentials_path in handoff JSON")
            val credFile = File(credPathStr)
            val credentials = loadCredentials(credFile)

            val fixtureObj = (root["fixture"] as? StrictJsonObject)
                ?: (root["manifest"] as? StrictJsonObject)
                ?: throw IllegalArgumentException("Missing or invalid 'fixture' or 'manifest' object in handoff JSON")

            val sampleRequestObj = root["sample_request"] as? StrictJsonObject
            val fixture = parseFixture(fixtureObj, sampleRequestObj)

            val schemaIdentity = optionalString(root, "schemaIdentity")
                ?: optionalString(fixtureObj, "schema_name")
                ?: credentials.schema
                ?: "accounting_p8_tmp"

            val allowHttpLoopback = optionalBoolean(root, "allowHttpLoopback") ?: true
            val caCertPath = optionalString(root, "caCertPath")
            val testTruststorePath = optionalString(root, "testTruststorePath")
            val testTruststorePassword = optionalString(root, "testTruststorePassword")
            val coordinatedFault = optionalBoolean(root, "coordinatedFaultAvailable") ?: false
            val stockProbes = optionalBoolean(root, "stockProbesAvailable") ?: false

            val ports = parsePorts(root["ports"] as? StrictJsonObject)
            val pids = parsePids(root["pids"] as? StrictJsonObject)

            return CashPilotHandoffConfig(
                status = statusStr,
                baseUrl = baseUrl,
                backendCommitSha = backendSha,
                clientCommitSha = clientSha,
                manifestDigest = manifestDigest,
                schemaIdentity = schemaIdentity,
                harnessNonce = harnessNonce,
                ttlExpiresAt = ttlExpiresAt,
                lifecycleGrant = lifecycleGrant,
                credentialsPath = credFile.absolutePath,
                credentials = credentials,
                fixture = fixture,
                allowHttpLoopback = allowHttpLoopback,
                caCertPath = caCertPath,
                testTruststorePath = testTruststorePath,
                testTruststorePassword = testTruststorePassword,
                coordinatedFaultAvailable = coordinatedFault,
                stockProbesAvailable = stockProbes,
                ports = ports,
                pids = pids
            )
        }

        private fun loadCredentials(credFile: File): CashPilotCredentials {
            validatePrivateFile(credFile, maxSizeBytes = MAX_FILE_SIZE_BYTES, enforceStrictFilePermissions = true)
            val content = readBoundedUtf8(credFile, maxSizeBytes = MAX_FILE_SIZE_BYTES, enforceStrictFilePermissions = true)
            val root = StrictJsonParser.parse(content).asObject()

            val unexpected = root.properties.keys - ALLOWED_CREDENTIALS_KEYS
            require(unexpected.isEmpty()) {
                "Unexpected keys in credentials JSON: $unexpected"
            }

            val rawToken = optionalString(root, "raw_token")
            val tokenHash = optionalString(root, "token_hash")
            val email = optionalString(root, "staff_email")
            val password = optionalString(root, "staff_password")
            val deviceIdStr = optionalString(root, "device_id")
            val deviceName = optionalString(root, "device_name")
            val schema = optionalString(root, "schema")
            val companyIdStr = optionalString(root, "company_id")
            val appVersion = optionalString(root, "app_version")
            val platform = optionalString(root, "platform")

            require(!rawToken.isNullOrBlank() || (!email.isNullOrBlank() && !password.isNullOrBlank())) {
                "Credentials file must provide either 'raw_token' or both 'staff_email' and 'staff_password'"
            }

            return CashPilotCredentials(
                rawToken = rawToken,
                tokenHash = tokenHash,
                staffEmail = email,
                staffPassword = password,
                deviceId = deviceIdStr?.let { ClientUuid.parse(it) },
                deviceName = deviceName,
                schema = schema,
                companyId = companyIdStr?.let { ServerUlid.parse(it) },
                appVersion = appVersion,
                platform = platform
            )
        }

        private fun parseFixture(obj: StrictJsonObject, sampleRequest: StrictJsonObject? = null): CashPilotFixture {
            val companyIdStr = optionalString(obj, "company_public_id")
                ?: optionalString(obj, "company_id")
                ?: throw IllegalArgumentException("Missing company_public_id in fixture")
            val companyId = ServerUlid.parse(companyIdStr)

            val baseCurrency = optionalString(obj, "company_base_currency") ?: "ILS"

            val staffActorIdStr = optionalString(obj, "staff_actor_public_id")
                ?: optionalString(obj, "staff_actor_id")
                ?: throw IllegalArgumentException("Missing staff_actor_public_id in fixture")
            val staffActorId = ServerUlid.parse(staffActorIdStr)

            val deviceIdStr = optionalString(obj, "device_id")
                ?: sampleRequest?.get("device_id")?.asString()
                ?: throw IllegalArgumentException("Missing device_id in fixture")
            val deviceId = ClientUuid.parse(deviceIdStr)

            val customerIdStr = optionalString(obj, "customer_public_id")
                ?: optionalString(obj, "customer_id")
                ?: sampleRequest?.get("customer_id")?.asString()
                ?: throw IllegalArgumentException("Missing customer_public_id in fixture")
            val customerId = ServerUlid.parse(customerIdStr)

            val warehouseIdStr = optionalString(obj, "warehouse_public_id")
                ?: optionalString(obj, "warehouse_id")
                ?: sampleRequest?.get("warehouse_id")?.asString()
                ?: throw IllegalArgumentException("Missing warehouse_public_id in fixture")
            val warehouseId = ServerUlid.parse(warehouseIdStr)

            val productIdStr = optionalString(obj, "product_public_id")
                ?: optionalString(obj, "product_id")
                ?: throw IllegalArgumentException("Missing product_public_id in fixture")
            val productId = ServerUlid.parse(productIdStr)

            val productUnitIdStr = optionalString(obj, "product_unit_public_id")
                ?: optionalString(obj, "product_unit_id")
                ?: throw IllegalArgumentException("Missing product_unit_public_id in fixture")
            val productUnitId = ServerUlid.parse(productUnitIdStr)

            val taxRateIdStr = optionalString(obj, "tax_rate_public_id")
                ?: optionalString(obj, "tax_rate_id")
                ?: throw IllegalArgumentException("Missing tax_rate_public_id in fixture")
            val taxRateId = ServerUlid.parse(taxRateIdStr)

            val taxRate = optionalString(obj, "tax_rate") ?: "16.000000"
            val unitPrice = optionalString(obj, "unit_price") ?: "50.000000"

            val cashAccountIdStr = optionalString(obj, "cash_account_public_id")
                ?: optionalString(obj, "cash_account_id")
                ?: throw IllegalArgumentException("Missing cash_account_public_id in fixture")
            val cashAccountId = ServerUlid.parse(cashAccountIdStr)

            val sampleEventId = (optionalString(obj, "sample_event_id") ?: sampleRequest?.get("client_event_id")?.asString())?.let { ClientUuid.parse(it) }
            val minorUnits = obj["currency_minor_units"]?.asInt() ?: 2

            return CashPilotFixture(
                companyId = companyId,
                companyBaseCurrency = baseCurrency,
                staffActorId = staffActorId,
                deviceId = deviceId,
                customerId = customerId,
                warehouseId = warehouseId,
                productId = productId,
                productUnitId = productUnitId,
                taxRateId = taxRateId,
                taxRate = taxRate,
                unitPrice = unitPrice,
                cashAccountId = cashAccountId,
                sampleEventId = sampleEventId,
                currencyMinorUnits = minorUnits
            )
        }

        private fun parsePorts(obj: StrictJsonObject?): Map<String, Int> {
            if (obj == null) return emptyMap()
            val map = mutableMapOf<String, Int>()
            for ((k, v) in obj.properties) {
                val p = v.asInt()
                require(p in 1..65535) { "Invalid port $p for $k" }
                map[k] = p
            }
            return map
        }

        private fun parsePids(obj: StrictJsonObject?): Map<String, Int> {
            if (obj == null) return emptyMap()
            val map = mutableMapOf<String, Int>()
            for ((k, v) in obj.properties) {
                val pid = v.asInt()
                require(pid > 0) { "Invalid PID $pid for $k" }
                map[k] = pid
            }
            return map
        }

        fun validatePrivateFile(
            file: File,
            maxSizeBytes: Long = MAX_FILE_SIZE_BYTES,
            enforceStrictFilePermissions: Boolean = false
        ): File {
            require(file.exists()) { "File does not exist: [REDACTED_FILE]" }
            val path = file.toPath()
            require(file.isFile && !Files.isSymbolicLink(path)) {
                "File must be a regular non-symlink file: [REDACTED_FILE]"
            }
            val length = file.length()
            require(length in 1..maxSizeBytes) {
                "File size ($length bytes) must be between 1 and $maxSizeBytes bytes: [REDACTED_FILE]"
            }

            try {
                val posixView = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
                if (posixView != null) {
                    val attrs = posixView.readAttributes()
                    val perms = attrs.permissions()
                    val owner = attrs.owner()
                    val currentUserName = System.getProperty("user.name")
                    if (currentUserName != null && owner.name != currentUserName) {
                        throw SecurityException("File owner '${owner.name}' does not match current process owner '$currentUserName'")
                    }
                    val disallowed = setOf(
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_WRITE,
                        PosixFilePermission.OTHERS_EXECUTE
                    )
                    val violations = perms.intersect(disallowed)
                    if (violations.isNotEmpty()) {
                        if (enforceStrictFilePermissions) {
                            throw SecurityException("Insecure permissions on ${file.name}: permissions must be 0600 or 0400, got $perms")
                        }
                    }

                    val parent = file.parentFile
                    if (parent != null) {
                        validateDirectoryPermissions(parent)
                    }
                }
            } catch (e: UnsupportedOperationException) {
            }

            return file
        }

        fun validateDirectoryPermissions(dir: File) {
            val parentPath = dir.toPath()
            val parentPosix = Files.getFileAttributeView(parentPath, PosixFileAttributeView::class.java)
            if (parentPosix != null) {
                val parentAttrs = parentPosix.readAttributes()
                val parentPerms = parentAttrs.permissions()
                val parentOwner = parentAttrs.owner()
                val currentUserName = System.getProperty("user.name")
                if (currentUserName != null && parentOwner.name != currentUserName) {
                    throw SecurityException("Directory owner '${parentOwner.name}' does not match current process owner '$currentUserName'")
                }
                val parentDisallowed = setOf(
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE
                )
                if (parentPerms.intersect(parentDisallowed).isNotEmpty()) {
                    throw SecurityException("Parent directory of private file has world permissions: $parentPerms")
                }
            }
        }

        fun readBoundedUtf8(
            file: File,
            maxSizeBytes: Long = MAX_FILE_SIZE_BYTES,
            enforceStrictFilePermissions: Boolean = false
        ): String {
            validatePrivateFile(file, maxSizeBytes, enforceStrictFilePermissions)
            file.inputStream().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > maxSizeBytes) {
                        throw IllegalArgumentException("File exceeds maximum allowed size of $maxSizeBytes bytes: [REDACTED]")
                    }
                    out.write(buffer, 0, read)
                }
                val bytes = out.toByteArray()
                require(bytes.isNotEmpty()) { "File must not be empty: [REDACTED]" }
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return decoder.decode(ByteBuffer.wrap(bytes)).toString()
            }
        }

        fun validateHttpsOrigin(urlStr: String, allowHttpLoopback: Boolean): String {
            require(urlStr.isNotBlank()) { "URL must not be blank" }
            val uri = try {
                URI(urlStr)
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed URL: [REDACTED]")
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            require(scheme != null) { "URL must specify a scheme: [REDACTED]" }
            require(uri.isAbsolute) { "URL must be absolute: [REDACTED]" }

            val host = uri.host
            require(!host.isNullOrBlank()) { "URL host must not be empty: [REDACTED]" }

            val isLoopback = host.lowercase(Locale.ROOT) in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1", "[::1]")
            if (scheme == "http") {
                require(allowHttpLoopback && isLoopback) {
                    "Insecure HTTP origin [REDACTED] is prohibited. HTTPS is required by default."
                }
            } else if (scheme != "https") {
                throw IllegalArgumentException("Unsupported scheme '$scheme'. Only HTTPS is allowed (or HTTP loopback with explicit opt-in).")
            }

            require(uri.rawUserInfo == null) { "URL must not contain userinfo: [REDACTED]" }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "URL must not contain path: [REDACTED]" }
            require(uri.rawQuery == null) { "URL must not contain query: [REDACTED]" }
            require(uri.rawFragment == null) { "URL must not contain fragment: [REDACTED]" }

            val port = uri.port
            if (port != -1) {
                require(port in 1..65535) { "Invalid port $port in URL" }
            }

            return if (port != -1) "$scheme://$host:$port" else "$scheme://$host"
        }

        fun computeSha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val r = input.read(buf)
                    if (r == -1) break
                    md.update(buf, 0, r)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun requireString(obj: StrictJsonObject, key: String): String {
            val element = obj[key] ?: throw IllegalArgumentException("Missing required key '$key'")
            if (element !is StrictJsonString) {
                throw IllegalArgumentException("Invalid type for key '$key': expected string, got ${element.javaClass.simpleName}")
            }
            return element.value
        }

        private fun optionalString(obj: StrictJsonObject, key: String): String? {
            val element = obj[key] ?: return null
            if (element !is StrictJsonString) {
                throw IllegalArgumentException("Invalid type for key '$key': expected string, got ${element.javaClass.simpleName}")
            }
            return element.value
        }

        private fun optionalBoolean(obj: StrictJsonObject, key: String): Boolean? {
            val element = obj[key] ?: return null
            if (element !is StrictJsonBoolean) {
                throw IllegalArgumentException("Invalid type for key '$key': expected boolean, got ${element.javaClass.simpleName}")
            }
            return element.value
        }
    }
}
