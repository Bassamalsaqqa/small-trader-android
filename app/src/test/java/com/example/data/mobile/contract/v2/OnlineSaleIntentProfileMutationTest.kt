package com.example.data.mobile.contract.v2

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.TaxPercentage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Mutation tests conforming to Python validate-mobile-contract.py Check 26a-k:
 * Asserts that any alteration to actor, company, device, event, tax identity, tax inclusive,
 * conversion to base, decimal lexemes, line order, payment, or currency minor units is caught
 * by canonical digest verification.
 */
class OnlineSaleIntentProfileMutationTest {

    private lateinit var goldenProfile: OnlineSaleIntentProfile
    private lateinit var goldenDigest: PayloadDigest

    @Before
    fun setUp() {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        val goldenFile = File(root, "docs/integration/mobile-v1/fixtures/golden-online-sale-intent-profile.json")

        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val contextJson = rootJson.require("context").asObject()
        val requestJson = rootJson.require("request").asObject()

        goldenDigest = PayloadDigest.parse(rootJson.require("payload_digest").asString())
        val context = OnlineSaleContext.fromStrictJson(contextJson)
        goldenProfile = OnlineSaleIntentProfile.fromContextAndRequest(context, requestJson)
    }

    @Test
    fun testCheck26aActorTamperingDetected() {
        val tampered = goldenProfile.copy(
            actorId = ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67899")
        )
        assertNotEquals(goldenDigest, tampered.computePayloadDigest())
        assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.verifyOnlineSaleIntentProfile(tampered, goldenDigest)
        }
    }

    @Test
    fun testCheck26bCompanyTamperingDetected() {
        val tampered = goldenProfile.copy(
            companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
        )
        assertNotEquals(goldenDigest, tampered.computePayloadDigest())
        assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.verifyOnlineSaleIntentProfile(tampered, goldenDigest)
        }
    }

    @Test
    fun testCheck26cDeviceTamperingDetected() {
        val tampered = goldenProfile.copy(
            deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e11")
        )
        assertNotEquals(goldenDigest, tampered.computePayloadDigest())
        assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.verifyOnlineSaleIntentProfile(tampered, goldenDigest)
        }
    }

    @Test
    fun testCheck26dEventTamperingDetected() {
        val tampered = goldenProfile.copy(
            clientEventId = ClientUuid.parse("3fa85f64-5717-4562-b3fc-2c963f66afa7")
        )
        assertNotEquals(goldenDigest, tampered.computePayloadDigest())
        assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.verifyOnlineSaleIntentProfile(tampered, goldenDigest)
        }
    }

    @Test
    fun testCheck26eTaxIdentityTamperingDetected() {
        val tamperedLine = goldenProfile.lines.first().copy(
            taxRateId = ServerUlid.parse("01J9Z5K3MN0000000000000031")
        )
        val tampered = goldenProfile.copy(lines = listOf(tamperedLine))
        assertNotEquals(goldenDigest, tampered.computePayloadDigest())
        assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.verifyOnlineSaleIntentProfile(tampered, goldenDigest)
        }
    }

    @Test
    fun testCheck26fTaxInclusiveTamperingDetected() {
        val tamperedLine = goldenProfile.lines.first().copy(
            taxInclusive = false
        )
        val tampered = goldenProfile.copy(lines = listOf(tamperedLine))
        assertNotEquals(goldenDigest, tampered.computePayloadDigest())
        assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.verifyOnlineSaleIntentProfile(tampered, goldenDigest)
        }
    }

    @Test
    fun testCheck26gConversionToBaseTamperingDetected() {
        val tamperedLine = goldenProfile.lines.first().copy(
            conversionToBase = PositiveDecimalQuantity.fromPositiveString("1.500000")
        )
        val tampered = goldenProfile.copy(lines = listOf(tamperedLine))
        assertNotEquals(goldenDigest, tampered.computePayloadDigest())
        assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.verifyOnlineSaleIntentProfile(tampered, goldenDigest)
        }
    }

    @Test
    fun testCheck26hDecimalLexemeTamperingDetected() {
        val tamperedLine = goldenProfile.lines.first().copy(
            unitPrice = MoneyAmount.fromNonnegativeString("25.000001")
        )
        val tampered = goldenProfile.copy(lines = listOf(tamperedLine))
        assertNotEquals(goldenDigest, tampered.computePayloadDigest())
        assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.verifyOnlineSaleIntentProfile(tampered, goldenDigest)
        }
    }

    @Test
    fun testCheck26iLineOrderTamperingDetected() {
        val line1 = goldenProfile.lines.first()
        val line2 = line1.copy(
            productId = ServerUlid.parse("01JCV8X9M6A1B2C3D4E5F6G7H9"),
            productUnitId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012346")
        )
        val multiLineProfile = goldenProfile.copy(lines = listOf(line1, line2))
        val multiLineDigest = multiLineProfile.computePayloadDigest()

        // Swap order: line2 before line1
        val swappedProfile = goldenProfile.copy(lines = listOf(line2, line1))
        val swappedDigest = swappedProfile.computePayloadDigest()

        assertNotEquals("Line order change must alter canonical digest", multiLineDigest, swappedDigest)
        assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.verifyOnlineSaleIntentProfile(swappedProfile, multiLineDigest)
        }
    }

    @Test
    fun testCheck26jPaymentTamperingDetected() {
        val tamperedPayment = goldenProfile.payment.copy(
            amount = MoneyAmount.fromPositiveString("50.000001")
        )
        val tampered = goldenProfile.copy(payment = tamperedPayment)
        assertNotEquals(goldenDigest, tampered.computePayloadDigest())
        assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.verifyOnlineSaleIntentProfile(tampered, goldenDigest)
        }
    }

    @Test
    fun testCheck26kCurrencyMinorUnitsTamperingDetected() {
        val tampered = goldenProfile.copy(
            currencyMinorUnits = 3
        )
        assertNotEquals(goldenDigest, tampered.computePayloadDigest())
        assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.verifyOnlineSaleIntentProfile(tampered, goldenDigest)
        }
    }
}
