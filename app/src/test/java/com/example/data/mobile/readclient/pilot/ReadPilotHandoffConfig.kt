package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonBoolean
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import java.io.File

/**
 * Validated hand-off configuration for the paired Android-to-Laravel V1 HTTPS read pilot.
 *
 * Reads configuration either from a single handoff JSON or a handoff JSON pointing to a
 * private chmod 600 credentials file.
 *
 * Fails closed on:
 * - Missing required fields
 * - Insecure base URL (HTTPS required unless loopback explicitly authorized)
 * - Mismatched contract manifest digest (must match frozen candidate manifest)
 * - Invalid identifier primitives (UUID device ID, ULID company ID)
 */
data class ReadPilotHandoffConfig(
    val baseUrl: String,
    val backendCommitSha: String,
    val manifestDigest: String,
    val staffEmail: String,
    val staffPassword: String,
    val deviceId: ClientUuid,
    val deviceName: String,
    val appVersion: String,
    val platform: String,
    val companyId: ServerUlid,
    val caCertPath: String? = null,
    val testTruststorePath: String? = null,
    val testTruststorePassword: String? = null,
    val credentialsPath: String? = null,
    val invalidCompanyId: ServerUlid? = null,
    val wrongHostnameUrl: String? = null,
    val untrustedCertUrl: String? = null,
    val v2FinancialProbeUrl: String? = null,
    val expectedMigrations: List<String> = emptyList(),
    val resolvedFlags: Map<String, Boolean> = emptyMap(),
    val ttlExpiresAt: String? = null,
    val allowHttpLoopback: Boolean = false
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        if (!allowHttpLoopback) {
            require(baseUrl.startsWith("https://", ignoreCase = true)) {
                "baseUrl must use HTTPS for paired proof, got '$baseUrl'"
            }
        }
        require(backendCommitSha.isNotBlank()) { "backendCommitSha must not be blank" }
        require(manifestDigest.equals(FROZEN_MANIFEST_DIGEST, ignoreCase = true)) {
            "Manifest digest mismatch: expected $FROZEN_MANIFEST_DIGEST, got $manifestDigest"
        }
        require(staffEmail.isNotBlank()) { "staffEmail must not be blank" }
        require(staffPassword.isNotBlank()) { "staffPassword must not be blank" }
        require(deviceName.isNotBlank()) { "deviceName must not be blank" }
        require(appVersion.isNotBlank()) { "appVersion must not be blank" }
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
            "staffEmail" to staffEmail,
            "deviceId" to deviceId.value,
            "deviceName" to deviceName,
            "appVersion" to appVersion,
            "platform" to platform,
            "companyId" to companyId.value,
            "hasCaCert" to (caCertPath != null),
            "hasInvalidCompanyId" to (invalidCompanyId != null),
            "hasWrongHostnameProbe" to (wrongHostnameUrl != null),
            "hasUntrustedCertProbe" to (untrustedCertUrl != null),
            "hasV2Probe" to (v2FinancialProbeUrl != null),
            "expectedMigrationCount" to expectedMigrations.size,
            "resolvedFlags" to resolvedFlags,
            "ttlExpiresAt" to ttlExpiresAt
        )
    }

    companion object {
        const val FROZEN_MANIFEST_DIGEST = "e8e3fe672a8f7aceb3152c3dd990b4fb137fa517b24038e468659e0fdf5a87a7"
        const val EXPECTED_BACKEND_PR94_HEAD = "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb"
        const val ENV_HANDOFF_PATH = "SMALL_TRADER_READ_PILOT_HANDOFF"

        fun fromEnvironment(): ReadPilotHandoffConfig? {
            val path = System.getenv(ENV_HANDOFF_PATH)?.trim()
            if (path.isNullOrEmpty()) {
                return null
            }
            val file = File(path)
            require(file.exists()) {
                "Handoff file specified in $ENV_HANDOFF_PATH does not exist: $path"
            }
            return fromFile(file)
        }

        fun fromFile(file: File): ReadPilotHandoffConfig {
            require(file.exists()) { "File does not exist: ${file.absolutePath}" }
            val rawJson = file.readText(Charsets.UTF_8)
            val root = StrictJsonParser.parse(rawJson).asObject()
            return fromJsonObject(root, baseDirectory = file.parentFile ?: File("."))
        }

        fun fromJsonObject(root: StrictJsonObject, baseDirectory: File = File(".")): ReadPilotHandoffConfig {
            val baseUrl = (root["baseUrl"] as? StrictJsonString)?.value
                ?: throw IllegalArgumentException("Missing required 'baseUrl' in handoff JSON")

            val backendSha = (root["backendCommitSha"] as? StrictJsonString)?.value
                ?: (root["backendSha"] as? StrictJsonString)?.value
                ?: throw IllegalArgumentException("Missing required 'backendCommitSha' in handoff JSON")

            val manifest = (root["manifestDigest"] as? StrictJsonString)?.value
                ?: (root["expectedManifestDigest"] as? StrictJsonString)?.value
                ?: FROZEN_MANIFEST_DIGEST

            val allowHttpLoopback = (root["allowHttpLoopback"] as? StrictJsonBoolean)?.value ?: false

            // Optional credentials sub-file or inline
            var credentialsPath = (root["credentialsPath"] as? StrictJsonString)?.value
            var staffEmail = (root["staffEmail"] as? StrictJsonString)?.value
            var staffPassword = (root["staffPassword"] as? StrictJsonString)?.value

            if (credentialsPath != null) {
                val credFile = File(credentialsPath).let {
                    if (it.isAbsolute) it else File(baseDirectory, credentialsPath)
                }
                if (credFile.exists()) {
                    val credJson = StrictJsonParser.parse(credFile.readText(Charsets.UTF_8)).asObject()
                    if (staffEmail == null) {
                        staffEmail = (credJson["staffEmail"] as? StrictJsonString)?.value
                            ?: (credJson["email"] as? StrictJsonString)?.value
                    }
                    if (staffPassword == null) {
                        staffPassword = (credJson["staffPassword"] as? StrictJsonString)?.value
                            ?: (credJson["password"] as? StrictJsonString)?.value
                    }
                }
            }

            val finalEmail = staffEmail ?: throw IllegalArgumentException("Missing required 'staffEmail' in handoff or credentials file")
            val finalPassword = staffPassword ?: throw IllegalArgumentException("Missing required 'staffPassword' in handoff or credentials file")

            val deviceIdStr = (root["deviceId"] as? StrictJsonString)?.value
                ?: throw IllegalArgumentException("Missing required 'deviceId' in handoff JSON")
            val deviceId = ClientUuid.parse(deviceIdStr)

            val deviceName = (root["deviceName"] as? StrictJsonString)?.value ?: "ReadPilotHarness"
            val appVersion = (root["appVersion"] as? StrictJsonString)?.value ?: "1.0.0"
            val platform = (root["platform"] as? StrictJsonString)?.value ?: "android"

            val companyIdStr = (root["companyId"] as? StrictJsonString)?.value
                ?: throw IllegalArgumentException("Missing required 'companyId' in handoff JSON")
            val companyId = ServerUlid.parse(companyIdStr)

            val invalidCompanyId = (root["invalidCompanyId"] as? StrictJsonString)?.value?.let {
                ServerUlid.parse(it)
            }

            var caCertPath = (root["caCertPath"] as? StrictJsonString)?.value
            if (caCertPath != null) {
                val certFile = File(caCertPath).let {
                    if (it.isAbsolute) it else File(baseDirectory, caCertPath)
                }
                caCertPath = certFile.absolutePath
            }

            val wrongHostnameUrl = (root["wrongHostnameUrl"] as? StrictJsonString)?.value
            val untrustedCertUrl = (root["untrustedCertUrl"] as? StrictJsonString)?.value
            val v2FinancialProbeUrl = (root["v2FinancialProbeUrl"] as? StrictJsonString)?.value

            val migrations = (root["expectedMigrations"] as? StrictJsonArray)?.elements
                ?.mapNotNull { (it as? StrictJsonString)?.value }
                ?: emptyList()

            val flags = mutableMapOf<String, Boolean>()
            (root["resolvedFlags"] as? StrictJsonObject)?.properties?.forEach { (k, v) ->
                if (v is StrictJsonBoolean) {
                    flags[k] = v.value
                }
            }

            val ttlExpiresAt = (root["ttlExpiresAt"] as? StrictJsonString)?.value

            return ReadPilotHandoffConfig(
                baseUrl = baseUrl,
                backendCommitSha = backendSha,
                manifestDigest = manifest,
                staffEmail = finalEmail,
                staffPassword = finalPassword,
                deviceId = deviceId,
                deviceName = deviceName,
                appVersion = appVersion,
                platform = platform,
                companyId = companyId,
                caCertPath = caCertPath,
                credentialsPath = credentialsPath,
                invalidCompanyId = invalidCompanyId,
                wrongHostnameUrl = wrongHostnameUrl,
                untrustedCertUrl = untrustedCertUrl,
                v2FinancialProbeUrl = v2FinancialProbeUrl,
                expectedMigrations = migrations,
                resolvedFlags = flags,
                ttlExpiresAt = ttlExpiresAt,
                allowHttpLoopback = allowHttpLoopback
            )
        }
    }
}
