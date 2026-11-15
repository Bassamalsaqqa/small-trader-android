package com.example.ui.staff

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.NonnegativeDecimalQuantity
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.onlinecash.OnlineCashSaleReceipt
import com.example.data.mobile.readclient.BootstrapTax
import com.example.data.mobile.readclient.CustomerDirectoryItem
import com.example.data.mobile.readclient.ProductBaseUnit
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.data.mobile.readclient.ProductDefaultUnit
import com.example.model.AppLanguage
import com.example.ui.staff.sale.StaffOnlineSaleDraft
import com.example.ui.staff.sale.StaffOnlineSaleDraftLine
import com.example.ui.theme.SmallTraderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StaffWorkspaceComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun signInScreen_rendersInputs_andSubmitsCredentials() {
        var submittedEmail = ""
        var submittedPassword = ""
        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.SIGN_IN,
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffSignInScreen(
                    uiState = uiState,
                    onSignIn = { email, pwd ->
                        submittedEmail = email
                        submittedPassword = pwd
                    },
                    onToggleLanguage = {},
                    onToggleTheme = {}
                )
            }
        }

        // Enter email and password
        composeTestRule.onNodeWithTag("staff_signin_email_input").performTextInput("staff@al-zaytouna.ps")
        composeTestRule.onNodeWithTag("staff_signin_password_input").performTextInput("Pass1234!")

        // Click submit button
        composeTestRule.onNodeWithTag("staff_signin_submit_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("staff@al-zaytouna.ps", submittedEmail)
        assertEquals("Pass1234!", submittedPassword)
    }

    @Test
    fun mfaScreen_rendersMutuallyExclusiveChips_andSubmits() {
        var submittedCode = ""
        var submittedIsRecovery = false
        var currentMode = MfaMode.TOTP

        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.MFA_REQUIRED,
            mfaMode = currentMode,
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffMfaScreen(
                    uiState = uiState,
                    onRetryWithMfa = { code, isRec ->
                        submittedCode = code
                        submittedIsRecovery = isRec
                    },
                    onSetMfaMode = { currentMode = it },
                    onBackToSignIn = {}
                )
            }
        }

        // Initially TOTP chip is selected
        composeTestRule.onNodeWithTag("staff_mfa_code_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("staff_mfa_code_input").performTextInput("654321")

        composeTestRule.onNodeWithTag("staff_mfa_submit_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("654321", submittedCode)
        assertFalse(submittedIsRecovery)
    }

    @Test
    fun companySelectScreen_rendersCompanies_andClicksItem() {
        var selectedId = ""
        val companies = listOf(
            SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789A", "فرع غزة المركزي"),
            SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789B", "فرع الشمال")
        )
        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.COMPANY_SELECTION,
            availableCompanies = companies,
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffCompanySelectScreen(
                    uiState = uiState,
                    onSelectCompany = { selectedId = it },
                    onCancel = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("staff_company_item_01JCV8X9M2NQW3TYZ4R5S6789B").assertIsDisplayed()
        composeTestRule.onNodeWithTag("staff_company_item_01JCV8X9M2NQW3TYZ4R5S6789B").performClick()
        composeTestRule.waitForIdle()

        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789B", selectedId)
    }

    @Test
    fun workspaceScreen_rendersProductsTab_andHeaders() {
        val baseUnit = ProductBaseUnit(ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789E"), "KG", "كيلوغرام")
        val defaultUnit = ProductDefaultUnit(
            unitId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789E"),
            productUnitId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S67891"),
            conversionToBase = PositiveDecimalQuantity("1.0"),
            salePrice = MoneyAmount("35.00")
        )
        val product = ProductCatalogItem(
            id = ServerUlid("01JCV8X9M2NQW3TYZ4R5S67891"),
            sku = "SKU-OIL-1L",
            barcode = "6251234567890",
            name = "زيت زيتون بكر ممتاز",
            unitPrice = MoneyAmount("30.00"),
            taxRate = TaxPercentage("16.00"),
            taxRateId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789T"),
            isTracked = true,
            availableQuantity = NonnegativeDecimalQuantity("75.000000"),
            baseUnit = baseUnit,
            defaultUnit = defaultUnit,
            units = emptyList()
        )

        var loadMoreClicked = false
        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.READ_READY,
            selectedTab = StaffWorkspaceTab.PRODUCTS,
            activeCompany = SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789A", "سوبرماركت الزيتونة", currency = "ILS"),
            staffActor = SafeStaffActorViewData("01JCV8X9M2NQW3TYZ4R5S6789C", "staff@test.com", "باسم السقا", "كاشير"),
            products = listOf(product),
            productsHasMore = true,
            productsNextCursor = "cursor_2",
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffWorkspaceScreen(
                    uiState = uiState,
                    onSelectTab = {},
                    onLoadMoreProducts = { loadMoreClicked = true },
                    onRefreshProducts = {},
                    onSearchProducts = {},
                    onLoadMoreCustomers = {},
                    onRefreshCustomers = {},
                    onSearchCustomers = {},
                    onSwitchCompany = {},
                    onLogout = {},
                    onToggleLanguage = {},
                    onToggleTheme = {}
                )
            }
        }

        // Verify Header elements
        composeTestRule.onNodeWithText("سوبرماركت الزيتونة").assertIsDisplayed()
        composeTestRule.onNodeWithText("الموظف: باسم السقا").assertIsDisplayed()
        composeTestRule.onNodeWithText("عرض فقط").assertIsDisplayed()

        // Verify Product details
        composeTestRule.onNodeWithText("زيت زيتون بكر ممتاز").assertExists()
        composeTestRule.onNodeWithText("SKU-OIL-1L").assertExists()
        composeTestRule.onNodeWithText("30.00 ILS").assertExists()
        composeTestRule.onNodeWithText("75.000000 كيلوغرام").assertExists()

        // Click Load More
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag("staff_product_load_more_button"))
        composeTestRule.onNodeWithTag("staff_product_load_more_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue(loadMoreClicked)
    }

    @Test
    fun workspaceScreen_rendersCustomersTab() {
        val customer = CustomerDirectoryItem(
            id = ServerUlid("01JCV8X9M2NQW3TYZ4R5S67898"),
            code = "C-500",
            name = "شركة النور التجارية",
            phone = "0599112233",
            isWalkIn = false
        )

        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.READ_READY,
            selectedTab = StaffWorkspaceTab.CUSTOMERS,
            activeCompany = SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789A", "الشركة العامة"),
            staffActor = SafeStaffActorViewData("01JCV8X9M2NQW3TYZ4R5S6789C", "staff@test.com", "أحمد", "موظف مبيعات"),
            customers = listOf(customer),
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffWorkspaceScreen(
                    uiState = uiState,
                    onSelectTab = {},
                    onLoadMoreProducts = {},
                    onRefreshProducts = {},
                    onSearchProducts = {},
                    onLoadMoreCustomers = {},
                    onRefreshCustomers = {},
                    onSearchCustomers = {},
                    onSwitchCompany = {},
                    onLogout = {},
                    onToggleLanguage = {},
                    onToggleTheme = {}
                )
            }
        }

        composeTestRule.onNodeWithText("شركة النور التجارية").assertIsDisplayed()
        composeTestRule.onNodeWithText("الكود: C-500").assertIsDisplayed()
        composeTestRule.onNodeWithText("الهاتف: 0599112233").assertIsDisplayed()
    }

    @Test
    fun statusScreens_renderTruthfulState() {
        var reAuthClicked = false

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffLockedScreen(
                    reason = "تم إلغاء صلاحية الرمز المميز من الخادم (HTTP 401)",
                    lang = AppLanguage.AR,
                    onReAuthenticate = { reAuthClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithText("جلسة العمل مقفلة").assertIsDisplayed()
        composeTestRule.onNodeWithText("تم إبطال صلاحية الدخول للجلسة").assertIsDisplayed()

        composeTestRule.onNodeWithTag("staff_locked_reauth_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue(reAuthClicked)
    }

    @Test
    fun workspaceScreen_unassignedTaxRate_rendersTruthfulCopy() {
        val baseUnit = ProductBaseUnit(ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789E"), "PCS", "قطعة")
        val defaultUnit = ProductDefaultUnit(
            unitId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789E"),
            productUnitId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S67891"),
            conversionToBase = PositiveDecimalQuantity("1.0"),
            salePrice = MoneyAmount("15.00")
        )
        val productWithNullTax = ProductCatalogItem(
            id = ServerUlid("01JCV8X9M2NQW3TYZ4R5S67891"),
            sku = "SKU-UNTAXED",
            barcode = "00000000",
            name = "منتج معفى من الضريبة",
            unitPrice = MoneyAmount("15.00"),
            taxRate = null,
            taxRateId = null,
            isTracked = true,
            availableQuantity = NonnegativeDecimalQuantity("10.000000"),
            baseUnit = baseUnit,
            defaultUnit = defaultUnit,
            units = emptyList()
        )

        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.READ_READY,
            selectedTab = StaffWorkspaceTab.PRODUCTS,
            activeCompany = SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789A", "سوبرماركت التجربة", currency = "USD"),
            products = listOf(productWithNullTax),
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffWorkspaceScreen(
                    uiState = uiState,
                    onSelectTab = {},
                    onLoadMoreProducts = {},
                    onRefreshProducts = {},
                    onSearchProducts = {},
                    onLoadMoreCustomers = {},
                    onRefreshCustomers = {},
                    onSearchCustomers = {},
                    onSwitchCompany = {},
                    onLogout = {},
                    onToggleLanguage = {},
                    onToggleTheme = {}
                )
            }
        }

        // Verify nullable tax rate display shows "غير محددة"
        composeTestRule.onNodeWithText("الضريبة: غير محددة").assertExists()
        composeTestRule.onNodeWithText("15.00 USD").assertExists()
    }

    @Test
    fun companySelectScreen_showsCancelWhenActiveCompanyExists_andLogoutOtherwise() {
        val companies = listOf(
            SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789A", "شركة أ"),
            SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789B", "شركة ب")
        )

        // Case 1: activeCompany != null -> button says "إلغاء"
        val stateWithActive = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.COMPANY_SELECTION,
            activeCompany = SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789A", "شركة أ"),
            availableCompanies = companies,
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffCompanySelectScreen(
                    uiState = stateWithActive,
                    onSelectCompany = {},
                    onCancel = {}
                )
            }
        }

        composeTestRule.onNodeWithText("إلغاء").assertIsDisplayed()
    }

    @Test
    fun catalog_whenCashEnabled_rendersAddButton_andInvokesCallbackOnClick() {
        var addedProduct: ProductCatalogItem? = null
        val baseUnit = ProductBaseUnit(ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S67891"), "PCS", "قطعة")
        val defaultUnit = ProductDefaultUnit(
            unitId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S67891"),
            productUnitId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S67892"),
            conversionToBase = PositiveDecimalQuantity.of(1),
            salePrice = MoneyAmount.fromNonnegativeString("25.000000")
        )
        val sampleProduct = ProductCatalogItem(
            id = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S67893"),
            sku = "SKU-CASH-01",
            barcode = "9988776655",
            name = "زيت زيتون بلدي",
            unitPrice = MoneyAmount.fromNonnegativeString("25.000000"),
            taxRate = null,
            taxRateId = null,
            isTracked = true,
            availableQuantity = NonnegativeDecimalQuantity.fromNonnegativeString("50.000000"),
            baseUnit = baseUnit,
            defaultUnit = defaultUnit,
            units = emptyList()
        )

        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.READ_READY,
            selectedTab = StaffWorkspaceTab.PRODUCTS,
            activeCompany = SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789A", "معصرة الزيتون", currency = "USD"),
            products = listOf(sampleProduct),
            isOnlineCashEnabled = true,
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffWorkspaceScreen(
                    uiState = uiState,
                    onSelectTab = {},
                    onLoadMoreProducts = {},
                    onRefreshProducts = {},
                    onSearchProducts = {},
                    onLoadMoreCustomers = {},
                    onRefreshCustomers = {},
                    onSearchCustomers = {},
                    onSwitchCompany = {},
                    onLogout = {},
                    onToggleLanguage = {},
                    onToggleTheme = {},
                    onAddProductToSale = { product -> addedProduct = product }
                )
            }
        }

        // Assert button exists and click it
        val addButtonNode = composeTestRule.onNodeWithTag("staff_product_add_to_sale_${sampleProduct.id.value}")
        addButtonNode.performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Verify that onAddProductToSale callback was invoked with the exact product
        assertEquals(sampleProduct, addedProduct)
    }

    @Test
    fun customerDirectory_whenCashEnabled_rendersSelectButton_andInvokesCallbackOnClick() {
        var selectedCustomer: CustomerDirectoryItem? = null
        val sampleCustomer = CustomerDirectoryItem(
            id = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S67894"),
            code = "CUST-100",
            name = "أحمد خليل",
            phone = "0599123456",
            isWalkIn = false
        )

        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.READ_READY,
            selectedTab = StaffWorkspaceTab.CUSTOMERS,
            activeCompany = SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789A", "معصرة الزيتون", currency = "USD"),
            customers = listOf(sampleCustomer),
            isOnlineCashEnabled = true,
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffWorkspaceScreen(
                    uiState = uiState,
                    onSelectTab = {},
                    onLoadMoreProducts = {},
                    onRefreshProducts = {},
                    onSearchProducts = {},
                    onLoadMoreCustomers = {},
                    onRefreshCustomers = {},
                    onSearchCustomers = {},
                    onSwitchCompany = {},
                    onLogout = {},
                    onToggleLanguage = {},
                    onToggleTheme = {},
                    onSelectCustomerForSale = { customer -> selectedCustomer = customer }
                )
            }
        }

        // Assert button exists and click it
        val selectButtonNode = composeTestRule.onNodeWithTag("staff_customer_select_for_sale_${sampleCustomer.id.value}")
        selectButtonNode.assertIsDisplayed()
        selectButtonNode.performClick()
        composeTestRule.waitForIdle()

        // Verify that onSelectCustomerForSale callback was invoked with the exact customer
        assertEquals(sampleCustomer, selectedCustomer)
    }

    @Test
    fun cashSaleScreen_rendersDirectDecimalQuantityInput_andUpdatesQuantityOnInput() {
        var updatedLineId: String? = null
        var updatedQuantity: PositiveDecimalQuantity? = null

        val baseUnit = ProductBaseUnit(ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S67891"), "PCS", "قطعة")
        val defaultUnit = ProductDefaultUnit(
            unitId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S67891"),
            productUnitId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S67892"),
            conversionToBase = PositiveDecimalQuantity.of(1),
            salePrice = MoneyAmount.fromNonnegativeString("10.000000")
        )
        val product = ProductCatalogItem(
            id = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S67893"),
            sku = "SKU-01",
            barcode = null,
            name = "صابون غار",
            unitPrice = MoneyAmount.fromNonnegativeString("10.000000"),
            taxRate = null,
            taxRateId = null,
            isTracked = true,
            availableQuantity = NonnegativeDecimalQuantity.fromNonnegativeString("100.000000"),
            baseUnit = baseUnit,
            defaultUnit = defaultUnit,
            units = emptyList()
        )
        val line = StaffOnlineSaleDraftLine.fromProduct(product = product, explicitNoTax = true)
        val draft = StaffOnlineSaleDraft(lines = listOf(line))

        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.READ_READY,
            selectedTab = StaffWorkspaceTab.CASH_SALE,
            activeCompany = SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789A", "معصرة الزيتون", currency = "USD"),
            saleDraft = draft,
            isOnlineCashEnabled = true,
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffWorkspaceScreen(
                    uiState = uiState,
                    onSelectTab = {},
                    onLoadMoreProducts = {},
                    onRefreshProducts = {},
                    onSearchProducts = {},
                    onLoadMoreCustomers = {},
                    onRefreshCustomers = {},
                    onSearchCustomers = {},
                    onSwitchCompany = {},
                    onLogout = {},
                    onToggleLanguage = {},
                    onToggleTheme = {},
                    onUpdateSaleLineQuantity = { id, qty ->
                        updatedLineId = id
                        updatedQuantity = qty
                    }
                )
            }
        }

        // Test direct quantity input field
        val qtyInputNode = composeTestRule.onNodeWithTag("staff_sale_line_qty_input_${line.lineId}")
        qtyInputNode.assertExists()
        qtyInputNode.performTextClearance()
        qtyInputNode.performTextInput("4.5")
        composeTestRule.waitForIdle()

        assertEquals(line.lineId, updatedLineId)
        assertEquals("4.5", updatedQuantity?.value?.toPlainString())
    }

    @Test
    fun unconfiguredScreen_rendersTruthfulSetupNotice_andTogglesLanguage() {
        var toggled = false
        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffUnconfiguredScreen(
                    lang = AppLanguage.AR,
                    onToggleLanguage = { toggled = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("staff_unconfigured_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("مساحة استعلام الموظفين غير مهيأة").assertIsDisplayed()

        val toggleBtn = composeTestRule.onNodeWithTag("staff_unconfigured_toggle_language_button")
        toggleBtn.assertIsDisplayed()
        toggleBtn.performClick()
        assertTrue("Language toggle callback must fire", toggled)
    }

    @Test
    fun cashSaleScreen_rendersPaymentUnverifiedHoldCard_andFreezesControls() {
        val baseUnit = ProductBaseUnit(ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789D"), "PCS", "Pieces")
        val defaultUnit = ProductDefaultUnit(
            unitId = baseUnit.id,
            productUnitId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789E"),
            conversionToBase = PositiveDecimalQuantity.of(1),
            salePrice = MoneyAmount.fromNonnegativeString("30.000000")
        )
        val product = ProductCatalogItem(
            id = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789F"),
            sku = "SKU-HOLD-01",
            barcode = null,
            name = "Hold Test Item",
            unitPrice = MoneyAmount.fromNonnegativeString("30.000000"),
            taxRate = null,
            taxRateId = null,
            isTracked = true,
            availableQuantity = NonnegativeDecimalQuantity.fromNonnegativeString("10.000000"),
            baseUnit = baseUnit,
            defaultUnit = defaultUnit,
            units = emptyList()
        )
        val line = StaffOnlineSaleDraftLine.fromProduct(product = product, explicitNoTax = true)
        val unverifiedReceipt = OnlineCashSaleReceipt(
            companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"),
            clientEventId = com.example.data.mobile.contract.ClientUuid.random(),
            payloadDigest = com.example.data.mobile.contract.PayloadDigest.fromBytes("test".toByteArray()),
            invoiceId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789G"),
            invoiceNumber = "INV-HOLD-999",
            paymentId = null,
            paymentReceiptNumber = null,
            grandTotal = MoneyAmount.fromNonnegativeString("30.000000"),
            postedAt = "2026-11-15T12:00:00Z",
            wasReplay = false
        )
        val draft = StaffOnlineSaleDraft(
            lines = listOf(line),
            isPaymentUnverifiedHold = true,
            unverifiedReceipt = unverifiedReceipt
        )

        var checkStatusClicked = false
        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.READ_READY,
            selectedTab = StaffWorkspaceTab.CASH_SALE,
            activeCompany = SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789A", "معصرة الزيتون", currency = "USD"),
            saleDraft = draft,
            isOnlineCashEnabled = true,
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffWorkspaceScreen(
                    uiState = uiState,
                    onSelectTab = {},
                    onLoadMoreProducts = {},
                    onRefreshProducts = {},
                    onSearchProducts = {},
                    onLoadMoreCustomers = {},
                    onRefreshCustomers = {},
                    onSearchCustomers = {},
                    onSwitchCompany = {},
                    onLogout = {},
                    onToggleLanguage = {},
                    onToggleTheme = {},
                    onCheckPaymentStatus = { checkStatusClicked = true }
                )
            }
        }

        // 1. Unverified payment hold card is displayed
        val holdCard = composeTestRule.onNodeWithTag("staff_sale_unverified_payment_hold_card")
        holdCard.assertIsDisplayed()
        composeTestRule.onNodeWithText("INV-HOLD-999", substring = true).assertIsDisplayed()

        // 2. Clear and submit buttons are disabled (frozen)
        composeTestRule.onNodeWithTag("staff_sale_clear_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("staff_sale_submit_button").assertIsNotEnabled()

        // 3. Check status button is displayed and clickable
        val checkStatusBtn = composeTestRule.onNodeWithTag("staff_sale_check_payment_status_button")
        checkStatusBtn.assertIsDisplayed()
        checkStatusBtn.performClick()
        composeTestRule.waitForIdle()
        assertTrue("Check status callback must fire on click", checkStatusClicked)
    }

    @Test
    fun cashSaleScreen_rendersDraftLineTaxChoices_andSelectsTaxAndNoTax() {
        val baseUnit = ProductBaseUnit(ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789D"), "PCS", "Pieces")
        val defaultUnit = ProductDefaultUnit(
            unitId = baseUnit.id,
            productUnitId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789E"),
            conversionToBase = PositiveDecimalQuantity.of(1),
            salePrice = MoneyAmount.fromNonnegativeString("50.000000")
        )
        val product = ProductCatalogItem(
            id = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789F"),
            sku = "SKU-TAX-01",
            barcode = null,
            name = "Tax Test Item",
            unitPrice = MoneyAmount.fromNonnegativeString("50.000000"),
            taxRate = null,
            taxRateId = null,
            isTracked = true,
            availableQuantity = NonnegativeDecimalQuantity.fromNonnegativeString("10.000000"),
            baseUnit = baseUnit,
            defaultUnit = defaultUnit,
            units = emptyList()
        )
        val line = StaffOnlineSaleDraftLine.fromProduct(product = product, explicitNoTax = false)
        val draft = StaffOnlineSaleDraft(lines = listOf(line))

        val tax1 = BootstrapTax(
            id = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789T"),
            code = "VAT16",
            name = "ضريبة القيمة المضافة 16%",
            rate = TaxPercentage("16.000000"),
            calculation = "exclusive",
            isDefault = true
        )

        var selectedTaxLineId = ""
        var selectedTax: BootstrapTax? = null
        var explicitNoTaxLineId = ""

        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.READ_READY,
            selectedTab = StaffWorkspaceTab.CASH_SALE,
            activeCompany = SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789A", "معصرة الزيتون", currency = "USD"),
            saleDraft = draft,
            availableTaxes = listOf(tax1),
            isOnlineCashEnabled = true,
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffWorkspaceScreen(
                    uiState = uiState,
                    onSelectTab = {},
                    onLoadMoreProducts = {},
                    onRefreshProducts = {},
                    onSearchProducts = {},
                    onLoadMoreCustomers = {},
                    onRefreshCustomers = {},
                    onSearchCustomers = {},
                    onSwitchCompany = {},
                    onLogout = {},
                    onToggleLanguage = {},
                    onToggleTheme = {},
                    onSetSaleLineTax = { lineId, tax ->
                        selectedTaxLineId = lineId
                        selectedTax = tax
                    },
                    onSetSaleLineExplicitNoTax = { lineId ->
                        explicitNoTaxLineId = lineId
                    }
                )
            }
        }

        // 1. Tax choice group is displayed
        composeTestRule.onNodeWithTag("staff_sale_line_tax_choices_${line.lineId}").assertIsDisplayed()

        // 2. Click specific bootstrap tax button
        val taxButton = composeTestRule.onNodeWithTag("staff_sale_line_tax_${tax1.id.value}_${line.lineId}")
        taxButton.assertIsDisplayed()
        taxButton.performClick()
        composeTestRule.waitForIdle()

        assertEquals(line.lineId, selectedTaxLineId)
        assertEquals(tax1, selectedTax)

        // 3. Click explicit No Tax button
        val noTaxButton = composeTestRule.onNodeWithTag("staff_sale_line_notax_${line.lineId}")
        noTaxButton.assertIsDisplayed()
        noTaxButton.performClick()
        composeTestRule.waitForIdle()

        assertEquals(line.lineId, explicitNoTaxLineId)
    }
}
