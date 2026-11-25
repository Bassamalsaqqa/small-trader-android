package com.example.data.mobile.offline

import android.content.Context
import com.example.data.ProductionTraderRepository
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.outbox.*
import com.example.data.mobile.readclient.*
import com.example.model.InvoiceRecord
import com.example.model.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Deliberately exposes no online repository, permission set, token, sync or auth state. */
internal class RestrictedOfflineWorkspace private constructor(
    private val repository: ProductionTraderRepository,
    private val validate: () -> Unit,
    val lease: com.example.data.mobile.contract.v2.OfflineSaleLease
) : AutoCloseable {
    val products = repository.productsFlow
    fun checkValid() = validate()
    suspend fun capture(invoice: InvoiceRecord): Result<InvoiceRecord> = repository.recordOfflineSale(invoice)
    suspend fun pending() = repository.offlineQueueSummary()
    override fun close() = repository.close()

    companion object {
        suspend fun open(context: Context, baseOrigin: String): RestrictedOfflineWorkspace? = withContext(Dispatchers.IO) {
            if (!com.example.BuildConfig.OFFLINE_OWNER_PILOT) return@withContext null
            val db = OutboxDatabase.buildDatabase(context)
            try {
                val leases = SqliteOfflineLeaseStore(context) { db.openHelper.writableDatabase }
                val bootstraps = RestrictedOfflineBootstrapStore(context)
                return@withContext compose(db, leases, bootstraps, baseOrigin, true,
                    OutboxPayloadCipher(), RoomMerchantCommandStore(db, com.example.data.mobile.session.AndroidKeyStoreCryptoEngine()))
            } catch (_: Exception) { db.close(); null }
        }
        internal suspend fun compose(db: OutboxDatabase, leases: OfflineLeaseStore,
            bootstraps: RestrictedOfflineBootstrapStore, baseOrigin: String, ownerPilotEnabled: Boolean,
            payloadCipher: OutboxPayloadCipher, commandStore: MerchantCommandStore): RestrictedOfflineWorkspace? = withContext(Dispatchers.IO) {
            if (!ownerPilotEnabled) { db.close(); return@withContext null }
            try {
                val bootstrap = bootstraps.read(leases) ?: run { db.close(); return@withContext null }
                val lease = bootstrap.lease
                val validate: () -> Unit = {
                    if (db.outboxDao().isScopeLocked(lease.companyId.value, lease.actorId.value, lease.deviceId.value))
                        throw SecurityException("Queue locked; live authorization recovery required")
                    if (bootstraps.read(leases) != bootstrap) throw SecurityException("Restricted offline grant unavailable")
                }
                validate()
                val outbox = OutboxRepositoryImpl(db, payloadCipher = payloadCipher, requireLeaseAuthorization = true, leaseAuthority = { suppliedLease, event ->
                    runCatching { validate(); suppliedLease == lease && leases.authorizesCapture(suppliedLease, event) }.getOrDefault(false)
                })
                val deniedTransport = object : ReadClientTransport {
                    override fun execute(request: TransportRequest): TransportResponse =
                        throw SecurityException("Restricted offline workspace has no network authority")
                }
                val taxes = lease.taxRateId?.let { id -> listOf(BootstrapTax(id, id.value, id.value,
                    requireNotNull(lease.taxRate), requireNotNull(lease.taxCalculation), false)) }.orEmpty()
                val repository = ProductionTraderRepository(companyId = lease.companyId, companyName = bootstrap.companyName,
                    actorId = lease.actorId, actorName = bootstrap.actorName, role = bootstrap.historicalRole,
                    deviceId = lease.deviceId, baseCurrency = lease.currency, currencyMinorUnits = lease.currencyMinorUnits,
                    defaultWarehouse = bootstrap.warehouse, approvedCashAccount = bootstrap.cash,
                    walkInCustomerId = lease.walkInCustomerId, bootstrapUnits = emptyList(), bootstrapTaxes = taxes,
                    apiClient = MobileMerchantApiClient(deniedTransport, baseOrigin) { throw SecurityException("No bearer authority in restricted offline mode") },
                    outboxRepository = outbox, commandStore = commandStore,
                    authScopeProvider = { null }, offlineLeaseStore = leases, offlineScopeValidator = validate,
                    autoRefresh = false, onClose = { db.close() })
                repository.isOfflineSalesCapabilityEnabled = true
                repository.isNetworkOffline = true
                RestrictedOfflineWorkspace(repository, validate, lease)
            } catch (_: Exception) { db.close(); null }
        }
    }
}
