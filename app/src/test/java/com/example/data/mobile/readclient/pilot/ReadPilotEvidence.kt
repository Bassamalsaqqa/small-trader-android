package com.example.data.mobile.readclient.pilot

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Sanitized evidence model for the paired Android-to-Laravel V1 HTTPS read pilot.
 *
 * Guarantees zero leakage of credentials, bearer tokens, passwords, raw request/response bodies,
 * and internal secret paths.
 */
data class ScenarioEvidence(
    val name: String,
    val status: String, // PASS, FAIL, SKIPPED, UNVERIFIED
    val httpStatusCode: Int?,
    val latencyMs: Long,
    val details: String
)

data class ReadPilotEvidence(
    val runId: String = UUID.randomUUID().toString(),
    val timestamp: String = currentIsoUtcTimestamp(),
    val backendHead: String,
    val manifestDigest: String,
    val baseUrl: String,
    val ttlExpiresAt: String? = null,
    val overallStatus: String, // PASS, FAIL, NOT_RUN
    val scenarios: List<ScenarioEvidence>
) {
    fun toJsonString(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"runId\": \"").append(escapeJson(runId)).append("\",\n")
        sb.append("  \"timestamp\": \"").append(escapeJson(timestamp)).append("\",\n")
        sb.append("  \"backendHead\": \"").append(escapeJson(backendHead)).append("\",\n")
        sb.append("  \"manifestDigest\": \"").append(escapeJson(manifestDigest)).append("\",\n")
        sb.append("  \"baseUrl\": \"").append(escapeJson(baseUrl)).append("\",\n")
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
            sb.append("      \"httpStatusCode\": ").append(sc.httpStatusCode ?: "null").append(",\n")
            sb.append("      \"latencyMs\": ").append(sc.latencyMs).append(",\n")
            sb.append("      \"details\": \"").append(escapeJson(sc.details)).append("\"\n")
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
        private fun currentIsoUtcTimestamp(): String {
            val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            df.timeZone = TimeZone.getTimeZone("UTC")
            return df.format(Date())
        }

        private fun escapeJson(value: String): String {
            val sb = StringBuilder()
            for (c in value) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    in '\u0000'..'\u001F', in '\u007F'..'\u009F' -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
