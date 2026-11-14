package com.example.data.mobile.readclient.pilot

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Sanitized evidence model for the paired Android V2 Online Cash Pilot.
 *
 * Security & Sanitization Invariants:
 * - Guarantees zero leakage of credentials, bearer tokens, passwords, raw request/response bodies,
 *   customer names, staff emails, raw URLs, and internal secret file paths.
 * - Retains only base origin (https://host[:port]) and safe entity IDs.
 * - Bounded string values with canonical JSON escaping.
 * - Strict tri-state overall status: PASS, FAIL, NOT_RUN.
 * - Zero literal NUL or control bytes in source control.
 */
data class CashPilotEvidence(
    val runId: String = UUID.randomUUID().toString(),
    val timestamp: String = currentIsoUtcTimestamp(),
    val backendHead: String,
    val clientHead: String? = null,
    val clientPublicationHead: String? = null,
    val clientExecutableHead: String? = null,
    val manifestDigest: String,
    val baseUrl: String,
    val lifecycleGrant: String,
    val ttlExpiresAt: String? = null,
    val overallStatus: String, // PASS, FAIL, NOT_RUN
    val scenarios: List<ScenarioEvidence>
) {
    init {
        require(overallStatus in ALLOWED_OVERALL_STATUSES) {
            "Invalid overallStatus '$overallStatus'. Must be one of $ALLOWED_OVERALL_STATUSES"
        }
    }

    fun toJsonString(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"runId\": \"").append(escapeJson(runId)).append("\",\n")
        sb.append("  \"timestamp\": \"").append(escapeJson(timestamp)).append("\",\n")
        sb.append("  \"backendHead\": \"").append(escapeJson(backendHead)).append("\",\n")
        val legacyClientHead = clientHead ?: clientExecutableHead
        if (legacyClientHead != null) {
            sb.append("  \"clientHead\": \"").append(escapeJson(legacyClientHead)).append("\",\n")
        }
        if (clientPublicationHead != null) {
            sb.append("  \"clientPublicationHead\": \"").append(escapeJson(clientPublicationHead)).append("\",\n")
        }
        val execClientHead = clientExecutableHead ?: clientHead
        if (execClientHead != null) {
            sb.append("  \"clientExecutableHead\": \"").append(escapeJson(execClientHead)).append("\",\n")
        }
        sb.append("  \"manifestDigest\": \"").append(escapeJson(manifestDigest)).append("\",\n")
        sb.append("  \"baseUrl\": \"").append(escapeJson(baseUrl)).append("\",\n")
        sb.append("  \"lifecycleGrant\": \"").append(escapeJson(lifecycleGrant)).append("\",\n")
        if (ttlExpiresAt != null) {
            sb.append("  \"ttlExpiresAt\": \"").append(escapeJson(ttlExpiresAt)).append("\",\n")
        }
        sb.append("  \"overallStatus\": \"").append(escapeJson(overallStatus)).append("\",\n")
        sb.append("  \"scenarios\": [\n")
        for (i in scenarios.indices) {
            val sc = scenarios[i]
            sb.append("    {\n")
            sb.append("      \"name\": \"").append(escapeJson(sc.name)).append("\",\n")
            sb.append("      \"status\": \"").append(escapeJson(sc.status)).append("\",\n")
            sb.append("      \"httpStatusCode\": ").append(sc.httpStatusCode?.toString() ?: "null").append(",\n")
            sb.append("      \"latencyMs\": ").append(sc.latencyMs).append(",\n")
            sb.append("      \"details\": \"").append(escapeJson(ScenarioEvidence.sanitizeDetails(sc.details))).append("\"\n")
            sb.append("    }").append(if (i < scenarios.size - 1) "," else "").append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    fun writeTo(targetFile: File) {
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(toJsonString(), Charsets.UTF_8)
    }

    companion object {
        val ALLOWED_OVERALL_STATUSES = setOf("PASS", "FAIL", "NOT_RUN")

        private fun currentIsoUtcTimestamp(): String {
            val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            df.timeZone = TimeZone.getTimeZone("UTC")
            return df.format(Date())
        }

        fun escapeJson(value: String): String {
            val sb = StringBuilder()
            for (c in value) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '\"' -> sb.append("\\\"")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    in '\u0000'..'\u001F', in '\u007F'..'\u009F' -> sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
