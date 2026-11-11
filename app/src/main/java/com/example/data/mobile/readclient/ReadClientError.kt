package com.example.data.mobile.readclient

import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.StrictJsonElement
import com.example.data.mobile.contract.StrictJsonNull
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.readclient.ValidationUtils.checkAllowedKeys
import com.example.data.mobile.readclient.ValidationUtils.optionalString
import com.example.data.mobile.readclient.ValidationUtils.optionalUlid
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
 */
data class ReadClientErrorResponse(
    val code: String,
    val message: String,
    val details: StrictJsonElement? = null,
    val conflictEvidence: ConflictEvidence? = null
) {
    companion object {
        private val ROOT_ALLOWED_KEYS = setOf("error")
        private val ERROR_ALLOWED_KEYS = setOf("code", "message", "details", "conflict_evidence")

        fun fromStrictJson(rootObj: StrictJsonObject): ReadClientErrorResponse {
            checkAllowedKeys(rootObj, ROOT_ALLOWED_KEYS, "ErrorResponse")
            val errObj = requireObject(rootObj, "error")
            checkAllowedKeys(errObj, ERROR_ALLOWED_KEYS, "ErrorResponse.error")

            val code = requireString(errObj, "code", minLength = 1, maxLength = 255)
            val message = requireString(errObj, "message", minLength = 1, maxLength = 2048)
            val detailsElement = errObj.properties["details"]?.takeIf { it !is StrictJsonNull }

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
 */
sealed class ReadClientResult<out T> {
    data class Success<out T>(val value: T) : ReadClientResult<T>()
    data class Auth401(val error: ReadClientErrorResponse) : ReadClientResult<Nothing>()
    data class Forbidden403(val error: ReadClientErrorResponse) : ReadClientResult<Nothing>()
    data class Conflict409(val error: ReadClientErrorResponse) : ReadClientResult<Nothing>()
    data class HttpError(val statusCode: Int, val error: ReadClientErrorResponse?) : ReadClientResult<Nothing>()
    data class ParseFailure(val message: String, val cause: Throwable? = null) : ReadClientResult<Nothing>()
    data class NetworkUnknown(val message: String, val cause: Throwable? = null) : ReadClientResult<Nothing>()
}
