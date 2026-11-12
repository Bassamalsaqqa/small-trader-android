package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonBoolean
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.Locale

/**
 * Expected fixture record for product pagination and decimal precision checks.
 */
data class ExpectedProductFixture(
    val id: String,
    val exactUnitPrice: String,
    val taxRate: String? = null
)

/**
 * Dual manifest digests representing candidate revision 3 and primary pin revision 2.
 */
data class ManifestDigests(
    val candidateRev3: String,
    val primaryPinRev2: String
)

/**
 * Validated hand-off configuration for the paired Android-to-Laravel V1 HTTPS read pilot.
 *
 * Security & Custody Rules:
 * - Credentials (email/password) MUST be loaded from a separate private credentials file
 *   with POSIX permissions 0600 or 0400.
 * - Inline passwords or tokens in the main handoff JSON are strictly forbidden and rejected.
 * - String representation [toString] overrides default data class behavior to redact all
 *   passwords, tokens, and secret file paths.
 * - Strict JSON key allowlisting: any unknown key in handoff or credentials JSON fails closed.
 * - Type enforcement: strict JSON types enforced (no lenient type coercion).
 * - Origin validation: strict HTTPS absolute origin with no path, query, fragment, or userinfo.
 * - Independent pinning: backendCommitSha validated against independently expected lead SHA when configured.
 * - TTL validation: strict future ISO-8601 UTC timestamp check.
 * - Dual-OFF flags: financial_v2_active and offline_capture_active must both be false.
 */
data class ReadPilotHandoffConfig(
    val baseUrl: String,
    val backendCommitSha: String,
    val manifestDigest: String,
    val manifestDigests: ManifestDigests? = null,
    val staffEmail: String,
    val staffPassword: String,
    val deviceId: ClientUuid,
    val deviceName: String,
    val appVersion: String,
    val platform: String,
    val companyId: ServerUlid,
    val invalidCompanyId: ServerUlid,
    val credentialsPath: String,
    val caCertPath: String? = null,
    val testTruststorePath: String? = null,
    val testTruststorePassword: String? = null,
    val wrongHostnameUrl: String? = null,
    val untrustedCertUrl: String? = null,
    val expectedMigrations: List<String> = emptyList(),
    val resolvedFlags: Map<String, Boolean> = emptyMap(),
    val ttlExpiresAt: String,
    val sourceHeads: Map<String, String> = emptyMap(),
    val expectedProducts: List<ExpectedProductFixture> = emptyList(),
    val expectedCustomers: List<String> = emptyList(),
    val baseCurrency: String? = null,
    val allowHttpLoopback: Boolean = false,
    val revokedDeviceId: ClientUuid? = null,
    val expectedBackendSha: String? = null
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        validateHttpsOrigin(baseUrl, allowHttpLoopback)

        require(HEX_40_REGEX.matches(backendCommitSha)) {
            "backendCommitSha must be a 40-character lowercase hex SHA, got '$backendCommitSha'"
        }

        // Validate against independent expected SHA if provided
        if (expectedBackendSha != null) {
            require(HEX_40_REGEX.matches(expectedBackendSha)) {
                "Independent expected backend SHA must be 40-character hex, got '$expectedBackendSha'"
            }
            require(backendCommitSha.equals(expectedBackendSha, ignoreCase = true)) {
                "Backend commit SHA mismatch with independent lead expectation: expected $expectedBackendSha, handoff declared $backendCommitSha"
            }
        }

        if (manifestDigests != null) {
            require(HEX_64_REGEX.matches(manifestDigests.candidateRev3)) {
                "candidateRev3 must be a 64-character hex SHA-256, got '${manifestDigests.candidateRev3}'"
            }
            require(HEX_64_REGEX.matches(manifestDigests.primaryPinRev2)) {
                "primaryPinRev2 must be a 64-character hex SHA-256, got '${manifestDigests.primaryPinRev2}'"
            }
            require(manifestDigests.candidateRev3.equals(EXPECTED_CANDIDATE_REV3, ignoreCase = true)) {
                "Manifest candidateRev3 mismatch: expected $EXPECTED_CANDIDATE_REV3, got ${manifestDigests.candidateRev3}"
            }
            require(manifestDigests.primaryPinRev2.equals(EXPECTED_PRIMARY_PIN_REV2, ignoreCase = true)) {
                "Manifest primaryPinRev2 mismatch: expected $EXPECTED_PRIMARY_PIN_REV2, got ${manifestDigests.primaryPinRev2}"
            }
            require(manifestDigest.equals(EXPECTED_CANDIDATE_REV3, ignoreCase = true)) {
                "Manifest digest mismatch: expected $EXPECTED_CANDIDATE_REV3, got $manifestDigest"
            }
        } else {
            require(manifestDigest.equals(FROZEN_MANIFEST_DIGEST, ignoreCase = true)) {
                "Manifest digest mismatch: expected $FROZEN_MANIFEST_DIGEST, got $manifestDigest"
            }
        }

        require(staffEmail.isNotBlank()) { "staffEmail must not be blank" }
        require(staffPassword.isNotBlank()) { "staffPassword must not be blank" }
        require(deviceName.isNotBlank()) { "deviceName must not be blank" }
        require(appVersion.isNotBlank()) { "appVersion must not be blank" }
        require(credentialsPath.isNotBlank()) { "credentialsPath must not be blank" }

        require(companyId != invalidCompanyId) {
            "invalidCompanyId must be a distinct foreign company ID from companyId: ${companyId.value}"
        }

        if (wrongHostnameUrl != null) {
            validateHttpsOrigin(wrongHostnameUrl, allowHttpLoopback)
        }
        if (untrustedCertUrl != null) {
            validateHttpsOrigin(untrustedCertUrl, allowHttpLoopback)
        }

        // TTL Validation: Must be a valid future ISO-8601 UTC timestamp
        val ttlInstant = try {
            Instant.parse(ttlExpiresAt)
        } catch (e: Exception) {
            throw IllegalArgumentException("ttlExpiresAt must be a valid ISO-8601 UTC timestamp, got '$ttlExpiresAt'")
        }
        require(ttlInstant.isAfter(Instant.now())) {
            "Handoff configuration TTL expired at $ttlExpiresAt (current clock: ${Instant.now()})"
        }

        // Migration list validation
        require(expectedMigrations.isNotEmpty()) {
            "expectedMigrations must not be empty in handoff configuration"
        }
        for (m in expectedMigrations) {
            require(m.isNotBlank()) { "Migration entries must not be blank" }
        }

        // Safe flags validation
        val financialV2 = resolvedFlags["financial_v2_active"]
        require(financialV2 != null && !financialV2) {
            "Safety invariant violation: resolvedFlags.financial_v2_active must be explicitly false, got $financialV2"
        }

        val offlineCapture = resolvedFlags["offline_capture_active"]
        require(offlineCapture != null && !offlineCapture) {
            "Safety invariant violation: resolvedFlags.offline_capture_active must be explicitly false, got $offlineCapture"
        }

        val mobileV1 = resolvedFlags["mobileV1"]
        if (mobileV1 != null) {
            require(mobileV1) { "Safety invariant violation: resolvedFlags.mobileV1 must be true, got $mobileV1" }
        }
        val mobileV2 = resolvedFlags["mobileV2"]
        if (mobileV2 != null) {
            require(!mobileV2) { "Safety invariant violation: resolvedFlags.mobileV2 must be false, got $mobileV2" }
        }
        val mobileEnabled = resolvedFlags["mobile.enabled"]
        if (mobileEnabled != null) {
            require(mobileEnabled) { "Safety invariant violation: resolvedFlags.mobile.enabled must be true, got $mobileEnabled" }
        }
        val mobileV2Enabled = resolvedFlags["mobile.v2_enabled"]
        if (mobileV2Enabled != null) {
            require(!mobileV2Enabled) { "Safety invariant violation: resolvedFlags.mobile.v2_enabled must be false, got $mobileV2Enabled" }
        }

        val v1Active = resolvedFlags["v1_read_pilot_active"] ?: resolvedFlags["v1_active"] ?: resolvedFlags["read_v1_active"] ?: resolvedFlags["mobileV1"]
        require(v1Active == true) {
            "Safety invariant violation: V1 read pilot active flag must be explicitly true in resolvedFlags"
        }
    }

    override fun toString(): String {
        return "ReadPilotHandoffConfig(baseUrl='$baseUrl', backendCommitSha='$backendCommitSha', manifestDigest='$manifestDigest', companyId='${companyId.value}', deviceId='${deviceId.value}', ttlExpiresAt='$ttlExpiresAt')"
    }

    /**
     * Sanitized summary suitable for logs and evidence files with NO passwords,
     * NO tokens, and NO secret file paths.
     */
    fun toSanitizedSummary(): Map<String, Any?> {
        return mapOf(
            "baseUrl" to baseUrl,
            "backendCommitSha" to backendCommitSha,
            "manifestDigest" to manifestDigest,
            "deviceId" to deviceId.value,
            "deviceName" to deviceName,
            "appVersion" to appVersion,
            "platform" to platform,
            "companyId" to companyId.value,
            "hasCaCert" to (caCertPath != null),
            "invalidCompanyId" to invalidCompanyId.value,
            "hasWrongHostnameProbe" to (wrongHostnameUrl != null),
            "hasUntrustedCertProbe" to (untrustedCertUrl != null),
            "expectedMigrationCount" to expectedMigrations.size,
            "resolvedFlags" to resolvedFlags,
            "ttlExpiresAt" to ttlExpiresAt,
            "sourceHeads" to sourceHeads.keys
        )
    }

    companion object {
        const val FROZEN_MANIFEST_DIGEST = "e8e3fe672a8f7aceb3152c3dd990b4fb137fa517b24038e468659e0fdf5a87a7"
        const val EXPECTED_CANDIDATE_REV3 = "e8e3fe672a8f7aceb3152c3dd990b4fb137fa517b24038e468659e0fdf5a87a7"
        const val EXPECTED_PRIMARY_PIN_REV2 = "ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659"
        const val ENV_HANDOFF_PATH = "SMALL_TRADER_READ_PILOT_HANDOFF"
        const val ENV_EXPECTED_BACKEND_SHA = "SMALL_TRADER_EXPECTED_BACKEND_SHA"
        const val MAX_FILE_SIZE_BYTES = 64 * 1024L // 64 KiB bounded read
        private val HEX_40_REGEX = Regex("^[0-9a-f]{40}$")
        private val HEX_64_REGEX = Regex("^[0-9a-f]{64}$")

        private val ALLOWED_HANDOFF_KEYS = setOf(
            "status",
            "baseUrl",
            "backendCommitSha",
            "composedBackendCommitSha",
            "manifestDigest",
            "credentialsPath",
            "deviceId",
            "deviceName",
            "appVersion",
            "platform",
            "companyId",
            "invalidCompanyId",
            "foreignCompanyId",
            "caCertPath",
            "testTruststorePath",
            "testTruststorePassword",
            "wrongHostnameUrl",
            "untrustedCertUrl",
            "expectedMigrations",
            "resolvedFlags",
            "ttlExpiresAt",
            "https_port",
            "wrong_hostname_port",
            "untrusted_port",
            "php_port",
            "php_pid",
            "caddy_pid",
            "schema_name",
            "db_host",
            "db_port",
            "db_user",
            "start_time",
            "fixtures",
            "processIdentities",
            "compositionFoundationSha",
            "mfaConsumedRecoveryIndices",
            "mfaAvailableRecoveryIndices",
            "gateStatus",
            "supervisorBoundaryEvidencePath",
            "cleanupPolicy",
            "sourceHeads",
            "allowHttpLoopback",
            "expectedProducts",
            "expectedCustomers",
            "baseCurrency"
        )

        private val ALLOWED_CREDENTIALS_KEYS = setOf(
            "staff1",
            "staff2_mfa",
            "staff3_tenant2",
            "staff4_restricted",
            "revoked_device_id",
            "staffEmail",
            "staffPassword",
            "email",
            "password"
        )

        private val FORBIDDEN_INLINE_CREDENTIAL_KEYS = setOf(
            "staffPassword",
            "password",
            "token",
            "bearerToken",
            "authToken"
        )

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
                        // If file permissions have group/other read, verify parent directory is 0700/0500 owned by current user
                        val parent = file.parentFile
                        if (parent != null) {
                            val parentView = Files.getFileAttributeView(parent.toPath(), PosixFileAttributeView::class.java)
                            if (parentView != null) {
                                val parentAttrs = parentView.readAttributes()
                                val parentOwner = parentAttrs.owner()
                                if (currentUserName != null && parentOwner.name != currentUserName) {
                                    throw SecurityException("Parent directory owner '${parentOwner.name}' does not match current process owner '$currentUserName'")
                                }
                                val parentDisallowed = setOf(
                                    PosixFilePermission.GROUP_READ,
                                    PosixFilePermission.GROUP_WRITE,
                                    PosixFilePermission.GROUP_EXECUTE,
                                    PosixFilePermission.OTHERS_READ,
                                    PosixFilePermission.OTHERS_WRITE,
                                    PosixFilePermission.OTHERS_EXECUTE
                                )
                                val parentViolations = parentAttrs.permissions().intersect(parentDisallowed)
                                if (parentViolations.isNotEmpty()) {
                                    throw SecurityException("Insecure permissions on ${file.name} ($perms) and parent directory (${parentAttrs.permissions()})")
                                }
                            }
                        } else {
                            throw SecurityException("Insecure permissions on ${file.name}: permissions must be 0600 or 0400, got $perms")
                        }
                    }
                }
            } catch (_: UnsupportedOperationException) {
                // POSIX attributes unsupported on this file system
            }
            return file
        }

        fun validatePublicCertFile(file: File, maxSizeBytes: Long = MAX_FILE_SIZE_BYTES): File {
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
                    val owner = attrs.owner()
                    val currentUserName = System.getProperty("user.name")
                    if (currentUserName != null && owner.name != currentUserName) {
                        throw SecurityException("File owner '${owner.name}' does not match current process owner '$currentUserName'")
                    }
                }
            } catch (_: UnsupportedOperationException) {
                // POSIX attributes unsupported
            }
            return file
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
            require(scheme != null) { "URL must have a scheme: [REDACTED]" }

            val host = uri.host
            require(!host.isNullOrBlank()) { "URL must have a valid host: [REDACTED]" }

            if (allowHttpLoopback && isLoopbackHost(host)) {
                require(scheme == "http" || scheme == "https") {
                    "Loopback URL must use http or https scheme, got '$scheme'"
                }
            } else {
                require(scheme == "https") {
                    "URL must strictly use HTTPS for paired proof: [REDACTED]"
                }
            }

            require(uri.rawUserInfo == null) { "URL must not contain userinfo: [REDACTED]" }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "URL must not contain path segments: [REDACTED]" }
            require(uri.rawQuery == null) { "URL must not contain query parameters: [REDACTED]" }
            require(uri.rawFragment == null) { "URL must not contain fragment identifier: [REDACTED]" }

            val port = uri.port
            if (port != -1) {
                require(port in 1..65535) { "Invalid port $port in URL" }
            }
            return if (port != -1) "$scheme://$host:$port" else "$scheme://$host"
        }

        private fun isLoopbackHost(host: String): Boolean =
            host.lowercase(Locale.ROOT) in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1", "[::1]")

        fun fromEnvironment(
            expectedBackendSha: String? = System.getenv(ENV_EXPECTED_BACKEND_SHA)?.trim(),
            requireIndependentSha: Boolean = false
        ): ReadPilotHandoffConfig? {
            val path = System.getenv(ENV_HANDOFF_PATH)?.trim()
            if (path.isNullOrEmpty()) {
                return null
            }
            val file = File(path)
            require(file.exists()) {
                "Handoff file specified in $ENV_HANDOFF_PATH does not exist: [REDACTED_FILE]"
            }
            return fromFile(
                file = file,
                expectedBackendSha = expectedBackendSha,
                requireIndependentSha = requireIndependentSha
            )
        }

        fun fromFile(
            file: File,
            expectedBackendSha: String? = System.getenv(ENV_EXPECTED_BACKEND_SHA)?.trim(),
            requireIndependentSha: Boolean = false
        ): ReadPilotHandoffConfig {
            val rawJson = readBoundedUtf8(file, enforceStrictFilePermissions = false)
            val root = StrictJsonParser.parse(rawJson).asObject()
            return fromJsonObject(
                root = root,
                baseDirectory = file.parentFile ?: File("."),
                expectedBackendSha = expectedBackendSha,
                requireIndependentSha = requireIndependentSha
            )
        }

        fun fromJsonObject(
            root: StrictJsonObject,
            baseDirectory: File = File("."),
            expectedBackendSha: String? = null,
            requireIndependentSha: Boolean = false
        ): ReadPilotHandoffConfig {
            if (requireIndependentSha) {
                require(!expectedBackendSha.isNullOrBlank()) {
                    "Independent expected backend SHA is required via $ENV_EXPECTED_BACKEND_SHA; never trust handoff alone"
                }
            }

            // 1. Reject Inline Passwords / Tokens
            val forbiddenPresent = root.properties.keys.intersect(FORBIDDEN_INLINE_CREDENTIAL_KEYS)
            if (forbiddenPresent.isNotEmpty()) {
                throw IllegalArgumentException(
                    "Security custody violation: inline credentials forbidden in handoff JSON; use 'credentialsPath' pointing to a private chmod 0600 file"
                )
            }

            // 2. Unknown Key Rejection
            val unknownKeys = root.properties.keys - ALLOWED_HANDOFF_KEYS
            if (unknownKeys.isNotEmpty()) {
                throw IllegalArgumentException("Unknown key(s) in handoff configuration: $unknownKeys")
            }

            val baseUrl = requireString(root, "baseUrl")
            val backendSha = optionalString(root, "backendCommitSha")
                ?: optionalString(root, "composedBackendCommitSha")
                ?: throw IllegalArgumentException("Missing required 'backendCommitSha' in handoff JSON")

            val manifestElement = root["manifestDigest"] ?: throw IllegalArgumentException("Missing required key 'manifestDigest'")
            val manifestDigestStr: String
            val manifestDigestsObj: ManifestDigests?
            when (manifestElement) {
                is StrictJsonObject -> {
                    val unknownManifestKeys = manifestElement.properties.keys - setOf("candidateRev3", "primaryPinRev2")
                    if (unknownManifestKeys.isNotEmpty()) {
                        throw IllegalArgumentException("Unknown key(s) in manifestDigest: $unknownManifestKeys")
                    }
                    val cand = requireString(manifestElement, "candidateRev3")
                    val prim = requireString(manifestElement, "primaryPinRev2")
                    require(HEX_64_REGEX.matches(cand)) { "candidateRev3 must be 64-character hex, got '$cand'" }
                    require(HEX_64_REGEX.matches(prim)) { "primaryPinRev2 must be 64-character hex, got '$prim'" }
                    require(cand.equals(EXPECTED_CANDIDATE_REV3, ignoreCase = true)) {
                        "Manifest candidateRev3 mismatch: expected $EXPECTED_CANDIDATE_REV3, got $cand"
                    }
                    require(prim.equals(EXPECTED_PRIMARY_PIN_REV2, ignoreCase = true)) {
                        "Manifest primaryPinRev2 mismatch: expected $EXPECTED_PRIMARY_PIN_REV2, got $prim"
                    }
                    manifestDigestStr = cand
                    manifestDigestsObj = ManifestDigests(candidateRev3 = cand, primaryPinRev2 = prim)
                }
                is StrictJsonString -> {
                    manifestDigestStr = manifestElement.value
                    manifestDigestsObj = null
                }
                else -> {
                    throw IllegalArgumentException("Invalid type for key 'manifestDigest': expected object or string, got ${manifestElement.javaClass.simpleName}")
                }
            }

            val allowHttpLoopback = optionalBoolean(root, "allowHttpLoopback") ?: false

            // Enforce separate private credentials file with strict 0600/0400 permissions
            val credentialsPath = requireString(root, "credentialsPath")
            val credFile = File(credentialsPath).let {
                if (it.isAbsolute) it else File(baseDirectory, credentialsPath)
            }
            val credRaw = readBoundedUtf8(credFile, enforceStrictFilePermissions = true)
            val credJson = StrictJsonParser.parse(credRaw).asObject()

            val unknownCredKeys = credJson.properties.keys - ALLOWED_CREDENTIALS_KEYS
            if (unknownCredKeys.isNotEmpty()) {
                throw IllegalArgumentException("Unknown key(s) in credentials file: $unknownCredKeys")
            }

            val staffEmail: String
            val staffPassword: String
            var revokedDeviceId: ClientUuid? = null

            if (credJson.properties.containsKey("staff1")) {
                val staff1El = credJson["staff1"]
                if (staff1El !is StrictJsonObject) {
                    throw IllegalArgumentException("Invalid type for staff1 in credentials: expected object, got ${staff1El?.javaClass?.simpleName}")
                }
                val allowedStaff1Keys = setOf("email", "password", "device_id", "device_name")
                val unknownStaff1 = staff1El.properties.keys - allowedStaff1Keys
                if (unknownStaff1.isNotEmpty()) {
                    throw IllegalArgumentException("Unknown key(s) in staff1 credentials: $unknownStaff1")
                }
                staffEmail = requireString(staff1El, "email")
                staffPassword = requireString(staff1El, "password")
            } else {
                staffEmail = optionalString(credJson, "staffEmail")
                    ?: optionalString(credJson, "email")
                    ?: optionalString(root, "staffEmail")
                    ?: throw IllegalArgumentException("Missing required 'staffEmail' in credentials file")
                staffPassword = optionalString(credJson, "staffPassword")
                    ?: optionalString(credJson, "password")
                    ?: throw IllegalArgumentException("Missing required 'staffPassword' in credentials file")
            }

            val revokedDeviceStr = optionalString(credJson, "revoked_device_id")
            if (revokedDeviceStr != null) {
                revokedDeviceId = ClientUuid.parse(revokedDeviceStr)
            }

            val deviceIdStr = requireString(root, "deviceId")
            val deviceId = ClientUuid.parse(deviceIdStr)

            val deviceName = optionalString(root, "deviceName") ?: "ReadPilotHarness"
            val appVersion = optionalString(root, "appVersion") ?: "1.0.0"
            val platform = optionalString(root, "platform") ?: "android"

            val companyIdStr = requireString(root, "companyId")
            val companyId = ServerUlid.parse(companyIdStr)

            val invalidCompanyIdStr = optionalString(root, "invalidCompanyId")
                ?: optionalString(root, "foreignCompanyId")
                ?: throw IllegalArgumentException("Missing required 'invalidCompanyId'/'foreignCompanyId' from two-tenant fixture in handoff JSON")
            val invalidCompanyId = ServerUlid.parse(invalidCompanyIdStr)

            var caCertPath = optionalString(root, "caCertPath")
            if (caCertPath != null) {
                val certFile = File(caCertPath).let {
                    if (it.isAbsolute) it else File(baseDirectory, caCertPath)
                }
                validatePublicCertFile(certFile)
                caCertPath = certFile.absolutePath
            }

            var testTruststorePath = optionalString(root, "testTruststorePath")
            if (testTruststorePath != null) {
                val tsFile = File(testTruststorePath).let {
                    if (it.isAbsolute) it else File(baseDirectory, testTruststorePath)
                }
                validatePrivateFile(tsFile, enforceStrictFilePermissions = true)
                testTruststorePath = tsFile.absolutePath
            }
            val testTruststorePassword = optionalString(root, "testTruststorePassword")

            val wrongHostnameUrl = optionalString(root, "wrongHostnameUrl")
            val untrustedCertUrl = optionalString(root, "untrustedCertUrl")

            val migrations = requireStringList(root, "expectedMigrations")
            val flags = requireBooleanMap(root, "resolvedFlags")
            val ttlExpiresAt = requireString(root, "ttlExpiresAt")

            val sourceHeads = optionalStringMap(root, "sourceHeads")

            // Fixtures validation and mapping
            var baseCurrency = optionalString(root, "baseCurrency")
            val fixturesElement = root["fixtures"]
            if (fixturesElement != null) {
                if (fixturesElement !is StrictJsonObject) {
                    throw IllegalArgumentException("Invalid type for fixtures: expected object, got ${fixturesElement.javaClass.simpleName}")
                }
                val allowedFixtures = setOf("company_a", "company_b", "scenarios", "credential_file")
                val unknownFixtures = fixturesElement.properties.keys - allowedFixtures
                if (unknownFixtures.isNotEmpty()) {
                    throw IllegalArgumentException("Unknown key(s) in fixtures: $unknownFixtures")
                }
                val compA = fixturesElement["company_a"]
                if (compA is StrictJsonObject) {
                    val pubA = requireString(compA, "public_id")
                    require(pubA == companyIdStr) { "fixtures.company_a.public_id mismatch with companyId" }
                    if (baseCurrency == null) {
                        baseCurrency = optionalString(compA, "base_currency")
                    }
                }
                val compB = fixturesElement["company_b"]
                if (compB is StrictJsonObject) {
                    val pubB = requireString(compB, "public_id")
                    require(pubB == invalidCompanyIdStr) { "fixtures.company_b.public_id mismatch with invalidCompanyId" }
                }
                val scn = fixturesElement["scenarios"]
                if (scn != null && scn !is StrictJsonObject) {
                    throw IllegalArgumentException("fixtures.scenarios must be an object")
                }
            }

            // Port numbers validation if present
            for (portKey in listOf("https_port", "wrong_hostname_port", "untrusted_port", "php_port", "db_port")) {
                val el = root[portKey]
                if (el != null) {
                    val p = el.asInt()
                    require(p in 1..65535) { "Invalid port $p for $portKey" }
                }
            }
            // PID validation if present
            for (pidKey in listOf("php_pid", "caddy_pid")) {
                val el = root[pidKey]
                if (el != null) {
                    val pid = el.asInt()
                    require(pid > 0) { "Invalid PID $pid for $pidKey" }
                }
            }
            val compSha = optionalString(root, "compositionFoundationSha")
            if (compSha != null) {
                require(HEX_40_REGEX.matches(compSha)) { "compositionFoundationSha must be 40-character hex, got '$compSha'" }
            }
            val statusStr = optionalString(root, "status")
            if (statusStr != null) {
                require(statusStr.isNotBlank()) { "status must not be blank" }
            }
            val schemaNameStr = optionalString(root, "schema_name")
            if (schemaNameStr != null) {
                require(schemaNameStr.isNotBlank()) { "schema_name must not be blank" }
            }

            // Optional expected product fixtures for pagination & exact lexeme verification
            val expectedProducts = mutableListOf<ExpectedProductFixture>()
            val prodArray = root["expectedProducts"] as? StrictJsonArray
            if (prodArray != null) {
                for (el in prodArray.elements) {
                    if (el is StrictJsonObject) {
                        val id = requireString(el, "id")
                        val unitPrice = requireString(el, "exactUnitPrice")
                        val taxRate = optionalString(el, "taxRate")
                        expectedProducts.add(ExpectedProductFixture(id, unitPrice, taxRate))
                    }
                }
            }

            val expectedCustomers = mutableListOf<String>()
            val custArray = root["expectedCustomers"] as? StrictJsonArray
            if (custArray != null) {
                for (el in custArray.elements) {
                    if (el is StrictJsonString) {
                        expectedCustomers.add(el.value)
                    } else if (el is StrictJsonObject) {
                        val id = requireString(el, "id")
                        expectedCustomers.add(id)
                    }
                }
            }

            return ReadPilotHandoffConfig(
                baseUrl = baseUrl,
                backendCommitSha = backendSha,
                manifestDigest = manifestDigestStr,
                manifestDigests = manifestDigestsObj,
                staffEmail = staffEmail,
                staffPassword = staffPassword,
                deviceId = deviceId,
                deviceName = deviceName,
                appVersion = appVersion,
                platform = platform,
                companyId = companyId,
                invalidCompanyId = invalidCompanyId,
                credentialsPath = credFile.absolutePath,
                caCertPath = caCertPath,
                testTruststorePath = testTruststorePath,
                testTruststorePassword = testTruststorePassword,
                wrongHostnameUrl = wrongHostnameUrl,
                untrustedCertUrl = untrustedCertUrl,
                expectedMigrations = migrations,
                resolvedFlags = flags,
                ttlExpiresAt = ttlExpiresAt,
                sourceHeads = sourceHeads,
                expectedProducts = expectedProducts,
                expectedCustomers = expectedCustomers,
                baseCurrency = baseCurrency,
                allowHttpLoopback = allowHttpLoopback,
                revokedDeviceId = revokedDeviceId,
                expectedBackendSha = expectedBackendSha
            )
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

        private fun requireStringList(obj: StrictJsonObject, key: String): List<String> {
            val element = obj[key] ?: throw IllegalArgumentException("Missing required key '$key'")
            if (element !is StrictJsonArray) {
                throw IllegalArgumentException("Invalid type for key '$key': expected array, got ${element.javaClass.simpleName}")
            }
            return element.elements.mapIndexed { index, el ->
                if (el !is StrictJsonString) {
                    throw IllegalArgumentException("Invalid type for element at index $index in array '$key': expected string, got ${el.javaClass.simpleName}")
                }
                el.value
            }
        }

        private fun requireBooleanMap(obj: StrictJsonObject, key: String): Map<String, Boolean> {
            val element = obj[key] ?: throw IllegalArgumentException("Missing required key '$key'")
            if (element !is StrictJsonObject) {
                throw IllegalArgumentException("Invalid type for key '$key': expected object, got ${element.javaClass.simpleName}")
            }
            val result = mutableMapOf<String, Boolean>()
            for ((k, v) in element.properties) {
                if (v !is StrictJsonBoolean) {
                    throw IllegalArgumentException("Invalid type for flag '$k' in resolvedFlags: expected boolean, got ${v.javaClass.simpleName}")
                }
                result[k] = v.value
            }
            return result
        }

        private fun optionalStringMap(obj: StrictJsonObject, key: String): Map<String, String> {
            val element = obj[key] ?: return emptyMap()
            if (element !is StrictJsonObject) {
                throw IllegalArgumentException("Invalid type for key '$key': expected object, got ${element.javaClass.simpleName}")
            }
            val result = mutableMapOf<String, String>()
            for ((k, v) in element.properties) {
                if (v !is StrictJsonString) {
                    throw IllegalArgumentException("Invalid type for sourceHead '$k': expected string, got ${v.javaClass.simpleName}")
                }
                result[k] = v.value
            }
            return result
        }
    }
}
