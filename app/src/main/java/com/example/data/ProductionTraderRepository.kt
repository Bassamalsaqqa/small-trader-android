package com.example.data

import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.contract.v2.OfflineSaleEventV2
import com.example.data.mobile.contract.v2.OfflineSaleLease
import com.example.data.mobile.contract.v2.OfflineSaleLineV2
import com.example.data.mobile.contract.v2.OfflineSalePaymentV2
import com.example.data.mobile.contract.v2.OnlineSaleContext
import com.example.data.mobile.offline.InMemoryOfflineLeaseStore
import com.example.data.mobile.offline.OfflineLeaseStore
import com.example.data.mobile.sync.SyncBatchResult
import com.example.data.mobile.contract.v2.OnlineSaleIntentLine
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonBoolean
import com.example.data.mobile.contract.StrictJsonElement
import com.example.data.mobile.contract.StrictJsonNumber
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonNull
import com.example.data.mobile.contract.StrictJsonString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.example.data.mobile.contract.toNullableString
import com.example.data.mobile.contract.toNullableBigDecimal
import com.example.data.mobile.contract.v2.OnlineSaleIntentPayment
import com.example.data.mobile.contract.v2.OnlineSaleIntentProfile
import com.example.data.mobile.contract.v2.PaymentDecimalConsistency
import com.example.data.mobile.outbox.CaptureResult
import com.example.data.mobile.outbox.CommandStatus
import com.example.data.mobile.outbox.InMemoryMerchantCommandStore
import com.example.data.mobile.outbox.MerchantCommandRecord
import com.example.data.mobile.outbox.MerchantCommandStore
import com.example.data.mobile.outbox.OutboxRepository
import com.example.data.mobile.merchantread.CustomerStatementResponse
import com.example.data.mobile.merchantread.HomeResponse
import com.example.data.mobile.merchantread.MerchantReadAdapter
import com.example.data.mobile.merchantread.MerchantReadClient
import com.example.data.mobile.merchantread.MerchantReadResult
import com.example.data.mobile.merchantread.MerchantReadTokenProvider
import com.example.data.mobile.merchantread.ProductHistoryResponse
import com.example.data.mobile.merchantread.SalesInvoiceDetailResponse
import com.example.data.mobile.merchantread.SalesInvoiceListItem
import com.example.data.mobile.merchantread.SalesInvoicesQuery
import com.example.data.mobile.merchantread.ShiftCloseResponse
import com.example.data.mobile.merchantread.ShiftSummaryReportResponse
import com.example.data.mobile.merchantread.TreasuryReportResponse
import com.example.data.mobile.readclient.ApprovedCashAccount
import com.example.data.mobile.readclient.BootstrapResponse
import com.example.data.mobile.readclient.BootstrapTax
import com.example.data.mobile.readclient.BootstrapUnit
import com.example.data.mobile.readclient.CustomerDirectoryItem
import com.example.data.mobile.readclient.DefaultWarehouse
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.session.StaffAuthState
import com.example.data.mobile.session.StaffSessionCoordinator
import com.example.data.mobile.sync.HttpSaleSyncGateway
import com.example.data.mobile.sync.SaleSyncCoordinator
import com.example.data.mobile.sync.SaleSyncCoordinatorImpl
import com.example.data.mobile.sync.SaleSyncGateway
import com.example.data.mobile.sync.SyncStatusOutcome
import com.example.data.mobile.sync.SyncSubmitOutcome
import com.example.model.AppLanguage
import com.example.model.BasketItem
import com.example.model.BusinessInfo
import com.example.model.Customer
import com.example.model.CustomerTransaction
import com.example.model.PaymentAllocationInput
import com.example.model.PaymentAllocationResult
import com.example.model.SettlementCurrencyTotal
import com.example.model.SettlementTarget
import com.example.model.SettlementTargetsResponse
import com.example.model.ExpenseCategory
import com.example.model.ExpenseEntry
import com.example.model.InvoiceRecord
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.PurchaseBill
import com.example.model.PurchaseLineItem
import com.example.model.ServerExpenseCategory
import com.example.model.StockMovement
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.model.WesternFormatters
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.data.mobile.outbox.OutboxStatus
import com.example.data.mobile.outbox.OutboxRecord
import com.example.data.mobile.contract.PayloadDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class CurrentAuthScope(
    val companyId: ServerUlid,
    val actorId: ServerUlid,
    val deviceId: ClientUuid,
    val token: String,
    val generation: Long
) {
    override fun toString(): String = "CurrentAuthScope(companyId=$companyId, actorId=$actorId, deviceId=$deviceId, generation=$generation, token=[REDACTED])"
}

/**
 * Authoritative, non-stubbed production implementation of [TraderRepository].
 *
 * Grounded in:
 * 1. Verified native V2 online-cash sale capture with pre-network encrypted Room outbox,
 *    canonical JCS serialization, and authoritative server status reconciliation.
 * 2. Real server bootstrap metadata (companyId, defaultWarehouse, approvedCashAccount,
 *    walkInCustomerId, staff actor, units, taxes).
 * 3. Exact BigDecimal economics end-to-end without Double conversion or Int truncation.
 * 4. Reactive StateFlow-backed collections without exception swallowing.
 */
class ProductionTraderRepository(
    val companyId: ServerUlid,
    val companyName: String,
    val actorId: ServerUlid,
    val actorName: String,
    val role: UserRole,
    private val permissions: Set<String> = emptySet(),
    val deviceId: ClientUuid,
    val baseCurrency: String,
    val currencyMinorUnits: Int,
    val defaultWarehouse: DefaultWarehouse,
    val approvedCashAccount: ApprovedCashAccount,
    val walkInCustomerId: ServerUlid,
    val bootstrapUnits: List<BootstrapUnit>,
    val bootstrapTaxes: List<BootstrapTax>,
    private val apiClient: MobileMerchantApiClient,
    private val outboxRepository: OutboxRepository? = null,
    private val saleSyncGateway: SaleSyncGateway? = null,
    private val syncCoordinator: SaleSyncCoordinator? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val authScopeProvider: (() -> CurrentAuthScope?)? = null,
    private val onClose: (() -> Unit)? = null,
    initialBusinessInfo: BusinessInfo? = null,
    val commandStore: MerchantCommandStore,
    private val merchantReadClient: MerchantReadClient? = null,
    private val autoRefresh: Boolean = true,
    val offlineLeaseStore: OfflineLeaseStore,
    private val offlineScopeValidator: (() -> Unit)? = null
) : TraderRepository {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val retainedGeneration = authScopeProvider?.invoke()?.generation

    @Volatile var isOfflineSalesCapabilityEnabled: Boolean = false
    @Volatile var isNetworkOffline: Boolean = false
    var clock: () -> Long = { System.currentTimeMillis() }

    private val _activeOfflineLeaseFlow = kotlinx.coroutines.flow.MutableStateFlow<OfflineSaleLease?>(null)
    val activeOfflineLeaseFlow: kotlinx.coroutines.flow.StateFlow<OfflineSaleLease?> = _activeOfflineLeaseFlow.asStateFlow()

    init {
        require(currencyMinorUnits in 0..6) { "Missing or invalid business currency precision" }
        require(ContractConstants.CURRENCY_CODE_REGEX.matches(baseCurrency)) { "Missing or invalid business currency" }
        scope.launch { _activeOfflineLeaseFlow.value = offlineLeaseStore.getLease(companyId, actorId, deviceId) }
    }

    internal fun verifyOfflineBootstrapSource(lease: OfflineSaleLease) {
        requireActiveScope()
        require(isOfflineSalesCapabilityEnabled && lease.companyId == companyId && lease.actorId == actorId && lease.deviceId == deviceId)
        require(getActiveOfflineSaleLease() == lease)
    }

    private fun requireOfflineScope() {
        if (offlineScopeValidator != null) offlineScopeValidator.invoke() else requireActiveScope()
    }

    override fun isScopeActive(): Boolean {
        val provider = authScopeProvider ?: return true
        val current = provider.invoke() ?: return false
        return current.companyId == companyId &&
            current.actorId == actorId &&
            current.deviceId == deviceId &&
            current.generation == retainedGeneration
    }

    private fun requireActiveScope(): CurrentAuthScope {
        val current = authScopeProvider?.invoke() ?: throw SecurityException("Authenticated scope is not active")
        if (current.companyId != companyId || current.actorId != actorId || current.deviceId != deviceId || current.generation != retainedGeneration) {
            throw SecurityException("Authenticated scope has changed")
        }
        return current
    }

    private suspend fun executeDurableCommand(
        commandType: String,
        commandId: String,
        idempotencyKey: String,
        canonicalPayloadBytes: ByteArray,
        isReceiptStatusChecked: Boolean = false,
        validateResponse: ((StrictJsonObject) -> Result<StrictJsonObject>)? = null,
        postAction: suspend (key: String) -> Result<StrictJsonObject>
    ): Result<StrictJsonObject> {
        val scopeBefore = requireActiveScope()
        val admissionKey = "${scopeBefore.companyId.value}:${scopeBefore.actorId.value}:${scopeBefore.deviceId.value}:$commandId"
        if (!inFlightCommands.add(admissionKey)) {
            return Result.failure(IllegalStateException("Command is already being submitted"))
        }
        val singleGroup = com.example.data.mobile.outbox.singleDraftFormGroup(commandType)
        val formAdmissionKey = singleGroup?.let {
            "${scopeBefore.companyId.value}:${scopeBefore.actorId.value}:${scopeBefore.deviceId.value}:$it"
        }
        if (formAdmissionKey != null && !inFlightFormTypes.add(formAdmissionKey)) {
            inFlightCommands.remove(admissionKey)
            return Result.failure(IllegalStateException("Resolve the existing $singleGroup operation before starting another"))
        }

        try {
            val payloadDigest = com.example.data.mobile.contract.PayloadDigest("sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(canonicalPayloadBytes).joinToString("") { "%02x".format(it) })

            val record = commandStore.recordPendingCommand(
                companyId = scopeBefore.companyId.value,
                commandId = commandId,
                actorId = scopeBefore.actorId.value,
                deviceId = scopeBefore.deviceId.value,
                commandType = commandType,
                idempotencyKey = idempotencyKey,
                payloadDigest = payloadDigest.value,
                canonicalPayloadBytes = canonicalPayloadBytes
            )

            if (record.status == CommandStatus.COMPLETED) {
                val activeScope = requireActiveScope()
                if (record.companyId != activeScope.companyId.value ||
                    record.actorId != activeScope.actorId.value ||
                    record.deviceId != activeScope.deviceId.value
                ) {
                    throw SecurityException("Cached command belongs to different authenticated scope")
                }
                val paymentForm = paymentFormType(commandType)
                val parsed = runCatching { readCachedCommandResponse(record) }
                val validation = parsed.fold(
                    onSuccess = { validateResponse?.invoke(it) ?: Result.success(it) },
                    onFailure = { Result.failure(it) }
                )
                val error = validation.exceptionOrNull() ?: return validation
                if (paymentForm != null) {
                    commandStore.reopenInvalidCompletedCommand(
                        paymentForm, record, error.message ?: "Invalid cached payment response"
                    )
                } else if (parsed.isFailure && isReceiptStatusChecked && record.responseSnapshot != null) {
                    return checkReceiptStatusOnServer(scopeBefore.companyId, idempotencyKey, commandId, validateResponse)
                        ?: validation
                }
                return validation
            }

            if (isReceiptStatusChecked && (record.status == CommandStatus.UNKNOWN_ACK || record.status == CommandStatus.SUBMITTED)) {
                val statusCheck = checkReceiptStatusOnServer(scopeBefore.companyId, idempotencyKey, commandId, validateResponse)
                if (statusCheck != null) {
                    return statusCheck
                }
            }

            if (record.status != CommandStatus.SUBMITTED) {
                if (!commandStore.transitionStatus(scopeBefore.companyId.value, commandId, record.status, CommandStatus.SUBMITTED)) {
                    return Result.failure(IllegalStateException("Command is already being submitted"))
                }
            } else {
                if (!commandStore.claimSubmittedCommand(scopeBefore.companyId.value, commandId, record.updatedAt)) {
                    return Result.failure(IllegalStateException("Command is already being submitted"))
                }
            }

            val postResult = try {
                postAction(record.idempotencyKey)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                runCatching {
                    commandStore.markUnknownAck(
                        scopeBefore.companyId.value,
                        commandId,
                        ce.message ?: "Command submission interrupted"
                    )
                }
                throw ce
            }

            val scopeAfter = requireActiveScope()
            if (scopeAfter != scopeBefore) {
                throw SecurityException("Authenticated scope changed during command execution")
            }

            if (postResult.isSuccess) {
                val snapshot = postResult.getOrThrow()
                if (validateResponse != null) {
                    val valRes = validateResponse(snapshot)
                    if (valRes.isFailure) {
                        val valErr = valRes.exceptionOrNull() ?: IllegalStateException("Authoritative response validation failed")
                        val valErrMsg = valErr.message ?: "Authoritative response validation failed"
                        commandStore.markUnknownAck(scopeAfter.companyId.value, commandId, valErrMsg)
                        return Result.failure(valErr)
                    }
                }
                val snapBytes = CanonicalJsonSerializer.serialize(snapshot).toByteArray(StandardCharsets.UTF_8)
                commandStore.markCompleted(scopeAfter.companyId.value, commandId, snapBytes)
                return Result.success(snapshot)
            }

            val err = postResult.exceptionOrNull()
            val errMsg = err?.message ?: "Command execution failed"
            val httpErr = err as? com.example.data.mobile.readclient.http.HttpTransportException
            // A rejection of this retry cannot disprove an earlier committed attempt.
            val previouslySubmitted = record.status == CommandStatus.UNKNOWN_ACK || record.status == CommandStatus.SUBMITTED
            val isDefinitiveFailure = !previouslySubmitted && isValidatedAuthoritativeRejection(
                err = httpErr,
                expectedCompanyId = scopeAfter.companyId.value,
                expectedIdempotencyKey = idempotencyKey
            )

            if (!isDefinitiveFailure && isReceiptStatusChecked) {
                val statusAfterError = checkReceiptStatusOnServer(scopeAfter.companyId, idempotencyKey, commandId, validateResponse)
                if (statusAfterError != null) {
                    if (statusAfterError.isFailure) {
                        val lookupErr = statusAfterError.exceptionOrNull()?.message ?: errMsg
                        commandStore.markUnknownAck(scopeAfter.companyId.value, commandId, lookupErr)
                    }
                    return statusAfterError
                }
            }

            if (isDefinitiveFailure) {
                commandStore.markFailed(scopeAfter.companyId.value, commandId, errMsg)
            } else {
                commandStore.markUnknownAck(scopeAfter.companyId.value, commandId, errMsg)
            }

            return Result.failure(err ?: RuntimeException(errMsg))
        } finally {
            if (formAdmissionKey != null) {
                inFlightFormTypes.remove(formAdmissionKey)
            }
            inFlightCommands.remove(admissionKey)
        }
    }

    private fun isValidatedAuthoritativeRejection(
        err: com.example.data.mobile.readclient.http.HttpTransportException?,
        expectedCompanyId: String,
        expectedIdempotencyKey: String
    ): Boolean {
        if (err == null) return false
        if (err.category != com.example.data.mobile.readclient.http.HttpTransportException.CATEGORY_SERVER_ERROR) {
            return false
        }
        val status = err.httpStatusCode ?: return false
        val code = err.authoritativeErrorCode ?: return false
        if (err.companyId != expectedCompanyId || err.idempotencyKey != expectedIdempotencyKey) {
            return false
        }
        val allowedCodes = when (status) {
            400 -> setOf("BAD_REQUEST", "INVALID_JSON", "MISSING_IDEMPOTENCY_KEY")
            403 -> setOf("FORBIDDEN", "FORBIDDEN_TENANT_MISMATCH", "MOBILE_MERCHANT_DISABLED")
            404 -> setOf("NOT_FOUND")
            422 -> setOf("VALIDATION_FAILED", "INVALID_JSON", "MISSING_IDEMPOTENCY_KEY")
            else -> emptySet()
        }
        return code in allowedCodes
    }

    private fun checkReceiptStatusOnServer(
        companyId: ServerUlid,
        idempotencyKey: String,
        commandId: String,
        validateResponse: ((StrictJsonObject) -> Result<StrictJsonObject>)? = null
    ): Result<StrictJsonObject>? {
        val before = requireActiveScope()
        val status = apiClient.getReceiptStatus(companyId, idempotencyKey)
        if (requireActiveScope() != before) throw SecurityException("Authenticated scope changed during status lookup")
        return when (status) {
            is MobileMerchantApiClient.ReceiptStatusResult.Found -> {
                val respCompanyId = status.snapshot.properties["company_id"]?.asString()
                if (respCompanyId != null && respCompanyId != companyId.value) {
                    val secErr = SecurityException("Receipt status company mismatch: expected ${companyId.value}, got $respCompanyId")
                    commandStore.markUnknownAck(companyId.value, commandId, secErr.message ?: "Receipt status company mismatch")
                    return Result.failure(secErr)
                }
                val respIdempotencyKey = status.snapshot.properties["idempotency_key"]?.asString()
                if (respIdempotencyKey != null && respIdempotencyKey != idempotencyKey) {
                    val keyErr = SecurityException("Receipt status idempotency key mismatch: expected $idempotencyKey, got $respIdempotencyKey")
                    commandStore.markUnknownAck(companyId.value, commandId, keyErr.message ?: "Receipt status key mismatch")
                    return Result.failure(keyErr)
                }
                val snapshot = status.snapshot.properties["response_snapshot"]?.asObject()
                    ?: status.snapshot.properties["result"]?.asObject()
                    ?: return Result.failure(IllegalStateException("Receipt status omitted its authoritative result"))
                if (validateResponse != null) {
                    val valRes = validateResponse(snapshot)
                    if (valRes.isFailure) {
                        val valErr = valRes.exceptionOrNull() ?: IllegalStateException("Authoritative response validation failed")
                        val valErrMsg = valErr.message ?: "Authoritative response validation failed"
                        commandStore.markUnknownAck(companyId.value, commandId, valErrMsg)
                        return Result.failure(valErr)
                    }
                }
                val snapBytes = CanonicalJsonSerializer.serialize(snapshot).toByteArray(StandardCharsets.UTF_8)
                commandStore.markCompleted(companyId.value, commandId, snapBytes)
                Result.success(snapshot)
            }
            is MobileMerchantApiClient.ReceiptStatusResult.NotFound -> {
                null
            }
            is MobileMerchantApiClient.ReceiptStatusResult.Error -> {
                Result.failure(status.exception)
            }
        }
    }

    internal fun parseAuthoritativePurchaseBill(obj: StrictJsonObject): PurchaseBill {
        val publicId = obj.properties["id"]?.asString()
            ?: throw IllegalArgumentException("Authoritative purchase bill ID is required")
        if (!ServerUlid.isValid(publicId)) {
            throw IllegalArgumentException("Authoritative purchase bill ID must be a valid ULID: $publicId")
        }
        val billNum = obj.properties["bill_number"]?.asString()
            ?: obj.properties["purchase_number"]?.asString()
            ?: throw IllegalArgumentException("Authoritative bill number is required")
        if (billNum.isBlank()) {
            throw IllegalArgumentException("Authoritative bill number cannot be blank")
        }
        val supplierId = obj.properties["supplier_id"]?.asString()
            ?: throw IllegalArgumentException("Authoritative supplier ID is required")
        if (!ServerUlid.isValid(supplierId)) {
            throw IllegalArgumentException("Authoritative supplier ID must be a valid ULID: $supplierId")
        }
        val date = obj.properties["date"]?.asString()
            ?: obj.properties["purchase_date"]?.asString()
            ?: obj.properties["posted_at"]?.asString()
            ?: obj.properties["created_at"]?.asString()
            ?: throw IllegalArgumentException("Authoritative purchase date is required")
        val currency = obj.properties["currency"]?.asString()
            ?: obj.properties["currency_code"]?.asString()
            ?: "ILS"
        val currencyMinorUnits = (obj.properties["currency_minor_units"] as? StrictJsonNumber)?.rawString?.toIntOrNull()
            ?: (obj.properties["currency_minor"] as? StrictJsonNumber)?.rawString?.toIntOrNull()
            ?: (obj.properties["minor_units"] as? StrictJsonNumber)?.rawString?.toIntOrNull()
            ?: (obj.properties["currency_minor_units"]?.asString()?.toIntOrNull())
            ?: (obj.properties["currency_minor"]?.asString()?.toIntOrNull())
            ?: (obj.properties["minor_units"]?.asString()?.toIntOrNull())
            ?: (if (currency.equals("JOD", ignoreCase = true)) 3 else 2)

        val totalExact = obj.properties["total_exact"]?.asString()?.let { BigDecimal(it) }
            ?: obj.properties["total"]?.asString()?.let { BigDecimal(it) }
            ?: obj.properties["total_amount"]?.asString()?.let { BigDecimal(it) }
            ?: obj.properties["total_ils"]?.asString()?.let { BigDecimal(it) }
            ?: throw IllegalArgumentException("Authoritative purchase total is required")
        val paidExact = obj.properties["paid_exact"]?.asString()?.let { BigDecimal(it) }
            ?: obj.properties["paid"]?.asString()?.let { BigDecimal(it) }
            ?: obj.properties["paid_amount"]?.asString()?.let { BigDecimal(it) }
            ?: obj.properties["paid_ils"]?.asString()?.let { BigDecimal(it) }
            ?: BigDecimal.ZERO
        val isReceived = (obj.properties["is_received"] as? StrictJsonBoolean)?.value
            ?: (obj.properties["receiving_status"]?.asString() == "received")
        val itemsSummary = obj.properties["items_summary"]?.asString() ?: ""

        val linesList = mutableListOf<PurchaseLineItem>()
        val linesRaw = obj.properties["lines"]
        if (linesRaw is StrictJsonArray) {
            for (elem in linesRaw.elements) {
                if (elem is StrictJsonObject) {
                    val lineId = elem.properties["id"]?.asString()
                        ?: elem.properties["purchase_line_id"]?.asString()
                        ?: throw IllegalArgumentException("Authoritative purchase line ULID is required")
                    if (!ServerUlid.isValid(lineId)) {
                        throw IllegalArgumentException("Authoritative purchase line ID must be a valid ULID: $lineId")
                    }
                    val prodId = elem.properties["product_id"]?.asString()
                        ?: throw IllegalArgumentException("Authoritative product ULID is required")
                    if (!ServerUlid.isValid(prodId)) {
                        throw IllegalArgumentException("Authoritative product ID must be a valid ULID: $prodId")
                    }
                    val prodName = elem.properties["product_name"]?.asString()
                        ?: elem.properties["product_name_ar"]?.asString()
                        ?: elem.properties["product_name_en"]?.asString()
                        ?: ""
                    val orderedQty = elem.properties["ordered_quantity"]?.asString()?.let { BigDecimal(it) }
                        ?: elem.properties["quantity"]?.asString()?.let { BigDecimal(it) }
                        ?: throw IllegalArgumentException("Authoritative line quantity is required")
                    val prevRecQty = elem.properties["previously_received_quantity"]?.asString()?.let { BigDecimal(it) }
                        ?: elem.properties["received_quantity"]?.asString()?.let { BigDecimal(it) }
                        ?: BigDecimal.ZERO
                    val unitCostExact = elem.properties["unit_cost_exact"]?.asString()?.let { BigDecimal(it) }
                        ?: elem.properties["unit_cost"]?.asString()?.let { BigDecimal(it) }
                        ?: elem.properties["unit_cost_ils"]?.asString()?.let { BigDecimal(it) }

                    linesList.add(
                        PurchaseLineItem(
                            id = lineId,
                            productId = prodId,
                            productNameAr = prodName,
                            productNameEn = prodName,
                            quantityExact = orderedQty,
                            receivedQuantityExact = prevRecQty,
                            unitCostExact = unitCostExact
                        )
                    )
                }
            }
        }

        return PurchaseBill(
            id = publicId,
            billNumber = billNum,
            supplierId = supplierId,
            date = date,
            totalExact = totalExact,
            paidExact = paidExact,
            itemsSummary = itemsSummary.ifBlank { linesList.joinToString(" • ") { "${it.quantity}x ${it.productNameAr}" } },
            isReceived = isReceived,
            lines = linesList,
            currency = currency,
            currencyMinorUnits = currencyMinorUnits
        )
    }


    private val currentSession = UserSession(
        id = actorId.value,
        name = actorName,
        username = actorName,
        role = role,
        branchNameAr = companyName,
        branchNameEn = companyName,
        isDemoSession = false,
        deviceSessionName = "POS Terminal (${deviceId.value.take(8)})",
        permissions = permissions
    )

    private val businessInfo = initialBusinessInfo?.copy(
        defaultCurrency = baseCurrency,
        defaultCurrencyMinorUnits = currencyMinorUnits
    ) ?: BusinessInfo(
        nameAr = companyName,
        nameEn = companyName,
        cityAr = "",
        cityEn = "",
        phone = "",
        taxNumber = "",
        defaultCurrency = baseCurrency,
        defaultCurrencyMinorUnits = currencyMinorUnits,
        defaultTaxPercent = 0.0,
        laravelSyncStatus = "متصل بالخادم (Laravel Mobile API v1/v2)"
    )

    override fun close() {
        scope.cancel()
        onClose?.invoke()
    }

    // Reactive StateFlows for all domain entities
    private val _productsFlow = MutableStateFlow<List<Product>>(emptyList())
    override val productsFlow: StateFlow<List<Product>> = _productsFlow.asStateFlow()

    private val _customersFlow = MutableStateFlow<List<Customer>>(emptyList())
    override val customersFlow: StateFlow<List<Customer>> = _customersFlow.asStateFlow()

    private val _suppliersFlow = MutableStateFlow<List<Supplier>>(emptyList())
    override val suppliersFlow: StateFlow<List<Supplier>> = _suppliersFlow.asStateFlow()

    private val _purchasesFlow = MutableStateFlow<List<PurchaseBill>>(emptyList())
    override val purchasesFlow: StateFlow<List<PurchaseBill>> = _purchasesFlow.asStateFlow()

    private val _expensesFlow = MutableStateFlow<List<ExpenseEntry>>(emptyList())
    override val expensesFlow: StateFlow<List<ExpenseEntry>> = _expensesFlow.asStateFlow()

    private val _invoicesFlow = MutableStateFlow<List<InvoiceRecord>>(emptyList())
    override val invoicesFlow: StateFlow<List<InvoiceRecord>> = _invoicesFlow.asStateFlow()

    private val _stockMovementsFlow = MutableStateFlow<List<StockMovement>>(emptyList())
    override val stockMovementsFlow: StateFlow<List<StockMovement>> = _stockMovementsFlow.asStateFlow()

    private val _customerTransactionsFlow = MutableStateFlow<List<CustomerTransaction>>(emptyList())
    override val customerTransactionsFlow: StateFlow<List<CustomerTransaction>> = _customerTransactionsFlow.asStateFlow()

    private val _supplierPaymentsFlow = MutableStateFlow<List<SupplierPayment>>(emptyList())
    override val supplierPaymentsFlow: StateFlow<List<SupplierPayment>> = _supplierPaymentsFlow.asStateFlow()

    private val _lastRefreshError = MutableStateFlow<String?>(null)
    private val customerRevisions = ConcurrentHashMap<String, AtomicLong>()
    private val customerCreditLimitRevisions = ConcurrentHashMap<String, AtomicLong>()
    private fun getCreditLimitRevision(id: String): Long = customerCreditLimitRevisions.computeIfAbsent(id) { AtomicLong(0L) }.get()
    private val customerLocks = ConcurrentHashMap<String, Any>()
    private fun getCustomerLock(customerId: String): Any = customerLocks.computeIfAbsent(customerId) { Any() }
    private val customerInFlightPayments = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private fun getCustomerRevision(customerId: String): Long = customerRevisions.computeIfAbsent(customerId) { AtomicLong(0L) }.get()
    private fun incrementCustomerRevision(customerId: String): Long = customerRevisions.computeIfAbsent(customerId) { AtomicLong(0L) }.incrementAndGet()
    val lastRefreshError: StateFlow<String?> = _lastRefreshError.asStateFlow()

    private val _expenseCategoriesFlow = MutableStateFlow<List<ServerExpenseCategory>>(emptyList())
    override fun getServerExpenseCategories(): List<ServerExpenseCategory> = _expenseCategoriesFlow.value

    private val _homeMetricsFlow = MutableStateFlow<HomeResponse?>(null)
    override val homeMetricsFlow: StateFlow<HomeResponse?> = _homeMetricsFlow.asStateFlow()

    private val _shiftSummaryFlow = MutableStateFlow<ShiftSummaryReportResponse?>(null)
    override val shiftSummaryFlow: StateFlow<ShiftSummaryReportResponse?> = _shiftSummaryFlow.asStateFlow()

    private val _shiftCloseSuccessFlow = MutableStateFlow<ShiftCloseResponse?>(null)
    override val shiftCloseSuccessFlow: StateFlow<ShiftCloseResponse?> = _shiftCloseSuccessFlow.asStateFlow()
    private val shiftCloseAdmissionLock = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _treasuryReportFlow = MutableStateFlow<TreasuryReportResponse?>(null)
    override val treasuryReportFlow: StateFlow<TreasuryReportResponse?> = _treasuryReportFlow.asStateFlow()

    private val _serverInvoicesFlow = MutableStateFlow<List<SalesInvoiceListItem>>(emptyList())
    override val serverInvoicesFlow: StateFlow<List<SalesInvoiceListItem>> = _serverInvoicesFlow.asStateFlow()

    private val _invoicesHasMoreFlow = MutableStateFlow<Boolean>(false)
    override val invoicesHasMoreFlow: StateFlow<Boolean> = _invoicesHasMoreFlow.asStateFlow()

    private val _invoicesNextCursorFlow = MutableStateFlow<String?>(null)
    override val invoicesNextCursorFlow: StateFlow<String?> = _invoicesNextCursorFlow.asStateFlow()

    private val _isInvoicesLoadingFlow = MutableStateFlow<Boolean>(false)
    override val isInvoicesLoadingFlow: StateFlow<Boolean> = _isInvoicesLoadingFlow.asStateFlow()

    private val _suppliersHasMoreFlow = MutableStateFlow<Boolean>(false)
    override val suppliersHasMoreFlow: StateFlow<Boolean> = _suppliersHasMoreFlow.asStateFlow()

    private val _productsHasMoreFlow = MutableStateFlow<Boolean>(false)
    override val productsHasMoreFlow: StateFlow<Boolean> = _productsHasMoreFlow.asStateFlow()

    private val _customersHasMoreFlow = MutableStateFlow<Boolean>(false)
    override val customersHasMoreFlow: StateFlow<Boolean> = _customersHasMoreFlow.asStateFlow()

    private val supplierRevisions = ConcurrentHashMap<String, AtomicLong>()
    private val supplierLocks = ConcurrentHashMap<String, Any>()
    private fun getSupplierLock(supplierId: String): Any = supplierLocks.computeIfAbsent(supplierId) { Any() }
    private val supplierInFlightPayments = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private fun getSupplierRevision(supplierId: String): Long = supplierRevisions.computeIfAbsent(supplierId) { AtomicLong(0L) }.get()
    private fun incrementSupplierRevision(supplierId: String): Long = supplierRevisions.computeIfAbsent(supplierId) { AtomicLong(0L) }.incrementAndGet()

    private val _invoicesHasMoreGapFlow = MutableStateFlow<Boolean>(false)
    override val invoicesHasMoreGapFlow: StateFlow<Boolean> = _invoicesHasMoreGapFlow.asStateFlow()

    private var activeInvoicesQuery: SalesInvoicesQuery = SalesInvoicesQuery()
    private var activeInvoicesGeneration: Long? = null
    private var activeInvoicesCompanyId: ServerUlid = companyId

    init {
        rehydrateCompletedShiftClose()
        scope.launch {
            try {
                val pages = offlineLeaseStore.getCatalogPages(companyId, actorId, deviceId)
                if (pages.isNotEmpty()) {
                    val products = pages.flatMap { bytes -> com.example.data.mobile.readclient.ProductCatalogResponse.fromStrictJson(StrictJsonParser.parseObject(String(bytes, Charsets.UTF_8))).products }
                    requireOfflineScope()
                    _productsFlow.value = products.map { mapProductFromCatalog(it) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _productsFlow.value = emptyList() }
        }
        if (autoRefresh) refreshFromBackend()
    }

    private fun rehydrateCompletedShiftClose() {
        try {
            val completed = getLatestCompletedShiftClose()
            _shiftCloseSuccessFlow.value = completed
        } catch (_: Exception) {}
    }

    fun refreshFromBackend() {
        scope.launch {
            refreshProducts()
            refreshCustomers()
            refreshSuppliers()
            refreshPurchases()
            refreshExpenses()
            loadExpenseCategories()
            refreshInvoices()
            refreshHomeMetrics()
            refreshShiftSummary()
            refreshTreasuryReport()
        }
    }

    suspend fun loadExpenseCategories(): Result<List<ServerExpenseCategory>> = withContext(ioDispatcher) {
        try {
            val res = apiClient.getExpenseCategories(companyId)
            if (res.isSuccess) {
                val root = res.getOrThrow()
                val catArray = root.properties["categories"] ?: root.properties["data"]
                val list = mutableListOf<ServerExpenseCategory>()
                if (catArray is StrictJsonArray) {
                    for (elem in catArray.elements) {
                        if (elem is StrictJsonObject) {
                            val id = elem.properties["id"]?.asString() ?: continue
                            val nameAr = elem.properties["name_ar"]?.asString() ?: ""
                            val nameEn = elem.properties["name_en"]?.asString() ?: ""
                            val code = elem.properties["code"]?.asString()
                            list.add(ServerExpenseCategory(id = id, nameAr = nameAr, nameEn = nameEn, code = code))
                        }
                    }
                }
                _expenseCategoriesFlow.value = list
                Result.success(list)
            } else {
                Result.failure(res.exceptionOrNull() ?: RuntimeException("Failed to load expense categories"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Explicit owner-configured bounds; coroutine cancellation preserves the last complete snapshot. */
    suspend fun prepareOfflineCatalog(maxPages: Int, maxProducts: Int, progress: (Int) -> Unit): Result<Unit> = withContext(ioDispatcher) {
        try {
            requireActiveScope()
            require(isOfflineSalesCapabilityEnabled && !isNetworkOffline)
            require(maxPages > 0 && maxProducts > 0)
            val pages = ArrayList<ByteArray>()
            val products = ArrayList<Product>()
            val cursors = HashSet<String>()
            val productIds = HashSet<String>()
            var cursor: String? = null
            var version: String? = null
            do {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
                require(pages.size < maxPages) { "Offline preparation page limit reached" }
                val page = apiClient.getProducts(companyId, limit = 50, cursor = cursor, snapshotVersion = version,
                    verifiedBytes = { pages.add(it) }).getOrThrow()
                require(page.companyId == companyId)
                if (version == null) version = page.catalogSnapshotVersion
                require(page.catalogSnapshotVersion == version)
                require(page.products.all { productIds.add(it.id.value) }) { "Catalog contains duplicate product IDs" }
                products.addAll(page.products.map { mapProductFromCatalog(it) })
                require(products.size <= maxProducts) { "Offline preparation product limit reached" }
                progress(products.size)
                cursor = if (page.hasMore) requireNotNull(page.nextCursor) else null
                if (cursor != null) require(cursors.add(cursor)) { "Catalog cursor repeated" }
            } while (cursor != null)
            requireActiveScope()
            offlineLeaseStore.saveCatalogPages(companyId, actorId, deviceId, requireNotNull(version), pages)
            _productsFlow.value = products
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    override suspend fun refreshProducts(): Result<Unit> = withContext(ioDispatcher) {
        try {
            requireActiveScope()
            var verifiedPage: ByteArray? = null
            val page = apiClient.getProducts(companyId, limit = 50, verifiedBytes = { verifiedPage = it }).getOrThrow()
            require(page.companyId == companyId)
            val mapped = page.products.map { item -> mapProductFromCatalog(item) }
            _productsFlow.value = mapped
            _productsHasMoreFlow.value = page.hasMore
            if (!page.hasMore && offlineLeaseStore.getLease(companyId, actorId, deviceId) == null) offlineLeaseStore.saveCatalogPages(companyId, actorId, deviceId,
                page.catalogSnapshotVersion, listOf(requireNotNull(verifiedPage)))
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun refreshCustomers(): Result<Unit> = withContext(ioDispatcher) {
        try {
            requireActiveScope()
            val page = apiClient.getCustomers(companyId, limit = 50).getOrThrow()
            require(page.companyId == companyId)
            val mapped = page.customers.map { item ->
                Customer(
                    id = item.id.value,
                    nameAr = item.name,
                    nameEn = item.name,
                    phone = item.phone,
                    addressAr = null,
                    addressEn = null,
                    balanceExact = null,
                    creditLimitExact = null,
                    totalPurchasesExact = null,
                    isWalkIn = item.isWalkIn,
                    code = item.code
                )
            }
            _customersFlow.value = mapped
            _customersHasMoreFlow.value = page.hasMore
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun searchCustomers(query: String): Result<List<Customer>> = withContext(ioDispatcher) {
        if (authScopeProvider != null && !isScopeActive()) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val before = requireActiveScope()
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.success(_customersFlow.value)
        }
        try {
            val res = apiClient.getCustomers(companyId, search = trimmed, limit = 30)
            if (res.isSuccess) {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
                check(requireActiveScope() == before) { "Session changed during customer search" }
                val page = res.getOrThrow()
                val list = page.customers.map { item ->
                    val existing = _customersFlow.value.firstOrNull { it.id == item.id.value }
                    Customer(
                        id = item.id.value,
                        nameAr = item.name,
                        nameEn = item.name,
                        phone = item.phone ?: existing?.phone,
                        addressAr = existing?.addressAr,
                        addressEn = existing?.addressEn,
                        balanceExact = existing?.balanceExact,
                        creditLimitExact = existing?.creditLimitExact,
                        totalPurchasesExact = existing?.totalPurchasesExact,
                        isWalkIn = item.isWalkIn,
                        code = item.code ?: existing?.code,
                        isDetailLoaded = existing?.isDetailLoaded ?: false
                    )
                }
                Result.success(list)
            } else {
                Result.failure(res.exceptionOrNull() ?: RuntimeException("Failed to search customers"))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    override suspend fun searchProductsOnServer(query: String, limit: Int): Result<List<Product>> = withContext(ioDispatcher) {
        if (authScopeProvider != null && !isScopeActive()) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val before = requireActiveScope()
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.success(_productsFlow.value.take(limit))
        }
        try {
            val res = apiClient.getProducts(companyId, limit = limit, search = trimmed)
            if (res.isSuccess) {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
                check(requireActiveScope() == before) { "Session changed during product search" }
                val page = res.getOrThrow()
                val list = page.products.map { item -> mapProductFromCatalog(item) }
                Result.success(list)
            } else {
                Result.failure(res.exceptionOrNull() ?: RuntimeException("Failed to search products"))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    override suspend fun refreshSuppliers(search: String?): Result<Unit> = withContext(ioDispatcher) {
        if (authScopeProvider != null && !isScopeActive()) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val before = requireActiveScope()
        try {
            val res = apiClient.getSuppliers(companyId, search = search)
            if (res.isSuccess) {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
                check(requireActiveScope() == before) { "Session changed during supplier refresh" }

                val root = res.getOrThrow()
                val hasMore = (root.properties["has_more"] as? StrictJsonBoolean)?.value
                    ?: (root.properties["has_more"].toNullableString()?.toBooleanStrictOrNull() ?: false)
                _suppliersHasMoreFlow.value = hasMore
                val dataElem = root.properties["suppliers"] ?: root.properties["data"] ?: root.properties["items"]
                if (dataElem is com.example.data.mobile.contract.StrictJsonArray) {
                    val list = (0 until dataElem.size).mapNotNull { i ->
                        val obj = dataElem[i] as? com.example.data.mobile.contract.StrictJsonObject ?: return@mapNotNull null
                        parseSupplier(obj)
                    }
                    if (search.isNullOrBlank()) {
                        _suppliersHasMoreFlow.value = hasMore
                        _suppliersFlow.value = list
                    }
                }
                Result.success(Unit)
            } else {
                _lastRefreshError.value = res.exceptionOrNull()?.message
                Result.failure(res.exceptionOrNull() ?: RuntimeException("Failed to refresh suppliers"))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            _lastRefreshError.value = e.message
            Result.failure(e)
        }
    }

    override suspend fun refreshSuppliers(): Result<Unit> = refreshSuppliers(null)

    override suspend fun searchSuppliers(query: String): Result<List<Supplier>> = withContext(ioDispatcher) {
        if (authScopeProvider != null && !isScopeActive()) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val before = requireActiveScope()
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.success(_suppliersFlow.value)
        }
        try {
            val res = apiClient.getSuppliers(companyId, search = trimmed, limit = 50)
            if (res.isSuccess) {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
                check(requireActiveScope() == before) { "Session changed during supplier search" }
                val root = res.getOrThrow()
                val dataElem = root.properties["suppliers"] ?: root.properties["data"] ?: root.properties["items"]
                val list = if (dataElem is com.example.data.mobile.contract.StrictJsonArray) {
                    (0 until dataElem.size).mapNotNull { i ->
                        val obj = dataElem[i] as? com.example.data.mobile.contract.StrictJsonObject ?: return@mapNotNull null
                        parseSupplier(obj)
                    }
                } else emptyList()
                Result.success(list)
            } else {
                Result.failure(res.exceptionOrNull() ?: RuntimeException("Failed to search suppliers"))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    private fun parseSupplier(obj: com.example.data.mobile.contract.StrictJsonObject): Supplier? {
        val id = obj.properties["id"].toNullableString() ?: return null
        val code = obj.properties["code"].toNullableString()
        val name = obj.properties["name"].toNullableString()
            ?: obj.properties["name_ar"].toNullableString()
            ?: obj.properties["name_en"].toNullableString()
            ?: return null
        val company = obj.properties["company_name"].toNullableString() ?: obj.properties["company"].toNullableString()
        val phone = obj.properties["phone"].toNullableString()
        val balanceStr = obj.properties["balance_owed"].toNullableString()
            ?: obj.properties["owed"].toNullableString()
            ?: obj.properties["balance"].toNullableString()
        val balanceExact = balanceStr?.let { try { BigDecimal(it) } catch (_: Exception) { null } }
        val advancesStr = obj.properties["advances"].toNullableString()
        val advancesExact = advancesStr?.let { try { BigDecimal(it) } catch (_: Exception) { null } }
        return Supplier(
            id = id,
            code = code,
            nameAr = name,
            nameEn = name,
            companyAr = company,
            companyEn = company,
            phone = phone,
            balanceOwedExact = balanceExact,
            advancesExact = advancesExact
        )
    }

    private suspend fun refreshPurchases() {
        try {
            val res = apiClient.getPurchases(companyId)
            if (res.isSuccess) {
                val root = res.getOrThrow()
                val dataElem = root.properties["purchases"] ?: root.properties["data"]
                if (dataElem is com.example.data.mobile.contract.StrictJsonArray) {
                    val list = (0 until dataElem.size).mapNotNull { i ->
                        val obj = dataElem[i] as? com.example.data.mobile.contract.StrictJsonObject ?: return@mapNotNull null
                        parseAuthoritativePurchaseBill(obj)
                    }
                    _purchasesFlow.value = list
                }
            } else {
                _lastRefreshError.value = res.exceptionOrNull()?.message
            }
        } catch (e: Exception) {
            _lastRefreshError.value = e.message
        }
    }

    private suspend fun refreshExpenses() {
        try {
            val res = apiClient.getExpenses(companyId)
            if (res.isSuccess) {
                val root = res.getOrThrow()
                val dataElem = root.properties["expenses"] ?: root.properties["data"]
                if (dataElem is com.example.data.mobile.contract.StrictJsonArray) {
                    val list = (0 until dataElem.size).mapNotNull { i ->
                        val obj = dataElem[i] as? com.example.data.mobile.contract.StrictJsonObject ?: return@mapNotNull null
                        val id = obj.properties["id"]?.asString() ?: return@mapNotNull null
                        val title = obj.properties["title"]?.asString()
                            ?: obj.properties["description"]?.asString()
                            ?: "Expense"
                        val amountStr = obj.properties["amount_ils"]?.asString()
                            ?: obj.properties["amount"]?.asString()
                            ?: "0.00"
                        val date = obj.properties["date"]?.asString() ?: WesternFormatters.currentDate()
                        ExpenseEntry(
                            id = id,
                            date = date,
                            titleAr = title,
                            titleEn = title,
                            category = ExpenseCategory.OTHER,
                            amountExact = try { BigDecimal(amountStr) } catch (_: Exception) { BigDecimal.ZERO }
                        )
                    }
                    _expensesFlow.value = list
                }
            } else {
                _lastRefreshError.value = res.exceptionOrNull()?.message
            }
        } catch (e: Exception) {
            _lastRefreshError.value = e.message
        }
    }

    private fun mapProductFromCatalog(item: ProductCatalogItem): Product {
        val defaultPackaging = item.units.firstOrNull { it.isDefaultSale } ?: item.units.firstOrNull()
        val priceExact = (if (defaultPackaging != null) defaultPackaging.salePrice?.value else item.defaultUnit.salePrice?.value)
            ?: item.unitPrice?.value?.multiply(defaultPackaging?.conversionToBase?.value ?: item.defaultUnit.conversionToBase.value)
        val stockCountExact = item.availableQuantity.value
        val conversionExact = defaultPackaging?.conversionToBase?.value ?: item.defaultUnit.conversionToBase.value
        val productUnitUlid = defaultPackaging?.productUnitId?.value ?: item.defaultUnit.productUnitId.value

        val tax = item.taxRateId?.let { id ->
            bootstrapTaxes.singleOrNull { it.id == id && item.taxRate?.value?.compareTo(it.rate.value) == 0 }
        }
        return Product(
            id = item.id.value,
            nameAr = item.name,
            nameEn = item.name,
            category = ProductCategory.ALL,
            barcode = item.barcode ?: item.sku,
            priceExact = priceExact,
            costExact = null,
            unitAr = item.baseUnit.name,
            unitEn = item.baseUnit.code,
            stockCount = stockCountExact.toInt(),
            stockCountExact = stockCountExact,
            minStockThresholdExact = BigDecimal.valueOf(8),
            accentHex = 0xFF2E7D32,
            isStockTracked = item.isTracked,
            warehouseName = defaultWarehouse.name,
            lotBatchCode = null,
            expiryDate = null,
            isWeightedDisplayOnly = false,
            productUnitId = productUnitUlid,
            conversionToBaseExact = conversionExact,
            taxRateId = tax?.id?.value,
            taxRateExact = tax?.rate?.value,
            isTaxInclusive = tax?.calculation == "inclusive",
            saleCurrencyMinorUnits = currencyMinorUnits,
            sku = item.sku
        )
    }

    // -------------------------------------------------------------------------
    // Session & Business Info
    // -------------------------------------------------------------------------

    override fun getCurrentSession(): UserSession = currentSession

    override fun getBusinessInfo(): BusinessInfo = businessInfo

    // -------------------------------------------------------------------------
    // Product & Inventory
    // -------------------------------------------------------------------------

    override fun getProducts(): List<Product> = _productsFlow.value

    override fun searchProducts(query: String, category: ProductCategory, limit: Int): List<Product> {
        val all = _productsFlow.value
        if (query.isBlank() && category == ProductCategory.ALL) return all.take(limit)
        return all.filter { prod ->
            val matchesCat = category == ProductCategory.ALL || prod.category == category
            val matchesQuery = query.isBlank() || prod.computeSearchScore(query.trim()) > 0
            matchesCat && matchesQuery
        }.take(limit)
    }

    override fun findProductById(id: String): Product? = _productsFlow.value.firstOrNull { it.id == id }

    override suspend fun updateProductPrice(productId: String, newPriceIls: Double, operator: UserSession): Result<Unit> = withContext(ioDispatcher) {
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val currentProd = findProductById(productId) ?: return@withContext Result.failure(IllegalArgumentException("Product not found: $productId"))
        if (!ServerUlid.isValid(productId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid product ULID: $productId"))
        }
        val newPriceExact = BigDecimal(newPriceIls.toString()).setScale(currencyMinorUnits, RoundingMode.HALF_UP)
        val res = apiClient.updateProduct(
            companyId = companyId,
            productId = ServerUlid.parse(productId),
            nameAr = currentProd.nameAr,
            nameEn = currentProd.nameEn,
            barcode = currentProd.barcode,
            priceIls = newPriceExact,
            costIls = currentProd.costExact
        )
        if (res.isSuccess) {
            _productsFlow.update { list ->
                list.map { if (it.id == productId) it.copy(priceExact = newPriceExact) else it }
            }
            Result.success(Unit)
        } else {
            Result.failure(res.exceptionOrNull() ?: RuntimeException("Price update failed"))
        }
    }

    override suspend fun adjustProductStock(
        productId: String,
        adjustment: Int,
        reason: String,
        operator: UserSession
    ): Result<StockMovement> = adjustProductStock(productId, adjustment, reason, operator, "")

    override suspend fun adjustProductStock(
        productId: String,
        adjustment: Int,
        reason: String,
        operator: UserSession,
        operationId: String
    ): Result<StockMovement> = adjustProductStockExact(
        productId = productId,
        deltaExact = BigDecimal.valueOf(adjustment.toLong()),
        reason = reason,
        operator = operator,
        operationId = operationId
    )

    suspend fun adjustProductStockExact(
        productId: String,
        deltaExact: BigDecimal,
        reason: String,
        operator: UserSession,
        operationId: String = ""
    ): Result<StockMovement> = withContext(ioDispatcher) {
        requireActiveScope()
        if (!ServerUlid.isValid(productId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid product ULID: $productId"))
        }
        if (deltaExact.compareTo(BigDecimal.ZERO) == 0) {
            return@withContext Result.failure(IllegalArgumentException("Adjustment delta cannot be zero."))
        }
        if (reason.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Adjustment reason is required."))
        }

        val effectiveOpId = if (operationId.isNotBlank()) operationId else UUID.randomUUID().toString()
        val commandId = effectiveOpId
        val idempotencyKey = if (effectiveOpId.startsWith("adj-")) effectiveOpId else "adj-$effectiveOpId"

        val payloadObj = StrictJsonObject(
            mapOf(
                "product_id" to StrictJsonString(productId),
                "warehouse_id" to StrictJsonString(defaultWarehouse.id.value),
                "delta" to StrictJsonString(deltaExact.setScale(6, RoundingMode.HALF_UP).toPlainString()),
                "reason" to StrictJsonString(reason)
            )
        )
        val payloadBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(payloadObj)

        val postCall: suspend (String) -> Result<StrictJsonObject> = { key ->
            apiClient.adjustStock(
                companyId = companyId,
                productId = ServerUlid.parse(productId),
                warehouseId = defaultWarehouse.id,
                delta = deltaExact,
                reason = reason,
                idempotencyKey = key
            )
        }

        val durableRes = executeDurableCommand(
            commandType = "inventory.adjustment",
            commandId = commandId,
            idempotencyKey = idempotencyKey,
            canonicalPayloadBytes = payloadBytes,
            isReceiptStatusChecked = true,
            postAction = postCall
        )

        if (durableRes.isFailure) {
            return@withContext Result.failure(durableRes.exceptionOrNull() ?: RuntimeException("Stock adjustment failed"))
        }

        val snapshot = durableRes.getOrThrow()
        val officialId = snapshot.properties["id"]?.asString()
            ?: return@withContext Result.failure(IllegalStateException("Server did not return adjustment ID"))
        val officialDelta = snapshot.properties["delta"]?.asString()?.let { BigDecimal(it) } ?: deltaExact
        val officialResultingStock = snapshot.properties["resulting_stock"]?.asString()?.let { BigDecimal(it) }
        val postedAt = snapshot.properties["posted_at"]?.asString() ?: WesternFormatters.currentDate()

        val finalResultingStock: BigDecimal? = if (officialResultingStock != null) {
            _productsFlow.update { list ->
                list.map { p ->
                    if (p.id == productId) {
                        p.copy(stockCountExact = officialResultingStock, stockCount = officialResultingStock.toInt())
                    } else p
                }
            }
            officialResultingStock
        } else {
            apiClient.getProduct(companyId, ServerUlid.parse(productId)).getOrNull()?.let { prodObj ->
                val serverStock = prodObj.properties["available_quantity"]?.asString()?.let { BigDecimal(it) }
                    ?: prodObj.properties["stock_quantity"]?.asString()?.let { BigDecimal(it) }
                if (serverStock != null) {
                    _productsFlow.update { list ->
                        list.map { p ->
                            if (p.id == productId) {
                                p.copy(stockCountExact = serverStock, stockCount = serverStock.toInt())
                            } else p
                        }
                    }
                    serverStock
                } else null
            }
        }

        val movement = StockMovement(
            id = officialId,
            productId = productId,
            date = postedAt,
            deltaExact = officialDelta,
            reasonAr = reason,
            reasonEn = reason,
            resultingStockExact = finalResultingStock
        )
        _stockMovementsFlow.update { list -> listOf(movement) + list }
        Result.success(movement)
    }

    override fun getStockMovements(): List<StockMovement> = _stockMovementsFlow.value

    override suspend fun getProductStockHistory(
        productId: String,
        limit: Int
    ): Result<ProductHistoryResponse> = withContext(ioDispatcher) {
        if (authScopeProvider != null && !isScopeActive()) {
            return@withContext Result.failure(SecurityException("Authenticated scope is superseded or revoked."))
        }
        val client = merchantReadClient ?: return@withContext Result.failure(IllegalStateException("MerchantReadClient not configured"))
        val ulid = try {
            ServerUlid.parse(productId)
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Invalid productId ULID: '$productId'", e))
        }
        try {
            val res = client.getProductHistory(companyId, ulid, limit, retainedGeneration = retainedGeneration)
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
            if (authScopeProvider != null && !isScopeActive()) {
                return@withContext Result.failure(SecurityException("Authenticated scope is superseded or revoked."))
            }
            when (res) {
                is MerchantReadResult.Success -> {
                    val history = res.value
                    val mapped = history.movements.map { item ->
                        MerchantReadAdapter.toDomainStockMovement(item, productId)
                    }
                    if (authScopeProvider != null && !isScopeActive()) {
                        throw SecurityException("Authenticated scope changed")
                    }
                    _stockMovementsFlow.update { current ->
                        val others = current.filter { it.productId != productId }
                        mapped + others
                    }
                    Result.success(history)
                }
                is MerchantReadResult.NotFound -> Result.failure(NoSuchElementException("Product '$productId' not found"))
                is MerchantReadResult.Forbidden -> Result.failure(SecurityException("Forbidden: ${res.error?.message ?: "Missing inventory.stock.view permission"}"))
                is MerchantReadResult.AuthDenial -> Result.failure(SecurityException("Authentication denied: ${res.error?.message ?: "UNAUTHENTICATED"}"))
                is MerchantReadResult.NetworkError -> Result.failure(java.io.IOException("Network error fetching product movements: ${res.message}"))
                is MerchantReadResult.SchemaError -> Result.failure(IllegalStateException("Schema error parsing product movements: ${res.message}"))
                else -> Result.failure(RuntimeException("Failed to load product stock history: $res"))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addCustomProduct(product: Product): Result<Product> {
        _productsFlow.update { listOf(product) + it }
        return Result.success(product)
    }

    fun seedProduct(product: Product) {
        _productsFlow.update { listOf(product) + it.filter { p -> p.id != product.id } }
    }

    fun seedPurchaseBill(bill: PurchaseBill) {
        _purchasesFlow.update { listOf(bill) + it.filter { b -> b.id != bill.id } }
    }

    override suspend fun saveProduct(
        productId: String?,
        nameAr: String,
        nameEn: String,
        category: ProductCategory,
        unitAr: String,
        unitEn: String,
        barcode: String,
        priceIls: Double,
        costIls: Double,
        minStockThreshold: Int,
        initialStock: Int,
        warehouseAr: String,
        lotBatchCode: String,
        expiryDate: String?,
        isWeightedDisplayOnly: Boolean,
        operator: UserSession
    ): Result<Product> = withContext(ioDispatcher) {
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val priceExact = BigDecimal(priceIls.toString()).setScale(currencyMinorUnits, RoundingMode.HALF_UP)
        val costExact = if (costIls > 0.0) BigDecimal(costIls.toString()).setScale(currencyMinorUnits, RoundingMode.HALF_UP) else null

        if (productId != null && ServerUlid.isValid(productId)) {
            val res = apiClient.updateProduct(
                companyId = companyId,
                productId = ServerUlid.parse(productId),
                nameAr = nameAr,
                nameEn = nameEn,
                barcode = barcode,
                priceIls = priceExact,
                costIls = costExact
            )
            if (res.isSuccess) {
                val updated = Product(
                    id = productId,
                    nameAr = nameAr,
                    nameEn = nameEn,
                    category = category,
                    barcode = barcode,
                    priceExact = priceExact,
                    costExact = costExact,
                    unitAr = unitAr,
                    unitEn = unitEn,
                    stockCount = initialStock,
                    minStockThreshold = minStockThreshold,
                    accentHex = 0xFF2E7D32,
                    isStockTracked = true,
                    warehouseName = warehouseAr,
                    lotBatchCode = lotBatchCode,
                    expiryDate = expiryDate,
                    isWeightedDisplayOnly = isWeightedDisplayOnly
                )
                _productsFlow.update { list -> list.map { if (it.id == productId) updated else it } }
                Result.success(updated)
            } else {
                Result.failure(res.exceptionOrNull() ?: RuntimeException("Product update failed"))
            }
        } else {
            val baseUnitId = bootstrapUnits.firstOrNull()?.id
            val res = apiClient.createProduct(
                companyId = companyId,
                nameAr = nameAr,
                nameEn = nameEn,
                barcode = barcode,
                priceIls = priceExact,
                costIls = costExact,
                unitId = baseUnitId
            )
            if (res.isSuccess) {
                val createdUlid = res.getOrNull()?.properties?.get("id")?.asString() ?: UUID.randomUUID().toString()
                val created = Product(
                    id = createdUlid,
                    nameAr = nameAr,
                    nameEn = nameEn,
                    category = category,
                    barcode = barcode,
                    priceExact = priceExact,
                    costExact = costExact,
                    unitAr = unitAr,
                    unitEn = unitEn,
                    stockCount = initialStock,
                    minStockThreshold = minStockThreshold,
                    accentHex = 0xFF2E7D32,
                    isStockTracked = true,
                    warehouseName = warehouseAr,
                    lotBatchCode = lotBatchCode,
                    expiryDate = expiryDate,
                    isWeightedDisplayOnly = isWeightedDisplayOnly
                )
                _productsFlow.update { listOf(created) + it }
                Result.success(created)
            } else {
                Result.failure(res.exceptionOrNull() ?: RuntimeException("Product creation failed"))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Sales & Invoices (Native V2 Online-Cash Lifecycle)
    // -------------------------------------------------------------------------

    override fun getInvoices(): List<InvoiceRecord> = _invoicesFlow.value

    val supportsSaleRecovery: Boolean get() = outboxRepository != null
    private val saleMutex = Mutex()

    private fun heldOnlineSale(): OutboxRecord? {
        requireActiveScope()
        return checkNotNull(outboxRepository).oldestHeldOnlineSale(companyId, actorId, deviceId)
    }

    private fun retainedSaleIntent(record: OutboxRecord): OnlineSaleIntentProfile {
        val bytes = record.payloadBytes
        val intent = OnlineSaleIntentProfile.fromStrictJson(
            StrictJsonParser.parseObject(String(bytes, StandardCharsets.UTF_8))
        )
        require(record.eventVersion == 2 && record.event.deviceLeaseId == null &&
            intent.companyId == companyId && intent.actorId == actorId && intent.deviceId == deviceId &&
            intent.clientEventId.value == record.clientEventId &&
            PayloadDigest.fromBytes(bytes).value == record.payloadDigest &&
            intent.computePayloadDigest().value == record.payloadDigest
        ) { "Stored sale identity could not be verified" }
        return intent
    }

    private fun recoverInvoice(record: OutboxRecord, intent: OnlineSaleIntentProfile): InvoiceRecord {
        val catalog = _productsFlow.value.associateBy { it.id }
        var total = BigDecimal.ZERO
        var taxTotal = BigDecimal.ZERO
        val items = intent.lines.map { line ->
            val calc = com.example.data.mobile.contract.SalesLineCalculator.calculate(
                quantity = line.quantity,
                unitPrice = line.unitPrice,
                taxPercentage = line.taxRate,
                taxInclusive = line.taxInclusive,
                minorUnits = intent.currencyMinorUnits
            )
            total = total.add(calc.lineTotal.value)
            taxTotal = taxTotal.add(calc.taxAmount.value)
            val product = catalog[line.productId.value] ?: Product(
                id = line.productId.value,
                nameAr = line.productId.value,
                nameEn = line.productId.value,
                category = ProductCategory.ALL,
                barcode = "",
                priceExact = line.unitPrice.value,
                unitAr = "",
                unitEn = "",
                stockCount = 0,
                accentHex = 0xFF2E7D32
            )
            BasketItem(
                product = product.copy(
                    productUnitId = line.productUnitId.value,
                    conversionToBaseExact = line.conversionToBase.value,
                    saleCurrencyMinorUnits = intent.currencyMinorUnits
                ),
                quantity = line.quantity.value,
                unitPriceExact = line.unitPrice.value,
                taxRateId = line.taxRateId?.value,
                taxRateExact = line.taxRate.value,
                isTaxInclusive = line.taxInclusive,
                precomputedLineTotalExact = calc.lineTotal.value
            )
        }
        val settled = intent.payment.amount.value
        return InvoiceRecord(
            id = record.clientEventId,
            invoiceNumber = 0,
            timestamp = record.event.capturedAt,
            items = items,
            subtotalExact = total,
            discountExact = BigDecimal.ZERO,
            netTotalExact = total,
            cashReceivedExact = settled,
            changeReturnedExact = BigDecimal.ZERO,
            customerId = intent.customerId.value.takeUnless { it == walkInCustomerId.value },
            customerNote = "",
            cashierNameAr = actorName,
            cashierNameEn = actorName,
            isPaidInFull = intent.payment.settlementMode == "full",
            remainingDebitExact = total - settled,
            hasLocalTenderDetails = false,
            currency = intent.currency,
            currencyMinorUnits = intent.currencyMinorUnits,
            taxExact = taxTotal
        ).withOutboxState(record)
    }

    fun getActiveOfflineSaleLease(): OfflineSaleLease? {
        val lease = offlineLeaseStore.getLease(companyId, actorId, deviceId) ?: return null
        if (!lease.isTimeValid(offlineLeaseStore.trustedTime(lease) ?: clock())) {
            offlineLeaseStore.clearLease(companyId, actorId, deviceId)
            return null
        }
        return lease
    }

    fun hasValidOfflineLease(): Boolean = getActiveOfflineSaleLease() != null

    suspend fun acquireOfflineSaleLease(): Result<OfflineSaleLease> = withContext(ioDispatcher) {
        try {
            requireActiveScope()
            if (!isOfflineSalesCapabilityEnabled) {
                return@withContext Result.failure(IllegalStateException("Offline sales capability is disabled"))
            }
            val snapshotVersion = offlineLeaseStore.getVerifiedCatalogSnapshotVersion(companyId, actorId, deviceId)
            val isComplete = offlineLeaseStore.isCatalogComplete(companyId, actorId, deviceId)
            if (snapshotVersion == null || !isComplete) {
                return@withContext Result.failure(IllegalStateException("Product catalog is incomplete or missing for offline sales"))
            }
            val requestElapsed = android.os.SystemClock.elapsedRealtime()
            val result = apiClient.acquireOfflineSaleLease(companyId, snapshotVersion)
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull()!!)
            }
            val lease = result.getOrThrow()
            require(lease.companyId == companyId && lease.actorId == actorId && lease.deviceId == deviceId) {
                "Acquired lease scope mismatch"
            }
            require(lease.catalogSnapshotVersion == snapshotVersion) { "Acquired lease catalog mismatch" }
            requireActiveScope()
            require(lease.currency == baseCurrency && lease.currencyMinorUnits == currencyMinorUnits) {
                "Acquired lease currency mismatch"
            }
            require(offlineLeaseStore.getVerifiedCatalogSnapshotVersion(companyId, actorId, deviceId) == snapshotVersion &&
                offlineLeaseStore.isCatalogComplete(companyId, actorId, deviceId)) { "Catalog changed during lease acquisition" }
            val durableLease = requireNotNull(outboxRepository).reconcileOfflineLease(lease, offlineLeaseStore, requestElapsed)
            _activeOfflineLeaseFlow.value = durableLease
            Result.success(durableLease)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun recoverOfflineInvoice(record: OutboxRecord, event: OfflineSaleEventV2): InvoiceRecord {
        val catalog = _productsFlow.value.associateBy { it.id }
        val matchingLeaseMinorUnits = offlineLeaseStore.getLease(event.companyId, event.actorId, event.deviceId)
            ?.takeIf { it.leaseId == event.deviceLeaseId }
            ?.currencyMinorUnits
            ?.takeIf { it in 0..6 }
        val rawRecordMinorUnits = record.event.currencyMinorUnits
        // Plaintext Room column metadata is advisory and bounded to 0..6; tampered out-of-range
        // values fail closed to null instead of crashing or reconstructing stored economic totals.
        val frozenMinorUnits = when {
            rawRecordMinorUnits != null -> rawRecordMinorUnits.takeIf { it in 0..6 }
            else -> matchingLeaseMinorUnits
        }
        var total = BigDecimal.ZERO
        var taxTotal = BigDecimal.ZERO
        val items = event.lines.map { line ->
            total = total.add(line.lineTotal.value)
            taxTotal = taxTotal.add(line.taxAmount.value)
            val product = catalog[line.productId.value] ?: Product(
                id = line.productId.value,
                nameAr = line.productId.value,
                nameEn = line.productId.value,
                category = ProductCategory.ALL,
                barcode = "",
                priceExact = line.unitPrice.value,
                unitAr = "",
                unitEn = "",
                stockCount = 0,
                accentHex = 0xFF2E7D32
            )
            BasketItem(
                product = product.copy(
                    productUnitId = line.productUnitId.value,
                    conversionToBaseExact = line.conversionToBase.value,
                    saleCurrencyMinorUnits = frozenMinorUnits
                ),
                quantity = line.quantity.value,
                unitPriceExact = line.unitPrice.value,
                taxRateId = line.taxRateId?.value,
                taxRateExact = line.taxRate.value,
                isTaxInclusive = line.taxInclusive,
                precomputedLineTotalExact = line.lineTotal.value
            )
        }
        val tendered = event.payment.amountTendered.value
        val change = event.payment.changeReturned.value
        return InvoiceRecord(
            id = record.clientEventId,
            invoiceNumber = 0,
            timestamp = record.event.capturedAt,
            items = items,
            subtotalExact = total,
            discountExact = BigDecimal.ZERO,
            netTotalExact = total,
            cashReceivedExact = tendered,
            changeReturnedExact = change,
            customerId = event.customerId.value.takeUnless { it == walkInCustomerId.value },
            customerNote = "",
            cashierNameAr = actorName,
            cashierNameEn = actorName,
            isPaidInFull = true,
            remainingDebitExact = BigDecimal.ZERO,
            hasLocalTenderDetails = true,
            localReceiptNumber = event.localReceiptNumber,
            currency = event.currency,
            currencyMinorUnits = frozenMinorUnits,
            taxExact = taxTotal
        ).withOutboxState(record)
    }

    private fun InvoiceRecord.withOutboxState(record: OutboxRecord): InvoiceRecord {
        val delivery = record.deliveryState
        var resolvedLocalReceipt = localReceiptNumber
        var resolvedCurrency = normalizedCurrency
        val rawRecordMinorUnits = record.event.currencyMinorUnits
        val validatedRecordMinorUnits = rawRecordMinorUnits?.takeIf { it in 0..6 }
        val hasTamperedOutOfRangeMetadata = rawRecordMinorUnits != null && rawRecordMinorUnits !in 0..6
        var resolvedMinorUnits = if (record.event.deviceLeaseId != null && hasTamperedOutOfRangeMetadata) {
            null
        } else {
            currencyMinorUnits?.takeIf { it in 0..6 } ?: validatedRecordMinorUnits
        }
        if (resolvedLocalReceipt == null || resolvedCurrency == null || (record.event.deviceLeaseId == null && resolvedMinorUnits == null)) {
            try {
                val root = StrictJsonParser.parseObject(String(record.payloadBytes, StandardCharsets.UTF_8))
                if (record.event.deviceLeaseId != null) {
                    val ev = OfflineSaleEventV2.fromStrictJson(root)
                    if (resolvedLocalReceipt == null) resolvedLocalReceipt = ev.localReceiptNumber
                    if (resolvedCurrency == null) resolvedCurrency = ev.currency
                } else {
                    val intent = OnlineSaleIntentProfile.fromStrictJson(root)
                    if (resolvedCurrency == null) resolvedCurrency = intent.currency
                    if (resolvedMinorUnits == null) resolvedMinorUnits = intent.currencyMinorUnits.takeIf { it in 0..6 }
                }
            } catch (_: Exception) { }
        }
        return copy(
            id = delivery.invoiceUlid ?: record.clientEventId,
            clientEventId = record.clientEventId,
            salePayloadDigest = record.payloadDigest,
            serverInvoiceUlid = delivery.invoiceUlid,
            serverPaymentUlid = delivery.paymentUlid,
            serverPostedAt = delivery.serverAcknowledgedAt,
            outboxStatus = if (record.status == OutboxStatus.CONFIRMED) "CONFIRMED" else record.status.wireValue,
            localReceiptNumber = resolvedLocalReceipt,
            currency = resolvedCurrency,
            currencyMinorUnits = resolvedMinorUnits
        )
    }

    private fun publishSale(invoice: InvoiceRecord, offline: Boolean = false): InvoiceRecord {
        if (offline) requireOfflineScope() else requireActiveScope()
        _invoicesFlow.update { invoices ->
            listOf(invoice) + invoices.filterNot {
                it.clientEventId == invoice.clientEventId || it.id == invoice.id
            }
        }
        return invoice
    }

    private fun heldInvoice(record: OutboxRecord): InvoiceRecord {
        val cached = _invoicesFlow.value.firstOrNull {
            it.clientEventId == record.clientEventId && it.salePayloadDigest == record.payloadDigest
        }
        if (cached != null) return cached

        return if (record.event.deviceLeaseId != null) {
            val root = StrictJsonParser.parseObject(String(record.payloadBytes, StandardCharsets.UTF_8))
            val event = OfflineSaleEventV2.fromStrictJson(root)
            recoverOfflineInvoice(record, event)
        } else {
            recoverInvoice(record, retainedSaleIntent(record))
        }
    }

    suspend fun restoreHeldSale(): Result<InvoiceRecord?> = withContext(ioDispatcher) {
        saleMutex.withLock {
            runCatching {
                heldOnlineSale()?.let { publishSale(heldInvoice(it).withOutboxState(it)) }
            }
        }
    }

    suspend fun checkHeldSaleStatus(expectedEventId: String): Result<InvoiceRecord> = withContext(ioDispatcher) {
        saleMutex.withLock {
            runCatching {
                requireActiveScope()
                val outbox = checkNotNull(outboxRepository)
                val eventId = ClientUuid.parse(expectedEventId)
                val record = checkNotNull(outbox.getRecord(companyId, actorId, deviceId, eventId))
                val intent = retainedSaleIntent(record)
                val invoice = heldInvoice(record)
                if (outbox.isQueueLocked(companyId, actorId, deviceId)) return@runCatching invoice.withOutboxState(record)
                val coordinator = syncCoordinator ?: SaleSyncCoordinatorImpl(outbox, checkNotNull(saleSyncGateway))
                val outcome = coordinator.reconcileOnlineSaleStatus(record, intent) { requireActiveScope() }
                val current = checkNotNull(outbox.getRecord(companyId, actorId, deviceId, eventId))
                val receipt = invoice.withOutboxState(current)
                if (outcome is SyncStatusOutcome.Posted) publishSale(receipt.copy(
                    serverInvoiceNumber = outcome.ack.invoiceNumber,
                    serverPaymentReceiptNumber = outcome.ack.paymentReceiptNumber
                )) else {
                    // A check can clear a hold only when its own authenticated lookup verifies the result.
                    require(current.status != OutboxStatus.CONFIRMED) { "Could not verify the held sale" }
                    receipt
                }
            }
        }
    }

    suspend fun recordOfflineSale(invoice: InvoiceRecord): Result<InvoiceRecord> = withContext(ioDispatcher) {
        saleMutex.withLock { recordOfflineSaleLocked(invoice) }
    }

    private suspend fun recordOfflineSaleLocked(invoice: InvoiceRecord): Result<InvoiceRecord> = withContext(ioDispatcher) {
        try { requireOfflineScope() } catch (denied: SecurityException) { return@withContext Result.failure(denied) }

        if (!isOfflineSalesCapabilityEnabled) {
            return@withContext Result.failure(IllegalStateException("إمكانية المبيعات غير المتصلة معطلة | Offline sales capability is disabled"))
        }

        val lease = getActiveOfflineSaleLease()
            ?: return@withContext Result.failure(IllegalStateException("لا يوجد إذن نشط صالح للمبيعات غير المتصلة | No active valid offline sale lease"))

        val isCatalogComplete = offlineLeaseStore.isCatalogComplete(companyId, actorId, deviceId)
        val snapshotVersion = offlineLeaseStore.getVerifiedCatalogSnapshotVersion(companyId, actorId, deviceId)
        if (!isCatalogComplete || snapshotVersion != lease.catalogSnapshotVersion) {
            return@withContext Result.failure(IllegalStateException("كتالوج المنتجات غير مكتمل أو لا يطابق إذن المبيعات | Product catalog snapshot is incomplete or mismatches active lease"))
        }

        if (!invoice.isPaidInFull || invoice.remainingDebitExact > BigDecimal.ZERO) {
            return@withContext Result.failure(IllegalArgumentException("المبيعات غير المتصلة تدعم الدفع النقدي الكامل فقط | Offline sales support full cash payment only"))
        }

        if (invoice.discountExact.compareTo(BigDecimal.ZERO) != 0) {
            return@withContext Result.failure(IllegalArgumentException("الخصومات غير مدعومة في المبيعات غير المتصلة | Discounts are not supported in offline sales"))
        }

        if (invoice.items.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Invoice must contain at least one item."))
        }

        for (item in invoice.items) {
            if (item.quantity <= BigDecimal.ZERO) {
                return@withContext Result.failure(IllegalArgumentException("Item quantity must be > 0."))
            }
            if (item.unitPriceExact < BigDecimal.ZERO) {
                return@withContext Result.failure(IllegalArgumentException("Item price cannot be negative."))
            }
        }

        val tenderedExact = invoice.cashReceivedExact
        val netTotalExact = invoice.netTotalExact
        val changeReturnedExact = invoice.changeReturnedExact

        if (netTotalExact <= BigDecimal.ZERO) {
            return@withContext Result.failure(IllegalArgumentException("Grand total must be strictly positive."))
        }

        if (invoice.normalizedCurrency != null && !invoice.normalizedCurrency.equals(lease.currency, ignoreCase = true)) {
            return@withContext Result.failure(
                IllegalArgumentException("Invoice currency (${invoice.normalizedCurrency}) does not match active lease currency (${lease.currency})")
            )
        }

        val expectedTender = netTotalExact.add(changeReturnedExact)
        if (tenderedExact.compareTo(expectedTender) != 0) {
            return@withContext Result.failure(
                IllegalArgumentException("Cash tender conservation violated: tendered ($tenderedExact) != netTotal ($netTotalExact) + change ($changeReturnedExact)")
            )
        }

        if (tenderedExact < netTotalExact) {
            return@withContext Result.failure(IllegalArgumentException("Tendered cash must be >= settled total"))
        }

        val outbox = outboxRepository ?: return@withContext Result.failure(IllegalStateException("OutboxRepository not configured"))

        val (pendingCount, pendingTotal) = outbox.getPendingOfflineSalesBudget(companyId, actorId, deviceId, lease.leaseId)
        val newCount = lease.postedSalesCount + pendingCount + 1
        val newTotal = lease.postedTotalAmount.add(pendingTotal).add(netTotalExact)

        if (newCount > lease.maxOfflineSalesCount) {
            return@withContext Result.failure(
                IllegalStateException("تجاوز الحد الأقصى لعدد المبيعات غير المتصلة المسموح به (${lease.maxOfflineSalesCount}) | Exceeded max offline sales count limit")
            )
        }
        if (newTotal.compareTo(lease.maxOfflineTotalAmount) > 0) {
            return@withContext Result.failure(
                IllegalStateException("تجاوز الحد الأقصى للمبلغ الإجمالي للمبيعات غير المتصلة المسموح به (${lease.maxOfflineTotalAmount}) | Exceeded max offline total amount limit")
            )
        }

        val targetCustomerId = if (invoice.customerId != null && ServerUlid.isValid(invoice.customerId)) {
            ServerUlid.parse(invoice.customerId)
        } else {
            lease.walkInCustomerId
        }

        val leasedProducts = try {
            offlineLeaseStore.getCatalogPages(companyId, actorId, deviceId).flatMap { bytes ->
                val page = com.example.data.mobile.readclient.ProductCatalogResponse.fromStrictJson(StrictJsonParser.parseObject(String(bytes, Charsets.UTF_8)))
                require(page.companyId == companyId && page.catalogSnapshotVersion == lease.catalogSnapshotVersion)
                page.products.map { mapProductFromCatalog(it) }
            }.associateBy { it.id }
        } catch (error: Exception) { return@withContext Result.failure(error) }
        val lines = ArrayList<OfflineSaleLineV2>(invoice.items.size)
        for (basketItem in invoice.items) {
            val verifiedProduct = leasedProducts[basketItem.product.id]
                ?: return@withContext Result.failure(IllegalArgumentException("Product missing from leased catalog"))
            if (verifiedProduct.productUnitId != basketItem.product.productUnitId ||
                verifiedProduct.conversionToBaseExact.compareTo(basketItem.product.conversionToBaseExact) != 0) {
                return@withContext Result.failure(IllegalArgumentException("Product unit differs from leased catalog"))
            }
            val prodUlid = if (ServerUlid.isValid(basketItem.product.id)) {
                ServerUlid.parse(basketItem.product.id)
            } else {
                return@withContext Result.failure(IllegalArgumentException("Cannot record sale: product '${basketItem.product.nameAr}' lacks a valid server ULID."))
            }

            val packagingUnitStr = basketItem.product.productUnitId
            if (packagingUnitStr.isNullOrBlank() || !ServerUlid.isValid(packagingUnitStr)) {
                return@withContext Result.failure(IllegalArgumentException("Cannot record sale: product '${basketItem.product.nameAr}' lacks a valid packaging product_unit_id."))
            }
            val productUnitUlid = ServerUlid.parse(packagingUnitStr)

            val lineTaxRate: TaxPercentage
            val lineTaxRateId: ServerUlid?
            val lineTaxInclusive: Boolean

            if (lease.taxRateId == null) {
                if (basketItem.taxRateId != null || basketItem.taxRateExact?.signum()?.let { it != 0 } == true || basketItem.isTaxInclusive) {
                    return@withContext Result.failure(IllegalArgumentException("Tax selected but offline lease configured for no tax"))
                }
                lineTaxRateId = null
                lineTaxRate = TaxPercentage("0.000000")
                lineTaxInclusive = false
            } else {
                if (!basketItem.hasResolvedTax || basketItem.taxRateId != lease.taxRateId.value ||
                    basketItem.taxRateExact?.compareTo(requireNotNull(lease.taxRate).value) != 0) {
                    return@withContext Result.failure(IllegalArgumentException("Item tax does not match active lease tax rate"))
                }
                val leaseTaxCalc = lease.taxCalculation ?: "exclusive"
                if (basketItem.isTaxInclusive != (leaseTaxCalc == "inclusive")) {
                    return@withContext Result.failure(IllegalArgumentException("Item tax calculation does not match active lease"))
                }
                lineTaxRateId = lease.taxRateId
                lineTaxRate = requireNotNull(lease.taxRate)
                lineTaxInclusive = basketItem.isTaxInclusive
            }

            val lineResult = basketItem.calculateLine(lease.currencyMinorUnits)
            lines.add(
                OfflineSaleLineV2(
                    productId = prodUlid,
                    productUnitId = productUnitUlid,
                    unitId = productUnitUlid,
                    conversionToBase = PositiveDecimalQuantity(basketItem.product.conversionToBaseExact.setScale(6, RoundingMode.UNNECESSARY)),
                    quantity = PositiveDecimalQuantity(basketItem.quantity.setScale(6, RoundingMode.UNNECESSARY)),
                    unitPrice = MoneyAmount(basketItem.unitPriceExact.setScale(6, RoundingMode.UNNECESSARY)),
                    taxRateId = lineTaxRateId,
                    taxRate = lineTaxRate,
                    taxInclusive = lineTaxInclusive,
                    taxAmount = MoneyAmount(lineResult.taxAmount.value.setScale(6, RoundingMode.UNNECESSARY)),
                    lineTotal = MoneyAmount(lineResult.lineTotal.value.setScale(6, RoundingMode.UNNECESSARY))
                )
            )
        }

        val expectedTotal = lines.fold(BigDecimal.ZERO) { sum, line -> sum.add(line.lineTotal.value) }
        if (netTotalExact.compareTo(expectedTotal) != 0 || invoice.subtotalExact.compareTo(expectedTotal) != 0) {
            return@withContext Result.failure(IllegalArgumentException("Sale amounts do not match the calculated line total"))
        }

        if (!PaymentDecimalConsistency.fitsCashEntry(tenderedExact, lease.currencyMinorUnits) ||
            listOf(netTotalExact, changeReturnedExact).any {
                it < BigDecimal.ZERO || it.stripTrailingZeros().scale() > lease.currencyMinorUnits
            }) {
            return@withContext Result.failure(IllegalArgumentException("Cash amounts do not conform to currency minor units (${lease.currencyMinorUnits})"))
        }

        val payment = OfflineSalePaymentV2(
            mode = "full",
            paymentMethod = "cash",
            cashAccountId = lease.cashAccountId,
            amountTendered = MoneyAmount(tenderedExact.setScale(6, RoundingMode.UNNECESSARY)),
            amountSettled = MoneyAmount(netTotalExact.setScale(6, RoundingMode.UNNECESSARY)),
            changeReturned = MoneyAmount(changeReturnedExact.setScale(6, RoundingMode.UNNECESSARY))
        )

        val clientEventId = if (!invoice.clientEventId.isNullOrBlank() && ClientUuid.isValid(invoice.clientEventId)) {
            ClientUuid.parse(requireNotNull(invoice.clientEventId))
        } else {
            ClientUuid.random()
        }

        val localReceiptNumber = "LOCAL-${pendingCount + 1}"
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val capturedAt = sdf.format(Date(offlineLeaseStore.trustedTime(lease) ?: clock()))

        val event = OfflineSaleEventV2(
            clientEventId = clientEventId,
            eventVersion = 2,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            deviceLeaseId = lease.leaseId,
            capturedAt = capturedAt,
            customerId = targetCustomerId,
            currency = lease.currency,
            warehouseId = lease.warehouseId,
            cashAccountId = lease.cashAccountId,
            catalogSnapshotVersion = lease.catalogSnapshotVersion,
            localReceiptNumber = localReceiptNumber,
            lines = lines,
            payment = payment
        )

        val canonicalBytes = CanonicalJsonSerializer.serialize(event)
        val canonicalByteArray = canonicalBytes.toByteArray(StandardCharsets.UTF_8)
        val digest = event.computePayloadDigest()

        val captureResult = try {
            outbox.captureLeaseSale(lease, event) {
                requireOfflineScope()
                require(getActiveOfflineSaleLease()?.leaseId == lease.leaseId) { "Offline lease expired" }
            }
        } catch (error: Exception) { return@withContext Result.failure(error) }


        when (captureResult) {
            is CaptureResult.Conflict ->
                return@withContext Result.failure(IllegalStateException("Local outbox conflict for event ${clientEventId.value}"))
            is CaptureResult.ScopeRejected ->
                return@withContext Result.failure(SecurityException("Outbox scope rejected: ${captureResult.message}"))
            is CaptureResult.Created, is CaptureResult.Duplicate -> {
                val record = checkNotNull(outbox.getRecord(companyId, actorId, deviceId, clientEventId))
                val capturedTaxExact = lines.fold(BigDecimal.ZERO) { sum, line -> sum.add(line.taxAmount.value) }
                val offlineInvoice = invoice.copy(
                    timestamp = record.event.capturedAt,
                    id = clientEventId.value,
                    clientEventId = clientEventId.value,
                    localReceiptNumber = OfflineSaleEventV2.fromStrictJson(StrictJsonParser.parseObject(String(record.payloadBytes, StandardCharsets.UTF_8))).localReceiptNumber,
                    serverInvoiceUlid = null,
                    serverInvoiceNumber = null,
                    serverPaymentUlid = null,
                    serverPaymentReceiptNumber = null,
                    outboxStatus = record.deliveryState.status,
                    salePayloadDigest = record.payloadDigest,
                    hasLocalTenderDetails = true,
                    currency = event.currency,
                    currencyMinorUnits = lease.currencyMinorUnits,
                    taxExact = capturedTaxExact
                )
                Result.success(publishSale(offlineInvoice, offline = true))
            }
        }
    }

    data class OfflineQueueSummary(val count: Int, val total: BigDecimal, val rejected: List<InvoiceRecord>)

    @Volatile private var offlineQueueSummaryCache: Pair<List<OutboxRecord>, OfflineQueueSummary>? = null

    suspend fun offlineQueueSummary(): OfflineQueueSummary = withContext(ioDispatcher) {
        requireOfflineScope()
        val records = outboxRepository?.getOfflineQueueRecords(companyId, actorId, deviceId).orEmpty()
        offlineQueueSummaryCache?.takeIf { it.first === records }?.let { return@withContext it.second }
        var total = BigDecimal.ZERO
        val rejected = ArrayList<InvoiceRecord>()
        for (record in records) {
            val event = OfflineSaleEventV2.fromStrictJson(StrictJsonParser.parseObject(String(record.payloadBytes, StandardCharsets.UTF_8)))
            total = total.add(event.payment.amountSettled.value)
            if (record.status == OutboxStatus.CONFLICT) rejected.add(heldInvoice(record).withOutboxState(record))
        }
        requireOfflineScope()
        OfflineQueueSummary(records.size, total, rejected).also { offlineQueueSummaryCache = records to it }
    }

    fun getPendingOfflineSales(): List<InvoiceRecord> {
        val outbox = outboxRepository ?: return emptyList()
        val records = outbox.getOfflineQueueRecords(companyId, actorId, deviceId)
        return records.filter { it.event.deviceLeaseId != null && it.status != OutboxStatus.CONFIRMED }
            .map { heldInvoice(it).withOutboxState(it) }
    }

    fun getRejectedCashSales(): List<InvoiceRecord> {
        val outbox = outboxRepository ?: return emptyList()
        val records = outbox.getOfflineQueueRecords(companyId, actorId, deviceId)
        return records.filter { it.event.deviceLeaseId != null && it.status == OutboxStatus.CONFLICT }
            .map { heldInvoice(it).withOutboxState(it) }
    }

    fun getPendingOfflineSalesCount(): Int {
        val outbox = outboxRepository ?: return 0
        val records = outbox.getOfflineQueueRecords(companyId, actorId, deviceId)
        return records.count { it.event.deviceLeaseId != null && it.status != OutboxStatus.CONFIRMED }
    }

    fun getPendingOfflineSalesTotal(): BigDecimal {
        val outbox = outboxRepository ?: return BigDecimal.ZERO
        val records = outbox.getOfflineQueueRecords(companyId, actorId, deviceId)
        val raw = records.filter { it.event.deviceLeaseId != null && it.status != OutboxStatus.CONFIRMED }
            .fold(BigDecimal.ZERO) { acc, record ->
                try {
                    val root = StrictJsonParser.parseObject(String(record.payloadBytes, StandardCharsets.UTF_8))
                    val event = OfflineSaleEventV2.fromStrictJson(root)
                    acc.add(event.payment.amountSettled.value)
                } catch (_: Exception) {
                    acc
                }
            }
        return if (raw.signum() == 0) {
            BigDecimal.ZERO
        } else {
            raw.setScale(currencyMinorUnits, RoundingMode.HALF_UP)
        }
    }

    suspend fun syncOfflineSales(batchSize: Int = 10): Result<SyncBatchResult> = withContext(ioDispatcher) {
        try {
            requireActiveScope()
            val outbox = outboxRepository ?: return@withContext Result.failure(IllegalStateException("OutboxRepository not configured"))
            val gateway = saleSyncGateway ?: return@withContext Result.failure(IllegalStateException("SaleSyncGateway not configured"))
            val coordinator = syncCoordinator ?: SaleSyncCoordinatorImpl(outbox, gateway)
            val result = coordinator.synchronizeScope(companyId, actorId, deviceId, batchSize = batchSize)
            val records = outbox.getRecordsByScope(companyId, actorId, deviceId)
            for (record in records) {
                if (record.status == OutboxStatus.CONFIRMED) {
                    val inv = heldInvoice(record).withOutboxState(record)
                    publishSale(inv)
                }
            }
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun recordSale(invoice: InvoiceRecord): Result<InvoiceRecord> = withContext(ioDispatcher) {
        saleMutex.withLock { recordSaleLocked(invoice) }
    }

    private suspend fun recordSaleLocked(invoice: InvoiceRecord): Result<InvoiceRecord> = withContext(ioDispatcher) {
        try { requireActiveScope() } catch (denied: SecurityException) { return@withContext Result.failure(denied) }

        if (isOfflineSalesCapabilityEnabled && isNetworkOffline) {
            return@withContext recordOfflineSaleLocked(invoice)
        }

        // 1. Line item validation
        if (invoice.items.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Invoice must contain at least one item."))
        }
        for (item in invoice.items) {
            if (item.quantity <= BigDecimal.ZERO) {
                return@withContext Result.failure(IllegalArgumentException("Item quantity must be > 0."))
            }
            if (item.unitPriceExact < BigDecimal.ZERO) {
                return@withContext Result.failure(IllegalArgumentException("Item price cannot be negative."))
            }
        }

        // 2. Exact decimal validation & tender conservation
        val tenderedExact = invoice.cashReceivedExact
        val netTotalExact = invoice.netTotalExact
        val remainingDebitExact = invoice.remainingDebitExact
        val changeReturnedExact = invoice.changeReturnedExact

        if (netTotalExact <= BigDecimal.ZERO) {
            return@withContext Result.failure(IllegalArgumentException("Grand total must be strictly positive."))
        }

        if (invoice.normalizedCurrency != null && !invoice.normalizedCurrency.equals(baseCurrency, ignoreCase = true)) {
            return@withContext Result.failure(
                IllegalArgumentException("Invoice currency (${invoice.normalizedCurrency}) does not match active company currency ($baseCurrency)")
            )
        }

        if (invoice.isPaidInFull) {
            val expectedTender = netTotalExact.add(changeReturnedExact)
            if (tenderedExact.compareTo(expectedTender) != 0) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "Cash tender conservation violated: tendered ($tenderedExact) != netTotal ($netTotalExact) + change ($changeReturnedExact)"
                    )
                )
            }
        }

        // 2b. Rejection of unsupported nonzero discounts for production V2 sale dispatch
        if (invoice.discountExact.compareTo(BigDecimal.ZERO) != 0) {
            return@withContext Result.failure(
                IllegalArgumentException(
                    "الخصومات غير مدعومة في إرسال المبيعات الحالي | Unsupported nonzero discount in production V2 sale dispatch: ${invoice.discountExact.toPlainString()}"
                )
            )
        }

        // 3. Construct OnlineSaleIntentProfile
        // Maintain existing clientEventId across retry of same UI sale; never generate a new UUID on retry!
        val clientEventId = if (!invoice.clientEventId.isNullOrBlank() && ClientUuid.isValid(invoice.clientEventId)) {
            ClientUuid.parse(requireNotNull(invoice.clientEventId))
        } else {
            ClientUuid.random()
        }

        val targetCustomerId = if (invoice.customerId != null && ServerUlid.isValid(invoice.customerId)) {
            ServerUlid.parse(invoice.customerId)
        } else {
            walkInCustomerId
        }

        val lines = ArrayList<OnlineSaleIntentLine>(invoice.items.size)
        for (basketItem in invoice.items) {
            val prodUlid = if (ServerUlid.isValid(basketItem.product.id)) {
                ServerUlid.parse(basketItem.product.id)
            } else {
                return@withContext Result.failure(IllegalArgumentException("Cannot record sale: product '${basketItem.product.nameAr}' lacks a valid server ULID."))
            }

            // Resolve packaging product unit from product; never fall back to product ULID
            val packagingUnitStr = basketItem.product.productUnitId
            if (packagingUnitStr.isNullOrBlank() || !ServerUlid.isValid(packagingUnitStr)) {
                return@withContext Result.failure(IllegalArgumentException("Cannot record sale: product '${basketItem.product.nameAr}' lacks a valid packaging product_unit_id."))
            }
            val productUnitUlid = ServerUlid.parse(packagingUnitStr)

            if (!basketItem.hasResolvedTax) {
                return@withContext Result.failure(IllegalArgumentException(
                    "اختر ضريبة كل صنف | Select a tax treatment for each item."
                ))
            }
            val rate = requireNotNull(basketItem.taxRateExact)
            val taxRateId = basketItem.taxRateId?.let { id ->
                val tax = bootstrapTaxes.singleOrNull { it.id.value == id }
                if (tax == null || rate.compareTo(tax.rate.value) != 0 ||
                    basketItem.isTaxInclusive != (tax.calculation == "inclusive")
                ) return@withContext Result.failure(IllegalArgumentException("Selected tax does not match company tax settings"))
                tax.id
            }
            lines.add(
                OnlineSaleIntentLine(
                    productId = prodUlid,
                    productUnitId = productUnitUlid,
                    conversionToBase = PositiveDecimalQuantity(basketItem.product.conversionToBaseExact.setScale(6, RoundingMode.UNNECESSARY)),
                    quantity = PositiveDecimalQuantity(basketItem.quantity.setScale(6, RoundingMode.UNNECESSARY)),
                    unitPrice = MoneyAmount(basketItem.unitPriceExact.setScale(6, RoundingMode.UNNECESSARY)),
                    taxRateId = taxRateId,
                    taxRate = TaxPercentage(rate.setScale(6, RoundingMode.UNNECESSARY)),
                    taxInclusive = basketItem.isTaxInclusive
                )
            )
        }

        val expectedTotal = invoice.items.fold(BigDecimal.ZERO) { sum, item ->
            sum + item.calculateLine(currencyMinorUnits).lineTotal.value
        }
        val settled = tenderedExact.min(expectedTotal)
        if (netTotalExact.compareTo(expectedTotal) != 0 || invoice.subtotalExact.compareTo(expectedTotal) != 0 ||
            tenderedExact.signum() < 0 || changeReturnedExact.signum() < 0 ||
            remainingDebitExact.compareTo(expectedTotal - settled) != 0 ||
            (tenderedExact > BigDecimal.ZERO &&
                (approvedCashAccount.currency != baseCurrency ||
                    listOf(tenderedExact, settled, changeReturnedExact).any {
                        !PaymentDecimalConsistency.fitsCashEntry(it, currencyMinorUnits)
                    })) ||
            (!invoice.isPaidInFull && (tenderedExact >= expectedTotal || changeReturnedExact.signum() != 0))
        ) return@withContext Result.failure(IllegalArgumentException("Sale amounts do not match the calculated total or cash precision"))

        val settlementMode = if (invoice.isPaidInFull) "full" else if (tenderedExact > BigDecimal.ZERO) "partial" else "unpaid"
        val paymentAmountExact = settled.setScale(6, RoundingMode.UNNECESSARY).toPlainString()
        val payment = OnlineSaleIntentPayment(
            settlementMode = settlementMode,
            paymentMethod = if (settlementMode != "unpaid") "cash" else null,
            cashAccountId = if (settlementMode != "unpaid") approvedCashAccount.id else null,
            amount = MoneyAmount.fromNonnegativeString(paymentAmountExact)
        )

        val profile = OnlineSaleIntentProfile(
            actorId = actorId,
            clientEventId = clientEventId,
            companyId = companyId,
            currency = baseCurrency,
            currencyMinorUnits = currencyMinorUnits,
            customerId = targetCustomerId,
            deviceId = deviceId,
            lines = lines,
            payment = payment,
            warehouseId = defaultWarehouse.id
        )

        // 4. Canonical JCS serialization & digest calculation BEFORE dispatch
        val canonicalBytes = CanonicalJsonSerializer.serialize(profile)
        val canonicalByteArray = canonicalBytes.toByteArray(StandardCharsets.UTF_8)
        val digest = profile.computePayloadDigest()
        val outbox = outboxRepository ?: return@withContext Result.failure(IllegalStateException("OutboxRepository not configured"))
        val gateway = saleSyncGateway ?: return@withContext Result.failure(IllegalStateException("SaleSyncGateway not configured"))

        val held = heldOnlineSale()
        if (held != null && held.clientEventId != clientEventId.value) {
            return@withContext Result.failure(IllegalStateException("A sale is pending verification"))
        }
        // Re-entering this method is never permission to dispatch an existing held sale.
        val existing = outbox.getRecord(companyId, actorId, deviceId, clientEventId)
        if (existing != null) {
            if (existing.payloadDigest != digest.value) {
                return@withContext Result.failure(IllegalStateException("Sale payload differs from the retained event"))
            }
            return@withContext Result.success(publishSale(invoice.withOutboxState(existing)))
        }

        val captureResult = outbox.captureOnlineSaleIntent(
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            currencyMinorUnits = currencyMinorUnits,
            profile = profile,
            rawCanonicalProfileUtf8 = canonicalByteArray,
            requireNoOtherHeldSale = true
        )
        when (captureResult) {
            is CaptureResult.Conflict ->
                return@withContext Result.failure(IllegalStateException("Local outbox conflict for event ${clientEventId.value}"))
            is CaptureResult.ScopeRejected ->
                return@withContext Result.failure(SecurityException("Outbox scope rejected: ${captureResult.message}"))
            is CaptureResult.Created -> {
                requireActiveScope()
                val coordinator = syncCoordinator ?: SaleSyncCoordinatorImpl(outbox, gateway)
                coordinator.synchronizeScope(companyId, actorId, deviceId, batchSize = 1, onlyEventId = clientEventId)
            }
            is CaptureResult.Duplicate -> Unit
        }

        val intentTaxExact = lines.fold(BigDecimal.ZERO) { sum, line ->
            sum.add(
                com.example.data.mobile.contract.SalesLineCalculator.calculate(
                    quantity = line.quantity,
                    unitPrice = line.unitPrice,
                    taxPercentage = line.taxRate,
                    taxInclusive = line.taxInclusive,
                    minorUnits = profile.currencyMinorUnits
                ).taxAmount.value
            )
        }
        val record = checkNotNull(outbox.getRecord(companyId, actorId, deviceId, clientEventId))
        Result.success(
            publishSale(
                invoice.copy(
                    currency = profile.currency,
                    currencyMinorUnits = profile.currencyMinorUnits,
                    taxExact = invoice.taxExact ?: intentTaxExact
                ).withOutboxState(record)
            )
        )
    }

    private fun mergeServerInvoices(serverList: List<SalesInvoiceListItem>, locals: List<InvoiceRecord>): List<InvoiceRecord> {
        requireActiveScope()
        val mappings = outboxRepository?.confirmedInvoiceMappings(
            companyId, actorId, deviceId, serverList.map { it.id.value }
        ).orEmpty().associateBy { it.invoiceUlid }
        val cached = locals.filter { it.serverInvoiceUlid != null }.associateBy { it.serverInvoiceUlid }
        requireActiveScope()
        return serverList.map { item ->
            val mapping = mappings[item.id.value]
            val local = cached[item.id.value]?.takeIf { it.clientEventId == mapping?.clientEventId }
            val serverInvoice = MerchantReadAdapter.toDomainInvoiceRecord(item, local)
            if (mapping == null) serverInvoice else serverInvoice.copy(
                clientEventId = mapping.clientEventId,
                serverPaymentUlid = mapping.paymentUlid,
                serverPostedAt = mapping.serverAcknowledgedAt,
                salePayloadDigest = mapping.payloadDigest
            )
        }
    }

    override suspend fun refreshInvoices(query: SalesInvoicesQuery): Result<List<InvoiceRecord>> = withContext(ioDispatcher) {
        val client = merchantReadClient ?: return@withContext Result.failure(IllegalStateException("MerchantReadClient not configured"))
        _isInvoicesLoadingFlow.value = true
        activeInvoicesQuery = query
        activeInvoicesGeneration = retainedGeneration
        activeInvoicesCompanyId = companyId

        val startGen = retainedGeneration
        val startCompanyId = companyId
        val startQuery = query

        try {
            val res = client.getSalesInvoices(companyId, query, retainedGeneration = retainedGeneration)
            if (retainedGeneration != startGen || companyId != startCompanyId || activeInvoicesQuery != startQuery) {
                return@withContext Result.failure(IllegalStateException("Stale invoices refresh completion ignored"))
            }

            when (res) {
                is MerchantReadResult.Success -> {
                    val serverList = res.value.invoices
                    _serverInvoicesFlow.value = serverList
                    _invoicesNextCursorFlow.value = res.value.nextCursor
                    _invoicesHasMoreFlow.value = res.value.hasMore
                    _invoicesHasMoreGapFlow.value = res.value.hasMore

                    saleMutex.withLock {
                        val currentLocal = _invoicesFlow.value
                        val pending = currentLocal.filter { it.clientEventId != null && it.outboxStatus != "CONFIRMED" }
                        val mergedList = pending + mergeServerInvoices(serverList, currentLocal)
                        _invoicesFlow.value = mergedList
                        Result.success(mergedList)
                    }
                }
                is MerchantReadResult.Forbidden -> {
                    Result.failure(SecurityException("Forbidden: ${res.error?.message ?: "Missing sales.invoice.view permission"}"))
                }
                is MerchantReadResult.AuthDenial -> {
                    Result.failure(SecurityException("Authentication denied: ${res.error?.message ?: "UNAUTHENTICATED"}"))
                }
                is MerchantReadResult.NetworkError -> {
                    Result.failure(java.io.IOException("Network error during sales invoices refresh: ${res.message}"))
                }
                is MerchantReadResult.SchemaError -> {
                    Result.failure(IllegalStateException("Schema validation error in sales invoices: ${res.message}"))
                }
                is MerchantReadResult.HttpError -> {
                    Result.failure(RuntimeException("HTTP error ${res.statusCode}: ${res.error?.message}"))
                }
                else -> {
                    Result.failure(RuntimeException("Failed to refresh sales invoices: $res"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isInvoicesLoadingFlow.value = false
        }
    }

    override suspend fun loadMoreInvoices(): Result<List<InvoiceRecord>> = withContext(ioDispatcher) {
        val client = merchantReadClient ?: return@withContext Result.failure(IllegalStateException("MerchantReadClient not configured"))
        if (_isInvoicesLoadingFlow.value) {
            return@withContext Result.success(_invoicesFlow.value)
        }
        val nextCursor = _invoicesNextCursorFlow.value
        if (!_invoicesHasMoreFlow.value || nextCursor.isNullOrBlank()) {
            return@withContext Result.success(_invoicesFlow.value)
        }

        _isInvoicesLoadingFlow.value = true
        val startGen = retainedGeneration
        val startCompanyId = companyId
        val startQuery = activeInvoicesQuery
        val loadMoreQuery = startQuery.copy(cursor = nextCursor)

        try {
            val res = client.getSalesInvoices(companyId, loadMoreQuery, retainedGeneration = startGen)
            if (retainedGeneration != startGen || companyId != startCompanyId || activeInvoicesQuery != startQuery) {
                return@withContext Result.failure(IllegalStateException("Stale load more completion ignored due to scope/filter change"))
            }

            when (res) {
                is MerchantReadResult.Success -> {
                    val newServerList = res.value.invoices
                    val existingServerList = _serverInvoicesFlow.value

                    val existingServerIds = existingServerList.map { it.id.value }.toSet()
                    val dedupedNewServerList = newServerList.filter { !existingServerIds.contains(it.id.value) }
                    val combinedServerList = existingServerList + dedupedNewServerList
                    _serverInvoicesFlow.value = combinedServerList

                    _invoicesNextCursorFlow.value = res.value.nextCursor
                    _invoicesHasMoreFlow.value = res.value.hasMore
                    _invoicesHasMoreGapFlow.value = res.value.hasMore

                    saleMutex.withLock {
                        val currentLocal = _invoicesFlow.value
                        val updatedList = currentLocal + mergeServerInvoices(dedupedNewServerList, currentLocal)
                            .filter { server -> currentLocal.none { it.serverInvoiceUlid == server.serverInvoiceUlid } }
                        _invoicesFlow.value = updatedList
                        Result.success(updatedList)
                    }
                }
                is MerchantReadResult.Forbidden -> {
                    Result.failure(SecurityException("Forbidden: ${res.error?.message ?: "Missing sales.invoice.view permission"}"))
                }
                is MerchantReadResult.AuthDenial -> {
                    Result.failure(SecurityException("Authentication denied: ${res.error?.message ?: "UNAUTHENTICATED"}"))
                }
                is MerchantReadResult.NetworkError -> {
                    Result.failure(java.io.IOException("Network error during load more invoices: ${res.message}"))
                }
                is MerchantReadResult.SchemaError -> {
                    Result.failure(IllegalStateException("Schema validation error in load more invoices: ${res.message}"))
                }
                is MerchantReadResult.HttpError -> {
                    Result.failure(RuntimeException("HTTP error ${res.statusCode}: ${res.error?.message}"))
                }
                else -> {
                    Result.failure(RuntimeException("Failed to load more invoices: $res"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isInvoicesLoadingFlow.value = false
        }
    }

    override suspend fun getSalesInvoiceDetail(invoicePublicId: String): Result<SalesInvoiceDetailResponse> = withContext(ioDispatcher) {
        val client = merchantReadClient ?: return@withContext Result.failure(IllegalStateException("MerchantReadClient not configured"))
        val ulid = try {
            ServerUlid.parse(invoicePublicId)
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Invalid invoice ULID: '$invoicePublicId'", e))
        }
        try {
            val res = client.getSalesInvoiceDetail(companyId, ulid, retainedGeneration = retainedGeneration)
            when (res) {
                is MerchantReadResult.Success -> Result.success(res.value)
                is MerchantReadResult.NotFound -> Result.failure(NoSuchElementException("Invoice '$invoicePublicId' not found on server"))
                is MerchantReadResult.Forbidden -> Result.failure(SecurityException("Forbidden: ${res.error?.message ?: "Missing sales.invoice.view permission"}"))
                is MerchantReadResult.AuthDenial -> Result.failure(SecurityException("Authentication denied: ${res.error?.message ?: "UNAUTHENTICATED"}"))
                is MerchantReadResult.NetworkError -> Result.failure(java.io.IOException("Network error loading invoice detail: ${res.message}"))
                is MerchantReadResult.SchemaError -> Result.failure(IllegalStateException("Schema error in invoice detail: ${res.message}"))
                else -> Result.failure(RuntimeException("Failed to load invoice detail: $res"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // Customers & Balances
    // -------------------------------------------------------------------------

    override fun getCustomers(): List<Customer> = _customersFlow.value

    override fun getCustomerTransactions(): List<CustomerTransaction> = _customerTransactionsFlow.value

    override suspend fun getCustomerSettlementTargets(
        customerId: String,
        currency: String?,
        limit: Int,
        cursor: String?
    ): Result<SettlementTargetsResponse> = withContext(ioDispatcher) {
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val before = requireActiveScope()
        if (!ServerUlid.isValid(customerId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid customer ULID: $customerId"))
        }
        try {
            val res = apiClient.getCustomerSettlementTargets(
                companyId = companyId,
                customerId = ServerUlid.parse(customerId),
                currency = currency,
                limit = limit,
                cursor = cursor
            )
            if (res.isSuccess) {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
                check(requireActiveScope() == before) { "Session changed during customer settlement targets read" }
                val parsed = parseSettlementTargetsResponse(res.getOrThrow(), isSupplier = false)
                if (currency == baseCurrency) {
                    require(parsed.targets.all { it.currency == baseCurrency && it.currencyMinorUnits == currencyMinorUnits })
                }
                Result.success(parsed)
            } else {
                val ex = res.exceptionOrNull() ?: RuntimeException("Failed to read customer settlement targets")
                Result.failure(ex)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    override suspend fun recordCustomerPayment(
        customerId: String,
        amountIls: Double,
        note: String,
        paymentId: String
    ): Result<CustomerTransaction> = recordCustomerPayment(
        customerId = customerId,
        amount = BigDecimal(amountIls.toString()),
        note = note,
        paymentId = paymentId,
        cashAccountId = null,
        paymentDate = null,
        allocations = emptyList()
    )

    override suspend fun recordCustomerPayment(
        customerId: String,
        amount: BigDecimal,
        note: String,
        paymentId: String,
        cashAccountId: String?,
        paymentDate: String?,
        allocations: List<PaymentAllocationInput>
    ): Result<CustomerTransaction> = withContext(ioDispatcher) {
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val before = requireActiveScope()
        if (!ServerUlid.isValid(customerId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid customer ULID: $customerId"))
        }
        val amountExact = try {
            amount.setScale(currencyMinorUnits, RoundingMode.UNNECESSARY)
        } catch (e: ArithmeticException) {
            return@withContext Result.failure(IllegalArgumentException("Payment amount $amount exceeds allowed precision of $currencyMinorUnits minor units for $baseCurrency"))
        }
        if (amountExact <= BigDecimal.ZERO) {
            return@withContext Result.failure(IllegalArgumentException("Payment amount must be strictly positive"))
        }

        var totalAllocated = BigDecimal.ZERO
        val targetSet = mutableSetOf<String>()
        for (alloc in allocations) {
            if (alloc.amountExact <= BigDecimal.ZERO) {
                return@withContext Result.failure(IllegalArgumentException("Allocation amount must be strictly positive"))
            }
            try {
                alloc.amountExact.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY)
            } catch (e: ArithmeticException) {
                return@withContext Result.failure(IllegalArgumentException("Allocation amount ${alloc.amountExact} exceeds allowed precision of ${ContractConstants.DECIMAL_SCALE} decimal places"))
            }
            val key = "${alloc.targetType}:${alloc.targetId}"
            if (!targetSet.add(key)) {
                return@withContext Result.failure(IllegalArgumentException("Duplicate allocation target detected: $key"))
            }
            totalAllocated = totalAllocated.add(alloc.amountExact)
        }
        if (totalAllocated > amountExact) {
            return@withContext Result.failure(IllegalArgumentException("Allocations ($totalAllocated) exceed total payment amount ($amountExact)"))
        }

        val preparedRes = prepareDurablePaymentSubmission(
            scope = before,
            formType = "customer_payment",
            keyPrefix = "custpay",
            partyFieldName = "customer_id",
            partyId = customerId,
            requestedPaymentId = paymentId,
            amountExact = amountExact,
            note = note,
            cashAccountId = cashAccountId,
            paymentDate = paymentDate,
            allocations = allocations
        )
        if (preparedRes.isFailure) {
            return@withContext Result.failure(preparedRes.exceptionOrNull() ?: IllegalStateException("Payment preparation failed"))
        }
        val prepared = preparedRes.getOrThrow()
        val commandId = prepared.commandId
        val idempotencyKey = prepared.idempotencyKey
        val effectivePaymentDate = prepared.effectivePaymentDate
        val effectiveCashAccount = prepared.effectiveCashAccount
        val payloadBytes = prepared.payloadBytes

        val inFlightCounter = customerInFlightPayments.computeIfAbsent(customerId) { java.util.concurrent.atomic.AtomicInteger(0) }
        inFlightCounter.incrementAndGet()
        val postPaymentRevision = incrementCustomerRevision(customerId)

        val postCall: suspend (String) -> Result<StrictJsonObject> = { key ->
            apiClient.recordCustomerPayment(
                companyId = companyId,
                customerId = ServerUlid.parse(customerId),
                amount = amountExact,
                cashAccountId = effectiveCashAccount,
                note = note,
                idempotencyKey = key,
                paymentDate = effectivePaymentDate,
                allocations = allocations,
                currency = baseCurrency
            )
        }

        val validatePaymentResponse: (StrictJsonObject) -> Result<StrictJsonObject> = { snap ->
            validatePaymentSnapshotObject(
                snap = snap,
                isSupplier = false,
                expectedPartyId = customerId,
                expectedAmount = amountExact,
                expectedAllocated = totalAllocated,
                defaultAdvance = allocations.isEmpty()
            )
        }

        try {
            val durableRes = executeDurableCommand(
                commandType = "customer.payment",
                commandId = commandId,
                idempotencyKey = idempotencyKey,
                canonicalPayloadBytes = payloadBytes,
                isReceiptStatusChecked = true,
                validateResponse = validatePaymentResponse,
                postAction = postCall
            )

            if (durableRes.isFailure) {
                return@withContext Result.failure(durableRes.exceptionOrNull() ?: RuntimeException("Customer payment failed"))
            }

            if (prepared.activeDraft != null || commandId == prepared.persistedDraftId) {
                rotateDraftOperationId("customer_payment")
            }

            val snapshot = durableRes.getOrThrow()
            val officialId = snapshot.properties["id"].toNullableString()
                ?: snapshot.properties["receipt_id"].toNullableString()
                ?: return@withContext Result.failure(IllegalStateException("Server did not return payment ID"))
            val parsedFields = parseCanonicalPaymentFields(
                snapshot = snapshot,
                fallbackAmount = amountExact,
                fallbackAllocated = totalAllocated,
                defaultAdvance = allocations.isEmpty()
            )
            val officialAmount = parsedFields.officialAmount
            val postedAt = snapshot.properties["posted_at"].toNullableString() ?: effectivePaymentDate

            val isAdvance = parsedFields.isAdvance
            val allocatedAmount = parsedFields.allocatedAmount
            val unallocatedAmount = parsedFields.unallocatedAmount
            val parsedAllocations = parsedFields.allocations

            // Authoritative customer detail from server with strict session recheck
            val snapshotBalance = snapshot.properties["balance"].toNullableBigDecimal()
                ?: snapshot.properties["running_balance"].toNullableBigDecimal()

            val getCustomerRes = if (snapshotBalance == null) {
                val beforeFollowup = requireActiveScope()
                val res = apiClient.getCustomer(companyId, ServerUlid.parse(customerId))
                if (requireActiveScope() != beforeFollowup) {
                    throw SecurityException("Authenticated scope changed during customer balance refresh")
                }
                res
            } else null

            val refreshFailed = getCustomerRes != null && getCustomerRes.isFailure
            val serverCustomerObj = getCustomerRes?.getOrNull()

            val authoritativeBalanceExact: BigDecimal? = synchronized(getCustomerLock(customerId)) {
                val currentRevision = getCustomerRevision(customerId)
                val isLatest = (currentRevision == postPaymentRevision) || (inFlightCounter.get() == 1 && currentRevision <= postPaymentRevision)
                val authBalance = snapshotBalance
                    ?: serverCustomerObj?.properties?.get("balance").toNullableBigDecimal()
                    ?: serverCustomerObj?.properties?.get("balance_ils").toNullableBigDecimal()

                if (isLatest) {
                    incrementCustomerRevision(customerId)
                    val publishedBalance = if (refreshFailed) null else authBalance
                    _customersFlow.update { list ->
                        list.map { c ->
                            if (c.id == customerId) c.copy(balanceExact = publishedBalance) else c
                        }
                    }
                    publishedBalance
                } else {
                    // Older payment cannot clear newer state! Leave existing state in _customersFlow
                    _customersFlow.value.firstOrNull { it.id == customerId }?.balanceExact
                }
            }

            val tx = CustomerTransaction(
                id = officialId,
                customerId = customerId,
                date = postedAt,
                typeAr = if (isAdvance) "دفعة مقدمة غير مخصصة" else "دفعة نقدية مقبوضة",
                typeEn = if (isAdvance) "Unallocated Advance Receipt" else "Cash Collection",
                debitExact = BigDecimal.ZERO,
                creditExact = officialAmount,
                runningBalanceExact = authoritativeBalanceExact,
                note = note,
                isAdvance = isAdvance,
                allocatedAmountExact = allocatedAmount,
                unallocatedAmountExact = unallocatedAmount,
                allocations = parsedAllocations,
                currency = snapshot.properties["currency"].toNullableString() ?: baseCurrency
            )
            _customerTransactionsFlow.update { history ->
                if (history.any { it.id == tx.id }) history else listOf(tx) + history
            }
            Result.success(tx)
        } finally {
            inFlightCounter.decrementAndGet()
        }
    }

    override suspend fun createCustomer(
        name: String,
        phone: String,
        address: String,
        creditLimitIls: Double,
        operationId: String
    ): Result<Customer> = withContext(ioDispatcher) {
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Customer name is required | اسم الزبون مطلوب"))
        }
        val cleanPhone = phone.trim().ifBlank { null }
        val cleanAddress = address.trim().ifBlank { null }
        val creditLimitExact = if (creditLimitIls >= 0.0) {
            BigDecimal(creditLimitIls.toString()).setScale(currencyMinorUnits, RoundingMode.HALF_UP)
        } else null

        val commandId = if (operationId.isNotBlank()) operationId else getDraftOperationId("customer_create")
        val idempotencyKey = "custcreate-$commandId"

        val payloadMap = mutableMapOf<String, com.example.data.mobile.contract.StrictJsonElement>(
            "name_ar" to com.example.data.mobile.contract.StrictJsonString(cleanName)
        )
        if (cleanPhone != null) payloadMap["phone"] = com.example.data.mobile.contract.StrictJsonString(cleanPhone)
        if (cleanAddress != null) payloadMap["address_ar"] = com.example.data.mobile.contract.StrictJsonString(cleanAddress)
        if (creditLimitExact != null) payloadMap["credit_limit"] = com.example.data.mobile.contract.StrictJsonString(creditLimitExact.toPlainString())
        val payloadObj = com.example.data.mobile.contract.StrictJsonObject(payloadMap)
        val payloadBytes = com.example.data.mobile.contract.CanonicalJsonSerializer.serializeToUtf8Bytes(payloadObj)

        val scope = requireActiveScope()
        val activeDraft = commandStore.getActiveDraftRecord(scope.companyId.value, scope.actorId.value, scope.deviceId.value, "customer_create")
        val effectivePayloadBytes = if (activeDraft != null && activeDraft.commandId == commandId) {
            commandStore.decryptPayload(activeDraft)
        } else {
            payloadBytes
        }

        val validateCustomer: (StrictJsonObject) -> Result<StrictJsonObject> = { obj ->
            val rawId = obj.properties["id"].toNullableString()
            if (rawId == null) {
                Result.failure(IllegalStateException("Server response missing customer ID"))
            } else if (!ServerUlid.isValid(rawId)) {
                Result.failure(IllegalStateException("Server returned invalid customer ULID: '$rawId'"))
            } else {
                Result.success(obj)
            }
        }

        val postCall: suspend (String) -> Result<com.example.data.mobile.contract.StrictJsonObject> = { key ->
            val effectiveObj = if (activeDraft != null && activeDraft.commandId == commandId) {
                try {
                    StrictJsonParser.parse(String(effectivePayloadBytes, StandardCharsets.UTF_8)).asObject()
                } catch (_: Exception) { payloadObj }
            } else payloadObj
            val effName = effectiveObj.properties["name_ar"]?.asString() ?: cleanName
            val effPhone = effectiveObj.properties["phone"]?.asString() ?: cleanPhone
            val effAddress = effectiveObj.properties["address_ar"]?.asString() ?: cleanAddress
            val effLimit = effectiveObj.properties["credit_limit"]?.asString()?.let { BigDecimal(it) } ?: creditLimitExact
            apiClient.createCustomer(
                companyId = companyId,
                nameAr = effName,
                phone = effPhone,
                creditLimit = effLimit,
                address = effAddress,
                idempotencyKey = key
            )
        }

        try {
            val durableRes = executeDurableCommand(
                commandType = "customer_create",
                commandId = commandId,
                idempotencyKey = idempotencyKey,
                canonicalPayloadBytes = effectivePayloadBytes,
                isReceiptStatusChecked = true,
                validateResponse = validateCustomer,
                postAction = postCall
            )

            if (durableRes.isFailure) {
                return@withContext Result.failure(durableRes.exceptionOrNull() ?: RuntimeException("Customer creation failed"))
            }

            rotateDraftOperationId("customer_create")

            val responseObj = durableRes.getOrThrow()
            val rawId = responseObj.properties["id"].toNullableString()
                ?: return@withContext Result.failure(IllegalStateException("Server response missing customer ID"))
            if (!ServerUlid.isValid(rawId)) {
                return@withContext Result.failure(IllegalStateException("Server returned invalid customer ULID: '$rawId'"))
            }

            val authName = responseObj.properties["name"].toNullableString()
                ?: responseObj.properties["name_ar"].toNullableString()
                ?: cleanName
            val authCode = responseObj.properties["code"].toNullableString()
            val authPhone = responseObj.properties["phone"].toNullableString()
            val authAddress = responseObj.properties["address"].toNullableString()
                ?: responseObj.properties["address_ar"].toNullableString()
            val authCreditLimit = if (responseObj.properties.containsKey("credit_limit")) {
                responseObj.properties["credit_limit"].toNullableBigDecimal()
            } else if (responseObj.properties.containsKey("credit_limit_exact")) {
                responseObj.properties["credit_limit_exact"].toNullableBigDecimal()
            } else if (responseObj.properties.containsKey("credit_limit_ils")) {
                responseObj.properties["credit_limit_ils"].toNullableBigDecimal()
            } else {
                creditLimitExact
            }
            val authBalance = responseObj.properties["balance"].toNullableBigDecimal()

            val created = Customer(
                id = rawId,
                code = authCode,
                nameAr = authName,
                nameEn = authName,
                phone = authPhone,
                addressAr = authAddress,
                addressEn = authAddress,
                balanceExact = authBalance,
                creditLimitExact = authCreditLimit,
                totalPurchasesExact = null
            )
            _customersFlow.update { listOf(created) + it }
            Result.success(created)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun createCustomer(name: String, phone: String, address: String, creditLimitIls: Double): Result<Customer> =
        createCustomer(name, phone, address, creditLimitIls, "")

    override suspend fun createCustomer(name: String, phone: String, address: String, operationId: String): Result<Customer> =
        createCustomer(name, phone, address, 1000.0, operationId)

    override suspend fun updateCustomer(
        customerId: String,
        name: String,
        phone: String?,
        address: String?,
        creditLimit: BigDecimal?,
        updatePhone: Boolean,
        updateAddress: Boolean,
        updateCreditLimit: Boolean
    ): Result<Customer> = withContext(ioDispatcher) {
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val existing = _customersFlow.value.firstOrNull { it.id == customerId }
            ?: return@withContext Result.failure(IllegalArgumentException("Customer not found: $customerId"))
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Customer name is required | اسم الزبون مطلوب"))
        }

        try {
            val before = requireActiveScope()
            val response = apiClient.updateCustomer(
                companyId = companyId,
                customerId = ServerUlid.parse(customerId),
                name = cleanName,
                phone = if (updatePhone) phone?.trim() else null,
                address = if (updateAddress) address?.trim() else null,
                creditLimit = if (updateCreditLimit) creditLimit else null,
                updatePhone = updatePhone,
                updateAddress = updateAddress,
                updateCreditLimit = updateCreditLimit
            ).getOrThrow()

            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
            check(requireActiveScope() == before) { "Session changed" }

            val id = response.properties["id"].toNullableString() ?: error("Missing customer ID")
            require(ServerUlid.parse(id).value == customerId) { "Customer ID mismatch" }
            val serverName = response.properties["name"].toNullableString()
                ?: response.properties["name_ar"].toNullableString()
                ?: error("Missing customer name")

            val authoritativePhone = if (response.properties.containsKey("phone")) {
                response.properties["phone"].toNullableString()
            } else {
                existing.phone
            }

            val authoritativeAddressAr = if (response.properties.containsKey("address") || response.properties.containsKey("address_ar")) {
                response.properties["address"].toNullableString() ?: response.properties["address_ar"].toNullableString()
            } else {
                existing.addressAr
            }

            val authoritativeCreditLimit = if (updateCreditLimit) {
                if (response.properties.containsKey("credit_limit")) {
                    val prop = response.properties["credit_limit"]
                    if (prop == null || prop is StrictJsonNull) {
                        null
                    } else {
                        prop.toNullableBigDecimal()
                    }
                } else if (response.properties.containsKey("credit_limit_exact")) {
                    val prop = response.properties["credit_limit_exact"]
                    if (prop == null || prop is StrictJsonNull) null else prop.toNullableBigDecimal()
                } else if (response.properties.containsKey("credit_limit_ils")) {
                    val prop = response.properties["credit_limit_ils"]
                    if (prop == null || prop is StrictJsonNull) null else prop.toNullableBigDecimal()
                } else {
                    creditLimit
                }
            } else {
                existing.creditLimitExact
            }

            val updated = synchronized(getCustomerLock(customerId)) {
                val current = _customersFlow.value.firstOrNull { it.id == customerId } ?: existing
                val merged = current.copy(
                    nameAr = serverName,
                    nameEn = serverName,
                    phone = authoritativePhone,
                    addressAr = authoritativeAddressAr,
                    addressEn = authoritativeAddressAr,
                    creditLimitExact = if (updateCreditLimit) authoritativeCreditLimit else current.creditLimitExact
                )
                if (updateCreditLimit) {
                    customerCreditLimitRevisions.computeIfAbsent(customerId) { AtomicLong(0L) }.incrementAndGet()
                }
                _customersFlow.update { list -> list.map { if (it.id == id) merged else it } }
                merged
            }
            Result.success(updated)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun updateCustomer(
        customerId: String,
        name: String,
        phone: String,
        address: String,
        creditLimitIls: Double
    ): Result<Customer> {
        val existing = _customersFlow.value.firstOrNull { it.id == customerId }
            ?: return Result.failure(IllegalArgumentException("Customer not found: $customerId"))
        val isAddressUnloaded = existing.addressAr == null && existing.addressEn == null
        val shouldUpdateAddress = if (isAddressUnloaded) {
            address.trim().isNotBlank()
        } else {
            true
        }

        val isCreditLimitUnloaded = existing.creditLimitExact == null
        val shouldUpdateCreditLimit = if (creditLimitIls.isNaN()) {
            false
        } else if (isCreditLimitUnloaded) {
            creditLimitIls > 0.0
        } else {
            val newExact = BigDecimal(creditLimitIls.toString()).setScale(currencyMinorUnits, RoundingMode.HALF_UP)
            existing.creditLimitExact.compareTo(newExact) != 0
        }
        val creditLimitExact = if (shouldUpdateCreditLimit && !creditLimitIls.isNaN() && creditLimitIls >= 0.0) {
            BigDecimal(creditLimitIls.toString()).setScale(currencyMinorUnits, RoundingMode.HALF_UP)
        } else null

        return updateCustomer(
            customerId = customerId,
            name = name,
            phone = phone,
            address = address,
            creditLimit = creditLimitExact,
            updatePhone = true,
            updateAddress = shouldUpdateAddress,
            updateCreditLimit = shouldUpdateCreditLimit
        )
    }

    override suspend fun getCustomerDetail(customerId: String): Result<Customer> = withContext(ioDispatcher) {
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val before = requireActiveScope()
        val ulid = try {
            ServerUlid.parse(customerId)
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Invalid customerId ULID: '$customerId'", e))
        }
        val creditLimitRevisionBefore = getCreditLimitRevision(customerId)
        val revisionBefore = getCustomerRevision(customerId)
        val inFlightBefore = customerInFlightPayments[customerId]?.get() ?: 0
        try {
            val res = apiClient.getCustomer(companyId, ulid)
            if (res.isFailure) {
                return@withContext Result.failure(res.exceptionOrNull() ?: RuntimeException("Failed to get customer detail"))
            }
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
            check(requireActiveScope() == before) { "Session changed" }
            val obj = res.getOrThrow()
            val id = obj.properties["id"].toNullableString() ?: return@withContext Result.failure(IllegalStateException("Missing customer ID"))
            require(id == customerId)
            val name = obj.properties["name"].toNullableString() ?: obj.properties["name_ar"].toNullableString() ?: ""
            val nameAr = obj.properties["name_ar"].toNullableString() ?: name
            val nameEn = obj.properties["name_en"].toNullableString() ?: name
            val phone = obj.properties["phone"].toNullableString()
            val code = obj.properties["code"].toNullableString()
            val address = obj.properties["address"].toNullableString() ?: obj.properties["address_ar"].toNullableString()
            val hasCreditLimitKey = obj.properties.containsKey("credit_limit") ||
                obj.properties.containsKey("credit_limit_exact") ||
                obj.properties.containsKey("credit_limit_ils")
            val explicitCreditLimit = when {
                obj.properties.containsKey("credit_limit") -> obj.properties["credit_limit"].toNullableBigDecimal()
                obj.properties.containsKey("credit_limit_exact") -> obj.properties["credit_limit_exact"].toNullableBigDecimal()
                obj.properties.containsKey("credit_limit_ils") -> obj.properties["credit_limit_ils"].toNullableBigDecimal()
                else -> null
            }
            val serverBalance = when {
                obj.properties.containsKey("balance") -> obj.properties["balance"].toNullableBigDecimal()
                obj.properties.containsKey("balance_ils") -> obj.properties["balance_ils"].toNullableBigDecimal()
                else -> null
            }

            val detail = synchronized(getCustomerLock(customerId)) {
                val currentRevision = getCustomerRevision(customerId)
                val inFlightNow = customerInFlightPayments[customerId]?.get() ?: 0
                val currentCust = _customersFlow.value.firstOrNull { it.id == customerId }
                val canPublishBalance = (currentRevision == revisionBefore && inFlightBefore == 0 && inFlightNow == 0)
                val resolvedBalance = if (canPublishBalance) {
                    serverBalance
                } else {
                    currentCust?.balanceExact
                }
                val resolvedCreditLimit = if (hasCreditLimitKey && getCreditLimitRevision(customerId) == creditLimitRevisionBefore) {
                    explicitCreditLimit
                } else {
                    currentCust?.creditLimitExact
                }
                val built = Customer(
                    id = id,
                    nameAr = nameAr,
                    nameEn = nameEn,
                    phone = phone ?: currentCust?.phone,
                    code = code ?: currentCust?.code,
                    addressAr = address ?: currentCust?.addressAr,
                    addressEn = address ?: currentCust?.addressEn,
                    creditLimitExact = resolvedCreditLimit,
                    balanceExact = resolvedBalance,
                    isWalkIn = currentCust?.isWalkIn ?: false,
                    isDetailLoaded = true
                )
                _customersFlow.update { list ->
                    if (list.none { it.id == id }) list + built
                    else list.map { c ->
                        if (c.id == id) {
                            c.copy(
                                nameAr = nameAr,
                                nameEn = nameEn,
                                phone = phone ?: c.phone,
                                code = code ?: c.code,
                                addressAr = address ?: c.addressAr,
                                addressEn = address ?: c.addressEn,
                                creditLimitExact = resolvedCreditLimit,
                                balanceExact = resolvedBalance,
                                isDetailLoaded = true
                            )
                        } else c
                    }
                }
                built
            }
            Result.success(detail)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSupplierDetail(supplierId: String): Result<Supplier> = withContext(ioDispatcher) {
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val before = requireActiveScope()
        val ulid = try {
            ServerUlid.parse(supplierId)
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Invalid supplierId ULID: '$supplierId'", e))
        }
        val revisionBefore = getSupplierRevision(supplierId)
        val inFlightBefore = supplierInFlightPayments[supplierId]?.get() ?: 0
        try {
            val res = apiClient.getSupplier(companyId, ulid)
            if (res.isFailure) {
                return@withContext Result.failure(res.exceptionOrNull() ?: RuntimeException("Failed to get supplier detail"))
            }
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
            check(requireActiveScope() == before) { "Session changed" }
            val obj = res.getOrThrow()
            val id = obj.properties["id"].toNullableString() ?: return@withContext Result.failure(IllegalStateException("Missing supplier ID"))
            require(id == supplierId)
            val name = obj.properties["name"].toNullableString()
                ?: obj.properties["name_ar"].toNullableString()
                ?: obj.properties["name_en"].toNullableString()
                ?: ""
            val nameAr = obj.properties["name_ar"].toNullableString() ?: name
            val nameEn = obj.properties["name_en"].toNullableString() ?: name
            val company = obj.properties["company_name"].toNullableString() ?: obj.properties["company"].toNullableString()
            val phone = obj.properties["phone"].toNullableString()
            val code = obj.properties["code"].toNullableString()
            val balanceStr = obj.properties["balance_owed"].toNullableString()
                ?: obj.properties["owed"].toNullableString()
                ?: obj.properties["balance"].toNullableString()
            val serverBalanceOwed = balanceStr?.let { try { BigDecimal(it) } catch (_: Exception) { null } }
            val advancesStr = obj.properties["advances"].toNullableString()
            val serverAdvances = advancesStr?.let { try { BigDecimal(it) } catch (_: Exception) { null } }

            val detail = synchronized(getSupplierLock(supplierId)) {
                val currentRevision = getSupplierRevision(supplierId)
                val inFlightNow = supplierInFlightPayments[supplierId]?.get() ?: 0
                val currentSup = _suppliersFlow.value.firstOrNull { it.id == supplierId }
                val canPublishBalance = (currentRevision == revisionBefore && inFlightBefore == 0 && inFlightNow == 0)
                val (balanceOwed, advances) = if (canPublishBalance) {
                    Pair(serverBalanceOwed, serverAdvances)
                } else {
                    Pair(currentSup?.balanceOwedExact, currentSup?.advancesExact)
                }
                val built = Supplier(
                    id = id,
                    code = code ?: currentSup?.code,
                    nameAr = nameAr,
                    nameEn = nameEn,
                    companyAr = company ?: currentSup?.companyAr,
                    companyEn = company ?: currentSup?.companyEn,
                    phone = phone ?: currentSup?.phone,
                    balanceOwedExact = balanceOwed,
                    advancesExact = advances
                )
                _suppliersFlow.update { list ->
                    if (list.none { it.id == id }) list + built else list.map { if (it.id == id) built else it }
                }
                built
            }
            Result.success(detail)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCustomerStatement(
        customerId: String,
        fromDate: String?,
        toDate: String?
    ): Result<CustomerStatementResponse> = withContext(ioDispatcher) {
        if (authScopeProvider != null && !isScopeActive()) {
            return@withContext Result.failure(SecurityException("Authenticated scope is superseded or revoked."))
        }
        val client = merchantReadClient ?: return@withContext Result.failure(IllegalStateException("MerchantReadClient not configured"))
        val ulid = try {
            ServerUlid.parse(customerId)
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Invalid customerId ULID: '$customerId'", e))
        }
        try {
            val res = client.getCustomerStatement(companyId, ulid, fromDate, toDate, retainedGeneration = retainedGeneration)
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
            if (authScopeProvider != null && !isScopeActive()) {
                return@withContext Result.failure(SecurityException("Authenticated scope is superseded or revoked."))
            }
            when (res) {
                is MerchantReadResult.Success -> {
                    val statement = res.value
                    val summary = statement.currencies[baseCurrency] ?: statement.currencies.values.firstOrNull()
                    if (summary != null) {
                        val mapped = summary.entries.map { entry ->
                            MerchantReadAdapter.toDomainCustomerTransaction(entry, customerId, currency = summary.currency)
                        }
                        if (authScopeProvider != null && !isScopeActive()) {
                            throw SecurityException("Authenticated scope changed")
                        }
                        _customerTransactionsFlow.update { current ->
                            val others = current.filter { it.customerId != customerId }
                            mapped + others
                        }
                    }
                    Result.success(statement)
                }
                is MerchantReadResult.NotFound -> Result.failure(NoSuchElementException("Customer '$customerId' not found"))
                is MerchantReadResult.Forbidden -> Result.failure(SecurityException("Forbidden: ${res.error?.message ?: "Missing customers.view permission"}"))
                is MerchantReadResult.AuthDenial -> Result.failure(SecurityException("Authentication denied: ${res.error?.message ?: "UNAUTHENTICATED"}"))
                is MerchantReadResult.NetworkError -> Result.failure(java.io.IOException("Network error loading customer statement: ${res.message}"))
                is MerchantReadResult.SchemaError -> Result.failure(IllegalStateException("Schema error in customer statement: ${res.message}"))
                else -> Result.failure(RuntimeException("Failed to load customer statement: $res"))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // Suppliers & Purchases
    // -------------------------------------------------------------------------

    override fun getSuppliers(): List<Supplier> = _suppliersFlow.value

    override fun getPurchaseBills(): List<PurchaseBill> = _purchasesFlow.value

    override fun getSupplierPayments(): List<SupplierPayment> = _supplierPaymentsFlow.value

    override suspend fun createSupplier(
        name: String,
        phone: String,
        company: String,
        operationId: String
    ): Result<Supplier> = withContext(ioDispatcher) {
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Supplier name is required | اسم المورد مطلوب"))
        }
        val cleanPhone = phone.trim().ifBlank { null }
        val cleanCompany = company.trim().ifBlank { null }

        val commandId = if (operationId.isNotBlank()) operationId else getDraftOperationId("supplier_create")
        val idempotencyKey = "suppcreate-$commandId"

        val payloadMap = mutableMapOf<String, com.example.data.mobile.contract.StrictJsonElement>(
            "name_ar" to com.example.data.mobile.contract.StrictJsonString(cleanName)
        )
        if (cleanCompany != null) payloadMap["company_name"] = com.example.data.mobile.contract.StrictJsonString(cleanCompany)
        if (cleanPhone != null) payloadMap["phone"] = com.example.data.mobile.contract.StrictJsonString(cleanPhone)
        val payloadObj = com.example.data.mobile.contract.StrictJsonObject(payloadMap)
        val payloadBytes = com.example.data.mobile.contract.CanonicalJsonSerializer.serializeToUtf8Bytes(payloadObj)

        val scope = requireActiveScope()
        val activeDraft = commandStore.getActiveDraftRecord(scope.companyId.value, scope.actorId.value, scope.deviceId.value, "supplier_create")
        val effectivePayloadBytes = if (activeDraft != null && activeDraft.commandId == commandId) {
            commandStore.decryptPayload(activeDraft)
        } else {
            payloadBytes
        }

        val validateSupplier: (StrictJsonObject) -> Result<StrictJsonObject> = { obj ->
            val rawId = obj.properties["id"].toNullableString()
            if (rawId == null) {
                Result.failure(IllegalStateException("Server response missing supplier ID"))
            } else if (!ServerUlid.isValid(rawId)) {
                Result.failure(IllegalStateException("Server returned invalid supplier ULID: '$rawId'"))
            } else {
                Result.success(obj)
            }
        }

        val postCall: suspend (String) -> Result<com.example.data.mobile.contract.StrictJsonObject> = { key ->
            val effectiveObj = if (activeDraft != null && activeDraft.commandId == commandId) {
                try {
                    StrictJsonParser.parse(String(effectivePayloadBytes, StandardCharsets.UTF_8)).asObject()
                } catch (_: Exception) { payloadObj }
            } else payloadObj
            val effName = effectiveObj.properties["name_ar"]?.asString() ?: cleanName
            val effCompany = effectiveObj.properties["company_name"]?.asString() ?: cleanCompany
            val effPhone = effectiveObj.properties["phone"]?.asString() ?: cleanPhone
            apiClient.createSupplier(
                companyId = companyId,
                nameAr = effName,
                company = effCompany,
                phone = effPhone,
                idempotencyKey = key
            )
        }

        try {
            val durableRes = executeDurableCommand(
                commandType = "supplier_create",
                commandId = commandId,
                idempotencyKey = idempotencyKey,
                canonicalPayloadBytes = effectivePayloadBytes,
                isReceiptStatusChecked = true,
                validateResponse = validateSupplier,
                postAction = postCall
            )

            if (durableRes.isFailure) {
                return@withContext Result.failure(durableRes.exceptionOrNull() ?: RuntimeException("Supplier creation failed"))
            }

            rotateDraftOperationId("supplier_create")

            val responseObj = durableRes.getOrThrow()
            val rawId = responseObj.properties["id"].toNullableString()
                ?: return@withContext Result.failure(IllegalStateException("Server response missing supplier ID"))
            if (!ServerUlid.isValid(rawId)) {
                return@withContext Result.failure(IllegalStateException("Server returned invalid supplier ULID: '$rawId'"))
            }

            val authName = responseObj.properties["name"].toNullableString()
                ?: responseObj.properties["name_ar"].toNullableString()
                ?: cleanName
            val authCompany = responseObj.properties["company_name"].toNullableString()
                ?: responseObj.properties["company"].toNullableString()
                ?: cleanCompany
            val authPhone = responseObj.properties["phone"].toNullableString() ?: cleanPhone
            val authBalanceOwed = responseObj.properties["balance_owed"].toNullableBigDecimal()
                ?: responseObj.properties["owed"].toNullableBigDecimal()
                ?: responseObj.properties["balance"].toNullableBigDecimal()
            val authAdvances = responseObj.properties["advances"].toNullableBigDecimal()

            val created = Supplier(
                id = rawId,
                nameAr = authName,
                nameEn = authName,
                companyAr = authCompany,
                companyEn = authCompany,
                phone = authPhone,
                balanceOwedExact = authBalanceOwed,
                advancesExact = authAdvances
            )
            _suppliersFlow.update { listOf(created) + it }
            Result.success(created)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun saveSupplier(
        supplierId: String?,
        name: String,
        company: String,
        phone: String,
        operator: UserSession
    ): Result<Supplier> = saveSupplier(supplierId, name, company, phone, operator, "")

    override suspend fun saveSupplier(
        supplierId: String?,
        name: String,
        company: String,
        phone: String,
        operator: UserSession,
        operationId: String
    ): Result<Supplier> = withContext(ioDispatcher) {
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Supplier name is required | اسم المورد مطلوب"))
        }
        if (company.isNotBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Supplier company name is not persisted on server | اسم الشركة للمورد غير مدعوم على الخادم"))
        }
        if (supplierId == null) {
            return@withContext createSupplier(cleanName, phone, "", operationId)
        }
        try {
            val before = requireActiveScope()
            val response = apiClient.updateSupplier(companyId, ServerUlid.parse(supplierId), cleanName, phone.ifBlank { null }).getOrThrow()
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
            check(requireActiveScope() == before) { "Session changed" }
            val id = response.properties["id"].toNullableString() ?: error("Missing supplier ID")
            ServerUlid.parse(id)
            require(supplierId == id) { "Supplier ID mismatch" }
            val serverName = response.properties["name"].toNullableString()
                ?: response.properties["name_ar"].toNullableString()
                ?: error("Missing supplier name")
            val existing = _suppliersFlow.value.firstOrNull { it.id == id }
            val supplier = existing?.copy(
                nameAr = serverName,
                nameEn = serverName,
                companyAr = null,
                companyEn = null,
                phone = response.properties["phone"].toNullableString() ?: phone.ifBlank { null }
            ) ?: Supplier(
                id = id,
                nameAr = serverName,
                nameEn = serverName,
                companyAr = null,
                companyEn = null,
                phone = response.properties["phone"].toNullableString() ?: phone.ifBlank { null },
                balanceOwedExact = null
            )
            _suppliersFlow.update { list ->
                if (list.any { it.id == id }) {
                    list.map { if (it.id == id) supplier else it }
                } else {
                    listOf(supplier) + list
                }
            }
            Result.success(supplier)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (error: Exception) { Result.failure(error) }
    }

    override suspend fun recordPurchaseBill(
        supplierId: String,
        itemsSummary: String,
        totalIls: Double,
        paidNowIls: Double,
        operator: UserSession,
        expectedProductId: String?,
        expectedQuantity: Int
    ): Result<PurchaseBill> = withContext(ioDispatcher) {
        val prodId = expectedProductId
            ?: _productsFlow.value.firstOrNull { it.nameAr == itemsSummary || it.nameEn == itemsSummary }?.id
            ?: return@withContext Result.failure(IllegalArgumentException("Expected product ID is required to record purchase bill"))
        val qty = if (expectedQuantity > 0) expectedQuantity else 1
        val unitCost = (totalIls / qty).coerceAtLeast(0.0)
        val line = PurchaseLineItem(
            id = "",
            productId = prodId,
            productNameAr = itemsSummary,
            productNameEn = itemsSummary,
            quantity = qty,
            receivedQuantity = 0,
            unitCostIls = unitCost
        )
        recordStructuredPurchaseBill(
            supplierId = supplierId,
            lines = listOf(line),
            paidNowIls = paidNowIls,
            operator = operator,
            note = itemsSummary
        )
    }

    override suspend fun recordStructuredPurchaseBill(
        supplierId: String,
        lines: List<PurchaseLineItem>,
        paidNowIls: Double,
        operator: UserSession,
        note: String
    ): Result<PurchaseBill> = recordStructuredPurchaseBill(
        supplierId = supplierId,
        lines = lines,
        paidNowIls = paidNowIls,
        operator = operator,
        note = note,
        operationId = ""
    )

    override suspend fun recordStructuredPurchaseBill(
        supplierId: String,
        lines: List<PurchaseLineItem>,
        paidNowIls: Double,
        operator: UserSession,
        note: String,
        operationId: String
    ): Result<PurchaseBill> = withContext(ioDispatcher) {
        requireActiveScope()
        if (!ServerUlid.isValid(supplierId)) {
            return@withContext Result.failure(IllegalArgumentException("Valid supplier ULID is required: $supplierId"))
        }
        if (lines.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Purchase bill requires at least one line item."))
        }
        for (line in lines) {
            if (!ServerUlid.isValid(line.productId)) {
                return@withContext Result.failure(IllegalArgumentException("Line requires valid product ULID: ${line.productId}"))
            }
            if (line.quantityExact <= BigDecimal.ZERO) {
                return@withContext Result.failure(IllegalArgumentException("Line quantity must be strictly positive: ${line.quantityExact}"))
            }
        }

        val totalExact = lines.fold(BigDecimal.ZERO) { acc, line -> acc.add(line.totalCostExact) }
        val paidExact = BigDecimal(paidNowIls.toString()).setScale(6, RoundingMode.HALF_UP)
        val effectiveOpId = if (operationId.isNotBlank()) operationId else UUID.randomUUID().toString()
        val commandId = effectiveOpId
        val idempotencyKey = if (effectiveOpId.startsWith("purch-")) effectiveOpId else "purch-$effectiveOpId"

        val linesArray = lines.map { line ->
            StrictJsonObject(
                mapOf(
                    "product_id" to StrictJsonString(line.productId),
                    "quantity" to StrictJsonString(line.quantityExact.setScale(6, RoundingMode.HALF_UP).toPlainString()),
                    "unit_cost_ils" to StrictJsonString((line.unitCostExact ?: throw IllegalArgumentException("Explicit unit cost required")).setScale(6, RoundingMode.UNNECESSARY).toPlainString())
                )
            )
        }
        val linesJson = CanonicalJsonSerializer.serialize(StrictJsonArray(linesArray))
        val payloadBytes = linesJson.toByteArray(StandardCharsets.UTF_8)

        val postCall: suspend (String) -> Result<StrictJsonObject> = { key ->
            apiClient.createPurchase(
                companyId = companyId,
                supplierId = ServerUlid.parse(supplierId),
                linesJson = linesJson,
                paidNow = paidExact,
                billNumber = null,
                accountId = if (paidExact > BigDecimal.ZERO) approvedCashAccount.id else null,
                idempotencyKey = key
            )
        }

        val durableRes = executeDurableCommand(
            commandType = "purchase.bill",
            commandId = commandId,
            idempotencyKey = idempotencyKey,
            canonicalPayloadBytes = payloadBytes,
            isReceiptStatusChecked = false,
            postAction = postCall
        )

        if (durableRes.isFailure) {
            return@withContext Result.failure(durableRes.exceptionOrNull() ?: RuntimeException("Purchase bill creation failed"))
        }

        val snapshot = durableRes.getOrThrow()
        val purchasePublicId = snapshot.properties["id"]?.asString()
            ?: return@withContext Result.failure(IllegalStateException("Server did not return purchase public ID"))

        // Authoritative follow-up GET to obtain immutable line IDs, frozen numbers, totals
        val getRes = apiClient.getPurchase(companyId, ServerUlid.parse(purchasePublicId))
        val purchaseObj = if (getRes.isSuccess) {
            getRes.getOrThrow()
        } else {
            val map = snapshot.properties.toMutableMap()
            if (!map.containsKey("supplier_id")) map["supplier_id"] = StrictJsonString(supplierId)
            StrictJsonObject(map)
        }

        val bill = parseAuthoritativePurchaseBill(purchaseObj)
        _purchasesFlow.update { listOf(bill) + it.filter { existing -> existing.id != bill.id } }

        // Refresh supplier balance from server
        apiClient.getSupplier(companyId, ServerUlid.parse(supplierId)).getOrNull()?.let { supObj ->
            updateSupplierFromServer(supObj)
        }

        Result.success(bill)
    }

    override suspend fun confirmPhysicalGoodsReceipt(
        billId: String,
        productId: String,
        receivedQty: Int,
        batchNote: String,
        operator: UserSession,
        operationId: String,
        lineId: String?
    ): Result<StockMovement> = confirmPhysicalGoodsReceipt(
        billId = billId,
        productId = productId,
        receivedQty = BigDecimal.valueOf(receivedQty.toLong()),
        batchNote = batchNote,
        operator = operator,
        operationId = operationId,
        lineId = lineId
    )

    override suspend fun confirmPhysicalGoodsReceipt(
        billId: String,
        productId: String,
        receivedQty: BigDecimal,
        batchNote: String,
        operator: UserSession,
        operationId: String,
        lineId: String?
    ): Result<StockMovement> = withContext(ioDispatcher) {
        requireActiveScope()
        if (!ServerUlid.isValid(billId)) {
            return@withContext Result.failure(IllegalArgumentException("Valid purchase bill ULID is required: $billId"))
        }
        if (!ServerUlid.isValid(productId)) {
            return@withContext Result.failure(IllegalArgumentException("Valid product ULID is required: $productId"))
        }
        if (receivedQty <= BigDecimal.ZERO) {
            return@withContext Result.failure(IllegalArgumentException("Received quantity must be strictly positive: $receivedQty"))
        }

        val existingBill = _purchasesFlow.value.firstOrNull { it.id == billId }
            ?: run {
                val fetch = apiClient.getPurchase(companyId, ServerUlid.parse(billId)).getOrNull()
                if (fetch != null) parseAuthoritativePurchaseBill(fetch) else null
            }
            ?: return@withContext Result.failure(IllegalArgumentException("Purchase bill not found: $billId"))

        val candidateLines = existingBill.effectiveLines.filter { it.productId == productId }
        if (candidateLines.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Product [$productId] is not part of purchase bill [$billId]"))
        }

        // Multi-line same SKU resolution: bind exact line ID
        val targetLine = if (!lineId.isNullOrBlank()) {
            candidateLines.firstOrNull { it.id == lineId }
                ?: return@withContext Result.failure(IllegalArgumentException("Line [$lineId] does not belong to purchase bill [$billId] for product [$productId]"))
        } else {
            if (candidateLines.size > 1) {
                return@withContext Result.failure(IllegalArgumentException("Ambiguous line resolution: bill has ${candidateLines.size} lines with product [$productId]. Explicit lineId is required."))
            }
            candidateLines.first()
        }

        if (targetLine.pendingQuantityExact <= BigDecimal.ZERO) {
            return@withContext Result.failure(IllegalStateException("Line [${targetLine.id}] has already been fully received."))
        }

        if (receivedQty > targetLine.pendingQuantityExact) {
            return@withContext Result.failure(IllegalArgumentException("Requested quantity ($receivedQty) exceeds pending line quantity (${targetLine.pendingQuantityExact})"))
        }

        val commandId = if (operationId.isNotBlank()) operationId else UUID.randomUUID().toString()
        val idempotencyKey = "grn-$commandId"

        // Canonical GRN payload: key is 'lines' (NOT 'received_lines'), explicit destination warehouse public ID
        val grnLines = listOf(
            StrictJsonObject(
                mapOf(
                    "purchase_line_id" to StrictJsonString(targetLine.id),
                    "quantity" to StrictJsonString(receivedQty.setScale(6, RoundingMode.HALF_UP).toPlainString())
                )
            )
        )
        val grnLinesJson = CanonicalJsonSerializer.serialize(StrictJsonArray(grnLines))
        val payloadBytes = grnLinesJson.toByteArray(StandardCharsets.UTF_8)

        val postCall: suspend (String) -> Result<StrictJsonObject> = { key ->
            apiClient.receiveGoods(
                companyId = companyId,
                purchaseId = ServerUlid.parse(billId),
                linesJson = grnLinesJson,
                destinationWarehouseId = defaultWarehouse.id,
                receiptDate = WesternFormatters.currentDate(),
                notes = batchNote.ifBlank { null },
                idempotencyKey = key
            )
        }

        val durableRes = executeDurableCommand(
            commandType = "purchase.grn",
            commandId = commandId,
            idempotencyKey = idempotencyKey,
            canonicalPayloadBytes = payloadBytes,
            isReceiptStatusChecked = false,
            postAction = postCall
        )

        if (durableRes.isFailure) {
            return@withContext Result.failure(durableRes.exceptionOrNull() ?: RuntimeException("GRN stock receipt failed"))
        }

        val snapshot = durableRes.getOrThrow()
        val grnPublicId = snapshot.properties["id"]?.asString()
            ?: return@withContext Result.failure(IllegalStateException("Server did not return official GRN public ID"))

        // Refresh authoritative product stock from server (NO local addition!)
        val refreshedProduct = apiClient.getProduct(companyId, ServerUlid.parse(productId)).getOrNull()
        val authoritativeStockExact: BigDecimal? = refreshedProduct?.properties?.get("available_quantity")?.asString()?.let { BigDecimal(it) }
            ?: refreshedProduct?.properties?.get("stock_quantity")?.asString()?.let { BigDecimal(it) }

        if (authoritativeStockExact != null) {
            _productsFlow.update { list ->
                list.map { p ->
                    if (p.id == productId) {
                        p.copy(
                            stockCountExact = authoritativeStockExact,
                            stockCount = authoritativeStockExact.toInt()
                        )
                    } else p
                }
            }
        }

        // Refresh purchase bill from server
        apiClient.getPurchase(companyId, ServerUlid.parse(billId)).getOrNull()?.let { updatedPurchaseObj ->
            val updatedBill = parseAuthoritativePurchaseBill(updatedPurchaseObj)
            _purchasesFlow.update { list ->
                list.map { if (it.id == billId) updatedBill else it }
            }
        }

        val movement = StockMovement(
            id = grnPublicId,
            productId = productId,
            date = snapshot.properties["posted_at"]?.asString() ?: WesternFormatters.currentDate(),
            deltaExact = receivedQty,
            reasonAr = "سند استلام بضاعة: $batchNote",
            reasonEn = "Goods Receipt: $batchNote",
            resultingStockExact = authoritativeStockExact
        )
        _stockMovementsFlow.update { listOf(movement) + it }

        Result.success(movement)
    }

    override suspend fun getSupplierSettlementTargets(
        supplierId: String,
        currency: String?,
        limit: Int,
        cursor: String?
    ): Result<SettlementTargetsResponse> = withContext(ioDispatcher) {
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val before = requireActiveScope()
        if (!ServerUlid.isValid(supplierId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid supplier ULID: $supplierId"))
        }
        try {
            val res = apiClient.getSupplierSettlementTargets(
                companyId = companyId,
                supplierId = ServerUlid.parse(supplierId),
                currency = currency,
                limit = limit,
                cursor = cursor
            )
            if (res.isSuccess) {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException() }
                check(requireActiveScope() == before) { "Session changed during supplier settlement targets read" }
                val parsed = parseSettlementTargetsResponse(res.getOrThrow(), isSupplier = true)
                if (currency == baseCurrency) {
                    require(parsed.targets.all { it.currency == baseCurrency && it.currencyMinorUnits == currencyMinorUnits })
                }
                Result.success(parsed)
            } else {
                val ex = res.exceptionOrNull() ?: RuntimeException("Failed to read supplier settlement targets")
                val msg = ex.message ?: ""
                if (msg.contains("403") || msg.contains("FORBIDDEN") || msg.contains("purchasing cost")) {
                    Result.failure(SecurityException("Viewing supplier financial settlement targets requires purchasing cost permission."))
                } else {
                    Result.failure(ex)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    override suspend fun recordSupplierPayment(
        supplierId: String,
        amountIls: Double,
        note: String,
        operator: UserSession,
        paymentId: String
    ): Result<SupplierPayment> = recordSupplierPayment(
        supplierId = supplierId,
        amount = BigDecimal(amountIls.toString()),
        note = note,
        operator = operator,
        paymentId = paymentId,
        cashAccountId = null,
        paymentDate = null,
        allocations = emptyList()
    )

    override suspend fun recordSupplierPayment(
        supplierId: String,
        amount: BigDecimal,
        note: String,
        operator: UserSession,
        paymentId: String,
        cashAccountId: String?,
        paymentDate: String?,
        allocations: List<PaymentAllocationInput>
    ): Result<SupplierPayment> = withContext(ioDispatcher) {
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val before = requireActiveScope()
        if (!ServerUlid.isValid(supplierId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid supplier ULID: $supplierId"))
        }
        val amountExact = try {
            amount.setScale(currencyMinorUnits, RoundingMode.UNNECESSARY)
        } catch (e: ArithmeticException) {
            return@withContext Result.failure(IllegalArgumentException("Payment amount $amount exceeds allowed precision of $currencyMinorUnits minor units for $baseCurrency"))
        }
        if (amountExact <= BigDecimal.ZERO) {
            return@withContext Result.failure(IllegalArgumentException("Payment amount must be strictly positive"))
        }

        var totalAllocated = BigDecimal.ZERO
        val targetSet = mutableSetOf<String>()
        for (alloc in allocations) {
            if (alloc.amountExact <= BigDecimal.ZERO) {
                return@withContext Result.failure(IllegalArgumentException("Allocation amount must be strictly positive"))
            }
            try {
                alloc.amountExact.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY)
            } catch (e: ArithmeticException) {
                return@withContext Result.failure(IllegalArgumentException("Allocation amount ${alloc.amountExact} exceeds allowed precision of ${ContractConstants.DECIMAL_SCALE} decimal places"))
            }
            val key = "${alloc.targetType}:${alloc.targetId}"
            if (!targetSet.add(key)) {
                return@withContext Result.failure(IllegalArgumentException("Duplicate allocation target detected: $key"))
            }
            totalAllocated = totalAllocated.add(alloc.amountExact)
        }
        if (totalAllocated > amountExact) {
            return@withContext Result.failure(IllegalArgumentException("Allocations ($totalAllocated) exceed total payment amount ($amountExact)"))
        }

        val preparedRes = prepareDurablePaymentSubmission(
            scope = before,
            formType = "supplier_payment",
            keyPrefix = "suppay",
            partyFieldName = "supplier_id",
            partyId = supplierId,
            requestedPaymentId = paymentId,
            amountExact = amountExact,
            note = note,
            cashAccountId = cashAccountId,
            paymentDate = paymentDate,
            allocations = allocations
        )
        if (preparedRes.isFailure) {
            return@withContext Result.failure(preparedRes.exceptionOrNull() ?: IllegalStateException("Payment preparation failed"))
        }
        val prepared = preparedRes.getOrThrow()
        val commandId = prepared.commandId
        val idempotencyKey = prepared.idempotencyKey
        val effectivePaymentDate = prepared.effectivePaymentDate
        val effectiveCashAccount = prepared.effectiveCashAccount
        val payloadBytes = prepared.payloadBytes

        val inFlightCounter = supplierInFlightPayments.computeIfAbsent(supplierId) { java.util.concurrent.atomic.AtomicInteger(0) }
        inFlightCounter.incrementAndGet()
        val postPaymentRevision = incrementSupplierRevision(supplierId)

        val postCall: suspend (String) -> Result<StrictJsonObject> = { key ->
            apiClient.recordSupplierPayment(
                companyId = companyId,
                supplierId = ServerUlid.parse(supplierId),
                amount = amountExact,
                cashAccountId = effectiveCashAccount,
                note = note,
                idempotencyKey = key,
                paymentDate = effectivePaymentDate,
                allocations = allocations,
                currency = baseCurrency
            )
        }

        val validatePaymentResponse: (StrictJsonObject) -> Result<StrictJsonObject> = { snap ->
            validatePaymentSnapshotObject(
                snap = snap,
                isSupplier = true,
                expectedPartyId = supplierId,
                expectedAmount = amountExact,
                expectedAllocated = totalAllocated,
                defaultAdvance = allocations.isEmpty()
            )
        }

        try {
            val durableRes = executeDurableCommand(
                commandType = "supplier.payment",
                commandId = commandId,
                idempotencyKey = idempotencyKey,
                canonicalPayloadBytes = payloadBytes,
                isReceiptStatusChecked = true,
                validateResponse = validatePaymentResponse,
                postAction = postCall
            )

            if (durableRes.isFailure) {
                return@withContext Result.failure(durableRes.exceptionOrNull() ?: RuntimeException("Supplier payment failed"))
            }

            if (prepared.activeDraft != null || commandId == prepared.persistedDraftId) {
                rotateDraftOperationId("supplier_payment")
            }

            val snapshot = durableRes.getOrThrow()
            val officialId = snapshot.properties["id"].toNullableString()
                ?: snapshot.properties["voucher_id"].toNullableString()
                ?: snapshot.properties["receipt_id"].toNullableString()
                ?: return@withContext Result.failure(IllegalStateException("Server did not return payment ID"))
            val parsedFields = parseCanonicalPaymentFields(
                snapshot = snapshot,
                fallbackAmount = amountExact,
                fallbackAllocated = totalAllocated,
                defaultAdvance = allocations.isEmpty()
            )
            val officialAmount = parsedFields.officialAmount
            val postedAt = snapshot.properties["posted_at"].toNullableString() ?: effectivePaymentDate

            val isAdvance = parsedFields.isAdvance
            val allocatedAmount = parsedFields.allocatedAmount
            val unallocatedAmount = parsedFields.unallocatedAmount
            val parsedAllocations = parsedFields.allocations

            // Authoritative supplier detail from server (unknown balance remains null) with strict session recheck
            val snapshotBalanceOwed: BigDecimal? = snapshot.properties["balance_owed"].toNullableBigDecimal()
                ?: snapshot.properties["owed"].toNullableBigDecimal()
                ?: snapshot.properties["balance"].toNullableBigDecimal()
            val snapshotAdvances: BigDecimal? = snapshot.properties["advances"].toNullableBigDecimal()

            val getSupplierRes = if (snapshotBalanceOwed == null && snapshotAdvances == null) {
                val beforeFollowup = requireActiveScope()
                val res = apiClient.getSupplier(companyId, ServerUlid.parse(supplierId))
                if (requireActiveScope() != beforeFollowup) {
                    throw SecurityException("Authenticated scope changed during supplier balance refresh")
                }
                res
            } else null

            val refreshFailed = getSupplierRes != null && getSupplierRes.isFailure
            val serverSupplierObj = getSupplierRes?.getOrNull()
            val authoritativeBalanceExact: BigDecimal? = snapshotBalanceOwed
                ?: serverSupplierObj?.properties?.get("balance_owed").toNullableBigDecimal()
                ?: serverSupplierObj?.properties?.get("owed").toNullableBigDecimal()
                ?: serverSupplierObj?.properties?.get("balance").toNullableBigDecimal()
            val authoritativeAdvancesExact: BigDecimal? = snapshotAdvances
                ?: serverSupplierObj?.properties?.get("advances").toNullableBigDecimal()

            synchronized(getSupplierLock(supplierId)) {
                val currentRevision = getSupplierRevision(supplierId)
                val isLatest = (currentRevision == postPaymentRevision) || (inFlightCounter.get() == 1 && currentRevision <= postPaymentRevision)
                if (isLatest) {
                    incrementSupplierRevision(supplierId)
                    val publishedBalanceOwed = if (refreshFailed) null else authoritativeBalanceExact
                    val publishedAdvances = if (refreshFailed) null else authoritativeAdvancesExact
                    _suppliersFlow.update { list ->
                        list.map { s ->
                            if (s.id == supplierId) {
                                s.copy(
                                    balanceOwedExact = publishedBalanceOwed,
                                    advancesExact = publishedAdvances
                                )
                            } else s
                        }
                    }
                }
            }

            val payment = SupplierPayment(
                id = officialId,
                supplierId = supplierId,
                date = postedAt,
                amountExact = officialAmount,
                note = note,
                isAdvance = isAdvance,
                allocatedAmountExact = allocatedAmount,
                unallocatedAmountExact = unallocatedAmount,
                allocations = parsedAllocations,
                currency = snapshot.properties["currency"].toNullableString() ?: baseCurrency
            )
            _supplierPaymentsFlow.update { history ->
                if (history.any { it.id == payment.id }) history else listOf(payment) + history
            }
            Result.success(payment)
        } finally {
            inFlightCounter.decrementAndGet()
        }
    }

    private data class ParsedCanonicalPaymentFields(
        val officialAmount: BigDecimal,
        val isAdvance: Boolean,
        val allocatedAmount: BigDecimal,
        val unallocatedAmount: BigDecimal,
        val allocations: List<PaymentAllocationResult>
    )

    private fun validatePaymentSnapshotObject(
        snap: StrictJsonObject,
        isSupplier: Boolean,
        expectedPartyId: String,
        expectedAmount: BigDecimal,
        expectedAllocated: BigDecimal,
        defaultAdvance: Boolean
    ): Result<StrictJsonObject> = runCatching {
        val id = snap.properties["id"].toNullableString()
            ?: (if (isSupplier) snap.properties["voucher_id"].toNullableString() else null)
            ?: snap.properties["receipt_id"].toNullableString()
        check(!id.isNullOrBlank()) { "Server response missing payment id" }
        val partyField = if (isSupplier) "supplier_id" else "customer_id"
        val party = snap.properties[partyField]
        if (party != null && party != StrictJsonNull) {
            check(party.asString() == expectedPartyId) { "Server returned payment for a different $partyField" }
        }
        parseCanonicalPaymentFields(snap, expectedAmount, expectedAllocated, defaultAdvance)
        snap
    }

    private fun readCachedCommandResponse(record: MerchantCommandRecord): StrictJsonObject {
        val bytes = checkNotNull(commandStore.decryptSnapshot(record)) { "Cached command missing response snapshot" }
        return StrictJsonParser.parse(String(bytes, StandardCharsets.UTF_8)).asObject()
    }

    private fun paymentFormType(type: String): String? = when (type) {
        "customer.payment", "customer_payment" -> "customer_payment"
        "supplier.payment", "supplier_payment" -> "supplier_payment"
        else -> null
    }

    private fun cachedPaymentValidation(formType: String): ((MerchantCommandRecord) -> String?)? {
        val paymentForm = paymentFormType(formType) ?: return null
        val isSupplier = paymentForm == "supplier_payment"
        val partyField = if (isSupplier) "supplier_id" else "customer_id"
        return { record ->
            runCatching {
                val payload = checkNotNull(decryptDraftPayloadObject(record)) { "Invalid cached payment payload" }
                val partyId = checkNotNull(payload.properties[partyField]).asString()
                val amount = checkNotNull(payload.properties["amount"].toNullableBigDecimal())
                val allocations = payload.properties["allocations"]?.asArray()?.elements.orEmpty()
                val allocated = allocations.fold(BigDecimal.ZERO) { total, allocation ->
                    total + checkNotNull(allocation.asObject().properties["amount"].toNullableBigDecimal())
                }
                validatePaymentSnapshotObject(
                    readCachedCommandResponse(record), isSupplier, partyId, amount, allocated, allocations.isEmpty()
                ).getOrThrow()
            }.exceptionOrNull()?.let { it.message ?: "Invalid cached payment response" }
        }
    }

    private fun ensureCachedPaymentCompletionValidated(scope: CurrentAuthScope, formType: String) {
        val validate = cachedPaymentValidation(formType) ?: return
        requireActiveScope()
        val completed = commandStore.peekCompletedCommandForDraft(
            scope.companyId.value, scope.actorId.value, scope.deviceId.value, formType
        ) ?: return
        val error = validate(completed) ?: return
        commandStore.reopenInvalidCompletedCommand(formType, completed, error)
    }

    private fun parseCanonicalPaymentFields(
        snapshot: StrictJsonObject,
        fallbackAmount: BigDecimal,
        fallbackAllocated: BigDecimal,
        defaultAdvance: Boolean
    ): ParsedCanonicalPaymentFields {
        fun validateExactFields(value: StrictJsonObject, keys: List<String>) {
            for (key in keys) {
                if (value.properties.containsKey(key) && value.properties[key].toNullableBigDecimal() == null) {
                    throw IllegalStateException("Invalid canonical payment field: $key")
                }
            }
        }
        validateExactFields(snapshot, listOf("amount_exact", "allocated_amount_exact", "unallocated_amount_exact"))
        val officialAmount = snapshot.properties["amount_exact"].toNullableBigDecimal()
            ?: snapshot.properties["amount"].toNullableBigDecimal()
            ?: fallbackAmount
        val isAdvance = snapshot.properties["is_advance"]?.asBoolean() ?: defaultAdvance
        val allocatedAmount = snapshot.properties["allocated_amount_exact"].toNullableBigDecimal()
            ?: snapshot.properties["allocated_amount"].toNullableBigDecimal()
            ?: fallbackAllocated
        val unallocatedAmount = snapshot.properties["unallocated_amount_exact"].toNullableBigDecimal()
            ?: snapshot.properties["unallocated_amount"].toNullableBigDecimal()
            ?: (officialAmount - allocatedAmount).coerceAtLeast(BigDecimal.ZERO)

        val parsedAllocations = mutableListOf<PaymentAllocationResult>()
        val respAllocs = snapshot.properties["allocations"]
        if (respAllocs is StrictJsonArray) {
            for (el in respAllocs.elements) {
                if (el is StrictJsonObject) {
                    validateExactFields(el, listOf("allocated_amount_exact", "amount_exact"))
                    val tType = el.properties["target_type"]?.asString() ?: ""
                    val tId = el.properties["target_id"]?.asString() ?: ""
                    val allocAmt = el.properties["allocated_amount_exact"].toNullableBigDecimal()
                        ?: el.properties["amount_exact"].toNullableBigDecimal()
                        ?: el.properties["allocated_amount"].toNullableBigDecimal()
                        ?: el.properties["amount"].toNullableBigDecimal()
                        ?: BigDecimal.ZERO
                    val lineAmt = el.properties["amount_exact"].toNullableBigDecimal()
                        ?: el.properties["allocated_amount_exact"].toNullableBigDecimal()
                        ?: el.properties["amount"].toNullableBigDecimal()
                        ?: el.properties["allocated_amount"].toNullableBigDecimal()
                        ?: allocAmt
                    if (tId.isNotBlank()) {
                        parsedAllocations.add(PaymentAllocationResult(tType, tId, lineAmt, allocAmt))
                    }
                }
            }
        }
        return ParsedCanonicalPaymentFields(
            officialAmount = officialAmount,
            isAdvance = isAdvance,
            allocatedAmount = allocatedAmount,
            unallocatedAmount = unallocatedAmount,
            allocations = parsedAllocations
        )
    }

    private fun parseSettlementTargetsResponse(root: StrictJsonObject, isSupplier: Boolean): SettlementTargetsResponse {
        return SettlementTargetsResponse.fromStrictJson(root, isSupplier, currencyMinorUnits)
    }

    private fun updateSupplierFromServer(supObj: StrictJsonObject) {
        val id = supObj.properties["id"].toNullableString() ?: return
        val balanceOwedExact = supObj.properties["balance_owed"].toNullableBigDecimal()
            ?: supObj.properties["owed"].toNullableBigDecimal()
            ?: supObj.properties["balance"].toNullableBigDecimal()
            ?: supObj.properties["balance_owed_ils"].toNullableBigDecimal()
        val advancesExact = supObj.properties["advances"].toNullableBigDecimal()
        synchronized(getSupplierLock(id)) {
            if ((supplierInFlightPayments[id]?.get() ?: 0) > 0) return
            _suppliersFlow.update { list ->
                list.map { s ->
                    if (s.id == id) {
                        s.copy(
                            balanceOwedExact = balanceOwedExact,
                            advancesExact = advancesExact
                        )
                    } else s
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Expenses
    // -------------------------------------------------------------------------

    override fun getExpenses(): List<ExpenseEntry> = _expensesFlow.value

    override suspend fun recordExpense(
        title: String,
        category: ExpenseCategory,
        amountIls: Double,
        operator: UserSession
    ): Result<ExpenseEntry> = recordExpense(
        title = title,
        category = category,
        amountIls = amountIls,
        operator = operator,
        categoryId = null,
        operationId = ""
    )

    override suspend fun recordExpense(
        title: String,
        category: ExpenseCategory,
        amountIls: Double,
        operator: UserSession,
        categoryId: String?
    ): Result<ExpenseEntry> = recordExpense(
        title = title,
        category = category,
        amountIls = amountIls,
        operator = operator,
        categoryId = categoryId,
        operationId = ""
    )

    override suspend fun recordExpense(
        title: String,
        category: ExpenseCategory,
        amountIls: Double,
        operator: UserSession,
        categoryId: String?,
        operationId: String
    ): Result<ExpenseEntry> = withContext(ioDispatcher) {
        requireActiveScope()
        val amountExact = BigDecimal(amountIls.toString()).setScale(currencyMinorUnits, RoundingMode.HALF_UP)
        if (amountExact <= BigDecimal.ZERO) {
            return@withContext Result.failure(IllegalArgumentException("Expense amount must be strictly positive"))
        }
        if (title.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Expense description is required"))
        }

        // Must use real server ULID from GET /expenses/categories, no null or enum guessing
        val resolvedCategoryId: ServerUlid = if (!categoryId.isNullOrBlank() && ServerUlid.isValid(categoryId)) {
            ServerUlid.parse(categoryId)
        } else {
            return@withContext Result.failure(IllegalArgumentException("Explicit server expense category is required"))
        }

        val effectiveOpId = if (operationId.isNotBlank()) operationId else UUID.randomUUID().toString()
        val commandId = effectiveOpId
        val idempotencyKey = if (effectiveOpId.startsWith("exp-")) effectiveOpId else "exp-$effectiveOpId"

        val payloadObject = StrictJsonObject(
            mapOf(
                "amount" to StrictJsonString(amountExact.setScale(6, RoundingMode.HALF_UP).toPlainString()),
                "expense_category_id" to StrictJsonString(resolvedCategoryId.value),
                "description" to StrictJsonString(title)
            )
        )
        val canonicalPayloadBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(payloadObject)

        val postCall: suspend (String) -> Result<StrictJsonObject> = { key ->
            apiClient.createExpense(
                companyId = companyId,
                amount = amountExact,
                categoryId = resolvedCategoryId,
                description = title,
                idempotencyKey = key
            )
        }

        val durableRes = executeDurableCommand(
            commandType = "expense.create",
            commandId = commandId,
            idempotencyKey = idempotencyKey,
            canonicalPayloadBytes = canonicalPayloadBytes,
            isReceiptStatusChecked = true,
            postAction = postCall
        )

        if (durableRes.isFailure) {
            return@withContext Result.failure(durableRes.exceptionOrNull() ?: RuntimeException("Expense creation failed"))
        }

        val snapshot = durableRes.getOrThrow()
        val officialId = snapshot.properties["id"]?.asString()
            ?: return@withContext Result.failure(IllegalStateException("Server did not return expense ID"))
        val officialAmount = snapshot.properties["amount"]?.asString()?.let { BigDecimal(it) } ?: amountExact
        val postedAt = snapshot.properties["posted_at"]?.asString() ?: WesternFormatters.currentDate()

        val entry = ExpenseEntry(
            id = officialId,
            date = postedAt,
            titleAr = title,
            titleEn = title,
            category = category,
            amountExact = officialAmount
        )
        _expensesFlow.update { listOf(entry) + it }
        Result.success(entry)
    }

    override fun getDraftOperationId(formType: String): String {
        val scope = requireActiveScope()
        return commandStore.getDraftOperationId(
            scope.companyId.value, scope.actorId.value, scope.deviceId.value, formType, cachedPaymentValidation(formType)
        )
    }

    override fun rotateDraftOperationId(formType: String): String {
        val scope = requireActiveScope()
        return commandStore.rotateDraftOperationId(
            scope.companyId.value, scope.actorId.value, scope.deviceId.value, formType, cachedPaymentValidation(formType)
        )
    }

    override fun cancelDraftOperation(formType: String) {
        rotateDraftOperationId(formType)
    }

    override fun getActiveDraftPayload(formType: String): StrictJsonObject? {
        val scope = authScopeProvider?.invoke() ?: return null
        ensureCachedPaymentCompletionValidated(scope, formType)
        return commandStore.getActiveDraftPayload(scope.companyId.value, scope.actorId.value, scope.deviceId.value, formType)
    }

    override fun getActiveDraftRecord(formType: String): MerchantCommandRecord? {
        val scope = authScopeProvider?.invoke() ?: return null
        ensureCachedPaymentCompletionValidated(scope, formType)
        return commandStore.getActiveDraftRecord(scope.companyId.value, scope.actorId.value, scope.deviceId.value, formType)
    }

    override fun getLatestCompletedShiftClose(): ShiftCloseResponse? {
        val scope = authScopeProvider?.invoke() ?: return null
        val record = commandStore.getLatestCompletedCommand(
            companyId = scope.companyId.value,
            actorId = scope.actorId.value,
            deviceId = scope.deviceId.value,
            commandType = "shift.close"
        ) ?: return null

        val snapBytes = commandStore.decryptSnapshot(record) ?: return null
        val jsonStr = String(snapBytes, StandardCharsets.UTF_8)
        val obj = try {
            StrictJsonParser.parse(jsonStr).asObject()
        } catch (_: Exception) {
            return null
        }
        val response = try {
            ShiftCloseResponse.fromStrictJson(obj)
        } catch (_: Exception) {
            return null
        }

        // Account relevance check:
        val payloadObj = try {
            val payloadBytes = commandStore.decryptPayload(record)
            StrictJsonParser.parse(String(payloadBytes, StandardCharsets.UTF_8)).asObject()
        } catch (_: Exception) { null }

        val payloadAccountId = payloadObj?.properties?.get("cash_account_id")?.asString()
        if (payloadAccountId != null && payloadAccountId != approvedCashAccount.id.value) {
            return null
        }

        // Business date / session relevance check:
        val effectiveShiftDate = _shiftSummaryFlow.value?.shiftDate ?: WesternFormatters.currentDate()
        if (response.shiftDate != effectiveShiftDate) {
            // Belongs to a different / previous date -> do not mark current shift closed ("newday no falseclosed")
            return null
        }

        return response
    }

    override fun isDraftRecovering(formType: String): Boolean {
        val scope = authScopeProvider?.invoke() ?: return false
        ensureCachedPaymentCompletionValidated(scope, formType)
        val record = commandStore.getActiveDraftRecord(scope.companyId.value, scope.actorId.value, scope.deviceId.value, formType) ?: return false
        return record.status == CommandStatus.PENDING ||
            record.status == CommandStatus.SUBMITTED ||
            record.status == CommandStatus.UNKNOWN_ACK
    }

    override suspend fun loadCustomerDetail(customerId: String): Result<Customer> = getCustomerDetail(customerId)

    override suspend fun refreshHomeMetrics(): Result<HomeResponse?> = withContext(ioDispatcher) {
        val client = merchantReadClient ?: return@withContext Result.failure(IllegalStateException("MerchantReadClient not configured"))
        try {
            val res = client.getHome(companyId, retainedGeneration = retainedGeneration)
            when (res) {
                is MerchantReadResult.Success -> {
                    _homeMetricsFlow.value = res.value
                    Result.success(res.value)
                }
                is MerchantReadResult.Forbidden -> {
                    // Lacks permission (e.g. pos.home.view or reports permission) -> remains unknown, no fake 0
                    _homeMetricsFlow.value = null
                    Result.success(null)
                }
                is MerchantReadResult.AuthDenial -> {
                    _homeMetricsFlow.value = null
                    Result.failure(SecurityException("Authentication denied: ${res.error?.message ?: "UNAUTHENTICATED"}"))
                }
                is MerchantReadResult.NetworkError -> {
                    // Preserves existing or null on network error, never fake 0
                    Result.failure(java.io.IOException("Network error loading home metrics: ${res.message}"))
                }
                is MerchantReadResult.SchemaError -> {
                    Result.failure(IllegalStateException("Schema error in home metrics: ${res.message}"))
                }
                else -> {
                    Result.failure(RuntimeException("Failed to refresh home metrics: $res"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun refreshShiftSummary(): Result<ShiftSummaryReportResponse?> = withContext(ioDispatcher) {
        val client = merchantReadClient ?: return@withContext Result.failure(IllegalStateException("MerchantReadClient not configured"))
        try {
            val res = client.getShiftSummary(companyId, retainedGeneration = retainedGeneration)
            when (res) {
                is MerchantReadResult.Success -> {
                    _shiftSummaryFlow.value = res.value
                    rehydrateCompletedShiftClose()
                    Result.success(res.value)
                }
                is MerchantReadResult.Forbidden -> {
                    // Missing reports.financial.view permission -> remains null, no fake 0
                    _shiftSummaryFlow.value = null
                    Result.success(null)
                }
                is MerchantReadResult.AuthDenial -> {
                    _shiftSummaryFlow.value = null
                    Result.failure(SecurityException("Authentication denied: ${res.error?.message ?: "UNAUTHENTICATED"}"))
                }
                is MerchantReadResult.NetworkError -> {
                    Result.failure(java.io.IOException("Network error loading shift summary: ${res.message}"))
                }
                is MerchantReadResult.SchemaError -> {
                    Result.failure(IllegalStateException("Schema error in shift summary: ${res.message}"))
                }
                else -> {
                    Result.failure(RuntimeException("Failed to refresh shift summary: $res"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun closeShift(
        countedCash: BigDecimal,
        notes: String?,
        cashAccountId: String?,
        commandId: String
    ): Result<ShiftCloseResponse> = withContext(ioDispatcher) {
        val before = try {
            requireActiveScope()
        } catch (e: SecurityException) {
            return@withContext Result.failure(e)
        }

        // 1. Permission check (reports.financial.view ONLY - least privilege)
        if (permissions.isNotEmpty() && !permissions.contains("reports.financial.view")) {
            return@withContext Result.failure(
                SecurityException("Action is unauthorized. Missing required reports permission.")
            )
        }

        // 2. Exact decimal validation (no negative, scale <= minor units, exact arithmetic)
        if (countedCash.signum() < 0) {
            return@withContext Result.failure(
                IllegalArgumentException("Counted physical cash must be non-negative")
            )
        }
        if (countedCash.scale() > currencyMinorUnits) {
            return@withContext Result.failure(
                IllegalArgumentException("Counted physical cash precision (${countedCash.scale()}) exceeds currency minor units ($currencyMinorUnits)")
            )
        }

        // 3. Resolve cash account ID
        val effectiveCashAccount = if (cashAccountId != null) {
            if (!cashAccountId.matches(Regex("""^[0-7][0-9A-HJKMNP-TV-Z]{25}$"""))) {
                return@withContext Result.failure(
                    IllegalArgumentException("The specified cash account ID must be a canonical 26-character ULID string.")
                )
            }
            ServerUlid.parse(cashAccountId)
        } else {
            approvedCashAccount.id
        }

        val effectiveCommandId = if (commandId.isNotBlank()) commandId else getDraftOperationId("shift_close")
        val idempotencyKey = "shiftclose-$effectiveCommandId"
        if (idempotencyKey.length > 128) {
            return@withContext Result.failure(
                IllegalArgumentException("Idempotency key exceeds 128 characters: $idempotencyKey")
            )
        }

        val effectiveDate = _shiftSummaryFlow.value?.shiftDate ?: WesternFormatters.currentDate()

        // 4. Construct canonical payload JSON
        val payloadMap = mutableMapOf<String, StrictJsonElement>(
            "counted_cash" to StrictJsonString(countedCash.toPlainString()),
            "cash_account_id" to StrictJsonString(effectiveCashAccount.value),
            "currency" to StrictJsonString(baseCurrency),
            "currency_minor_units" to StrictJsonNumber(currencyMinorUnits.toString(), isInt = true),
            "shift_date" to StrictJsonString(effectiveDate)
        )
        if (!notes.isNullOrBlank()) {
            payloadMap["notes"] = StrictJsonString(notes.trim())
        }
        val payloadObj = StrictJsonObject(payloadMap)
        val payloadBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(payloadObj)
        val payloadDigest = "sha256:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(payloadBytes).joinToString("") { "%02x".format(it) }

        // 5. Pre-POST conflict check: existing command with same key but different payload
        val existingCmd = commandStore.findCommand(
            before.companyId.value,
            before.actorId.value,
            before.deviceId.value,
            idempotencyKey
        )
        if (existingCmd != null && existingCmd.payloadDigest != payloadDigest) {
            return@withContext Result.failure(
                IllegalStateException("Idempotency key [$idempotencyKey] was previously committed with different parameters")
            )
        }

        val activeDraft = commandStore.getActiveDraftRecord(before.companyId.value, before.actorId.value, before.deviceId.value, "shift_close")
        if (activeDraft != null && activeDraft.commandId != effectiveCommandId) {
            return@withContext Result.failure(
                IllegalStateException("Resolve the existing cash count attempt before starting another")
            )
        }
        if (activeDraft != null && activeDraft.commandId == effectiveCommandId && activeDraft.payloadDigest != payloadDigest) {
            return@withContext Result.failure(
                IllegalStateException("Draft command [$effectiveCommandId] has different parameters")
            )
        }

        // 6. Concurrent admission lock: prevents duplicate closures concurrently
        if (!shiftCloseAdmissionLock.compareAndSet(false, true)) {
            return@withContext Result.failure(
                IllegalStateException("Shift close is already in flight")
            )
        }

        try {
            val postCall: suspend (String) -> Result<StrictJsonObject> = { key ->
                apiClient.closeShift(
                    companyId = before.companyId,
                    countedCash = countedCash,
                    idempotencyKey = key,
                    cashAccountId = effectiveCashAccount,
                    notes = notes?.trim(),
                    currencyMinorUnits = currencyMinorUnits
                )
            }

            val validateShiftCloseResponse: (StrictJsonObject) -> Result<StrictJsonObject> = { snap ->
                val closureId = snap.properties["shift_closure_id"]?.asString() ?: snap.properties["id"]?.asString()
                if (closureId.isNullOrBlank()) {
                    Result.failure(IllegalStateException("Server response missing shift closure id"))
                } else {
                    val varianceStatus = snap.properties["variance_status"]?.asString()
                    if (varianceStatus !in listOf("EXACT", "OVERAGE", "SHORTAGE")) {
                        Result.failure(IllegalStateException("Server returned invalid variance status: $varianceStatus"))
                    } else {
                        Result.success(snap)
                    }
                }
            }

            val durableRes = executeDurableCommand(
                commandType = "shift.close",
                commandId = effectiveCommandId,
                idempotencyKey = idempotencyKey,
                canonicalPayloadBytes = payloadBytes,
                isReceiptStatusChecked = false,
                validateResponse = validateShiftCloseResponse,
                postAction = postCall
            )

            if (durableRes.isSuccess) {
                val snapshotObj = durableRes.getOrThrow()
                val response = ShiftCloseResponse.fromStrictJson(snapshotObj)
                _shiftCloseSuccessFlow.value = response
                // Authoritative validated success: rotate draft only after persisted ACK!
                rotateDraftOperationId("shift_close")
                try {
                    refreshShiftSummary()
                } catch (_: Exception) {}
                Result.success(response)
            } else {
                val err = durableRes.exceptionOrNull() ?: RuntimeException("Shift close execution failed")
                Result.failure(err)
            }
        } finally {
            shiftCloseAdmissionLock.set(false)
        }
    }

    override suspend fun refreshTreasuryReport(): Result<TreasuryReportResponse?> = withContext(ioDispatcher) {
        val client = merchantReadClient ?: return@withContext Result.failure(IllegalStateException("MerchantReadClient not configured"))
        try {
            val res = client.getTreasury(companyId, retainedGeneration = retainedGeneration)
            when (res) {
                is MerchantReadResult.Success -> {
                    _treasuryReportFlow.value = res.value
                    Result.success(res.value)
                }
                is MerchantReadResult.Forbidden -> {
                    // Missing reports.financial.view permission -> remains null, no fake 0
                    _treasuryReportFlow.value = null
                    Result.success(null)
                }
                is MerchantReadResult.AuthDenial -> {
                    _treasuryReportFlow.value = null
                    Result.failure(SecurityException("Authentication denied: ${res.error?.message ?: "UNAUTHENTICATED"}"))
                }
                is MerchantReadResult.NetworkError -> {
                    Result.failure(java.io.IOException("Network error loading treasury report: ${res.message}"))
                }
                is MerchantReadResult.SchemaError -> {
                    Result.failure(IllegalStateException("Schema error in treasury report: ${res.message}"))
                }
                else -> {
                    Result.failure(RuntimeException("Failed to refresh treasury report: $res"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class ResolvedPaymentCommand(
        val commandId: String,
        val idempotencyKey: String,
        val effectivePaymentDate: String,
        val effectiveCashAccount: ServerUlid,
        val activeDraft: MerchantCommandRecord?,
        val persistedDraftId: String,
        val payloadBytes: ByteArray,
        val payloadDigest: String
    )

    private fun decryptDraftPayloadObject(record: MerchantCommandRecord?): StrictJsonObject? {
        if (record == null) return null
        return runCatching {
            val bytes = commandStore.decryptPayload(record)
            StrictJsonParser.parse(String(bytes, StandardCharsets.UTF_8)).asObject()
        }.getOrNull()
    }

    private fun prepareDurablePaymentSubmission(
        scope: CurrentAuthScope,
        formType: String,
        keyPrefix: String,
        partyFieldName: String,
        partyId: String,
        requestedPaymentId: String,
        amountExact: BigDecimal,
        note: String,
        cashAccountId: String?,
        paymentDate: String?,
        allocations: List<PaymentAllocationInput>
    ): Result<ResolvedPaymentCommand> {
        val persistedDraftId = getDraftOperationId(formType)
        val activeDraft = commandStore.getActiveDraftRecord(
            scope.companyId.value,
            scope.actorId.value,
            scope.deviceId.value,
            formType
        )
        if (activeDraft != null && requestedPaymentId.isNotBlank() && requestedPaymentId != activeDraft.commandId) {
            return Result.failure(
                IllegalStateException(
                    "Resolve the existing $formType [${activeDraft.commandId}] (${activeDraft.status}) before starting another"
                )
            )
        }
        val commandId = when {
            activeDraft != null -> activeDraft.commandId
            requestedPaymentId.isNotBlank() -> requestedPaymentId
            else -> persistedDraftId
        }
        val idempotencyKey = "$keyPrefix-$commandId"
        if (idempotencyKey.length > 128) {
            return Result.failure(IllegalArgumentException("Idempotency key exceeds 128 characters: $idempotencyKey"))
        }

        val draftPayloadObj = decryptDraftPayloadObject(activeDraft)
        val effectivePaymentDate = paymentDate
            ?: draftPayloadObj?.properties?.get("payment_date")?.asString()
            ?: WesternFormatters.currentDate()
        val effectiveCashAccount = if (!cashAccountId.isNullOrBlank()) {
            try {
                ServerUlid.parse(cashAccountId)
            } catch (e: IllegalArgumentException) {
                return Result.failure(e)
            }
        } else {
            draftPayloadObj?.properties?.get("money_account_id")?.asString()
                ?.takeIf { ServerUlid.isValid(it) }
                ?.let { ServerUlid.parse(it) }
                ?: approvedCashAccount.id
        }

        val allocElements = allocations.map { alloc ->
            StrictJsonObject(
                mapOf(
                    "target_type" to StrictJsonString(alloc.targetType),
                    "target_id" to StrictJsonString(alloc.targetId),
                    "amount" to StrictJsonString(alloc.amountExact.setScale(6, RoundingMode.HALF_UP).toPlainString())
                )
            )
        }
        val payloadObj = StrictJsonObject(
            mapOf(
                partyFieldName to StrictJsonString(partyId),
                "amount" to StrictJsonString(amountExact.setScale(6, RoundingMode.HALF_UP).toPlainString()),
                "currency" to StrictJsonString(baseCurrency),
                "payment_date" to StrictJsonString(effectivePaymentDate),
                "money_account_id" to StrictJsonString(effectiveCashAccount.value),
                "notes" to StrictJsonString(note),
                "allocations" to StrictJsonArray(allocElements)
            )
        )
        val payloadBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(payloadObj)
        val payloadDigest = "sha256:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(payloadBytes).joinToString("") { "%02x".format(it) }

        val existingCmd = commandStore.findCommand(scope.companyId.value, scope.actorId.value, scope.deviceId.value, idempotencyKey)
        if (existingCmd != null && existingCmd.payloadDigest != payloadDigest) {
            return Result.failure(IllegalStateException("Idempotency key [$idempotencyKey] was previously committed with different parameters"))
        }
        if (activeDraft != null && activeDraft.commandId == commandId && activeDraft.payloadDigest != payloadDigest) {
            return Result.failure(IllegalStateException("Draft command [$commandId] has different parameters"))
        }

        return Result.success(
            ResolvedPaymentCommand(
                commandId = commandId,
                idempotencyKey = idempotencyKey,
                effectivePaymentDate = effectivePaymentDate,
                effectiveCashAccount = effectiveCashAccount,
                activeDraft = activeDraft,
                persistedDraftId = persistedDraftId,
                payloadBytes = payloadBytes,
                payloadDigest = payloadDigest
            )
        )
    }

    companion object {
        // Atomic in-flight admission across coroutines and repository instances in the process
        private val inFlightCommands = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val inFlightFormTypes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun isInFlight(companyId: String, actorId: String, deviceId: String, commandId: String): Boolean {
            return inFlightCommands.contains("$companyId:$actorId:$deviceId:$commandId")
        }

        internal fun clearInFlightForTest() {
            inFlightCommands.clear()
            inFlightFormTypes.clear()
        }

        suspend fun fromReadReady(
            readReady: StaffAuthState.ReadReady,
            outboxRepository: OutboxRepository,
            transport: ReadClientTransport,
            baseOrigin: String,
            commandStore: MerchantCommandStore,
            authScopeProvider: (() -> CurrentAuthScope?)? = null,
            onClose: (() -> Unit)? = null,
            authoritativeMinorUnits: Int? = null,
            merchantReadClient: MerchantReadClient? = null,
            offlineLeaseStore: OfflineLeaseStore
        ): ProductionTraderRepository {
            val bootstrap = readReady.bootstrap
            val session = readReady.session
            val role = when (session.roleLabel?.lowercase(Locale.ROOT)) {
                "owner" -> UserRole.OWNER
                "manager" -> UserRole.MANAGER
                else -> UserRole.CASHIER
            }

            val retainedScope = requireNotNull(authScopeProvider?.invoke()) { "Authenticated scope is required" }
            val tokenProvider: () -> String = {
                val current = requireNotNull(authScopeProvider?.invoke()) { "Authenticated scope is no longer active" }
                require(current.companyId == bootstrap.companyId && current.actorId == session.actorUlid && current.deviceId == session.deviceUuid && current.generation == retainedScope.generation)
                current.token
            }

            val apiClient = MobileMerchantApiClient(
                transport = transport,
                baseOrigin = baseOrigin,
                tokenProvider = tokenProvider
            )

            val saleSyncGateway = HttpSaleSyncGateway(
                transport = transport,
                baseOrigin = baseOrigin,
                tokenProvider = tokenProvider
            )

            val syncCoordinator = SaleSyncCoordinatorImpl(
                outboxRepository = outboxRepository,
                gateway = saleSyncGateway
            )

            val readTokenProvider = MerchantReadTokenProvider { reqCompanyId, retainedGen ->
                val current = authScopeProvider?.invoke() ?: return@MerchantReadTokenProvider null
                if (current.companyId != reqCompanyId || current.actorId != session.actorUlid || current.deviceId != session.deviceUuid) {
                    return@MerchantReadTokenProvider null
                }
                if (retainedGen != null && current.generation != retainedGen) {
                    return@MerchantReadTokenProvider null
                }
                current.token
            }

            val effectiveReadClient = merchantReadClient ?: MerchantReadClient(
                transport = transport,
                baseOrigin = baseOrigin,
                allowHttpLoopback = false,
                tokenProvider = readTokenProvider
            )

            // Obtain authoritative business settings from server
            val settingsRes = apiClient.getBusinessSettings(bootstrap.companyId)
            val settingsObj = settingsRes.getOrNull()

            val cityAr = settingsObj?.properties?.get("city_ar")?.asString() ?: ""
            val cityEn = settingsObj?.properties?.get("city_en")?.asString() ?: ""
            val phone = settingsObj?.properties?.get("phone")?.asString() ?: ""
            val taxNumber = settingsObj?.properties?.get("tax_number")?.asString() ?: ""

            val cmu = authoritativeMinorUnits ?: bootstrap.currencyMinorUnits ?: run {
                val elem = settingsObj?.properties?.get("default_currency_minor_units")
                    ?: throw (settingsRes.exceptionOrNull() ?: IllegalStateException("Missing currency precision in business settings"))
                val parsed = when (elem) {
                    is com.example.data.mobile.contract.StrictJsonNumber -> elem.asInt()
                    is com.example.data.mobile.contract.StrictJsonString -> elem.value.toIntOrNull()
                    else -> null
                } ?: throw IllegalStateException("Invalid default_currency_minor_units type in business settings")
                require(parsed in 0..6) { "default_currency_minor_units out of range 0..6: $parsed" }
                parsed
            }

            val realBusinessInfo = BusinessInfo(
                nameAr = bootstrap.companyName,
                nameEn = bootstrap.companyName,
                cityAr = cityAr,
                cityEn = cityEn,
                phone = phone,
                taxNumber = taxNumber,
                defaultCurrency = bootstrap.baseCurrency,
                defaultCurrencyMinorUnits = cmu,
                defaultTaxPercent = 0.0,
                laravelSyncStatus = "متصل بالخادم (Laravel Mobile API v1/v2)"
            )

            return ProductionTraderRepository(
                companyId = bootstrap.companyId,
                companyName = bootstrap.companyName,
                actorId = session.actorUlid,
                actorName = session.actorName ?: bootstrap.staff.name,
                role = role,
                permissions = bootstrap.staff.permissions.toSet(),
                deviceId = session.deviceUuid,
                baseCurrency = bootstrap.baseCurrency,
                currencyMinorUnits = cmu,
                defaultWarehouse = bootstrap.defaultWarehouse,
                approvedCashAccount = bootstrap.approvedCashAccount,
                walkInCustomerId = bootstrap.walkInCustomerId,
                bootstrapUnits = bootstrap.units,
                bootstrapTaxes = bootstrap.taxes,
                apiClient = apiClient,
                outboxRepository = outboxRepository,
                saleSyncGateway = saleSyncGateway,
                syncCoordinator = syncCoordinator,
                authScopeProvider = authScopeProvider,
                onClose = onClose,
                initialBusinessInfo = realBusinessInfo,
                commandStore = commandStore,
                merchantReadClient = effectiveReadClient,
                autoRefresh = false,
                offlineLeaseStore = offlineLeaseStore
            )
        }

        fun forTesting(
            apiClient: MobileMerchantApiClient,
            companyId: ServerUlid,
            actorId: ServerUlid,
            deviceId: ClientUuid,
            warehouseId: ServerUlid = ServerUlid.parse("01JMB000000000000000000003"),
            commandStore: MerchantCommandStore = InMemoryMerchantCommandStore(),
            authScopeProvider: (() -> CurrentAuthScope?)? = null,
            merchantReadClient: MerchantReadClient? = null,
            baseCurrency: String = "ILS",
            currencyMinorUnits: Int = 2,
            permissions: Set<String> = setOf("pos.sale", "purchases.manage", "expenses.manage", "reports.financial.view"),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            outboxRepository: OutboxRepository? = null,
            saleSyncGateway: SaleSyncGateway? = null,
            bootstrapTaxes: List<BootstrapTax> = emptyList(),
            offlineLeaseStore: OfflineLeaseStore = InMemoryOfflineLeaseStore()
        ): ProductionTraderRepository {
            val readTokenProvider = MerchantReadTokenProvider { reqCompanyId, retainedGen ->
                val current = (authScopeProvider ?: { CurrentAuthScope(companyId = companyId, actorId = actorId, deviceId = deviceId, token = "test-token", generation = 1L) }).invoke()
                    ?: return@MerchantReadTokenProvider null
                if (current.companyId != reqCompanyId || current.actorId != actorId || current.deviceId != deviceId) {
                    return@MerchantReadTokenProvider null
                }
                if (retainedGen != null && current.generation != retainedGen) {
                    return@MerchantReadTokenProvider null
                }
                current.token
            }

            val effectiveReadClient = merchantReadClient ?: MerchantReadClient(
                transport = object : com.example.data.mobile.readclient.ReadClientTransport {
                    override fun execute(request: com.example.data.mobile.readclient.TransportRequest): com.example.data.mobile.readclient.TransportResponse {
                        return com.example.data.mobile.readclient.TransportResponse(200, emptyMap(), "{}")
                    }
                },
                baseOrigin = "https://api.example.com",
                tokenProvider = readTokenProvider
            )

            return ProductionTraderRepository(
                companyId = companyId,
                companyName = "Test Company",
                actorId = actorId,
                actorName = "Test Actor",
                role = UserRole.OWNER,
                permissions = permissions,
                deviceId = deviceId,
                baseCurrency = baseCurrency,
                currencyMinorUnits = currencyMinorUnits,
                defaultWarehouse = DefaultWarehouse(id = warehouseId, code = "WH-01", name = "Main Warehouse"),
                approvedCashAccount = ApprovedCashAccount(id = ServerUlid.parse("01JMB000000000000000000004"), code = "CASH-01", name = "Main Drawer", currency = baseCurrency),
                walkInCustomerId = ServerUlid.parse("01JMB000000000000000000005"),
                bootstrapUnits = emptyList(),
                bootstrapTaxes = bootstrapTaxes,
                apiClient = apiClient,
                outboxRepository = outboxRepository,
                saleSyncGateway = saleSyncGateway,
                ioDispatcher = ioDispatcher,
                authScopeProvider = authScopeProvider ?: { CurrentAuthScope(companyId = companyId, actorId = actorId, deviceId = deviceId, token = "test-token", generation = 1L) },
                commandStore = commandStore,
                merchantReadClient = effectiveReadClient,
                autoRefresh = false,
                offlineLeaseStore = offlineLeaseStore
            )
        }
    }
}
