package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Storefront
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.model.InvoiceRecord
import com.example.model.Product
import com.example.model.UserRole
import com.example.model.WesternFormatters
import com.example.ui.components.StitchBadge
import com.example.ui.components.StitchBadgeTone
import com.example.ui.components.StitchBidiUtils
import com.example.ui.components.StitchCard
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState

/**
 * Calm, Analytics-Led Merchant Home Screen for Small Trader (التاجر الصغير).
 *
 * Implements Google Stitch Round 2 Design Specifications (R2-HOME):
 * 1. Card-Contained Store Identity: Authentic parent brand storefront vector mark in royal blue (#255FD6),
 *    business name, operator welcome, role badge, and current date strictly enclosed in a surface card.
 *    Global header language switch is supplied by CommonComponents, avoiding duplicate header controls.
 * 2. Calm Analytics-Led KPI Dashboard: Sourced honestly from TraderUiState (todaySalesTotalIls,
 *    todayInvoiceCount, shiftCashSalesIls, shiftCreditSalesIls). Labels clearly distinguish cash sales
 *    received from drawer expected cash, and today period sales from all-time ledger balances.
 *    Strictly guarded for authorized roles (Owner / Manager); cashier view preserves financial privacy.
 * 3. Restrained Selling Flow: Single dominant action without competing banners. Resumes active sale if basket
 *    contains items (home_resume_review_button) or triggers new sale (hero_new_sale_button).
 * 4. Tactile Secondary Quick Actions: 48dp minimum touch targets for Barcode Scanning and Quick Custom Items.
 *    Utilitarian Purchase bill action is guarded for supplier-authorized roles only.
 * 5. Card-Contained Operational Attention: Warning alert for low stock items linking cleanly to catalog
 *    (strictly NO restock mutation from Home).
 * 6. Card-Contained Recent Transactions: Section header, count, and view-all action are strictly enclosed
 *    within the ledger card surface, eliminating floating uncontained titles.
 * 7. Accessibility & Touch Ergonomics: >= 48dp touch targets, >= 12sp Arabic/English typography,
 *    full dark/light mode compatibility, bidi isolation, and adaptive 360dp / font2 stress resilience.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val isDark = state.isDarkTheme

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. TOP STORE IDENTITY & OPERATOR WELCOME CARD (STRICTLY CARD-CONTAINED)
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_welcome_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            // Storefront vector icon in royal blue (#255FD6) - Authentic parent brand mark (CircleShape)
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF255FD6).copy(alpha = 0.10f),
                                border = BorderStroke(1.dp, Color(0xFF255FD6).copy(alpha = 0.20f)),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Storefront,
                                        contentDescription = null,
                                        tint = Color(0xFF255FD6),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = lang.tr("التاجر الصغير", "Small Trader"),
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("home_store_title")
                                )
                                Text(
                                    text = "${state.businessInfo.name(lang)} • ${state.currentSession.name}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("home_store_subtitle")
                                )
                            }
                        }

                        // Operator role badge (no duplicate language toggle: global header owns language)
                        StitchBadge(
                            text = state.currentSession.role.label(lang),
                            tone = if (state.currentSession.role == UserRole.OWNER) StitchBadgeTone.PRIMARY else StitchBadgeTone.NEUTRAL
                        )
                    }

                    // Full date on dedicated width below identity row, eliminating 2.0x font scaling clipping
                    Text(
                        text = state.todayDate,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_date_text")
                    )
                }
            }
        }

        // 2. PRIMARY SELLING FLOW (Direct Merchant Action)
        item {
            if (hasActiveBasket) {
                // Resume Sale Action when Basket has Items
                Button(
                    onClick = onReviewBasket,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
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
                                modifier = Modifier.size(22.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    text = lang.tr(
                                        "متابعة البيع (${state.basketItemCount})",
                                        "Resume Sale (${state.basketItemCount})"
                                    ),
                                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = lang.tr("السلة الحالية غير مكتملة", "Active sale in progress"),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                                )
                            }
                        }

                        Text(
                            text = WesternFormatters.formatIls(state.basketNetTotalIls),
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                // Calm Primary New Sale Action
                Button(
                    onClick = onStartNewSale,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
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
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = lang.tr("بدء عملية بيع جديدة", "Start New Sale"),
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 3. CALM ANALYTICS-LED KPI DASHBOARD (GUARDED FOR OWNER / MANAGER ONLY)
// 2. CALM ANALYTICS-LED KPI DASHBOARD (GUARDED FOR OWNER / MANAGER ONLY)
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
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Section Header inside card: adaptive wrapping for 360dp under 2.0x font scaling
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.testTag("home_kpi_title_text")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Payments,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = lang.tr("مؤشرات مبيعات اليوم", "Today's Sales Performance"),
                                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.testTag("home_kpi_reports_action")
                            ) {
                                Text(
                                    text = lang.tr("عرض التقارير", "Reports"),
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        // Bento 1: Primary Today's Total Sales Card (Tinted Mint/Emerald)
                        val successBg = if (isDark) TraderTheme.financial.incomeContainer else Color(0xFFF0FDF4)
                        val successBorder = if (isDark) TraderTheme.financial.income.copy(alpha = 0.35f) else Color(0xFFBBF7D0)
                        val successText = if (isDark) TraderTheme.financial.income else Color(0xFF166534)

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = successBg,
                            border = BorderStroke(1.dp, successBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = lang.tr("مبيعات اليوم الكلية", "Today's Total Sales"),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                        color = successText,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = WesternFormatters.formatIls(state.todaySalesTotalIls),
                                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = successText
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = successBorder.copy(alpha = 0.45f),
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = "${WesternFormatters.formatInt(state.todayInvoiceCount)} ${lang.tr("فاتورة", "invoices")}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = successText,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        // Bento Split Row: Cash Sales Received vs Deferred Credit Sales
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Split Card 1: Cash Sales Received (Clearly distinguished from drawer cash!)
                            val cashBg = if (isDark) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color(0xFFEFF6FF)
                            val cashBorder = if (isDark) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color(0xFFBFDBFE)
                            val cashText = if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF1E40AF)

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = cashBg,
                                border = BorderStroke(1.dp, cashBorder),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = lang.tr("المقبوض نقداً", "Cash Received"),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                            color = cashText,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(
                                            imageVector = Icons.Default.PointOfSale,
                                            contentDescription = null,
                                            tint = cashText,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Text(
                                        text = WesternFormatters.formatIls(state.shiftCashSalesIls),
                                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = cashText
                                    )
                                    Text(
                                        text = lang.tr("مبيعات نقدية لليوم", "Today's cash sales"),
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        color = cashText.copy(alpha = 0.75f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Split Card 2: Deferred Credit Sales
                            val creditBg = if (isDark) TraderTheme.financial.warningContainer else Color(0xFFFFF5DF)
                            val creditBorder = if (isDark) TraderTheme.financial.warning.copy(alpha = 0.35f) else Color(0xFFFDE68A)
                            val creditText = if (isDark) TraderTheme.financial.warning else Color(0xFF92400E)

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = creditBg,
                                border = BorderStroke(1.dp, creditBorder),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = lang.tr("ذمم مؤجلة (آجل)", "Credit Sales"),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                            color = creditText,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(
                                            imageVector = Icons.Default.MenuBook,
                                            contentDescription = null,
                                            tint = creditText,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Text(
                                        text = WesternFormatters.formatIls(state.shiftCreditSalesIls),
                                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = creditText
                                    )
                                    Text(
                                        text = lang.tr("فواتير ذمة لليوم", "Today's credit sales"),
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        color = creditText.copy(alpha = 0.75f),
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

        // 3. RESTRAINED OPERATIONAL SALE ACTION (FAST SELLING PRESERVED)

                // 4. SECONDARY POS QUICK ACTIONS (TACTILE 48dp TARGETS)
        item {
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
                    color = MaterialTheme.colorScheme.surface,
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
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = lang.tr("مسح باركود", "Scan Barcode"),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
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
                    color = MaterialTheme.colorScheme.surface,
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
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = lang.tr("صنف سريع", "Custom Item"),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // 5. AUTHORIZED PURCHASE AFFORDANCE (OWNER / MANAGER ONLY)
        if (canManageSuppliers) {
            item {
                Surface(
                    onClick = onOpenPurchase,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("home_purchase_button"),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.LocalShipping,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }

                            Text(
                                text = lang.tr("المشتريات", "Purchases"),
                                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
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

        // 6. LOW STOCK ATTENTION CARD (CARD-CONTAINED, STRICTLY NO RESTOCK MUTATION)
        if (state.lowStockProducts.isNotEmpty()) {
            item {
                val warnBg = if (isDark) TraderTheme.financial.warningContainer else Color(0xFFFFF7ED)
                val warnBorder = if (isDark) TraderTheme.financial.warning.copy(alpha = 0.35f) else Color(0xFFFED7AA)
                val warnIconColor = if (isDark) TraderTheme.financial.warning else Color(0xFFC2410C)
                val warnTitleColor = if (isDark) TraderTheme.financial.warning else Color(0xFF7C2D12)
                val warnSubtitleColor = if (isDark) TraderTheme.financial.warning.copy(alpha = 0.85f) else Color(0xFF9A3412)

                Surface(
                    onClick = onNavigateToCatalog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("home_catalog_button"),
                    shape = RoundedCornerShape(12.dp),
                    color = warnBg,
                    border = BorderStroke(1.dp, warnBorder)
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
                                color = if (isDark) TraderTheme.financial.warning.copy(alpha = 0.2f) else Color(0xFFFFEDD5),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = warnIconColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = if (lang == AppLanguage.AR) {
                                        "تنبيه نواقص المخزون (${WesternFormatters.formatInt(state.lowStockProducts.size)} أصناف)"
                                    } else {
                                        "Low Stock Alert (${WesternFormatters.formatInt(state.lowStockProducts.size)} items)"
                                    },
                                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = warnTitleColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = state.lowStockProducts.take(2).joinToString(if (lang == AppLanguage.AR) "، " else ", ") { it.name(lang) } +
                                            if (state.lowStockProducts.size > 2) "..." else "",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = warnSubtitleColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = warnIconColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // 7. RECENT INVOICES TRANSACTIONS (PLANAR LEDGER CARD WITH CONTAINED HEADER)
        item {
            val recentInvoices = state.invoices.sortedByDescending { it.timestamp }.take(2)

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Contained Section Header: Icon + Title + 48dp View All TextButton
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ReceiptLong,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = lang.tr("آخر العمليات المسجلة", "Recent Transactions"),
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        TextButton(
                            onClick = onViewAllInvoices,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("view_all_invoices_button"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = lang.tr("عرض الكل", "View All"),
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 1.dp
                    )

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
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
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
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
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
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
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
