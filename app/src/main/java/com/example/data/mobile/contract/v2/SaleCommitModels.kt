package com.example.data.mobile.contract.v2

import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonNull
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.readclient.ValidationUtils.checkAllowedKeys
import com.example.data.mobile.readclient.ValidationUtils.optionalString
import com.example.data.mobile.readclient.ValidationUtils.requireMoney
import com.example.data.mobile.readclient.ValidationUtils.requireNullableString
import com.example.data.mobile.readclient.ValidationUtils.requireNullableUlid
import com.example.data.mobile.readclient.ValidationUtils.requirePositiveMoney
import com.example.data.mobile.readclient.ValidationUtils.requireString
import com.example.data.mobile.readclient.ValidationUtils.requireUlid
import com.example.data.mobile.readclient.ValidationUtils.requireUuid
import com.example.data.mobile.readclient.ValidationUtils.validateDateTime
import com.example.data.mobile.readclient.ValidationUtils.validateStringBounds

/**
 * Authoritative response payload for POST /api/mobile/v2/companies/{companyId}/sales/online-cash
 * (HTTP 201 Created on fresh commit or HTTP 200 OK on lost-ACK replay).
 *
 * Wire specification adheres strictly to Revision 3 schema: sale-commit-response.json.
 * Invariants:
 * - Exactly 10 required root properties.
 * - additionalProperties is false (zero extra / unreviewed alias fields permitted).
 * - Exact string decimal lexemes preserved without IEEE-754 Double.
 * - Internal integer IDs strictly forbidden.
 */
data class SaleCommitResponse(
    val clientEventId: ClientUuid,
    val status: String,
    val invoiceId: ServerUlid,
    val invoiceNumber: String,
    val paymentId: ServerUlid?,
    val paymentReceiptNumber: String?,
    val grandTotal: MoneyAmount,
    val amountPaid: MoneyAmount,
    val changeReturned: MoneyAmount,
    val postedAt: String
) {
    init {
        require(status == "posted") {
            "status must be 'posted', got '$status'"
        }
        validateStringBounds(invoiceNumber, "invoice_number", minLength = 1, maxLength = 64)
        paymentReceiptNumber?.let {
            validateStringBounds(it, "payment_receipt_number", minLength = 0, maxLength = 64)
        }
        validateDateTime(postedAt, "posted_at")
    }

    override fun toString(): String =
        "SaleCommitResponse(clientEventId=$clientEventId, status='$status', invoiceId=$invoiceId, " +
            "invoiceNumber='$invoiceNumber', paymentId=$paymentId, paymentReceiptNumber=${paymentReceiptNumber?.let { "'$it'" } ?: "null"}, " +
            "grandTotal=$grandTotal, amountPaid=$amountPaid, changeReturned=$changeReturned, postedAt='$postedAt')"

    companion object {
        val ALLOWED_KEYS: Set<String> = setOf(
            "client_event_id",
            "status",
            "invoice_id",
            "invoice_number",
            "payment_id",
            "payment_receipt_number",
            "grand_total",
            "amount_paid",
            "change_returned",
            "posted_at"
        )

        val REQUIRED_KEYS: Set<String> = setOf(
            "client_event_id",
            "status",
            "invoice_id",
            "invoice_number",
            "grand_total",
            "amount_paid",
            "change_returned",
            "posted_at"
        )

        fun fromStrictJson(obj: StrictJsonObject): SaleCommitResponse {
            checkAllowedKeys(obj, ALLOWED_KEYS, "SaleCommitResponse")

            val missing = REQUIRED_KEYS - obj.properties.keys
            if (missing.isNotEmpty()) {
                throw CanonicalSerializationException("Missing required keys in SaleCommitResponse: $missing")
            }

            val clientEventId = requireUuid(obj, "client_event_id")
            val status = requireString(obj, "status", minLength = 6, maxLength = 6)
            if (status != "posted") {
                throw CanonicalSerializationException("status must be 'posted', got '$status'")
            }

            val invoiceId = requireUlid(obj, "invoice_id")
            val invoiceNumber = requireString(obj, "invoice_number", minLength = 1, maxLength = 64)
            val paymentId = if (obj.properties.containsKey("payment_id")) {
                requireNullableUlid(obj, "payment_id")
            } else null
            val paymentReceiptNumber = if (obj.properties.containsKey("payment_receipt_number")) {
                requireNullableString(obj, "payment_receipt_number", minLength = 0, maxLength = 64)
            } else null

            val grandTotal = requirePositiveMoney(obj, "grand_total")
            val amountPaid = requireMoney(obj, "amount_paid")
            val changeReturned = requireMoney(obj, "change_returned")
            val postedAt = requireString(obj, "posted_at")
            validateDateTime(postedAt, "posted_at")

            return SaleCommitResponse(
                clientEventId = clientEventId,
                status = status,
                invoiceId = invoiceId,
                invoiceNumber = invoiceNumber,
                paymentId = paymentId,
                paymentReceiptNumber = paymentReceiptNumber,
                grandTotal = grandTotal,
                amountPaid = amountPaid,
                changeReturned = changeReturned,
                postedAt = postedAt
            )
        }
    }
}

/**
 * Nested official invoice details in SaleEventStatusResponse.
 * Strict parity with commit wire DTO fields.
 */
data class OfficialInvoiceDto(
    val invoiceId: ServerUlid,
    val invoiceNumber: String,
    val paymentId: ServerUlid?,
    val paymentReceiptNumber: String?,
    val grandTotal: MoneyAmount,
    val postedAt: String
) {
    init {
        validateStringBounds(invoiceNumber, "invoice_number", minLength = 1, maxLength = 64)
        paymentReceiptNumber?.let {
            validateStringBounds(it, "payment_receipt_number", minLength = 0, maxLength = 64)
        }
        validateDateTime(postedAt, "posted_at")
    }

    companion object {
        val ALLOWED_KEYS: Set<String> = setOf(
            "invoice_id",
            "invoice_number",
            "payment_id",
            "payment_receipt_number",
            "grand_total",
            "posted_at"
        )

        fun fromStrictJson(obj: StrictJsonObject): OfficialInvoiceDto {
            checkAllowedKeys(obj, ALLOWED_KEYS, "OfficialInvoiceDto")

            val missing = setOf("invoice_id", "invoice_number", "grand_total", "posted_at") - obj.properties.keys
            if (missing.isNotEmpty()) {
                throw CanonicalSerializationException("Missing required keys in OfficialInvoiceDto: $missing")
            }

            val invoiceId = requireUlid(obj, "invoice_id")
            val invoiceNumber = requireString(obj, "invoice_number", minLength = 1, maxLength = 64)
            val paymentId = if (obj.properties.containsKey("payment_id")) {
                requireNullableUlid(obj, "payment_id")
            } else null
            val paymentReceiptNumber = if (obj.properties.containsKey("payment_receipt_number")) {
                requireNullableString(obj, "payment_receipt_number", minLength = 0, maxLength = 64)
            } else null
            val grandTotal = requirePositiveMoney(obj, "grand_total")
            val postedAt = requireString(obj, "posted_at")
            validateDateTime(postedAt, "posted_at")

            return OfficialInvoiceDto(
                invoiceId = invoiceId,
                invoiceNumber = invoiceNumber,
                paymentId = paymentId,
                paymentReceiptNumber = paymentReceiptNumber,
                grandTotal = grandTotal,
                postedAt = postedAt
            )
        }
    }
}

/**
 * Diagnostic conflict details when status is parked_conflict.
 */
data class ConflictDto(
    val conflictCode: String,
    val conflictReason: String,
    val parkedAt: String,
    val resolutionStatus: String
) {
    init {
        validateStringBounds(conflictCode, "conflict_code", minLength = 1, maxLength = 64)
        validateStringBounds(conflictReason, "conflict_reason", minLength = 1, maxLength = 255)
        validateDateTime(parkedAt, "parked_at")
        require(resolutionStatus in ALLOWED_RESOLUTION_STATUSES) {
            "Invalid resolution_status: '$resolutionStatus'. Must be one of $ALLOWED_RESOLUTION_STATUSES"
        }
    }

    companion object {
        val ALLOWED_KEYS: Set<String> = setOf(
            "conflict_code",
            "conflict_reason",
            "parked_at",
            "resolution_status"
        )
        val ALLOWED_RESOLUTION_STATUSES: Set<String> = setOf(
            "pending_manager_review",
            "resolved",
            "rejected"
        )

        fun fromStrictJson(obj: StrictJsonObject): ConflictDto {
            checkAllowedKeys(obj, ALLOWED_KEYS, "ConflictDto")

            val missing = ALLOWED_KEYS - obj.properties.keys
            if (missing.isNotEmpty()) {
                throw CanonicalSerializationException("Missing required keys in ConflictDto: $missing")
            }

            val conflictCode = requireString(obj, "conflict_code", minLength = 1, maxLength = 64)
            val conflictReason = requireString(obj, "conflict_reason", minLength = 1, maxLength = 255)
            val parkedAt = requireString(obj, "parked_at")
            validateDateTime(parkedAt, "parked_at")
            val resolutionStatus = requireString(obj, "resolution_status")
            if (resolutionStatus !in ALLOWED_RESOLUTION_STATUSES) {
                throw CanonicalSerializationException("Invalid resolution_status: '$resolutionStatus'")
            }

            return ConflictDto(
                conflictCode = conflictCode,
                conflictReason = conflictReason,
                parkedAt = parkedAt,
                resolutionStatus = resolutionStatus
            )
        }
    }
}

/**
 * Authoritative response payload for GET /api/mobile/v2/companies/{companyId}/offline-events/{clientEventId}/status.
 *
 * Wire specification adheres strictly to Revision 3 schema: sale-event-status-response.json.
 * Exactly 7 root properties:
 * - client_event_id (UUID)
 * - company_id (ULID)
 * - status (posted | parked_conflict | unknown)
 * - payload_digest (sha256:... | null)
 * - official_invoice (object | null)
 * - conflict (object | null)
 * - queried_at (ISO 8601 date-time)
 */
data class SaleEventStatusResponse(
    val clientEventId: ClientUuid,
    val companyId: ServerUlid,
    val status: String,
    val payloadDigest: PayloadDigest?,
    val officialInvoice: OfficialInvoiceDto?,
    val conflict: ConflictDto?,
    val queriedAt: String
) {
    init {
        require(status in ALLOWED_STATUSES) {
            "status must be one of $ALLOWED_STATUSES, got '$status'"
        }
        validateDateTime(queriedAt, "queried_at")
        when (status) {
            "unknown" -> {
                require(officialInvoice == null) { "official_invoice must be null when status is 'unknown'" }
                require(conflict == null) { "conflict must be null when status is 'unknown'" }
            }
            "posted" -> {
                require(officialInvoice != null) { "official_invoice must be present when status is 'posted'" }
                require(conflict == null) { "conflict must be null when status is 'posted'" }
            }
            "parked_conflict" -> {
                require(conflict != null) { "conflict must be present when status is 'parked_conflict'" }
            }
        }
    }

    override fun toString(): String =
        "SaleEventStatusResponse(clientEventId=$clientEventId, companyId=$companyId, status='$status', " +
            "payloadDigest=$payloadDigest, officialInvoice=$officialInvoice, conflict=$conflict, queriedAt='$queriedAt')"

    companion object {
        val ALLOWED_KEYS: Set<String> = setOf(
            "client_event_id",
            "company_id",
            "status",
            "payload_digest",
            "official_invoice",
            "conflict",
            "queried_at"
        )
        val ALLOWED_STATUSES: Set<String> = setOf(
            "posted",
            "parked_conflict",
            "unknown"
        )

        fun fromStrictJson(obj: StrictJsonObject): SaleEventStatusResponse {
            checkAllowedKeys(obj, ALLOWED_KEYS, "SaleEventStatusResponse")

            val missing = setOf("client_event_id", "company_id", "status", "queried_at") - obj.properties.keys
            if (missing.isNotEmpty()) {
                throw CanonicalSerializationException("Missing required keys in SaleEventStatusResponse: $missing")
            }

            val clientEventId = requireUuid(obj, "client_event_id")
            val companyId = requireUlid(obj, "company_id")
            val status = requireString(obj, "status")
            if (status !in ALLOWED_STATUSES) {
                throw CanonicalSerializationException("Invalid status in SaleEventStatusResponse: '$status'")
            }

            val rawDigest = optionalString(obj, "payload_digest")
            val payloadDigest = rawDigest?.let {
                if (!ContractConstants.SHA256_DIGEST_REGEX.matches(it)) {
                    throw CanonicalSerializationException("Invalid payload_digest format: '$it'")
                }
                PayloadDigest.parse(it)
            }

            val officialInvoice = if (obj.properties.containsKey("official_invoice")) {
                val elem = obj.properties["official_invoice"]!!
                when (elem) {
                    is StrictJsonNull -> null
                    is StrictJsonObject -> OfficialInvoiceDto.fromStrictJson(elem)
                    else -> throw CanonicalSerializationException("official_invoice must be an object or null")
                }
            } else null

            val conflict = if (obj.properties.containsKey("conflict")) {
                val elem = obj.properties["conflict"]!!
                when (elem) {
                    is StrictJsonNull -> null
                    is StrictJsonObject -> ConflictDto.fromStrictJson(elem)
                    else -> throw CanonicalSerializationException("conflict must be an object or null")
                }
            } else null

            val queriedAt = requireString(obj, "queried_at")
            validateDateTime(queriedAt, "queried_at")

            return SaleEventStatusResponse(
                clientEventId = clientEventId,
                companyId = companyId,
                status = status,
                payloadDigest = payloadDigest,
                officialInvoice = officialInvoice,
                conflict = conflict,
                queriedAt = queriedAt
            )
        }
    }
}
