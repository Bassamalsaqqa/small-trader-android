package com.example.data.mobile.contract.v2

import com.example.data.mobile.contract.StrictJsonBoolean
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Wire validation tests conforming to Python validate-mobile-contract.py Checks 30, 31, and 32:
 * Asserts fail-closed behavior on unknown keys, missing required keys, boolean/int confusion,
 * and missing tax_rate_id.
 */
class ContractV2WireValidationTest {

    private lateinit var goldenContext: StrictJsonObject
    private lateinit var goldenRequest: StrictJsonObject

    @Before
    fun setUp() {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        val goldenFile = File(root, "docs/integration/mobile-v1/fixtures/golden-online-sale-intent-profile.json")

        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        goldenContext = rootJson.require("context").asObject()
        goldenRequest = rootJson.require("request").asObject()
    }

    @Test
    fun testCheck30aMissingTaxRateIdThrowsNeverImplicitNoTax() {
        val context = OnlineSaleContext.fromStrictJson(goldenContext)

        // Clone request and delete tax_rate_id from the line item
        val reqProps = goldenRequest.properties.toMutableMap()
        val linesArray = goldenRequest.require("lines").asArray()
        val lineProps = linesArray[0].asObject().properties.toMutableMap()
        lineProps.remove("tax_rate_id")
        lineProps["tax_rate"] = StrictJsonString("0.000000")
        lineProps["tax_inclusive"] = StrictJsonBoolean(false)

        reqProps["lines"] = com.example.data.mobile.contract.StrictJsonArray(listOf(StrictJsonObject(lineProps)))
        val badReq = StrictJsonObject(reqProps)

        val ex = assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.fromContextAndRequest(context, badReq)
        }
        assertTrue(ex.message!!.contains("Missing required 'tax_rate_id'"))
    }

    @Test
    fun testCheck31aUnknownRootFieldRejectedBeforeProjection() {
        val context = OnlineSaleContext.fromStrictJson(goldenContext)

        val reqProps = goldenRequest.properties.toMutableMap()
        reqProps["unknown_root_property"] = StrictJsonString("malicious_payload")
        val badReq = StrictJsonObject(reqProps)

        val ex = assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.fromContextAndRequest(context, badReq)
        }
        assertTrue(ex.message!!.contains("Unknown root fields"))
    }

    @Test
    fun testCheck31bUnknownLineFieldRejectedBeforeProjection() {
        val context = OnlineSaleContext.fromStrictJson(goldenContext)

        val reqProps = goldenRequest.properties.toMutableMap()
        val linesArray = goldenRequest.require("lines").asArray()
        val lineProps = linesArray[0].asObject().properties.toMutableMap()
        lineProps["unknown_line_property"] = StrictJsonString("malicious_line_payload")
        reqProps["lines"] = com.example.data.mobile.contract.StrictJsonArray(listOf(StrictJsonObject(lineProps)))
        val badReq = StrictJsonObject(reqProps)

        val ex = assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.fromContextAndRequest(context, badReq)
        }
        assertTrue(ex.message!!.contains("Unknown line field"))
    }

    @Test
    fun testCheck31cUnknownPaymentFieldRejectedBeforeProjection() {
        val context = OnlineSaleContext.fromStrictJson(goldenContext)

        val reqProps = goldenRequest.properties.toMutableMap()
        val paymentProps = goldenRequest.require("payment").asObject().properties.toMutableMap()
        paymentProps["unknown_payment_property"] = StrictJsonString("malicious_payment_payload")
        reqProps["payment"] = StrictJsonObject(paymentProps)
        val badReq = StrictJsonObject(reqProps)

        val ex = assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleIntentProfile.fromContextAndRequest(context, badReq)
        }
        assertTrue(ex.message!!.contains("Unknown payment fields"))
    }

    @Test
    fun testCheck32aContextCurrencyMinorUnitsBooleanTrueRejected() {
        val ctxProps = goldenContext.properties.toMutableMap()
        ctxProps["currency_minor_units"] = StrictJsonBoolean(true)
        val badCtx = StrictJsonObject(ctxProps)

        val ex = assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleContext.fromStrictJson(badCtx)
        }
        assertTrue(ex.message!!.contains("rejecting boolean"))
    }

    @Test
    fun testCheck32bContextCurrencyMinorUnitsBooleanFalseRejected() {
        val ctxProps = goldenContext.properties.toMutableMap()
        ctxProps["currency_minor_units"] = StrictJsonBoolean(false)
        val badCtx = StrictJsonObject(ctxProps)

        val ex = assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleContext.fromStrictJson(badCtx)
        }
        assertTrue(ex.message!!.contains("rejecting boolean"))
    }

    @Test
    fun testCheck32cContextWithExtraKeyRejected() {
        val ctxProps = goldenContext.properties.toMutableMap()
        ctxProps["unexpected_context_key"] = StrictJsonString("extra_value")
        val badCtx = StrictJsonObject(ctxProps)

        val ex = assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleContext.fromStrictJson(badCtx)
        }
        assertTrue(ex.message!!.contains("Unexpected keys in context"))
    }

    @Test
    fun testCheck32dContextMissingKeyRejected() {
        val ctxProps = goldenContext.properties.toMutableMap()
        ctxProps.remove("company_id")
        val badCtx = StrictJsonObject(ctxProps)

        val ex = assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleContext.fromStrictJson(badCtx)
        }
        assertTrue(ex.message!!.contains("Missing required context keys"))
    }

    @Test
    fun testCheck32eNonUlidUuidInContextRejected() {
        val ctxProps = goldenContext.properties.toMutableMap()
        ctxProps["actor_id"] = StrictJsonString("3fa85f64-5717-4562-b3fc-2c963f66afa6") // UUID, not Crockford ULID
        val badCtx = StrictJsonObject(ctxProps)

        val ex = assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleContext.fromStrictJson(badCtx)
        }
        assertTrue(ex.message!!.contains("Invalid actor_id ULID"))
    }

    @Test
    fun testContextIdentityTrailingNewlineRejected() {
        val ctxProps = goldenContext.properties.toMutableMap()
        ctxProps["actor_id"] = StrictJsonString("01JCV8X9M8T1V2W3X4Y5Z67890\n")
        val badCtx = StrictJsonObject(ctxProps)

        val ex = assertThrows(IntentProfileValidationError::class.java) {
            OnlineSaleContext.fromStrictJson(badCtx)
        }
        assertTrue(ex.message!!.contains("Invalid actor_id ULID"))
    }
}
