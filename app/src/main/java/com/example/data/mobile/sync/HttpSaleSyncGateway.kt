package com.example.data.mobile.sync

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.Rfc3339Validator
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonNull
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.v2.OfflineSaleEventV2
import com.example.data.mobile.contract.v2.OfflineSaleIntakeRequestV2
import com.example.data.mobile.contract.v2.OnlineSaleIntentProfile
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.http.HttpTransportException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

/**
 * Concrete HTTP implementation of [SaleSyncGateway] dispatching to native Mobile V2 routes:
 * - Submit: POST /api/mobile/v2/companies/{companyId}/sales/online-cash
 * - Status: GET /api/mobile/v2/companies/{companyId}/offline-events/{clientEventId}/status
 *
 * Enforces strict canonical wire format, exact digest matching, and defensive scope checks.
 *
 * CORE INVARIANTS:
 * 1. Status GET original event + company scope, original actor/device authenticated via Bearer token.
 * 2. Parses actual backend wire Posted status and distinct receipt IDs.
 * 3. Server digest must be non-null and match the client canonical digest; no local fallback digest.
 * 4. Payment ID resolution:
 *    - Unpaid: explicit null payment accepted ONLY when bound to an original admitted unpaid profile.
 *    - Full / Partial: MUST be a non-null, valid Crockford ULID, strictly distinct from invoiceUlid.
 *      Payment ULID never falls back to invoice ID.
 * 5. Partial amount semantics compare against original economic intent; no inferred mode from response.
 * 6. Commit (POST) requires authoritative status GET confirmation matching server digest before returning Posted.
 */
class HttpSaleSyncGateway(
    private val transport: ReadClientTransport,
    private val baseOrigin: String,
    private val tokenProvider: () -> String
) : SaleSyncGateway {

    override fun checkStatus(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        payloadDigest: PayloadDigest,
        expectedIntent: OnlineSaleIntentProfile?
    ): SyncStatusOutcome {
        if (expectedIntent != null) {
            require(expectedIntent.companyId == companyId && expectedIntent.actorId == actorId &&
                expectedIntent.deviceId == deviceId && expectedIntent.clientEventId == clientEventId &&
                expectedIntent.computePayloadDigest() == payloadDigest)
        }
        val path = "/api/mobile/v2/companies/${companyId.value}/offline-events/${clientEventId.value}/status"
        val token = tokenProvider()
        val headers = mapOf(
            "Accept" to "application/json",
            "Authorization" to "Bearer $token"
        )
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = baseOrigin,
            headers = headers
        )

        return try {
            val response = transport.execute(request)
            when (response.statusCode) {
                200 -> {
                    val root = StrictJsonParser.parseObject(response.body)
                    require(root.require("company_id").asString() == companyId.value) {
                        "Status response company_id mismatch"
                    }
                    require(root.require("client_event_id").asString() == clientEventId.value) {
                        "Status response client_event_id mismatch"
                    }
                    // The current server binds actor and device through the bearer token.
                    // If returned by the server, these fields must agree with that scope.
                    require(root["actor_id"]?.asString()?.let { it == actorId.value } != false)
                    require(root["device_id"]?.asString()?.let { it == deviceId.value } != false)
                    when (root.require("status").asString()) {
                        "posted" -> {
                            val rawDigestElem = root.require("payload_digest")
                            if (rawDigestElem is StrictJsonNull) {
                                error("Server returned null payload_digest for posted status")
                            }
                            val serverDigestStr = rawDigestElem.asString()
                            if (serverDigestStr.isBlank()) {
                                error("Server returned empty payload_digest for posted status")
                            }
                            val serverDigest = PayloadDigest.parse(serverDigestStr)
                            require(serverDigest == payloadDigest) {
                                "Server digest ($serverDigest) does not match expected payload digest ($payloadDigest)"
                            }

                            val invoice = root.require("official_invoice") as? StrictJsonObject
                                ?: error("Missing official invoice in posted status response")

                            val rawInvoiceId = invoice.require("invoice_id").asString()
                            require(ServerUlid.isValid(rawInvoiceId)) {
                                "Invalid invoice ULID format: $rawInvoiceId"
                            }
                            val invoiceUlid = ServerUlid.parse(rawInvoiceId)

                            val rawInvoiceNumberElem = invoice["invoice_number"]
                            val invoiceNumber = if (rawInvoiceNumberElem != null && rawInvoiceNumberElem !is StrictJsonNull) {
                                rawInvoiceNumberElem.asString().takeIf { it.isNotBlank() }
                            } else null

                            val rawPaymentElem = invoice.require("payment_id")
                            val isPaymentNull = rawPaymentElem is StrictJsonNull

                            val paymentUlid: ServerUlid?
                            if (isPaymentNull) {
                                paymentUlid = null
                            } else {
                                val rawPaymentId = rawPaymentElem.asString()
                                require(ServerUlid.isValid(rawPaymentId)) {
                                    "Invalid payment ULID format: $rawPaymentId"
                                }
                                val parsedPay = ServerUlid.parse(rawPaymentId)
                                require(parsedPay != invoiceUlid) {
                                    "Payment ULID ($parsedPay) must not match invoice ULID ($invoiceUlid); fallback prohibited"
                                }
                                paymentUlid = parsedPay
                            }

                            val rawReceiptElem = invoice["payment_receipt_number"]
                            val paymentReceiptNumber = if (rawReceiptElem != null && rawReceiptElem !is StrictJsonNull) {
                                rawReceiptElem.asString().takeIf { it.isNotBlank() }
                            } else null

                            val postedAt = invoice.require("posted_at").asString()
                            require(Rfc3339Validator.isValid(postedAt)) {
                                "Invalid posted_at timestamp: $postedAt"
                            }

                            // Typed confirmation bound to original retained canonical profile
                            if (expectedIntent != null) {
                                val mode = expectedIntent.payment.settlementMode
                                when (mode) {
                                    "unpaid" -> {
                                        // Unpaid explicit null payment accepted ONLY for original admitted unpaid profile
                                        require(paymentUlid == null) {
                                            "Payment ULID ($paymentUlid) returned for unpaid settlement profile; must be null"
                                        }
                                        require(paymentReceiptNumber == null) {
                                            "Payment receipt number ($paymentReceiptNumber) returned for unpaid settlement profile; must be null"
                                        }
                                    }
                                    "full", "partial" -> {
                                        // full/partial MUST nonnull distinct real payment ULID never fallback invoice ID
                                        require(paymentUlid != null) {
                                            "Explicit null payment is forbidden for $mode settlement mode"
                                        }
                                        require(paymentUlid != invoiceUlid) {
                                            "Payment ULID must never fall back to invoice ID"
                                        }
                                    }
                                    else -> error("Unsupported settlement mode: $mode")
                                }

                                // Compare partial/full amount semantics with original economic intent
                                val rawGrandTotalElem = invoice["grand_total"]
                                if (rawGrandTotalElem != null && rawGrandTotalElem !is StrictJsonNull) {
                                    val grandTotalStr = rawGrandTotalElem.asString()
                                    val grandTotal = BigDecimal(grandTotalStr)
                                    val paidAmount = expectedIntent.payment.amount.value

                                    if (mode == "partial") {
                                        require(paidAmount > BigDecimal.ZERO && paidAmount < grandTotal) {
                                            "Partial paid amount ($paidAmount) violates partial economic intent for grand total ($grandTotal)"
                                        }
                                    } else if (mode == "full") {
                                        require(paidAmount.compareTo(grandTotal) == 0) {
                                            "Full paid amount ($paidAmount) does not match grand total ($grandTotal)"
                                        }
                                    }
                                }
                            } else {
                                // If expectedIntent is missing, explicit null payment is prohibited
                                // Unpaid explicit null payment accepted ONLY for original admitted unpaid profile
                                if (paymentUlid == null) {
                                    error("Explicit null payment is rejected without original admitted unpaid profile")
                                }
                            }

                            SyncStatusOutcome.Posted(
                                ServerSaleAck(
                                    companyId = ServerUlid.parse(root.require("company_id").asString()),
                                    clientEventId = ClientUuid.parse(root.require("client_event_id").asString()),
                                    payloadDigest = serverDigest,
                                    invoiceUlid = invoiceUlid,
                                    paymentUlid = paymentUlid,
                                    serverAcknowledgedAt = postedAt,
                                    invoiceNumber = invoiceNumber,
                                    paymentReceiptNumber = paymentReceiptNumber
                                )
                            )
                        }
                        "unknown" -> SyncStatusOutcome.NotFound
                        else -> SyncStatusOutcome.TransientUnknown("Unrecognized server status")
                    }
                }
                401 -> SyncStatusOutcome.AuthDenied("Authentication required or token expired")
                403 -> SyncStatusOutcome.MembershipDenied("Tenant membership or action permission denied")
                404 -> SyncStatusOutcome.NotFound
                409 -> SyncStatusOutcome.BusinessConflict("Resource conflict on server")
                else -> SyncStatusOutcome.TransientUnknown("Server returned HTTP ${response.statusCode}")
            }
        } catch (e: HttpTransportException) {
            SyncStatusOutcome.TransientUnknown(e.message ?: "Transport error", isTimeout = e.isTimeout())
        } catch (e: Exception) {
            SyncStatusOutcome.TransientUnknown(e.message ?: "Unknown error")
        }
    }

    override fun submitSale(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        payloadDigest: PayloadDigest,
        canonicalEventUtf8: ByteArray
    ): SyncSubmitOutcome {
        require(PayloadDigest.fromBytes(canonicalEventUtf8) == payloadDigest)
        val token = tokenProvider()
        val headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json; charset=utf-8",
            "Authorization" to "Bearer $token"
        )

        val root = try {
            StrictJsonParser.parseObject(String(canonicalEventUtf8, StandardCharsets.UTF_8))
        } catch (_: Exception) {
            return SyncSubmitOutcome.BusinessConflict("Invalid sale representation")
        }

        val isOfflineSale = root.properties.containsKey("device_lease_id")
        val path: String
        val bodyString: String
        val expectedIntent: OnlineSaleIntentProfile?

        if (isOfflineSale) {
            val event = try {
                val ev = OfflineSaleEventV2.fromStrictJson(root)
                require(ev.companyId == companyId && ev.actorId == actorId && ev.deviceId == deviceId && ev.clientEventId == clientEventId)
                ev
            } catch (_: Exception) {
                return SyncSubmitOutcome.BusinessConflict("Invalid offline sale event representation")
            }
            val intakeRequest = OfflineSaleIntakeRequestV2(
                clientEventId = clientEventId,
                deviceId = deviceId,
                capturedAt = event.capturedAt,
                payloadDigest = payloadDigest,
                event = event
            )
            bodyString = CanonicalJsonSerializer.serialize(intakeRequest)
            path = "/api/mobile/v2/companies/${companyId.value}/offline-events"
            expectedIntent = null
        } else {
            val profile = try {
                val p = OnlineSaleIntentProfile.fromStrictJson(root)
                require(p.companyId == companyId && p.actorId == actorId && p.deviceId == deviceId && p.clientEventId == clientEventId)
                p
            } catch (_: Exception) {
                return SyncSubmitOutcome.BusinessConflict("Invalid online sale intent representation")
            }
            bodyString = profile.toWireRequestJson()
            path = "/api/mobile/v2/companies/${companyId.value}/sales/online-cash"
            expectedIntent = profile
        }

        val request = TransportRequest(
            method = HttpMethod.POST,
            path = path,
            baseOrigin = baseOrigin,
            headers = headers,
            body = bodyString
        )

        return try {
            val response = transport.execute(request)
            when (response.statusCode) {
                200, 201 -> {
                    // Commit responses omit the canonical digest. Confirm only through the
                    // scoped status resource, which supplies the persisted digest and receipt,
                    // bound to original retained canonical profile or verified offline event.
                    when (val status = checkStatus(companyId, actorId, deviceId, clientEventId, payloadDigest, expectedIntent = expectedIntent)) {
                        is SyncStatusOutcome.Posted -> SyncSubmitOutcome.Posted(status.ack)
                        is SyncStatusOutcome.AuthDenied -> SyncSubmitOutcome.AuthDenied()
                        is SyncStatusOutcome.MembershipDenied -> SyncSubmitOutcome.MembershipDenied()
                        is SyncStatusOutcome.BusinessConflict -> SyncSubmitOutcome.BusinessConflict()
                        else -> SyncSubmitOutcome.TransientUnknown("Commit requires authoritative status confirmation")
                    }
                }
                401 -> SyncSubmitOutcome.AuthDenied("Authentication required or token expired")
                403 -> SyncSubmitOutcome.MembershipDenied("Tenant membership or action permission denied")
                409 -> SyncSubmitOutcome.BusinessConflict("Duplicate payload or ledger conflict")
                else -> SyncSubmitOutcome.TransientUnknown("Server returned HTTP ${response.statusCode}")
            }
        } catch (e: HttpTransportException) {
            SyncSubmitOutcome.TransientUnknown(e.message ?: "Transport error", isTimeout = e.isTimeout())
        } catch (e: Exception) {
            SyncSubmitOutcome.TransientUnknown(e.message ?: "Unknown error")
        }
    }

    private fun HttpTransportException.isTimeout(): Boolean =
        category == HttpTransportException.CATEGORY_TIMEOUT
}
