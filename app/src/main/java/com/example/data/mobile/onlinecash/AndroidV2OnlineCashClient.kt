package com.example.data.mobile.onlinecash

import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.v2.SaleCommitResponse
import com.example.data.mobile.contract.v2.SaleEventStatusResponse
import com.example.data.mobile.readclient.EndpointSecurity
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientErrorResponse
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.ValidationUtils
import java.util.concurrent.CancellationException

/**
 * Typed outcomes for submitting an online cash sale to POST /api/mobile/v2/companies/{companyId}/sales/online-cash.
 */
sealed class OnlineCashSubmitResult {
    /** HTTP 201 Created: Fresh sale committed */
    data class Created(val response: SaleCommitResponse) : OnlineCashSubmitResult()

    /** HTTP 200 OK: Replayed existing canonical sale commit */
    data class Replayed(val response: SaleCommitResponse) : OnlineCashSubmitResult()

    /** HTTP 409 Conflict: Tamper or state conflict */
    data class Conflict409(val error: ReadClientErrorResponse) : OnlineCashSubmitResult()

    /** HTTP 401 Unauthorized: Auth required, expired, or invalid */
    data class AuthDenied401(val error: ReadClientErrorResponse) : OnlineCashSubmitResult()

    /** HTTP 403 Forbidden: Missing permissions, revoked role */
    data class Forbidden403(val error: ReadClientErrorResponse) : OnlineCashSubmitResult()

    /** HTTP 422 Unprocessable Entity: Validation failed */
    data class ValidationFailed422(val error: ReadClientErrorResponse) : OnlineCashSubmitResult()

    /** Other 4xx/5xx HTTP error */
    data class HttpError(val statusCode: Int, val error: ReadClientErrorResponse?) : OnlineCashSubmitResult()

    /** Malformed or unparseable response */
    data class ParseFailure(val category: String = "PARSE_ERROR") : OnlineCashSubmitResult()

    /** Network drop, socket timeout, or I/O failure */
    data class TransientError(val isTimeout: Boolean = false, val category: String = "NETWORK_ERROR") : OnlineCashSubmitResult()
}

/**
 * Typed outcomes for checking the status of an event at GET /api/mobile/v2/companies/{companyId}/offline-events/{clientEventId}/status.
 */
sealed class OnlineCashStatusResult {
    /** Event is posted on server with official invoice */
    data class Posted(val response: SaleEventStatusResponse) : OnlineCashStatusResult()

    /** Event is unknown to server (not recorded yet) */
    data class Unknown(val response: SaleEventStatusResponse) : OnlineCashStatusResult()

    /** Event is parked in conflict on server */
    data class ParkedConflict(val response: SaleEventStatusResponse) : OnlineCashStatusResult()

    /** HTTP 401 Unauthorized */
    data class AuthDenied401(val error: ReadClientErrorResponse) : OnlineCashStatusResult()

    /** HTTP 403 Forbidden */
    data class Forbidden403(val error: ReadClientErrorResponse) : OnlineCashStatusResult()

    /** HTTP 422 Validation Failed */
    data class ValidationFailed422(val error: ReadClientErrorResponse) : OnlineCashStatusResult()

    /** Other 4xx/5xx HTTP error */
    data class HttpError(val statusCode: Int, val error: ReadClientErrorResponse?) : OnlineCashStatusResult()

    /** Malformed response */
    data class ParseFailure(val category: String = "PARSE_ERROR") : OnlineCashStatusResult()

    /** Transient network error */
    data class TransientError(val isTimeout: Boolean = false, val category: String = "NETWORK_ERROR") : OnlineCashStatusResult()
}

/**
 * High-level execution result with Lost ACK recovery.
 */
sealed class OnlineCashExecutionResult {
    /** Sale was successfully committed or confirmed posted on server */
    data class Success(
        val commitResponse: SaleCommitResponse?,
        val statusResponse: SaleEventStatusResponse?,
        val wasReplay: Boolean
    ) : OnlineCashExecutionResult()

    /** Conflict on initial post or during status check */
    data class Conflict(val error: ReadClientErrorResponse) : OnlineCashExecutionResult()

    /** Authentication denied (401) - caller MUST retain queue lock, NEVER auto-unlock */
    data class AuthDenied(val error: ReadClientErrorResponse) : OnlineCashExecutionResult()

    /** Forbidden (403) */
    data class Forbidden(val error: ReadClientErrorResponse) : OnlineCashExecutionResult()

    /** Validation failed (422) */
    data class ValidationFailed(val error: ReadClientErrorResponse) : OnlineCashExecutionResult()

    /** Parse or response schema failure */
    data class ParseFailure(val category: String = "PARSE_ERROR") : OnlineCashExecutionResult()

    /** Transient failure remaining in indeterminate state - original event ID and bytes preserved */
    data class IndeterminateTransient(
        val clientEventId: ClientUuid,
        val payloadDigest: PayloadDigest,
        val reason: String
    ) : OnlineCashExecutionResult()
}

/**
 * Android V2 Online Cash and Status HTTP client.
 *
 * Scoped strictly to:
 * - POST /api/mobile/v2/companies/{companyId}/sales/online-cash (201 create / 200 replay / 409 tamper)
 * - GET  /api/mobile/v2/companies/{companyId}/offline-events/{clientEventId}/status (200 posted/unknown fresh auth)
 *
 * Invariants:
 * - Immutable canonical bytes + digest / event identity preserved.
 * - Strict allowlisted URL paths and response DTO schema checking.
 * - Exact wire decimals: no IEEE-754 Double.
 * - Response event / tenant parity strictly enforced.
 * - Typed sanitized 401/403/409/conflict/transient errors.
 * - CancellationException is never swallowed.
 * - Lost ACK MUST look up status before safe replay of original bytes.
 * - Unknown is not proof of no future commit; preserves original event ID and digest; never regenerates sale.
 * - Never automatically unlocks auth-blocked queue on 401.
 */
class AndroidV2OnlineCashClient(
    private val transport: ReadClientTransport,
    baseOrigin: String,
    allowHttpLoopback: Boolean = false
) {
    val validatedBaseOrigin: String = EndpointSecurity.validateBaseOrigin(baseOrigin, allowHttpLoopback)

    /**
     * Submits an online cash sale request to the backend.
     *
     * @param token Staff session Bearer token
     * @param companyId Tenant company ULID
     * @param clientEventId Canonical event UUID
     * @param expectedPayloadDigest SHA-256 digest of original normative request
     * @param canonicalBytes Defensively copied canonical UTF-8 bytes of wire request
     */
    fun submitSale(
        token: String,
        companyId: ServerUlid,
        clientEventId: ClientUuid,
        expectedPayloadDigest: PayloadDigest,
        canonicalBytes: ByteArray
    ): OnlineCashSubmitResult {
        val path = EndpointSecurity.onlineCashSalePath(companyId)
        val bodyString = String(canonicalBytes.clone(), Charsets.UTF_8)

        val transportRequest = TransportRequest(
            method = HttpMethod.POST,
            path = path,
            baseOrigin = validatedBaseOrigin,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to buildAuthHeader(token)
            ),
            body = bodyString
        )

        return try {
            EndpointSecurity.validateMethodAndPath(transportRequest.method, transportRequest.path)
            val response = transport.execute(transportRequest)

            when (response.statusCode) {
                201 -> {
                    try {
                        val rootObj = StrictJsonParser.parse(response.body).asObject()
                        val commit = SaleCommitResponse.fromStrictJson(rootObj)
                        if (commit.clientEventId != clientEventId) {
                            OnlineCashSubmitResult.ParseFailure("Event parity mismatch: response client_event_id != requested clientEventId")
                        } else {
                            OnlineCashSubmitResult.Created(commit)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        OnlineCashSubmitResult.ParseFailure("PARSE_ERROR")
                    }
                }
                200 -> {
                    try {
                        val rootObj = StrictJsonParser.parse(response.body).asObject()
                        val commit = SaleCommitResponse.fromStrictJson(rootObj)
                        if (commit.clientEventId != clientEventId) {
                            OnlineCashSubmitResult.ParseFailure("Event parity mismatch on replay")
                        } else {
                            OnlineCashSubmitResult.Replayed(commit)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        OnlineCashSubmitResult.ParseFailure("PARSE_ERROR")
                    }
                }
                401 -> {
                    val err = parseErrorBody(response.body, "UNAUTHENTICATED", "Authentication required or token expired.")
                    OnlineCashSubmitResult.AuthDenied401(err)
                }
                403 -> {
                    val err = parseErrorBody(response.body, "FORBIDDEN", "Access forbidden.")
                    OnlineCashSubmitResult.Forbidden403(err)
                }
                409 -> {
                    val err = parseErrorBody(response.body, "CONFLICT", "Resource state conflict.")
                    OnlineCashSubmitResult.Conflict409(err)
                }
                422 -> {
                    val err = parseErrorBody(response.body, "VALIDATION_FAILED", "Validation failed.")
                    OnlineCashSubmitResult.ValidationFailed422(err)
                }
                else -> {
                    val err = try {
                        val rootObj = StrictJsonParser.parse(response.body).asObject()
                        ReadClientErrorResponse.fromStrictJson(rootObj)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        null
                    }
                    OnlineCashSubmitResult.HttpError(response.statusCode, err)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val isTimeout = e.message?.contains("timeout", ignoreCase = true) == true
            OnlineCashSubmitResult.TransientError(isTimeout = isTimeout, category = "NETWORK_ERROR")
        }
    }

    /**
     * Queries event status at GET /api/mobile/v2/companies/{companyId}/offline-events/{clientEventId}/status.
     */
    fun checkStatus(
        token: String,
        companyId: ServerUlid,
        clientEventId: ClientUuid
    ): OnlineCashStatusResult {
        val path = EndpointSecurity.offlineEventStatusPath(companyId, clientEventId)

        val transportRequest = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = validatedBaseOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to buildAuthHeader(token)
            )
        )

        return try {
            EndpointSecurity.validateMethodAndPath(transportRequest.method, transportRequest.path)
            val response = transport.execute(transportRequest)

            when (response.statusCode) {
                200 -> {
                    try {
                        val rootObj = StrictJsonParser.parse(response.body).asObject()
                        val statusResp = SaleEventStatusResponse.fromStrictJson(rootObj)

                        if (statusResp.clientEventId != clientEventId) {
                            return OnlineCashStatusResult.ParseFailure("Event parity mismatch in status response")
                        }
                        if (statusResp.companyId != companyId) {
                            return OnlineCashStatusResult.ParseFailure("Tenant parity mismatch in status response")
                        }

                        when (statusResp.status) {
                            "posted" -> OnlineCashStatusResult.Posted(statusResp)
                            "unknown" -> OnlineCashStatusResult.Unknown(statusResp)
                            "parked_conflict" -> OnlineCashStatusResult.ParkedConflict(statusResp)
                            else -> OnlineCashStatusResult.ParseFailure("Unsupported status enum: '${statusResp.status}'")
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        OnlineCashStatusResult.ParseFailure("PARSE_ERROR")
                    }
                }
                401 -> {
                    val err = parseErrorBody(response.body, "UNAUTHENTICATED", "Authentication required or token expired.")
                    OnlineCashStatusResult.AuthDenied401(err)
                }
                403 -> {
                    val err = parseErrorBody(response.body, "FORBIDDEN", "Access forbidden.")
                    OnlineCashStatusResult.Forbidden403(err)
                }
                422 -> {
                    val err = parseErrorBody(response.body, "VALIDATION_FAILED", "Validation failed.")
                    OnlineCashStatusResult.ValidationFailed422(err)
                }
                else -> {
                    val err = try {
                        val rootObj = StrictJsonParser.parse(response.body).asObject()
                        ReadClientErrorResponse.fromStrictJson(rootObj)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        null
                    }
                    OnlineCashStatusResult.HttpError(response.statusCode, err)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val isTimeout = e.message?.contains("timeout", ignoreCase = true) == true
            OnlineCashStatusResult.TransientError(isTimeout = isTimeout, category = "NETWORK_ERROR")
        }
    }

    /**
     * Executes online cash sale submission with authoritative Lost ACK recovery:
     * 1. Submits sale to POST endpoint.
     * 2. If submission succeeds with 201 or 200: returns Success.
     * 3. If submission returns terminal error (401, 403, 409, 422): returns typed outcome.
     * 4. If submission encounters network drop or timeout (Lost ACK):
     *    a. Calls checkStatus BEFORE any replay of original bytes.
     *    b. If status is 'posted': verifies parity and digest; returns Success (recovered).
     *    c. If status is 'unknown': safely replays original canonical bytes (once).
     *    d. If status is 401: returns AuthDenied without retry or unlocking queue.
     *    e. If status is 403: returns Forbidden.
     *    f. If status fails transiently: returns IndeterminateTransient, preserving original event ID and bytes.
     */
    fun executeOnlineCashSale(
        token: String,
        companyId: ServerUlid,
        clientEventId: ClientUuid,
        expectedPayloadDigest: PayloadDigest,
        canonicalBytes: ByteArray
    ): OnlineCashExecutionResult {
        val defensiveBytes = canonicalBytes.clone()

        val initialSubmit = submitSale(
            token = token,
            companyId = companyId,
            clientEventId = clientEventId,
            expectedPayloadDigest = expectedPayloadDigest,
            canonicalBytes = defensiveBytes
        )

        return when (initialSubmit) {
            is OnlineCashSubmitResult.Created -> OnlineCashExecutionResult.Success(
                commitResponse = initialSubmit.response,
                statusResponse = null,
                wasReplay = false
            )
            is OnlineCashSubmitResult.Replayed -> OnlineCashExecutionResult.Success(
                commitResponse = initialSubmit.response,
                statusResponse = null,
                wasReplay = true
            )
            is OnlineCashSubmitResult.Conflict409 -> OnlineCashExecutionResult.Conflict(initialSubmit.error)
            is OnlineCashSubmitResult.AuthDenied401 -> OnlineCashExecutionResult.AuthDenied(initialSubmit.error)
            is OnlineCashSubmitResult.Forbidden403 -> OnlineCashExecutionResult.Forbidden(initialSubmit.error)
            is OnlineCashSubmitResult.ValidationFailed422 -> OnlineCashExecutionResult.ValidationFailed(initialSubmit.error)
            is OnlineCashSubmitResult.ParseFailure -> OnlineCashExecutionResult.ParseFailure(initialSubmit.category)
            is OnlineCashSubmitResult.HttpError -> OnlineCashExecutionResult.IndeterminateTransient(
                clientEventId = clientEventId,
                payloadDigest = expectedPayloadDigest,
                reason = "HTTP_${initialSubmit.statusCode}"
            )
            is OnlineCashSubmitResult.TransientError -> {
                // Lost ACK: MUST look up status before attempting replay
                val statusResult = checkStatus(
                    token = token,
                    companyId = companyId,
                    clientEventId = clientEventId
                )

                when (statusResult) {
                    is OnlineCashStatusResult.Posted -> {
                        val statusResp = statusResult.response
                        if (statusResp.clientEventId != clientEventId) {
                            return OnlineCashExecutionResult.ParseFailure("Event parity violation in status recovery")
                        }
                        if (statusResp.companyId != companyId) {
                            return OnlineCashExecutionResult.ParseFailure("Tenant parity violation in status recovery")
                        }
                        if (statusResp.payloadDigest != null && statusResp.payloadDigest != expectedPayloadDigest) {
                            return OnlineCashExecutionResult.ParseFailure("Payload digest mismatch in status recovery")
                        }
                        OnlineCashExecutionResult.Success(
                            commitResponse = null,
                            statusResponse = statusResp,
                            wasReplay = true
                        )
                    }
                    is OnlineCashStatusResult.Unknown -> {
                        // Unknown is not proof of no future commit, but permits safe replay of original bytes.
                        // Event ID and digest are preserved; never regenerate sale.
                        val replaySubmit = submitSale(
                            token = token,
                            companyId = companyId,
                            clientEventId = clientEventId,
                            expectedPayloadDigest = expectedPayloadDigest,
                            canonicalBytes = defensiveBytes
                        )

                        when (replaySubmit) {
                            is OnlineCashSubmitResult.Created -> OnlineCashExecutionResult.Success(
                                commitResponse = replaySubmit.response,
                                statusResponse = null,
                                wasReplay = false
                            )
                            is OnlineCashSubmitResult.Replayed -> OnlineCashExecutionResult.Success(
                                commitResponse = replaySubmit.response,
                                statusResponse = null,
                                wasReplay = true
                            )
                            is OnlineCashSubmitResult.Conflict409 -> OnlineCashExecutionResult.Conflict(replaySubmit.error)
                            is OnlineCashSubmitResult.AuthDenied401 -> OnlineCashExecutionResult.AuthDenied(replaySubmit.error)
                            is OnlineCashSubmitResult.Forbidden403 -> OnlineCashExecutionResult.Forbidden(replaySubmit.error)
                            is OnlineCashSubmitResult.ValidationFailed422 -> OnlineCashExecutionResult.ValidationFailed(replaySubmit.error)
                            is OnlineCashSubmitResult.ParseFailure -> OnlineCashExecutionResult.ParseFailure(replaySubmit.category)
                            is OnlineCashSubmitResult.HttpError -> OnlineCashExecutionResult.IndeterminateTransient(
                                clientEventId = clientEventId,
                                payloadDigest = expectedPayloadDigest,
                                reason = "REPLAY_HTTP_${replaySubmit.statusCode}"
                            )
                            is OnlineCashSubmitResult.TransientError -> OnlineCashExecutionResult.IndeterminateTransient(
                                clientEventId = clientEventId,
                                payloadDigest = expectedPayloadDigest,
                                reason = "REPLAY_TRANSIENT_TIMEOUT"
                            )
                        }
                    }
                    is OnlineCashStatusResult.ParkedConflict -> {
                        OnlineCashExecutionResult.Conflict(
                            ReadClientErrorResponse(code = "CONFLICT", message = "Event is in parked_conflict on backend")
                        )
                    }
                    is OnlineCashStatusResult.AuthDenied401 -> {
                        // Preserve locked state; never auto-unlock auth-blocked queue
                        OnlineCashExecutionResult.AuthDenied(statusResult.error)
                    }
                    is OnlineCashStatusResult.Forbidden403 -> OnlineCashExecutionResult.Forbidden(statusResult.error)
                    is OnlineCashStatusResult.ValidationFailed422 -> OnlineCashExecutionResult.ValidationFailed(statusResult.error)
                    is OnlineCashStatusResult.HttpError,
                    is OnlineCashStatusResult.ParseFailure,
                    is OnlineCashStatusResult.TransientError -> {
                        OnlineCashExecutionResult.IndeterminateTransient(
                            clientEventId = clientEventId,
                            payloadDigest = expectedPayloadDigest,
                            reason = "STATUS_LOOKUP_FAILED"
                        )
                    }
                }
            }
        }
    }

    private fun buildAuthHeader(token: String): String {
        ValidationUtils.validateToken(token, "token")
        return "Bearer $token"
    }

    private fun parseErrorBody(body: String, fallbackCode: String, fallbackMessage: String): ReadClientErrorResponse {
        return try {
            val rootObj = StrictJsonParser.parse(body).asObject()
            ReadClientErrorResponse.fromStrictJson(rootObj)
        } catch (_: Exception) {
            ReadClientErrorResponse(code = fallbackCode, message = fallbackMessage)
        }
    }
}
