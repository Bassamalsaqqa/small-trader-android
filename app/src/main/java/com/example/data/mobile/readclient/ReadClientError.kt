package com.example.data.mobile.readclient

import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonElement
import com.example.data.mobile.contract.StrictJsonNull
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonString
import com.example.data.mobile.readclient.ValidationUtils.checkAllowedKeys
import com.example.data.mobile.readclient.ValidationUtils.optionalString
import com.example.data.mobile.readclient.ValidationUtils.optionalUuid
import com.example.data.mobile.readclient.ValidationUtils.requireObject
import com.example.data.mobile.readclient.ValidationUtils.requireString
import com.example.data.mobile.readclient.ValidationUtils.validateDateTime

data class ConflictEvidence(
    val clientEventId: ClientUuid? = null,
    val storedPayloadDigest: PayloadDigest? = null,
    val incomingPayloadDigest: PayloadDigest? = null,
    val conflictDetectedAt: String? = null
) {
    init {
        if (conflictDetectedAt != null) {
            validateDateTime(conflictDetectedAt, "conflict_detected_at")
        }
    }

    override fun toString(): String =
        "ConflictEvidence(clientEventId=$clientEventId, storedPayloadDigest=$storedPayloadDigest, incomingPayloadDigest=$incomingPayloadDigest, conflictDetectedAt='$conflictDetectedAt')"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "client_event_id",
            "stored_payload_digest",
            "incoming_payload_digest",
            "conflict_detected_at"
        )

        fun fromStrictJson(obj: StrictJsonObject): ConflictEvidence {
            checkAllowedKeys(obj, ALLOWED_KEYS, "ConflictEvidence")
            val rawStored = optionalString(obj, "stored_payload_digest")
            val rawIncoming = optionalString(obj, "incoming_payload_digest")
            return ConflictEvidence(
                clientEventId = optionalUuid(obj, "client_event_id"),
                storedPayloadDigest = rawStored?.let { PayloadDigest.parse(it) },
                incomingPayloadDigest = rawIncoming?.let { PayloadDigest.parse(it) },
                conflictDetectedAt = optionalString(obj, "conflict_detected_at")
            )
        }
    }
}

/**
 * RFC 7807/9457 Problem Details conforming error response from the Mobile V1 API.
 * Never stores or serializes sensitive credentials or tokens.
 * Enforces bounded shape and redacts raw server message and details in toString().
 */
data class ReadClientErrorResponse(
    val code: String,
    val message: String,
    val details: StrictJsonElement? = null,
    val conflictEvidence: ConflictEvidence? = null
) {
    init {
        require(code.isNotEmpty()) { "error.code must not be empty" }
        require(message.isNotEmpty()) { "error.message must not be empty" }
        if (details != null) {
            require(details is StrictJsonObject || details is StrictJsonArray || details is StrictJsonString || details is StrictJsonNull) {
                "details must be object, array, string, or null, got ${details::class.simpleName}"
            }
        }
    }

    override fun toString(): String {
        val safeCode = if (SAFE_CODE_REGEX.matches(code)) code else "[UNTRUSTED_CODE]"
        val hasDetails = details != null && details !is StrictJsonNull
        val hasEvidence = conflictEvidence != null
        return "ReadClientErrorResponse(code='$safeCode', message='[REDACTED]', details=${if (hasDetails) "[REDACTED]" else "null"}, conflictEvidence=${if (hasEvidence) "[REDACTED]" else "null"})"
    }

    companion object {
        private val ROOT_ALLOWED_KEYS = setOf("error")
        private val ERROR_ALLOWED_KEYS = setOf("code", "message", "details", "conflict_evidence")
        private val SAFE_CODE_REGEX = Regex("^[A-Z0-9_]{1,64}$")

        fun fromStrictJson(rootObj: StrictJsonObject): ReadClientErrorResponse {
            checkAllowedKeys(rootObj, ROOT_ALLOWED_KEYS, "ErrorResponse")
            val errObj = requireObject(rootObj, "error")
            checkAllowedKeys(errObj, ERROR_ALLOWED_KEYS, "ErrorResponse.error")

            val code = requireString(errObj, "code", minLength = 1, maxLength = 255)
            val message = requireString(errObj, "message", minLength = 1, maxLength = 2048)

            val detailsElement = if (errObj.properties.containsKey("details")) {
                val elem = errObj.properties["details"]!!
                when (elem) {
                    is StrictJsonNull -> null
                    is StrictJsonObject, is StrictJsonArray, is StrictJsonString -> elem
                    else -> throw CanonicalSerializationException("details must be an object, array, string, or null, got ${elem::class.simpleName}")
                }
            } else {
                null
            }

            val conflictEvidence = errObj.properties["conflict_evidence"]?.let { element ->
                if (element is StrictJsonNull) null
                else if (element is StrictJsonObject) ConflictEvidence.fromStrictJson(element)
                else throw CanonicalSerializationException("conflict_evidence must be an object or null")
            }

            return ReadClientErrorResponse(
                code = code,
                message = message,
                details = detailsElement,
                conflictEvidence = conflictEvidence
            )
        }
    }
}

/**
 * Exhaustive typed outcomes for Mobile V1 read client calls.
 * Callers use Auth401 specifically for queue lock handling (QUEUE_LOCKED_AUTH_DENIED).
 * ParseFailure and NetworkUnknown retain only safe fixed failure categories with NO accessible unsanitized causes.
 */
sealed class ReadClientResult<out T> {
    data class Success<out T>(val value: T) : ReadClientResult<T>() {
        override fun toString(): String = "ReadClientResult.Success(value=$value)"
    }

    data class Auth401(val error: ReadClientErrorResponse) : ReadClientResult<Nothing>() {
        override fun toString(): String = "ReadClientResult.Auth401(error=$error)"
    }

    data class Forbidden403(val error: ReadClientErrorResponse) : ReadClientResult<Nothing>() {
        override fun toString(): String = "ReadClientResult.Forbidden403(error=$error)"
    }

    data class Conflict409(val error: ReadClientErrorResponse) : ReadClientResult<Nothing>() {
        override fun toString(): String = "ReadClientResult.Conflict409(error=$error)"
    }

    data class HttpError(val statusCode: Int, val error: ReadClientErrorResponse?) : ReadClientResult<Nothing>() {
        override fun toString(): String = "ReadClientResult.HttpError(statusCode=$statusCode, error=$error)"
    }

    data class ParseFailure(
        val category: String = "PARSE_ERROR"
    ) : ReadClientResult<Nothing>() {
        val message: String get() = category
        override fun toString(): String = "ReadClientResult.ParseFailure(category='$category')"
    }

    data class NetworkUnknown(
        val category: String = "NETWORK_ERROR"
    ) : ReadClientResult<Nothing>() {
        val message: String get() = category
        override fun toString(): String = "ReadClientResult.NetworkUnknown(category='$category')"
    }
}
