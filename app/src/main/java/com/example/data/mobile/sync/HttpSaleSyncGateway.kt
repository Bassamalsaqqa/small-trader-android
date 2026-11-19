package com.example.data.mobile.sync

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonElement
import com.example.data.mobile.contract.StrictJsonNull
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.http.HttpTransportException
import java.nio.charset.StandardCharsets

/**
 * Concrete HTTP implementation of [SaleSyncGateway] dispatching to native Mobile V2 routes:
 * - Submit: POST /api/mobile/v2/companies/{companyId}/sales/online-cash
 * - Status: GET /api/mobile/v2/companies/{companyId}/offline-events/{clientEventId}/status
 *
 * Enforces strict canonical wire format, exact digest matching, and defensive scope checks.
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
        payloadDigest: PayloadDigest
    ): SyncStatusOutcome {
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
                    require(root.require("company_id").asString() == companyId.value)
                    require(root.require("client_event_id").asString() == clientEventId.value)
                    when (root.require("status").asString()) {
                        "posted" -> {
                            val serverDigest = PayloadDigest.parse(root.require("payload_digest").asString())
                            require(serverDigest == payloadDigest)
                            val invoice = root.require("official_invoice") as? com.example.data.mobile.contract.StrictJsonObject
                                ?: error("Missing official invoice")
                            SyncStatusOutcome.Posted(ServerSaleAck(
                                companyId = ServerUlid.parse(root.require("company_id").asString()),
                                clientEventId = ClientUuid.parse(root.require("client_event_id").asString()),
                                payloadDigest = serverDigest,
                                invoiceUlid = ServerUlid.parse(invoice.require("invoice_id").asString()),
                                paymentUlid = ServerUlid.parse(invoice.require("payment_id").asString()),
                                serverAcknowledgedAt = invoice.require("posted_at").asString()
                            ))
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
        val path = "/api/mobile/v2/companies/${companyId.value}/sales/online-cash"
        val token = tokenProvider()
        val headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json; charset=utf-8",
            "Authorization" to "Bearer $token"
        )
        val bodyString = try {
            val root = StrictJsonParser.parseObject(String(canonicalEventUtf8, StandardCharsets.UTF_8))
            val profile = com.example.data.mobile.contract.v2.OnlineSaleIntentProfile.fromStrictJson(root)
            require(profile.companyId == companyId && profile.actorId == actorId && profile.deviceId == deviceId && profile.clientEventId == clientEventId)
            profile.toWireRequestJson()
        } catch (_: Exception) {
            return SyncSubmitOutcome.BusinessConflict("Invalid online sale intent representation")
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
                    // scoped status resource, which supplies the persisted digest and receipt.
                    when (val status = checkStatus(companyId, actorId, deviceId, clientEventId, payloadDigest)) {
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
