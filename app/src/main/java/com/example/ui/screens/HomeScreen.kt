package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Analytics
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.InvoiceRecord
import com.example.model.Product
import com.example.model.WesternFormatters
import com.example.ui.theme.CairoFontFamily
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState

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
        val isExpanded = maxWidth >= 720.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_screen_list"),
            contentPadding = PaddingValues(
                start = if (isExpanded) 24.dp else 16.dp,
                end = if (isExpanded) 24.dp else 16.dp,
                top = 12.dp,
                bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Primary Action: Active Basket in Progress OR Immediate Sale Entry
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

            // 2. Role-Aware Financial & Shift Pulse
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

            // 3. Actionable Low-Stock Warning + Recent Sales (Side-by-side on tablet, stacked on phone)
            if (isExpanded) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            RecentInvoicesSection(
                                invoices = state.invoices.take(3),
                                lang = lang,
                                onOpenInvoice = onOpenInvoice,
                                onViewAllInvoices = onViewAllInvoices
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
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

                item {
                    RecentInvoicesSection(
                        invoices = state.invoices.take(2),
                        lang = lang,
                        onOpenInvoice = onOpenInvoice,
                        onViewAllInvoices = onViewAllInvoices
                    )
                }

                // 4. Secondary Store Operations (Relocated from 6-card directory to a calm utility strip)
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
            }
        }
    }
}

/**
 * Top task surface:
 * - When an unfinished basket exists (`basketCount > 0`), completing or editing that basket is the
 *   primary task instead of competing with a "New Sale" banner.
 * - When the basket is empty, starting a new sale is the unmistakable primary action, accompanied
 *   by fast barcode scan and custom-item entry without repeating store identity text.
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                        Column {
                            Text(
                                text = lang.tr("عملية بيع قيد الإجراء", "Sale in Progress"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (lang == AppLanguage.AR) {
                                    "${WesternFormatters.formatInt(basketCount)} أصناف في السلة الحالية"
                                } else {
                                    "${WesternFormatters.formatInt(basketCount)} items in current basket"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = WesternFormatters.formatIls(basketTotalIls),
                        style = MaterialTheme.typography.headlineSmall,
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
                        )
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
                        shape = RoundedCornerShape(12.dp)
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
                            contentDescription = lang.tr("مسح باركود", "Scan Barcode")
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
                            contentDescription = lang.tr("صنف حر سريع", "Quick Custom Item")
                        )
                    }
                }
            }
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onStartNewSale,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .testTag("hero_new_sale_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddShoppingCart,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = lang.tr("بدء عملية بيع", "Start New Sale"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                FilledTonalButton(
                    onClick = onOpenScanner,
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .testTag("hero_scan_barcode_button"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = lang.tr("مسح باركود", "Scan Barcode"),
                        modifier = Modifier.size(20.dp)
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
                        .size(52.dp)
                        .testTag("home_quick_custom_item_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PointOfSale,
                        contentDescription = lang.tr("مبلغ حر سريع", "Quick Custom Amount")
                    )
                }
            }
        }
    }
}

/**
 * Owner / Manager financial pulse:
 * Replaces 4 separate KPI cards with one cohesive, high-contrast ledger summary card
 * showing actual drawer cash, today's sales, and open receivables/payables.
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Primary Drawer Cash row (tappable to open Financial Reports)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onNavigateToReports)
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lang.tr("النقد الفعلي بالصندوق", "Cash in Drawer"),
                        style = MaterialTheme.typography.labelLarge,
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
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            // 3-column compact ledger metrics: Today's Sales | Customer Debt | Supplier Payables
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Today's Sales
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onNavigateToReports)
                        .padding(vertical = 2.dp),
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (lang == AppLanguage.AR) {
                            "${WesternFormatters.formatInt(state.todayInvoiceCount)} فاتورة"
                        } else {
                            "${WesternFormatters.formatInt(state.todayInvoiceCount)} invoices"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Customer Receivables
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onNavigateToCustomers)
                        .testTag("home_customers_button")
                        .padding(vertical = 2.dp),
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (lang == AppLanguage.AR) {
                            "${WesternFormatters.formatInt(debtorCustomersCount)} ذمم مفتوحة"
                        } else {
                            "${WesternFormatters.formatInt(debtorCustomersCount)} open"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.totalCustomerDebtsIls > 0.009) financial.debit else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Supplier Payables
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onNavigateToSuppliers)
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = lang.tr("مستحقات الموردين", "Payables"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = WesternFormatters.formatIls(state.totalSupplierPayablesIls),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (lang == AppLanguage.AR) {
                            "${WesternFormatters.formatInt(owedSuppliersCount)} موردين"
                        } else {
                            "${WesternFormatters.formatInt(owedSuppliersCount)} owed"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Cashier Shift Pulse:
 * Respects role boundaries (`canViewReports() == false`): hides store-wide profit and total debt KPIs,
 * showing only the cashier's immediate drawer responsibility and shift invoice count.
 */
@Composable
private fun CashierShiftPulseCard(
    state: TraderUiState,
    lang: AppLanguage,
    onViewInvoices: () -> Unit
) {
    val financial = TraderTheme.financial

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1.3f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = if (lang == AppLanguage.AR) {
                        "نقد الصندوق • ${state.currentSession.name}"
                    } else {
                        "Drawer Cash • ${state.currentSession.name}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = WesternFormatters.formatIls(state.drawerTotalCashIls),
                    style = MaterialTheme.typography.headlineSmall,
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
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onViewInvoices)
                    .padding(vertical = 2.dp),
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
 * Actionable Stock Warning:
 * Shows the most urgent low-stock items (up to 2 by default, expandable if needed)
 * with inline +10 restock for authorized roles or quick catalog jump.
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (outOfStockCount > 0) {
                financial.debit.copy(alpha = 0.4f)
            } else {
                financial.warning.copy(alpha = 0.4f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
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
                    Surface(
                        shape = CircleShape,
                        color = if (outOfStockCount > 0) financial.debitContainer else financial.warningContainer,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = if (outOfStockCount > 0) financial.debit else financial.warning,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Column {
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
                            Text(
                                text = if (lang == AppLanguage.AR) {
                                    "${WesternFormatters.formatInt(outOfStockCount)} أصناف نفدت بالكامل"
                                } else {
                                    "${WesternFormatters.formatInt(outOfStockCount)} items out of stock"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = financial.debit
                            )
                        }
                    }
                }

                TextButton(
                    onClick = onNavigateToCatalog,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = lang.tr("فتح الجرد", "Inventory"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

            if (lowStockProducts.size > 2) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (expanded) {
                            lang.tr("طي القائمة", "Show less")
                        } else {
                            val remaining = lowStockProducts.size - 2
                            if (lang == AppLanguage.AR) {
                                "عرض ${WesternFormatters.formatInt(remaining)} أصناف أخرى"
                            } else {
                                "Show ${WesternFormatters.formatInt(remaining)} more"
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
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
            .padding(vertical = 2.dp),
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
                text = product.name(lang),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isOut) {
                    if (lang == AppLanguage.AR) {
                        "نفد من الرف • الحد الأدنى ${WesternFormatters.formatInt(product.minStockThreshold)}"
                    } else {
                        "Out of stock • Min ${WesternFormatters.formatInt(product.minStockThreshold)}"
                    }
                } else {
                    if (lang == AppLanguage.AR) {
                        "المتبقي ${WesternFormatters.formatInt(product.stockCount)} ${product.unit(lang)} (الحد ${WesternFormatters.formatInt(product.minStockThreshold)})"
                    } else {
                        "${WesternFormatters.formatInt(product.stockCount)} ${product.unit(lang)} left (Min ${WesternFormatters.formatInt(product.minStockThreshold)})"
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isOut) financial.debit else financial.warning
            )
        }

        if (canManageInventory) {
            FilledTonalButton(
                onClick = onRestock,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .testTag("restock_${product.id}"),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = lang.tr("+10 توريد", "+10 Restock"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = CairoFontFamily
                )
            }
        } else if (!isOut) {
            OutlinedButton(
                onClick = onAddToSale,
                modifier = Modifier.heightIn(min = 40.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = lang.tr("بيع", "Sell"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Latest Work:
 * Shows the latest 2 invoices for immediate verification or re-opening a receipt.
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
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = lang.tr("آخر الفواتير", "Latest Invoices"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(
                onClick = onViewAllInvoices,
                modifier = Modifier.testTag("view_all_invoices_button"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = lang.tr("كل الفواتير", "All Invoices"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (invoices.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = lang.tr("لا توجد فواتير مسجلة اليوم بعد.", "No invoices recorded yet today."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
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
 * Secondary Store Operations:
 * Relocates back-office destinations that are not in the primary bottom bar into a calm,
 * compact horizontal utility strip so they remain 1-tap reachable without turning Home into
 * a 6-card module directory.
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = lang.tr("إدارة المتجر", "Store Operations"),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OperationPill(
                title = lang.tr("المخزون", "Inventory"),
                subtitle = if (lang == AppLanguage.AR) {
                    "${WesternFormatters.formatInt(state.products.size)} صنف"
                } else {
                    "${WesternFormatters.formatInt(state.products.size)} items"
                },
                icon = Icons.Default.Inventory2,
                onClick = onNavigateToCatalog,
                testTag = "home_catalog_button"
            )

            OperationPill(
                title = lang.tr("الموردون", "Suppliers"),
                subtitle = if (lang == AppLanguage.AR) {
                    "${WesternFormatters.formatInt(state.suppliers.size)} مورد"
                } else {
                    "${WesternFormatters.formatInt(state.suppliers.size)} vendors"
                },
                icon = Icons.Default.LocalShipping,
                onClick = onNavigateToSuppliers,
                testTag = "home_suppliers_button"
            )

            OperationPill(
                title = lang.tr("المصاريف", "Expenses"),
                subtitle = WesternFormatters.formatIls(state.shiftExpensesIls),
                icon = Icons.Default.Payments,
                onClick = onNavigateToExpenses,
                testTag = "home_expenses_button"
            )

            if (canViewReports) {
                OperationPill(
                    title = lang.tr("التقارير", "Reports"),
                    subtitle = lang.tr("اليومية والأرباح", "Daily P&L"),
                    icon = Icons.Default.Analytics,
                    onClick = onNavigateToReports,
                    testTag = "home_reports_button"
                )
            } else {
                // Keep Customers reachable via home_customers_button for Cashier role as well
                OperationPill(
                    title = lang.tr("الزبائن", "Customers"),
                    subtitle = lang.tr("دفتر الديون", "Receivables"),
                    icon = Icons.Default.People,
                    onClick = onNavigateToCustomers,
                    testTag = "home_customers_button"
                )
            }

            OperationPill(
                title = lang.tr("الطابعة", "Printer"),
                subtitle = "${WesternFormatters.formatInt(state.receiptPaperWidthMm)}mm",
                icon = Icons.Default.Print,
                onClick = onOpenPrinter,
                testTag = "home_printer_setup_button"
            )

            OperationPill(
                title = lang.tr("الإعدادات", "Settings"),
                subtitle = state.currentSession.role.label(lang),
                icon = Icons.Default.Settings,
                onClick = onNavigateToSettings,
                testTag = "home_settings_button"
            )
        }
    }
}

@Composable
private fun OperationPill(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 52.dp)
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
                "جزئي • متبقي ${WesternFormatters.formatIls(invoice.remainingDebitIls)}",
                "Partial • Due ${WesternFormatters.formatIls(invoice.remainingDebitIls)}"
            ),
            financial.onWarningContainer,
            financial.warningContainer
        )
        else -> Triple(
            lang.tr(
                "ذمة آجلة • متبقي ${WesternFormatters.formatIls(invoice.remainingDebitIls)}",
                "On Credit • Due ${WesternFormatters.formatIls(invoice.remainingDebitIls)}"
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
        "${WesternFormatters.formatInt(invoice.items.size)} أصناف (${WesternFormatters.formatInt(invoice.totalQuantity)} وحدات)",
        "${WesternFormatters.formatInt(invoice.items.size)} items (${WesternFormatters.formatInt(invoice.totalQuantity)} units)"
    )

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recent_invoice_${invoice.invoiceNumber}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Top Row: Customer / Note (Primary identifier) + Net Total
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = customerTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
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

            // Bottom Row: Invoice Code • Date/Time • Item Count | Paid vs Credit Status Pill
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

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusBg
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

