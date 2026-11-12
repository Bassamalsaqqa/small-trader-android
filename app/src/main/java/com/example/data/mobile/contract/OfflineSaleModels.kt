package com.example.data.mobile.contract

import java.math.BigDecimal

/**
 * Packaging unit mapping documentation:
 * In client-facing contracts and sales drafts (SalesDraftIntent), packaging units are
 * uniquely and deterministically identified via stable external mapping bound to the packaging unit's
 * unit of measure (unit_id ULID), NOT an internal database join auto-increment ID.
 */
data class OfflineSaleLine(
    val productId: ServerUlid,
    /**
     * External packaging unit mapping: unit public ULID bound to product.
     * Schema parity: units table has public char(26) ULID, product_units join table does not.
     */
    val productUnitId: ServerUlid,
    val unitId: ServerUlid,
    val quantity: DecimalQuantity,
    val unitPrice: MoneyAmount,
    val taxRate: TaxPercentage,
    val taxAmount: MoneyAmount,
    val lineTotal: MoneyAmount
) : CanonicalSerializable {
    init {
        // unitId vs productUnitId mapping equality per ADR-0001 Section 4
        require(unitId == productUnitId) {
            "unitId ($unitId) must match productUnitId ($productUnitId) for offline sale line mapping"
        }
        // Reject negative or zero line total according to schema
        require(lineTotal.value > BigDecimal.ZERO) {
            "lineTotal must be strictly positive according to schema, got ${lineTotal.lexeme}"
        }
    }

    override fun toCanonicalStructure(): Map<String, Any?> = mapOf(
        "product_id" to productId.value,
        "product_unit_id" to productUnitId.value,
        "unit_id" to unitId.value,
        "quantity" to quantity.toPlainString(),
        "unit_price" to unitPrice.toPlainString(),
        "tax_rate" to taxRate.toPlainString(),
        "tax_amount" to taxAmount.toPlainString(),
        "line_total" to lineTotal.toPlainString()
    )

    companion object {
        private val ALLOWED_KEYS = setOf(
            "product_id", "product_unit_id", "unit_id", "quantity",
            "unit_price", "tax_rate", "tax_amount", "line_total"
        )

        fun fromStrictJson(obj: StrictJsonObject): OfflineSaleLine {
            val unexpected = obj.properties.keys - ALLOWED_KEYS
            if (unexpected.isNotEmpty()) {
                throw CanonicalSerializationException("Unexpected fields in OfflineSaleLine: $unexpected")
            }
            return OfflineSaleLine(
                productId = ServerUlid.parse(obj.require("product_id").asString()),
                productUnitId = ServerUlid.parse(obj.require("product_unit_id").asString()),
                unitId = ServerUlid.parse(obj.require("unit_id").asString()),
                quantity = DecimalQuantity.fromPositiveString(obj.require("quantity").asString()),
                unitPrice = MoneyAmount.fromNonnegativeString(obj.require("unit_price").asString()),
                taxRate = TaxPercentage.fromString(obj.require("tax_rate").asString()),
                taxAmount = MoneyAmount.fromNonnegativeString(obj.require("tax_amount").asString()),
                lineTotal = MoneyAmount.fromPositiveString(obj.require("line_total").asString())
            )
        }
    }
}

data class OfflineSalePayment(
    val mode: String,
    val paymentMethod: String,
    val cashAccountId: ServerUlid,
    val amountTendered: MoneyAmount,
    val amountSettled: MoneyAmount,
    val changeReturned: MoneyAmount
) : CanonicalSerializable {
    init {
        require(mode == "full") { "Offline payment mode must be 'full', got '$mode'" }
        require(paymentMethod == "cash") { "Offline payment method must be 'cash', got '$paymentMethod'" }
        require(amountSettled.value > BigDecimal.ZERO) {
            "amountSettled must be strictly positive according to schema, got ${amountSettled.lexeme}"
        }
        require(amountTendered.value >= amountSettled.value) {
            "amountTendered (${amountTendered.lexeme}) must be >= amountSettled (${amountSettled.lexeme})"
        }
        // Cash conservation: amountTendered == amountSettled + changeReturned
        val expectedChange = amountTendered.value.subtract(amountSettled.value)
        require(expectedChange.compareTo(changeReturned.value) == 0) {
            "Cash tendered/change/settled conservation violated: tendered (${amountTendered.lexeme}) - settled (${amountSettled.lexeme}) != change (${changeReturned.lexeme})"
        }
    }

    override fun toCanonicalStructure(): Map<String, Any?> = mapOf(
        "mode" to mode,
        "payment_method" to paymentMethod,
        "cash_account_id" to cashAccountId.value,
        "amount_tendered" to amountTendered.toPlainString(),
        "amount_settled" to amountSettled.toPlainString(),
        "change_returned" to changeReturned.toPlainString()
    )

    companion object {
        private val ALLOWED_KEYS = setOf(
            "mode", "payment_method", "cash_account_id",
            "amount_tendered", "amount_settled", "change_returned"
        )

        fun fromStrictJson(obj: StrictJsonObject): OfflineSalePayment {
            val unexpected = obj.properties.keys - ALLOWED_KEYS
            if (unexpected.isNotEmpty()) {
                throw CanonicalSerializationException("Unexpected fields in OfflineSalePayment: $unexpected")
            }
            return OfflineSalePayment(
                mode = obj.require("mode").asString(),
                paymentMethod = obj.require("payment_method").asString(),
                cashAccountId = ServerUlid.parse(obj.require("cash_account_id").asString()),
                amountTendered = MoneyAmount.fromNonnegativeString(obj.require("amount_tendered").asString()),
                amountSettled = MoneyAmount.fromPositiveString(obj.require("amount_settled").asString()),
                changeReturned = MoneyAmount.fromNonnegativeString(obj.require("change_returned").asString())
            )
        }
    }
}

class OfflineSaleEvent(
    val clientEventId: ClientUuid,
    val eventVersion: Int = 1,
    val companyId: ServerUlid,
    val actorId: ServerUlid,
    val deviceId: ClientUuid,
    val deviceLeaseId: ClientUuid,
    val capturedAt: String,
    val customerId: ServerUlid,
    val currency: String,
    val warehouseId: ServerUlid,
    val cashAccountId: ServerUlid,
    val catalogSnapshotVersion: String,
    val localReceiptNumber: String,
    lines: List<OfflineSaleLine>,
    val payment: OfflineSalePayment
) : CanonicalSerializable {

    // Defensive copy: avoid mutable source alias so event remains strictly immutable after construction
    val lines: List<OfflineSaleLine> = java.util.Collections.unmodifiableList(ArrayList(lines))

    init {
        require(eventVersion == 1) { "event_version must be 1, got $eventVersion" }
        require(ContractConstants.CURRENCY_CODE_REGEX.matches(currency)) {
            "currency must be 3-letter ISO code, got '$currency'"
        }
        require(this.lines.isNotEmpty()) { "lines must contain at least 1 line item" }
        require(this.lines.size <= ContractConstants.MAX_LINE_ITEMS) {
            "lines exceeds maximum allowed ${ContractConstants.MAX_LINE_ITEMS}"
        }
        // Calendar is available on API 24; java.time requires API 26 or desugaring.
        validateCapturedAt(capturedAt)
        // String caps
        require(catalogSnapshotVersion.isNotEmpty() && catalogSnapshotVersion.length <= 64) {
            "catalogSnapshotVersion length must be 1..64, got '${catalogSnapshotVersion.length}'"
        }
        require(localReceiptNumber.isNotEmpty() && localReceiptNumber.length <= 64) {
            "localReceiptNumber length must be 1..64, got '${localReceiptNumber.length}'"
        }
        // Top-level cashAccountId vs payment cashAccountId equality
        require(cashAccountId == payment.cashAccountId) {
            "Top-level cashAccountId ($cashAccountId) does not match payment cashAccountId (${payment.cashAccountId})"
        }
        // Full settlement vs line sum
        val lineSum = this.lines.fold(BigDecimal.ZERO) { acc, line -> acc.add(line.lineTotal.value) }
        require(lineSum.compareTo(payment.amountSettled.value) == 0) {
            "Payment amountSettled (${payment.amountSettled.lexeme}) does not match sum of line totals ($lineSum)"
        }
    }

    fun copy(
        clientEventId: ClientUuid = this.clientEventId,
        eventVersion: Int = this.eventVersion,
        companyId: ServerUlid = this.companyId,
        actorId: ServerUlid = this.actorId,
        deviceId: ClientUuid = this.deviceId,
        deviceLeaseId: ClientUuid = this.deviceLeaseId,
        capturedAt: String = this.capturedAt,
        customerId: ServerUlid = this.customerId,
        currency: String = this.currency,
        warehouseId: ServerUlid = this.warehouseId,
        cashAccountId: ServerUlid = this.cashAccountId,
        catalogSnapshotVersion: String = this.catalogSnapshotVersion,
        localReceiptNumber: String = this.localReceiptNumber,
        lines: List<OfflineSaleLine> = this.lines,
        payment: OfflineSalePayment = this.payment
    ): OfflineSaleEvent = OfflineSaleEvent(
        clientEventId = clientEventId,
        eventVersion = eventVersion,
        companyId = companyId,
        actorId = actorId,
        deviceId = deviceId,
        deviceLeaseId = deviceLeaseId,
        capturedAt = capturedAt,
        customerId = customerId,
        currency = currency,
        warehouseId = warehouseId,
        cashAccountId = cashAccountId,
        catalogSnapshotVersion = catalogSnapshotVersion,
        localReceiptNumber = localReceiptNumber,
        lines = lines,
        payment = payment
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OfflineSaleEvent) return false
        return clientEventId == other.clientEventId &&
                eventVersion == other.eventVersion &&
                companyId == other.companyId &&
                actorId == other.actorId &&
                deviceId == other.deviceId &&
                deviceLeaseId == other.deviceLeaseId &&
                capturedAt == other.capturedAt &&
                customerId == other.customerId &&
                currency == other.currency &&
                warehouseId == other.warehouseId &&
                cashAccountId == other.cashAccountId &&
                catalogSnapshotVersion == other.catalogSnapshotVersion &&
                localReceiptNumber == other.localReceiptNumber &&
                lines == other.lines &&
                payment == other.payment
    }

    override fun hashCode(): Int {
        var result = clientEventId.hashCode()
        result = 31 * result + eventVersion
        result = 31 * result + companyId.hashCode()
        result = 31 * result + actorId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + deviceLeaseId.hashCode()
        result = 31 * result + capturedAt.hashCode()
        result = 31 * result + customerId.hashCode()
        result = 31 * result + currency.hashCode()
        result = 31 * result + warehouseId.hashCode()
        result = 31 * result + cashAccountId.hashCode()
        result = 31 * result + catalogSnapshotVersion.hashCode()
        result = 31 * result + localReceiptNumber.hashCode()
        result = 31 * result + lines.hashCode()
        result = 31 * result + payment.hashCode()
        return result
    }

    override fun toString(): String {
        return "OfflineSaleEvent(clientEventId=$clientEventId, companyId=$companyId, capturedAt='$capturedAt', lines=$lines, payment=$payment)"
    }

    override fun toCanonicalStructure(): Map<String, Any?> = mapOf(
        "client_event_id" to clientEventId.value,
        "event_version" to eventVersion,
        "company_id" to companyId.value,
        "actor_id" to actorId.value,
        "device_id" to deviceId.value,
        "device_lease_id" to deviceLeaseId.value,
        "captured_at" to capturedAt,
        "customer_id" to customerId.value,
        "currency" to currency,
        "warehouse_id" to warehouseId.value,
        "cash_account_id" to cashAccountId.value,
        "catalog_snapshot_version" to catalogSnapshotVersion,
        "local_receipt_number" to localReceiptNumber,
        "lines" to lines.map { it.toCanonicalStructure() },
        "payment" to payment.toCanonicalStructure()
    )

    fun computePayloadDigest(): PayloadDigest =
        CanonicalJsonSerializer.computeDigest(this)

    companion object {
        private val ALLOWED_KEYS = setOf(
            "client_event_id", "event_version", "company_id", "actor_id",
            "device_id", "device_lease_id", "captured_at", "customer_id",
            "currency", "warehouse_id", "cash_account_id",
            "catalog_snapshot_version", "local_receipt_number",
            "lines", "payment"
        )

        fun fromStrictJson(obj: StrictJsonObject): OfflineSaleEvent {
            val unexpected = obj.properties.keys - ALLOWED_KEYS
            if (unexpected.isNotEmpty()) {
                throw CanonicalSerializationException("Unexpected fields in OfflineSaleEvent: $unexpected")
            }
            val linesJson = obj.require("lines").asArray()
            val lines = linesJson.elements.map { OfflineSaleLine.fromStrictJson(it.asObject()) }
            return OfflineSaleEvent(
                clientEventId = ClientUuid.parse(obj.require("client_event_id").asString()),
                eventVersion = obj.require("event_version").asInt(),
                companyId = ServerUlid.parse(obj.require("company_id").asString()),
                actorId = ServerUlid.parse(obj.require("actor_id").asString()),
                deviceId = ClientUuid.parse(obj.require("device_id").asString()),
                deviceLeaseId = ClientUuid.parse(obj.require("device_lease_id").asString()),
                capturedAt = obj.require("captured_at").asString(),
                customerId = ServerUlid.parse(obj.require("customer_id").asString()),
                currency = obj.require("currency").asString(),
                warehouseId = ServerUlid.parse(obj.require("warehouse_id").asString()),
                cashAccountId = ServerUlid.parse(obj.require("cash_account_id").asString()),
                catalogSnapshotVersion = obj.require("catalog_snapshot_version").asString(),
                localReceiptNumber = obj.require("local_receipt_number").asString(),
                lines = lines,
                payment = OfflineSalePayment.fromStrictJson(obj.require("payment").asObject())
            )
        }
    }
}

data class OfflineSaleIntakeRequest(
    val clientEventId: ClientUuid,
    val deviceId: ClientUuid,
    val capturedAt: String,
    val payloadDigest: PayloadDigest,
    val event: OfflineSaleEvent
) : CanonicalSerializable {

    init {
        validateEnvelopeBindings()
    }

    fun validateEnvelopeBindings() {
        require(clientEventId == event.clientEventId) {
            "Envelope client_event_id ($clientEventId) does not match inner event client_event_id (${event.clientEventId})"
        }
        require(deviceId == event.deviceId) {
            "Envelope device_id ($deviceId) does not match inner event device_id (${event.deviceId})"
        }
        require(capturedAt == event.capturedAt) {
            "Envelope captured_at ($capturedAt) does not match inner event captured_at (${event.capturedAt})"
        }
        val computedDigest = event.computePayloadDigest()
        require(payloadDigest == computedDigest) {
            "Envelope payload_digest ($payloadDigest) does not match computed digest ($computedDigest)"
        }
    }

    override fun toCanonicalStructure(): Map<String, Any?> {
        validateEnvelopeBindings()
        return mapOf(
            "client_event_id" to clientEventId.value,
            "device_id" to deviceId.value,
            "captured_at" to capturedAt,
            "payload_digest" to payloadDigest.value,
            "event" to event.toCanonicalStructure()
        )
    }

    companion object {
        private val ALLOWED_KEYS = setOf(
            "client_event_id", "device_id", "captured_at", "payload_digest", "event"
        )

        fun fromStrictJson(obj: StrictJsonObject): OfflineSaleIntakeRequest {
            val unexpected = obj.properties.keys - ALLOWED_KEYS
            if (unexpected.isNotEmpty()) {
                throw CanonicalSerializationException("Unexpected fields in OfflineSaleIntakeRequest: $unexpected")
            }

            // Validate hash against original strict parsed canonical event BEFORE coercion; no silent normalization.
            val rawEventObj = obj.require("event").asObject()
            val rawComputedDigest = CanonicalJsonSerializer.computeDigest(rawEventObj)
            val declaredDigest = PayloadDigest.parse(obj.require("payload_digest").asString())
            if (declaredDigest != rawComputedDigest) {
                throw CanonicalSerializationException(
                    "Envelope payload_digest ($declaredDigest) does not match raw parsed event digest ($rawComputedDigest)"
                )
            }

            return OfflineSaleIntakeRequest(
                clientEventId = ClientUuid.parse(obj.require("client_event_id").asString()),
                deviceId = ClientUuid.parse(obj.require("device_id").asString()),
                capturedAt = obj.require("captured_at").asString(),
                payloadDigest = declaredDigest,
                event = OfflineSaleEvent.fromStrictJson(rawEventObj)
            )
        }
    }
}

/** Strict RFC 3339 field validation using APIs available at the supported minimum SDK. */
internal fun validateCapturedAt(value: String) {
    val match = Regex("""^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.[0-9]{1,9})?(?:[Zz]|([+-])(\d{2}):(\d{2}))$""").matchEntire(value)
        ?: throw IllegalArgumentException("capturedAt must be an RFC 3339 timestamp")
    val groups = match.groupValues
    require(groups[1].toInt() in 2020..2100) { "capturedAt year is outside supported bounds" }
    if (groups[7].isNotEmpty()) {
        require(groups[8].toInt() <= 23 && groups[9].toInt() <= 59) { "Invalid timestamp offset" }
    }
    val calendar = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
    calendar.isLenient = false
    calendar.clear()
    calendar.set(groups[1].toInt(), groups[2].toInt() - 1, groups[3].toInt(),
        groups[4].toInt(), groups[5].toInt(), groups[6].toInt())
    calendar.timeInMillis // Force validation, including month lengths and leap years.
}
