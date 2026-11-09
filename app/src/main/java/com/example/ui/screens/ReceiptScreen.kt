package com.example.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsBluetooth
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.model.BusinessInfo
import com.example.model.Customer
import com.example.model.InvoicePaymentStatus
import com.example.model.InvoiceRecord
import com.example.model.PrinterStatus
import com.example.model.WesternFormatters
import com.example.ui.components.StitchBadge
import com.example.ui.components.StitchBadgeTone
import com.example.ui.components.StitchBidiUtils
import com.example.ui.components.StitchCard
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState
import java.math.BigDecimal
import java.math.RoundingMode

private enum class InvoiceHistoryFilter {
    ALL,
    PAID_CASH,
    PARTIAL,
    ON_CREDIT
}

/**
 * Native Material3 Receipt & Invoice Detail Screen (Stage 3 Checkout).
 *
 * Grounded in Stitch Project 15641450350510420845 / ST-13 (Receipt / Success):
 * 1. Centered Confident Success Hero (Newly Completed Sale):
 *    - Circular success badge (#14804A on #E8F5E9) with CheckCircle icon.
 *    - Dominant "تم تسجيل البيع بنجاح" headline.
 *    - Isolated bidi-safe invoice code and payment status pill.
 * 2. Historical Document State:
 *    - Clean archived banner (receipt_historical_banner) with zero completion celebration.
 *    - Detailed payment settlement status (Paid in full, Partial, or On Credit).
 * 3. Tasteful Compact Digital Paper Receipt Card (ST-13 Section 3):
 *    - Store identity header, timestamp, cashier attribution.
 *    - Compact itemized lines with Western digits and bidi-safe ₪.
 *    - Subtotal, discounts, and dominant net total (24sp bold).
 *    - Cash / Credit tender breakdown box.
 * 4. Collapsible Detailed Thermal Paper Preview (toggle_invoice_details_button).
 * 5. Secondary Actions Row (ST-13 Section 4, 48dp height buttons):
 *    - Simulated thermal bluetooth printing, paper width settings, Android system text share, copy text.
 *    - Sales return / credit note preview.
 * 6. Primary Next Action Dock (ST-13 Section 5):
 *    - Fixed prominent CTA "بدء بيع جديد" (receipt_new_sale_button).
 *    - Clean secondary "العودة للرئيسية" (receipt_home_button).
 */
@Composable
fun ReceiptScreen(
    state: TraderUiState,
    onPrintReceipt: () -> Unit,
    onOpenPrinterSettings: () -> Unit = {},
    onStartNewSale: () -> Unit,
    onBackToHome: () -> Unit,
    onShareReceipt: ((InvoiceRecord) -> String)? = null,
    onCopyReceipt: ((InvoiceRecord) -> String)? = null,
    onOpenCustomerAccount: ((Customer) -> Unit)? = null
) {
    val lang = state.language
    val receipt = state.activeReceipt ?: state.invoices.firstOrNull()
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var localActionFeedback by remember(receipt?.id) { mutableStateOf<String?>(null) }
    var isSalesReturnPreviewOpen by remember(receipt?.id) { mutableStateOf(false) }
    var isInvoiceDetailsExpanded by rememberSaveable(receipt?.id) { mutableStateOf(false) }

    if (receipt == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag("receipt_screen"),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = lang.tr("لا توجد وثيقة محددة", "No document selected"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val linkedCustomer = remember(receipt.customerId, state.customers) {
        receipt.customerId?.let { cid -> state.customers.firstOrNull { it.id == cid } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("receipt_screen")
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Quiet Outcome Hero (Newly Completed Sale vs Historical Document)
            if (state.isNewlyCompletedSale) {
                val isCash = receipt.isPaidInFull
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("receipt_completion_banner")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = TraderTheme.financial.incomeContainer,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = TraderTheme.financial.income,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        Text(
                            text = if (isCash) {
                                lang.tr("تم تسجيل البيع بنجاح", "Sale Completed Successfully")
                            } else {
                                lang.tr("تم تسجيل البيع الآجل بالذمة", "Credit Invoice Posted")
                            },
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StitchBadge(
                                text = StitchBidiUtils.formatInvoiceCode(receipt.invoiceNumber),
                                tone = StitchBadgeTone.PRIMARY
                            )
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            StitchBadge(
                                text = when (receipt.paymentStatus) {
                                    InvoicePaymentStatus.PAID_CASH -> lang.tr("نقداً", "Cash")
                                    InvoicePaymentStatus.PARTIAL -> lang.tr("سداد جزئي", "Partial")
                                    InvoicePaymentStatus.ON_CREDIT -> lang.tr("ذمة آجلة", "On Credit")
                                },
                                tone = when (receipt.paymentStatus) {
                                    InvoicePaymentStatus.PAID_CASH -> StitchBadgeTone.SUCCESS
                                    InvoicePaymentStatus.PARTIAL -> StitchBadgeTone.WARNING
                                    InvoicePaymentStatus.ON_CREDIT -> StitchBadgeTone.DANGER
                                }
                            )
                        }
                    }
                }
            } else {
                // Historical Document Banner (NO completion celebration)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("receipt_historical_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = StitchBidiUtils.formatInvoiceCode(receipt.invoiceNumber),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                StitchBadge(
                                    text = when (receipt.paymentStatus) {
                                        InvoicePaymentStatus.PAID_CASH -> lang.tr("مدفوع نقداً", "Paid Cash")
                                        InvoicePaymentStatus.PARTIAL -> lang.tr("سداد جزئي", "Partial Payment")
                                        InvoicePaymentStatus.ON_CREDIT -> lang.tr("ذمة آجلة", "On Credit")
                                    },
                                    tone = when (receipt.paymentStatus) {
                                        InvoicePaymentStatus.PAID_CASH -> StitchBadgeTone.SUCCESS
                                        InvoicePaymentStatus.PARTIAL -> StitchBadgeTone.WARNING
                                        InvoicePaymentStatus.ON_CREDIT -> StitchBadgeTone.DANGER
                                    }
                                )
                            }
                            Text(
                                text = when (receipt.paymentStatus) {
                                    InvoicePaymentStatus.PAID_CASH -> lang.tr(
                                        "مسددة نقداً بالكامل (${WesternFormatters.formatIls(receipt.netTotalIls)}) بتاريخ ${receipt.timestamp}",
                                        "Paid in full (Cash ${WesternFormatters.formatIls(receipt.netTotalIls)}) on ${receipt.timestamp}"
                                    )
                                    InvoicePaymentStatus.PARTIAL -> lang.tr(
                                        "سداد جزئي: مسدد ${WesternFormatters.formatIls(receipt.cashReceivedIls)} • متبقي ${WesternFormatters.formatIls(receipt.remainingDebitIls)} (الإجمالي ${WesternFormatters.formatIls(receipt.netTotalIls)})",
                                        "Partial: Paid ${WesternFormatters.formatIls(receipt.cashReceivedIls)} • Remaining ${WesternFormatters.formatIls(receipt.remainingDebitIls)} (Total ${WesternFormatters.formatIls(receipt.netTotalIls)})"
                                    )
                                    InvoicePaymentStatus.ON_CREDIT -> lang.tr(
                                        "فاتورة آجلة بقيمة ${WesternFormatters.formatIls(receipt.remainingDebitIls)} بتاريخ ${receipt.timestamp}",
                                        "Credit invoice of ${WesternFormatters.formatIls(receipt.remainingDebitIls)} issued on ${receipt.timestamp}"
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (linkedCustomer != null && onOpenCustomerAccount != null) {
                            TextButton(
                                onClick = { onOpenCustomerAccount(linkedCustomer) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("receipt_open_customer_button")
                            ) {
                                Text(
                                    text = lang.tr("دفتر الزبون", "Customer Account"),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }

            // 2. Tasteful Compact Digital Paper Receipt Card (ST-13 Section 3)
            StitchCard(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Receipt Store Header
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.businessInfo.name(lang),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${receipt.timestamp} • ${lang.tr("الكاشير:", "Cashier:")} ${lang.tr(receipt.cashierNameAr, receipt.cashierNameEn)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (linkedCustomer != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${lang.tr("الزبون:", "Customer:")} ${linkedCustomer.name(lang)}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (onOpenCustomerAccount != null && state.isNewlyCompletedSale) {
                                    TextButton(
                                        onClick = { onOpenCustomerAccount(linkedCustomer) },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier
                                            .heightIn(min = 36.dp)
                                            .testTag("receipt_open_customer_button")
                                    ) {
                                        Text(
                                            text = lang.tr("دفتر الحساب", "Ledger"),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 1.dp
                    )

                    // Itemized lines list
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        receipt.items.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${item.product.name(lang)} (${WesternFormatters.formatInt(item.quantity)}×)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = WesternFormatters.formatIls(item.lineTotal),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = MonoFontFamily
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 1.dp
                    )

                    // Financial Summary
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (receipt.discountIls > 0.005) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = lang.tr("المجموع قبل الخصم", "Subtotal"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = WesternFormatters.formatIls(receipt.subtotalIls),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = lang.tr("خصم", "Discount"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TraderTheme.financial.income
                                )
                                Text(
                                    text = "-${WesternFormatters.formatIls(receipt.discountIls)}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = MonoFontFamily
                                    ),
                                    color = TraderTheme.financial.income
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = if (receipt.isPaidInFull) {
                                    lang.tr("صافي الفاتورة المدفوع", "Total Net Paid")
                                } else {
                                    lang.tr("صافي الفاتورة", "Net Invoice Total")
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = WesternFormatters.formatIls(receipt.netTotalIls),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = MonoFontFamily
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Cash / Credit Tender Breakdown Box (ST-13)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            when (receipt.paymentStatus) {
                                InvoicePaymentStatus.PAID_CASH -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = lang.tr("المقبوض نقداً", "Cash Tendered"),
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = WesternFormatters.formatIls(receipt.cashReceivedIls),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = MonoFontFamily
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    if (receipt.changeReturnedIls > 0.005) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = lang.tr("الباقي (الفكة المسترجعة)", "Change Returned"),
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = TraderTheme.financial.income
                                            )
                                            Text(
                                                text = WesternFormatters.formatIls(receipt.changeReturnedIls),
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = MonoFontFamily
                                                ),
                                                color = TraderTheme.financial.income
                                            )
                                        }
                                    }
                                }
                                InvoicePaymentStatus.PARTIAL -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = lang.tr("المقبوض نقداً", "Cash Paid"),
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = WesternFormatters.formatIls(receipt.cashReceivedIls),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = MonoFontFamily
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = lang.tr("المتبقي بالذمة الآجلة", "Remaining Due (Credit)"),
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = TraderTheme.financial.warning
                                        )
                                        Text(
                                            text = WesternFormatters.formatIls(receipt.remainingDebitIls),
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = MonoFontFamily
                                            ),
                                            color = TraderTheme.financial.warning
                                        )
                                    }
                                }
                                InvoicePaymentStatus.ON_CREDIT -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = lang.tr("المقبوض نقداً", "Cash Paid"),
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "0.00 ₪",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = MonoFontFamily
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = lang.tr("المسجل كذمة مستحقة على الزبون", "Debited to Customer"),
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = TraderTheme.financial.debit
                                        )
                                        Text(
                                            text = WesternFormatters.formatIls(receipt.remainingDebitIls),
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = MonoFontFamily
                                            ),
                                            color = TraderTheme.financial.debit
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Collapsible Detailed Thermal Paper Content (ST-13 Paper Preview)
            val maxDocWidth = if (state.receiptPaperWidthMm == 58) 380.dp else 480.dp
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isInvoiceDetailsExpanded = !isInvoiceDetailsExpanded }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("toggle_invoice_details_button"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = lang.tr("معاينة الورقة الحرارية للطابعة", "Thermal Paper Preview"),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = lang.tr(
                                        "${WesternFormatters.formatInt(receipt.items.size)} أصناف • عرض الورقة ${state.receiptPaperWidthMm} مم",
                                        "${WesternFormatters.formatInt(receipt.items.size)} items • ${state.receiptPaperWidthMm}mm paper"
                                    ),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = { isInvoiceDetailsExpanded = !isInvoiceDetailsExpanded },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (isInvoiceDetailsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isInvoiceDetailsExpanded) lang.tr("طي", "Collapse") else lang.tr("توسيع", "Expand"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = isInvoiceDetailsExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            InvoiceReceiptDocumentCard(
                                receipt = receipt,
                                business = state.businessInfo,
                                lang = lang,
                                paperWidthMm = state.receiptPaperWidthMm,
                                modifier = Modifier
                                    .widthIn(max = maxDocWidth)
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // 4. Secondary Actions Card: Print (Simulated), Share & Return Preview (ST-13 Section 4)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val printer = state.connectedPrinter
                    val isPrinterErrorOrDisconnected =
                        state.printerStatus == PrinterStatus.ERROR ||
                            state.printerStatus == PrinterStatus.DISCONNECTED ||
                            printer == null ||
                            !printer.isPaired

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = lang.tr("خيارات التوثيق والمشاركة", "Documentation & Share"),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = lang.tr(
                                    "طباعة تجريبية أو مشاركة نص الفاتورة عبر النظام",
                                    "Simulated printing or native text sharing"
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (printer != null && printer.isPaired) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Text(
                                    text = "${printer.name} (${state.receiptPaperWidthMm}mm)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    if (isPrinterErrorOrDisconnected) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = TraderTheme.financial.warningContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("printer_unavailable_banner")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = TraderTheme.financial.warning,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = when {
                                        state.printerStatus == PrinterStatus.ERROR ->
                                            lang.tr(
                                                "تعذرت محاكاة الطباعة — اضغط زر الطباعة أدناه لإعادة المحاولة",
                                                "Thermal print simulation failed — tap print button below to retry"
                                            )
                                        printer == null ->
                                            lang.tr(
                                                "لا توجد طابعة مقترنة (يتم ضبط الطابعة حصرياً من شاشة الإعدادات)",
                                                "No printer paired (configure printer exclusively in Settings)"
                                            )
                                        else ->
                                            lang.tr(
                                                "طابعة المحاكاة غير متصلة (يتم ضبط الطابعة من شاشة الإعدادات)",
                                                "Simulated printer disconnected (configure in Settings)"
                                            )
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = TraderTheme.financial.warning
                                )
                            }
                        }
                    } else if (state.printerStatus == PrinterStatus.PRINTED_SUCCESS) {
                        Text(
                            text = lang.tr(state.printProgressStepAr, state.printProgressStepEn),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TraderTheme.financial.income
                        )
                    }

                    // Secondary Action Buttons (Accessible >= 48dp touch targets, icon-only print to prevent text wrapping)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Simulated Bluetooth Print (Icon-only >= 48dp, retaining print semantics & testTag)
                        val printContentDesc = when {
                            state.printerStatus == PrinterStatus.ERROR -> lang.tr("إعادة محاولة الطباعة", "Retry Print")
                            receipt.printCount == 0 -> lang.tr("طباعة إيصال", "Print Receipt")
                            else -> lang.tr("إعادة طباعة", "Reprint")
                        }

                        Button(
                            onClick = onPrintReceipt,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("print_bluetooth_receipt_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Print,
                                contentDescription = printContentDesc,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // System Share (WhatsApp / Native Chooser)
                        OutlinedButton(
                            onClick = {
                                val shareText = onShareReceipt?.invoke(receipt)
                                    ?: buildPlainTextDocument(receipt, state.businessInfo, lang)
                                localActionFeedback = lang.tr(
                                    "تم تجهيز نص الوثيقة للمشاركة (${WesternFormatters.formatInvoiceCode(receipt.invoiceNumber)})",
                                    "Document text prepared for sharing (${WesternFormatters.formatInvoiceCode(receipt.invoiceNumber)})"
                                )
                                runCatching {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                        type = "text/plain"
                                    }
                                    val shareIntent = Intent.createChooser(
                                        sendIntent,
                                        lang.tr("مشاركة الفاتورة / الإيصال", "Share Invoice / Receipt")
                                    )
                                    context.startActivity(shareIntent)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("share_receipt_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = lang.tr("مشاركة الوثيقة", "Share Document"),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Copy Text to Clipboard
                        OutlinedButton(
                            onClick = {
                                val copyText = onCopyReceipt?.invoke(receipt)
                                    ?: buildPlainTextDocument(receipt, state.businessInfo, lang)
                                clipboardManager.setText(AnnotatedString(copyText))
                                val count = state.receiptCopyCount + 1
                                localActionFeedback = if (count > 1) {
                                    lang.tr(
                                        "تم نسخ نص الوثيقة للحافظة مجدداً (${count}×)",
                                        "Document text copied to clipboard again (${count}×)"
                                    )
                                } else {
                                    lang.tr(
                                        "تم نسخ نص الوثيقة للحافظة",
                                        "Document text copied to clipboard"
                                    )
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("copy_receipt_text_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = lang.tr("نسخ النص", "Copy Text"),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Sales Return / Credit Note Preview Button
                    OutlinedButton(
                        onClick = { isSalesReturnPreviewOpen = true },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("open_sales_return_preview_button")
                    ) {
                        Text(
                            text = lang.tr(
                                "معاينة مرتجع مبيعات / إشعار دائن (Sales Return Preview)",
                                "Sales Return / Credit Note Preview"
                            ),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }

                    if (localActionFeedback != null) {
                        Text(
                            text = localActionFeedback!!,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = TraderTheme.financial.income,
                            modifier = Modifier.testTag("receipt_share_feedback")
                        )
                    }
                }
            }
        }

        // 5. Fixed Primary Next Action Dock (ST-13 Section 5)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onBackToHome,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("receipt_home_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (state.isNewlyCompletedSale) {
                            lang.tr("الرئيسية", "Home")
                        } else {
                            lang.tr("رجوع", "Back")
                        }
                    )
                }

                Button(
                    onClick = onStartNewSale,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .weight(1.5f)
                        .heightIn(min = 52.dp)
                        .testTag("receipt_new_sale_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PointOfSale,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.basketItemCount > 0) {
                            lang.tr(
                                "متابعة السلة (${WesternFormatters.formatInt(state.basketItemCount)})",
                                "Resume Sale (${WesternFormatters.formatInt(state.basketItemCount)})"
                            )
                        } else {
                            lang.tr("بدء بيع جديد", "Start New Sale")
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }

    if (isSalesReturnPreviewOpen) {
        SalesReturnPreviewDialog(
            receipt = receipt,
            lang = lang,
            onDismiss = { isSalesReturnPreviewOpen = false }
        )
    }
}

@Composable
private fun SalesReturnPreviewDialog(
    receipt: InvoiceRecord,
    lang: AppLanguage,
    onDismiss: () -> Unit
) {
    val firstLine = receipt.items.firstOrNull()
    val sampleReturnAmount = firstLine?.product?.priceIls ?: receipt.netTotalIls
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = lang.tr(
                    "معاينة مرتجع مبيعات / إشعار دائن (${WesternFormatters.formatInvoiceCode(receipt.invoiceNumber)})",
                    "Sales Return / Credit Note Preview (${WesternFormatters.formatInvoiceCode(receipt.invoiceNumber)})"
                ),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TraderTheme.financial.warningContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = lang.tr(
                            "معاينة تفاعلية: توضح أثر إرجاع صنف من الفاتورة على المخزون وحساب الزبون أو الصندوق.",
                            "Interactive Preview: Illustrates stock and customer/cash credit-note impact when returning an invoice item."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Text(
                    text = lang.tr("الأصناف القابلة للإرجاع من الوثيقة:", "Returnable Lines on Document:"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
                receipt.items.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.product.name(lang),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "${lang.tr("المباع:", "Sold:")} ${WesternFormatters.formatInt(item.quantity)} × ${WesternFormatters.formatIls(item.product.priceIls)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = WesternFormatters.formatIls(item.lineTotal),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    text = if (receipt.isPaidInFull) {
                        lang.tr(
                            "الأثر المالي المتوقع عند إرجاع وحدة واحدة: رد نقدي ${WesternFormatters.formatIls(sampleReturnAmount)} من الصندوق + إعادة +1 للمخزون.",
                            "Projected impact for 1-unit return: Cash refund of ${WesternFormatters.formatIls(sampleReturnAmount)} + restock +1 unit."
                        )
                    } else {
                        lang.tr(
                            "الأثر المالي المتوقع عند إرجاع وحدة واحدة: إشعار دائن يخفض ذمة الزبون بمقدار ${WesternFormatters.formatIls(sampleReturnAmount)} + إعادة +1 للمخزون.",
                            "Projected impact for 1-unit return: Credit note reducing customer debt by ${WesternFormatters.formatIls(sampleReturnAmount)} + restock +1 unit."
                        )
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = TraderTheme.financial.income,
                    modifier = Modifier.testTag("sales_return_impact_summary")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("close_sales_return_preview_button")
            ) {
                Text(lang.tr("إغلاق المعاينة", "Close Preview"))
            }
        }
    )
}

@Composable
private fun InvoiceReceiptDocumentCard(
    receipt: InvoiceRecord,
    business: BusinessInfo,
    lang: AppLanguage,
    paperWidthMm: Int,
    modifier: Modifier = Modifier
) {
    val paymentStatus = receipt.paymentStatus
    val isPaidCash = paymentStatus == com.example.model.InvoicePaymentStatus.PAID_CASH
    val isPartial = paymentStatus == com.example.model.InvoicePaymentStatus.PARTIAL

    Card(
        modifier = modifier.testTag("thermal_receipt_paper"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Store Identity Header
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = business.name(lang),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = business.city(lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " • ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = business.phone,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = "${lang.tr("الرقم الضريبي", "Tax ID")}: ${business.taxNumber} • ${paperWidthMm}mm",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 2. Document Type & Settlement Status Badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = when {
                    isPaidCash -> TraderTheme.financial.incomeContainer
                    isPartial -> TraderTheme.financial.warningContainer
                    else -> TraderTheme.financial.debitContainer
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            isPaidCash -> lang.tr("إيصال قبض وفاتورة مبيعات نقدية", "Cash Sales Invoice & Receipt")
                            isPartial -> lang.tr("فاتورة مبيعات — سداد جزئي والباقي بالذمة", "Sales Invoice — Partial Payment & Credit")
                            else -> lang.tr("فاتورة مبيعات آجلة — ذمة على الحساب", "Credit Sales Invoice — On Account")
                        },
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = when {
                            isPaidCash -> TraderTheme.financial.income
                            isPartial -> TraderTheme.financial.warning
                            else -> TraderTheme.financial.debit
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = WesternFormatters.formatInvoiceCode(receipt.invoiceNumber),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = when {
                            isPaidCash -> TraderTheme.financial.income
                            isPartial -> TraderTheme.financial.warning
                            else -> TraderTheme.financial.debit
                        }
                    )
                }
            }

            // 3. Document Metadata
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DocumentMetaRow(
                    label = lang.tr("رقم الوثيقة:", "Document No:"),
                    value = WesternFormatters.formatInvoiceCode(receipt.invoiceNumber),
                    useMono = true
                )
                DocumentMetaRow(
                    label = lang.tr("التاريخ والوقت:", "Date & Time:"),
                    value = receipt.timestamp,
                    useMono = true
                )
                DocumentMetaRow(
                    label = lang.tr("الزبون / البيان:", "Customer / Note:"),
                    value = receipt.customerNote,
                    useMono = false
                )
                DocumentMetaRow(
                    label = lang.tr("المسؤول:", "Cashier:"),
                    value = lang.tr(receipt.cashierNameAr, receipt.cashierNameEn),
                    useMono = false
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 4. Line Items Table Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lang.tr(
                        "الأصناف (${WesternFormatters.formatInt(receipt.items.size)} صنف • ${WesternFormatters.formatInt(receipt.totalQuantity)} وحدة)",
                        "Items (${WesternFormatters.formatInt(receipt.items.size)} lines • ${WesternFormatters.formatInt(receipt.totalQuantity)} units)"
                    ),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = lang.tr("المبلغ (₪)", "Amount (₪)"),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 5. Numbered Line Items
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                receipt.items.forEachIndexed { index, item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "${index + 1}. ${item.product.name(lang)}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = WesternFormatters.formatIls(item.lineTotal),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "${WesternFormatters.formatInt(item.quantity)} × ${WesternFormatters.formatIls(item.product.priceIls)} (${item.product.unit(lang)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 6. Totals & Settlement Breakdown
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (receipt.discountIls > 0.005) {
                    DocumentMetaRow(
                        label = lang.tr("المجموع الفرعي:", "Subtotal:"),
                        value = WesternFormatters.formatIls(receipt.subtotalIls),
                        useMono = false
                    )
                    DocumentMetaRow(
                        label = lang.tr("الخصم الممنوح:", "Discount:"),
                        value = "- ${WesternFormatters.formatIls(receipt.discountIls)}",
                        useMono = false,
                        valueColor = TraderTheme.financial.income
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lang.tr("الصافي المطلوب:", "Net Total:"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = WesternFormatters.formatIls(receipt.netTotalIls),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("receipt_net_total_value")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                when {
                    isPaidCash -> {
                        DocumentMetaRow(
                            label = lang.tr("طريقة السداد:", "Settlement:"),
                            value = lang.tr("مدفوع نقداً بالكامل", "Paid in Full (Cash)"),
                            useMono = false,
                            valueColor = TraderTheme.financial.income
                        )
                        DocumentMetaRow(
                            label = lang.tr("المبلغ المقبوض نقداً:", "Cash Received:"),
                            value = WesternFormatters.formatIls(receipt.cashReceivedIls),
                            useMono = false
                        )
                        DocumentMetaRow(
                            label = lang.tr("الباقي المرجع للزبون:", "Change Returned:"),
                            value = WesternFormatters.formatIls(receipt.changeReturnedIls),
                            useMono = false
                        )
                    }
                    isPartial -> {
                        DocumentMetaRow(
                            label = lang.tr("طريقة السداد:", "Settlement:"),
                            value = lang.tr("سداد جزئي (دفعة نقدية + باقي بالذمة)", "Partial Payment (Cash + Account Debt)"),
                            useMono = false,
                            valueColor = TraderTheme.financial.warning
                        )
                        DocumentMetaRow(
                            label = lang.tr("المدفوع نقداً:", "Cash Paid:"),
                            value = WesternFormatters.formatIls(receipt.cashReceivedIls),
                            useMono = false,
                            valueColor = TraderTheme.financial.income
                        )
                        DocumentMetaRow(
                            label = lang.tr("المتبقي بالذمة على الحساب:", "Remaining Due on Account:"),
                            value = WesternFormatters.formatIls(receipt.remainingDebitIls),
                            useMono = false,
                            valueColor = TraderTheme.financial.debit
                        )
                    }
                    else -> {
                        DocumentMetaRow(
                            label = lang.tr("طريقة السداد:", "Settlement:"),
                            value = lang.tr("ذمة آجلة على حساب الزبون", "Unpaid — On Customer Account"),
                            useMono = false,
                            valueColor = TraderTheme.financial.debit
                        )
                        DocumentMetaRow(
                            label = lang.tr("المدفوع نقداً عند الإصدار:", "Cash Paid at Issue:"),
                            value = WesternFormatters.formatIls(receipt.cashReceivedIls),
                            useMono = false
                        )
                        DocumentMetaRow(
                            label = lang.tr("المبلغ المقيد بالذمة:", "Balance Due on Account:"),
                            value = WesternFormatters.formatIls(receipt.remainingDebitIls),
                            useMono = false,
                            valueColor = TraderTheme.financial.debit
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentMetaRow(
    label: String,
    value: String,
    useMono: Boolean,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = if (useMono) MonoFontFamily else MaterialTheme.typography.bodySmall.fontFamily,
                fontWeight = FontWeight.SemiBold
            ),
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

private fun buildPlainTextDocument(
    receipt: InvoiceRecord,
    business: BusinessInfo,
    lang: AppLanguage
): String {
    val sb = StringBuilder()
    val docTitle = when (receipt.paymentStatus) {
        com.example.model.InvoicePaymentStatus.PAID_CASH -> lang.tr("إيصال قبض وفاتورة مبيعات نقدية", "Cash Sales Invoice & Receipt")
        com.example.model.InvoicePaymentStatus.PARTIAL -> lang.tr("فاتورة مبيعات بسداد جزئي", "Partial-Payment Sales Invoice")
        com.example.model.InvoicePaymentStatus.ON_CREDIT -> lang.tr("فاتورة مبيعات آجلة (ذمة على الحساب)", "Credit Sales Invoice (On Account)")
    }
    sb.appendLine(business.name(lang))
    sb.appendLine("${business.city(lang)} • ${business.phone}")
    sb.appendLine("--------------------------------")
    sb.appendLine("$docTitle: ${WesternFormatters.formatInvoiceCode(receipt.invoiceNumber)}")
    sb.appendLine("${lang.tr("التاريخ", "Date")}: ${receipt.timestamp}")
    sb.appendLine("${lang.tr("الزبون", "Customer")}: ${receipt.customerNote}")
    sb.appendLine("--------------------------------")
    receipt.items.forEachIndexed { idx, item ->
        sb.appendLine("${idx + 1}. ${item.product.name(lang)} (${WesternFormatters.formatInt(item.quantity)} × ${WesternFormatters.formatIls(item.product.priceIls)}) = ${WesternFormatters.formatIls(item.lineTotal)}")
    }
    sb.appendLine("--------------------------------")
    if (receipt.discountIls > 0.005) {
        sb.appendLine("${lang.tr("المجموع الفرعي", "Subtotal")}: ${WesternFormatters.formatIls(receipt.subtotalIls)}")
        sb.appendLine("${lang.tr("الخصم", "Discount")}: -${WesternFormatters.formatIls(receipt.discountIls)}")
    }
    sb.appendLine("${lang.tr("الصافي المطلوب", "Net Total")}: ${WesternFormatters.formatIls(receipt.netTotalIls)}")
    if (receipt.isPaidInFull) {
        sb.appendLine("${lang.tr("المقبوض نقداً", "Cash Received")}: ${WesternFormatters.formatIls(receipt.cashReceivedIls)}")
        sb.appendLine("${lang.tr("الباقي للزبون", "Change Returned")}: ${WesternFormatters.formatIls(receipt.changeReturnedIls)}")
    } else {
        if (receipt.cashReceivedIls > 0.005) {
            sb.appendLine("${lang.tr("المدفوع نقداً", "Cash Paid")}: ${WesternFormatters.formatIls(receipt.cashReceivedIls)}")
        }
        sb.appendLine("${lang.tr("المتبقي بالذمة", "Balance Due on Account")}: ${WesternFormatters.formatIls(receipt.remainingDebitIls)}")
    }
    return sb.toString().trim()
}

/**
 * Calculates the actual settled (paid) monetary portion of an invoice record.
 *
 * For fully paid invoices (isPaidInFull == true or remainingDebitIls <= 0.005),
 * the settled amount equals netTotalIls.
 * For partial or credit invoices, the settled amount is netTotalIls minus remainingDebitIls,
 * clamped to zero and rounded to 2 decimal places using HALF_UP.
 * This represents the true accounting settlement (net minus remaining debt),
 * avoiding conflation of cash tender (which may include change returned) with actual settled debt.
 */
fun computeInvoiceSettledPaidPortion(inv: InvoiceRecord): Double {
    val net = BigDecimal(inv.netTotalIls.toString())
    val debt = if (inv.isPaidInFull || inv.remainingDebitIls <= 0.005) {
        BigDecimal.ZERO
    } else {
        BigDecimal(inv.remainingDebitIls.toString())
    }
    return net.subtract(debt).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toDouble()
}

/**
 * Calculates the total settled paid amount for a list of invoice records.
 */
fun calculateFilteredInvoicesPaidTotalIls(invoices: List<InvoiceRecord>): Double {
    return invoices.fold(BigDecimal.ZERO) { acc, inv ->
        acc.add(BigDecimal(computeInvoiceSettledPaidPortion(inv).toString()))
    }.setScale(2, RoundingMode.HALF_UP).toDouble()
}

@Composable
fun InvoicesHistoryScreen(
    state: TraderUiState,
    onOpenInvoice: (InvoiceRecord) -> Unit,
    onStartNewSale: () -> Unit
) {
    val lang = state.language
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(InvoiceHistoryFilter.ALL) }

    val paidCount = remember(state.invoices) {
        state.invoices.count { it.paymentStatus == com.example.model.InvoicePaymentStatus.PAID_CASH }
    }
    val partialCount = remember(state.invoices) {
        state.invoices.count { it.paymentStatus == com.example.model.InvoicePaymentStatus.PARTIAL }
    }
    val creditCount = remember(state.invoices) {
        state.invoices.count { it.paymentStatus == com.example.model.InvoicePaymentStatus.ON_CREDIT }
    }

    val filteredInvoices = remember(state.invoices, searchQuery, selectedFilter) {
        val q = TraderUiState.normalizeForSearch(WesternFormatters.sanitizeWesternDigits(searchQuery.trim()))
        state.invoices.filter { inv ->
            val matchesStatus = when (selectedFilter) {
                InvoiceHistoryFilter.ALL -> true
                InvoiceHistoryFilter.PAID_CASH -> inv.paymentStatus == com.example.model.InvoicePaymentStatus.PAID_CASH
                InvoiceHistoryFilter.PARTIAL -> inv.paymentStatus == com.example.model.InvoicePaymentStatus.PARTIAL
                InvoiceHistoryFilter.ON_CREDIT -> inv.paymentStatus == com.example.model.InvoicePaymentStatus.ON_CREDIT
            }
            if (!matchesStatus) return@filter false
            if (q.isEmpty()) return@filter true

            val invNumStr = inv.invoiceNumber.toString()
            val invCodeStr = WesternFormatters.formatInvoiceCode(inv.invoiceNumber).lowercase()
            val noteNorm = TraderUiState.normalizeForSearch(inv.customerNote)
            val hasMatchingItem = inv.items.any { item ->
                TraderUiState.normalizeForSearch(item.product.nameAr).contains(q) ||
                    TraderUiState.normalizeForSearch(item.product.nameEn).contains(q)
            }
            invNumStr.contains(q) ||
                invCodeStr.contains(q) ||
                noteNorm.contains(q) ||
                inv.timestamp.contains(q) ||
                hasMatchingItem
        }
    }

    val filteredPaidTotalIls = remember(filteredInvoices) {
        calculateFilteredInvoicesPaidTotalIls(filteredInvoices)
    }

    val filteredCreditTotalIls = remember(filteredInvoices) {
        filteredInvoices.filter { !it.isPaidInFull && it.remainingDebitIls > 0.005 }
            .fold(BigDecimal.ZERO) { acc, inv -> acc.add(BigDecimal(inv.remainingDebitIls.toString())) }
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }

    val filteredTotalIls = remember(filteredInvoices) {
        filteredInvoices.fold(BigDecimal.ZERO) { acc, inv ->
            acc.add(BigDecimal(inv.netTotalIls.toString()))
        }.setScale(2, RoundingMode.HALF_UP).toDouble()
    }

    val canViewReports = state.currentSession.canViewReports()
    val isFiltered = selectedFilter != InvoiceHistoryFilter.ALL || searchQuery.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("invoices_history_screen")
    ) {
        // Generous Attractive Card Hero (R2-SALES-HISTORY Canonical Pattern)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header Top Row: Title, Store Identity & New Sale Action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("سجل فواتير البيع", "Sales Invoices"),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${state.businessInfo.name(lang)} • ${lang.tr("الوردية الحالية", "Current Shift")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = onStartNewSale,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = lang.tr("بيع جديد", "New Sale"),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }

                // Shift & Sales Summary Box (Tonal well with filter-responsive & permission-guarded totals)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Row 1: Invoice Count (Truthfully scoped) & Sales/View Total
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val countText = if (isFiltered) {
                                lang.tr(
                                    "${WesternFormatters.formatInt(filteredInvoices.size)} فاتورة بالعرض",
                                    "${WesternFormatters.formatInt(filteredInvoices.size)} invoices in view"
                                )
                            } else if (canViewReports) {
                                lang.tr(
                                    "${WesternFormatters.formatInt(state.todayInvoiceCount)} فواتير مسجلة اليوم",
                                    "${WesternFormatters.formatInt(state.todayInvoiceCount)} invoices recorded today"
                                )
                            } else {
                                lang.tr(
                                    "${WesternFormatters.formatInt(state.invoices.size)} فاتورة مسجلة",
                                    "${WesternFormatters.formatInt(state.invoices.size)} invoices recorded"
                                )
                            }

                            Text(
                                text = countText,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val totalLabel = if (isFiltered) {
                                    lang.tr("إجمالي المعروض: ", "Total in View: ")
                                } else if (canViewReports) {
                                    lang.tr("مبيعات اليوم: ", "Today's Sales: ")
                                } else {
                                    lang.tr("الإجمالي: ", "Total: ")
                                }
                                val totalValue = if (isFiltered || !canViewReports) {
                                    WesternFormatters.formatIls(filteredTotalIls)
                                } else {
                                    WesternFormatters.formatIls(state.todaySalesTotalIls)
                                }

                                Text(
                                    text = totalLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = totalValue,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = MonoFontFamily
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Row 2: Financial Breakdown (Truthful labels, responsive to filter or shift scope, guarded by permission)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isFiltered || !canViewReports) {
                                // Filter-responsive ledger totals (Operational scope visible to all roles)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = TraderTheme.financial.income,
                                        modifier = Modifier.size(8.dp)
                                    ) {}
                                    Text(
                                        text = "${lang.tr("مسدد بالعرض:", "Paid in View:")} ${WesternFormatters.formatIls(filteredPaidTotalIls)}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = MonoFontFamily,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = TraderTheme.financial.income
                                    )
                                }

                                if (filteredCreditTotalIls > 0.005) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = TraderTheme.financial.warning,
                                            modifier = Modifier.size(8.dp)
                                        ) {}
                                        Text(
                                            text = "${lang.tr("متبقي ذمم بالعرض:", "Unpaid in View:")} ${WesternFormatters.formatIls(filteredCreditTotalIls)}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = MonoFontFamily,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = TraderTheme.financial.warning
                                        )
                                    }
                                }
                            } else {
                                // Owner / Manager Default Unfiltered View: Real shift sales properties (truthfully labeled)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = TraderTheme.financial.income,
                                        modifier = Modifier.size(8.dp)
                                    ) {}
                                    Text(
                                        text = "${lang.tr("مبيعات نقدية (وردية):", "Shift Cash Sales:")} ${WesternFormatters.formatIls(state.shiftCashSalesIls)}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = MonoFontFamily,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = TraderTheme.financial.income
                                    )
                                }

                                if (state.shiftCreditSalesIls > 0.005) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = TraderTheme.financial.warning,
                                            modifier = Modifier.size(8.dp)
                                        ) {}
                                        Text(
                                            text = "${lang.tr("ذمم آجلة (وردية):", "Shift Credit Sales:")} ${WesternFormatters.formatIls(state.shiftCreditSalesIls)}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = MonoFontFamily,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = TraderTheme.financial.warning
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Search by invoice #, customer name, item name, or date
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("invoice_search_input"),
                    placeholder = {
                        Text(
                            text = lang.tr(
                                "ابحث برقم الفاتورة، اسم الزبون، الصنف أو التاريخ...",
                                "Search invoice #, customer, item, or date..."
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = lang.tr("مسح", "Clear")
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )

                // Status Filter Chips (All / Paid Cash / Partial / On Credit)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedFilter == InvoiceHistoryFilter.ALL,
                            onClick = { selectedFilter = InvoiceHistoryFilter.ALL },
                            label = {
                                Text(
                                    lang.tr(
                                        "الكل (${WesternFormatters.formatInt(state.invoices.size)})",
                                        "All (${WesternFormatters.formatInt(state.invoices.size)})"
                                    )
                                )
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("invoice_filter_ALL")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == InvoiceHistoryFilter.PAID_CASH,
                            onClick = { selectedFilter = InvoiceHistoryFilter.PAID_CASH },
                            label = {
                                Text(
                                    lang.tr(
                                        "مدفوع نقداً (${WesternFormatters.formatInt(paidCount)})",
                                        "Paid Cash (${WesternFormatters.formatInt(paidCount)})"
                                    )
                                )
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("invoice_filter_PAID")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == InvoiceHistoryFilter.PARTIAL,
                            onClick = { selectedFilter = InvoiceHistoryFilter.PARTIAL },
                            label = {
                                Text(
                                    lang.tr(
                                        "سداد جزئي (${WesternFormatters.formatInt(partialCount)})",
                                        "Partial (${WesternFormatters.formatInt(partialCount)})"
                                    )
                                )
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("invoice_filter_PARTIAL")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == InvoiceHistoryFilter.ON_CREDIT,
                            onClick = { selectedFilter = InvoiceHistoryFilter.ON_CREDIT },
                            label = {
                                Text(
                                    lang.tr(
                                        "ذمة آجلة (${WesternFormatters.formatInt(creditCount)})",
                                        "On Credit (${WesternFormatters.formatInt(creditCount)})"
                                    )
                                )
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("invoice_filter_CREDIT")
                        )
                    }
                }
            }
        }

        // Invoices List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filteredInvoices.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (searchQuery.isNotBlank() || selectedFilter != InvoiceHistoryFilter.ALL) {
                                    lang.tr("لا توجد فواتير مطابقة للبحث أو التصنيف", "No matching invoices found")
                                } else {
                                    lang.tr("لا توجد فواتير مسجلة", "No invoices recorded")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (searchQuery.isNotBlank() || selectedFilter != InvoiceHistoryFilter.ALL) {
                                TextButton(
                                    onClick = {
                                        searchQuery = ""
                                        selectedFilter = InvoiceHistoryFilter.ALL
                                    }
                                ) {
                                    Text(lang.tr("عرض كل الفواتير", "Show All Invoices"))
                                }
                            }
                        }
                    }
                }
            } else {
                items(filteredInvoices, key = { it.id }) { invoice ->
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

