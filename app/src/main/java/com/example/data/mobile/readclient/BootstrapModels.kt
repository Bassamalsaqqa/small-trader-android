package com.example.data.mobile.readclient

import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.NonnegativeDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.readclient.ValidationUtils.checkAllowedKeys
import com.example.data.mobile.readclient.ValidationUtils.optionalObject
import com.example.data.mobile.readclient.ValidationUtils.optionalString
import com.example.data.mobile.readclient.ValidationUtils.requireArray
import com.example.data.mobile.readclient.ValidationUtils.requireBoolean
import com.example.data.mobile.readclient.ValidationUtils.requireInt
import com.example.data.mobile.readclient.ValidationUtils.requireLong
import com.example.data.mobile.readclient.ValidationUtils.requireNonnegativeQuantity
import com.example.data.mobile.readclient.ValidationUtils.requireObject
import com.example.data.mobile.readclient.ValidationUtils.requireString
import com.example.data.mobile.readclient.ValidationUtils.requireTaxPercentage
import com.example.data.mobile.readclient.ValidationUtils.requireUlid
import com.example.data.mobile.readclient.ValidationUtils.requireUuid
import com.example.data.mobile.readclient.ValidationUtils.validateDateTime
import com.example.data.mobile.readclient.ValidationUtils.validateStringBounds
import java.util.Collections

/**
 * Staff information in bootstrap response.
 * Role is an informative presentation label only; never an authorization grant.
 * Defensive copy protects permissions from external modification.
 */
class BootstrapStaff(
    val id: ServerUlid,
    val name: String,
    val role: String,
    permissions: List<String>
) {
    val permissions: List<String> = Collections.unmodifiableList(permissions.toList())

    init {
        validateStringBounds(name, "staff.name", minLength = 1, maxLength = 255)
        validateStringBounds(role, "staff.role", minLength = 1, maxLength = 255)
    }

    fun copy(
        id: ServerUlid = this.id,
        name: String = this.name,
        role: String = this.role,
        permissions: List<String> = this.permissions
    ): BootstrapStaff = BootstrapStaff(id, name, role, permissions)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BootstrapStaff) return false
        return id == other.id &&
                name == other.name &&
                role == other.role &&
                permissions == other.permissions
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + permissions.hashCode()
        return result
    }

    override fun toString(): String =
        "BootstrapStaff(id=$id, name='[REDACTED]', role='$role', permissionsCount=${permissions.size})"

    companion object {
        private val ALLOWED_KEYS = setOf("id", "name", "role", "permissions")

        fun fromStrictJson(obj: StrictJsonObject): BootstrapStaff {
            checkAllowedKeys(obj, ALLOWED_KEYS, "BootstrapStaff")
            val permsArray = requireArray(obj, "permissions")
            val perms = (0 until permsArray.size).map { i ->
                (permsArray[i] as? com.example.data.mobile.contract.StrictJsonString)?.value
                    ?: throw CanonicalSerializationException("permissions[$i] must be a string")
            }
            return BootstrapStaff(
                id = requireUlid(obj, "id"),
                name = requireString(obj, "name", minLength = 1, maxLength = 255),
                role = requireString(obj, "role", minLength = 1, maxLength = 255),
                permissions = perms
            )
        }
    }
}

/**
 * Offline device lease. When offline_policy.enabled is false, this must be null.
 */
data class DeviceLease(
    val leaseId: ClientUuid,
    val deviceId: ClientUuid,
    val actorId: ServerUlid,
    val expiresAt: String
) {
    init {
        validateDateTime(expiresAt, "expires_at")
    }

    companion object {
        private val ALLOWED_KEYS = setOf("lease_id", "device_id", "actor_id", "expires_at")

        fun fromStrictJson(obj: StrictJsonObject): DeviceLease {
            checkAllowedKeys(obj, ALLOWED_KEYS, "DeviceLease")
            return DeviceLease(
                leaseId = requireUuid(obj, "lease_id"),
                deviceId = requireUuid(obj, "device_id"),
                actorId = requireUlid(obj, "actor_id"),
                expiresAt = requireString(obj, "expires_at", minLength = 20, maxLength = 35)
            )
        }
    }
}

data class DefaultWarehouse(
    val id: ServerUlid,
    val code: String,
    val name: String
) {
    init {
        validateStringBounds(code, "default_warehouse.code", minLength = 1, maxLength = 64)
        validateStringBounds(name, "default_warehouse.name", minLength = 1, maxLength = 255)
    }

    companion object {
        private val ALLOWED_KEYS = setOf("id", "code", "name")

        fun fromStrictJson(obj: StrictJsonObject): DefaultWarehouse {
            checkAllowedKeys(obj, ALLOWED_KEYS, "DefaultWarehouse")
            return DefaultWarehouse(
                id = requireUlid(obj, "id"),
                code = requireString(obj, "code", minLength = 1, maxLength = 64),
                name = requireString(obj, "name", minLength = 1, maxLength = 255)
            )
        }
    }
}

data class ApprovedCashAccount(
    val id: ServerUlid,
    val code: String,
    val name: String,
    val currency: String
) {
    init {
        validateStringBounds(code, "approved_cash_account.code", minLength = 1, maxLength = 32)
        validateStringBounds(name, "approved_cash_account.name", minLength = 1, maxLength = 255)
        require(ContractConstants.CURRENCY_CODE_REGEX.matches(currency)) {
            "approved_cash_account.currency must be 3 uppercase letters, got '$currency'"
        }
    }

    companion object {
        private val ALLOWED_KEYS = setOf("id", "code", "name", "currency")

        fun fromStrictJson(obj: StrictJsonObject): ApprovedCashAccount {
            checkAllowedKeys(obj, ALLOWED_KEYS, "ApprovedCashAccount")
            return ApprovedCashAccount(
                id = requireUlid(obj, "id"),
                code = requireString(obj, "code", minLength = 1, maxLength = 32),
                name = requireString(obj, "name", minLength = 1, maxLength = 255),
                currency = requireString(obj, "currency", minLength = 3, maxLength = 3)
            )
        }
    }
}

data class BootstrapUnit(
    val id: ServerUlid,
    val code: String,
    val name: String,
    val symbol: String?,
    val allowsFraction: Boolean,
    val decimalPlaces: Int
) {
    init {
        validateStringBounds(code, "unit.code", minLength = 1, maxLength = 32)
        validateStringBounds(name, "unit.name", minLength = 1, maxLength = 128)
        if (symbol != null) {
            validateStringBounds(symbol, "unit.symbol", minLength = 1, maxLength = 32)
        }
        require(decimalPlaces in 0..6) {
            "unit.decimal_places must be between 0 and 6, got $decimalPlaces"
        }
    }

    companion object {
        private val ALLOWED_KEYS = setOf("id", "code", "name", "symbol", "allows_fraction", "decimal_places")

        fun fromStrictJson(obj: StrictJsonObject): BootstrapUnit {
            checkAllowedKeys(obj, ALLOWED_KEYS, "BootstrapUnit")
            return BootstrapUnit(
                id = requireUlid(obj, "id"),
                code = requireString(obj, "code", minLength = 1, maxLength = 32),
                name = requireString(obj, "name", minLength = 1, maxLength = 128),
                symbol = optionalString(obj, "symbol", minLength = 1, maxLength = 32),
                allowsFraction = requireBoolean(obj, "allows_fraction"),
                decimalPlaces = requireInt(obj, "decimal_places", min = 0, max = 6)
            )
        }
    }
}

data class BootstrapTax(
    val id: ServerUlid,
    val code: String,
    val name: String,
    val rate: TaxPercentage,
    val calculation: String,
    val isDefault: Boolean
) {
    init {
        validateStringBounds(code, "tax.code", minLength = 1, maxLength = 32)
        validateStringBounds(name, "tax.name", minLength = 1, maxLength = 255)
        require(calculation == "exclusive" || calculation == "inclusive") {
            "tax.calculation must be 'exclusive' or 'inclusive', got '$calculation'"
        }
    }

    companion object {
        private val ALLOWED_KEYS = setOf("id", "code", "name", "rate", "calculation", "is_default")

        fun fromStrictJson(obj: StrictJsonObject): BootstrapTax {
            checkAllowedKeys(obj, ALLOWED_KEYS, "BootstrapTax")
            return BootstrapTax(
                id = requireUlid(obj, "id"),
                code = requireString(obj, "code", minLength = 1, maxLength = 32),
                name = requireString(obj, "name", minLength = 1, maxLength = 255),
                rate = requireTaxPercentage(obj, "rate"),
                calculation = requireString(obj, "calculation", minLength = 9, maxLength = 9),
                isDefault = requireBoolean(obj, "is_default")
            )
        }
    }
}

sealed interface OfflinePolicy {
    val enabled: Boolean
    val availability: String
}

data class OfflinePolicyDisabled(
    override val enabled: Boolean = false,
    override val availability: String = "unavailable"
) : OfflinePolicy {
    init {
        require(!enabled) { "OfflinePolicyDisabled must have enabled=false" }
        require(availability == "unavailable") { "OfflinePolicyDisabled must have availability='unavailable'" }
    }

    companion object {
        private val ALLOWED_KEYS = setOf("enabled", "availability")

        fun fromStrictJson(obj: StrictJsonObject): OfflinePolicyDisabled {
            checkAllowedKeys(obj, ALLOWED_KEYS, "OfflinePolicyDisabled")
            val enabled = requireBoolean(obj, "enabled")
            val availability = requireString(obj, "availability", minLength = 11, maxLength = 11)
            return OfflinePolicyDisabled(enabled = enabled, availability = availability)
        }
    }
}

data class OfflinePolicyEnabled(
    override val enabled: Boolean = true,
    val cashOnly: Boolean,
    val maxOfflineHours: Long,
    val maxOfflineSalesCount: Long,
    val maxOfflineTotalAmount: NonnegativeDecimalQuantity,
    override val availability: String = "available"
) : OfflinePolicy {
    init {
        require(enabled) { "OfflinePolicyEnabled must have enabled=true" }
        require(availability == "available") { "OfflinePolicyEnabled must have availability='available'" }
        require(maxOfflineHours >= 0) { "maxOfflineHours must be >= 0" }
        require(maxOfflineSalesCount >= 0) { "maxOfflineSalesCount must be >= 0" }
    }

    companion object {
        private val ALLOWED_KEYS = setOf(
            "enabled", "cash_only", "max_offline_hours",
            "max_offline_sales_count", "max_offline_total_amount", "availability"
        )

        fun fromStrictJson(obj: StrictJsonObject): OfflinePolicyEnabled {
            checkAllowedKeys(obj, ALLOWED_KEYS, "OfflinePolicyEnabled")
            return OfflinePolicyEnabled(
                enabled = requireBoolean(obj, "enabled"),
                cashOnly = requireBoolean(obj, "cash_only"),
                maxOfflineHours = requireLong(obj, "max_offline_hours", min = 0),
                maxOfflineSalesCount = requireLong(obj, "max_offline_sales_count", min = 0),
                maxOfflineTotalAmount = requireNonnegativeQuantity(obj, "max_offline_total_amount"),
                availability = requireString(obj, "availability", minLength = 9, maxLength = 9)
            )
        }
    }
}

/**
 * Authoritative response payload for GET /api/mobile/v1/companies/{companyId}/bootstrap.
 * Defensive copies protect units and taxes from external modification.
 */
class BootstrapResponse(
    val companyId: ServerUlid,
    val companyName: String,
    val baseCurrency: String,
    val walkInCustomerId: ServerUlid,
    val staff: BootstrapStaff,
    val deviceLease: DeviceLease?,
    val defaultWarehouse: DefaultWarehouse,
    val approvedCashAccount: ApprovedCashAccount,
    units: List<BootstrapUnit>,
    taxes: List<BootstrapTax>,
    val offlinePolicy: OfflinePolicy,
    val catalogSnapshotVersion: String
) {
    val units: List<BootstrapUnit> = Collections.unmodifiableList(units.toList())
    val taxes: List<BootstrapTax> = Collections.unmodifiableList(taxes.toList())

    init {
        validateStringBounds(companyName, "company_name", minLength = 1, maxLength = 255)
        require(ContractConstants.CURRENCY_CODE_REGEX.matches(baseCurrency)) {
            "base_currency must be 3 uppercase letters, got '$baseCurrency'"
        }
        validateStringBounds(catalogSnapshotVersion, "catalog_snapshot_version", minLength = 1, maxLength = 64)

        // Schema invariant: when offline_policy.enabled is false, device_lease MUST be null
        if (!offlinePolicy.enabled) {
            require(deviceLease == null) {
                "device_lease must be null when offline policy is disabled (offline unavailable)"
            }
        }
    }

    fun copy(
        companyId: ServerUlid = this.companyId,
        companyName: String = this.companyName,
        baseCurrency: String = this.baseCurrency,
        walkInCustomerId: ServerUlid = this.walkInCustomerId,
        staff: BootstrapStaff = this.staff,
        deviceLease: DeviceLease? = this.deviceLease,
        defaultWarehouse: DefaultWarehouse = this.defaultWarehouse,
        approvedCashAccount: ApprovedCashAccount = this.approvedCashAccount,
        units: List<BootstrapUnit> = this.units,
        taxes: List<BootstrapTax> = this.taxes,
        offlinePolicy: OfflinePolicy = this.offlinePolicy,
        catalogSnapshotVersion: String = this.catalogSnapshotVersion
    ): BootstrapResponse = BootstrapResponse(
        companyId, companyName, baseCurrency, walkInCustomerId, staff,
        deviceLease, defaultWarehouse, approvedCashAccount, units, taxes,
        offlinePolicy, catalogSnapshotVersion
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BootstrapResponse) return false
        return companyId == other.companyId &&
                companyName == other.companyName &&
                baseCurrency == other.baseCurrency &&
                walkInCustomerId == other.walkInCustomerId &&
                staff == other.staff &&
                deviceLease == other.deviceLease &&
                defaultWarehouse == other.defaultWarehouse &&
                approvedCashAccount == other.approvedCashAccount &&
                units == other.units &&
                taxes == other.taxes &&
                offlinePolicy == other.offlinePolicy &&
                catalogSnapshotVersion == other.catalogSnapshotVersion
    }

    override fun hashCode(): Int {
        var result = companyId.hashCode()
        result = 31 * result + companyName.hashCode()
        result = 31 * result + baseCurrency.hashCode()
        result = 31 * result + walkInCustomerId.hashCode()
        result = 31 * result + staff.hashCode()
        result = 31 * result + (deviceLease?.hashCode() ?: 0)
        result = 31 * result + defaultWarehouse.hashCode()
        result = 31 * result + approvedCashAccount.hashCode()
        result = 31 * result + units.hashCode()
        result = 31 * result + taxes.hashCode()
        result = 31 * result + offlinePolicy.hashCode()
        result = 31 * result + catalogSnapshotVersion.hashCode()
        return result
    }

    override fun toString(): String =
        "BootstrapResponse(companyId=$companyId, companyName='$companyName', baseCurrency='$baseCurrency', " +
                "walkInCustomerId=$walkInCustomerId, staff=$staff, deviceLease=$deviceLease, " +
                "defaultWarehouse=$defaultWarehouse, approvedCashAccount=$approvedCashAccount, " +
                "unitsCount=${units.size}, taxesCount=${taxes.size}, offlinePolicy=$offlinePolicy, " +
                "catalogSnapshotVersion='$catalogSnapshotVersion')"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "company_id", "company_name", "base_currency", "walk_in_customer_id",
            "staff", "device_lease", "default_warehouse", "approved_cash_account",
            "units", "taxes", "offline_policy", "catalog_snapshot_version"
        )

        fun fromStrictJson(obj: StrictJsonObject): BootstrapResponse {
            checkAllowedKeys(obj, ALLOWED_KEYS, "BootstrapResponse")

            val offlinePolicyObj = requireObject(obj, "offline_policy")
            val offlineEnabled = requireBoolean(offlinePolicyObj, "enabled")
            val offlinePolicy: OfflinePolicy = if (offlineEnabled) {
                OfflinePolicyEnabled.fromStrictJson(offlinePolicyObj)
            } else {
                OfflinePolicyDisabled.fromStrictJson(offlinePolicyObj)
            }

            if (!obj.properties.containsKey("device_lease")) {
                throw CanonicalSerializationException("Missing required property 'device_lease'")
            }
            val leaseElement = obj.properties["device_lease"]!!
            val deviceLease = when (leaseElement) {
                is com.example.data.mobile.contract.StrictJsonNull -> null
                is StrictJsonObject -> DeviceLease.fromStrictJson(leaseElement)
                else -> throw CanonicalSerializationException("Property 'device_lease' must be an object or null, got ${leaseElement::class.simpleName}")
            }

            if (!offlinePolicy.enabled && deviceLease != null) {
                throw CanonicalSerializationException("device_lease must be null when offline policy is disabled")
            }

            val unitsArray = requireArray(obj, "units")
            val units = (0 until unitsArray.size).map { i ->
                val uObj = unitsArray[i] as? StrictJsonObject
                    ?: throw CanonicalSerializationException("units[$i] must be a JSON object")
                BootstrapUnit.fromStrictJson(uObj)
            }

            val taxesArray = requireArray(obj, "taxes")
            val taxes = (0 until taxesArray.size).map { i ->
                val tObj = taxesArray[i] as? StrictJsonObject
                    ?: throw CanonicalSerializationException("taxes[$i] must be a JSON object")
                BootstrapTax.fromStrictJson(tObj)
            }

            return BootstrapResponse(
                companyId = requireUlid(obj, "company_id"),
                companyName = requireString(obj, "company_name", minLength = 1, maxLength = 255),
                baseCurrency = requireString(obj, "base_currency", minLength = 3, maxLength = 3),
                walkInCustomerId = requireUlid(obj, "walk_in_customer_id"),
                staff = BootstrapStaff.fromStrictJson(requireObject(obj, "staff")),
                deviceLease = deviceLease,
                defaultWarehouse = DefaultWarehouse.fromStrictJson(requireObject(obj, "default_warehouse")),
                approvedCashAccount = ApprovedCashAccount.fromStrictJson(requireObject(obj, "approved_cash_account")),
                units = units,
                taxes = taxes,
                offlinePolicy = offlinePolicy,
                catalogSnapshotVersion = requireString(obj, "catalog_snapshot_version", minLength = 1, maxLength = 64)
            )
        }
    }
}
