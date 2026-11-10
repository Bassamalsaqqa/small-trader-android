package com.example.data.mobile.contract

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
        fun fromStrictJson(obj: StrictJsonObject): OfflineSaleLine {
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
        fun fromStrictJson(obj: StrictJsonObject): OfflineSalePayment {
            return OfflineSalePayment(
                mode = obj.require("mode").asString(),
                paymentMethod = obj.require("payment_method").asString(),
                cashAccountId = ServerUlid.parse(obj.require("cash_account_id").asString()),
                amountTendered = MoneyAmount.fromNonnegativeString(obj.require("amount_tendered").asString()),
                amountSettled = MoneyAmount.fromNonnegativeString(obj.require("amount_settled").asString()),
                changeReturned = MoneyAmount.fromNonnegativeString(obj.require("change_returned").asString())
            )
        }
    }
}

data class OfflineSaleEvent(
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
    val lines: List<OfflineSaleLine>,
    val payment: OfflineSalePayment
) : CanonicalSerializable {
    init {
        require(eventVersion == 1) { "event_version must be 1, got $eventVersion" }
        require(ContractConstants.CURRENCY_CODE_REGEX.matches(currency)) {
            "currency must be 3-letter ISO code, got '$currency'"
        }
        require(lines.isNotEmpty()) { "lines must contain at least 1 line item" }
        require(lines.size <= ContractConstants.MAX_LINE_ITEMS) {
            "lines exceeds maximum allowed ${ContractConstants.MAX_LINE_ITEMS}"
        }
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
        fun fromStrictJson(obj: StrictJsonObject): OfflineSaleEvent {
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

    override fun toCanonicalStructure(): Map<String, Any?> = mapOf(
        "client_event_id" to clientEventId.value,
        "device_id" to deviceId.value,
        "captured_at" to capturedAt,
        "payload_digest" to payloadDigest.value,
        "event" to event.toCanonicalStructure()
    )

    companion object {
        fun fromStrictJson(obj: StrictJsonObject): OfflineSaleIntakeRequest {
            return OfflineSaleIntakeRequest(
                clientEventId = ClientUuid.parse(obj.require("client_event_id").asString()),
                deviceId = ClientUuid.parse(obj.require("device_id").asString()),
                capturedAt = obj.require("captured_at").asString(),
                payloadDigest = PayloadDigest.parse(obj.require("payload_digest").asString()),
                event = OfflineSaleEvent.fromStrictJson(obj.require("event").asObject())
            )
        }
    }
}
