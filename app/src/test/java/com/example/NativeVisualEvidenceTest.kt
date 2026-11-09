package com.example

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode

import androidx.compose.ui.test.assertIsDisplayed

import androidx.compose.foundation.layout.fillMaxSize

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.example.data.OfflineDemoTraderRepository
import com.example.model.AppLanguage
import androidx.compose.ui.unit.dp
import com.example.model.TraderScreen

import com.example.ui.screens.CashPaymentScreen
import com.example.ui.screens.CustomersScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.NewSaleScreen
import com.example.ui.screens.PeopleHubScreen
import com.example.ui.screens.SuppliersScreen
import com.example.ui.screens.PaySupplierDialog
import com.example.ui.screens.PhysicalGoodsReceiptDialog
import com.example.ui.screens.ReceivePurchaseBillDialog
import com.example.ui.components.SmallTraderBottomBar
import com.example.model.Supplier
import com.example.model.PurchaseBill
import com.example.model.PurchaseLineItem
import com.example.model.StockMovement
import com.example.model.SupplierPayment
import com.example.viewmodel.TraderUiState
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Genuine Native Compose-Rendered Visual Evidence Test Suite.
 *
 * Uses Robolectric Native Graphics (RNG) with Skia native drawing path
 * to produce genuine PNG screenshots directly from the Android View hierarchy and Compose render tree.
 *
 * Captures:
 * 1. Home Screen (~393dp AR Light)
 * 2. Populated Catalog / POS Screen (~393dp AR Light)
 * 3. Cash Payment Screen (~393dp AR Light)
 * 4. People / Customer Flow Screen (~393dp AR Light)
 * 5. Compact 360dp Font 1.5x Stress Test
 * 6. English Dark Theme Screen (~393dp EN Dark)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeVisualEvidenceTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val outputDir: File
        get() {
            val configured = System.getenv("TRADER_VISUAL_OUTPUT_DIR") ?: "round2/validation/native-renders-v03"
            val direct = File(configured)
            return if (direct.isAbsolute) direct
            else if (File("..", "round2").exists()) File("..", configured)
            else direct
        }

    private fun captureViewToPng(fileName: String, expectedWidth: Int? = null, expectedHeight: Int? = null): File {
        composeTestRule.waitForIdle()
        try {
            composeTestRule.mainClock.advanceTimeBy(300)
            composeTestRule.waitForIdle()
        } catch (_: Throwable) {}
        val activity = composeTestRule.activity
        val view = activity.window.decorView
        val width = if (view.width > 0) view.width else (expectedWidth ?: 1179)
        val height = if (view.height > 0) view.height else (expectedHeight ?: 2556)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)

        outputDir.mkdirs()
        val outFile = File(outputDir, fileName)
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }

        println("EVIDENCE_RENDER: $fileName -> ${bitmap.width}x${bitmap.height} (${outFile.length()} bytes)")
        assertTrue("Rendered PNG must be non-empty", outFile.length() > 1000)
        return outFile
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
    fun capture_01_homeScreen_ar_light_393dp() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                androidx.compose.material3.Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                HomeScreen(
                    state = state,
                    onStartNewSale = {},
                    onOpenScanner = {},
                    onOpenPrinter = {},
                    onOpenCustomItem = {},
                    onReviewBasket = {},
                    onOpenInvoice = {},
                    onViewAllInvoices = {},
                    onRestockProduct = {},
                    onAddProductToSale = {},
                    onNavigateToCatalog = {},
                    onNavigateToCustomers = {},
                    onNavigateToSuppliers = {},
                    onNavigateToExpenses = {},
                    onNavigateToReports = {},
                    onNavigateToSettings = {}
                )
            
                }
}
        }

        captureViewToPng("01_home_screen_ar_light_393dp.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
    fun capture_02_populatedCatalog_ar_light_393dp() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // Populate basket with real items to reveal tactile stepper and sticky review dock
        val p1 = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        val p2 = viewModel.uiState.value.products.first { it.id == "p_zaatar_jenin" }
        viewModel.addProductToBasket(p1, 2)
        viewModel.addProductToBasket(p2, 1)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                androidx.compose.material3.Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                NewSaleScreen(
                    state = state,
                    onSearchQueryChange = {},
                    onSelectCategory = {},
                    onAddProduct = {},
                    onDecrementProduct = {},
                    onRemoveProduct = {},
                    onClearBasket = {},
                    onOpenScanner = {},
                    onOpenCustomItem = {},
                    onProceedToInvoiceReview = {}
                )
            
                }
}
        }

        captureViewToPng("02_populated_catalog_ar_light_393dp.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
    fun capture_03_cashPayment_ar_light_393dp() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        val p1 = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        viewModel.addProductToBasket(p1, 2) // 76.00 ILS

        // Select debtor customer to exercise financial safety indicators
        val customer = viewModel.uiState.value.customers.first { it.balanceIls > 0 }
        viewModel.assignCustomerToSale(customer)
        viewModel.updateCashInputText("100.00") // 24.00 ILS change

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                androidx.compose.material3.Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                CashPaymentScreen(
                    state = state,
                    onSetExactAmount = viewModel::setCashExactAmount,
                    onSelectDenomination = viewModel::setCashQuickDenomination,
                    onUpdateCashText = viewModel::updateCashInputText,
                    onKeypadPress = viewModel::onCashKeypadPress,
                    onAssignCustomer = viewModel::assignCustomerToSale,
                    onCompleteSale = { viewModel.completeCashSale(asCreditDebt = false) },
                    onCompleteCreditSale = { viewModel.completeCashSale(asCreditDebt = true) }
                )
            
                }
}
        }

        captureViewToPng("03_cash_payment_ar_light_393dp.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
    fun capture_04_peopleCustomerFlow_ar_light_393dp() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                androidx.compose.material3.Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                PeopleHubScreen(
                    state = state,
                    customers = {
                        CustomersScreen(
                            state = state,
                            onCustomerClick = {},
                            onOpenAddCustomerDialog = {},
                            onStartSaleForCustomer = {},
                            onQuickCollectPayment = {},
                            onDismissFeedback = {}
                        )
                    },
                    suppliers = {
                        SuppliersScreen(
                            state = state,
                            onSupplierClick = {},
                            onOpenReceivePurchaseDialog = {}
                        )
                    }
                )
            
                }
}
        }

        captureViewToPng("04_people_customer_flow_ar_light_393dp.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w360dp-h780dp-xhdpi")
    fun capture_05_compact360_fontScale1_5_stress() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        val p1 = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        viewModel.addProductToBasket(p1, 1)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            val currentDensity = LocalDensity.current
            // Inject 1.5x font scale stress testing typography layout guards
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 1.5f
                )
            ) {
                SmallTraderTheme(darkTheme = false, isRtl = true) {
                androidx.compose.material3.Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    NewSaleScreen(
                        state = state,
                        onSearchQueryChange = {},
                        onSelectCategory = {},
                        onAddProduct = {},
                        onDecrementProduct = {},
                        onRemoveProduct = {},
                        onClearBasket = {},
                        onOpenScanner = {},
                        onOpenCustomItem = {},
                        onProceedToInvoiceReview = {}
                    )
                
                }
}
            }
        }

        captureViewToPng("05_compact_catalog_360dp_font1_5_stress.png")

        // 1. CTA bounds assertion: CTA button must be strictly within viewport [0, 780dp]
        val ctaBounds = composeTestRule.onNodeWithTag("review_invoice_button").getBoundsInRoot()
        assertTrue("1.5x font: CTA top must be within viewport", ctaBounds.top >= 0.dp)
        assertTrue("1.5x font: CTA bottom must be within viewport (<= 780dp)", ctaBounds.bottom <= 780.dp)

        // 2. Search bounds assertion: search bar must expand cleanly and remain within viewport
        val searchBounds = composeTestRule.onNodeWithTag("product_search_input").getBoundsInRoot()
        assertTrue("1.5x font: search top must be within viewport", searchBounds.top >= 0.dp)
        assertTrue("1.5x font: search height must be at least 36dp", (searchBounds.bottom - searchBounds.top) >= 36.dp)

        // 3. Currency atomic non-overflow assertion via SemanticsActions.GetTextLayoutResult
        val subtotalResults = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithTag("basket_subtotal_text", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(subtotalResults) }
        assertTrue("1.5x font: Subtotal text layout must be available", subtotalResults.isNotEmpty())
        val subtotalLayout = subtotalResults.first()
        assertTrue("1.5x font: subtotal must not overflow", !subtotalLayout.hasVisualOverflow)
        assertTrue("1.5x font: subtotal must remain single-line (atomic nowrap currency)", subtotalLayout.lineCount == 1)

    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w360dp-h780dp-xhdpi")
    fun capture_07_compact360_fontScale2_stress() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        val p1 = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        viewModel.addProductToBasket(p1, 1)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            val currentDensity = LocalDensity.current
            // Inject 1.5x font scale stress testing typography layout guards
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2.0f
                )
            ) {
                SmallTraderTheme(darkTheme = false, isRtl = true) {
                androidx.compose.material3.Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    NewSaleScreen(
                        state = state,
                        onSearchQueryChange = {},
                        onSelectCategory = {},
                        onAddProduct = {},
                        onDecrementProduct = {},
                        onRemoveProduct = {},
                        onClearBasket = {},
                        onOpenScanner = {},
                        onOpenCustomItem = {},
                        onProceedToInvoiceReview = {}
                    )
                
                }
}
            }
        }

        captureViewToPng("07_compact_catalog_360dp_font2_stress.png")

        // 1. CTA bounds assertion: CTA button must be strictly within viewport [0, 780dp]
        val ctaBounds = composeTestRule.onNodeWithTag("review_invoice_button").getBoundsInRoot()
        assertTrue("2.0x font: CTA top must be within viewport", ctaBounds.top >= 0.dp)
        assertTrue("2.0x font: CTA bottom must be within viewport (<= 780dp)", ctaBounds.bottom <= 780.dp)

        // 2. Search bounds assertion: search bar must expand cleanly and remain within viewport
        val searchBounds = composeTestRule.onNodeWithTag("product_search_input").getBoundsInRoot()
        assertTrue("2.0x font: search top must be within viewport", searchBounds.top >= 0.dp)
        assertTrue("2.0x font: search height must be at least 36dp", (searchBounds.bottom - searchBounds.top) >= 36.dp)

        // 3. Currency atomic non-overflow assertion via SemanticsActions.GetTextLayoutResult
        val subtotalResults = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithTag("basket_subtotal_text", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(subtotalResults) }
        assertTrue("2.0x font: Subtotal text layout must be available", subtotalResults.isNotEmpty())
        val subtotalLayout = subtotalResults.first()
        assertTrue("2.0x font: subtotal must not overflow", !subtotalLayout.hasVisualOverflow)
        assertTrue("2.0x font: subtotal must remain single-line (atomic nowrap currency)", subtotalLayout.lineCount == 1)

    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w393dp-h852dp-xxhdpi")
    fun capture_06_en_dark_393dp() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        viewModel.setLanguage(AppLanguage.EN)

        val p1 = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        viewModel.addProductToBasket(p1, 1)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = true, isRtl = false) {
                androidx.compose.material3.Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                HomeScreen(
                    state = state.copy(isDarkTheme = true, language = AppLanguage.EN),
                    onStartNewSale = {},
                    onOpenScanner = {},
                    onOpenPrinter = {},
                    onOpenCustomItem = {},
                    onReviewBasket = {},
                    onOpenInvoice = {},
                    onViewAllInvoices = {},
                    onRestockProduct = {},
                    onAddProductToSale = {},
                    onNavigateToCatalog = {},
                    onNavigateToCustomers = {},
                    onNavigateToSuppliers = {},
                    onNavigateToExpenses = {},
                    onNavigateToReports = {},
                    onNavigateToSettings = {}
                )
            
                }
}
        }

        captureViewToPng("06_home_screen_en_dark_393dp.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
    fun capture_08_appShell_ar_light_393dp() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        composeTestRule.setContent {
            SmallTraderApp(traderViewModel = viewModel)
        }

        val homeNodes = composeTestRule.onAllNodesWithTag("home_screen_list").fetchSemanticsNodes()
        val topBarNodes = composeTestRule.onAllNodesWithTag("small_trader_top_bar").fetchSemanticsNodes()

        composeTestRule.onNodeWithTag("hero_new_sale_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_home_tab").assertIsDisplayed()
        captureViewToPng("08_app_shell_ar_light_393dp.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w360dp-h780dp-xhdpi")
    fun capture_09_fullApp_360dp_font2_stress() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        val p1 = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        viewModel.addProductToBasket(p1, 1)
        viewModel.navigateTo(TraderScreen.NEW_SALE)

        composeTestRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2.0f
                )
            ) {
                SmallTraderApp(traderViewModel = viewModel)
            }
        }

        captureViewToPng("09_full_app_360dp_font2_stress.png")

        val ctaBounds = composeTestRule.onNodeWithTag("review_invoice_button").getBoundsInRoot()
        assertTrue("Full App 2.0x font: CTA top must be within viewport", ctaBounds.top >= 0.dp)
        assertTrue("Full App 2.0x font: CTA bottom must be within viewport (<= 780dp)", ctaBounds.bottom <= 780.dp)
    }


    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
    fun capture_10_invoicesHistory_ar_light_393dp() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        viewModel.navigateTo(TraderScreen.INVOICES_HISTORY)

        composeTestRule.setContent {
            SmallTraderApp(traderViewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("invoices_history_screen").assertIsDisplayed()
        captureViewToPng("10_invoices_history_ar_light_393dp.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
    fun capture_11_inventoryCatalog_ar_light_393dp() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        viewModel.navigateTo(TraderScreen.PRODUCTS_CATALOG)

        composeTestRule.setContent {
            SmallTraderApp(traderViewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("products_catalog_screen").assertIsDisplayed()
        captureViewToPng("11_inventory_catalog_ar_light_393dp.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
    fun capture_12_productDetail_ar_light_393dp() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val product = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        viewModel.selectProductForDetail(product)

        composeTestRule.setContent {
            SmallTraderApp(traderViewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("product_detail_screen").assertIsDisplayed()
        captureViewToPng("12_product_detail_ar_light_393dp.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
    fun capture_13_peopleHubPager_ar_light_393dp() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        viewModel.navigateTo(TraderScreen.PEOPLE)

        composeTestRule.setContent {
            SmallTraderApp(traderViewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("people_hub_segmented_control").assertIsDisplayed()
        captureViewToPng("13_people_hub_pager_ar_light_393dp.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
    fun capture_14_settings_ar_light_393dp() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        viewModel.navigateTo(TraderScreen.BUSINESS_SETTINGS)

        composeTestRule.setContent {
            SmallTraderApp(traderViewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("business_settings_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_phone_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_tax_id_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_theme_chip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_language_chip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_open_printer_button").assertIsDisplayed()
        captureViewToPng("14_settings_ar_light_393dp.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
    fun capture_15_profile_ar_light_393dp() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        viewModel.navigateTo(TraderScreen.USER_PROFILE)

        composeTestRule.setContent {
            SmallTraderApp(traderViewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("user_profile_screen").assertIsDisplayed()
        captureViewToPng("15_profile_ar_light_393dp.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w360dp-h780dp-xhdpi")
    fun capture_16_fullApp_home_360dp_font2_stress() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        composeTestRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2.0f
                )
            ) {
                SmallTraderApp(traderViewModel = viewModel)
            }
        }

        captureViewToPng("16_full_app_home_360dp_font2_stress.png")
        composeTestRule.onNodeWithTag("hero_new_sale_button").assertIsDisplayed()

        // Explicit text bounds & overflow assertions for adaptive 2.0x Home KPI header & full date:
        composeTestRule.onNodeWithTag("home_reports_button").assertExists()
        composeTestRule.onNodeWithTag("home_screen_list").performScrollToNode(hasTestTag("home_reports_button"))
        composeTestRule.onNodeWithTag("home_reports_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("home_date_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("home_date_text", useUnmergedTree = true).assertIsDisplayed()

        val dateBounds = composeTestRule.onNodeWithTag("home_date_text", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("Date text right bound must be within 360dp screen width (was ${dateBounds.right})", dateBounds.right <= 360.dp)
        assertTrue("Date text left bound must be non-negative (was ${dateBounds.left})", dateBounds.left >= 0.dp)

        // Verify date layout has no visual overflow or clipping under 2.0x font scaling:
        val nodeConfig = composeTestRule.onNodeWithTag("home_date_text", useUnmergedTree = true).fetchSemanticsNode().config
        if (nodeConfig.contains(SemanticsActions.GetTextLayoutResult)) {
            val dateLayoutResults = mutableListOf<TextLayoutResult>()
            nodeConfig[SemanticsActions.GetTextLayoutResult].action?.invoke(dateLayoutResults)
            if (dateLayoutResults.isNotEmpty()) {
                val layout = dateLayoutResults[0]
                org.junit.Assert.assertFalse(
                    "Home date text must not have visual overflow under 2.0x font scaling",
                    layout.hasVisualOverflow
                )
            }
        }

        // Verify Reports action and KPI header title on narrow 360dp screen:
        composeTestRule.onNodeWithTag("home_kpi_title_text", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_kpi_reports_action", useUnmergedTree = true).assertIsDisplayed()

        val kpiTitleBounds = composeTestRule.onNodeWithTag("home_kpi_title_text", useUnmergedTree = true).getBoundsInRoot()
        val reportsActionBounds = composeTestRule.onNodeWithTag("home_kpi_reports_action", useUnmergedTree = true).getBoundsInRoot()

        assertTrue("KPI title right bound must be within 360dp width (was ${kpiTitleBounds.right})", kpiTitleBounds.right <= 360.dp)
        assertTrue("Reports action right bound must be within 360dp width (was ${reportsActionBounds.right})", reportsActionBounds.right <= 360.dp)
        assertTrue("KPI title left bound must be non-negative (was ${kpiTitleBounds.left})", kpiTitleBounds.left >= 0.dp)
        assertTrue("Reports action left bound must be non-negative (was ${reportsActionBounds.left})", reportsActionBounds.left >= 0.dp)
    }

    // =========================================================================
    // 17. Navigation Bar Multi-Scale & Density Verification (17, 18, 19, Matrix)
    // =========================================================================

    private fun verifyNavigationBarBottomScaffold(
        scenario: String,
        fontScale: Float,
        language: AppLanguage,
        isDark: Boolean,
        screenWidthDp: Int,
        screenHeightDp: Int,
        saveFileName: String? = null
    ) {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        viewModel.setLanguage(language)
        if (isDark) {
            viewModel.toggleTheme()
        }

        val navTags = listOf("nav_home_tab", "nav_invoices_tab", "nav_quick_add", "nav_inventory_tab", "nav_people_tab")
        val labelTags = listOf("nav_home_label", "nav_invoices_label", "nav_inventory_label", "nav_people_label")

        composeTestRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = fontScale
                )
            ) {
                SmallTraderTheme(darkTheme = isDark, isRtl = language.isRtl) {
                    androidx.compose.material3.Scaffold(
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                        bottomBar = {
                            SmallTraderBottomBar(
                                state = viewModel.uiState.value,
                                onSelectScreen = {},
                                onOpenQuickActions = {}
                            )
                        }
                    ) { _ ->
                        androidx.compose.foundation.layout.Box(
                            modifier = androidx.compose.ui.Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        // 1. Minimum touch bounds >= 48dp and positioned cleanly within bottom scaffold shell
        val boundsList = mutableListOf<androidx.compose.ui.unit.DpRect>()
        for (tag in navTags) {
            val node = composeTestRule.onNodeWithTag(tag)
            node.assertExists()
            node.assertIsDisplayed()
            val bounds = node.getBoundsInRoot()
            boundsList.add(bounds)
            val w = bounds.right - bounds.left
            val h = bounds.bottom - bounds.top
            assertTrue("[$scenario $tag scale=$fontScale] height ($h) must be >= 48.dp", h >= 48.dp)
            assertTrue("[$scenario $tag scale=$fontScale] width ($w) must be >= 48.dp", w >= 48.dp)
            assertTrue("[$scenario $tag scale=$fontScale] bottom must be within screen height ($screenHeightDp dp)", bounds.bottom <= screenHeightDp.dp + 2.dp)
            assertTrue("[$scenario $tag scale=$fontScale] top must not clip top of screen (at bottom scaffold)", bounds.top >= 0.dp)
        }

        // 2. No overlaps between adjacent navigation items
        val sortedByLeft = boundsList.sortedBy { it.left.value }
        for (i in 0 until sortedByLeft.size - 1) {
            val current = sortedByLeft[i]
            val next = sortedByLeft[i + 1]
            assertTrue(
                "[$scenario scale=$fontScale] Navigation items must not overlap: right (${current.right}) <= next left (${next.left})",
                current.right <= next.left + 1.dp
            )
        }

        // 3. Label TextLayoutResult: No visual overflow AND no mid-word break (strictly 1 line)
        for (labelTag in labelTags) {
            val results = mutableListOf<TextLayoutResult>()
            composeTestRule.onNodeWithTag(labelTag, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue("[$scenario $labelTag scale=$fontScale] TextLayoutResult must be available", results.isNotEmpty())
            val layout = results.first()
            println("DEBUG_LAYOUT: tag=$labelTag hasOverflow=${layout.hasVisualOverflow} lineCount=${layout.lineCount} size=${layout.size} multiH=${layout.multiParagraph.height} multiW=${layout.multiParagraph.width}")
            assertFalse(
                "[$scenario $labelTag scale=$fontScale] Label must NOT have visual overflow (size=${layout.size}, multiW=${layout.multiParagraph.width}, multiH=${layout.multiParagraph.height})",
                layout.hasVisualOverflow
            )
            assertEquals(
                "[$scenario $labelTag scale=$fontScale] Label must remain on a single line (no midword break)",
                1,
                layout.lineCount
            )
        }

        if (saveFileName != null) {
            captureViewToPng(saveFileName)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w360dp-h780dp-xhdpi")
    fun capture_17_nav_360dp_font2_ar_stress() {
        verifyNavigationBarBottomScaffold(
            scenario = "17_nav_360dp_font2_ar_stress",
            fontScale = 2.0f,
            language = AppLanguage.AR,
            isDark = false,
            screenWidthDp = 360,
            screenHeightDp = 780,
            saveFileName = "17_nav_360dp_font2_ar_stress.png"
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w393dp-h852dp-xxhdpi")
    fun capture_18_nav_393dp_font1_5_en() {
        verifyNavigationBarBottomScaffold(
            scenario = "18_nav_393dp_font1_5_en",
            fontScale = 1.5f,
            language = AppLanguage.EN,
            isDark = false,
            screenWidthDp = 393,
            screenHeightDp = 852,
            saveFileName = "18_nav_393dp_font1_5_en.png"
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
    fun capture_19_nav_393dp_font1_ar() {
        verifyNavigationBarBottomScaffold(
            scenario = "19_nav_393dp_font1_ar",
            fontScale = 1.0f,
            language = AppLanguage.AR,
            isDark = false,
            screenWidthDp = 393,
            screenHeightDp = 852,
            saveFileName = "19_nav_393dp_font1_ar.png"
        )
    }

    private fun runDynamicMatrixTest(
        scenarioPrefix: String,
        screenWidthDp: Int,
        screenHeightDp: Int
    ) {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        val navTags = listOf("nav_home_tab", "nav_invoices_tab", "nav_quick_add", "nav_inventory_tab", "nav_people_tab")
        val labelTags = listOf("nav_home_label", "nav_invoices_label", "nav_inventory_label", "nav_people_label")

        val currentFontScale = androidx.compose.runtime.mutableStateOf(1.0f)
        val currentLang = androidx.compose.runtime.mutableStateOf(AppLanguage.AR)
        val currentIsDark = androidx.compose.runtime.mutableStateOf(false)

        composeTestRule.setContent {
            val currentDensity = LocalDensity.current
            val state by viewModel.uiState.collectAsState()
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = currentFontScale.value
                )
            ) {
                SmallTraderTheme(darkTheme = currentIsDark.value, isRtl = currentLang.value.isRtl) {
                    androidx.compose.material3.Scaffold(
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                        bottomBar = {
                            SmallTraderBottomBar(
                                state = state,
                                onSelectScreen = {},
                                onOpenQuickActions = {}
                            )
                        }
                    ) { _ ->
                        androidx.compose.foundation.layout.Box(
                            modifier = androidx.compose.ui.Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        for (fontScale in listOf(1.0f, 1.5f, 2.0f)) {
            for (lang in listOf(AppLanguage.AR, AppLanguage.EN)) {
                for (isDark in listOf(false, true)) {
                    val scenario = "${scenarioPrefix}_${lang.name.lowercase()}_scale${fontScale}_dark$isDark"
                    composeTestRule.runOnIdle {
                        currentFontScale.value = fontScale
                        currentLang.value = lang
                        currentIsDark.value = isDark
                        viewModel.setLanguage(lang)
                        if (viewModel.uiState.value.isDarkTheme != isDark) {
                            viewModel.toggleTheme()
                        }
                    }
                    composeTestRule.waitForIdle()

                    // 1. Minimum touch bounds >= 48dp and within bottom shell viewport
                    val boundsList = mutableListOf<androidx.compose.ui.unit.DpRect>()
                    for (tag in navTags) {
                        val node = composeTestRule.onNodeWithTag(tag)
                        node.assertExists()
                        node.assertIsDisplayed()
                        val bounds = node.getBoundsInRoot()
                        boundsList.add(bounds)
                        val w = bounds.right - bounds.left
                        val h = bounds.bottom - bounds.top
                        assertTrue("[$scenario $tag scale=$fontScale] height ($h) must be >= 48.dp", h >= 48.dp)
                        assertTrue("[$scenario $tag scale=$fontScale] width ($w) must be >= 48.dp", w >= 48.dp)
                        assertTrue("[$scenario $tag scale=$fontScale] bottom must be within screen height ($screenHeightDp dp)", bounds.bottom <= screenHeightDp.dp + 2.dp)
                        assertTrue("[$scenario $tag scale=$fontScale] top must not clip top of screen", bounds.top >= 0.dp)
                    }

                    // 2. No overlaps between adjacent navigation items
                    val sortedByLeft = boundsList.sortedBy { it.left.value }
                    for (i in 0 until sortedByLeft.size - 1) {
                        val current = sortedByLeft[i]
                        val next = sortedByLeft[i + 1]
                        assertTrue(
                            "[$scenario scale=$fontScale] Navigation items must not overlap: right (${current.right}) <= next left (${next.left})",
                            current.right <= next.left + 1.dp
                        )
                    }

                    // 3. Label TextLayoutResult: No visual overflow AND no mid-word break (strictly 1 line)
                    for (labelTag in labelTags) {
                        val results = mutableListOf<TextLayoutResult>()
                        composeTestRule.onNodeWithTag(labelTag, useUnmergedTree = true)
                            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                        assertTrue("[$scenario $labelTag scale=$fontScale] TextLayoutResult must be available", results.isNotEmpty())
                        val layout = results.first()
                        assertFalse(
                            "[$scenario $labelTag scale=$fontScale] Label must NOT have visual overflow (size=${layout.size}, multiW=${layout.multiParagraph.width}, multiH=${layout.multiParagraph.height})",
                            layout.hasVisualOverflow
                        )
                        assertEquals(
                            "[$scenario $labelTag scale=$fontScale] Label must remain on a single line (no midword break)",
                            1,
                            layout.lineCount
                        )
                    }
                }
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rEG-w360dp-h780dp-xhdpi")
    fun matrix_20_nav_360dp_full_matrix_stress() {
        runDynamicMatrixTest("matrix_360dp", 360, 780)
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w393dp-h852dp-xxhdpi")
    fun matrix_21_nav_393dp_full_matrix_stress() {
        runDynamicMatrixTest("matrix_393dp", 393, 852)
    }
}
