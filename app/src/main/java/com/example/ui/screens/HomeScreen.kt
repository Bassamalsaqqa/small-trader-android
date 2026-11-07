package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.InvoiceRecord
import com.example.model.Product
import com.example.model.WesternFormatters
import com.example.ui.theme.MonoFontFamily
import com.example.viewmodel.TraderUiState

/**
 * Calm, Native-First Home Screen for Small Trader (التاجر الصغير).
 *
 * Implements Merchant Workspace Concept A:
 * 1. Single Dominant Register Action: Dominant "Resume Sale" when an active basket exists,
 *    or dominant "New Sale" when empty. No competing hero buttons or paragraph fluff.
 * 2. Dedicated 48dp Secondary POS Actions: Direct Scan Barcode and Custom Item actions.
 * 3. Authorized Purchase Affordance: Distinct, concise 'شراء جديد' button for authorized
 *    roles without receive-stock conflation.
 * 4. High-Signal Owner Pulse: Single Today's Sales figure with date/invoice scope and quiet
 *    Reports drill-down. Strictly hidden for cashiers. No cash/float noise (lives in Reports).
 * 5. Short Activity & Attention List: Maximum 3 rows total (e.g. 1 navigation-only stock alert
 *    summary + 2 recent invoices). No stock mutation (+10 eliminated), no pills jungle,
 *    and no redundant operation chips (reachable via shell navigation).
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
    modifier: Modifier = Modifier,
    onOpenPurchase: () -> Unit = onNavigateToSuppliers
) {
    val lang = state.language
    val canViewReports = state.currentSession.canViewReports()
    val canManageSuppliers = state.currentSession.canManageSuppliers()
    val hasActiveBasket = state.basketItemCount > 0

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Dominant Register Section: Single Active Main Action + Secondary Scanner/Custom Row
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (hasActiveBasket) {
                    // Dominant Full-Width Resume Sale Action
                    Button(
                        onClick = onReviewBasket,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("home_resume_review_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ShoppingBag,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = lang.tr("متابعة البيع", "Resume Sale"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = if (lang == AppLanguage.AR) {
                                    "${WesternFormatters.formatInt(state.basketItemCount)} أصناف • ${WesternFormatters.formatIls(state.basketNetTotalIls)}"
                                } else {
                                    "${WesternFormatters.formatInt(state.basketItemCount)} items • ${WesternFormatters.formatIls(state.basketNetTotalIls)}"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    // Dominant Full-Width New Sale Action
                    Button(
                        onClick = onStartNewSale,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("hero_new_sale_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddShoppingCart,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = lang.tr("بيع جديد", "New Sale"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Secondary 48dp POS Tools Row: Scan Barcode & Quick Custom Item
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onOpenScanner,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("hero_scan_barcode_button"),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = lang.tr("مسح باركود", "Scan Barcode"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenCustomItem,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("home_quick_custom_item_button"),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PointOfSale,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = lang.tr("صنف حر", "Custom Item"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Quiet Add Items Affordance when Active Basket Exists
                if (hasActiveBasket) {
                    TextButton(
                        onClick = onStartNewSale,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddShoppingCart,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = lang.tr("إضافة أصناف أخرى للسلة", "Add more items to cart"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 2. Authorized Purchase Action: Concise Full-Width Button without Receive-Stock Conflation
        if (canManageSuppliers) {
            item {
                FilledTonalButton(
                    onClick = onOpenPurchase,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("home_purchase_button"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalShipping,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = lang.tr("شراء جديد", "New Purchase"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 3. Owner-Only Financial Pulse: Single Today's Sales Figure with Scope & Quiet Reports Drill-Down
        if (canViewReports) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (lang == AppLanguage.AR) {
                                    "مبيعات اليوم • ${WesternFormatters.formatInt(state.todayInvoiceCount)} فاتورة"
                                } else {
                                    "Today's Sales • ${WesternFormatters.formatInt(state.todayInvoiceCount)} invoices"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = WesternFormatters.formatIls(state.todaySalesTotalIls),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        TextButton(
                            onClick = onNavigateToReports,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("home_reports_button"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = lang.tr("التقارير", "Reports"),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // 4. Short Activity & Attention List (Max 3 Rows Total)
        item {
            val hasStockAlert = state.lowStockProducts.isNotEmpty()
            val maxInvoices = if (hasStockAlert) 2 else 3
            val recentInvoices = state.invoices.take(maxInvoices)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Activity Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lang.tr("النشاط الأخير", "Recent Activity"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(
                        onClick = onViewAllInvoices,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("view_all_invoices_button"),
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

                // Row 1 (if stock alert): Navigation-Only Stock Summary Row (NO MUTATION)
                if (hasStockAlert) {
                    Surface(
                        onClick = onNavigateToCatalog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("home_catalog_button"),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = if (lang == AppLanguage.AR) {
                                        "${WesternFormatters.formatInt(state.lowStockProducts.size)} أصناف منخفضة في المخزون"
                                    } else {
                                        "${WesternFormatters.formatInt(state.lowStockProducts.size)} items low in stock"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Invoices (Max 2 if stock alert, max 3 if no stock alert -> Max 3 rows total)
                if (recentInvoices.isNotEmpty()) {
                    recentInvoices.forEach { invoice ->
                        RecentInvoiceRow(
                            invoice = invoice,
                            lang = lang,
                            onClick = { onOpenInvoice(invoice) }
                        )
                    }
                } else if (!hasStockAlert) {
                    Text(
                        text = lang.tr("لا توجد فواتير مسجلة اليوم بعد.", "No invoices recorded yet today."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Clean, Single-Row Representation of a Recent Invoice.
 * Avoids multi-tier border slabs and badges while preserving financial clarity.
 */
@Composable
fun RecentInvoiceCard(
    invoice: InvoiceRecord,
    lang: AppLanguage,
    onClick: () -> Unit
) {
    RecentInvoiceRow(
        invoice = invoice,
        lang = lang,
        onClick = onClick
    )
}

@Composable
fun RecentInvoiceRow(
    invoice: InvoiceRecord,
    lang: AppLanguage,
    onClick: () -> Unit
) {
    val customerTitle = invoice.customerNote.ifBlank {
        lang.tr("زبون نقدي", "Walk-in Customer")
    }
    val paymentLabel = when {
        invoice.isPaidInFull -> lang.tr("نقداً", "Cash")
        invoice.cashReceivedIls > 0.005 -> lang.tr("جزئي", "Partial")
        else -> lang.tr("ذمة", "Credit")
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("recent_invoice_${invoice.invoiceNumber}"),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
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
                    text = customerTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = WesternFormatters.formatInvoiceCode(invoice.invoiceNumber),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = paymentLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = WesternFormatters.formatIls(invoice.netTotalIls),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
