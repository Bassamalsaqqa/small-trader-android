package com.example.data.mobile.outbox

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.DecimalQuantity
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.OfflineSaleEvent
import com.example.data.mobile.contract.OfflineSaleIntakeRequest
import com.example.data.mobile.contract.OfflineSaleLine
import com.example.data.mobile.contract.OfflineSalePayment
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.TaxPercentage
import java.io.File

object OutboxTestFixtures {

    val SAMPLE_INVOICE_ULID = ServerUlid.parse("01JCV8X9ME7890123456789ABC")
    val SAMPLE_PAYMENT_ULID = ServerUlid.parse("01JCV8X9MFGHJKMNPQRSTVWXYZ")
    const val SAMPLE_INVOICE_NUMBER = "INV-2026-00042"
    const val SAMPLE_PAYMENT_RECEIPT_NUMBER = "RCT-2026-00042"

    fun locateFixturesDir(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        return File(root, "docs/integration/mobile-v1/fixtures")
    }

    fun loadGoldenIntakeRequest(): OfflineSaleIntakeRequest {
        val goldenFile = File(locateFixturesDir(), "golden-offline-sale-intake.json")
        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val reqJson = rootJson.require("request").asObject()
        return OfflineSaleIntakeRequest.fromStrictJson(reqJson)
    }

    fun createSampleEvent(
        clientEventId: ClientUuid = ClientUuid.random(),
        companyId: ServerUlid = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012345"),
        actorId: ServerUlid = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012346"),
        deviceId: ClientUuid = ClientUuid.parse("3fa85f64-5717-4562-b3fc-2c963f66afa6"),
        deviceLeaseId: ClientUuid = ClientUuid.parse("7ba85f64-5717-4562-b3fc-2c963f66afa7"),
        capturedAt: String = "2026-11-10T12:00:00Z",
        customerId: ServerUlid = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012347"),
        currency: String = "SAR",
        warehouseId: ServerUlid = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012348"),
        cashAccountId: ServerUlid = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012349"),
        catalogSnapshotVersion: String = "v1.0.0",
        localReceiptNumber: String = "REC-001",
        quantity: String = "1.000000",
        unitPrice: String = "100.000000",
        taxRate: String = "15.000000",
        taxAmount: String = "15.000000",
        lineTotal: String = "115.000000"
    ): OfflineSaleEvent {
        val packagingUlid = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012350")
        val line = OfflineSaleLine(
            productId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012351"),
            productUnitId = packagingUlid,
            unitId = packagingUlid,
            quantity = DecimalQuantity.fromPositiveString(quantity),
            unitPrice = MoneyAmount.fromNonnegativeString(unitPrice),
            taxRate = TaxPercentage.fromString(taxRate),
            taxAmount = MoneyAmount.fromNonnegativeString(taxAmount),
            lineTotal = MoneyAmount.fromPositiveString(lineTotal)
        )
        val payment = OfflineSalePayment(
            mode = "full",
            paymentMethod = "cash",
            cashAccountId = cashAccountId,
            amountTendered = MoneyAmount.fromNonnegativeString(lineTotal),
            amountSettled = MoneyAmount.fromPositiveString(lineTotal),
            changeReturned = MoneyAmount.fromNonnegativeString("0.000000")
        )
        return OfflineSaleEvent(
            clientEventId = clientEventId,
            eventVersion = 1,
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
            lines = listOf(line),
            payment = payment
        )
    }

    fun loadGoldenIntakeRequestV2(): com.example.data.mobile.contract.v2.OfflineSaleIntakeRequestV2 {
        val goldenFile = File(locateFixturesDir(), "golden-offline-sale-intake-v2.json")
        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val reqJson = rootJson.require("request").asObject()
        return com.example.data.mobile.contract.v2.OfflineSaleIntakeRequestV2.fromStrictJson(reqJson)
    }

    fun createSampleEventV2(
        clientEventId: ClientUuid = ClientUuid.random(),
        companyId: ServerUlid = ServerUlid.parse("01J9Z5K3MN0000000000000001"),
        actorId: ServerUlid = ServerUlid.parse("01J9Z5K3MN0000000000000002"),
        deviceId: ClientUuid = ClientUuid.parse("a0000000-0000-0000-0000-000000000001"),
        deviceLeaseId: ClientUuid = ClientUuid.parse("b0000000-0000-0000-0000-000000000001"),
        capturedAt: String = "2026-11-11T14:30:00Z",
        customerId: ServerUlid = ServerUlid.parse("01J9Z5K3MN0000000000000003"),
        currency: String = "ILS",
        warehouseId: ServerUlid = ServerUlid.parse("01J9Z5K3MN0000000000000004"),
        cashAccountId: ServerUlid = ServerUlid.parse("01J9Z5K3MN0000000000000005"),
        catalogSnapshotVersion: String = "v2026.11.11.01",
        localReceiptNumber: String = "REC-20261111-0001",
        quantity: String = "2.000000",
        unitPrice: String = "25.000000",
        taxRateId: ServerUlid? = ServerUlid.parse("01J9Z5K3MN0000000000000030"),
        taxRate: String = "16.000000",
        taxInclusive: Boolean = true,
        taxAmount: String = "6.900000",
        lineTotal: String = "50.000000",
        amountTendered: String = "50.000000",
        amountSettled: String = "50.000000",
        changeReturned: String = "0.000000"
    ): com.example.data.mobile.contract.v2.OfflineSaleEventV2 {
        val packagingUlid = ServerUlid.parse("01J9Z5K3MN0000000000000020")
        val line = com.example.data.mobile.contract.v2.OfflineSaleLineV2(
            productId = ServerUlid.parse("01J9Z5K3MN0000000000000010"),
            productUnitId = packagingUlid,
            unitId = packagingUlid,
            conversionToBase = com.example.data.mobile.contract.PositiveDecimalQuantity.fromPositiveString("1.000000"),
            quantity = com.example.data.mobile.contract.PositiveDecimalQuantity.fromPositiveString(quantity),
            unitPrice = MoneyAmount.fromNonnegativeString(unitPrice),
            taxRateId = taxRateId,
            taxRate = TaxPercentage.fromString(taxRate),
            taxInclusive = taxInclusive,
            taxAmount = MoneyAmount.fromNonnegativeString(taxAmount),
            lineTotal = MoneyAmount.fromPositiveString(lineTotal)
        )
        val payment = com.example.data.mobile.contract.v2.OfflineSalePaymentV2(
            mode = "full",
            paymentMethod = "cash",
            cashAccountId = cashAccountId,
            amountTendered = MoneyAmount.fromNonnegativeString(amountTendered),
            amountSettled = MoneyAmount.fromPositiveString(amountSettled),
            changeReturned = MoneyAmount.fromNonnegativeString(changeReturned)
        )
        return com.example.data.mobile.contract.v2.OfflineSaleEventV2(
            clientEventId = clientEventId,
            eventVersion = 2,
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
            lines = listOf(line),
            payment = payment
        )
    }

    fun createSampleIntakeRequestV2(
        event: com.example.data.mobile.contract.v2.OfflineSaleEventV2 = createSampleEventV2()
    ): com.example.data.mobile.contract.v2.OfflineSaleIntakeRequestV2 {
        return com.example.data.mobile.contract.v2.OfflineSaleIntakeRequestV2(
            clientEventId = event.clientEventId,
            deviceId = event.deviceId,
            capturedAt = event.capturedAt,
            payloadDigest = event.computePayloadDigest(),
            event = event
        )
    }
}
