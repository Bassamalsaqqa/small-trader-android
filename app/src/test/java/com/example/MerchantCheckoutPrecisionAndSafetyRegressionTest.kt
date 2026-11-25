package com.example

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.data.OfflineDemoTraderRepository
import com.example.data.ProductionTraderRepository
import com.example.data.TraderRepository
import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonNumber
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonString
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.contract.v2.OnlineSaleIntentLine
import com.example.data.mobile.readclient.BootstrapResponse
import com.example.model.BusinessInfo
import com.example.model.Customer
import com.example.model.InvoiceRecord
import com.example.model.Product
import com.example.model.TraderScreen
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.ui.screens.CashPaymentScreen
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderViewModel
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MerchantCheckoutPrecisionAndSafetyRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun cashCollection_unrepresentableThreeDecimals_blockedBeforeTender_withClearMessage() {
        val repo = OfflineDemoTraderRepository(
            initialBusinessInfo = BusinessInfo(
                defaultCurrency = "JOD",
                defaultCurrencyMinorUnits = 3
            )
        )
        val viewModel = TraderViewModel(repo)

        // Add a line item costing exactly 9.833 JOD
        viewModel.addCustomQuickItem("Custom JOD Item", "9.833")

        val state = viewModel.uiState.value
        assertEquals("JOD", state.businessInfo.defaultCurrency)
        assertEquals(3, state.businessInfo.defaultCurrencyMinorUnits)
        assertEquals(BigDecimal("9.833"), state.basketNetTotalExact)

        // Verifies isCashCollectionUnrepresentable is detected (fraction beyond 2 decimal places)
        assertTrue(
            "9.833 net total must be flagged as unrepresentable for 2-decimal cash collection",
            state.isCashCollectionUnrepresentable
        )

        // setCashExactAmount must fail closed and refuse to round 9.833 to 9.83, setting checkout UI error
        viewModel.setCashExactAmount()
        val error = viewModel.uiState.value.checkoutErrorMessage
        assertNotNull("setCashExactAmount must set checkout error when net total has scale > 2", error)
        assertTrue(
            "Error message must clearly explain unrepresentable cash collection: $error",
            error?.contains("خانتين") == true ||
                error?.contains("كسور") == true ||
                error?.contains("حساب زبون") == true ||
                error?.contains("credit") == true ||
                error?.contains("decimal") == true ||
                error?.contains("basket") == true
        )
        assertEquals("cashInputText must remain empty when unrepresentable", "", viewModel.uiState.value.cashInputText)

        // Cashier manually types 9.83
        viewModel.updateCashInputText("9.83")
        assertFalse(
            "9.83 cash tendered is strictly less than 9.833 due",
            viewModel.uiState.value.isCashSufficient
        )

        // Cashier manually types 9.84 (overpayment)
        viewModel.updateCashInputText("9.84")
        assertTrue(viewModel.uiState.value.cashTenderedExact >= viewModel.uiState.value.basketNetTotalExact)
        // However change due is 9.84 - 9.833 = 0.007, which is unrepresentable in 2-decimal cash
        assertTrue(viewModel.uiState.value.isCashCollectionUnrepresentable)
        assertFalse("Cash cannot be marked sufficient when cash collection is unrepresentable", viewModel.uiState.value.isCashSufficient)

        // Attempting to complete cash sale must fail and preserve state
        val initialInvoices = viewModel.uiState.value.invoices.size
        viewModel.completeCashSale(asCreditDebt = false)
        assertEquals("Sale must NOT be posted", initialInvoices, viewModel.uiState.value.invoices.size)
        assertNotNull(viewModel.uiState.value.checkoutErrorMessage)
        assertTrue(
            "Checkout error must advise credit sale or adjusting invoice: ${viewModel.uiState.value.checkoutErrorMessage}",
            viewModel.uiState.value.checkoutErrorMessage?.contains("دقة النقد") == true ||
                viewModel.uiState.value.checkoutErrorMessage?.contains("decimal") == true ||
                viewModel.uiState.value.checkoutErrorMessage?.contains("credit") == true
        )

        // Render CashPaymentScreen and verify complete button is disabled and warning is displayed
        composeTestRule.setContent {
            val currentState by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = currentState.isDarkTheme, isRtl = currentState.language.isRtl) {
                CashPaymentScreen(
                    state = currentState,
                    onSetExactAmount = viewModel::setCashExactAmount,
                    onSelectDenomination = viewModel::setCashQuickDenomination,
                    onUpdateCashText = viewModel::updateCashInputText,
                    onKeypadPress = viewModel::onCashKeypadPress,
                    onAssignCustomer = viewModel::assignCustomerToSale,
                    onDismissCheckoutError = viewModel::dismissCheckoutError,
                    onEditInvoice = { viewModel.navigateTo(TraderScreen.INVOICE_REVIEW) },
                    onCompleteSale = { viewModel.completeCashSale(asCreditDebt = false) },
                    onCompleteCreditSale = { viewModel.completeCashSale(asCreditDebt = true) }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("cash_collection_unrepresentable_warning").assertExists()
        composeTestRule.onNodeWithTag("complete_sale_button").assertIsNotEnabled()
    }

    @Test
    fun creditSale_exactEconomicAmount_postsWithoutArbitraryRounding() {
        val repo = OfflineDemoTraderRepository(
            initialBusinessInfo = BusinessInfo(
                defaultCurrency = "JOD",
                defaultCurrencyMinorUnits = 3
            )
        )
        val viewModel = TraderViewModel(repo)

        // Add 9.833 item
        viewModel.addCustomQuickItem("Custom JOD Item", "9.833")

        // Link customer with sufficient credit limit
        val customer = viewModel.uiState.value.customers.first { it.id == "cust_01" }
        viewModel.assignCustomerToSale(customer)

        val initialInvoices = viewModel.uiState.value.invoices.size

        // Complete credit sale
        viewModel.completeCashSale(asCreditDebt = true)

        assertEquals("Sale must be posted", initialInvoices + 1, viewModel.uiState.value.invoices.size)
        val receipt = checkNotNull(viewModel.uiState.value.activeReceipt)
        assertEquals(BigDecimal("9.833"), receipt.netTotalExact)
        assertEquals(BigDecimal.ZERO, receipt.cashReceivedExact)
        assertEquals(BigDecimal("9.833"), receipt.remainingDebitExact)

        // Verify customer balance updated with exact decimal arithmetic (185.00 + 9.833 = 194.833)
        val updatedCustomer = repo.getCustomers().first { it.id == "cust_01" }
        assertEquals(customer.balanceExact!!.add(BigDecimal("9.833")), updatedCustomer.balanceExact)
    }

    @Test
    fun exactCashTender_conservationAndZeroEpsilon() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        viewModel.addCustomQuickItem("Exact Item", "10.00")
        val state = viewModel.uiState.value
        assertEquals(BigDecimal("10.00"), state.basketNetTotalExact)

        // Exact match
        viewModel.updateCashInputText("10.00")
        assertTrue(viewModel.uiState.value.isCashSufficient)
        assertTrue(viewModel.uiState.value.isExactCashTendered)
        assertEquals(BigDecimal.ZERO, viewModel.uiState.value.changeDueExact)
        assertEquals(BigDecimal.ZERO, viewModel.uiState.value.remainingUnpaidExact)

        // Underpayment by 1 cent (9.99): must be strictly insufficient with ZERO epsilon
        viewModel.updateCashInputText("9.99")
        assertFalse(
            "9.99 tendered for 10.00 must be insufficient (no 0.005 epsilon bypass)",
            viewModel.uiState.value.isCashSufficient
        )
        assertFalse(viewModel.uiState.value.isExactCashTendered)
        assertEquals(BigDecimal("0.01"), viewModel.uiState.value.remainingUnpaidExact)

        // Overpayment by 1 cent (10.01)
        viewModel.updateCashInputText("10.01")
        assertTrue(viewModel.uiState.value.isCashSufficient)
        assertFalse(viewModel.uiState.value.isExactCashTendered)
        assertEquals(BigDecimal("0.01"), viewModel.uiState.value.changeDueExact)
        assertEquals(BigDecimal.ZERO, viewModel.uiState.value.remainingUnpaidExact)
    }

    @Test
    fun creditLimit_zeroEpsilonEnforcement() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // cust_02 (Um Samer): creditLimit = 800.00, balance = 90.00 (remaining limit = 710.00)
        val customer = viewModel.uiState.value.customers.first { it.id == "cust_02" }
        viewModel.assignCustomerToSale(customer)

        // Item total = 710.01 -> projected balance = 90.00 + 710.01 = 800.01 > 800.00
        viewModel.addCustomQuickItem("Over Limit Item", "710.01")
        val state = viewModel.uiState.value
        assertTrue(
            "710.01 credit on remaining 710.00 must exceed limit without epsilon bypass",
            state.wouldCreditExceedCustomerLimit
        )
        assertEquals(BigDecimal("0.01"), state.creditCeilingExcessExact)

        // Attempting credit sale must fail closed
        val initialInvoices = state.invoices.size
        viewModel.completeCashSale(asCreditDebt = true)
        assertEquals("Sale over credit limit must not post", initialInvoices, viewModel.uiState.value.invoices.size)
        assertTrue(
            viewModel.uiState.value.checkoutErrorMessage?.contains("سقف الدين") == true ||
                viewModel.uiState.value.checkoutErrorMessage?.contains("credit limit") == true
        )
    }

    @Test
    fun bootstrap_dualSchema_failClosedWithoutFallback() {
        // Base 12-key golden JSON
        val goldenFile = File(com.example.data.mobile.outbox.OutboxTestFixtures.locateFixturesDir(), "golden-bootstrap.json")
        val baseObj = com.example.data.mobile.contract.StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()

        // 1. Default parser (12 keys): succeeds without currency_minor_units
        val parsed12 = BootstrapResponse.fromStrictJson(baseObj, includeCurrencyMinorUnits = false)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789A", parsed12.companyId.value)
        assertNull(parsed12.currencyMinorUnits)

        // 2. Opt-in parser (13 keys) requested on 12-key payload: FAILS CLOSED without fallback to 2
        try {
            BootstrapResponse.fromStrictJson(baseObj, includeCurrencyMinorUnits = true)
            fail("Expected CanonicalSerializationException when currency_minor_units is requested but missing")
        } catch (e: CanonicalSerializationException) {
            assertTrue(e.message?.contains("currency_minor_units was requested") == true)
        }

        // 3. Opt-in parser with valid 13-key payload: SUCCEEDS
        val withPrecisionProps = baseObj.properties.toMutableMap()
        withPrecisionProps["currency_minor_units"] = StrictJsonNumber("3", isInt = true)
        val valid13Obj = StrictJsonObject(withPrecisionProps)

        val parsed13 = BootstrapResponse.fromStrictJson(valid13Obj, includeCurrencyMinorUnits = true)
        assertEquals(3, parsed13.currencyMinorUnits)

        // 4. Opt-in parser with out-of-range precision (e.g. 7): FAILS CLOSED
        withPrecisionProps["currency_minor_units"] = StrictJsonNumber("7", isInt = true)
        val invalidRangeObj = StrictJsonObject(withPrecisionProps)
        try {
            BootstrapResponse.fromStrictJson(invalidRangeObj, includeCurrencyMinorUnits = true)
            fail("Expected CanonicalSerializationException when precision is out of range")
        } catch (e: CanonicalSerializationException) {
            assertTrue(e.message?.contains("currency_minor_units") == true && (e.message?.contains("must be <=") == true || e.message?.contains("6") == true))
        }
    }

    @Test
    fun pendingSync_preservesBasketAndEventId_andEnablesHoldRecovery() {
        // Create a repository fake that returns PENDING_SYNC for sale
        val storedInvoices = mutableListOf<InvoiceRecord>()
        val fakeRepo = object : TraderRepository by OfflineDemoTraderRepository() {
            override suspend fun recordSale(invoice: InvoiceRecord): Result<InvoiceRecord> {
                val pendingInvoice = invoice.copy(outboxStatus = "PENDING_SYNC")
                storedInvoices.add(pendingInvoice)
                return Result.success(pendingInvoice)
            }

            override fun getInvoices(): List<InvoiceRecord> = storedInvoices.toList()
        }

        val viewModel = TraderViewModel(fakeRepo)
        viewModel.addCustomQuickItem("Preserved Item", "20.00")
        val initialEventId = viewModel.uiState.value.currentSaleClientEventId
        assertNotNull(initialEventId)
        assertEquals(1, viewModel.uiState.value.basket.size)

        // Navigate to CASH_PAYMENT screen
        viewModel.navigateTo(TraderScreen.INVOICE_REVIEW)
        viewModel.navigateTo(TraderScreen.CASH_PAYMENT)
        assertEquals(TraderScreen.CASH_PAYMENT, viewModel.uiState.value.currentScreen)

        // Attempt complete cash sale
        viewModel.setCashExactAmount()
        viewModel.completeCashSale(asCreditDebt = false)

        val state = viewModel.uiState.value
        // INVARIANTS:
        // 1. isPaymentUnverifiedHold is true
        assertTrue("isPaymentUnverifiedHold must be true", state.isPaymentUnverifiedHold)
        // 2. Basket is NOT cleared
        assertEquals("Basket must be preserved on PENDING_SYNC", 1, state.basket.size)
        // 3. currentSaleClientEventId is NOT cleared or regenerated
        assertEquals("Client event ID must be preserved on PENDING_SYNC", initialEventId, state.currentSaleClientEventId)
        // 4. Still on payment screen / hold state (not navigated to RECEIPT)
        assertEquals(TraderScreen.CASH_PAYMENT, state.currentScreen)
        assertFalse(state.isNewlyCompletedSale)

        // Now simulate server outbox confirmation with canonical IDs
        storedInvoices.clear()
        val confirmedInvoice = state.activeReceipt!!.copy(
            outboxStatus = "CONFIRMED",
            serverInvoiceUlid = "01JMB000000000000000000099",
            serverPaymentUlid = "01JMB000000000000000000098"
        )
        storedInvoices.add(confirmedInvoice)

        // Cashier/system checks held sale status
        viewModel.checkHeldSaleStatus()

        val confirmedState = viewModel.uiState.value
        assertFalse("isPaymentUnverifiedHold must clear", confirmedState.isPaymentUnverifiedHold)
        assertTrue("isNewlyCompletedSale must be true", confirmedState.isNewlyCompletedSale)
        assertEquals("Basket must be cleared on confirmed recovery", 0, confirmedState.basket.size)
        assertEquals("Must navigate to RECEIPT screen", TraderScreen.RECEIPT, confirmedState.currentScreen)
    }

    @Test
    fun onlineSaleIntentLine_taxInclusiveSnapshot_andExplicitNoTax() {
        val prodUlid = ServerUlid.parse("01JMB000000000000000000010")
        val unitUlid = ServerUlid.parse("01JMB000000000000000000011")
        val taxUlid = ServerUlid.parse("01JMB000000000000000000012")

        // 1. With tax rate and taxInclusive = true
        val lineTaxInclusive = OnlineSaleIntentLine(
            productId = prodUlid,
            productUnitId = unitUlid,
            conversionToBase = PositiveDecimalQuantity.fromPositiveString("1.000000"),
            quantity = PositiveDecimalQuantity.fromPositiveString("2.000000"),
            unitPrice = MoneyAmount.fromNonnegativeString("10.000000"),
            taxRateId = taxUlid,
            taxRate = TaxPercentage.fromString("16.000000"),
            taxInclusive = true
        )
        assertEquals(true, lineTaxInclusive.taxInclusive)
        assertEquals("16.000000", lineTaxInclusive.taxRate.toPlainString())

        // 2. ExplicitNoTax: taxRateId is null -> taxRate must be 0.000000 and taxInclusive false
        val lineNoTax = OnlineSaleIntentLine(
            productId = prodUlid,
            productUnitId = unitUlid,
            conversionToBase = PositiveDecimalQuantity.fromPositiveString("1.000000"),
            quantity = PositiveDecimalQuantity.fromPositiveString("1.000000"),
            unitPrice = MoneyAmount.fromNonnegativeString("5.000000"),
            taxRateId = null,
            taxRate = TaxPercentage.fromString("0.000000"),
            taxInclusive = false
        )
        assertEquals(false, lineNoTax.taxInclusive)
        assertEquals("0.000000", lineNoTax.taxRate.toPlainString())

        // 3. ExplicitNoTax violation: taxRateId is null but taxInclusive = true -> FAILS
        try {
            OnlineSaleIntentLine(
                productId = prodUlid,
                productUnitId = unitUlid,
                conversionToBase = PositiveDecimalQuantity.fromPositiveString("1.000000"),
                quantity = PositiveDecimalQuantity.fromPositiveString("1.000000"),
                unitPrice = MoneyAmount.fromNonnegativeString("5.000000"),
                taxRateId = null,
                taxRate = TaxPercentage.fromString("0.000000"),
                taxInclusive = true
            )
            fail("Expected IntentProfileValidationError for taxInclusive=true with null taxRateId")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("ExplicitNoTax") == true)
        }
    }

    @Test
    fun unverifiedHold_survivesVmRecreation_andBlocksEditingOrResubmission() {
        val prod = com.example.data.SampleCatalog.initialProducts.first()
        val pendingInvoice = InvoiceRecord(
            id = "inv_pending_recovery",
            invoiceNumber = 101,
            timestamp = "2026-11-25T10:00:00Z",
            items = listOf(com.example.model.BasketItem(prod, 1)),
            subtotalExact = BigDecimal("10.00"),
            discountExact = BigDecimal.ZERO,
            netTotalExact = BigDecimal("10.00"),
            cashReceivedExact = BigDecimal("10.00"),
            changeReturnedExact = BigDecimal.ZERO,
            customerNote = "",
            cashierNameAr = "Cashier",
            cashierNameEn = "Cashier",
            isPaidInFull = true,
            clientEventId = "event_pending_123",
            outboxStatus = "PENDING_SYNC"
        )
        val fakeRepo = object : TraderRepository by OfflineDemoTraderRepository() {
            override fun getInvoices(): List<InvoiceRecord> = listOf(pendingInvoice)
        }

        // Recreate ViewModel and verify hold state is recovered
        val recreatedVm = TraderViewModel(fakeRepo)
        val state = recreatedVm.uiState.value
        assertTrue("isPaymentUnverifiedHold must be recovered", state.isPaymentUnverifiedHold)
        assertEquals(TraderScreen.CASH_PAYMENT, state.currentScreen)
        assertEquals("event_pending_123", state.currentSaleClientEventId)
        assertEquals(1, state.basket.size)
        assertEquals("inv_pending_recovery", state.activeReceipt?.id)

        // Block editing basket while unverified
        recreatedVm.addProductToBasket(com.example.data.SampleCatalog.initialProducts[1], 1)
        assertEquals("Basket must not accept additions while on unverified hold", 1, recreatedVm.uiState.value.basket.size)

        recreatedVm.clearBasket()
        assertEquals("Basket must not be cleared while on unverified hold", 1, recreatedVm.uiState.value.basket.size)

        // Block resubmission while unverified
        recreatedVm.completeCashSale(asCreditDebt = false)
        assertTrue(recreatedVm.uiState.value.isPaymentUnverifiedHold)
        assertFalse(recreatedVm.uiState.value.isNewlyCompletedSale)
    }

    @Test
    fun customerPayment_rejectsPrecisionExceedingMinorUnits_withoutSilentRounding() {
        val repo = OfflineDemoTraderRepository(
            initialBusinessInfo = BusinessInfo(
                defaultCurrency = "JOD",
                defaultCurrencyMinorUnits = 3
            )
        )
        val viewModel = TraderViewModel(repo)

        // Attempting to record 10.1234 (4 decimal places when currency minor units is 3) must be rejected
        var callbackCalled = false
        val success = viewModel.recordCustomerPayment(
            customerId = "cust_01",
            amount = BigDecimal("10.1234"),
            note = "Exceeds precision"
        ) { res ->
            callbackCalled = true
            assertFalse("Payment with scale > 3 must fail", res.isSuccess)
        }
        assertFalse("Must return false when precision exceeds minor units", success)
        assertTrue(callbackCalled)

        // Valid 3 decimal places succeeds
        val validSuccess = viewModel.recordCustomerPayment(
            customerId = "cust_01",
            amount = BigDecimal("10.123"),
            note = "Valid 3 decimals"
        )
        assertTrue("Valid 3 decimals must succeed", validSuccess)
    }
}
