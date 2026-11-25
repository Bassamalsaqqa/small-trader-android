package com.example.data.mobile.offline

import com.example.data.mobile.contract.SalesLineCalculator
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.v2.OfflineSaleEventV2
import com.example.data.mobile.contract.v2.OfflineSaleLease
import com.example.data.mobile.contract.v2.PaymentDecimalConsistency
import com.example.data.mobile.readclient.ProductCatalogResponse

/** Validate the event itself at the production storage boundary, not only its lease key. */
internal fun OfflineLeaseStore.authorizesCapture(lease: OfflineSaleLease, event: OfflineSaleEventV2): Boolean = try {
    require(getLease(lease.companyId, lease.actorId, lease.deviceId) == lease)
    require(isCatalogComplete(lease.companyId, lease.actorId, lease.deviceId))
    require(getVerifiedCatalogSnapshotVersion(lease.companyId, lease.actorId, lease.deviceId) == lease.catalogSnapshotVersion)
    require(event.companyId == lease.companyId && event.actorId == lease.actorId && event.deviceId == lease.deviceId && event.deviceLeaseId == lease.leaseId)
    require(event.currency == lease.currency && event.catalogSnapshotVersion == lease.catalogSnapshotVersion)
    require(event.warehouseId == lease.warehouseId && event.cashAccountId == lease.cashAccountId && event.payment.cashAccountId == lease.cashAccountId)
    require(event.customerId == lease.walkInCustomerId)
    require(lease.isTimeValid(requireNotNull(OfflineSaleLease.parseRfc3339Millis(event.capturedAt))))
    require(PaymentDecimalConsistency.fitsCashEntry(event.payment.amountTendered.value, lease.currencyMinorUnits))
    val products = getCatalogPages(lease.companyId, lease.actorId, lease.deviceId).flatMap { bytes ->
        val page = ProductCatalogResponse.fromStrictJson(StrictJsonParser.parseObject(String(bytes, Charsets.UTF_8)))
        require(page.companyId == lease.companyId && page.catalogSnapshotVersion == lease.catalogSnapshotVersion)
        page.products
    }.associateBy { it.id }
    require(event.lines.isNotEmpty())
    event.lines.forEach { line ->
        val product = requireNotNull(products[line.productId])
        require(product.defaultUnit.productUnitId == line.productUnitId)
        require(product.defaultUnit.conversionToBase.value.compareTo(line.conversionToBase.value) == 0)
        require(line.taxRateId == lease.taxRateId)
        if (lease.taxRateId == null) require(line.taxRate.value.signum() == 0 && !line.taxInclusive)
        else require(line.taxRate.value.compareTo(requireNotNull(lease.taxRate).value) == 0 &&
            line.taxInclusive == (lease.taxCalculation == "inclusive"))
        val calculated = SalesLineCalculator.calculate(line.quantity, line.unitPrice, line.taxRate, line.taxInclusive, lease.currencyMinorUnits)
        require(calculated.lineTotal.value.compareTo(line.lineTotal.value) == 0 && calculated.taxAmount.value.compareTo(line.taxAmount.value) == 0)
    }
    true
} catch (_: Exception) { false }
