package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.InvoiceRecord
import com.example.model.Product
import com.example.model.WesternFormatters
import com.example.ui.components.PhaseOneBentoTile
import com.example.ui.components.PhaseOneCard
import com.example.ui.components.PhaseOneSectionHeader
import com.example.ui.components.PhaseOneStatusBadge
import com.example.ui.theme.CairoFontFamily
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState

/**
 * Rebuilt Native Home Screen for Small Trader (التاجر الصغير).
 *
 * Implements Phase 1 UX Architecture:
 * 1. Task-Driven Action Header: Instant recognition of active in-progress baskets
 *    vs. fast new sale / barcode launch with 48dp minimum accessible touch targets.
 * 2. BoardUI-Inspired Financial Pulse: Rich, subtle surface tinting for cash drawer,
 *    today's sales, and receivables with strict Owner vs. Cashier permission boundaries.
 * 3. Urgent Inventory Action Center: Prominently positions low-stock and out-of-stock
 *    alerts with one-tap +10 restock for authorized roles.
 * 4. Adaptive Bento Grid: Balanced 2x2 grid eliminating horizontal scroller fatigue on phone,
 *    and smoothly expanding to dual-column responsive layout on Medium/Expanded displays.
 * 5. Complete Callback & Financial Invariant Preservation: Preserves all navigation routes,
 *    accounting guards, and automated test hooks.
 */
@Composable
fun HomeScreen(
    state: TraderUiState,
    onStartNewSale: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenPrinter: () -> Unit,
    onOpenCustomItem: () -> Unit,
    onReviewBasket: () -> Unit,
    onOpenInvoice: (InvoiceRecord) -> Unit,
    onViewAllInvoices: () -> Unit,
    onRestockProduct: (Product) -> Unit,
    onAddProductToSale: (Product) -> Unit,
    onNavigateToCatalog: () -> Unit,
    onNavigateToCustomers: () -> Unit,
    onNavigateToSuppliers: () -> Unit,
    onNavigateToExpenses: () -> Unit,
    onNavigateToReports: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lang = state.language
    val canViewReports = state.currentSession.canViewReports()
    val canManageInventory = state.currentSession.canManageInventory()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Material 3 Adaptive breakpoint: Compact (<600dp), Medium/Expanded (>=600dp / >=720dp)
        val isExpanded = maxWidth >= 700.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_screen_list"),
            contentPadding = PaddingValues(
                start = if (isExpanded) 24.dp else 16.dp,
                end = if (isExpanded) 24.dp else 16.dp,
                top = 12.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isExpanded) {
                // =============================================================
                // ADAPTIVE DUAL-COLUMN LAYOUT (Tablets, Foldables, Wide Screens)
                // =============================================================
                item {
                    PrimaryRegisterSection(
                        basketCount = state.basketItemCount,
                        basketTotalIls = state.basketSubtotalIls,
                        lang = lang,
                        onStartNewSale = onStartNewSale,
                        onReviewBasket = onReviewBasket,
                        onOpenScanner = onOpenScanner,
                        onOpenCustomItem = onOpenCustomItem
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Start Column: Financial Pulse & Recent Invoices
                        Column(
                            modifier = Modifier.weight(1.1f),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            if (canViewReports) {
                                OwnerFinancialPulseCard(
                                    state = state,
                                    lang = lang,
                                    onNavigateToReports = onNavigateToReports,
                                    onNavigateToCustomers = onNavigateToCustomers,
                                    onNavigateToSuppliers = onNavigateToSuppliers
                                )
                            } else {
                                CashierShiftPulseCard(
                                    state = state,
                                    lang = lang,
                                    onViewInvoices = onViewAllInvoices
                                )
                            }

                            RecentInvoicesSection(
                                invoices = state.invoices.take(3),
                                lang = lang,
                                onOpenInvoice = onOpenInvoice,
                                onViewAllInvoices = onViewAllInvoices
                            )
                        }

                        // End Column: Actionable Low Stock & Bento Grid Store Operations
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            if (state.lowStockProducts.isNotEmpty()) {
                                ActionableLowStockSection(
                                    lowStockProducts = state.lowStockProducts,
                                    canManageInventory = canManageInventory,
                                    lang = lang,
                                    onRestockProduct = onRestockProduct,
                                    onAddProductToSale = onAddProductToSale,
                                    onNavigateToCatalog = onNavigateToCatalog
                                )
                            }

                            SecondaryOperationsSection(
                                state = state,
                                lang = lang,
                                canViewReports = canViewReports,
                                onNavigateToCatalog = onNavigateToCatalog,
                                onNavigateToSuppliers = onNavigateToSuppliers,
                                onNavigateToExpenses = onNavigateToExpenses,
                                onNavigateToReports = onNavigateToReports,
                                onNavigateToCustomers = onNavigateToCustomers,
                                onOpenPrinter = onOpenPrinter,
                                onNavigateToSettings = onNavigateToSettings
                            )
                        }
                    }
                }
            } else {
                // =============================================================
                // COMPACT SINGLE-COLUMN LAYOUT (Phones < 700dp)
                // =============================================================

                // 1. Primary Register Task: Active Basket in Progress OR Start Sale
                item {
                    PrimaryRegisterSection(
                        basketCount = state.basketItemCount,
                        basketTotalIls = state.basketSubtotalIls,
                        lang = lang,
                        onStartNewSale = onStartNewSale,
                        onReviewBasket = onReviewBasket,
                        onOpenScanner = onOpenScanner,
                        onOpenCustomItem = onOpenCustomItem
                    )
                }

                // 2. Role-Aware Financial & Shift Ledger Pulse
                item {
                    if (canViewReports) {
                        OwnerFinancialPulseCard(
                            state = state,
                            lang = lang,
                            onNavigateToReports = onNavigateToReports,
                            onNavigateToCustomers = onNavigateToCustomers,
                            onNavigateToSuppliers = onNavigateToSuppliers
                        )
                    } else {
                        CashierShiftPulseCard(
                            state = state,
                            lang = lang,
                            onViewInvoices = onViewAllInvoices
                        )
                    }
                }

                // 3. Urgent Actionable Low-Stock Alert (Prioritized right after pulse)
                if (state.lowStockProducts.isNotEmpty()) {
                    item {
                        ActionableLowStockSection(
                            lowStockProducts = state.lowStockProducts,
                            canManageInventory = canManageInventory,
                            lang = lang,
                            onRestockProduct = onRestockProduct,
                            onAddProductToSale = onAddProductToSale,
                            onNavigateToCatalog = onNavigateToCatalog
                        )
                    }
                }

                // 4. Store Operations Bento Grid (Immediately visible 2x2 grid, no horizontal scroll)
                item {
                    SecondaryOperationsSection(
                        state = state,
                        lang = lang,
                        canViewReports = canViewReports,
                        onNavigateToCatalog = onNavigateToCatalog,
                        onNavigateToSuppliers = onNavigateToSuppliers,
                        onNavigateToExpenses = onNavigateToExpenses,
                        onNavigateToReports = onNavigateToReports,
                        onNavigateToCustomers = onNavigateToCustomers,
                        onOpenPrinter = onOpenPrinter,
                        onNavigateToSettings = onNavigateToSettings
                    )
                }

                // 5. Latest Reconciled Invoices
                item {
                    RecentInvoicesSection(
                        invoices = state.invoices.take(2),
                        lang = lang,
                        onOpenInvoice = onOpenInvoice,
                        onViewAllInvoices = onViewAllInvoices
                    )
                }
            }
        }
    }
}

/**
 * Top task surface:
 * - When an unfinished basket exists (`basketCount > 0`), completing or reviewing that basket
 *   is the primary task, accompanied by secondary scan and quick-item shortcuts.
 * - When the basket is empty, starting a new sale is the prominent primary CTA with
 *   integrated 48dp scan and custom-item shortcuts.
 */
@Composable
private fun PrimaryRegisterSection(
    basketCount: Int,
    basketTotalIls: Double,
    lang: AppLanguage,
    onStartNewSale: () -> Unit,
    onReviewBasket: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenCustomItem: () -> Unit
) {
    if (basketCount > 0) {
        PhaseOneCard(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.40f),
            borderWidth = 1.2.dp,
            contentPadding = PaddingValues(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ShoppingBag,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = lang.tr("عملية بيع قيد الإجراء", "Sale in Progress"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (lang == AppLanguage.AR) {
                                    "${WesternFormatters.formatInt(basketCount)} أصناف في السلة"
                                } else {
                                    "${WesternFormatters.formatInt(basketCount)} items in basket"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = WesternFormatters.formatIls(basketTotalIls),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onReviewBasket,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("home_resume_review_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = lang.tr("مراجعة ودفع", "Review & Pay"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    FilledTonalButton(
                        onClick = onStartNewSale,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("hero_new_sale_button"),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddShoppingCart,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = lang.tr("إضافة أصناف", "Add Items"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    FilledTonalIconButton(
                        onClick = onOpenScanner,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("hero_scan_barcode_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = lang.tr("مسح باركود", "Scan Barcode"),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    FilledTonalIconButton(
                        onClick = onOpenCustomItem,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("home_quick_custom_item_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PointOfSale,
                            contentDescription = lang.tr("صنف حر سريع", "Quick Custom Item"),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onStartNewSale,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("hero_new_sale_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AddShoppingCart,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = lang.tr("بدء عملية بيع", "Start New Sale"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            FilledTonalButton(
                onClick = onOpenScanner,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("hero_scan_barcode_button"),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = lang.tr("مسح باركود", "Scan Barcode"),
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = lang.tr("مسح", "Scan"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            FilledTonalIconButton(
                onClick = onOpenCustomItem,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("home_quick_custom_item_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PointOfSale,
                    contentDescription = lang.tr("مبلغ حر سريع", "Quick Custom Amount"),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Owner / Manager financial pulse:
 * Provides a cohesive ledger summary card with subtle BoardUI-inspired surface tinting,
 * displaying expected book cash in drawer, today's sales, and open receivables/payables.
 */
@Composable
private fun OwnerFinancialPulseCard(
    state: TraderUiState,
    lang: AppLanguage,
    onNavigateToReports: () -> Unit,
    onNavigateToCustomers: () -> Unit,
    onNavigateToSuppliers: () -> Unit
) {
    val financial = TraderTheme.financial
    val netShiftCashChange = state.drawerTotalCashIls - state.startingDrawerCashIls
    val debtorCustomersCount = state.customers.count { it.balanceIls > 0.009 }
    val owedSuppliersCount = state.suppliers.count { it.balanceOwedIls > 0.009 }

    PhaseOneCard(
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Primary Drawer Cash row (tappable to open Financial Reports reconciliation)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = onNavigateToReports)
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = lang.tr("النقد المتوقع بالصندوق", "Expected drawer cash"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (lang == AppLanguage.AR) {
                            "عهدة ${WesternFormatters.formatIls(state.startingDrawerCashIls)} • صافي الوردية ${WesternFormatters.formatIls(netShiftCashChange)}"
                        } else {
                            "Float ${WesternFormatters.formatIls(state.startingDrawerCashIls)} • Shift net ${WesternFormatters.formatIls(netShiftCashChange)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = WesternFormatters.formatIls(state.drawerTotalCashIls),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

            // 3-column compact ledger metrics: Today's Sales | Customer Debt | Supplier Payables
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Today's Sales Column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onNavigateToReports)
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = lang.tr("مبيعات اليوم", "Today's Sales"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = WesternFormatters.formatIls(state.todaySalesTotalIls),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = if (lang == AppLanguage.AR) {
                            "${WesternFormatters.formatInt(state.todayInvoiceCount)} فواتير"
                        } else {
                            "${WesternFormatters.formatInt(state.todayInvoiceCount)} invoices"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Customer Receivables Column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onNavigateToCustomers)
                        .testTag("home_customers_button")
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = lang.tr("ديون الزبائن", "Receivables"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = WesternFormatters.formatIls(state.totalCustomerDebtsIls),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = if (lang == AppLanguage.AR) {
                            "${WesternFormatters.formatInt(debtorCustomersCount)} ذمم"
                        } else {
                            "${WesternFormatters.formatInt(debtorCustomersCount)} open"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.totalCustomerDebtsIls > 0.009) financial.debit else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Supplier Payables Column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onNavigateToSuppliers)
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = lang.tr("ذمم الموردين", "Payables"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = WesternFormatters.formatIls(state.totalSupplierPayablesIls),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = if (lang == AppLanguage.AR) {
                            "${WesternFormatters.formatInt(owedSuppliersCount)} موردين"
                        } else {
                            "${WesternFormatters.formatInt(owedSuppliersCount)} owed"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Cashier Shift Pulse:
 * Respects role security boundaries (`canViewReports() == false`): hides store-wide profit
 * and total receivables/payables, showing only immediate shift drawer cash and invoice count.
 */
@Composable
private fun CashierShiftPulseCard(
    state: TraderUiState,
    lang: AppLanguage,
    onViewInvoices: () -> Unit
) {
    PhaseOneCard(
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1.3f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = if (lang == AppLanguage.AR) {
                        "النقد المتوقع • ${state.currentSession.name}"
                    } else {
                        "Expected cash • ${state.currentSession.name}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = WesternFormatters.formatIls(state.drawerTotalCashIls),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (lang == AppLanguage.AR) {
                        "عهدة البداية ${WesternFormatters.formatIls(state.startingDrawerCashIls)}"
                    } else {
                        "Opening float ${WesternFormatters.formatIls(state.startingDrawerCashIls)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onViewInvoices)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = lang.tr("فواتير الوردية", "Shift Invoices"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = WesternFormatters.formatInt(state.todayInvoiceCount),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = lang.tr("عرض السجل", "View log"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Actionable Stock Warning Section:
 * Surfaces urgent zero-stock and low-stock items with high-contrast warning badges,
 * one-tap +10 restock for managers, or quick catalog jump.
 */
@Composable
private fun ActionableLowStockSection(
    lowStockProducts: List<Product>,
    canManageInventory: Boolean,
    lang: AppLanguage,
    onRestockProduct: (Product) -> Unit,
    onAddProductToSale: (Product) -> Unit,
    onNavigateToCatalog: () -> Unit
) {
    val financial = TraderTheme.financial
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visibleProducts = if (expanded) lowStockProducts else lowStockProducts.take(2)
    val outOfStockCount = lowStockProducts.count { it.stockCount <= 0 }

    PhaseOneCard(
        borderColor = if (outOfStockCount > 0) {
            financial.debit.copy(alpha = 0.40f)
        } else {
            financial.warning.copy(alpha = 0.40f)
        },
        borderWidth = 1.2.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = if (outOfStockCount > 0) financial.debit else financial.warning,
                        modifier = Modifier.size(19.dp)
                    )
                    Text(
                        text = if (lang == AppLanguage.AR) {
                            "نواقص المخزون (${WesternFormatters.formatInt(lowStockProducts.size)})"
                        } else {
                            "Low Stock (${WesternFormatters.formatInt(lowStockProducts.size)})"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (outOfStockCount > 0) {
                        PhaseOneStatusBadge(
                            text = if (lang == AppLanguage.AR) {
                                "${WesternFormatters.formatInt(outOfStockCount)} نفد"
                            } else {
                                "${WesternFormatters.formatInt(outOfStockCount)} out"
                            },
                            textColor = financial.onDebitContainer,
                            containerColor = financial.debitContainer
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (lowStockProducts.size > 2) {
                        TextButton(
                            onClick = { expanded = !expanded },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(
                                text = if (expanded) {
                                    lang.tr("طي", "Less")
                                } else {
                                    "+${WesternFormatters.formatInt(lowStockProducts.size - 2)}"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    TextButton(
                        onClick = onNavigateToCatalog,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(
                            text = lang.tr("الجرد", "Inventory"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                visibleProducts.forEachIndexed { index, product ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                    LowStockActionRow(
                        product = product,
                        canManageInventory = canManageInventory,
                        lang = lang,
                        onRestock = { onRestockProduct(product) },
                        onAddToSale = { onAddProductToSale(product) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LowStockActionRow(
    product: Product,
    canManageInventory: Boolean,
    lang: AppLanguage,
    onRestock: () -> Unit,
    onAddToSale: () -> Unit
) {
    val financial = TraderTheme.financial
    val isOut = product.stockCount <= 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isOut) {
                    lang.tr("نفد من المخزون", "Out of stock")
                } else {
                    if (lang == AppLanguage.AR) {
                        "متبقي ${WesternFormatters.formatInt(product.stockCount)} ${product.unit(lang)}"
                    } else {
                        "${WesternFormatters.formatInt(product.stockCount)} ${product.unit(lang)} left"
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isOut) financial.debit else financial.warning
            )
        }

        if (canManageInventory) {
            FilledTonalButton(
                onClick = onRestock,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("restock_${product.id}"),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = lang.tr("+10 توريد", "+10 Restock"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = CairoFontFamily
                )
            }
        } else if (!isOut) {
            OutlinedButton(
                onClick = onAddToSale,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = lang.tr("بيع", "Sell"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Recent Invoices Section:
 * Shows the latest invoices for immediate verification or receipt reprint.
 */
@Composable
private fun RecentInvoicesSection(
    invoices: List<InvoiceRecord>,
    lang: AppLanguage,
    onOpenInvoice: (InvoiceRecord) -> Unit,
    onViewAllInvoices: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PhaseOneSectionHeader(
            title = lang.tr("آخر الفواتير", "Latest Invoices"),
            actionTitle = lang.tr("كل الفواتير", "All Invoices"),
            onActionClick = onViewAllInvoices,
            actionTag = "view_all_invoices_button"
        )

        if (invoices.isEmpty()) {
            PhaseOneCard(
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                contentPadding = PaddingValues(16.dp)
            ) {
                Text(
                    text = lang.tr("لا توجد فواتير مسجلة اليوم بعد.", "No invoices recorded yet today."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                invoices.forEach { invoice ->
                    RecentInvoiceCard(
                        invoice = invoice,
                        lang = lang,
                        onClick = { onOpenInvoice(invoice) }
                    )
                }
            }
        }
    }
}

/**
 * Secondary Store Operations Hub:
 * Balanced 2x2 Bento Grid for core store modules + utility buttons for Printer and Settings.
 */
@Composable
private fun SecondaryOperationsSection(
    state: TraderUiState,
    lang: AppLanguage,
    canViewReports: Boolean,
    onNavigateToCatalog: () -> Unit,
    onNavigateToSuppliers: () -> Unit,
    onNavigateToExpenses: () -> Unit,
    onNavigateToReports: () -> Unit,
    onNavigateToCustomers: () -> Unit,
    onOpenPrinter: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val lowStockCount = state.lowStockProducts.size

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = lang.tr("إدارة المتجر والدفاتر", "Store Operations & Ledgers"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Row 1: Inventory & Suppliers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PhaseOneBentoTile(
                title = lang.tr("المخزون والأصناف", "Inventory"),
                subtitle = if (lowStockCount > 0) {
                    lang.tr(
                        "${WesternFormatters.formatInt(state.products.size)} صنف • ${WesternFormatters.formatInt(lowStockCount)} نواقص",
                        "${WesternFormatters.formatInt(state.products.size)} items • ${WesternFormatters.formatInt(lowStockCount)} low"
                    )
                } else {
                    lang.tr(
                        "${WesternFormatters.formatInt(state.products.size)} صنف مسجل",
                        "${WesternFormatters.formatInt(state.products.size)} items"
                    )
                },
                icon = Icons.Default.Inventory2,
                onClick = onNavigateToCatalog,
                testTag = "home_catalog_button",
                modifier = Modifier.weight(1f)
            )

            PhaseOneBentoTile(
                title = lang.tr("الموردون والمشتريات", "Suppliers"),
                subtitle = if (canViewReports) lang.tr(
                    "${WesternFormatters.formatInt(state.suppliers.size)} مورد • ${WesternFormatters.formatIls(state.totalSupplierPayablesIls)}",
                    "${WesternFormatters.formatInt(state.suppliers.size)} vendors • ${WesternFormatters.formatIls(state.totalSupplierPayablesIls)}"
                ) else lang.tr("دليل الموردين", "Supplier directory"),
                icon = Icons.Default.LocalShipping,
                onClick = onNavigateToSuppliers,
                testTag = "home_suppliers_button",
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2: Expenses & Reports (or Customers for Cashier)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PhaseOneBentoTile(
                title = lang.tr("المصاريف والنثريات", "Expenses"),
                subtitle = lang.tr(
                    "اليوم: ${WesternFormatters.formatIls(state.shiftExpensesIls)}",
                    "Today: ${WesternFormatters.formatIls(state.shiftExpensesIls)}"
                ),
                icon = Icons.Default.Payments,
                onClick = onNavigateToExpenses,
                testTag = "home_expenses_button",
                modifier = Modifier.weight(1f)
            )

            if (canViewReports) {
                PhaseOneBentoTile(
                    title = lang.tr("التقارير والصندوق", "Reports & Drawer"),
                    subtitle = lang.tr(
                        "الصندوق: ${WesternFormatters.formatIls(state.drawerTotalCashIls)}",
                        "Drawer: ${WesternFormatters.formatIls(state.drawerTotalCashIls)}"
                    ),
                    icon = Icons.Default.Analytics,
                    onClick = onNavigateToReports,
                    testTag = "home_reports_button",
                    modifier = Modifier.weight(1f)
                )
            } else {
                PhaseOneBentoTile(
                    title = lang.tr("دفتر الزبائن", "Customers"),
                    subtitle = lang.tr(
                        "حسابات الزبائن والتحصيل",
                        "Accounts and collections"
                    ),
                    icon = Icons.Default.People,
                    onClick = onNavigateToCustomers,
                    testTag = "home_customers_button",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Utility Row: Thermal Printer Setup & Store/Profile Settings
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OperationUtilityButton(
                title = lang.tr("الطابعة الحرارية", "Printer"),
                subtitle = "${WesternFormatters.formatInt(state.receiptPaperWidthMm)}mm",
                icon = Icons.Default.Print,
                onClick = onOpenPrinter,
                testTag = "home_printer_setup_button",
                modifier = Modifier.weight(1f)
            )

            OperationUtilityButton(
                title = lang.tr("الإعدادات والملف", "Settings & Profile"),
                subtitle = state.currentSession.role.label(lang),
                icon = Icons.Default.Settings,
                onClick = onNavigateToSettings,
                testTag = "home_settings_button",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun OperationUtilityButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag(testTag),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
fun RecentInvoiceCard(
    invoice: InvoiceRecord,
    lang: AppLanguage,
    onClick: () -> Unit
) {
    val financial = TraderTheme.financial
    val isPartial = !invoice.isPaidInFull && invoice.cashReceivedIls > 0.005
    val (statusText, statusColor, statusBg) = when {
        invoice.isPaidInFull -> Triple(
            lang.tr("مدفوع نقداً", "Paid Cash"),
            financial.onIncomeContainer,
            financial.incomeContainer
        )
        isPartial -> Triple(
            lang.tr(
                "جزئي • ${WesternFormatters.formatIls(invoice.remainingDebitIls)}",
                "Partial • ${WesternFormatters.formatIls(invoice.remainingDebitIls)}"
            ),
            financial.onWarningContainer,
            financial.warningContainer
        )
        else -> Triple(
            lang.tr(
                "ذمة • ${WesternFormatters.formatIls(invoice.remainingDebitIls)}",
                "Credit • ${WesternFormatters.formatIls(invoice.remainingDebitIls)}"
            ),
            financial.onDebitContainer,
            financial.debitContainer
        )
    }

    val todayPrefix = WesternFormatters.currentDate()
    val formattedDateTime = if (invoice.timestamp.startsWith(todayPrefix)) {
        val timePart = invoice.timestamp.substringAfter(" ", invoice.timestamp)
        lang.tr("اليوم $timePart", "Today $timePart")
    } else {
        invoice.timestamp
    }

    val customerTitle = invoice.customerNote.ifBlank {
        lang.tr("زبون نقدي", "Walk-in Customer")
    }
    val itemSummary = lang.tr(
        "${WesternFormatters.formatInt(invoice.items.size)} أصناف",
        "${WesternFormatters.formatInt(invoice.items.size)} items"
    )

    PhaseOneCard(
        onClick = onClick,
        testTag = "recent_invoice_${invoice.invoiceNumber}",
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Top Row: Customer / Note + Net Total
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = customerTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                )

                Text(
                    text = WesternFormatters.formatIls(invoice.netTotalIls),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Bottom Row: Invoice Code • Date/Time • Item Count | Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = WesternFormatters.formatInvoiceCode(invoice.invoiceNumber),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formattedDateTime,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = itemSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                PhaseOneStatusBadge(
                    text = statusText,
                    textColor = statusColor,
                    containerColor = statusBg
                )
            }
        }
    }
}
