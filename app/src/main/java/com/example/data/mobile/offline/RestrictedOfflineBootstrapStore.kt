package com.example.data.mobile.offline

import android.content.Context
import android.util.AtomicFile
import com.example.data.ProductionTraderRepository
import com.example.data.mobile.contract.*
import com.example.data.mobile.contract.v2.OfflineSaleLease
import com.example.data.mobile.readclient.ApprovedCashAccount
import com.example.data.mobile.readclient.DefaultWarehouse
import com.example.data.mobile.session.*
import com.example.model.UserRole
import java.io.File
import java.security.MessageDigest

/** Historical display/bootstrap subset. This grants neither a bearer nor online permissions. */
internal data class RestrictedOfflineBootstrap(
    val lease: OfflineSaleLease, val companyName: String, val actorName: String,
    val historicalRole: UserRole, val warehouse: DefaultWarehouse, val cash: ApprovedCashAccount
)

internal class RestrictedOfflineBootstrapStore internal constructor(
    private val file: AtomicFile,
    private val crypto: SessionCryptoEngine,
    private val sessions: SecureStaffSessionStore
) {
    constructor(context: Context) : this(AtomicFile(File(context.noBackupFilesDir, "restricted_offline_bootstrap.bin")),
        AndroidKeyStoreCryptoEngine("small_trader_restricted_bootstrap_v1"), SecureStaffSessionStore.create(context))

    private fun aad() = "restricted-offline-bootstrap:1:${sessions.installationId.value}".toByteArray(Charsets.UTF_8)
    private fun digest(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun invalidate() = synchronized(lock) { file.delete(); check(!file.baseFile.exists()) { "Cannot invalidate offline bootstrap" } }

    fun save(repository: ProductionTraderRepository, lease: OfflineSaleLease, leases: OfflineLeaseStore) = synchronized(lock) {
        repository.verifyOfflineBootstrapSource(lease)
        val now = requireNotNull(leases.trustedTime(lease))
        require(leases.isCatalogComplete(lease.companyId, lease.actorId, lease.deviceId))
        val session = (sessions.readSession(SessionReadScope(lease.deviceId, lease.actorId, lease.companyId), now) as? SessionReadResult.Success)?.session
            ?: throw SecurityException("Previously authenticated session unavailable")
        val warehouse = repository.defaultWarehouse
        val cash = repository.approvedCashAccount
        val bytes = CanonicalJsonSerializer.serialize(mapOf("company" to lease.companyId.value,
            "actor" to lease.actorId.value, "device" to lease.deviceId.value, "lease" to lease.leaseId.value,
            "session_digest" to digest(session.token), "company_name" to repository.companyName,
            "actor_name" to repository.actorName, "historical_role" to repository.role.name,
            "warehouse" to mapOf("id" to warehouse.id.value, "code" to warehouse.code, "name" to warehouse.name),
            "cash" to mapOf("id" to cash.id.value, "code" to cash.code, "name" to cash.name, "currency" to cash.currency)
        )).toByteArray(Charsets.UTF_8)
        require(bytes.size <= ContractConstants.MAX_PAYLOAD_BYTES)
        crypto.ensureKeyUsable(false)
        val (iv, encrypted) = crypto.encrypt(bytes, aad())
        require(iv.size == 12)
        val output = file.startWrite()
        try { output.write(iv + encrypted); repository.verifyOfflineBootstrapSource(lease); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    fun read(leases: OfflineLeaseStore): RestrictedOfflineBootstrap? = synchronized(lock) {
        try {
            val bytes = file.openRead().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    require(output.size() + read <= ContractConstants.MAX_PAYLOAD_BYTES + 28)
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            require(bytes.size in 28..(ContractConstants.MAX_PAYLOAD_BYTES + 28))
            crypto.ensureKeyUsable(true)
            val obj = StrictJsonParser.parseObject(String(crypto.decrypt(bytes.copyOfRange(0, 12), bytes.copyOfRange(12, bytes.size), aad()), Charsets.UTF_8))
            val company = ServerUlid.parse(obj.require("company").asString())
            val actor = ServerUlid.parse(obj.require("actor").asString())
            val device = ClientUuid.parse(obj.require("device").asString())
            require(device == sessions.installationId)
            val lease = requireNotNull(leases.getLease(company, actor, device))
            require(lease.leaseId.value == obj.require("lease").asString())
            val now = requireNotNull(leases.trustedTime(lease))
            val session = (sessions.readSession(SessionReadScope(device, actor, company), now) as? SessionReadResult.Success)?.session
                ?: throw SecurityException("Local session unavailable")
            require(digest(session.token) == obj.require("session_digest").asString())
            require(leases.isCatalogComplete(company, actor, device))
            val warehouse = DefaultWarehouse.fromStrictJson(obj.require("warehouse").asObject())
            val cash = ApprovedCashAccount.fromStrictJson(obj.require("cash").asObject())
            require(warehouse.id == lease.warehouseId && cash.id == lease.cashAccountId && cash.currency == lease.currency)
            RestrictedOfflineBootstrap(lease, obj.require("company_name").asString(), obj.require("actor_name").asString(),
                UserRole.valueOf(obj.require("historical_role").asString()), warehouse, cash)
        } catch (_: Exception) { null }
    }

    companion object { private val lock = Any() }
}
