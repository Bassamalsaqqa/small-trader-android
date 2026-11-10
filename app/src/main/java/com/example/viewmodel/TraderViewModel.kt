package com.example.viewmodel

import com.example.ui.navigation.TraderNavigationPolicy
import com.example.ui.navigation.MerchantAction
import com.example.ui.navigation.allowed
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.OfflineDemoTraderRepository
import com.example.data.SampleCatalog
import com.example.data.TraderRepository
import com.example.model.AppLanguage
import com.example.model.BasketItem
import com.example.model.BluetoothPrinterDevice
import com.example.model.BusinessInfo
import com.example.model.Customer
import com.example.model.CustomerTransaction
import com.example.model.DiscountPreset
import com.example.model.ExpenseCategory
import com.example.model.ExpenseEntry
import com.example.model.InvoiceRecord
import com.example.model.PrinterStatus
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.PurchaseBill
import com.example.model.PurchaseLineItem
import com.example.model.SaleDraft
import com.example.model.StockMovement
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.model.TraderScreen
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.model.WesternFormatters
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TraderViewModel(
    private val repository: TraderRepository = OfflineDemoTraderRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        TraderUiState(
            products = repository.getProducts(),
            stockMovements = repository.getStockMovements(),
            invoices = repository.getInvoices(),
            customers = repository.getCustomers(),
            customerTransactions = repository.getCustomerTransactions(),
            suppliers = repository.getSuppliers(),
            purchaseBills = repository.getPurchaseBills(),
            supplierPayments = repository.getSupplierPayments(),
            expenses = repository.getExpenses(),
            currentSession = repository.getCurrentSession(),
            businessInfo = repository.getBusinessInfo()
        )
    )
    val uiState: StateFlow<TraderUiState> = _uiState.asStateFlow()

    private var printJob: Job? = null
    private var bannerJob: Job? = null
    private var customItemCounter = 1

    private fun syncProductsAndInventory() {
        val updatedProducts = repository.getProducts()
        _uiState.update { current ->
            current.copy(
                products = updatedProducts,
                stockMovements = repository.getStockMovements(),
                selectedProductForDetail = current.selectedProductForDetail?.let { sel ->
                    updatedProducts.firstOrNull { it.id == sel.id } ?: sel
                }
            )
        }
    }

    private fun syncCustomers() {
        val updatedCustomers = repository.getCustomers()
        _uiState.update { current ->
            current.copy(
                customers = updatedCustomers,
                customerTransactions = repository.getCustomerTransactions(),
                selectedCustomerForDetail = current.selectedCustomerForDetail?.let { sel ->
                    updatedCustomers.firstOrNull { it.id == sel.id } ?: sel
                }
            )
        }
    }

    private fun syncSuppliersAndPurchases() {
        val updatedSuppliers = repository.getSuppliers()
        _uiState.update { current ->
            current.copy(
                suppliers = updatedSuppliers,
                purchaseBills = repository.getPurchaseBills(),
                supplierPayments = repository.getSupplierPayments(),
                selectedSupplierForDetail = current.selectedSupplierForDetail?.let { sel ->
                    updatedSuppliers.firstOrNull { it.id == sel.id } ?: sel
                }
            )
        }
    }

    private fun syncExpenses() {
        _uiState.update { current ->
            current.copy(expenses = repository.getExpenses())
        }
    }

    fun syncFromRepository() {
        val updatedProducts = repository.getProducts()
        val updatedCustomers = repository.getCustomers()
        val updatedSuppliers = repository.getSuppliers()
        _uiState.update { current ->
            current.copy(
                products = updatedProducts,
                stockMovements = repository.getStockMovements(),
                invoices = repository.getInvoices(),
                customers = updatedCustomers,
                customerTransactions = repository.getCustomerTransactions(),
                suppliers = updatedSuppliers,
                purchaseBills = repository.getPurchaseBills(),
                supplierPayments = repository.getSupplierPayments(),
                expenses = repository.getExpenses(),
                businessInfo = repository.getBusinessInfo(),
                selectedProductForDetail = current.selectedProductForDetail?.let { sel ->
                    updatedProducts.firstOrNull { it.id == sel.id } ?: sel
                },
                selectedCustomerForDetail = current.selectedCustomerForDetail?.let { sel ->
                    updatedCustomers.firstOrNull { it.id == sel.id } ?: sel
                },
                selectedSupplierForDetail = current.selectedSupplierForDetail?.let { sel ->
                    updatedSuppliers.firstOrNull { it.id == sel.id } ?: sel
                }
            )
        }
    }

    fun toggleLanguage() {
        _uiState.update { state ->
            val next = if (state.language == AppLanguage.AR) AppLanguage.EN else AppLanguage.AR
            state.copy(language = next)
        }
    }

    fun toggleTheme() {
        _uiState.update { it.copy(isDarkTheme = !it.isDarkTheme) }
    }

    fun setDarkTheme(enabled: Boolean) {
        _uiState.update { it.copy(isDarkTheme = enabled) }
    }

    fun setLanguage(language: AppLanguage) {
        _uiState.update { it.copy(language = language) }
    }

    fun switchUserSession(user: UserSession) {
        _uiState.update { state ->
            val safe = TraderNavigationPolicy.allows(user, state.currentScreen)
            state.copy(currentSession = user, currentScreen = if (safe) state.currentScreen else TraderScreen.HOME,
                selectedRoot = if (safe) state.selectedRoot else TraderScreen.HOME,
                screenHistory = state.screenHistory.filter { TraderNavigationPolicy.allows(user, it) },
                pendingAction = null, isQuickActionOpen = false, isReceivePurchaseDialogOpen = false,
                isAddExpenseDialogOpen = false, selectedSupplierForDetail = null)
        }
    }

    fun setQuickActionOpen(open: Boolean) { _uiState.update { it.copy(isQuickActionOpen = open) } }
    fun consumeAction() { _uiState.update { it.copy(pendingAction = null) } }
    fun openMerchantAction(action: MerchantAction) {
        if (!action.allowed(_uiState.value.currentSession)) return
        _uiState.update { it.copy(pendingAction = null, isQuickActionOpen = false) }
        when (action) {
            MerchantAction.SALE -> navigateTo(TraderScreen.NEW_SALE)
            MerchantAction.PURCHASE -> { navigateTo(TraderScreen.PURCHASES); openReceivePurchaseDialog(true) }
            MerchantAction.CUSTOMER -> { navigateTo(TraderScreen.CUSTOMERS); openAddCustomerDialog(true) }
            MerchantAction.EXPENSE -> { navigateTo(TraderScreen.EXPENSES); openAddExpenseDialog(true) }
            MerchantAction.COLLECT, MerchantAction.PAY -> {
                _uiState.update { it.copy(pendingAction = action) }
            }
            else -> {
                navigateTo(when (action) {
                    MerchantAction.PRODUCT -> TraderScreen.PRODUCTS_CATALOG
                    else -> TraderScreen.SUPPLIERS
                })
                _uiState.update { it.copy(pendingAction = action) }
            }
        }
    }

    fun navigateTo(target: TraderScreen) {
        if (!TraderNavigationPolicy.allows(_uiState.value.currentSession, target)) return
        _uiState.update { state ->
            if (state.currentScreen == target) {
                state
            } else {
                val updatedHistory = when {
                    target == TraderScreen.HOME -> emptyList()
                    target in TraderUiState.ROOT_DESTINATIONS -> {
                        listOf(TraderScreen.HOME)
                    }
                    target in state.screenHistory -> {
                        state.screenHistory.takeWhile { it != target }
                    }
                    else -> {
                        (state.screenHistory + state.currentScreen).takeLast(8)
                    }
                }
                val defaultCashText = if (target == TraderScreen.CASH_PAYMENT && state.cashInputText.isBlank()) {
                    WesternFormatters.formatNumber(state.basketNetTotalIls)
                } else {
                    state.cashInputText
                }
                state.copy(
                    currentScreen = target,
                    selectedRoot = if (target in TraderUiState.ROOT_DESTINATIONS) target else state.selectedRoot,
                    isQuickActionOpen = false,
                    pendingAction = null,
                    searchQuery = if (target == TraderScreen.PRODUCTS_CATALOG) state.inventorySearchQuery else if (target == TraderScreen.NEW_SALE) state.saleSearchQuery else state.searchQuery,
                    selectedCategory = if (target == TraderScreen.PRODUCTS_CATALOG) state.inventoryCategory else if (target == TraderScreen.NEW_SALE) state.saleCategory else state.selectedCategory,
                    screenHistory = updatedHistory,
                    cashInputText = defaultCashText,
                    checkoutErrorAr = null,
                    checkoutErrorEn = null
                )
            }
        }
    }

    fun navigateBack(): Boolean {
        val current = _uiState.value
        if (current.isQuickActionOpen) { setQuickActionOpen(false); return true }
        if (current.pendingAction != null) { consumeAction(); return true }
        if (current.isScannerSheetOpen) {
            _uiState.update { it.copy(isScannerSheetOpen = false) }
            return true
        }
        if (current.isPrinterSheetOpen) {
            _uiState.update { it.copy(isPrinterSheetOpen = false) }
            return true
        }
        if (current.isCustomItemDialogOpen) {
            _uiState.update { it.copy(isCustomItemDialogOpen = false) }
            return true
        }
        if (current.isPaymentCollectionDialogOpen) {
            _uiState.update { it.copy(isPaymentCollectionDialogOpen = false) }
            return true
        }
        if (current.isAddCustomerDialogOpen) {
            _uiState.update { it.copy(isAddCustomerDialogOpen = false) }
            return true
        }
        if (current.isAddExpenseDialogOpen) {
            _uiState.update { it.copy(isAddExpenseDialogOpen = false) }
            return true
        }
        if (current.isReceivePurchaseDialogOpen) {
            _uiState.update { it.copy(isReceivePurchaseDialogOpen = false) }
            return true
        }
        if (current.currentScreen == TraderScreen.HOME) return false
        val previous = current.screenHistory.lastOrNull() ?: TraderScreen.HOME
        _uiState.update { state ->
            state.copy(
                currentScreen = previous,
                searchQuery = when (previous) {
                    TraderScreen.NEW_SALE -> state.saleSearchQuery
                    TraderScreen.PRODUCTS_CATALOG -> state.inventorySearchQuery
                    else -> state.searchQuery
                },
                selectedCategory = when (previous) {
                    TraderScreen.NEW_SALE -> state.saleCategory
                    TraderScreen.PRODUCTS_CATALOG -> state.inventoryCategory
                    else -> state.selectedCategory
                },
                selectedRoot = if (previous in TraderUiState.ROOT_DESTINATIONS) previous else state.selectedRoot,
                screenHistory = state.screenHistory.dropLast(1)
            )
        }
        return true
    }

    // Product Catalog & Inventory
    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query,
            saleSearchQuery = if (it.currentScreen == TraderScreen.NEW_SALE) query else it.saleSearchQuery,
            inventorySearchQuery = if (it.currentScreen == TraderScreen.PRODUCTS_CATALOG) query else it.inventorySearchQuery) }
    }

    fun openLowStockInventory() {
        navigateTo(TraderScreen.PRODUCTS_CATALOG)
        _uiState.update { it.copy(inventoryLowStockRequested = true,
            inventorySearchQuery = "", searchQuery = "",
            inventoryCategory = ProductCategory.ALL, selectedCategory = ProductCategory.ALL) }
    }

    fun consumeLowStockRequest() {
        _uiState.update { it.copy(inventoryLowStockRequested = false) }
    }

    fun selectCategory(category: ProductCategory) {
        _uiState.update { state -> state.copy(selectedCategory = category,
            saleCategory = if (state.currentScreen == TraderScreen.NEW_SALE) category else state.saleCategory,
            inventoryCategory = if (state.currentScreen == TraderScreen.PRODUCTS_CATALOG) category else state.inventoryCategory) }
    }

    fun selectProductForDetail(product: Product) {
        _uiState.update { state ->
            val freshProduct = state.products.firstOrNull { it.id == product.id } ?: product
            state.copy(
                selectedProductForDetail = freshProduct,
                lastInventoryActionMessageAr = null,
                lastInventoryActionMessageEn = null,
                isInventoryActionError = false,
                currentScreen = TraderScreen.PRODUCT_DETAIL,
                screenHistory = (state.screenHistory + state.currentScreen).takeLast(8)
            )
        }
    }

    fun dismissInventoryFeedback() {
        _uiState.update {
            it.copy(
                lastInventoryActionMessageAr = null,
                lastInventoryActionMessageEn = null,
                isInventoryActionError = false
            )
        }
    }

    fun updateProductPrice(productId: String, newPriceIls: Double): Boolean {
        val session = _uiState.value.currentSession
        val result = repository.updateProductPrice(productId, newPriceIls, session)
        return if (result.isSuccess) {
            syncFromRepository()
            val formattedPrice = WesternFormatters.formatIls(newPriceIls)
            _uiState.update {
                it.copy(
                    lastInventoryActionMessageAr = "تم تحديث سعر البيع إلى $formattedPrice",
                    lastInventoryActionMessageEn = "Selling price updated to $formattedPrice",
                    isInventoryActionError = false
                )
            }
            true
        } else {
            val rawErr = result.exceptionOrNull()?.message ?: "Price update rejected"
            val parts = rawErr.split(" | ", limit = 2)
            val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            _uiState.update {
                it.copy(
                    lastInventoryActionMessageAr = errAr,
                    lastInventoryActionMessageEn = errEn,
                    isInventoryActionError = true
                )
            }
            showBanner(errAr, errEn, isError = true)
            false
        }
    }

    fun adjustProductStock(productId: String, adjustment: Int, reason: String): Boolean {
        val session = _uiState.value.currentSession
        val result = repository.adjustProductStock(productId, adjustment, reason, session)
        return if (result.isSuccess) {
            val movement = result.getOrNull()
            syncFromRepository()
            val signedDelta = if (adjustment > 0) "+${WesternFormatters.formatInt(adjustment)}" else WesternFormatters.formatInt(adjustment)
            val newStock = movement?.resultingStock ?: (_uiState.value.products.firstOrNull { it.id == productId }?.stockCount ?: 0)
            _uiState.update {
                it.copy(
                    lastInventoryActionMessageAr = "تم تسجيل حركة المخزون ($signedDelta) — الرصيد الحالي: ${WesternFormatters.formatInt(newStock)}",
                    lastInventoryActionMessageEn = "Stock movement recorded ($signedDelta) — Current stock: ${WesternFormatters.formatInt(newStock)}",
                    isInventoryActionError = false
                )
            }
            true
        } else {
            val rawErr = result.exceptionOrNull()?.message ?: "Stock adjustment rejected"
            val parts = rawErr.split(" | ", limit = 2)
            val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            _uiState.update {
                it.copy(
                    lastInventoryActionMessageAr = errAr,
                    lastInventoryActionMessageEn = errEn,
                    isInventoryActionError = true
                )
            }
            showBanner(errAr, errEn, isError = true)
            false
        }
    }

    fun restockProduct(productId: String, amount: Int = 10) {
        adjustProductStock(productId, amount, "إعادة تزويد سريع للرفوف (+${amount})")
    }

    // POS Basket with Overselling Prevention Safeguard
    fun addProductToBasket(product: Product, count: Int = 1) {
        if (count <= 0) return
        _uiState.update { state ->
            val productInCatalog = state.products.firstOrNull { it.id == product.id } ?: product
            val existingIndex = state.basket.indexOfFirst { it.product.id == product.id }
            val currentQty = if (existingIndex >= 0) state.basket[existingIndex].quantity else 0

            // If product is stock-tracked, enforce physical inventory cap
            if (productInCatalog.isStockTracked) {
                if (productInCatalog.stockCount <= 0) {
                    return@update state.copy(
                        lastScannedBannerAr = "غير متوفر (نفد المخزون): ${productInCatalog.nameAr}",
                        lastScannedBannerEn = "Out of stock: ${productInCatalog.nameEn}",
                        isBannerError = true
                    )
                }
                if (currentQty >= productInCatalog.stockCount) {
                    return@update state.copy(
                        lastScannedBannerAr = "تم الوصول للحد الأقصى لمخزون: ${productInCatalog.nameAr} (${productInCatalog.stockCount})",
                        lastScannedBannerEn = "Maximum available stock reached for ${productInCatalog.nameEn} (${productInCatalog.stockCount})",
                        isBannerError = true
                    )
                }
                val allowedCount = minOf(count, productInCatalog.stockCount - currentQty)
                if (allowedCount <= 0) return@update state

                val updatedBasket = if (existingIndex >= 0) {
                    state.basket.mapIndexed { idx, item ->
                        if (idx == existingIndex) item.copy(quantity = item.quantity + allowedCount) else item
                    }
                } else {
                    state.basket + BasketItem(product = productInCatalog, quantity = allowedCount)
                }
                state.copy(basket = updatedBasket)
            } else {
                // Non-stock line (custom item): permissible addition without stock caps
                val updatedBasket = if (existingIndex >= 0) {
                    state.basket.mapIndexed { idx, item ->
                        if (idx == existingIndex) item.copy(quantity = item.quantity + count) else item
                    }
                } else {
                    state.basket + BasketItem(product = productInCatalog, quantity = count)
                }
                state.copy(basket = updatedBasket)
            }
        }
    }

    fun decrementProductInBasket(productId: String) {
        _uiState.update { state ->
            val updatedBasket = state.basket.mapNotNull { item ->
                if (item.product.id == productId) {
                    if (item.quantity > 1) item.copy(quantity = item.quantity - 1) else null
                } else {
                    item
                }
            }
            state.copy(basket = updatedBasket)
        }
    }

    fun removeProductFromBasket(productId: String) {
        _uiState.update { state ->
            state.copy(basket = state.basket.filterNot { it.product.id == productId })
        }
    }

    fun clearBasket() {
        _uiState.update { state ->
            state.copy(
                basket = emptyList(),
                saleSessionId = state.saleSessionId + 1,
                discountPreset = DiscountPreset.NONE,
                customDiscountText = "",
                customerNote = "",
                selectedCustomerId = null,
                cashInputText = "",
                isSubmittingSale = false,
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun openCustomItemDialog(open: Boolean) {
        _uiState.update { it.copy(isCustomItemDialogOpen = open) }
    }

    fun addCustomQuickItem(nameInput: String, priceInput: String) {
        val price = WesternFormatters.sanitizeWesternDigits(priceInput).toDoubleOrNull() ?: return
        if (price <= 0.0) return
        val cleanName = nameInput.trim()
        val index = customItemCounter++
        val nameAr = cleanName.ifEmpty { "مادة سريعة #$index" }
        val nameEn = cleanName.ifEmpty { "Quick Line #$index" }
        // Truthful modeling: permissible non-stock line with 0 invented cost and 0 invented stock
        val customProduct = Product(
            id = "custom_${System.currentTimeMillis()}_$index",
            nameAr = nameAr,
            nameEn = nameEn,
            category = ProductCategory.PANTRY,
            barcode = String.format(java.util.Locale.US, "625999000%04d", index),
            priceIls = price,
            costIls = 0.0, // Zero invented cost
            unitAr = "مادة سريعة",
            unitEn = "Quick Line",
            stockCount = 0, // Zero invented stock
            minStockThreshold = 0,
            accentHex = 0xFF1D4E89,
            isStockTracked = false // Permissible non-stock line
        )
        repository.addCustomProduct(customProduct)
        syncFromRepository()
        addProductToBasket(customProduct, 1)
        _uiState.update { it.copy(isCustomItemDialogOpen = false) }
    }

    // Barcode Scanner & Flashlight Hardware Control
    fun setScannerSheetOpen(open: Boolean) {
        _uiState.update { it.copy(isScannerSheetOpen = open) }
    }

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    fun updateManualBarcodeInput(input: String) {
        _uiState.update { it.copy(manualBarcodeInput = WesternFormatters.sanitizeWesternDigits(input)) }
    }

    fun simulateBarcodeScan(product: Product) {
        val currentState = _uiState.value
        val productInCatalog = currentState.products.firstOrNull { it.id == product.id } ?: product
        val currentQty = currentState.quantityInBasket(productInCatalog.id)

        if (productInCatalog.isStockTracked && productInCatalog.stockCount <= 0) {
            showBanner(
                ar = "غير متوفر (نفد المخزون): ${productInCatalog.nameAr}",
                en = "Out of stock: ${productInCatalog.nameEn}",
                isError = true
            )
            return
        }

        if (productInCatalog.isStockTracked && currentQty >= productInCatalog.stockCount) {
            showBanner(
                ar = "تم الوصول للحد الأقصى لمخزون: ${productInCatalog.nameAr} (${WesternFormatters.formatInt(productInCatalog.stockCount)})",
                en = "Maximum available stock reached for ${productInCatalog.nameEn} (${WesternFormatters.formatInt(productInCatalog.stockCount)})",
                isError = true
            )
            return
        }

        addProductToBasket(productInCatalog, 1)
        val newQty = _uiState.value.quantityInBasket(productInCatalog.id)
        val priceFormatted = WesternFormatters.formatIls(productInCatalog.priceIls)
        if (newQty > 1) {
            showBanner(
                ar = "تم مسح (×${WesternFormatters.formatInt(newQty)} في السلة): ${productInCatalog.nameAr} ($priceFormatted)",
                en = "Scanned (×${WesternFormatters.formatInt(newQty)} in basket): ${productInCatalog.nameEn} ($priceFormatted)",
                isError = false
            )
        } else {
            showBanner(
                ar = "تم مسح: ${productInCatalog.nameAr} ($priceFormatted)",
                en = "Scanned: ${productInCatalog.nameEn} ($priceFormatted)",
                isError = false
            )
        }
        _uiState.update { it.copy(scanCountSession = it.scanCountSession + 1) }
    }

    fun simulateRandomBarcodeScan() {
        val state = _uiState.value
        val catalog = state.products
        if (catalog.isEmpty()) return
        val available = catalog.filter { !it.isStockTracked || state.quantityInBasket(it.id) < it.stockCount }
        val chosen = (available.ifEmpty { catalog }).random()
        simulateBarcodeScan(chosen)
    }

    fun submitManualBarcode(): Boolean {
        val code = WesternFormatters.sanitizeWesternDigits(_uiState.value.manualBarcodeInput.trim())
        if (code.isEmpty()) return false
        val matched = _uiState.value.products.firstOrNull {
            it.barcode == code || it.barcode.endsWith(code)
        }
        return if (matched != null) {
            simulateBarcodeScan(matched)
            _uiState.update { it.copy(manualBarcodeInput = "") }
            true
        } else {
            showBanner(
                ar = "لم يتم العثور على باركود: $code",
                en = "Barcode not found: $code",
                isError = true
            )
            false
        }
    }

    // Discounts & Customer Notes
    fun selectDiscountPreset(preset: DiscountPreset) {
        _uiState.update { state ->
            state.copy(
                discountPreset = preset,
                cashInputText = "",
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun updateCustomDiscountText(text: String) {
        val sanitized = WesternFormatters.sanitizeWesternDigits(text)
        _uiState.update {
            it.copy(
                discountPreset = DiscountPreset.CUSTOM_ILS,
                customDiscountText = sanitized,
                cashInputText = "",
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun updateCustomerNote(note: String) {
        _uiState.update { it.copy(customerNote = note) }
    }

    fun assignCustomerToSale(customer: Customer?) {
        _uiState.update { state ->
            val currentNote = state.customerNote.trim()
            val wasPreviousCustomerName = state.customers.any {
                it.nameAr == currentNote || it.nameEn == currentNote
            }
            val updatedNote = if (wasPreviousCustomerName) "" else state.customerNote
            state.copy(
                selectedCustomerId = customer?.id,
                customerNote = updatedNote,
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun dismissCheckoutError() {
        _uiState.update {
            it.copy(
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    // Cash Payment & Native / Quick Tender Entry
    fun setCashExactAmount() {
        _uiState.update { state ->
            state.copy(
                cashInputText = WesternFormatters.formatNumber(state.basketNetTotalIls),
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun setCashQuickDenomination(amountIls: Int) {
        _uiState.update { state ->
            state.copy(
                cashInputText = amountIls.toString(),
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun addCashIncrement(deltaIls: Int) {
        _uiState.update { state ->
            val current = state.cashTenderedIls
            val next = current + deltaIls
            state.copy(
                cashInputText = WesternFormatters.formatNumber(next).removeSuffix(".00"),
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun updateCashInputText(rawInput: String) {
        val western = WesternFormatters.sanitizeWesternDigits(rawInput)
        val filtered = buildString {
            var hasDot = false
            var decimals = 0
            for (ch in western) {
                if (ch.isDigit()) {
                    if (hasDot) {
                        if (decimals < 2) {
                            append(ch)
                            decimals++
                        }
                    } else if (length < 6) {
                        append(ch)
                    }
                } else if (ch == '.' && !hasDot) {
                    if (isEmpty()) append('0')
                    append('.')
                    hasDot = true
                }
            }
        }
        _uiState.update { state ->
            state.copy(
                cashInputText = filtered,
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun onCashKeypadPress(key: String) {
        _uiState.update { state ->
            val current = state.cashInputText
            val updated = when (key) {
                "C" -> ""
                "BACK" -> if (current.isNotEmpty()) current.dropLast(1) else ""
                "." -> {
                    if (current.contains(".")) current
                    else if (current.isEmpty()) "0."
                    else "$current."
                }
                else -> {
                    val base = if (current == "0" || current == WesternFormatters.formatNumber(state.basketNetTotalIls)) {
                        ""
                    } else {
                        current
                    }
                    val candidate = base + key
                    val parts = candidate.split(".")
                    if (parts.size == 2 && parts[1].length > 2) current
                    else if (candidate.length > 8) current
                    else candidate
                }
            }
            state.copy(
                cashInputText = updated,
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    // Final Sale Posting via Repository
    fun completeCashSale(asCreditDebt: Boolean = false) {
        val state = _uiState.value
        if (state.basket.isEmpty() || state.isSubmittingSale) return

        val netTotal = state.basketNetTotalIls

        // Safeguard 1: Cash sale requires sufficient cash tendered (no partial cash accepted)
        if (!asCreditDebt && !state.isCashSufficient) {
            val shortfall = WesternFormatters.formatIls(state.remainingUnpaidIls)
            val totalFormatted = WesternFormatters.formatIls(netTotal)
            val ar = "النقد المستلم ناقص بمقدار $shortfall — استلم كامل المبلغ ($totalFormatted) أو اختر الترحيل الآجل لزبون مسجل"
            val en = "Cash received is short by $shortfall — collect full $totalFormatted or post on a customer account"
            _uiState.update {
                it.copy(
                    checkoutErrorAr = ar,
                    checkoutErrorEn = en
                )
            }
            showBanner(ar = ar, en = en, isError = true)
            return
        }

        // Safeguard 2: Credit sale requires a registered customer account
        if (asCreditDebt && state.selectedCustomerId == null) {
            val ar = "البيع على الحساب يتطلب تحديد حساب زبون مسجل أولاً"
            val en = "Credit sale requires selecting a registered customer account first"
            _uiState.update {
                it.copy(
                    checkoutErrorAr = ar,
                    checkoutErrorEn = en
                )
            }
            showBanner(ar = ar, en = en, isError = true)
            return
        }

        _uiState.update { it.copy(isSubmittingSale = true) }

        val tendered: Double
        val change: Double
        val remainingDebt: Double
        val isPaidInFull: Boolean

        if (asCreditDebt) {
            // Truthful full-credit sale: 0.00 cash into drawer, 100% of netTotal recorded as customer debt
            tendered = 0.0
            change = 0.0
            remainingDebt = BigDecimal(netTotal.toString()).setScale(2, RoundingMode.HALF_UP).toDouble()
            isPaidInFull = (remainingDebt <= 0.001)
        } else {
            tendered = BigDecimal(state.cashTenderedIls.toString()).setScale(2, RoundingMode.HALF_UP).toDouble()
            val changeBd = BigDecimal(tendered.toString()).subtract(BigDecimal(netTotal.toString())).setScale(2, RoundingMode.HALF_UP)
            change = changeBd.toDouble().coerceAtLeast(0.0)
            remainingDebt = 0.0
            isPaidInFull = true
        }

        val nextNumber = (state.invoices.maxOfOrNull { it.invoiceNumber } ?: 1040) + 1
        val timestamp = WesternFormatters.currentTimestamp()
        val linkedCustomer = state.selectedCustomer
        val noteTrimmed = state.customerNote.trim()
        val customerName = when {
            linkedCustomer != null && noteTrimmed.isNotEmpty() &&
                noteTrimmed != linkedCustomer.nameAr && noteTrimmed != linkedCustomer.nameEn ->
                "${linkedCustomer.name(state.language)} • $noteTrimmed"
            linkedCustomer != null ->
                linkedCustomer.name(state.language)
            noteTrimmed.isNotEmpty() ->
                noteTrimmed
            else ->
                state.language.tr("زبون نقدي", "Walk-in Customer")
        }

        val newInvoice = InvoiceRecord(
            id = "inv_$nextNumber",
            invoiceNumber = nextNumber,
            timestamp = timestamp,
            items = state.basket,
            subtotalIls = state.basketSubtotalIls,
            discountIls = state.calculatedDiscountIls,
            netTotalIls = netTotal,
            cashReceivedIls = tendered,
            changeReturnedIls = change,
            customerId = state.selectedCustomerId,
            customerNote = customerName,
            cashierNameAr = state.currentSession.name,
            cashierNameEn = state.currentSession.name,
            isPaidInFull = isPaidInFull,
            remainingDebitIls = remainingDebt,
            printCount = 0
        )

        // Authoritative posting through the repository contract
        val result = repository.recordSale(newInvoice)
        if (result.isSuccess) {
            syncFromRepository()
            _uiState.update { current ->
                val printer = current.connectedPrinter
                val ready = printer != null && printer.isPaired
                current.copy(
                    activeReceipt = newInvoice,
                    isNewlyCompletedSale = true,
                    receiptShareCount = 0,
                    receiptCopyCount = 0,
                    lastSharedReceiptText = null,
                    basket = emptyList(),
                saleSessionId = current.saleSessionId + 1,
                    discountPreset = DiscountPreset.NONE,
                    customDiscountText = "",
                    customerNote = "",
                    selectedCustomerId = null,
                    cashInputText = "",
                    isSubmittingSale = false,
                    checkoutErrorAr = null,
                    checkoutErrorEn = null,
                    currentScreen = TraderScreen.RECEIPT,
                    screenHistory = listOf(TraderScreen.HOME),
                    printerStatus = if (ready) PrinterStatus.READY else PrinterStatus.DISCONNECTED,
                    printProgressFraction = 0f,
                    printProgressStepAr = if (ready) {
                        "وضع المحاكاة: جاهز لمعاينة الطباعة عبر ${printer!!.name} (${current.receiptPaperWidthMm}mm)"
                    } else {
                        "وضع المحاكاة: لا توجد طابعة متصلة — اختر طابعة أو شارك النص"
                    },
                    printProgressStepEn = if (ready) {
                        "Simulation mode: Ready for print preview via ${printer!!.name} (${current.receiptPaperWidthMm}mm)"
                    } else {
                        "Simulation mode: No printer connected — select a printer or share text"
                    }
                )
            }
        } else {
            val rawErr = result.exceptionOrNull()?.message ?: "Sale rejected by domain engine"
            val parts = rawErr.split(" | ", limit = 2)
            val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            _uiState.update {
                it.copy(
                    isSubmittingSale = false,
                    checkoutErrorAr = errAr,
                    checkoutErrorEn = errEn
                )
            }
            showBanner(ar = errAr, en = errEn, isError = true)
        }
    }

    fun openInvoiceReceipt(invoice: InvoiceRecord) {
        _uiState.update { state ->
            val printer = state.connectedPrinter
            val ready = printer != null && printer.isPaired
            state.copy(
                activeReceipt = invoice,
                isNewlyCompletedSale = false,
                receiptShareCount = 0,
                receiptCopyCount = 0,
                lastSharedReceiptText = null,
                currentScreen = TraderScreen.RECEIPT,
                screenHistory = (state.screenHistory + state.currentScreen).takeLast(8),
                printerStatus = if (ready) PrinterStatus.READY else PrinterStatus.DISCONNECTED,
                printProgressFraction = 0f,
                printProgressStepAr = if (ready) {
                    "وضع المحاكاة: جاهز لمعاينة الطباعة عبر ${printer!!.name} (${state.receiptPaperWidthMm}mm)"
                } else {
                    "وضع المحاكاة: لا توجد طابعة متصلة — اختر طابعة أو شارك النص"
                },
                printProgressStepEn = if (ready) {
                    "Simulation mode: Ready for print preview via ${printer!!.name} (${state.receiptPaperWidthMm}mm)"
                } else {
                    "Simulation mode: No printer connected — select a printer or share text"
                }
            )
        }
    }

    fun startFreshSale() {
        clearBasket()
        _uiState.update { state ->
            state.copy(
                currentScreen = TraderScreen.NEW_SALE,
                screenHistory = listOf(TraderScreen.HOME)
            )
        }
    }

    // Customer Ledger & Debt Collections
    fun selectCustomerForDetail(customer: Customer) {
        _uiState.update { state ->
            val freshCustomer = state.customers.firstOrNull { it.id == customer.id } ?: customer
            state.copy(
                selectedCustomerForDetail = freshCustomer,
                lastCustomerPaymentMessageAr = null,
                lastCustomerPaymentMessageEn = null,
                currentScreen = TraderScreen.CUSTOMER_DETAIL,
                screenHistory = (state.screenHistory + state.currentScreen).takeLast(8)
            )
        }
    }

    fun openPaymentCollectionDialog(open: Boolean) {
        _uiState.update { it.copy(isPaymentCollectionDialogOpen = open) }
    }

    fun openPaymentCollectionForCustomer(customer: Customer) {
        _uiState.update { state ->
            val freshCustomer = state.customers.firstOrNull { it.id == customer.id } ?: customer
            state.copy(
                selectedCustomerForDetail = freshCustomer,
                isPaymentCollectionDialogOpen = true
            )
        }
    }

    fun dismissCustomerFeedback() {
        _uiState.update {
            it.copy(
                lastCustomerPaymentMessageAr = null,
                lastCustomerPaymentMessageEn = null
            )
        }
    }

    fun confirmCustomerCollection(customerId: String, paymentAmountIls: Double, note: String, paymentId: String): Boolean {
        val saved = recordCustomerPayment(customerId, paymentAmountIls, note, paymentId)
        if (saved) navigateTo(TraderScreen.CUSTOMER_DETAIL)
        return saved
    }

    fun recordCustomerPayment(customerId: String, paymentAmountIls: Double, note: String, paymentId: String = ""): Boolean {
        val pid = if (paymentId.isNotBlank()) paymentId else "cpay_${System.currentTimeMillis()}"
        val result = repository.recordCustomerPayment(customerId, paymentAmountIls, note, pid)
        return if (result.isSuccess) {
            syncCustomers()
            _uiState.update { state ->
                val updatedCust = state.customers.firstOrNull { it.id == customerId }
                val paidStr = WesternFormatters.formatIls(paymentAmountIls)
                val newBal = updatedCust?.balanceIls ?: 0.0
                val msgAr = when {
                    newBal > 0.005 -> "تم قبض $paidStr نقداً — المتبقي على الزبون: ${WesternFormatters.formatIls(newBal)}"
                    newBal < -0.005 -> "تم قبض $paidStr نقداً — أصبح للزبون رصيد مقدم: ${WesternFormatters.formatIls(-newBal)}"
                    else -> "تم قبض $paidStr نقداً — أصبح الحساب خالصاً (0.00 ₪)"
                }
                val msgEn = when {
                    newBal > 0.005 -> "Collected $paidStr in cash — Remaining debt: ${WesternFormatters.formatIls(newBal)}"
                    newBal < -0.005 -> "Collected $paidStr in cash — Customer advance credit: ${WesternFormatters.formatIls(-newBal)}"
                    else -> "Collected $paidStr in cash — Account is now settled (0.00 ₪)"
                }
                state.copy(
                    isPaymentCollectionDialogOpen = false,
                    selectedCustomerForDetail = updatedCust,
                    lastCustomerPaymentMessageAr = msgAr,
                    lastCustomerPaymentMessageEn = msgEn
                )
            }
            true
        } else {
            val err = result.exceptionOrNull()?.message ?: "Payment rejected"
            showBanner(err, err, isError = true)
            false
        }
    }

    fun createCustomer(name: String, phone: String, address: String) {
        val result = repository.createCustomer(name, phone, address)
        if (result.isSuccess) {
            val created = result.getOrNull()
            syncCustomers()
            _uiState.update {
                it.copy(
                    isAddCustomerDialogOpen = false,
                    lastCustomerPaymentMessageAr = if (created != null) "تمت إضافة الزبون «${created.nameAr}» إلى دفتر الحسابات" else null,
                    lastCustomerPaymentMessageEn = if (created != null) "Added customer \"${created.nameEn}\" to ledger" else null
                )
            }
        }
    }

    fun openAddCustomerDialog(open: Boolean) {
        _uiState.update { it.copy(isAddCustomerDialogOpen = open) }
    }

    // Suppliers & Purchasing Operations
    fun openSupplierPayment(supplier: Supplier) {
        if (!_uiState.value.currentSession.canManageSuppliers()) return
        selectSupplierForDetail(supplier)
        _uiState.update { it.copy(pendingAction = MerchantAction.PAY) }
    }

    fun selectSupplierForDetail(supplier: Supplier) {
        if (!_uiState.value.currentSession.canManageSuppliers()) return
        _uiState.update { state ->
            val freshSupplier = state.suppliers.firstOrNull { it.id == supplier.id } ?: supplier
            state.copy(
                selectedSupplierForDetail = freshSupplier,
                lastSupplierActionMessageAr = null,
                lastSupplierActionMessageEn = null,
                isSupplierActionError = false,
                currentScreen = TraderScreen.SUPPLIER_DETAIL,
                screenHistory = (state.screenHistory + state.currentScreen).takeLast(8)
            )
        }
    }

    fun dismissSupplierFeedback() {
        _uiState.update {
            it.copy(
                lastSupplierActionMessageAr = null,
                lastSupplierActionMessageEn = null,
                isSupplierActionError = false
            )
        }
    }

    fun openReceivePurchaseDialog(open: Boolean) {
        if (open && !_uiState.value.currentSession.canManageSuppliers()) return
        _uiState.update { it.copy(isReceivePurchaseDialogOpen = open) }
    }

    fun recordPurchaseBill(
        supplierId: String,
        itemsSummary: String,
        totalIls: Double,
        paidNowIls: Double,
        expectedProductId: String? = null,
        expectedQuantity: Int = 0
    ): Boolean {
        val session = _uiState.value.currentSession
        val result = repository.recordPurchaseBill(
            supplierId = supplierId,
            itemsSummary = itemsSummary,
            totalIls = totalIls,
            paidNowIls = paidNowIls,
            operator = session,
            expectedProductId = expectedProductId,
            expectedQuantity = expectedQuantity
        )
        return if (result.isSuccess) {
            val bill = result.getOrNull()
            syncSuppliersAndPurchases()
            _uiState.update { state ->
                val billCode = bill?.let { WesternFormatters.formatBillCode(it.billNumber) } ?: ""
                val remStr = bill?.let { WesternFormatters.formatIls(it.remainingPayableIls) } ?: ""
                val stockNoteAr = if (expectedProductId != null && expectedQuantity > 0) {
                    " — مسجل بانتظار استلام (${WesternFormatters.formatInt(expectedQuantity)} وحدة) مخزنياً"
                } else {
                    " — قيد مالي فقط دون إضافة مخزون تلقائي"
                }
                val stockNoteEn = if (expectedProductId != null && expectedQuantity > 0) {
                    " — pending physical goods receipt (${expectedQuantity} units)"
                } else {
                    " — financial entry only, stock not auto-incremented"
                }
                state.copy(
                    isReceivePurchaseDialogOpen = false,
                    selectedSupplierForDetail = state.suppliers.firstOrNull { it.id == supplierId },
                    lastSupplierActionMessageAr = "تم قيد فاتورة الشراء $billCode (المتبقي للمورد: $remStr)$stockNoteAr",
                    lastSupplierActionMessageEn = "Purchase bill $billCode recorded (Payable added: $remStr)$stockNoteEn",
                    isSupplierActionError = false
                )
            }
            true
        } else {
            val rawErr = result.exceptionOrNull()?.message ?: "Purchase bill rejected"
            val parts = rawErr.split(" | ", limit = 2)
            val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            _uiState.update {
                it.copy(
                    lastSupplierActionMessageAr = errAr,
                    lastSupplierActionMessageEn = errEn,
                    isSupplierActionError = true
                )
            }
            showBanner(errAr, errEn, isError = true)
            false
        }
    }

    fun recordStructuredPurchaseBill(
        supplierId: String,
        lines: List<PurchaseLineItem>,
        paidNowIls: Double,
        note: String = ""
    ): Result<PurchaseBill> {
        val session = _uiState.value.currentSession
        val result = repository.recordStructuredPurchaseBill(supplierId, lines, paidNowIls, session, note)
        if (result.isSuccess) {
            val created = result.getOrNull()
            val updatedSuppliers = repository.getSuppliers()
            val updatedBills = repository.getPurchaseBills()
            val updatedSup = updatedSuppliers.firstOrNull { it.id == supplierId }
            _uiState.update { state ->
                state.copy(
                    suppliers = updatedSuppliers,
                    purchaseBills = updatedBills,
                    selectedSupplierForDetail = updatedSup ?: state.selectedSupplierForDetail,
                    lastSupplierActionMessageAr = "تم اعتماد فاتورة الشراء #${created?.billNumber ?: ""} بنجاح",
                    lastSupplierActionMessageEn = "Purchase bill #${created?.billNumber ?: ""} recorded successfully",
                    isSupplierActionError = false
                )
            }
        } else {
            val rawErr = result.exceptionOrNull()?.message ?: "Purchase bill rejected"
            val parts = rawErr.split(" | ", limit = 2)
            val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            _uiState.update {
                it.copy(
                    lastSupplierActionMessageAr = errAr,
                    lastSupplierActionMessageEn = errEn,
                    isSupplierActionError = true
                )
            }
            showBanner(errAr, errEn, isError = true)
        }
        return result
    }

    fun paySupplierAmountResult(
        supplierId: String,
        amountIls: Double,
        note: String = "سداد نقدي للمورد من الصندوق",
        paymentId: String = ""
    ): Result<SupplierPayment> {
        val session = _uiState.value.currentSession
        val result = repository.recordSupplierPayment(supplierId, amountIls, note, session, paymentId)
        if (result.isSuccess) {
            val updatedSuppliers = repository.getSuppliers()
            val updatedBills = repository.getPurchaseBills()
            val updatedPayments = repository.getSupplierPayments()
            val updatedSup = updatedSuppliers.firstOrNull { it.id == supplierId }
            val paidStr = WesternFormatters.formatIls(amountIls)
            val newOwed = updatedSup?.balanceOwedIls ?: 0.0
            val msgAr = when {
                newOwed > 0.005 -> "تم صرف $paidStr للمورد — المتبقي له علينا: ${WesternFormatters.formatIls(newOwed)}"
                newOwed < -0.005 -> "تم صرف $paidStr للمورد — أصبح لنا دفعة مقدمة عنده: ${WesternFormatters.formatIls(-newOwed)}"
                else -> "تم صرف $paidStr للمورد — أصبح حساب المورد خالصاً (0.00 ₪)"
            }
            val msgEn = when {
                newOwed > 0.005 -> "Paid $paidStr to supplier — Remaining payable: ${WesternFormatters.formatIls(newOwed)}"
                newOwed < -0.005 -> "Paid $paidStr to supplier — Store prepayment credit: ${WesternFormatters.formatIls(-newOwed)}"
                else -> "Paid $paidStr to supplier — Supplier account settled (0.00 ₪)"
            }
            _uiState.update { state ->
                state.copy(
                    suppliers = updatedSuppliers,
                    purchaseBills = updatedBills,
                    supplierPayments = updatedPayments,
                    selectedSupplierForDetail = updatedSup ?: state.selectedSupplierForDetail,
                    lastSupplierActionMessageAr = msgAr,
                    lastSupplierActionMessageEn = msgEn,
                    isSupplierActionError = false
                )
            }
        } else {
            val rawErr = result.exceptionOrNull()?.message ?: "Supplier disbursement rejected"
            val parts = rawErr.split(" | ", limit = 2)
            val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            _uiState.update {
                it.copy(
                    lastSupplierActionMessageAr = errAr,
                    lastSupplierActionMessageEn = errEn,
                    isSupplierActionError = true
                )
            }
            showBanner(errAr, errEn, isError = true)
        }
        return result
    }

    fun paySupplierAmount(
        supplierId: String,
        amountIls: Double,
        note: String = "سداد نقدي للمورد من الصندوق",
        paymentId: String = ""
    ): Boolean = paySupplierAmountResult(supplierId, amountIls, note, paymentId).isSuccess

    // Expenses & Cash Outflow
    fun openAddExpenseDialog(open: Boolean) {
        if (open && !_uiState.value.currentSession.canManageExpenses()) return
        _uiState.update { it.copy(isAddExpenseDialogOpen = open) }
    }

    fun dismissExpenseFeedback() {
        _uiState.update {
            it.copy(
                lastExpenseActionMessageAr = null,
                lastExpenseActionMessageEn = null,
                isExpenseActionError = false
            )
        }
    }

    fun addExpense(title: String, category: ExpenseCategory, amountIls: Double): Boolean {
        val result = repository.recordExpense(title, category, amountIls, _uiState.value.currentSession)
        return if (result.isSuccess) {
            val recorded = result.getOrNull()
            syncExpenses()
            val amtStr = WesternFormatters.formatIls(recorded?.amountIls ?: amountIls)
            _uiState.update {
                it.copy(
                    isAddExpenseDialogOpen = false,
                    lastExpenseActionMessageAr = "تم تسجيل وصرف $amtStr (${category.labelAr}) من نقد الصندوق لليوم",
                    lastExpenseActionMessageEn = "Recorded and disbursed $amtStr (${category.labelEn}) from today's cash drawer",
                    isExpenseActionError = false
                )
            }
            true
        } else {
            val rawErr = result.exceptionOrNull()?.message ?: "Expense rejected"
            val parts = rawErr.split(" | ", limit = 2)
            val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            _uiState.update {
                it.copy(
                    lastExpenseActionMessageAr = errAr,
                    lastExpenseActionMessageEn = errEn,
                    isExpenseActionError = true
                )
            }
            showBanner(errAr, errEn, isError = true)
            false
        }
    }

    // Bluetooth Thermal Printer Simulation & Document Sharing
    fun setPrinterSheetOpen(open: Boolean) {
        _uiState.update { it.copy(isPrinterSheetOpen = open) }
    }

    fun selectPaperWidth(widthMm: Int) {
        _uiState.update { state ->
            val printer = state.connectedPrinter
            state.copy(
                receiptPaperWidthMm = widthMm,
                printProgressStepAr = if (printer != null) {
                    "وضع المحاكاة: تم ضبط عرض الورق ${widthMm}mm (${printer.name})"
                } else {
                    "وضع المحاكاة: تم ضبط عرض المعاينة ${widthMm}mm (لا توجد طابعة محددة)"
                },
                printProgressStepEn = if (printer != null) {
                    "Simulation mode: Paper width set to ${widthMm}mm (${printer.name})"
                } else {
                    "Simulation mode: Preview width set to ${widthMm}mm (no printer selected)"
                }
            )
        }
    }

    fun scanBluetoothPrinters() {
        printJob?.cancel()
        _uiState.update { state ->
            val ready = state.connectedPrinter != null && state.connectedPrinter.isPaired
            val pairedCount = state.availablePrinters.count { it.isPaired }
            state.copy(
                printerStatus = if (ready) PrinterStatus.READY else PrinterStatus.DISCONNECTED,
                printProgressStepAr = "وضع المحاكاة: ${state.availablePrinters.size} أجهزة افتراضية (${pairedCount} متاحة، 1 خارج النطاق)",
                printProgressStepEn = "Simulation mode: ${state.availablePrinters.size} virtual devices (${pairedCount} available, 1 offline)"
            )
        }
    }

    fun connectBluetoothPrinter(device: BluetoothPrinterDevice) {
        printJob?.cancel()
        if (!device.isPaired) {
            _uiState.update {
                it.copy(
                    connectedPrinter = null,
                    printerStatus = PrinterStatus.ERROR,
                    printProgressFraction = 0f,
                    printProgressStepAr = "تعذر الاتصال المحاكى بـ ${device.name} (خارج التغطية / غير مقترنة) — اختر طابعة متاحة أو أعد المحاولة",
                    printProgressStepEn = "Simulated connection to ${device.name} failed (offline / unpaired) — select an available printer or retry"
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                connectedPrinter = device,
                receiptPaperWidthMm = device.paperWidthMm,
                printerStatus = PrinterStatus.READY,
                printProgressFraction = 0f,
                printProgressStepAr = "وضع المحاكاة: متصل افتراضياً بـ ${device.name} (${device.paperWidthMm}mm)",
                printProgressStepEn = "Simulation mode: Virtually connected to ${device.name} (${device.paperWidthMm}mm)"
            )
        }
    }

    fun disconnectBluetoothPrinter() {
        printJob?.cancel()
        _uiState.update {
            it.copy(
                connectedPrinter = null,
                printerStatus = PrinterStatus.DISCONNECTED,
                printProgressFraction = 0f,
                printProgressStepAr = "وضع المحاكاة: غير متصل بأي طابعة — اختر طابعة أو شارك الإيصال كنص",
                printProgressStepEn = "Simulation mode: No printer selected — select a printer or share receipt text"
            )
        }
    }

    fun simulateThermalPrint() {
        val state = _uiState.value
        val activeReceipt = state.activeReceipt ?: state.invoices.firstOrNull() ?: return
        val printer = state.connectedPrinter

        printJob?.cancel()
        if (printer == null || !printer.isPaired) {
            _uiState.update {
                it.copy(
                    printerStatus = PrinterStatus.ERROR,
                    printProgressFraction = 0f,
                    printProgressStepAr = "تعذرت محاكاة الطباعة: لا توجد طابعة متصلة. اختر طابعة من الإعدادات أو شارك النص.",
                    printProgressStepEn = "Simulated print failed: No printer connected. Select a printer in settings or share text."
                )
            }
            return
        }

        _uiState.update { current ->
            val updatedReceipt = activeReceipt.copy(printCount = activeReceipt.printCount + 1)
            val updatedInvoices = current.invoices.map { inv ->
                if (inv.id == updatedReceipt.id) updatedReceipt else inv
            }
            current.copy(
                activeReceipt = updatedReceipt,
                invoices = updatedInvoices,
                printerStatus = PrinterStatus.PRINTED_SUCCESS,
                printProgressFraction = 1.0f,
                printProgressStepAr = "تمت محاكاة الطباعة (#${updatedReceipt.printCount}) عبر ${printer.name} (${current.receiptPaperWidthMm}mm)",
                printProgressStepEn = "Simulated print (#${updatedReceipt.printCount}) completed via ${printer.name} (${current.receiptPaperWidthMm}mm)"
            )
        }
    }

    fun formatReceiptShareText(receipt: InvoiceRecord? = _uiState.value.activeReceipt ?: _uiState.value.invoices.firstOrNull()): String {
        val target = receipt ?: return ""
        val state = _uiState.value
        val lang = state.language
        val business = state.businessInfo
        val sb = StringBuilder()

        val docTitle = if (target.isPaidInFull) {
            lang.tr("إيصال قبض وفاتورة مبيعات نقدية", "Cash Sales Invoice & Receipt")
        } else {
            lang.tr("فاتورة مبيعات آجلة (ذمة على الحساب)", "Credit Sales Invoice (On Account)")
        }

        sb.appendLine(business.name(lang))
        sb.appendLine("${business.city(lang)} • ${business.phone}")
        sb.appendLine("--------------------------------")
        sb.appendLine("$docTitle: ${WesternFormatters.formatInvoiceCode(target.invoiceNumber)}")
        sb.appendLine("${lang.tr("التاريخ", "Date")}: ${target.timestamp}")
        sb.appendLine("${lang.tr("الزبون", "Customer")}: ${target.customerNote}")
        sb.appendLine("${lang.tr("الكاشير", "Cashier")}: ${lang.tr(target.cashierNameAr, target.cashierNameEn)}")
        sb.appendLine("--------------------------------")
        target.items.forEachIndexed { idx, item ->
            sb.appendLine(
                "${idx + 1}. ${item.product.name(lang)} (${WesternFormatters.formatInt(item.quantity)} × ${WesternFormatters.formatIls(item.product.priceIls)}) = ${WesternFormatters.formatIls(item.lineTotal)}"
            )
        }
        sb.appendLine("--------------------------------")
        if (target.discountIls > 0.005) {
            sb.appendLine("${lang.tr("المجموع الفرعي", "Subtotal")}: ${WesternFormatters.formatIls(target.subtotalIls)}")
            sb.appendLine("${lang.tr("الخصم", "Discount")}: -${WesternFormatters.formatIls(target.discountIls)}")
        }
        sb.appendLine("${lang.tr("الصافي المطلوب", "Net Total")}: ${WesternFormatters.formatIls(target.netTotalIls)}")
        if (target.isPaidInFull) {
            sb.appendLine("${lang.tr("الحالة", "Status")}: ${lang.tr("مدفوع نقداً بالكامل", "Paid in Full (Cash)")}")
            sb.appendLine("${lang.tr("المقبوض نقداً", "Cash Received")}: ${WesternFormatters.formatIls(target.cashReceivedIls)}")
            sb.appendLine("${lang.tr("الباقي للزبون", "Change Returned")}: ${WesternFormatters.formatIls(target.changeReturnedIls)}")
        } else {
            sb.appendLine("${lang.tr("الحالة", "Status")}: ${lang.tr("ذمة آجلة على حساب الزبون", "Unpaid — On Customer Account")}")
            sb.appendLine("${lang.tr("المدفوع عند الإصدار", "Cash Paid at Issue")}: ${WesternFormatters.formatIls(target.cashReceivedIls)}")
            sb.appendLine("${lang.tr("المتبقي بالذمة", "Balance Due on Account")}: ${WesternFormatters.formatIls(target.remainingDebitIls)}")
        }
        return sb.toString().trim()
    }

    fun recordReceiptShare(receipt: InvoiceRecord? = _uiState.value.activeReceipt ?: _uiState.value.invoices.firstOrNull()): String {
        val text = formatReceiptShareText(receipt)
        if (text.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    receiptShareCount = it.receiptShareCount + 1,
                    lastSharedReceiptText = text
                )
            }
        }
        return text
    }

    fun recordReceiptCopy(receipt: InvoiceRecord? = _uiState.value.activeReceipt ?: _uiState.value.invoices.firstOrNull()): String {
        val text = formatReceiptShareText(receipt)
        if (text.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    receiptCopyCount = it.receiptCopyCount + 1,
                    lastSharedReceiptText = text
                )
            }
        }
        return text
    }

    // =========================================================================
    // Interactive Demo Extensions: Product CRUD, Drafts, Quotations, GRN & Shift
    // =========================================================================

    fun saveProduct(
        productId: String?,
        nameAr: String,
        nameEn: String = nameAr,
        category: ProductCategory = ProductCategory.PANTRY,
        unitAr: String = "قطعة",
        unitEn: String = unitAr,
        barcode: String = "",
        priceIls: Double,
        costIls: Double = 0.0,
        minStockThreshold: Int = 5,
        initialStock: Int = 0,
        warehouseAr: String = "المستودع الرئيسي - نابلس",
        lotBatchCode: String = "LOT-2026-A",
        expiryDate: String? = null,
        isWeightedDisplayOnly: Boolean = false
    ): Boolean {
        val session = _uiState.value.currentSession
        val result = repository.saveProduct(
            productId = productId,
            nameAr = nameAr,
            nameEn = nameEn,
            category = category,
            unitAr = unitAr,
            unitEn = unitEn,
            barcode = barcode,
            priceIls = priceIls,
            costIls = costIls,
            minStockThreshold = minStockThreshold,
            initialStock = initialStock,
            warehouseAr = warehouseAr,
            lotBatchCode = lotBatchCode,
            expiryDate = expiryDate,
            isWeightedDisplayOnly = isWeightedDisplayOnly,
            operator = session
        )
        return if (result.isSuccess) {
            val saved = result.getOrNull()
            syncFromRepository()
            _uiState.update { state ->
                val actionAr = if (productId == null) "تمت إضافة الصنف «${saved?.nameAr}» إلى الكتالوج" else "تم حفظ تعديلات الصنف «${saved?.nameAr}»"
                val actionEn = if (productId == null) "Added product \"${saved?.nameEn}\" to catalog" else "Saved changes for \"${saved?.nameEn}\""
                state.copy(
                    selectedProductForDetail = saved ?: state.selectedProductForDetail,
                    lastInventoryActionMessageAr = actionAr,
                    lastInventoryActionMessageEn = actionEn,
                    isInventoryActionError = false
                )
            }
            true
        } else {
            val rawErr = result.exceptionOrNull()?.message ?: "Product save failed"
            val parts = rawErr.split(" | ", limit = 2)
            val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            _uiState.update {
                it.copy(
                    lastInventoryActionMessageAr = errAr,
                    lastInventoryActionMessageEn = errEn,
                    isInventoryActionError = true
                )
            }
            showBanner(errAr, errEn, isError = true)
            false
        }
    }

    fun saveCurrentBasketAsDraft(customTitle: String = ""): Boolean {
        val state = _uiState.value
        if (state.basket.isEmpty()) return false
        val linkedCust = state.selectedCustomer
        val fallbackAr = linkedCust?.let { "مسودة: ${it.nameAr}" }
            ?: state.customerNote.trim().takeIf { it.isNotEmpty() }?.let { "مسودة: $it" }
            ?: "مسودة بيع (${WesternFormatters.formatInt(state.basketItemCount)} وحدات)"
        val fallbackEn = linkedCust?.let { "Draft: ${it.nameEn}" }
            ?: state.customerNote.trim().takeIf { it.isNotEmpty() }?.let { "Draft: $it" }
            ?: "Sale Draft (${WesternFormatters.formatInt(state.basketItemCount)} units)"
        val cleanTitle = customTitle.trim()
        val draft = SaleDraft(
            id = "draft_${System.currentTimeMillis()}",
            titleAr = cleanTitle.ifEmpty { fallbackAr },
            titleEn = cleanTitle.ifEmpty { fallbackEn },
            timestamp = WesternFormatters.currentTimestamp(),
            items = state.basket,
            customerId = state.selectedCustomerId,
            customerNote = state.customerNote,
            discountPreset = state.discountPreset,
            customDiscountText = state.customDiscountText,
            subtotalIls = state.basketSubtotalIls,
            netTotalIls = state.basketNetTotalIls
        )
        _uiState.update { current ->
            current.copy(
                savedDrafts = listOf(draft) + current.savedDrafts,
                basket = emptyList(),
                saleSessionId = current.saleSessionId + 1,
                discountPreset = DiscountPreset.NONE,
                customDiscountText = "",
                customerNote = "",
                selectedCustomerId = null,
                cashInputText = "",
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
        showBanner(
            ar = "تم حفظ السلة كمسودة بيع: ${draft.titleAr}",
            en = "Saved basket as sale draft: ${draft.titleEn}",
            isError = false
        )
        return true
    }

    fun resumeSaleDraft(draftId: String): Boolean {
        val state = _uiState.value
        val draft = state.savedDrafts.firstOrNull { it.id == draftId } ?: return false
        val restoredItems = draft.items.mapNotNull { item ->
            val freshProd = state.products.firstOrNull { it.id == item.product.id } ?: item.product
            if (freshProd.isStockTracked) {
                val allowedQty = item.quantity.coerceAtMost(freshProd.stockCount)
                if (allowedQty > 0) BasketItem(freshProd, allowedQty) else null
            } else {
                BasketItem(freshProd, item.quantity)
            }
        }
        if (restoredItems.isEmpty()) {
            showBanner(
                ar = "تعذر استئناف المسودة لعدم توفر مخزون للأصناف المحفوظة",
                en = "Cannot resume draft: saved items are currently out of stock",
                isError = true
            )
            return false
        }
        _uiState.update { current ->
            current.copy(
                basket = restoredItems,
                saleSessionId = current.saleSessionId + 1,
                savedDrafts = current.savedDrafts.filterNot { it.id == draftId },
                selectedCustomerId = draft.customerId,
                customerNote = draft.customerNote,
                discountPreset = draft.discountPreset,
                customDiscountText = draft.customDiscountText,
                cashInputText = "",
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
        showBanner(
            ar = "تم استئناف المسودة: ${draft.titleAr}",
            en = "Resumed sale draft: ${draft.titleEn}",
            isError = false
        )
        return true
    }

    fun deleteSaleDraft(draftId: String) {
        _uiState.update { current ->
            current.copy(savedDrafts = current.savedDrafts.filterNot { it.id == draftId })
        }
    }

    fun formatQuotationShareText(): String {
        val state = _uiState.value
        if (state.basket.isEmpty()) return ""
        val lang = state.language
        val b = state.businessInfo
        val custName = state.selectedCustomer?.name(lang)
            ?: state.customerNote.trim().takeIf { it.isNotEmpty() }
            ?: lang.tr("زبون كريم", "Valued Customer")
        val sb = StringBuilder()
        sb.appendLine(b.name(lang))
        sb.appendLine("${b.city(lang)} • ${b.phone}")
        sb.appendLine("--------------------------------")
        sb.appendLine(lang.tr("عرض سعر تجاري (وثيقة غير مرحلة محاسبياً)", "Price Quotation (Non-Posting Commercial Document)"))
        sb.appendLine("${lang.tr("التاريخ", "Date")}: ${WesternFormatters.currentTimestamp()}")
        sb.appendLine("${lang.tr("موجه إلى", "Prepared for")}: $custName")
        sb.appendLine("--------------------------------")
        state.basket.forEachIndexed { idx, item ->
            sb.appendLine(
                "${idx + 1}. ${item.product.name(lang)} (${WesternFormatters.formatInt(item.quantity)} × ${WesternFormatters.formatIls(item.product.priceIls)}) = ${WesternFormatters.formatIls(item.lineTotal)}"
            )
        }
        sb.appendLine("--------------------------------")
        if (state.calculatedDiscountIls > 0.005) {
            sb.appendLine("${lang.tr("المجموع الفرعي", "Subtotal")}: ${WesternFormatters.formatIls(state.basketSubtotalIls)}")
            sb.appendLine("${lang.tr("الخصم المقترح", "Proposed Discount")}: -${WesternFormatters.formatIls(state.calculatedDiscountIls)}")
        }
        sb.appendLine("${lang.tr("الصافي الإجمالي لعرض السعر", "Quoted Net Total")}: ${WesternFormatters.formatIls(state.basketNetTotalIls)}")
        sb.appendLine(lang.tr("* ملاحظة: عرض السعر لا يخصم من المخزون ولا يقيد ذمة مالية حتى اعتماد البيع.", "* Note: This quotation does not deduct stock or post financial debt until confirmed as a sale."))
        return sb.toString().trim()
    }

    fun updateCustomer(
        customerId: String,
        name: String,
        phone: String,
        address: String,
        creditLimitIls: Double = 1000.0
    ): Boolean {
        val result = repository.updateCustomer(customerId, name, phone, address, creditLimitIls)
        return if (result.isSuccess) {
            val updated = result.getOrNull()
            syncFromRepository()
            _uiState.update { state ->
                state.copy(
                    selectedCustomerForDetail = updated ?: state.selectedCustomerForDetail,
                    lastCustomerPaymentMessageAr = "تم تحديث بيانات الزبون «${updated?.nameAr}» وسقف الائتمان (${WesternFormatters.formatIls(creditLimitIls)})",
                    lastCustomerPaymentMessageEn = "Updated customer \"${updated?.nameEn}\" and credit limit (${WesternFormatters.formatIls(creditLimitIls)})"
                )
            }
            true
        } else {
            val err = result.exceptionOrNull()?.message ?: "Customer update failed"
            showBanner(err, err, isError = true)
            false
        }
    }

    fun formatCustomerStatementText(customer: Customer): String {
        val state = _uiState.value
        val lang = state.language
        val b = state.businessInfo
        val txs = state.customerTransactions.filter { it.customerId == customer.id }
        val sb = StringBuilder()
        sb.appendLine(b.name(lang))
        sb.appendLine("${b.city(lang)} • ${b.phone}")
        sb.appendLine("--------------------------------")
        sb.appendLine(lang.tr("كشف حساب زبون تفصيلي", "Customer Account Statement"))
        sb.appendLine("${lang.tr("الزبون", "Customer")}: ${customer.name(lang)} (${customer.phone})")
        sb.appendLine("${lang.tr("تاريخ الكشف", "Statement Date")}: ${WesternFormatters.currentTimestamp()}")
        val balStr = when {
            customer.balanceIls > 0.005 -> lang.tr("ذمة مستحقة للمتجر: ${WesternFormatters.formatIls(customer.balanceIls)}", "Balance Due to Store: ${WesternFormatters.formatIls(customer.balanceIls)}")
            customer.balanceIls < -0.005 -> lang.tr("رصيد مقدم للزبون: ${WesternFormatters.formatIls(-customer.balanceIls)}", "Customer Advance Credit: ${WesternFormatters.formatIls(-customer.balanceIls)}")
            else -> lang.tr("الحساب خالص (0.00 ₪)", "Settled Account (0.00 ₪)")
        }
        sb.appendLine("${lang.tr("الرصيد الحالي", "Current Balance")}: $balStr")
        sb.appendLine("${lang.tr("سقف الائتمان", "Credit Limit")}: ${WesternFormatters.formatIls(customer.creditLimitIls)}")
        sb.appendLine("--------------------------------")
        txs.take(10).forEach { tx ->
            val mov = if (tx.debitIls > 0 && tx.creditIls == 0.0) {
                "+${WesternFormatters.formatIls(tx.debitIls)}"
            } else if (tx.creditIls > 0 && tx.debitIls == 0.0) {
                "-${WesternFormatters.formatIls(tx.creditIls)}"
            } else if (tx.debitIls > 0 && tx.creditIls > 0 && tx.debitIls > tx.creditIls + 0.005) {
                val netDebit = tx.debitIls - tx.creditIls
                lang.tr(
                    "إجمالي ${WesternFormatters.formatIls(tx.debitIls)} / مدفوع ${WesternFormatters.formatIls(tx.creditIls)} / متبقي +${WesternFormatters.formatIls(netDebit)}",
                    "Total ${WesternFormatters.formatIls(tx.debitIls)} / Paid ${WesternFormatters.formatIls(tx.creditIls)} / Rem +${WesternFormatters.formatIls(netDebit)}"
                )
            } else {
                WesternFormatters.formatIls(tx.debitIls)
            }
            sb.appendLine("${tx.date} | ${tx.type(lang)} | $mov | ${lang.tr("الرصيد", "Bal")}: ${WesternFormatters.formatIls(tx.runningBalanceIls)}")
        }
        return sb.toString().trim()
    }

    fun saveSupplier(
        supplierId: String?,
        name: String,
        company: String,
        phone: String
    ): Boolean {
        val session = _uiState.value.currentSession
        val result = repository.saveSupplier(supplierId, name, company, phone, session)
        return if (result.isSuccess) {
            val saved = result.getOrNull()
            syncFromRepository()
            _uiState.update { state ->
                val msgAr = if (supplierId == null) "تمت إضافة المورد «${saved?.nameAr}»" else "تم تحديث بيانات المورد «${saved?.nameAr}»"
                val msgEn = if (supplierId == null) "Added supplier \"${saved?.nameEn}\"" else "Updated supplier \"${saved?.nameEn}\""
                state.copy(
                    selectedSupplierForDetail = saved ?: state.selectedSupplierForDetail,
                    lastSupplierActionMessageAr = msgAr,
                    lastSupplierActionMessageEn = msgEn,
                    isSupplierActionError = false
                )
            }
            true
        } else {
            val rawErr = result.exceptionOrNull()?.message ?: "Supplier save failed"
            val parts = rawErr.split(" | ", limit = 2)
            val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            _uiState.update {
                it.copy(
                    lastSupplierActionMessageAr = errAr,
                    lastSupplierActionMessageEn = errEn,
                    isSupplierActionError = true
                )
            }
            showBanner(errAr, errEn, isError = true)
            false
        }
    }

    fun confirmPhysicalGoodsReceiptResult(
        billId: String,
        productId: String,
        receivedQty: Int,
        batchNote: String = "",
        operationId: String = "",
        lineId: String? = null
    ): Result<StockMovement> {
        val session = _uiState.value.currentSession
        val result = repository.confirmPhysicalGoodsReceipt(billId, productId, receivedQty, batchNote, session, operationId, lineId)
        if (result.isSuccess) {
            val movement = result.getOrNull()
            val updatedProducts = repository.getProducts()
            val updatedSuppliers = repository.getSuppliers()
            val updatedBills = repository.getPurchaseBills()
            val prod = updatedProducts.firstOrNull { it.id == productId }
            _uiState.update { state ->
                state.copy(
                    products = updatedProducts,
                    stockMovements = repository.getStockMovements(),
                    suppliers = updatedSuppliers,
                    purchaseBills = updatedBills,
                    supplierPayments = repository.getSupplierPayments(),
                    lastSupplierActionMessageAr = "تم اعتماد سند الاستلام المخزني (+${WesternFormatters.formatInt(receivedQty)} من «${prod?.nameAr ?: ""}») — المخزون الحالي: ${WesternFormatters.formatInt(movement?.resultingStock ?: 0)}",
                    lastSupplierActionMessageEn = "Physical Goods Receipt confirmed (+${WesternFormatters.formatInt(receivedQty)} of \"${prod?.nameEn ?: ""}\") — Stock: ${WesternFormatters.formatInt(movement?.resultingStock ?: 0)}",
                    isSupplierActionError = false
                )
            }
        } else {
            val rawErr = result.exceptionOrNull()?.message ?: "Goods receipt rejected"
            val parts = rawErr.split(" | ", limit = 2)
            val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            _uiState.update {
                it.copy(
                    lastSupplierActionMessageAr = errAr,
                    lastSupplierActionMessageEn = errEn,
                    isSupplierActionError = true
                )
            }
            showBanner(errAr, errEn, isError = true)
        }
        return result
    }

    fun confirmPhysicalGoodsReceipt(
        billId: String,
        productId: String,
        receivedQty: Int,
        batchNote: String = "",
        operationId: String = "",
        lineId: String? = null
    ): Boolean = confirmPhysicalGoodsReceiptResult(billId, productId, receivedQty, batchNote, operationId, lineId).isSuccess

    fun updateStartingDrawerCash(openingIls: Double): Boolean {
        val session = _uiState.value.currentSession
        if (!session.canViewReports()) return false
        if (openingIls < 0.0) return false
        val exactOpening = BigDecimal(openingIls.toString()).setScale(2, RoundingMode.HALF_UP).toDouble()
        _uiState.update {
            it.copy(
                startingDrawerCashIls = exactOpening,
                isShiftClosedPreview = false,
                shiftClosedTimestamp = null
            )
        }
        return true
    }

    fun updateCountedPhysicalCashText(rawInput: String) {
        val sanitized = WesternFormatters.sanitizeWesternDigits(rawInput)
        _uiState.update {
            it.copy(countedPhysicalCashText = sanitized)
        }
    }

    fun toggleShiftClosePreview(closed: Boolean) {
        _uiState.update {
            it.copy(
                isShiftClosedPreview = closed,
                shiftClosedTimestamp = if (closed) WesternFormatters.currentTimestamp() else null
            )
        }
    }

    private fun showBanner(ar: String, en: String, isError: Boolean = false) {
        _uiState.update {
            it.copy(
                lastScannedBannerAr = ar,
                lastScannedBannerEn = en,
                isBannerError = isError
            )
        }
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(4000)
            _uiState.update {
                it.copy(lastScannedBannerAr = null, lastScannedBannerEn = null, isBannerError = false)
            }
        }
    }
}
