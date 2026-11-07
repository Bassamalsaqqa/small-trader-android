package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.model.BluetoothPrinterDevice
import com.example.model.PrinterStatus
import com.example.model.Product
import com.example.model.TraderScreen
import com.example.model.WesternFormatters
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmallTraderTopBar(
    state: TraderUiState,
    onNavigateBack: () -> Unit,
    onNavigateTo: (TraderScreen) -> Unit = {},
    onToggleLanguage: () -> Unit,
    onToggleTheme: () -> Unit = {},
    onOpenScanner: () -> Unit,
    onOpenPrinter: () -> Unit
) {
    val lang = state.language
    var accountOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(if (state.currentScreen == TraderScreen.HOME) state.businessInfo.name(lang)
            else lang.tr(when (state.currentScreen) {
                TraderScreen.PEOPLE -> "الحسابات"
                TraderScreen.PURCHASES -> "المشتريات"
                TraderScreen.NEW_SALE -> "بيع جديد"
                TraderScreen.INVOICES_HISTORY -> "المبيعات"
                TraderScreen.PRODUCTS_CATALOG -> "المخزون"
                TraderScreen.CASH_PAYMENT -> "الدفع"
                TraderScreen.INVOICE_REVIEW -> "خيارات البيع"
                TraderScreen.CUSTOMERS -> "الزبائن"
                TraderScreen.CUSTOMER_DETAIL -> "حساب الزبون"
                TraderScreen.SUPPLIERS -> "الموردون"
                TraderScreen.SUPPLIER_DETAIL -> "حساب المورد"
                TraderScreen.EXPENSES -> "المصاريف"
                TraderScreen.FINANCIAL_REPORTS -> "التقارير والصندوق"
                TraderScreen.USER_PROFILE -> "الملف الشخصي"
                TraderScreen.BUSINESS_SETTINGS -> "الإعدادات"
                TraderScreen.PRODUCT_DETAIL -> "الصنف"
                TraderScreen.RECEIPT -> "الإيصال"
                else -> "التاجر الصغير"
            }, when (state.currentScreen) {
                TraderScreen.PEOPLE -> "People"
                TraderScreen.PURCHASES -> "Purchases"
                TraderScreen.NEW_SALE -> "New sale"
                TraderScreen.INVOICES_HISTORY -> "Sales"
                TraderScreen.PRODUCTS_CATALOG -> "Inventory"
                TraderScreen.CASH_PAYMENT -> "Payment"
                TraderScreen.INVOICE_REVIEW -> "Sale options"
                TraderScreen.CUSTOMERS -> "Customers"
                TraderScreen.CUSTOMER_DETAIL -> "Customer account"
                TraderScreen.SUPPLIERS -> "Suppliers"
                TraderScreen.SUPPLIER_DETAIL -> "Supplier account"
                TraderScreen.EXPENSES -> "Expenses"
                TraderScreen.FINANCIAL_REPORTS -> "Reports and drawer"
                TraderScreen.USER_PROFILE -> "Profile"
                TraderScreen.BUSINESS_SETTINGS -> "Settings"
                TraderScreen.PRODUCT_DETAIL -> "Product"
                TraderScreen.RECEIPT -> "Receipt"
                else -> "Small Trader"
            }), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (!state.isRootDestination) IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("top_bar_back_button")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, lang.tr("رجوع", "Back"))
            }
        },
        actions = {
            IconButton(onClick = { accountOpen = true }, modifier = Modifier.testTag("top_bar_account_button")) {
                Icon(Icons.Default.AccountCircle, lang.tr("الحساب والإعدادات", "Account and settings"))
            }
        }
    )
    if (accountOpen) {
        ModalBottomSheet(onDismissRequest = { accountOpen = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(state.businessInfo.name(lang), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onToggleLanguage) { Text(lang.tr("English", "العربية")) }
                TextButton(onClick = onToggleTheme) { Text(lang.tr("المظهر", "Appearance")) }
                fun go(screen: TraderScreen) { accountOpen = false; onNavigateTo(screen) }
                TextButton(onClick = { go(TraderScreen.USER_PROFILE) }) { Text(lang.tr("الملف والصلاحيات", "Profile and role")) }
                TextButton(onClick = { go(TraderScreen.BUSINESS_SETTINGS) }) { Text(lang.tr("الإعدادات", "Settings")) }
                if (state.currentSession.canViewReports()) TextButton(onClick = { go(TraderScreen.FINANCIAL_REPORTS) }) { Text(lang.tr("التقارير والصندوق", "Reports and drawer")) }
                if (state.currentSession.canManageExpenses()) TextButton(onClick = { go(TraderScreen.EXPENSES) }) { Text(lang.tr("المصاريف", "Expenses")) }
                TextButton(onClick = { accountOpen = false; onOpenPrinter() }) { Text(lang.tr("معاينة الطابعة • محاكاة", "Printer preview • simulation")) }
                var showAbout by remember { mutableStateOf(false) }
                TextButton(onClick = { showAbout = !showAbout }) { Text(lang.tr("عن التطبيق", "About")) }
                if (showAbout) Text(lang.tr("نسخة تجريبية. البيانات بتنمسح عند إعادة التشغيل.", "Demo. Data resets on restart."))
            }
        }
    }
}

@Composable
private fun CheckoutStepSubBar(
    currentScreen: TraderScreen,
    basketItemCount: Int,
    lang: AppLanguage,
    onNavigateTo: (TraderScreen) -> Unit
) {
    val canAdvanceFromItems = basketItemCount > 0
    val steps = listOf(
        Triple(
            TraderScreen.NEW_SALE,
            lang.tr(
                "1. الأصناف (${WesternFormatters.formatInt(basketItemCount)})",
                "1. Items (${WesternFormatters.formatInt(basketItemCount)})"
            ),
            "checkout_step_items"
        ),
        Triple(
            TraderScreen.INVOICE_REVIEW,
            lang.tr("2. المراجعة", "2. Review"),
            "checkout_step_review"
        ),
        Triple(
            TraderScreen.CASH_PAYMENT,
            lang.tr("3. الدفع", "3. Payment"),
            "checkout_step_payment"
        )
    )

    val currentStepIndex = when (currentScreen) {
        TraderScreen.NEW_SALE -> 0
        TraderScreen.INVOICE_REVIEW -> 1
        TraderScreen.CASH_PAYMENT -> 2
        else -> 0
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            steps.forEachIndexed { index, (targetScreen, label, tag) ->
                val isCurrent = index == currentStepIndex
                val isCompleted = index < currentStepIndex
                val isEnabled = index == 0 || canAdvanceFromItems

                Surface(
                    onClick = {
                        if (isEnabled && targetScreen != currentScreen) {
                            onNavigateTo(targetScreen)
                        }
                    },
                    enabled = isEnabled,
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        isCurrent -> MaterialTheme.colorScheme.primary
                        isCompleted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                        else -> Color.Transparent
                    },
                    border = if (!isCurrent && !isCompleted) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    } else null,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag(tag)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isCurrent || isCompleted) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = when {
                                isCurrent -> MaterialTheme.colorScheme.onPrimary
                                isCompleted -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreOperationsSubBar(
    currentScreen: TraderScreen,
    canViewReports: Boolean,
    lang: AppLanguage,
    onNavigateTo: (TraderScreen) -> Unit
) {
    val modules = buildList {
        add(Triple(TraderScreen.PRODUCTS_CATALOG, lang.tr("المخزون", "Inventory"), "store_nav_catalog"))
        add(Triple(TraderScreen.SUPPLIERS, lang.tr("الموردون", "Suppliers"), "store_nav_suppliers"))
        add(Triple(TraderScreen.EXPENSES, lang.tr("المصاريف", "Expenses"), "store_nav_expenses"))
        if (canViewReports) {
            add(Triple(TraderScreen.FINANCIAL_REPORTS, lang.tr("التقارير", "Reports"), "store_nav_reports"))
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            modules.forEach { (screen, label, tag) ->
                val isSelected = currentScreen == screen
                Surface(
                    onClick = {
                        if (!isSelected) onNavigateTo(screen)
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                    border = if (isSelected) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    } else null,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag(tag)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountSettingsSubBar(
    currentScreen: TraderScreen,
    lang: AppLanguage,
    onNavigateTo: (TraderScreen) -> Unit
) {
    val tabs = listOf(
        Triple(
            TraderScreen.USER_PROFILE,
            lang.tr("الملف الشخصي والوردية", "Profile & Role"),
            "account_nav_profile"
        ),
        Triple(
            TraderScreen.BUSINESS_SETTINGS,
            lang.tr("إعدادات المتجر والطابعة", "Store & Printer Settings"),
            "account_nav_settings"
        )
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { (screen, label, tag) ->
                val isSelected = currentScreen == screen
                Surface(
                    onClick = {
                        if (!isSelected) onNavigateTo(screen)
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                    border = if (isSelected) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    } else null,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag(tag)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private data class PrimaryNavTab(
    val screen: TraderScreen,
    val labelAr: String,
    val labelEn: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
    val badgeCount: Int = 0
)

@Composable
fun SmallTraderBottomBar(
    state: TraderUiState,
    onSelectScreen: (TraderScreen) -> Unit,
    onOpenQuickActions: () -> Unit = {}
) {
    val lang = state.language
    NavigationBar(windowInsets = WindowInsets.navigationBars) {
        val tabs = listOf(
            Triple(TraderScreen.HOME, lang.tr("الرئيسية", "Home"), Icons.Default.Home),
            Triple(TraderScreen.INVOICES_HISTORY, lang.tr("المبيعات", "Sales"), Icons.AutoMirrored.Filled.ReceiptLong),
            Triple(TraderScreen.PRODUCTS_CATALOG, lang.tr("المخزون", "Inventory"), Icons.Default.Inventory2),
            Triple(TraderScreen.PEOPLE, lang.tr("الحسابات", "People"), Icons.Default.People)
        )
        tabs.forEachIndexed { index, (screen, label, icon) ->
            if (index == 2) {
                IconButton(onClick = onOpenQuickActions, modifier = Modifier.size(56.dp).testTag("nav_quick_add")) {
                    Icon(Icons.Default.Add, lang.tr("إضافة عملية", "New action"))
                }
            }
            NavigationBarItem(
                selected = state.currentScreen == screen,
                onClick = { onSelectScreen(screen) },
                icon = { Icon(icon, null) }, label = { Text(label) },
                modifier = Modifier.testTag(when(screen) {
                    TraderScreen.HOME -> "nav_home_tab"
                    TraderScreen.INVOICES_HISTORY -> "nav_invoices_tab"
                    TraderScreen.PRODUCTS_CATALOG -> "nav_inventory_tab"
                    else -> "nav_people_tab"
                })
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatedBarcodeScannerSheet(
    state: TraderUiState,
    onDismiss: () -> Unit,
    onScanProduct: (Product) -> Unit,
    onScanRandom: () -> Unit,
    onToggleFlashlight: () -> Unit = {},
    onManualInputChange: (String) -> Unit,
    onSubmitManual: () -> Unit
) {
    val lang = state.language
    val financial = TraderTheme.financial
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bannerText = if (lang == AppLanguage.AR) state.lastScannedBannerAr else state.lastScannedBannerEn

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Truthful simulation disclosure
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = lang.tr("إدخال الباركود", "Barcode Entry"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = lang.tr("وضع المحاكاة", "Simulation"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = lang.tr("إغلاق", "Close"),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 1. Direct Manual Barcode Entry Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.manualBarcodeInput,
                    onValueChange = onManualInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manual_barcode_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = {
                        Text(
                            text = lang.tr(
                                "رقم الباركود أو آخر 4 أرقام (مثال: 1014)",
                                "Full barcode or last 4 digits (e.g. 1014)"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingIcon = {
                        if (state.manualBarcodeInput.isNotEmpty()) {
                            IconButton(onClick = { onManualInputChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = lang.tr("مسح", "Clear"),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.SemiBold
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmitManual() })
                )
                Button(
                    onClick = onSubmitManual,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(54.dp)
                        .testTag("submit_manual_barcode_button")
                ) {
                    Text(
                        text = lang.tr("إدخال", "Add"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 2. Live Feedback & Basket Summary Strip (handles successive scans, unknown codes, and stock caps)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when {
                    bannerText != null && state.isBannerError -> financial.debitContainer
                    bannerText != null -> financial.incomeContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = when {
                        bannerText != null && state.isBannerError -> financial.debit.copy(alpha = 0.35f)
                        bannerText != null -> financial.income.copy(alpha = 0.35f)
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("scanner_feedback_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = bannerText ?: lang.tr(
                                "السلة الحالية: ${WesternFormatters.formatInt(state.basketItemCount)} أصناف (${WesternFormatters.formatIls(state.basketSubtotalIls)})",
                                "Current basket: ${WesternFormatters.formatInt(state.basketItemCount)} items (${WesternFormatters.formatIls(state.basketSubtotalIls)})"
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                bannerText != null && state.isBannerError -> financial.debit
                                bannerText != null -> financial.income
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (bannerText != null) {
                            Text(
                                text = lang.tr(
                                    "إجمالي السلة: ${WesternFormatters.formatInt(state.basketItemCount)} أصناف • ${WesternFormatters.formatIls(state.basketSubtotalIls)}",
                                    "Basket total: ${WesternFormatters.formatInt(state.basketItemCount)} items • ${WesternFormatters.formatIls(state.basketSubtotalIls)}"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onScanRandom,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("scan_random_product_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = lang.tr("مسح عشوائي", "Random Scan"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 3. Catalog Barcodes for Fast Successive Taps
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lang.tr("باركود الأصناف (اضغط للمسح المتتالي)", "Catalog Barcodes (Tap for successive scans)"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.scanCountSession > 0) {
                    Text(
                        text = lang.tr(
                            "عمليات المسح: ${WesternFormatters.formatInt(state.scanCountSession)}",
                            "Scans: ${WesternFormatters.formatInt(state.scanCountSession)}"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 250.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(state.products, key = { it.id }) { product ->
                        val qtyInBasket = state.quantityInBasket(product.id)
                        val isOutOfStock = product.isStockTracked && product.stockCount <= 0
                        val isMaxStock = product.isStockTracked && qtyInBasket >= product.stockCount

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (qtyInBasket > 0) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .clickable { onScanProduct(product) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("scanner_preset_${product.id}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = product.name(lang),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = if (qtyInBasket > 0) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isOutOfStock) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = product.barcode,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "• ${product.unit(lang)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = when {
                                                !product.isStockTracked -> lang.tr("• مادة حرة", "• Custom")
                                                isOutOfStock -> lang.tr("• نفد المخزون", "• Out of stock")
                                                isMaxStock -> lang.tr(
                                                    "• الحد الأقصى (${WesternFormatters.formatInt(product.stockCount)})",
                                                    "• Max (${WesternFormatters.formatInt(product.stockCount)})"
                                                )
                                                else -> lang.tr(
                                                    "• متاح ${WesternFormatters.formatInt(product.stockCount)}",
                                                    "• Avail ${WesternFormatters.formatInt(product.stockCount)}"
                                                )
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isOutOfStock || isMaxStock) FontWeight.SemiBold else FontWeight.Normal,
                                            color = when {
                                                isOutOfStock -> financial.debit
                                                isMaxStock -> financial.warning
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (qtyInBasket > 0) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = "×${WesternFormatters.formatInt(qtyInBasket)}",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontFamily = MonoFontFamily,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = WesternFormatters.formatIls(product.priceIls),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isOutOfStock) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothPrinterModalSheet(
    state: TraderUiState,
    onDismiss: () -> Unit,
    onScanPrinters: () -> Unit,
    onConnectPrinter: (BluetoothPrinterDevice) -> Unit,
    onDisconnectPrinter: () -> Unit,
    onSelectPaperWidth: (Int) -> Unit,
    onTestPrint: () -> Unit
) {
    val lang = state.language
    val financial = TraderTheme.financial
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("printer_modal_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = lang.tr("إعدادات الطباعة الحرارية", "Thermal Print Settings"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = lang.tr("محاكاة", "Simulation"),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = lang.tr("إغلاق", "Close"),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Current Status Callout (Ready / Disconnected / Error)
            val isError = state.printerStatus == PrinterStatus.ERROR
            val isReady = state.connectedPrinter != null && state.connectedPrinter.isPaired
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = when {
                    isError -> financial.debitContainer
                    isReady -> financial.incomeContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = when {
                        isError -> financial.debit.copy(alpha = 0.45f)
                        isReady -> financial.income.copy(alpha = 0.35f)
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("printer_sheet_status_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (lang == AppLanguage.AR) state.printProgressStepAr else state.printProgressStepEn,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            isError -> financial.debit
                            isReady -> financial.income
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (state.connectedPrinter != null) {
                        TextButton(
                            onClick = onDisconnectPrinter,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("disconnect_printer_button")
                        ) {
                            Text(
                                text = lang.tr("فصل", "Disconnect"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Paper width selector (58mm vs 80mm)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = lang.tr("عرض ورق المعاينة الحراري:", "Preview Paper Width:"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = state.receiptPaperWidthMm == 58,
                        onClick = { onSelectPaperWidth(58) },
                        label = {
                            Text(
                                text = lang.tr("58mm (قياس قياسي)", "58mm (Standard Roll)"),
                                fontFamily = MonoFontFamily
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("paper_width_58_chip")
                    )
                    FilterChip(
                        selected = state.receiptPaperWidthMm == 80,
                        onClick = { onSelectPaperWidth(80) },
                        label = {
                            Text(
                                text = lang.tr("80mm (قياس عريض)", "80mm (Wide Roll)"),
                                fontFamily = MonoFontFamily
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("paper_width_80_chip")
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lang.tr("أجهزة الطابعات المحاكاة", "Simulated Printer Devices"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onScanPrinters,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("scan_printers_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(lang.tr("تحديث القائمة", "Refresh List"))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.availablePrinters.forEach { device ->
                    val isSelected = state.connectedPrinter?.id == device.id
                    val isOfflineDevice = !device.isPaired
                    Card(
                        onClick = {
                            if (isSelected) onDisconnectPrinter() else onConnectPrinter(device)
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = when {
                            isSelected -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            isOfflineDevice -> BorderStroke(1.dp, financial.warning.copy(alpha = 0.45f))
                            else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("printer_device_${device.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isSelected) {
                                        Icons.Default.BluetoothConnected
                                    } else {
                                        Icons.Default.Bluetooth
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isOfflineDevice -> financial.warning
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = device.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (isOfflineDevice) {
                                            lang.tr(
                                                "${device.address} • ${device.paperWidthMm}mm • خارج النطاق (محاكاة خطأ)",
                                                "${device.address} • ${device.paperWidthMm}mm • Offline (simulated failure)"
                                            )
                                        } else {
                                            lang.tr(
                                                "${device.address} • ${device.paperWidthMm}mm • متاحة للمحاكاة",
                                                "${device.address} • ${device.paperWidthMm}mm • Available (simulated)"
                                            )
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                        color = if (isOfflineDevice) {
                                            financial.warning
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!isOfflineDevice) {
                                    Icon(
                                        imageVector = Icons.Default.BatteryChargingFull,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = financial.income
                                    )
                                    Text(
                                        text = "${WesternFormatters.formatInt(device.batteryPercent)}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                when {
                                    isSelected -> {
                                        Text(
                                            text = lang.tr("محددة ✓", "Selected ✓"),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = financial.income
                                        )
                                    }
                                    isOfflineDevice -> {
                                        Text(
                                            text = lang.tr("اختبار الفشل", "Test Fail"),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = financial.warning
                                        )
                                    }
                                    else -> {
                                        Text(
                                            text = lang.tr("اختيار", "Select"),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (state.activeReceipt != null) {
                Button(
                    onClick = onTestPrint,
                    enabled = state.connectedPrinter != null && state.connectedPrinter.isPaired,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("sheet_print_receipt_button"),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = lang.tr(
                            "معاينة طباعة الإيصال (${WesternFormatters.formatInvoiceCode(state.activeReceipt.invoiceNumber)})",
                            "Simulate Print (${WesternFormatters.formatInvoiceCode(state.activeReceipt.invoiceNumber)})"
                        ),
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun CustomQuickItemDialog(
    language: AppLanguage,
    onDismiss: () -> Unit,
    onConfirm: (name: String, priceIls: String) -> Unit
) {
    var nameText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = language.tr("مادة حرة سريعة", "Quick Custom Item"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text(language.tr("اسم الصنف (اختياري)", "Item Name (Optional)")) },
                    placeholder = { Text(language.tr("مثال: خضار مشكلة / خبز", "e.g. Produce / Bread")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("custom_item_name_input")
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = WesternFormatters.sanitizeWesternDigits(it) },
                    label = { Text(language.tr("السعر بالشيكل (₪)", "Price in Shekels (₪)")) },
                    placeholder = { Text("10.00") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("custom_item_price_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(nameText, priceText) },
                enabled = (WesternFormatters.sanitizeWesternDigits(priceText).toDoubleOrNull() ?: 0.0) > 0.0,
                modifier = Modifier.testTag("custom_item_confirm_button")
            ) {
                Text(language.tr("إضافة للسلة", "Add to Basket"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(language.tr("إلغاء", "Cancel"))
            }
        }
    )
}
