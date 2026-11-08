package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.model.InvoiceRecord
import com.example.model.Product
import com.example.model.WesternFormatters
import com.example.ui.components.StitchBadge
import com.example.ui.components.StitchBadgeTone
import com.example.ui.components.StitchBidiUtils
import com.example.ui.components.StitchCard
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState

/**
 * Stitch Canonical Home Screen for Small Trader (التاجر الصغير).
 *
 * Implements Google Stitch Design (ST-01-AR-LIGHT-OWNER-NORMAL-v02):
 * 1. Store Identity: Shell already owns the top app bar header, avoiding duplication.
 * 2. Dominant Register Action: Dominant "Resume Sale" when an active basket exists,
 *    or dominant "New Sale" when empty (min 52dp height, 10dp radius, #255FD6).
 * 3. Dedicated 48dp Secondary POS Actions: Direct Scan Barcode and Custom Item actions.
 * 4. Concise Purchase Action: Utilitarian 'المشتريات' action for authorized roles (no marketing slop).
 * 5. Owner Pulse Metric: Single dated sales figure with date scope and invoice count pill.
 *    No physical or book cash footer (honoring Lead Reconciliation). Strictly hidden for cashiers.
 * 6. Compact Attention List: Low stock summary row (navigates to catalog, strictly NO restock mutation)
 *    and up to 2 recent invoices with honest partial settlement tracking.
 * 7. BiDi Safety: Alphanumeric codes format cleanly as "فاتورة INV-1042" with 48dp touch targets.
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Dominant Register Section: Hero Action + Tactile 48dp Secondary Quick Actions
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasActiveBasket) {
                    // Dominant Full-Width Resume Sale Action
                    Button(
                        onClick = onReviewBasket,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("home_resume_review_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PointOfSale,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = lang.tr(
                                            "متابعة البيع (${state.basketItemCount})",
                                            "Resume Sale (${state.basketItemCount})"
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = lang.tr("السلة الحالية غير مكتملة", "Active sale in progress"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                                    )
                                }
                            }

                            Text(
                                text = WesternFormatters.formatIls(state.basketNetTotalIls),
                                style = MaterialTheme.typography.titleLarge,
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
                            .heightIn(min = 52.dp)
                            .testTag("hero_new_sale_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
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

                // Tactile 48dp Secondary POS Actions: Direct Scan Barcode & Custom Item
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = onOpenScanner,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("hero_scan_barcode_button"),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = lang.tr("مسح باركود", "Scan Barcode"),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        onClick = onOpenCustomItem,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("home_quick_custom_item_button"),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = lang.tr("صنف سريع", "Custom Item"),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 2. High-Signal Owner Pulse: Dated Sales Metric Card (Authorized Only, Strictly No Cash Footer)
        if (canViewReports) {
            item {
                Surface(
                    onClick = onNavigateToReports,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_reports_button"),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.tr(
                                    "مبيعات اليوم (${state.todayDate})",
                                    "Today's Sales (${state.todayDate})"
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            StitchBadge(
                                text = "${WesternFormatters.formatInt(state.todayInvoiceCount)} ${lang.tr("فاتورة", "invoices")}",
                                tone = StitchBadgeTone.NEUTRAL
                            )
                        }

                        Text(
                            text = WesternFormatters.formatIls(state.todaySalesTotalIls),
                            style = MaterialTheme.typography.displayMedium.copy(fontSize = 30.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // 3. Authorized Purchase Affordance (Owner / Manager Only, Concise Utilitarian Action)
        if (canManageSuppliers) {
            item {
                Surface(
                    onClick = onOpenPurchase,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("home_purchase_button"),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.LocalShipping,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Text(
                                text = lang.tr("المشتريات", "Purchases"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
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
        }

        // 4. Compact Stock Summary Row (Navigates to Catalog, Strictly NO Restock Mutation)
        if (state.lowStockProducts.isNotEmpty()) {
            item {
                Surface(
                    onClick = onNavigateToCatalog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("home_catalog_button"),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = TraderTheme.financial.warningContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = TraderTheme.financial.warning,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = if (lang == AppLanguage.AR) {
                                        "نواقص المخزون: ${WesternFormatters.formatInt(state.lowStockProducts.size)} أصناف"
                                    } else {
                                        "${WesternFormatters.formatInt(state.lowStockProducts.size)} items low in stock"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = state.lowStockProducts.take(2).joinToString(if (lang == AppLanguage.AR) "، " else ", ") { it.name(lang) } +
                                            if (state.lowStockProducts.size > 2) "..." else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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
        }

        // 5. Recent Invoices Section: Section Header + Clean Bordered Card
        item {
            val recentInvoices = state.invoices.sortedByDescending { it.timestamp }.take(2)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lang.tr("العمليات الأخيرة", "Recent Transactions"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
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
                            text = lang.tr("عرض الكل", "View All"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (recentInvoices.isNotEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            recentInvoices.forEachIndexed { index, invoice ->
                                RecentInvoiceRow(
                                    invoice = invoice,
                                    lang = lang,
                                    onClick = { onOpenInvoice(invoice) }
                                )
                                if (index < recentInvoices.lastIndex) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        thickness = 1.dp
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = lang.tr("لا توجد فواتير مسجلة اليوم بعد.", "No invoices recorded yet today."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Clean, Single-Row Representation of a Recent Invoice.
 * Preserves public compatibility for ReceiptScreen and legacy callers.
 */
@Composable
fun RecentInvoiceCard(
    invoice: InvoiceRecord,
    lang: AppLanguage,
    onClick: () -> Unit
) {
    StitchCard(
        onClick = onClick,
        modifier = Modifier.testTag("recent_invoice_${invoice.invoiceNumber}"),
        contentPadding = PaddingValues(0.dp)
    ) {
        RecentInvoiceRow(
            invoice = invoice,
            lang = lang,
            onClick = onClick
        )
    }
}

@Composable
fun RecentInvoiceRow(
    invoice: InvoiceRecord,
    lang: AppLanguage,
    onClick: () -> Unit
) {
    val isPartial = invoice.cashReceivedIls > 0.005 && !invoice.isPaidInFull
    val statusTone = when {
        invoice.isPaidInFull -> StitchBadgeTone.SUCCESS
        isPartial -> StitchBadgeTone.WARNING
        else -> StitchBadgeTone.DANGER
    }
    val statusLabel = when {
        invoice.isPaidInFull -> lang.tr("مدفوع", "Paid")
        isPartial -> lang.tr("سداد جزئي", "Partial")
        else -> lang.tr("ذمة", "Credit")
    }

    val customerTitle = invoice.customerNote.ifBlank {
        lang.tr("زبون نقدي", "Walk-in")
    }

    val subtitle = if (isPartial) {
        if (lang == AppLanguage.AR) {
            "مسدد ${WesternFormatters.formatIls(invoice.cashReceivedIls)} • متبقي ${WesternFormatters.formatIls(invoice.remainingDebitIls)}"
        } else {
            "Paid ${WesternFormatters.formatIls(invoice.cashReceivedIls)} • Due ${WesternFormatters.formatIls(invoice.remainingDebitIls)}"
        }
    } else {
        "$customerTitle • ${if (invoice.isPaidInFull) lang.tr("نقداً", "Cash") else lang.tr("ذمة", "Credit")}"
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("recent_invoice_${invoice.invoiceNumber}"),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Receipt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = StitchBidiUtils.formatInvoiceCode(invoice.invoiceNumber, lang.tr("فاتورة", "Invoice")),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = WesternFormatters.formatIls(invoice.netTotalIls),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                StitchBadge(
                    text = statusLabel,
                    tone = statusTone
                )
            }
        }
    }
}
