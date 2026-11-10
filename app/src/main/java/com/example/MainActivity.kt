package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.example.ui.navigation.*
import com.example.ui.shell.QuickActionSheet
import com.example.ui.screens.PeopleHubScreen
import com.example.ui.screens.PurchasesScreen
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.TraderScreen
import com.example.ui.components.BluetoothPrinterModalSheet
import com.example.ui.components.CustomQuickItemDialog
import com.example.ui.components.SimulatedBarcodeScannerSheet
import com.example.ui.components.SmallTraderBottomBar
import com.example.ui.components.SmallTraderTopBar
import com.example.ui.screens.AddCustomerDialog
import com.example.ui.screens.AddExpenseDialog
import com.example.ui.screens.BusinessSettingsScreen
import com.example.ui.screens.CashPaymentScreen
import com.example.ui.screens.CustomerDetailScreen
import com.example.ui.screens.CustomerPaymentCollectionDialog
import com.example.ui.screens.CustomersScreen
import com.example.ui.screens.ExpensesScreen
import com.example.ui.screens.FinancialReportsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.InvoiceReviewScreen
import com.example.ui.screens.InvoicesHistoryScreen
import com.example.ui.screens.NewSaleScreen
import com.example.ui.screens.ProductDetailScreen
import com.example.ui.screens.ProductsCatalogScreen
import com.example.ui.screens.ReceiptScreen
import com.example.ui.screens.ReceivePurchaseBillDialog
import com.example.ui.screens.SupplierDetailScreen
import com.example.ui.screens.SuppliersScreen
import com.example.ui.screens.UserProfileScreen
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmallTraderApp()
        }
    }
}

@Composable
fun SmallTraderApp(
    traderViewModel: TraderViewModel = viewModel()
) {
    val state by traderViewModel.uiState.collectAsStateWithLifecycle()

    val screenStates = rememberSaveableStateHolder()
    val saleScreenStates = key(state.saleSessionId) { rememberSaveableStateHolder() }
    val canNavigateBack = state.isQuickActionOpen || state.pendingAction != null || state.currentScreen != TraderScreen.HOME ||
        state.isScannerSheetOpen ||
        state.isPrinterSheetOpen ||
        state.isCustomItemDialogOpen ||
        state.isPaymentCollectionDialogOpen ||
        state.isAddCustomerDialogOpen ||
        state.isAddExpenseDialogOpen ||
        state.isReceivePurchaseDialogOpen

    BackHandler(enabled = canNavigateBack) {
        traderViewModel.navigateBack()
    }

    SmallTraderTheme(
        darkTheme = state.isDarkTheme,
        isRtl = state.language.isRtl
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                SmallTraderTopBar(
                    state = state,
                    onNavigateBack = { traderViewModel.navigateBack() },
                    onNavigateTo = { target -> traderViewModel.navigateTo(target) },
                    onToggleLanguage = { traderViewModel.toggleLanguage() },
                    onToggleTheme = { traderViewModel.toggleTheme() },
                    onOpenScanner = { traderViewModel.setScannerSheetOpen(true) },
                    onOpenPrinter = { traderViewModel.setPrinterSheetOpen(true) }
                )
            },
            bottomBar = {
                if (state.isRootDestination) {
                    SmallTraderBottomBar(
                        state = state,
                        onSelectScreen = { target -> traderViewModel.navigateTo(target) },
                        onOpenQuickActions = { traderViewModel.setQuickActionOpen(true) }
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
            ) {
                Crossfade(
                    targetState = state.currentScreen,
                    animationSpec = tween(durationMillis = 180),
                    label = "screen_transition"
                ) { screen ->
                    key(screen, if (screen in com.example.viewmodel.TraderUiState.CHECKOUT_FLOW_DESTINATIONS) state.saleSessionId else 0L) {
                    (if (screen in com.example.viewmodel.TraderUiState.CHECKOUT_FLOW_DESTINATIONS) saleScreenStates else screenStates).SaveableStateProvider(screen.name) {
                    if (TraderNavigationPolicy.allows(state.currentSession, screen)) when (screen) {
                        TraderScreen.PEOPLE -> PeopleHubScreen(state,
                            customers = { CustomersScreen(state, traderViewModel::selectCustomerForDetail,
                                { traderViewModel.openAddCustomerDialog(true) },
                                { customer -> traderViewModel.assignCustomerToSale(customer); traderViewModel.navigateTo(TraderScreen.NEW_SALE) },
                                traderViewModel::openPaymentCollectionForCustomer, traderViewModel::dismissCustomerFeedback) },
                            suppliers = { SuppliersScreen(state, traderViewModel::selectSupplierForDetail,
                                { traderViewModel.openReceivePurchaseDialog(true) }, traderViewModel::dismissSupplierFeedback, traderViewModel::saveSupplier) })
                        TraderScreen.PURCHASES -> PurchasesScreen(
                            state = state,
                            onNewPurchase = { traderViewModel.openReceivePurchaseDialog(true) },
                            onSupplier = traderViewModel::selectSupplierForDetail,
                            onConfirmPhysicalGoodsReceipt = { billId, productId, qty, note ->
                                traderViewModel.confirmPhysicalGoodsReceipt(billId, productId, qty, note)
                            },
                            onConfirmPhysicalGoodsReceiptResult = { billId, productId, qty, note, operationId, lineId ->
                                traderViewModel.confirmPhysicalGoodsReceiptResult(billId, productId, qty, note, operationId, lineId)
                            }
                        )
                        TraderScreen.HOME -> {
                            HomeScreen(
                                state = state,
                                onStartNewSale = { traderViewModel.navigateTo(TraderScreen.NEW_SALE) },
                                onOpenScanner = {
                                    traderViewModel.navigateTo(TraderScreen.NEW_SALE)
                                    traderViewModel.setScannerSheetOpen(true)
                                },
                                onOpenPrinter = { traderViewModel.setPrinterSheetOpen(true) },
                                onOpenCustomItem = {
                                    traderViewModel.navigateTo(TraderScreen.NEW_SALE)
                                    traderViewModel.openCustomItemDialog(true)
                                },
                                onReviewBasket = { traderViewModel.navigateTo(TraderScreen.NEW_SALE) },
                                onOpenInvoice = { invoice -> traderViewModel.openInvoiceReceipt(invoice) },
                                onViewAllInvoices = { traderViewModel.navigateTo(TraderScreen.INVOICES_HISTORY) },
                                onRestockProduct = { traderViewModel.navigateTo(TraderScreen.PRODUCTS_CATALOG) },
                                onAddProductToSale = { product -> traderViewModel.addProductToBasket(product, 1) },
                                onNavigateToCatalog = traderViewModel::openLowStockInventory,
                                onNavigateToCustomers = { traderViewModel.navigateTo(TraderScreen.CUSTOMERS) },
                                onNavigateToSuppliers = { traderViewModel.navigateTo(TraderScreen.SUPPLIERS) },
                                onNavigateToExpenses = { traderViewModel.navigateTo(TraderScreen.EXPENSES) },
                                onNavigateToReports = { traderViewModel.navigateTo(TraderScreen.FINANCIAL_REPORTS) },
                                onNavigateToSettings = { traderViewModel.navigateTo(TraderScreen.BUSINESS_SETTINGS) },
                                onOpenPurchase = { traderViewModel.openMerchantAction(MerchantAction.PURCHASE) }
                            )
                        }

                        TraderScreen.NEW_SALE -> {
                            NewSaleScreen(
                                state = state,
                                onSearchQueryChange = traderViewModel::updateSearchQuery,
                                onSelectCategory = traderViewModel::selectCategory,
                                onAddProduct = { product -> traderViewModel.addProductToBasket(product, 1) },
                                onDecrementProduct = traderViewModel::decrementProductInBasket,
                                onRemoveProduct = traderViewModel::removeProductFromBasket,
                                onClearBasket = traderViewModel::clearBasket,
                                onOpenScanner = { traderViewModel.setScannerSheetOpen(true) },
                                onOpenCustomItem = { traderViewModel.openCustomItemDialog(true) },
                                onProceedToInvoiceReview = {
                                    traderViewModel.navigateTo(TraderScreen.INVOICE_REVIEW)
                                },
                                onOpenSaleOptions = { traderViewModel.navigateTo(TraderScreen.INVOICE_REVIEW) }
                            )
                        }

                        TraderScreen.INVOICE_REVIEW -> {
                            InvoiceReviewScreen(
                                state = state,
                                onAddMoreItems = { traderViewModel.navigateTo(TraderScreen.NEW_SALE) },
                                onIncrementProduct = { product -> traderViewModel.addProductToBasket(product, 1) },
                                onDecrementProduct = traderViewModel::decrementProductInBasket,
                                onRemoveProduct = traderViewModel::removeProductFromBasket,
                                onSelectDiscountPreset = traderViewModel::selectDiscountPreset,
                                onUpdateCustomDiscount = traderViewModel::updateCustomDiscountText,
                                onUpdateCustomerNote = traderViewModel::updateCustomerNote,
                                onAssignCustomer = traderViewModel::assignCustomerToSale,
                                onProceedToCashPayment = {
                                    traderViewModel.navigateTo(TraderScreen.CASH_PAYMENT)
                                },
                                onSaveDraft = { traderViewModel.saveCurrentBasketAsDraft() },
                                onResumeDraft = traderViewModel::resumeSaleDraft,
                                onDeleteDraft = traderViewModel::deleteSaleDraft,
                                onFormatQuotation = traderViewModel::formatQuotationShareText
                            )
                        }

                        TraderScreen.CASH_PAYMENT -> {
                            CashPaymentScreen(
                                state = state,
                                onSetExactAmount = traderViewModel::setCashExactAmount,
                                onSelectDenomination = traderViewModel::setCashQuickDenomination,
                                onAddCashIncrement = traderViewModel::addCashIncrement,
                                onKeypadPress = traderViewModel::onCashKeypadPress,
                                onUpdateCashText = traderViewModel::updateCashInputText,
                                onAssignCustomer = traderViewModel::assignCustomerToSale,
                                onDismissCheckoutError = traderViewModel::dismissCheckoutError,
                                onEditInvoice = { traderViewModel.navigateTo(TraderScreen.INVOICE_REVIEW) },
                                onCompleteSale = { traderViewModel.completeCashSale(asCreditDebt = false) },
                                onCompleteCreditSale = { traderViewModel.completeCashSale(asCreditDebt = true) }
                            )
                        }

                        TraderScreen.RECEIPT -> {
                            ReceiptScreen(
                                state = state,
                                onPrintReceipt = traderViewModel::simulateThermalPrint,
                                onOpenPrinterSettings = { traderViewModel.setPrinterSheetOpen(true) },
                                onStartNewSale = {
                                    if (state.basket.isNotEmpty()) {
                                        traderViewModel.navigateTo(TraderScreen.NEW_SALE)
                                    } else {
                                        traderViewModel.startFreshSale()
                                    }
                                },
                                onBackToHome = {
                                    if (!state.isNewlyCompletedSale && state.screenHistory.isNotEmpty()) {
                                        traderViewModel.navigateBack()
                                    } else {
                                        traderViewModel.navigateTo(TraderScreen.HOME)
                                    }
                                },
                                onOpenCustomerAccount = { cust ->
                                    traderViewModel.selectCustomerForDetail(cust)
                                }
                            )
                        }

                        TraderScreen.INVOICES_HISTORY -> {
                            InvoicesHistoryScreen(
                                state = state,
                                onOpenInvoice = traderViewModel::openInvoiceReceipt,
                                onStartNewSale = {
                                    if (state.basket.isNotEmpty()) {
                                        traderViewModel.navigateTo(TraderScreen.NEW_SALE)
                                    } else {
                                        traderViewModel.startFreshSale()
                                    }
                                }
                            )
                        }

                        TraderScreen.PRODUCTS_CATALOG -> {
                            ProductsCatalogScreen(
                                state = state,
                                onSearchChange = traderViewModel::updateSearchQuery,
                                onCategorySelect = traderViewModel::selectCategory,
                                onProductClick = traderViewModel::selectProductForDetail,
                                onAddProductToSale = { prod ->
                                    traderViewModel.addProductToBasket(prod, 1)
                                    traderViewModel.navigateTo(TraderScreen.NEW_SALE)
                                },
                                onQuickRestock = { prod -> traderViewModel.restockProduct(prod.id, 10) },
                                launchCreate = state.pendingAction == MerchantAction.PRODUCT,
                                onLaunchConsumed = traderViewModel::consumeAction,
                                onOpenPurchases = { traderViewModel.navigateTo(TraderScreen.PURCHASES) },
                                launchLowStock = state.inventoryLowStockRequested,
                                onLowStockConsumed = traderViewModel::consumeLowStockRequest,
                                onSaveProduct = { pid, nameAr, cat, unitAr, barcode, price, cost, minThreshold, initialStock, warehouse, lot, expiry, isWeighted ->
                                    traderViewModel.saveProduct(
                                        productId = pid,
                                        nameAr = nameAr,
                                        category = cat,
                                        unitAr = unitAr,
                                        barcode = barcode,
                                        priceIls = price,
                                        costIls = cost,
                                        minStockThreshold = minThreshold,
                                        initialStock = initialStock,
                                        warehouseAr = warehouse,
                                        lotBatchCode = lot,
                                        expiryDate = expiry,
                                        isWeightedDisplayOnly = isWeighted
                                    )
                                }
                            )
                        }

                        TraderScreen.PRODUCT_DETAIL -> {
                            ProductDetailScreen(
                                state = state,
                                onBack = { traderViewModel.navigateBack() },
                                onUpdatePrice = { productId, newPrice ->
                                    traderViewModel.updateProductPrice(productId, newPrice)
                                },
                                onAdjustStock = { productId, delta, reason ->
                                    traderViewModel.adjustProductStock(productId, delta, reason)
                                },
                                onAddToBasket = { prod ->
                                    traderViewModel.addProductToBasket(prod, 1)
                                    traderViewModel.navigateTo(TraderScreen.NEW_SALE)
                                },
                                onDismissFeedback = traderViewModel::dismissInventoryFeedback,
                                onSaveProduct = { pid, nameAr, cat, unitAr, barcode, price, cost, minThreshold, initialStock, warehouse, lot, expiry, isWeighted ->
                                    traderViewModel.saveProduct(
                                        productId = pid,
                                        nameAr = nameAr,
                                        category = cat,
                                        unitAr = unitAr,
                                        barcode = barcode,
                                        priceIls = price,
                                        costIls = cost,
                                        minStockThreshold = minThreshold,
                                        initialStock = initialStock,
                                        warehouseAr = warehouse,
                                        lotBatchCode = lot,
                                        expiryDate = expiry,
                                        isWeightedDisplayOnly = isWeighted
                                    )
                                }
                            )
                        }

                        TraderScreen.CUSTOMERS -> {
                            CustomersScreen(
                                state = state,
                                onCustomerClick = traderViewModel::selectCustomerForDetail,
                                onOpenAddCustomerDialog = { traderViewModel.openAddCustomerDialog(true) },
                                onStartSaleForCustomer = { cust ->
                                    traderViewModel.assignCustomerToSale(cust)
                                    traderViewModel.navigateTo(TraderScreen.NEW_SALE)
                                },
                                onQuickCollectPayment = { cust ->
                                    traderViewModel.openPaymentCollectionForCustomer(cust)
                                },
                                onDismissFeedback = traderViewModel::dismissCustomerFeedback
                            )
                        }

                        TraderScreen.CUSTOMER_DETAIL -> {
                            CustomerDetailScreen(
                                state = state,
                                onBack = { traderViewModel.navigateBack() },
                                onOpenPaymentDialog = { traderViewModel.openPaymentCollectionDialog(true) },
                                onStartSaleForCustomer = { cust ->
                                    traderViewModel.assignCustomerToSale(cust)
                                    traderViewModel.navigateTo(TraderScreen.NEW_SALE)
                                },
                                onOpenInvoice = { inv -> traderViewModel.openInvoiceReceipt(inv) },
                                onDismissFeedback = traderViewModel::dismissCustomerFeedback,
                                onUpdateCustomer = { cid, name, phone, addr, limit ->
                                    traderViewModel.updateCustomer(cid, name, phone, addr, limit)
                                },
                                onFormatStatementText = { cid ->
                                    state.customers.firstOrNull { it.id == cid }?.let {
                                        traderViewModel.formatCustomerStatementText(it)
                                    } ?: ""
                                }
                            )
                        }

                        TraderScreen.SUPPLIERS -> {
                            SuppliersScreen(
                                state = state,
                                onSupplierClick = traderViewModel::selectSupplierForDetail,
                                onOpenReceivePurchaseDialog = { traderViewModel.openReceivePurchaseDialog(true) },
                                onDismissFeedback = traderViewModel::dismissSupplierFeedback,
                                onSaveSupplier = traderViewModel::saveSupplier,
                                launchCreate = state.pendingAction == MerchantAction.SUPPLIER,
                                onLaunchConsumed = traderViewModel::consumeAction
                            )
                        }

                        TraderScreen.SUPPLIER_DETAIL -> {
                            SupplierDetailScreen(
                                state = state,
                                onBack = { traderViewModel.navigateBack() },
                                onPaySupplier = { sup, amount -> traderViewModel.paySupplierAmount(sup.id, amount) },
                                onPaySupplierResult = { sup, amount, paymentId -> traderViewModel.paySupplierAmountResult(sup.id, amount, paymentId = paymentId) },
                                onOpenReceivePurchaseDialog = { traderViewModel.openReceivePurchaseDialog(true) },
                                onDismissFeedback = traderViewModel::dismissSupplierFeedback,
                                onSaveSupplier = traderViewModel::saveSupplier,
                                onConfirmGoodsReceipt = traderViewModel::confirmPhysicalGoodsReceipt,
                                onConfirmGoodsReceiptResult = { billId, productId, qty, note, opId, lineId -> traderViewModel.confirmPhysicalGoodsReceiptResult(billId, productId, qty, note, opId, lineId) },
                                launchPayment = state.pendingAction == MerchantAction.PAY,
                                onLaunchConsumed = traderViewModel::consumeAction
                            )
                        }

                        TraderScreen.EXPENSES -> {
                            ExpensesScreen(
                                state = state,
                                onOpenAddExpenseDialog = { traderViewModel.openAddExpenseDialog(true) },
                                onDismissFeedback = traderViewModel::dismissExpenseFeedback
                            )
                        }

                        TraderScreen.FINANCIAL_REPORTS -> {
                            FinancialReportsScreen(
                                state = state,
                                onUpdateOpeningCash = traderViewModel::updateStartingDrawerCash,
                                onUpdateCountedCashText = traderViewModel::updateCountedPhysicalCashText,
                                onToggleShiftClose = traderViewModel::toggleShiftClosePreview
                            )
                        }

                        TraderScreen.USER_PROFILE -> {
                            UserProfileScreen(
                                state = state,
                                onSwitchUser = traderViewModel::switchUserSession,
                                onToggleLanguage = traderViewModel::toggleLanguage,
                                onToggleTheme = traderViewModel::toggleTheme,
                                onOpenSettings = { traderViewModel.navigateTo(TraderScreen.BUSINESS_SETTINGS) }
                            )
                        }

                        TraderScreen.BUSINESS_SETTINGS -> {
                            BusinessSettingsScreen(
                                state = state,
                                onSwitchUser = traderViewModel::switchUserSession,
                                onToggleLanguage = traderViewModel::toggleLanguage,
                                onToggleTheme = traderViewModel::toggleTheme,
                                onSelectPaperWidth = traderViewModel::selectPaperWidth,
                                onOpenPrinterSheet = { traderViewModel.setPrinterSheetOpen(true) },
                                onOpenProfile = { traderViewModel.navigateTo(TraderScreen.USER_PROFILE) }
                            )
                        }
                    }
                }

                }
                }
                if (state.isQuickActionOpen) QuickActionSheet(state,
                    { traderViewModel.setQuickActionOpen(false) }, traderViewModel::openMerchantAction)
                if (state.pendingAction == MerchantAction.COLLECT || state.pendingAction == MerchantAction.PAY && state.currentScreen != TraderScreen.SUPPLIER_DETAIL) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = traderViewModel::consumeAction,
                        title = { androidx.compose.material3.Text(state.language.tr("اختار الحساب", "Choose account")) },
                        text = { androidx.compose.foundation.lazy.LazyColumn {
                            if (state.pendingAction == MerchantAction.COLLECT) {
                                if (state.customers.isEmpty()) {
                                    item {
                                        androidx.compose.material3.Text(
                                            text = state.language.tr("لا يوجد زبائن مسجلون", "No registered customers"),
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (MerchantAction.CUSTOMER.allowed(state.currentSession)) {
                                            androidx.compose.material3.TextButton(
                                                onClick = {
                                                    traderViewModel.consumeAction()
                                                    traderViewModel.openMerchantAction(MerchantAction.CUSTOMER)
                                                },
                                                modifier = androidx.compose.ui.Modifier.heightIn(min = 48.dp)
                                            ) {
                                                androidx.compose.material3.Text(state.language.tr("إضافة زبون جديد", "Add new customer"))
                                            }
                                        }
                                    }
                                } else {
                                    items(state.customers.size) { index ->
                                        val customer = state.customers[index]
                                        androidx.compose.material3.TextButton(
                                            onClick = {
                                                traderViewModel.consumeAction()
                                                traderViewModel.openPaymentCollectionForCustomer(customer)
                                            },
                                            modifier = androidx.compose.ui.Modifier.heightIn(min = 48.dp)
                                        ) { androidx.compose.material3.Text(customer.name(state.language)) }
                                    }
                                }
                            } else if (state.currentSession.canManageSuppliers()) {
                                if (state.suppliers.isEmpty()) {
                                    item {
                                        androidx.compose.material3.Text(
                                            text = state.language.tr("لا يوجد موردون مسجلون", "No registered suppliers"),
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        androidx.compose.material3.TextButton(
                                            onClick = {
                                                traderViewModel.consumeAction()
                                                traderViewModel.openMerchantAction(MerchantAction.SUPPLIER)
                                            },
                                            modifier = androidx.compose.ui.Modifier.heightIn(min = 48.dp)
                                        ) {
                                            androidx.compose.material3.Text(state.language.tr("إضافة مورد جديد", "Add new supplier"))
                                        }
                                    }
                                } else {
                                    items(state.suppliers.size) { index ->
                                        val supplier = state.suppliers[index]
                                        androidx.compose.material3.TextButton(
                                            onClick = { traderViewModel.openSupplierPayment(supplier) },
                                            modifier = androidx.compose.ui.Modifier.heightIn(min = 48.dp)
                                        ) {
                                            androidx.compose.material3.Text(supplier.name(state.language))
                                        }
                                    }
                                }
                            }
                        } }, confirmButton = {}, dismissButton = {
                            androidx.compose.material3.TextButton(onClick = traderViewModel::consumeAction) { androidx.compose.material3.Text(state.language.tr("إلغاء", "Cancel")) }
                        })
                }
                // Simulated Barcode Scanner Bottom Sheet
                if (state.isScannerSheetOpen) {
                    SimulatedBarcodeScannerSheet(
                        state = state,
                        onDismiss = { traderViewModel.setScannerSheetOpen(false) },
                        onScanProduct = { product -> traderViewModel.simulateBarcodeScan(product) },
                        onScanRandom = { traderViewModel.simulateRandomBarcodeScan() },
                        onToggleFlashlight = traderViewModel::toggleFlashlight,
                        onManualInputChange = traderViewModel::updateManualBarcodeInput,
                        onSubmitManual = { traderViewModel.submitManualBarcode() }
                    )
                }

                // Simulated Bluetooth Receipt Printer Bottom Sheet
                if (state.isPrinterSheetOpen) {
                    BluetoothPrinterModalSheet(
                        state = state,
                        onDismiss = { traderViewModel.setPrinterSheetOpen(false) },
                        onScanPrinters = traderViewModel::scanBluetoothPrinters,
                        onConnectPrinter = traderViewModel::connectBluetoothPrinter,
                        onDisconnectPrinter = traderViewModel::disconnectBluetoothPrinter,
                        onSelectPaperWidth = traderViewModel::selectPaperWidth,
                        onTestPrint = {
                            traderViewModel.setPrinterSheetOpen(false)
                            traderViewModel.simulateThermalPrint()
                        }
                    )
                }

                // Custom Quick Item Dialog
                if (state.isCustomItemDialogOpen) {
                    CustomQuickItemDialog(
                        language = state.language,
                        onDismiss = { traderViewModel.openCustomItemDialog(false) },
                        onConfirm = { name, price ->
                            traderViewModel.addCustomQuickItem(name, price)
                        }
                    )
                }

                // Payment Collection Dialog (Customer Debt / Advance Payment)
                if (state.isPaymentCollectionDialogOpen && state.selectedCustomerForDetail != null) {
                    val cust = state.selectedCustomerForDetail!!
                    screenStates.SaveableStateProvider("collection_${cust.id}_${state.customerTransactions.size}") {
                    CustomerPaymentCollectionDialog(
                        customer = cust,
                        lang = state.language,
                        onDismiss = { traderViewModel.openPaymentCollectionDialog(false) },
                        onConfirmPayment = { customerId, amountIls, note, paymentId ->
                            traderViewModel.confirmCustomerCollection(
                                customerId = customerId,
                                paymentAmountIls = amountIls,
                                note = note,
                                paymentId = paymentId
                            )
                        }
                    )
                    }
                }

                // Add Customer Dialog
                if (state.isAddCustomerDialogOpen) {
                    screenStates.SaveableStateProvider("form_customer_${state.customers.size}") {
                    AddCustomerDialog(
                        lang = state.language,
                        onDismiss = { traderViewModel.openAddCustomerDialog(false) },
                        onConfirm = { name, phone, address ->
                            traderViewModel.createCustomer(name, phone, address)
                        }
                    )
                    }
                }

                // Add Expense Dialog
                if (state.isAddExpenseDialogOpen) {
                    screenStates.SaveableStateProvider("form_expense_${state.expenses.size}") {
                    AddExpenseDialog(
                        state = state,
                        onDismiss = { traderViewModel.openAddExpenseDialog(false) },
                        onConfirmExpense = { title, category, amountIls ->
                            traderViewModel.addExpense(title, category, amountIls)
                        }
                    )
                    }
                }

                // Receive Purchase Bill Dialog
                if (state.isReceivePurchaseDialogOpen && state.suppliers.isNotEmpty()) {
                    screenStates.SaveableStateProvider("form_purchase_${state.purchaseBills.size}") {
                    ReceivePurchaseBillDialog(
                        state = state,
                        onDismiss = { traderViewModel.openReceivePurchaseDialog(false) },
                        onConfirmBill = { supplierId, itemsSummary, totalIls, paidNowIls, expectedProductId, expectedQty ->
                            traderViewModel.recordPurchaseBill(
                                supplierId = supplierId,
                                itemsSummary = itemsSummary,
                                totalIls = totalIls,
                                paidNowIls = paidNowIls,
                                expectedProductId = expectedProductId,
                                expectedQuantity = expectedQty
                            )
                        },
                        onConfirmStructuredBill = { supplierId, lines, paidNowIls, note ->
                            traderViewModel.recordStructuredPurchaseBill(
                                supplierId = supplierId,
                                lines = lines,
                                paidNowIls = paidNowIls,
                                note = note
                            )
                        }
                    )
                    }
                }
            }
        }
    }
}
