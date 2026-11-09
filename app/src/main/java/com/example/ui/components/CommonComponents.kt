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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    onNavigateTo: (TraderScreen) -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleTheme: () -> Unit = {},
    onOpenScanner: () -> Unit = {},
    onOpenPrinter: () -> Unit = {}
) {
    val lang = state.language
    var accountOpen by remember { mutableStateOf(false) }

    // Initials for avatar button (e.g. "أك" or initials from user session name)
    val initials = remember(state.currentSession.name) {
        val parts = state.currentSession.name.trim().split(" ").filter { it.isNotBlank() }
        when {
            parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
            parts.isNotEmpty() && parts[0].length >= 2 -> parts[0].take(2)
            parts.isNotEmpty() -> parts[0].take(1)
            else -> "أك"
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("small_trader_top_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Navigation Icon: Back button when not root destination
            if (!state.isRootDestination) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("top_bar_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = lang.tr("رجوع", "Back"),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Header / Title / Identity Area (Flexible weight, adaptive height, no clipping at font2 360dp)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.currentScreen == TraderScreen.HOME) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Store,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(
                            text = state.businessInfo.name(lang),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = state.currentSession.name,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = lang.tr(
                                when (state.currentScreen) {
                                    TraderScreen.PEOPLE -> "الحسابات"
                                    TraderScreen.PURCHASES -> "المشتريات"
                                    TraderScreen.NEW_SALE -> "بيع جديد"
                                    TraderScreen.INVOICES_HISTORY -> "المبيعات"
                                    TraderScreen.PRODUCTS_CATALOG -> "المخزون"
                                    TraderScreen.CASH_PAYMENT -> "الدفع"
                                    TraderScreen.INVOICE_REVIEW -> "مراجعة البيع"
                                    TraderScreen.CUSTOMERS -> "الزبائن"
                                    TraderScreen.CUSTOMER_DETAIL -> "حساب الزبون"
                                    TraderScreen.SUPPLIERS -> "الموردون"
                                    TraderScreen.SUPPLIER_DETAIL -> "حساب المورد"
                                    TraderScreen.EXPENSES -> "المصاريف"
                                    TraderScreen.FINANCIAL_REPORTS -> "التقارير والصندوق"
                                    TraderScreen.USER_PROFILE -> "الملف والصلاحيات"
                                    TraderScreen.BUSINESS_SETTINGS -> "إعدادات المتجر"
                                    TraderScreen.PRODUCT_DETAIL -> "تفاصيل الصنف"
                                    TraderScreen.RECEIPT -> "إيصال البيع"
                                    else -> "التاجر الصغير"
                                },
                                when (state.currentScreen) {
                                    TraderScreen.PEOPLE -> "Accounts"
                                    TraderScreen.PURCHASES -> "Purchases"
                                    TraderScreen.NEW_SALE -> "New Sale"
                                    TraderScreen.INVOICES_HISTORY -> "Sales"
                                    TraderScreen.PRODUCTS_CATALOG -> "Inventory"
                                    TraderScreen.CASH_PAYMENT -> "Payment"
                                    TraderScreen.INVOICE_REVIEW -> "Sale Review"
                                    TraderScreen.CUSTOMERS -> "Customers"
                                    TraderScreen.CUSTOMER_DETAIL -> "Customer Account"
                                    TraderScreen.SUPPLIERS -> "Suppliers"
                                    TraderScreen.SUPPLIER_DETAIL -> "Supplier Account"
                                    TraderScreen.EXPENSES -> "Expenses"
                                    TraderScreen.FINANCIAL_REPORTS -> "Reports & Cash"
                                    TraderScreen.USER_PROFILE -> "Profile & Role"
                                    TraderScreen.BUSINESS_SETTINGS -> "Settings"
                                    TraderScreen.PRODUCT_DETAIL -> "Product Detail"
                                    TraderScreen.RECEIPT -> "Receipt"
                                    else -> "Small Trader"
                                }
                            ),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Actions Area: Language Toggle (>= 48dp) + Accessible Avatar (>= 48dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // ALWAYS easily reachable >=48dp AR/EN language button invoking existing onToggleLanguage
                Surface(
                    onClick = onToggleLanguage,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = lang.tr(
                                "تبديل لغة الواجهة إلى الإنجليزية",
                                "Switch interface language to Arabic"
                            )
                        }
                        .testTag("top_bar_language_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (lang == AppLanguage.AR) "EN" else "عربي",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp
                            )
                        )
                    }
                }

                // Accessible Avatar button
                Surface(
                    onClick = { accountOpen = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = lang.tr("الحساب والإعدادات", "Account and settings")
                        }
                        .testTag("top_bar_account_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = initials,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp
                            )
                        )
                    }
                }
            }
        }
    }

    if (accountOpen) {
        AccountBottomSheet(
            state = state,
            onDismissRequest = { accountOpen = false },
            onNavigateTo = { destination ->
                accountOpen = false
                onNavigateTo(destination)
            },
            onToggleTheme = onToggleTheme,
            onToggleLanguage = onToggleLanguage
        )
    }
}

/**
 * Focused Account & Fast Settings Modal Bottom Sheet (Round 2 Design System).
 *
 * Contains:
 * 1. Sheet header with explicit >=48dp dismiss X button and system-back dismissal.
 * 2. Real identity card (User name, role badge, store name and icon).
 * 3. 4 role-gated navigation rows:
 *    - Profile & Role (USER_PROFILE)
 *    - Store Settings (BUSINESS_SETTINGS)
 *    - Reports & Cash Drawer (FINANCIAL_REPORTS, Owner/Manager gated)
 *    - Operating Expenses (EXPENSES, Owner/Manager gated)
 *
 * Selecting any destination dismisses the sheet before navigation.
 * No duplicate operational menus, no printer configuration (solely in Settings),
 * and no tutorial/invented version subtitles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountBottomSheet(
    state: TraderUiState,
    onDismissRequest: () -> Unit,
    onNavigateTo: (TraderScreen) -> Unit,
    onToggleTheme: () -> Unit = {},
    onToggleLanguage: () -> Unit = {}
) {
    val lang = state.language
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val initials = remember(state.currentSession.name) {
        val parts = state.currentSession.name.trim().split(" ").filter { it.isNotBlank() }
        when {
            parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
            parts.isNotEmpty() && parts[0].length >= 2 -> parts[0].take(2)
            parts.isNotEmpty() -> parts[0].take(1)
            else -> "أك"
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.testTag("account_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row: Title & Subtitle + Explicit Dismiss X (>= 48dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = lang.tr("الحساب والإعدادات السريعة", "Account & Quick Access"),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    )
                    Text(
                        text = lang.tr("إدارة الجلسة والوصول المباشر للمتجر", "Session management & direct store access"),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("account_sheet_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = lang.tr("إغلاق", "Close"),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Identity Header Card (Truthful session identity, role badge, store info)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_sheet_identity_card")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = initials,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = state.currentSession.name,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                            ) {
                                Text(
                                    text = state.currentSession.role.label(lang),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Store,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = state.businessInfo.name(lang),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Section: Destination Navigation List (4 Role-gated rows in grouped card)
            Text(
                text = lang.tr("الأقسام والعمليات", "Sections & Operations"),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    // Row 1: Profile & Permissions
                    AccountNavigationRow(
                        icon = Icons.Default.AccountCircle,
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = lang.tr("الملف الشخصي والصلاحيات", "Profile & Permissions"),
                        subtitle = lang.tr("بيانات الدخول، وتحديد صلاحيات الكاشير", "Login details & cashier permissions"),
                        testTag = "account_sheet_profile_row",
                        onClick = { onNavigateTo(TraderScreen.USER_PROFILE) }
                    )

                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    // Row 2: General Store Settings
                    AccountNavigationRow(
                        icon = Icons.Default.Settings,
                        iconContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = lang.tr("إعدادات المتجر العامة", "General Store Settings"),
                        subtitle = lang.tr("الرقم الضريبي، سياسات الفواتير والهوية", "Tax number, invoice policies & identity"),
                        testTag = "account_sheet_settings_row",
                        onClick = { onNavigateTo(TraderScreen.BUSINESS_SETTINGS) }
                    )

                    // Row 3: Financial Reports & Cash Drawer (Role guarded)
                    if (state.currentSession.canViewReports()) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        AccountNavigationRow(
                            icon = Icons.Default.PointOfSale,
                            iconContainerColor = TraderTheme.financial.incomeContainer.copy(alpha = 0.5f),
                            iconTint = TraderTheme.financial.income,
                            title = lang.tr("التقارير المالية وحركة الصندوق", "Financial Reports & Cash Drawer"),
                            subtitle = lang.tr("ملخص اليوم، جرد النقد، وإغلاق الوردية", "Daily summary, cash count & shift close"),
                            testTag = "account_sheet_reports_row",
                            onClick = { onNavigateTo(TraderScreen.FINANCIAL_REPORTS) }
                        )
                    }

                    // Row 4: Operating Expenses (Role guarded)
                    if (state.currentSession.canManageExpenses()) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        AccountNavigationRow(
                            icon = Icons.Default.AccountBalanceWallet,
                            iconContainerColor = TraderTheme.financial.debitContainer.copy(alpha = 0.5f),
                            iconTint = TraderTheme.financial.debit,
                            title = lang.tr("المصاريف وسندات الصرف", "Expenses & Payment Vouchers"),
                            subtitle = lang.tr("تسجيل النثريات، المشتريات والمدفوعات", "Petty cash, purchases & pay-outs"),
                            testTag = "account_sheet_expenses_row",
                            onClick = { onNavigateTo(TraderScreen.EXPENSES) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountNavigationRow(
    icon: ImageVector,
    iconContainerColor: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = iconContainerColor,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Stitch ST-01 Root Bottom Navigation Bar.
 *
 * Implements 5 physical slots in strict Right-to-Left order:
 * Slot 1: الرئيسية (Home)
 * Slot 2: المبيعات (Sales Ledger / Register)
 * Slot 3: [+] Modal Quick Action Trigger (Center elevated circular action)
 * Slot 4: المخزون (Inventory / Catalog)
 * Slot 5: الحسابات (Accounts / People & Debts)
 *
 * Enforces >= 48dp touch targets, TalkBack accessibility bounds, min 12sp typography,
 * and 2x font scale resilience.
 */
@Composable
fun SmallTraderBottomBar(
    state: TraderUiState,
    onSelectScreen: (TraderScreen) -> Unit,
    onOpenQuickActions: () -> Unit = {}
) {
    val lang = state.language
    NavigationBar(
        windowInsets = WindowInsets.navigationBars,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Slot 1: الرئيسية (Home)
        val isHomeSelected = state.currentScreen == TraderScreen.HOME
        NavigationBarItem(
            selected = isHomeSelected,
            onClick = { onSelectScreen(TraderScreen.HOME) },
            icon = {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            },
            label = {
                Text(
                    text = lang.tr("الرئيسية", "Home"),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            alwaysShowLabel = true,
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("nav_home_tab")
        )

        // Slot 2: المبيعات (Sales / Invoices)
        val isSalesSelected = state.currentScreen == TraderScreen.INVOICES_HISTORY
        NavigationBarItem(
            selected = isSalesSelected,
            onClick = { onSelectScreen(TraderScreen.INVOICES_HISTORY) },
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            },
            label = {
                Text(
                    text = lang.tr("المبيعات", "Sales"),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            alwaysShowLabel = true,
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("nav_invoices_tab")
        )

        // Slot 3: Quick Action [+] Center Elevated Button (Stitch ST-01 / ST-03)
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 64.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                onClick = onOpenQuickActions,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .size(48.dp)
                    .offset(y = (-4).dp)
                    .testTag("nav_quick_add")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = lang.tr("إجراء سريع", "Quick action"),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Slot 4: المخزون (Inventory)
        val isInventorySelected = state.currentScreen == TraderScreen.PRODUCTS_CATALOG
        NavigationBarItem(
            selected = isInventorySelected,
            onClick = { onSelectScreen(TraderScreen.PRODUCTS_CATALOG) },
            icon = {
                Icon(
                    imageVector = Icons.Default.Inventory2,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            },
            label = {
                Text(
                    text = lang.tr("المخزون", "Inventory"),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            alwaysShowLabel = true,
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("nav_inventory_tab")
        )

        // Slot 5: الحسابات (Accounts / People)
        val isPeopleSelected = state.currentScreen == TraderScreen.PEOPLE || state.currentScreen == TraderScreen.PURCHASES
        NavigationBarItem(
            selected = isPeopleSelected,
            onClick = { onSelectScreen(TraderScreen.PEOPLE) },
            icon = {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            },
            label = {
                Text(
                    text = lang.tr("الحسابات", "People"),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            alwaysShowLabel = true,
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("nav_people_tab")
        )
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
