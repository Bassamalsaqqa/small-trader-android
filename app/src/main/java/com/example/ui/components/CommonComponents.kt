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
    val isHome = state.currentScreen == TraderScreen.HOME
    val showBack = !isHome
    var isAccountMenuOpen by remember { mutableStateOf(false) }

    val screenTitle = when (state.currentScreen) {
        TraderScreen.HOME -> lang.tr("التاجر الصغير", "Small Trader")
        TraderScreen.NEW_SALE -> lang.tr("بيع جديد", "New Sale")
        TraderScreen.INVOICE_REVIEW -> lang.tr("مراجعة الفاتورة", "Invoice Review")
        TraderScreen.CASH_PAYMENT -> lang.tr("تحصيل الدفع", "Payment")
        TraderScreen.RECEIPT -> lang.tr("إيصال البيع", "Receipt")
        TraderScreen.INVOICES_HISTORY -> lang.tr("سجل الفواتير", "Invoices")
        TraderScreen.PRODUCTS_CATALOG -> lang.tr("الأصناف والمخزون", "Inventory")
        TraderScreen.PRODUCT_DETAIL -> lang.tr("بطاقة الصنف", "Product Detail")
        TraderScreen.CUSTOMERS -> lang.tr("حسابات الزبائن", "Customers")
        TraderScreen.CUSTOMER_DETAIL -> lang.tr("كشف حساب الزبون", "Customer Account")
        TraderScreen.SUPPLIERS -> lang.tr("الموردون والمشتريات", "Suppliers")
        TraderScreen.SUPPLIER_DETAIL -> lang.tr("حساب المورد", "Supplier Account")
        TraderScreen.EXPENSES -> lang.tr("المصاريف والنثريات", "Expenses")
        TraderScreen.FINANCIAL_REPORTS -> lang.tr("التقارير والصندوق", "Reports & Drawer")
        TraderScreen.USER_PROFILE -> lang.tr("الملف الشخصي", "My Profile")
        TraderScreen.BUSINESS_SETTINGS -> lang.tr("الإعدادات", "Settings")
    }

    val contextualSubtitle: String? = when (state.currentScreen) {
        TraderScreen.HOME -> "${state.businessInfo.name(lang)} • ${state.businessInfo.city(lang)}"
        TraderScreen.NEW_SALE -> if (state.basketItemCount > 0) {
            lang.tr(
                "${WesternFormatters.formatInt(state.basketItemCount)} وحدات في السلة • ${WesternFormatters.formatIls(state.basketNetTotalIls)}",
                "${WesternFormatters.formatInt(state.basketItemCount)} in basket • ${WesternFormatters.formatIls(state.basketNetTotalIls)}"
            )
        } else {
            lang.tr("اختر الأصناف أو امسح الباركود", "Tap items or scan barcode")
        }
        TraderScreen.INVOICE_REVIEW -> lang.tr(
            "الخطوة 2 من 3 • الصافي ${WesternFormatters.formatIls(state.basketNetTotalIls)}",
            "Step 2 of 3 • Net ${WesternFormatters.formatIls(state.basketNetTotalIls)}"
        )
        TraderScreen.CASH_PAYMENT -> lang.tr(
            "الخطوة 3 من 3 • المطلوب ${WesternFormatters.formatIls(state.basketNetTotalIls)}",
            "Step 3 of 3 • Due ${WesternFormatters.formatIls(state.basketNetTotalIls)}"
        )
        TraderScreen.RECEIPT -> state.activeReceipt?.let { rec ->
            "${WesternFormatters.formatInvoiceCode(rec.invoiceNumber)} • ${rec.customerNote}"
        }
        TraderScreen.PRODUCT_DETAIL -> state.selectedProductForDetail?.let { prod ->
            "${lang.tr("الأصناف والمخزون", "Inventory")} • ${prod.name(lang)}"
        }
        TraderScreen.CUSTOMER_DETAIL -> state.selectedCustomerForDetail?.let { cust ->
            "${lang.tr("حسابات الزبائن", "Customers")} • ${cust.name(lang)}"
        }
        TraderScreen.SUPPLIER_DETAIL -> state.selectedSupplierForDetail?.let { sup ->
            "${lang.tr("الموردون", "Suppliers")} • ${sup.name(lang)}"
        }
        else -> null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = screenTitle,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (contextualSubtitle != null) {
                        Text(
                            text = contextualSubtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            navigationIcon = {
                if (showBack) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("top_bar_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = lang.tr("رجوع", "Back")
                        )
                    }
                }
            },
            actions = {
                // 1. Resume Unfinished Sale pill on non-checkout screens when basket has items
                val showResumeBasket = state.basketItemCount > 0 &&
                    state.currentScreen !in setOf(
                        TraderScreen.HOME,
                        TraderScreen.NEW_SALE,
                        TraderScreen.INVOICE_REVIEW,
                        TraderScreen.CASH_PAYMENT,
                        TraderScreen.RECEIPT
                    )
                if (showResumeBasket) {
                    Surface(
                        onClick = { onNavigateTo(TraderScreen.NEW_SALE) },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .heightIn(min = 48.dp)
                            .testTag("top_bar_resume_sale_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ShoppingCart,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = lang.tr(
                                    "السلة (${WesternFormatters.formatInt(state.basketItemCount)}) • ${WesternFormatters.formatIls(state.basketNetTotalIls)}",
                                    "Sale (${WesternFormatters.formatInt(state.basketItemCount)}) • ${WesternFormatters.formatIls(state.basketNetTotalIls)}"
                                ),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1
                            )
                        }
                    }
                }

                // 2. Barcode Scanner button in top bar on Catalog screen
                val showScanner = state.currentScreen == TraderScreen.PRODUCTS_CATALOG
                if (showScanner) {
                    IconButton(
                        onClick = onOpenScanner,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("top_bar_scanner_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = lang.tr("مسح باركود", "Scan Barcode"),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // 3. Bluetooth Printer status shortcut on Receipt screen
                if (state.currentScreen == TraderScreen.RECEIPT) {
                    val isConnected = state.connectedPrinter != null
                    Surface(
                        onClick = onOpenPrinter,
                        shape = RoundedCornerShape(10.dp),
                        color = if (isConnected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .heightIn(min = 48.dp)
                            .testTag("top_bar_printer_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (isConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                                contentDescription = lang.tr("طابعة البلوتوث", "Bluetooth Printer"),
                                modifier = Modifier.size(16.dp),
                                tint = if (isConnected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${state.receiptPaperWidthMm}mm",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (isConnected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }

                // 4. Home identity & quick display preferences (focused on Account/Role & Settings, no duplicate store modules)
                if (isHome) {
                    TextButton(
                        onClick = onToggleLanguage,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("language_toggle_button")
                    ) {
                        Text(
                            text = if (lang == AppLanguage.AR) "EN" else "عربي",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = onToggleTheme,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("theme_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (state.isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = lang.tr("تبديل المظهر", "Toggle Theme"),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(modifier = Modifier.padding(end = 6.dp)) {
                        Surface(
                            onClick = { isAccountMenuOpen = true },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("top_bar_account_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = lang.tr("الحساب والإعدادات", "Account & Settings"),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = state.currentSession.role.label(lang),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = isAccountMenuOpen,
                            onDismissRequest = { isAccountMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = state.currentSession.name,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Text(
                                            text = "${state.currentSession.role.label(lang)} • ${lang.tr("الملف الشخصي وتبديل الدور", "Profile & Role Switcher")}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                onClick = {
                                    isAccountMenuOpen = false
                                    onNavigateTo(TraderScreen.USER_PROFILE)
                                },
                                modifier = Modifier.testTag("menu_user_profile_item")
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(lang.tr("إعدادات المتجر والطابعة", "Store & Printer Settings")) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    isAccountMenuOpen = false
                                    onNavigateTo(TraderScreen.BUSINESS_SETTINGS)
                                },
                                modifier = Modifier.testTag("menu_business_settings_item")
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        lang.tr(
                                            "الطابعة الحرارية (${state.receiptPaperWidthMm}mm)",
                                            "Thermal Printer (${state.receiptPaperWidthMm}mm)"
                                        )
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Print, contentDescription = null) },
                                onClick = {
                                    isAccountMenuOpen = false
                                    onOpenPrinter()
                                }
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface
            )
        )

        // Interactive 3-Step Checkout Funnel Bar (shown during active checkout flow)
        val showCheckoutSteps = state.currentScreen == TraderScreen.INVOICE_REVIEW ||
            state.currentScreen == TraderScreen.CASH_PAYMENT ||
            (state.currentScreen == TraderScreen.NEW_SALE && state.basketItemCount > 0)
        if (showCheckoutSteps) {
            CheckoutStepSubBar(
                currentScreen = state.currentScreen,
                basketItemCount = state.basketItemCount,
                lang = lang,
                onNavigateTo = onNavigateTo
            )
        }

        // Lateral Store Operations Switcher Bar (Inventory • Suppliers • Expenses • Reports)
        if (state.isStoreOperationDestination) {
            StoreOperationsSubBar(
                currentScreen = state.currentScreen,
                canViewReports = state.currentSession.canViewReports(),
                lang = lang,
                onNavigateTo = onNavigateTo
            )
        }

        // Lateral Account & Settings Switcher Bar (Profile & Role • Store & Printer Settings)
        if (state.isAccountOrSettingsDestination) {
            AccountSettingsSubBar(
                currentScreen = state.currentScreen,
                lang = lang,
                onNavigateTo = onNavigateTo
            )
        }

        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
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
    onSelectScreen: (TraderScreen) -> Unit
) {
    val lang = state.language
    val current = state.currentScreen
    val saleCount = state.basketItemCount

    val tabs = listOf(
        PrimaryNavTab(
            screen = TraderScreen.HOME,
            labelAr = "الرئيسية",
            labelEn = "Home",
            selectedIcon = Icons.Filled.Home,
            unselectedIcon = Icons.Outlined.Home,
            testTag = "nav_home_tab"
        ),
        PrimaryNavTab(
            screen = TraderScreen.NEW_SALE,
            labelAr = if (saleCount > 0) "السلة (${WesternFormatters.formatInt(saleCount)})" else "بيع جديد",
            labelEn = if (saleCount > 0) "Sale (${WesternFormatters.formatInt(saleCount)})" else "New Sale",
            selectedIcon = Icons.Filled.PointOfSale,
            unselectedIcon = Icons.Outlined.PointOfSale,
            testTag = "nav_sale_tab",
            badgeCount = saleCount
        ),
        PrimaryNavTab(
            screen = TraderScreen.INVOICES_HISTORY,
            labelAr = "الفواتير",
            labelEn = "Invoices",
            selectedIcon = Icons.AutoMirrored.Filled.ReceiptLong,
            unselectedIcon = Icons.AutoMirrored.Outlined.ReceiptLong,
            testTag = "nav_invoices_tab"
        ),
        PrimaryNavTab(
            screen = TraderScreen.CUSTOMERS,
            labelAr = "الزبائن",
            labelEn = "Customers",
            selectedIcon = Icons.Filled.People,
            unselectedIcon = Icons.Outlined.People,
            testTag = "nav_customers_tab"
        )
    )

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val isSelected = current == tab.screen
                    val iconColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val labelColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onSelectScreen(tab.screen) }
                            .testTag(tab.testTag),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            Color.Transparent
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (tab.badgeCount > 0) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text(
                                                    text = WesternFormatters.formatInt(tab.badgeCount),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 10.sp
                                                    )
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = lang.tr(tab.labelAr, tab.labelEn),
                                        tint = iconColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = lang.tr(tab.labelAr, tab.labelEn),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = labelColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
