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
import com.example.data.mobile.contract.v2.OfflineSaleLineV2
import com.example.data.mobile.contract.v2.OnlineSaleContext
import com.example.data.mobile.contract.v2.OnlineSaleIntentLine
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonBoolean
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
    private val autoRefresh: Boolean = true
) : TraderRepository {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val retainedGeneration = authScopeProvider?.invoke()?.generation

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

        if (record.status == CommandStatus.COMPLETED && record.responseSnapshot != null) {
            val activeScope = requireActiveScope()
            if (record.companyId != activeScope.companyId.value ||
                record.actorId != activeScope.actorId.value ||
                record.deviceId != activeScope.deviceId.value
            ) {
                throw SecurityException("Cached command belongs to different authenticated scope")
            }
            return try {
                val snapBytes = commandStore.decryptSnapshot(record)
                    ?: throw IllegalStateException("Decrypted snapshot was null")
                val jsonStr = String(snapBytes, StandardCharsets.UTF_8)
                val parsed = StrictJsonParser.parse(jsonStr).asObject()
                if (validateResponse != null) {
                    val valRes = validateResponse(parsed)
                    if (valRes.isFailure) {
                        val valErr = valRes.exceptionOrNull() ?: IllegalStateException("Authoritative response validation failed")
                        commandStore.markUnknownAck(scopeBefore.companyId.value, commandId, valErr.message ?: "Authoritative response validation failed")
                        return Result.failure(valErr)
                    }
                }
                Result.success(parsed)
            } catch (ex: Exception) {
                if (isReceiptStatusChecked) {
                    checkReceiptStatusOnServer(scopeBefore.companyId, idempotencyKey, commandId, validateResponse)
                        ?: Result.failure(IllegalStateException("Failed to parse cached command response: ${ex.message}"))
                } else {
                    Result.failure(ex)
                }
            }
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
        }

        val postResult = postAction(record.idempotencyKey)

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

        if (isReceiptStatusChecked) {
            val statusAfterError = checkReceiptStatusOnServer(scopeAfter.companyId, idempotencyKey, commandId, validateResponse)
            if (statusAfterError != null) {
                return statusAfterError
            }
        }

        val isDefinitiveFailure = errMsg.contains("HTTP 4") || errMsg.contains("VALIDATION_FAILED") || errMsg.contains("FORBIDDEN")
        if (isDefinitiveFailure) {
            commandStore.markFailed(scopeAfter.companyId.value, commandId, errMsg)
        } else {
            commandStore.markUnknownAck(scopeAfter.companyId.value, commandId, errMsg)
        }

        return Result.failure(err ?: RuntimeException(errMsg))
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
        val totalExact = obj.properties["total_ils"]?.asString()?.let { BigDecimal(it) }
            ?: obj.properties["total"]?.asString()?.let { BigDecimal(it) }
            ?: obj.properties["total_amount"]?.asString()?.let { BigDecimal(it) }
            ?: throw IllegalArgumentException("Authoritative purchase total is required")
        val paidExact = obj.properties["paid_ils"]?.asString()?.let { BigDecimal(it) }
            ?: obj.properties["paid"]?.asString()?.let { BigDecimal(it) }
            ?: obj.properties["paid_amount"]?.asString()?.let { BigDecimal(it) }
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
                    val unitCostExact = elem.properties["unit_cost_ils"]?.asString()?.let { BigDecimal(it) }
                        ?: elem.properties["unit_cost"]?.asString()?.let { BigDecimal(it) }

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
            lines = linesList
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

    private val businessInfo = initialBusinessInfo ?: BusinessInfo(
        nameAr = companyName,
        nameEn = companyName,
        cityAr = "",
        cityEn = "",
        phone = "",
        taxNumber = "",
        defaultCurrency = baseCurrency,
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

    private val _invoicesHasMoreGapFlow = MutableStateFlow<Boolean>(false)
    override val invoicesHasMoreGapFlow: StateFlow<Boolean> = _invoicesHasMoreGapFlow.asStateFlow()

    private var activeInvoicesQuery: SalesInvoicesQuery = SalesInvoicesQuery()
    private var activeInvoicesGeneration: Long? = null
    private var activeInvoicesCompanyId: ServerUlid = companyId

    init {
        if (autoRefresh) refreshFromBackend()
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

    override suspend fun refreshProducts(): Result<Unit> = withContext(ioDispatcher) {
        try {
            val allItems = mutableListOf<ProductCatalogItem>()
            var cursor: String? = null
            var pages = 0
            do {
                requireActiveScope()
                val page = apiClient.getProducts(companyId, limit = 50, cursor = cursor).getOrThrow()
                require(page.companyId == companyId)
                allItems.addAll(page.products)
                cursor = page.nextCursor
                pages++
                check(!page.hasMore || cursor != null)
            } while (cursor != null && pages < 100)
            requireActiveScope()
            check(cursor == null)
            val mapped = allItems.map { item -> mapProductFromCatalog(item) }
            _productsFlow.value = mapped
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun refreshCustomers(): Result<Unit> = withContext(ioDispatcher) {
        try {
            val allItems = mutableListOf<CustomerDirectoryItem>()
            var cursor: String? = null
            var pages = 0
            do {
                requireActiveScope()
                val page = apiClient.getCustomers(companyId, limit = 50, cursor = cursor).getOrThrow()
                require(page.companyId == companyId)
                allItems.addAll(page.customers)
                cursor = page.nextCursor
                pages++
                check(!page.hasMore || cursor != null)
            } while (cursor != null && pages < 100)
            requireActiveScope()
            check(cursor == null)
            val mapped = allItems.map { item ->
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
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun refreshSuppliers(search: String?): Result<Unit> = withContext(ioDispatcher) {
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
            return@withContext Result.failure(SecurityException("Scope validation failed: current session is superseded or revoked."))
        }
        val before = requireActiveScope()
        try {
            val res = apiClient.getSuppliers(companyId, search = search)
            if (res.isSuccess) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                check(requireActiveScope() == before) { "Session changed during supplier refresh" }

                val root = res.getOrThrow()
                val hasMore = (root.properties["has_more"] as? StrictJsonBoolean)?.value
                    ?: (root.properties["has_more"].toNullableString()?.toBooleanStrictOrNull() ?: false)
                _suppliersHasMoreFlow.value = hasMore
                val dataElem = root.properties["suppliers"] ?: root.properties["data"] ?: root.properties["items"]
                if (dataElem is com.example.data.mobile.contract.StrictJsonArray) {
                    val list = (0 until dataElem.size).mapNotNull { i ->
                        val obj = dataElem[i] as? com.example.data.mobile.contract.StrictJsonObject ?: return@mapNotNull null
                        val id = obj.properties["id"].toNullableString() ?: return@mapNotNull null
                        val name = obj.properties["name"].toNullableString()
                            ?: obj.properties["name_ar"].toNullableString()
                            ?: obj.properties["name_en"].toNullableString()
                            ?: return@mapNotNull null
                        val company = obj.properties["company_name"].toNullableString() ?: obj.properties["company"].toNullableString()
                        val phone = obj.properties["phone"].toNullableString()
                        val balanceStr = obj.properties["balance_owed"].toNullableString()
                            ?: obj.properties["owed"].toNullableString()
                            ?: obj.properties["balance"].toNullableString()
                        val balanceExact = balanceStr?.let { try { BigDecimal(it) } catch (_: Exception) { null } }
                        val advancesStr = obj.properties["advances"].toNullableString()
                        val advancesExact = advancesStr?.let { try { BigDecimal(it) } catch (_: Exception) { null } }
                        Supplier(
                            id = id,
                            nameAr = name,
                            nameEn = name,
                            companyAr = company,
                            companyEn = company,
                            phone = phone,
                            balanceOwedExact = balanceExact,
                            advancesExact = advancesExact
                        )
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
        val currentScope = authScopeProvider?.invoke()
        if (authScopeProvider != null && (currentScope == null || currentScope.companyId != companyId || currentScope.actorId != actorId || currentScope.deviceId != deviceId)) {
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
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
        val priceExact = item.unitPrice?.value ?: defaultPackaging?.salePrice?.value ?: item.defaultUnit.salePrice?.value
        val stockCountExact = item.availableQuantity.value
        val conversionExact = defaultPackaging?.conversionToBase?.value ?: item.defaultUnit.conversionToBase.value
        val productUnitUlid = defaultPackaging?.productUnitId?.value ?: item.defaultUnit.productUnitId.value

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
            taxRateId = item.taxRateId?.value,
            taxRateExact = item.taxRate?.value
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
        val client = merchantReadClient ?: return@withContext Result.failure(IllegalStateException("MerchantReadClient not configured"))
        val ulid = try {
            ServerUlid.parse(productId)
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Invalid productId ULID: '$productId'", e))
        }
        try {
            val res = client.getProductHistory(companyId, ulid, limit, retainedGeneration = retainedGeneration)
            when (res) {
                is MerchantReadResult.Success -> {
                    val history = res.value
                    val mapped = history.movements.map { item ->
                        MerchantReadAdapter.toDomainStockMovement(item, productId)
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

    override suspend fun recordSale(invoice: InvoiceRecord): Result<InvoiceRecord> = withContext(ioDispatcher) {
        try { requireActiveScope() } catch (denied: SecurityException) { return@withContext Result.failure(denied) }

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

            // Exact selected packaging unit conversion from server catalog
            val conversionToBaseStr = basketItem.product.conversionToBaseExact.setScale(6, RoundingMode.HALF_UP).toPlainString()

            // Exact quantity preserving up to 6 decimals
            val qtyStr = basketItem.quantity.setScale(6, RoundingMode.HALF_UP).toPlainString()

            // Exact unit price from basket item override (not un-overridden catalog price)
            val priceStr = basketItem.unitPriceExact.setScale(6, RoundingMode.HALF_UP).toPlainString()

            // ExplicitNoTax constraint: if taxRateId is null, taxRate must be "0.000000" and taxInclusive false
            val taxRateId = basketItem.product.taxRateId?.let { if (ServerUlid.isValid(it)) ServerUlid.parse(it) else null }
            val taxRateStr = if (taxRateId != null) {
                basketItem.product.taxRateExact?.setScale(6, RoundingMode.HALF_UP)?.toPlainString() ?: "0.000000"
            } else {
                "0.000000"
            }
            val taxInclusive = false

            lines.add(
                OnlineSaleIntentLine(
                    productId = prodUlid,
                    productUnitId = productUnitUlid,
                    conversionToBase = PositiveDecimalQuantity.fromPositiveString(conversionToBaseStr),
                    quantity = PositiveDecimalQuantity.fromPositiveString(qtyStr),
                    unitPrice = MoneyAmount.fromNonnegativeString(priceStr),
                    taxRateId = taxRateId,
                    taxRate = TaxPercentage.fromString(taxRateStr),
                    taxInclusive = taxInclusive
                )
            )
        }

        val settlementMode = if (invoice.isPaidInFull) "full" else if (tenderedExact > BigDecimal.ZERO) "partial" else "unpaid"
        val paymentAmountExact = tenderedExact.min(netTotalExact).setScale(6, RoundingMode.HALF_UP).toPlainString()
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
        val digest = CanonicalJsonSerializer.computeDigest(profile)

        // 5. Pre-network durable Room outbox capture FIRST without fabricated device lease
        val outbox = outboxRepository ?: return@withContext Result.failure(IllegalStateException("OutboxRepository not configured"))
        val gateway = saleSyncGateway ?: return@withContext Result.failure(IllegalStateException("SaleSyncGateway not configured"))

        val captureResult = outbox.captureOnlineSaleIntent(
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            currencyMinorUnits = currencyMinorUnits,
            profile = profile,
            rawCanonicalProfileUtf8 = canonicalByteArray
        )

        when (captureResult) {
            is CaptureResult.Conflict -> {
                return@withContext Result.failure(IllegalStateException("Local outbox conflict for event ${clientEventId.value}"))
            }
            is CaptureResult.ScopeRejected -> {
                return@withContext Result.failure(SecurityException("Outbox scope rejected: ${captureResult.message}"))
            }
            else -> { /* Created or Duplicate replay */ }
        }

        // 6. Revalidate retained company, actor, device under current auth scope before any POST
        try { requireActiveScope() } catch (denied: SecurityException) { return@withContext Result.failure(denied) }

        // 7. Route through SaleSyncCoordinator claims/unknownack/reconcile protocol
        val coordinator = syncCoordinator ?: SaleSyncCoordinatorImpl(outbox, gateway)
        val syncResult = coordinator.synchronizeScope(
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            batchSize = 10
        )

        if (syncResult.authBlocked) {
            return@withContext Result.failure(SecurityException("Sale queue locked: authentication denied (401 UNAUTHENTICATED)."))
        }
        if (syncResult.membershipDenied) {
            return@withContext Result.failure(SecurityException("Sale rejected: forbidden tenant permission (403 FORBIDDEN)."))
        }

        // 8. Inspect authoritative persisted record from outbox
        val record = outbox.getRecord(companyId, actorId, deviceId, clientEventId)
        if (record != null && record.deliveryState.status == OutboxStatus.CONFIRMED.wireValue &&
            record.deliveryState.invoiceUlid != null && (record.deliveryState.paymentUlid != null || profile.payment.settlementMode == "unpaid")) {
            val confirmed = invoice.copy(
                clientEventId = clientEventId.value,
                serverInvoiceUlid = record.deliveryState.invoiceUlid,
                serverInvoiceNumber = null,
                serverPaymentUlid = record.deliveryState.paymentUlid,
                serverPaymentReceiptNumber = null,
                serverPostedAt = record.deliveryState.serverAcknowledgedAt ?: WesternFormatters.currentTimestamp(),
                outboxStatus = "CONFIRMED"
            )
            _invoicesFlow.update { listOf(confirmed) + it }
            return@withContext Result.success(confirmed)
        } else if (record != null && record.deliveryState.status == OutboxStatus.CONFLICT.wireValue) {
            return@withContext Result.failure(IllegalStateException("Sale resulted in server/local conflict (409 CONFLICT)."))
        } else {
            // Durable pending record in Room outbox (timeout or pending drain)
            val pending = invoice.copy(
                clientEventId = clientEventId.value,
                serverInvoiceUlid = null,
                serverInvoiceNumber = null,
                serverPaymentUlid = null,
                serverPaymentReceiptNumber = null,
                outboxStatus = "PENDING_SYNC"
            )
            _invoicesFlow.update { listOf(pending) + it }
            return@withContext Result.success(pending)
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

                    val currentLocal = _invoicesFlow.value

                    // Identify local outbox invoices that must not be dropped:
                    // Any pending/unreconciled local record, including unknown ACK events
                    val pendingOutbox = currentLocal.filter { local ->
                        !local.clientEventId.isNullOrBlank() && local.outboxStatus != "CONFIRMED"
                    }

                    // Map server list, merging with matching local outbox invoice by real ULID / ID / clientEventId
                    val serverMerged = serverList.map { serverItem ->
                        val localMatch = currentLocal.firstOrNull { local ->
                            local.serverInvoiceUlid == serverItem.id.value ||
                            local.id == serverItem.id.value ||
                            (!local.clientEventId.isNullOrBlank() && local.clientEventId == serverItem.id.value)
                        }
                        MerchantReadAdapter.toDomainInvoiceRecord(serverItem, localMatch)
                    }

                    val mergedList = pendingOutbox + serverMerged
                    _invoicesFlow.value = mergedList
                    Result.success(mergedList)
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

                    val currentLocal = _invoicesFlow.value
                    val pendingOutbox = currentLocal.filter { local ->
                        !local.clientEventId.isNullOrBlank() && local.outboxStatus != "CONFIRMED"
                    }

                    val existingDomainIds = currentLocal.mapNotNull { it.serverInvoiceUlid ?: it.id }.toSet()
                    val newDomainRecords = mutableListOf<InvoiceRecord>()
                    for (serverItem in dedupedNewServerList) {
                        if (!existingDomainIds.contains(serverItem.id.value)) {
                            val localMatch = currentLocal.firstOrNull { local ->
                                local.serverInvoiceUlid == serverItem.id.value ||
                                local.id == serverItem.id.value ||
                                (!local.clientEventId.isNullOrBlank() && local.clientEventId == serverItem.id.value)
                            }
                            newDomainRecords.add(MerchantReadAdapter.toDomainInvoiceRecord(serverItem, localMatch))
                        }
                    }

                    val updatedList = currentLocal + newDomainRecords
                    _invoicesFlow.value = updatedList
                    Result.success(updatedList)
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

    override suspend fun recordCustomerPayment(
        customerId: String,
        amountIls: Double,
        note: String,
        paymentId: String
    ): Result<CustomerTransaction> = withContext(ioDispatcher) {
        requireActiveScope()
        if (!ServerUlid.isValid(customerId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid customer ULID: $customerId"))
        }
        val amountExact = BigDecimal(amountIls.toString()).setScale(currencyMinorUnits, RoundingMode.HALF_UP)
        if (amountExact <= BigDecimal.ZERO) {
            return@withContext Result.failure(IllegalArgumentException("Payment amount must be strictly positive"))
        }

        val commandId = if (paymentId.isNotBlank()) paymentId else UUID.randomUUID().toString()
        val idempotencyKey = "custpay-$commandId"

        val payloadObj = StrictJsonObject(
            mapOf(
                "customer_id" to StrictJsonString(customerId),
                "amount" to StrictJsonString(amountExact.toPlainString()),
                "notes" to StrictJsonString(note)
            )
        )
        val payloadBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(payloadObj)

        val inFlightCounter = customerInFlightPayments.computeIfAbsent(customerId) { java.util.concurrent.atomic.AtomicInteger(0) }
        inFlightCounter.incrementAndGet()
        val postPaymentRevision = incrementCustomerRevision(customerId)

        val postCall: suspend (String) -> Result<StrictJsonObject> = { key ->
            apiClient.recordCustomerPayment(
                companyId = companyId,
                customerId = ServerUlid.parse(customerId),
                amount = amountExact,
                cashAccountId = approvedCashAccount.id,
                note = note,
                idempotencyKey = key
            )
        }

        try {
            val durableRes = executeDurableCommand(
                commandType = "customer.payment",
                commandId = commandId,
                idempotencyKey = idempotencyKey,
                canonicalPayloadBytes = payloadBytes,
                isReceiptStatusChecked = true,
                postAction = postCall
            )

            if (durableRes.isFailure) {
                return@withContext Result.failure(durableRes.exceptionOrNull() ?: RuntimeException("Customer payment failed"))
            }

            val snapshot = durableRes.getOrThrow()
            val officialId = snapshot.properties["id"]?.asString()
                ?: snapshot.properties["receipt_id"]?.asString()
                ?: return@withContext Result.failure(IllegalStateException("Server did not return payment ID"))
            val officialAmount = snapshot.properties["amount"]?.asString()?.let { BigDecimal(it) } ?: amountExact
            val postedAt = snapshot.properties["posted_at"]?.asString() ?: WesternFormatters.currentDate()

            // Authoritative customer detail from server with strict session recheck
            val snapshotBalance = snapshot.properties["balance"].toNullableBigDecimal(currencyMinorUnits)
                ?: snapshot.properties["running_balance"].toNullableBigDecimal(currencyMinorUnits)

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
                    ?: serverCustomerObj?.properties?.get("balance").toNullableBigDecimal(currencyMinorUnits)
                    ?: serverCustomerObj?.properties?.get("balance_ils").toNullableBigDecimal(currencyMinorUnits)

                if (isLatest) {
                    incrementCustomerRevision(customerId)
                    if (refreshFailed) {
                        _customersFlow.update { list ->
                            list.map { c ->
                                if (c.id == customerId) c.copy(balanceExact = null) else c
                            }
                        }
                        null
                    } else {
                        val existingCust = _customersFlow.value.firstOrNull { it.id == customerId }
                        val newBalance = authBalance
                            ?: existingCust?.balanceExact?.subtract(officialAmount)?.setScale(currencyMinorUnits, RoundingMode.HALF_UP)
                            ?: existingCust?.balanceExact
                        if (newBalance != null) {
                            _customersFlow.update { list ->
                                list.map { c ->
                                    if (c.id == customerId) c.copy(balanceExact = newBalance) else c
                                }
                            }
                        }
                        newBalance
                    }
                } else {
                    // Older payment cannot clear newer state! Leave existing state in _customersFlow
                    _customersFlow.value.firstOrNull { it.id == customerId }?.balanceExact
                }
            }

            val tx = CustomerTransaction(
                id = officialId,
                customerId = customerId,
                date = postedAt,
                typeAr = "دفعة نقدية مقبوضة",
                typeEn = "Cash Collection",
                debitExact = BigDecimal.ZERO,
                creditExact = officialAmount,
                runningBalanceExact = authoritativeBalanceExact,
                note = note
            )
            _customerTransactionsFlow.update { listOf(tx) + it }
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

            kotlinx.coroutines.currentCoroutineContext().ensureActive()
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

            val updated = existing.copy(
                nameAr = serverName,
                nameEn = serverName,
                phone = authoritativePhone,
                addressAr = authoritativeAddressAr,
                addressEn = authoritativeAddressAr,
                creditLimitExact = authoritativeCreditLimit
            )
            _customersFlow.update { list -> list.map { if (it.id == id) updated else it } }
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
        val revisionBefore = getCustomerRevision(customerId)
        val inFlightBefore = customerInFlightPayments[customerId]?.get() ?: 0
        try {
            val res = apiClient.getCustomer(companyId, ulid)
            if (res.isFailure) {
                return@withContext Result.failure(res.exceptionOrNull() ?: RuntimeException("Failed to get customer detail"))
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
            val existing = _customersFlow.value.firstOrNull { it.id == customerId }
            val creditLimit = if (obj.properties.containsKey("credit_limit")) {
                obj.properties["credit_limit"].toNullableBigDecimal()
            } else if (obj.properties.containsKey("credit_limit_exact")) {
                obj.properties["credit_limit_exact"].toNullableBigDecimal()
            } else if (obj.properties.containsKey("credit_limit_ils")) {
                obj.properties["credit_limit_ils"].toNullableBigDecimal()
            } else {
                existing?.creditLimitExact
            }
            val serverBalance = if (obj.properties.containsKey("balance")) {
                obj.properties["balance"].toNullableBigDecimal()
            } else null

            val balance = synchronized(getCustomerLock(customerId)) {
                val currentRevision = getCustomerRevision(customerId)
                val inFlightNow = customerInFlightPayments[customerId]?.get() ?: 0
                val currentCust = _customersFlow.value.firstOrNull { it.id == customerId }
                val canPublishBalance = (currentRevision == revisionBefore && inFlightBefore == 0 && inFlightNow == 0)
                if (canPublishBalance) {
                    serverBalance ?: currentCust?.balanceExact
                } else {
                    currentCust?.balanceExact
                }
            }

            val detail = Customer(
                id = id,
                nameAr = nameAr,
                nameEn = nameEn,
                phone = phone,
                code = code,
                addressAr = address ?: existing?.addressAr,
                addressEn = address ?: existing?.addressEn,
                creditLimitExact = creditLimit,
                balanceExact = balance,
                isWalkIn = existing?.isWalkIn ?: false,
                isDetailLoaded = true
            )
            _customersFlow.update { list ->
                if (list.none { it.id == id }) list + detail
                else list.map { c ->
                    if (c.id == id) {
                        c.copy(
                            nameAr = nameAr,
                            nameEn = nameEn,
                            phone = phone ?: c.phone,
                            code = code ?: c.code,
                            addressAr = address ?: c.addressAr,
                            addressEn = address ?: c.addressEn,
                            creditLimitExact = creditLimit ?: c.creditLimitExact,
                            balanceExact = balance ?: c.balanceExact,
                            isDetailLoaded = true
                        )
                    } else c
                }
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
        try {
            val res = apiClient.getSupplier(companyId, ulid)
            if (res.isFailure) {
                return@withContext Result.failure(res.exceptionOrNull() ?: RuntimeException("Failed to get supplier detail"))
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
            val existing = _suppliersFlow.value.firstOrNull { it.id == supplierId }
            val balanceStr = obj.properties["balance_owed"].toNullableString()
                ?: obj.properties["owed"].toNullableString()
                ?: obj.properties["balance"].toNullableString()
            val balanceExact = balanceStr?.let { try { BigDecimal(it) } catch (_: Exception) { null } } ?: existing?.balanceOwedExact
            val advancesStr = obj.properties["advances"].toNullableString()
            val advancesExact = advancesStr?.let { try { BigDecimal(it) } catch (_: Exception) { null } } ?: existing?.advancesExact

            val detail = Supplier(
                id = id,
                code = code ?: existing?.code,
                nameAr = nameAr,
                nameEn = nameEn,
                companyAr = company ?: existing?.companyAr,
                companyEn = company ?: existing?.companyEn,
                phone = phone ?: existing?.phone,
                balanceOwedExact = balanceExact,
                advancesExact = advancesExact
            )
            _suppliersFlow.update { list ->
                if (list.none { it.id == id }) list + detail else list.map { if (it.id == id) detail else it }
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
        val client = merchantReadClient ?: return@withContext Result.failure(IllegalStateException("MerchantReadClient not configured"))
        val ulid = try {
            ServerUlid.parse(customerId)
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Invalid customerId ULID: '$customerId'", e))
        }
        try {
            val res = client.getCustomerStatement(companyId, ulid, fromDate, toDate, retainedGeneration = retainedGeneration)
            when (res) {
                is MerchantReadResult.Success -> {
                    val statement = res.value
                    val summary = statement.currencies[baseCurrency] ?: statement.currencies.values.firstOrNull()
                    if (summary != null) {
                        val mapped = summary.entries.map { entry ->
                            MerchantReadAdapter.toDomainCustomerTransaction(entry, customerId)
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
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
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

    override suspend fun recordSupplierPayment(
        supplierId: String,
        amountIls: Double,
        note: String,
        operator: UserSession,
        paymentId: String
    ): Result<SupplierPayment> = withContext(ioDispatcher) {
        requireActiveScope()
        if (!ServerUlid.isValid(supplierId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid supplier ULID: $supplierId"))
        }
        val amountExact = BigDecimal(amountIls.toString()).setScale(currencyMinorUnits, RoundingMode.HALF_UP)
        if (amountExact <= BigDecimal.ZERO) {
            return@withContext Result.failure(IllegalArgumentException("Payment amount must be strictly positive"))
        }

        val commandId = if (paymentId.isNotBlank()) paymentId else UUID.randomUUID().toString()
        val idempotencyKey = "suppay-$commandId"

        val payloadObj = StrictJsonObject(
            mapOf(
                "supplier_id" to StrictJsonString(supplierId),
                "amount" to StrictJsonString(amountExact.toPlainString()),
                "notes" to StrictJsonString(note)
            )
        )
        val payloadBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(payloadObj)

        val postCall: suspend (String) -> Result<StrictJsonObject> = { key ->
            apiClient.recordSupplierPayment(
                companyId = companyId,
                supplierId = ServerUlid.parse(supplierId),
                amount = amountExact,
                cashAccountId = approvedCashAccount.id,
                note = note,
                idempotencyKey = key
            )
        }

        val durableRes = executeDurableCommand(
            commandType = "supplier.payment",
            commandId = commandId,
            idempotencyKey = idempotencyKey,
            canonicalPayloadBytes = payloadBytes,
            isReceiptStatusChecked = true,
            postAction = postCall
        )

        if (durableRes.isFailure) {
            return@withContext Result.failure(durableRes.exceptionOrNull() ?: RuntimeException("Supplier payment failed"))
        }

        val snapshot = durableRes.getOrThrow()
        val officialId = snapshot.properties["id"].toNullableString()
            ?: snapshot.properties["voucher_id"].toNullableString()
            ?: return@withContext Result.failure(IllegalStateException("Server did not return payment ID"))
        val officialAmount = snapshot.properties["amount"].toNullableBigDecimal() ?: amountExact
        val postedAt = snapshot.properties["posted_at"].toNullableString() ?: WesternFormatters.currentDate()

        // Authoritative supplier detail from server (unknown balance remains null)
        val serverSupplierObj = apiClient.getSupplier(companyId, ServerUlid.parse(supplierId)).getOrNull()
        val authoritativeBalanceExact: BigDecimal? = serverSupplierObj?.properties?.get("balance").toNullableBigDecimal()
            ?: serverSupplierObj?.properties?.get("balance_owed").toNullableBigDecimal()
            ?: serverSupplierObj?.properties?.get("owed").toNullableBigDecimal()
            ?: snapshot.properties["balance"].toNullableBigDecimal()
        val authoritativeAdvancesExact: BigDecimal? = serverSupplierObj?.properties?.get("advances").toNullableBigDecimal()
            ?: snapshot.properties["advances"].toNullableBigDecimal()

        if (authoritativeBalanceExact != null || authoritativeAdvancesExact != null) {
            _suppliersFlow.update { list ->
                list.map { s ->
                    if (s.id == supplierId) {
                        s.copy(
                            balanceOwedExact = authoritativeBalanceExact,
                            advancesExact = authoritativeAdvancesExact
                        )
                    } else s
                }
            }
        } else {
            // Invalidate prepayment balance if refresh fails or balance is unknown
            _suppliersFlow.update { list ->
                list.map { s ->
                    if (s.id == supplierId) s.copy(balanceOwedExact = null, advancesExact = null) else s
                }
            }
        }

        val payment = SupplierPayment(
            id = officialId,
            supplierId = supplierId,
            date = postedAt,
            amountExact = officialAmount,
            note = note
        )
        _supplierPaymentsFlow.update { listOf(payment) + it }
        Result.success(payment)
    }

    private fun updateSupplierFromServer(supObj: StrictJsonObject) {
        val id = supObj.properties["id"].toNullableString() ?: return
        val balanceOwedExact = supObj.properties["balance"].toNullableBigDecimal()
            ?: supObj.properties["balance_owed"].toNullableBigDecimal()
            ?: supObj.properties["owed"].toNullableBigDecimal()
            ?: supObj.properties["balance_owed_ils"].toNullableBigDecimal()
        val advancesExact = supObj.properties["advances"].toNullableBigDecimal()
        _suppliersFlow.update { list ->
            list.map { s ->
                if (s.id == id) {
                    s.copy(
                        balanceOwedExact = balanceOwedExact ?: s.balanceOwedExact,
                        advancesExact = advancesExact
                    )
                } else s
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
        return commandStore.getDraftOperationId(scope.companyId.value, scope.actorId.value, scope.deviceId.value, formType)
    }

    override fun rotateDraftOperationId(formType: String): String {
        val scope = requireActiveScope()
        return commandStore.rotateDraftOperationId(scope.companyId.value, scope.actorId.value, scope.deviceId.value, formType)
    }

    override fun cancelDraftOperation(formType: String) {
        val scope = requireActiveScope()
        commandStore.cancelDraftOperation(scope.companyId.value, scope.actorId.value, scope.deviceId.value, formType)
    }

    override fun getActiveDraftPayload(formType: String): StrictJsonObject? {
        val scope = authScopeProvider?.invoke() ?: return null
        return commandStore.getActiveDraftPayload(scope.companyId.value, scope.actorId.value, scope.deviceId.value, formType)
    }

    override fun isDraftRecovering(formType: String): Boolean {
        val scope = authScopeProvider?.invoke() ?: return false
        val record = commandStore.getActiveDraftRecord(scope.companyId.value, scope.actorId.value, scope.deviceId.value, formType) ?: return false
        return record.status == CommandStatus.SUBMITTED || record.status == CommandStatus.UNKNOWN_ACK
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

    companion object {
        suspend fun fromReadReady(
            readReady: StaffAuthState.ReadReady,
            outboxRepository: OutboxRepository,
            transport: ReadClientTransport,
            baseOrigin: String,
            commandStore: MerchantCommandStore,
            authScopeProvider: (() -> CurrentAuthScope?)? = null,
            onClose: (() -> Unit)? = null,
            authoritativeMinorUnits: Int? = null,
            merchantReadClient: MerchantReadClient? = null
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

            val cmu = authoritativeMinorUnits ?: run {
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
                autoRefresh = false
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
            merchantReadClient: MerchantReadClient? = null
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
                permissions = setOf("pos.sale", "purchases.manage", "expenses.manage"),
                deviceId = deviceId,
                baseCurrency = "ILS",
                currencyMinorUnits = 2,
                defaultWarehouse = DefaultWarehouse(id = warehouseId, code = "WH-01", name = "Main Warehouse"),
                approvedCashAccount = ApprovedCashAccount(id = ServerUlid.parse("01JMB000000000000000000004"), code = "CASH-01", name = "Main Drawer", currency = "ILS"),
                walkInCustomerId = ServerUlid.parse("01JMB000000000000000000005"),
                bootstrapUnits = emptyList(),
                bootstrapTaxes = emptyList(),
                apiClient = apiClient,
                authScopeProvider = authScopeProvider ?: { CurrentAuthScope(companyId = companyId, actorId = actorId, deviceId = deviceId, token = "test-token", generation = 1L) },
                commandStore = commandStore,
                merchantReadClient = effectiveReadClient,
                autoRefresh = false
            )
        }
    }
}
