package com.example.data.mobile.contract.v2

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.CanonicalSerializable
import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonBoolean
import com.example.data.mobile.contract.StrictJsonNull
import com.example.data.mobile.contract.StrictJsonNumber
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.TaxPercentage
import java.math.BigDecimal
import java.util.Collections

/**
 * Exception thrown when online sale intent profile wire validation, context scoping,
 * schema constraint, or digest verification fails.
 */
class IntentProfileValidationError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Authoritative online sales context scoping:
 * - actorId: Authoritative staff actor ULID from validated Bearer session context.
 * - companyId: Authoritative company tenant ULID bound from URL route parameter.
 * - currencyMinorUnits: Server-qualified integer scale (0..6) for base currency calculations.
 */
data class OnlineSaleContext(
    val actorId: ServerUlid,
    val companyId: ServerUlid,
    val currencyMinorUnits: Int
) {
    init {
        require(currencyMinorUnits in 0..6) {
            "currencyMinorUnits must be between 0 and 6, got $currencyMinorUnits"
        }
    }

    companion object {
        private val ALLOWED_CONTEXT_KEYS = setOf("actor_id", "company_id", "currency_minor_units")

        fun fromStrictJson(obj: StrictJsonObject): OnlineSaleContext {
            val unexpected = obj.properties.keys - ALLOWED_CONTEXT_KEYS
            if (unexpected.isNotEmpty()) {
                throw IntentProfileValidationError("Unexpected keys in context: $unexpected")
            }
            val missing = ALLOWED_CONTEXT_KEYS - obj.properties.keys
            if (missing.isNotEmpty()) {
                throw IntentProfileValidationError("Missing required context keys: $missing")
            }

            val rawActor = obj.require("actor_id").asString()
            if (!ContractConstants.CROCKFORD_ULID_REGEX.matches(rawActor) || rawActor.contains("\n") || rawActor.contains("\r")) {
                throw IntentProfileValidationError("Invalid actor_id ULID in context: '$rawActor'")
            }

            val rawCompany = obj.require("company_id").asString()
            if (!ContractConstants.CROCKFORD_ULID_REGEX.matches(rawCompany) || rawCompany.contains("\n") || rawCompany.contains("\r")) {
                throw IntentProfileValidationError("Invalid company_id ULID in context: '$rawCompany'")
            }

            val minorUnitsElem = obj.require("currency_minor_units")
            if (minorUnitsElem is StrictJsonBoolean) {
                throw IntentProfileValidationError("currency_minor_units must be an integer, rejecting boolean")
            }
            if (minorUnitsElem !is StrictJsonNumber || !minorUnitsElem.isInt) {
                throw IntentProfileValidationError("currency_minor_units must be an integer: $minorUnitsElem")
            }
            val minorUnits = minorUnitsElem.asInt()
            if (minorUnits !in 0..6) {
                throw IntentProfileValidationError("currency_minor_units must be in 0..6, got $minorUnits")
            }

            return OnlineSaleContext(
                actorId = ServerUlid.parse(rawActor),
                companyId = ServerUlid.parse(rawCompany),
                currencyMinorUnits = minorUnits
            )
        }
    }
}

/**
 * Ordered line item within an online canonical sale intent profile.
 */
data class OnlineSaleIntentLine(
    val productId: ServerUlid,
    val productUnitId: ServerUlid,
    val conversionToBase: PositiveDecimalQuantity,
    val quantity: PositiveDecimalQuantity,
    val unitPrice: MoneyAmount,
    val taxRateId: ServerUlid?,
    val taxRate: TaxPercentage,
    val taxInclusive: Boolean
) : CanonicalSerializable {

    init {
        if (taxRateId == null) {
            if (taxRate.toPlainString() != "0.000000" || taxInclusive) {
                throw IntentProfileValidationError(
                    "ExplicitNoTax requires taxRate '0.000000' and taxInclusive false when taxRateId is null"
                )
            }
        }
    }

    override fun toCanonicalStructure(): Map<String, Any?> = mapOf(
        "product_id" to productId.value,
        "product_unit_id" to productUnitId.value,
        "conversion_to_base" to conversionToBase.toPlainString(),
        "quantity" to quantity.toPlainString(),
        "unit_price" to unitPrice.toPlainString(),
        "tax_rate_id" to taxRateId?.value,
        "tax_rate" to taxRate.toPlainString(),
        "tax_inclusive" to taxInclusive
    )
}

/**
 * Payment intent within an online canonical sale intent profile.
 */
data class OnlineSaleIntentPayment(
    val settlementMode: String,
    val paymentMethod: String?,
    val cashAccountId: ServerUlid?,
    val amount: MoneyAmount
) : CanonicalSerializable {

    init {
        when (settlementMode) {
            "unpaid" -> {
                if (paymentMethod != null || cashAccountId != null) {
                    throw IntentProfileValidationError(
                        "An unpaid sale cannot carry payment method or cash account instructions"
                    )
                }
                if (amount.value.compareTo(BigDecimal.ZERO) != 0) {
                    throw IntentProfileValidationError(
                        "Unpaid settlement mode requires zero amount, got ${amount.lexeme}"
                    )
                }
            }
            "full", "partial" -> {
                if (paymentMethod != "cash") {
                    throw IntentProfileValidationError(
                        "Missing or invalid payment_method for $settlementMode settlement mode: '$paymentMethod'"
                    )
                }
                if (cashAccountId == null) {
                    throw IntentProfileValidationError(
                        "Missing required cash_account_id for $settlementMode settlement mode"
                    )
                }
                if (amount.value <= BigDecimal.ZERO) {
                    throw IntentProfileValidationError(
                        "Payment amount for $settlementMode mode must be strictly positive, got ${amount.lexeme}"
                    )
                }
            }
            else -> {
                throw IntentProfileValidationError("Unsupported settlement_mode: '$settlementMode'")
            }
        }
    }

    override fun toCanonicalStructure(): Map<String, Any?> = mapOf(
        "settlement_mode" to settlementMode,
        "payment_method" to paymentMethod,
        "cash_account_id" to cashAccountId?.value,
        "amount" to amount.toPlainString()
    )
}

/**
 * Normative canonical economic intent profile for online sale preview and commit binding.
 *
 * Implements RFC 8785 restricted JCS UTF-16 code unit ordering, strict string decimal lexemes,
 * zero floats, and defensive collection copies.
 */
class OnlineSaleIntentProfile(
    val actorId: ServerUlid,
    val clientEventId: ClientUuid,
    val companyId: ServerUlid,
    val currency: String,
    val currencyMinorUnits: Int,
    val customerId: ServerUlid,
    val deviceId: ClientUuid,
    lines: List<OnlineSaleIntentLine>,
    val payment: OnlineSaleIntentPayment,
    val warehouseId: ServerUlid
) : CanonicalSerializable {

    val lines: List<OnlineSaleIntentLine> = Collections.unmodifiableList(ArrayList(lines))

    init {
        if (!ContractConstants.CURRENCY_CODE_REGEX.matches(currency)) {
            throw IntentProfileValidationError("Invalid currency code: '$currency'")
        }
        if (currencyMinorUnits !in 0..6) {
            throw IntentProfileValidationError("currencyMinorUnits must be in 0..6, got $currencyMinorUnits")
        }
        if (this.lines.isEmpty()) {
            throw IntentProfileValidationError("Online intent profile requires at least one line item")
        }
        if (this.lines.size > ContractConstants.MAX_LINE_ITEMS) {
            throw IntentProfileValidationError("Online intent profile exceeds line limit")
        }
        if (PaymentDecimalConsistency.parseAndValidateMonetaryAmount(payment.amount.toPlainString(), currencyMinorUnits) == null) {
            throw IntentProfileValidationError("Payment amount exceeds currency precision")
        }
    }

    fun copy(
        actorId: ServerUlid = this.actorId,
        clientEventId: ClientUuid = this.clientEventId,
        companyId: ServerUlid = this.companyId,
        currency: String = this.currency,
        currencyMinorUnits: Int = this.currencyMinorUnits,
        customerId: ServerUlid = this.customerId,
        deviceId: ClientUuid = this.deviceId,
        lines: List<OnlineSaleIntentLine> = this.lines,
        payment: OnlineSaleIntentPayment = this.payment,
        warehouseId: ServerUlid = this.warehouseId
    ): OnlineSaleIntentProfile = OnlineSaleIntentProfile(
        actorId = actorId,
        clientEventId = clientEventId,
        companyId = companyId,
        currency = currency,
        currencyMinorUnits = currencyMinorUnits,
        customerId = customerId,
        deviceId = deviceId,
        lines = lines,
        payment = payment,
        warehouseId = warehouseId
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OnlineSaleIntentProfile) return false
        return actorId == other.actorId &&
                clientEventId == other.clientEventId &&
                companyId == other.companyId &&
                currency == other.currency &&
                currencyMinorUnits == other.currencyMinorUnits &&
                customerId == other.customerId &&
                deviceId == other.deviceId &&
                lines == other.lines &&
                payment == other.payment &&
                warehouseId == other.warehouseId
    }

    override fun hashCode(): Int {
        var result = actorId.hashCode()
        result = 31 * result + clientEventId.hashCode()
        result = 31 * result + companyId.hashCode()
        result = 31 * result + currency.hashCode()
        result = 31 * result + currencyMinorUnits
        result = 31 * result + customerId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + lines.hashCode()
        result = 31 * result + payment.hashCode()
        result = 31 * result + warehouseId.hashCode()
        return result
    }

    override fun toString(): String {
        return "OnlineSaleIntentProfile(actorId=$actorId, clientEventId=$clientEventId, companyId=$companyId, currency='$currency', lines=$lines, payment=$payment)"
    }

    override fun toCanonicalStructure(): Map<String, Any?> = mapOf(
        "actor_id" to actorId.value,
        "client_event_id" to clientEventId.value,
        "company_id" to companyId.value,
        "currency" to currency,
        "currency_minor_units" to currencyMinorUnits,
        "customer_id" to customerId.value,
        "device_id" to deviceId.value,
        "lines" to lines.map { it.toCanonicalStructure() },
        "payment" to payment.toCanonicalStructure(),
        "warehouse_id" to warehouseId.value
    )

    fun computePayloadDigest(): PayloadDigest =
        CanonicalJsonSerializer.computeDigest(this)

    companion object {
        private val ALLOWED_ROOT_KEYS = setOf(
            "client_event_id", "device_id", "customer_id",
            "warehouse_id", "currency", "lines", "payment"
        )

        private val ALLOWED_LINE_KEYS = setOf(
            "product_id", "product_unit_id", "conversion_to_base",
            "quantity", "unit_price", "tax_rate_id", "tax_rate", "tax_inclusive"
        )

        private val ALLOWED_PAYMENT_KEYS = setOf(
            "settlement_mode", "payment_method", "cash_account_id", "amount"
        )

        /**
         * Constructs the normative online canonical intent profile by combining:
         * - Authoritative company context from route / tenant membership
         * - Authoritative staff actor context from authenticated session
         * - Server-qualified currency minor units context (0..6)
         * - Client request parameters: device_id, client_event_id, customer_id, warehouse_id, currency,
         *   ordered line items with exact decimal lexemes, and payment intent.
         *
         * Wire validation is performed BEFORE any projection/coercion:
         * 1. Rejects unknown root fields.
         * 2. Rejects unknown line item fields; strictly requires 'tax_rate_id' (never defaults to implicit NoTax).
         * 3. Rejects unknown payment fields.
         * 4. Normalizes nullable payment fields explicitly only when permitted by contract:
         *    omitted/null payment_method and cash_account_id canonically map to null ONLY for unpaid settlement mode.
         */
        fun fromContextAndRequest(
            context: OnlineSaleContext,
            rawRequest: StrictJsonObject
        ): OnlineSaleIntentProfile {
            // 1. Wire validation of raw request root keys BEFORE projection
            val unexpectedRoot = rawRequest.properties.keys - ALLOWED_ROOT_KEYS
            if (unexpectedRoot.isNotEmpty()) {
                throw IntentProfileValidationError("Unknown root fields in sale preview request: $unexpectedRoot")
            }
            for (reqKey in ALLOWED_ROOT_KEYS) {
                if (reqKey !in rawRequest.properties) {
                    throw IntentProfileValidationError("Missing required root field in request: '$reqKey'")
                }
            }

            val clientEventId = ClientUuid.parse(rawRequest.require("client_event_id").asString())
            val deviceId = ClientUuid.parse(rawRequest.require("device_id").asString())
            val customerId = ServerUlid.parse(rawRequest.require("customer_id").asString())
            val warehouseId = ServerUlid.parse(rawRequest.require("warehouse_id").asString())
            val currency = rawRequest.require("currency").asString()

            // 2. Wire validation of line items
            val linesJson = rawRequest.require("lines").asArray()
            if (linesJson.size == 0) {
                throw IntentProfileValidationError("Online intent profile requires at least one line item")
            }

            val profileLines = ArrayList<OnlineSaleIntentLine>(linesJson.size)
            for (i in 0 until linesJson.size) {
                val lineObj = linesJson[i].asObject()
                val unexpectedLine = lineObj.properties.keys - ALLOWED_LINE_KEYS
                if (unexpectedLine.isNotEmpty()) {
                    throw IntentProfileValidationError("Unknown line field at index $i: $unexpectedLine")
                }
                if ("tax_rate_id" !in lineObj.properties) {
                    throw IntentProfileValidationError(
                        "Missing required 'tax_rate_id' on line $i (ExplicitNoTax requires explicit null, never omitted key)"
                    )
                }

                val rawTaxRateId = lineObj.require("tax_rate_id")
                val taxRateId = if (rawTaxRateId is StrictJsonNull) {
                    null
                } else {
                    val rawStr = rawTaxRateId.asString()
                    if (!ContractConstants.CROCKFORD_ULID_REGEX.matches(rawStr)) {
                        throw IntentProfileValidationError("Invalid tax_rate_id ULID on line $i: '$rawStr'")
                    }
                    ServerUlid.parse(rawStr)
                }

                val rawTaxRate = lineObj.require("tax_rate").asString()
                val taxRate = TaxPercentage.fromString(rawTaxRate)

                val taxInclusiveElem = lineObj.require("tax_inclusive")
                val taxInclusive = (taxInclusiveElem as? StrictJsonBoolean)?.value
                    ?: throw IntentProfileValidationError("tax_inclusive on line $i must be boolean, got $taxInclusiveElem")

                if (taxRateId == null) {
                    if (rawTaxRate != "0.000000" || taxInclusive) {
                        throw IntentProfileValidationError(
                            "Line $i: ExplicitNoTax requires taxRate '0.000000' and taxInclusive false when taxRateId is null"
                        )
                    }
                }

                profileLines.add(
                    OnlineSaleIntentLine(
                        productId = ServerUlid.parse(lineObj.require("product_id").asString()),
                        productUnitId = ServerUlid.parse(lineObj.require("product_unit_id").asString()),
                        conversionToBase = PositiveDecimalQuantity.fromPositiveString(lineObj.require("conversion_to_base").asString()),
                        quantity = PositiveDecimalQuantity.fromPositiveString(lineObj.require("quantity").asString()),
                        unitPrice = MoneyAmount.fromNonnegativeString(lineObj.require("unit_price").asString()),
                        taxRateId = taxRateId,
                        taxRate = taxRate,
                        taxInclusive = taxInclusive
                    )
                )
            }

            // 3. Wire validation of payment
            val paymentObj = rawRequest.require("payment").asObject()
            val unexpectedPayment = paymentObj.properties.keys - ALLOWED_PAYMENT_KEYS
            if (unexpectedPayment.isNotEmpty()) {
                throw IntentProfileValidationError("Unknown payment fields: $unexpectedPayment")
            }
            if ("settlement_mode" !in paymentObj.properties || "amount" !in paymentObj.properties) {
                throw IntentProfileValidationError("Payment must specify 'settlement_mode' and 'amount'")
            }

            val mode = paymentObj.require("settlement_mode").asString()
            val rawAmount = paymentObj.require("amount").asString()
            val amount = MoneyAmount.fromNonnegativeString(rawAmount)

            val method: String?
            val cashAccount: ServerUlid?

            when (mode) {
                "unpaid" -> {
                    val rawMethod = paymentObj["payment_method"]
                    val rawAccount = paymentObj["cash_account_id"]
                    val nonNullMethod = rawMethod != null && rawMethod !is StrictJsonNull
                    val nonNullAccount = rawAccount != null && rawAccount !is StrictJsonNull
                    if (nonNullMethod || nonNullAccount) {
                        throw IntentProfileValidationError(
                            "An unpaid sale cannot carry payment method or cash account instructions"
                        )
                    }
                    if (amount.value.compareTo(BigDecimal.ZERO) != 0) {
                        throw IntentProfileValidationError("Unpaid mode requires zero amount, got '$rawAmount'")
                    }
                    method = null
                    cashAccount = null
                }
                "full", "partial" -> {
                    val rawMethod = paymentObj["payment_method"]
                    if (rawMethod == null || rawMethod is StrictJsonNull) {
                        throw IntentProfileValidationError(
                            "Missing required payment_method for $mode settlement mode (never defaults)"
                        )
                    }
                    val methodStr = rawMethod.asString()
                    if (methodStr != "cash") {
                        throw IntentProfileValidationError(
                            "Unsupported payment_method '$methodStr' for $mode settlement mode"
                        )
                    }
                    method = methodStr

                    val rawAccount = paymentObj["cash_account_id"]
                    if (rawAccount == null || rawAccount is StrictJsonNull) {
                        throw IntentProfileValidationError(
                            "Missing required cash_account_id for $mode settlement mode (never defaults)"
                        )
                    }
                    val acctStr = rawAccount.asString()
                    if (!ContractConstants.CROCKFORD_ULID_REGEX.matches(acctStr)) {
                        throw IntentProfileValidationError("Invalid cash_account_id ULID: '$acctStr'")
                    }
                    cashAccount = ServerUlid.parse(acctStr)

                    if (amount.value <= BigDecimal.ZERO) {
                        throw IntentProfileValidationError("Payment amount for $mode mode must be strictly positive, got '$rawAmount'")
                    }
                }
                else -> {
                    throw IntentProfileValidationError("Unsupported settlement_mode: '$mode'")
                }
            }

            val profilePayment = OnlineSaleIntentPayment(
                settlementMode = mode,
                paymentMethod = method,
                cashAccountId = cashAccount,
                amount = amount
            )

            return OnlineSaleIntentProfile(
                actorId = context.actorId,
                clientEventId = clientEventId,
                companyId = context.companyId,
                currency = currency,
                currencyMinorUnits = context.currencyMinorUnits,
                customerId = customerId,
                deviceId = deviceId,
                lines = profileLines,
                payment = profilePayment,
                warehouseId = warehouseId
            )
        }

        /**
         * Normative verification helper for online sale canonical intent profiles:
         * 1. Confirms no IEEE-754 floats are present anywhere.
         * 2. Serializes profile to RFC 8785 restricted JCS bytes.
         * 3. Computes SHA-256 digest and asserts equality against expectedDigest.
         */
        fun verifyOnlineSaleIntentProfile(
            profile: OnlineSaleIntentProfile,
            expectedDigest: PayloadDigest
        ): Boolean {
            val computed = profile.computePayloadDigest()
            if (computed != expectedDigest) {
                throw IntentProfileValidationError(
                    "Intent profile digest mismatch! Computed: '${computed.value}', expected: '${expectedDigest.value}'"
                )
            }
            return true
        }

        fun parseAndConstruct(contextJson: String, requestJson: String): OnlineSaleIntentProfile {
            val ctxObj = StrictJsonParser.parse(contextJson).asObject()
            val reqObj = StrictJsonParser.parse(requestJson).asObject()
            val ctx = OnlineSaleContext.fromStrictJson(ctxObj)
            return fromContextAndRequest(ctx, reqObj)
        }
    }
}
