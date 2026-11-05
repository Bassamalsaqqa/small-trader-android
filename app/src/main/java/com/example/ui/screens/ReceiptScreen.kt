package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsBluetooth
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
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
import com.example.model.AppLanguage
import com.example.model.BusinessInfo
import com.example.model.Customer
import com.example.model.InvoiceRecord
import com.example.model.PrinterStatus
import com.example.model.WesternFormatters
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState
import java.math.BigDecimal
import java.math.RoundingMode

private enum class InvoiceHistoryFilter {
    ALL,
    PAID_CASH,
    ON_CREDIT
}

@Composable
fun ReceiptScreen(
    state: TraderUiState,
    onPrintReceipt: () -> Unit,
    onOpenPrinterSettings: () -> Unit,
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

    if (receipt == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(lang.tr("لا توجد وثيقة محددة", "No document selected"))
        }
        return
    }

    val linkedCustomer = remember(receipt.customerId, state.customers) {
        receipt.customerId?.let { cid -> state.customers.firstOrNull { it.id == cid } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            // 1. Context-aware Document Status Header (Newly Completed vs Revisited Record)
            if (state.isNewlyCompletedSale) {
                val isCash = receipt.isPaidInFull
                val containerColor = if (isCash) {
                    TraderTheme.financial.incomeContainer
                } else {
                    TraderTheme.financial.warningContainer
                }
                val accentColor = if (isCash) {
                    TraderTheme.financial.income
                } else {
                    TraderTheme.financial.debit
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = containerColor,
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("receipt_completion_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = if (isCash) {
                                        lang.tr("تم حفظ عملية البيع النقدي وإصدار الإيصال", "Cash Sale Completed & Receipt Issued")
                                    } else {
                                        lang.tr("تم تسجيل فاتورة البيع الآجل على حساب الزبون", "Credit Invoice Posted to Customer Account")
                                    },
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = accentColor
                                )
                                Text(
                                    text = if (isCash) {
                                        if (receipt.changeReturnedIls > 0.005) {
                                            lang.tr(
                                                "الباقي للزبون: ${WesternFormatters.formatIls(receipt.changeReturnedIls)} • المقبوض: ${WesternFormatters.formatIls(receipt.cashReceivedIls)}",
                                                "Change to return: ${WesternFormatters.formatIls(receipt.changeReturnedIls)} • Received: ${WesternFormatters.formatIls(receipt.cashReceivedIls)}"
                                            )
                                        } else {
                                            lang.tr(
                                                "استُلم المبلغ بالضبط (${WesternFormatters.formatIls(receipt.cashReceivedIls)}) — لا يوجد باقي",
                                                "Exact cash received (${WesternFormatters.formatIls(receipt.cashReceivedIls)}) — no change due"
                                            )
                                        }
                                    } else {
                                        lang.tr(
                                            "قُيد مبلغ ${WesternFormatters.formatIls(receipt.remainingDebitIls)} ذمة على الحساب (لم يُقبض نقد بالصندوق)",
                                            "${WesternFormatters.formatIls(receipt.remainingDebitIls)} recorded on customer account (0.00 ₪ cash drawer)"
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        ) {
                            Text(
                                text = WesternFormatters.formatInvoiceCode(receipt.invoiceNumber),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = accentColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            } else {
                // Revisiting an existing invoice from History or Home
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
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = lang.tr(
                                    "سجل وثيقة محفوظة • ${WesternFormatters.formatInvoiceCode(receipt.invoiceNumber)}",
                                    "Archived Document • ${WesternFormatters.formatInvoiceCode(receipt.invoiceNumber)}"
                                ),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (receipt.isPaidInFull) {
                                    lang.tr(
                                        "مسددة نقداً بالكامل بتاريخ ${receipt.timestamp}",
                                        "Paid in full (Cash) on ${receipt.timestamp}"
                                    )
                                } else {
                                    lang.tr(
                                        "فاتورة آجلة بقيمة ${WesternFormatters.formatIls(receipt.remainingDebitIls)} بتاريخ ${receipt.timestamp}",
                                        "Credit invoice of ${WesternFormatters.formatIls(receipt.remainingDebitIls)} issued on ${receipt.timestamp}"
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (linkedCustomer != null && onOpenCustomerAccount != null) {
                            TextButton(
                                onClick = { onOpenCustomerAccount(linkedCustomer) },
                                modifier = Modifier.testTag("receipt_open_customer_button")
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

            // 2. Trustworthy Invoice & Receipt Document Card (Clean, legible, no decorative paper theatre)
            val maxDocWidth = if (state.receiptPaperWidthMm == 58) 380.dp else 480.dp
            InvoiceReceiptDocumentCard(
                receipt = receipt,
                business = state.businessInfo,
                lang = lang,
                paperWidthMm = state.receiptPaperWidthMm,
                modifier = Modifier
                    .widthIn(max = maxDocWidth)
                    .fillMaxWidth()
            )

            // 3. Concise Print (Explicitly Simulated) & Share Actions Card
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
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = lang.tr("الطباعة والمشاركة", "Print & Share Document"),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = lang.tr("طباعة محاكاة", "Simulated Print"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (printer != null && printer.isPaired) {
                                    lang.tr(
                                        "الطابعة المحددة (محاكاة): ${printer.name} • عرض ${state.receiptPaperWidthMm}mm",
                                        "Selected printer (simulated): ${printer.name} • ${state.receiptPaperWidthMm}mm width"
                                    )
                                } else {
                                    lang.tr(
                                        "لا توجد طابعة متصلة حالياً — اختر طابعة متاحة أو شارك النص",
                                        "No printer currently connected — select an available printer or share text"
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (receipt.printCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = lang.tr(
                                        "معاينة ${WesternFormatters.formatInt(receipt.printCount)}×",
                                        "Previewed ${WesternFormatters.formatInt(receipt.printCount)}×"
                                    ),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Explicit Printer Status / Error / Retry Feedback
                    if (isPrinterErrorOrDisconnected) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (state.printerStatus == PrinterStatus.ERROR) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                TraderTheme.financial.warningContainer
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("printer_unavailable_banner")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (state.printerStatus == PrinterStatus.ERROR) {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        } else {
                                            TraderTheme.financial.warning
                                        },
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = lang.tr(state.printProgressStepAr, state.printProgressStepEn),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (state.printerStatus == PrinterStatus.ERROR) {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                                TextButton(onClick = onOpenPrinterSettings) {
                                    Text(
                                        text = lang.tr("اختيار طابعة", "Select Printer"),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    } else if (state.printerStatus == PrinterStatus.PRINTED_SUCCESS) {
                        Text(
                            text = lang.tr(state.printProgressStepAr, state.printProgressStepEn),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TraderTheme.financial.income
                        )
                    }

                    // Primary Print & Share Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onPrintReceipt,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1.35f)
                                .height(48.dp)
                                .testTag("print_bluetooth_receipt_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Print,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when {
                                    state.printerStatus == PrinterStatus.ERROR -> lang.tr("إعادة المحاولة", "Retry Print")
                                    receipt.printCount == 0 -> lang.tr("طباعة (محاكاة)", "Print (Simulated)")
                                    else -> lang.tr("إعادة طباعة", "Reprint")
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        OutlinedButton(
                            onClick = onOpenPrinterSettings,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("receipt_printer_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SettingsBluetooth,
                                contentDescription = lang.tr("إعدادات الطابعة", "Printer Settings"),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${state.receiptPaperWidthMm}mm",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily)
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val text = onShareReceipt?.invoke(receipt)
                                    ?: buildPlainTextDocument(receipt, state.businessInfo, lang)
                                localActionFeedback = lang.tr(
                                    "تم تجهيز نص الوثيقة للمشاركة (${WesternFormatters.formatInvoiceCode(receipt.invoiceNumber)})",
                                    "Document text prepared for sharing (${WesternFormatters.formatInvoiceCode(receipt.invoiceNumber)})"
                                )
                                runCatching {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, text)
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
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("share_receipt_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = lang.tr("مشاركة الوثيقة", "Share Document"),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val text = onCopyReceipt?.invoke(receipt)
                                    ?: buildPlainTextDocument(receipt, state.businessInfo, lang)
                                clipboardManager.setText(AnnotatedString(text))
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
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("copy_receipt_text_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = lang.tr("نسخ النص", "Copy Text"),
                                modifier = Modifier.size(18.dp)
                            )
                        }
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

        // Bottom Bar: Return Home or Start New Sale
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
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
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("receipt_home_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(lang.tr("الرئيسية", "Home"))
                }

                Button(
                    onClick = onStartNewSale,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp)
                        .testTag("receipt_new_sale_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PointOfSale,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = lang.tr("بيع جديد", "Start New Sale"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@Composable
private fun InvoiceReceiptDocumentCard(
    receipt: InvoiceRecord,
    business: BusinessInfo,
    lang: AppLanguage,
    paperWidthMm: Int,
    modifier: Modifier = Modifier
) {
    val isPaidCash = receipt.isPaidInFull

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
                color = if (isPaidCash) {
                    TraderTheme.financial.incomeContainer
                } else {
                    TraderTheme.financial.debitContainer
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
                        text = if (isPaidCash) {
                            lang.tr("إيصال قبض وفاتورة مبيعات نقدية", "Cash Sales Invoice & Receipt")
                        } else {
                            lang.tr("فاتورة مبيعات آجلة — ذمة على الحساب", "Credit Sales Invoice — On Account")
                        },
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (isPaidCash) {
                            TraderTheme.financial.income
                        } else {
                            TraderTheme.financial.debit
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
                        color = if (isPaidCash) {
                            TraderTheme.financial.income
                        } else {
                            TraderTheme.financial.debit
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
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "${WesternFormatters.formatInt(item.quantity)} × ${WesternFormatters.formatIls(item.product.priceIls)} (${item.product.unit(lang)})",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
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
                        useMono = true
                    )
                    DocumentMetaRow(
                        label = lang.tr("الخصم الممنوح:", "Discount:"),
                        value = "- ${WesternFormatters.formatIls(receipt.discountIls)}",
                        useMono = true,
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
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("receipt_net_total_value")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                if (isPaidCash) {
                    DocumentMetaRow(
                        label = lang.tr("طريقة السداد:", "Settlement:"),
                        value = lang.tr("مدفوع نقداً بالكامل", "Paid in Full (Cash)"),
                        useMono = false,
                        valueColor = TraderTheme.financial.income
                    )
                    DocumentMetaRow(
                        label = lang.tr("المبلغ المقبوض نقداً:", "Cash Received:"),
                        value = WesternFormatters.formatIls(receipt.cashReceivedIls),
                        useMono = true
                    )
                    DocumentMetaRow(
                        label = lang.tr("الباقي المرجع للزبون:", "Change Returned:"),
                        value = WesternFormatters.formatIls(receipt.changeReturnedIls),
                        useMono = true
                    )
                } else {
                    DocumentMetaRow(
                        label = lang.tr("طريقة السداد:", "Settlement:"),
                        value = lang.tr("ذمة آجلة على حساب الزبون", "Unpaid — On Customer Account"),
                        useMono = false,
                        valueColor = TraderTheme.financial.debit
                    )
                    DocumentMetaRow(
                        label = lang.tr("المدفوع نقداً عند الإصدار:", "Cash Paid at Issue:"),
                        value = WesternFormatters.formatIls(receipt.cashReceivedIls),
                        useMono = true
                    )
                    DocumentMetaRow(
                        label = lang.tr("المبلغ المقيد بالذمة:", "Balance Due on Account:"),
                        value = WesternFormatters.formatIls(receipt.remainingDebitIls),
                        useMono = true,
                        valueColor = TraderTheme.financial.debit
                    )
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
    val docTitle = if (receipt.isPaidInFull) {
        lang.tr("إيصال قبض وفاتورة مبيعات نقدية", "Cash Sales Invoice & Receipt")
    } else {
        lang.tr("فاتورة مبيعات آجلة (ذمة على الحساب)", "Credit Sales Invoice (On Account)")
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
        sb.appendLine("${lang.tr("المتبقي بالذمة", "Balance Due on Account")}: ${WesternFormatters.formatIls(receipt.remainingDebitIls)}")
    }
    return sb.toString().trim()
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

    val paidCount = remember(state.invoices) { state.invoices.count { it.isPaidInFull } }
    val creditCount = remember(state.invoices) { state.invoices.count { !it.isPaidInFull } }

    val filteredInvoices = remember(state.invoices, searchQuery, selectedFilter) {
        val q = TraderUiState.normalizeForSearch(WesternFormatters.sanitizeWesternDigits(searchQuery.trim()))
        state.invoices.filter { inv ->
            val matchesStatus = when (selectedFilter) {
                InvoiceHistoryFilter.ALL -> true
                InvoiceHistoryFilter.PAID_CASH -> inv.isPaidInFull
                InvoiceHistoryFilter.ON_CREDIT -> !inv.isPaidInFull
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
        filteredInvoices.filter { it.isPaidInFull }
            .fold(BigDecimal.ZERO) { acc, inv -> acc.add(BigDecimal(inv.netTotalIls.toString())) }
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }

    val filteredCreditTotalIls = remember(filteredInvoices) {
        filteredInvoices.filter { !it.isPaidInFull }
            .fold(BigDecimal.ZERO) { acc, inv -> acc.add(BigDecimal(inv.remainingDebitIls.toString())) }
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("invoices_history_screen")
    ) {
        // Sticky Search, Status Filters & Summary Header
        Surface(
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Compact Summary Bar: Paid Cash vs Credit Invoices + New Sale button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = lang.tr("مبيعات اليوم", "Today's Sales"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = WesternFormatters.formatIls(state.todaySalesTotalIls),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (filteredCreditTotalIls > 0.005) {
                            Column {
                                Text(
                                    text = lang.tr("فواتير آجلة بالعرض", "Credit in View"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = WesternFormatters.formatIls(filteredCreditTotalIls),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = TraderTheme.financial.debit
                                )
                            }
                        } else {
                            Column {
                                Text(
                                    text = lang.tr("مسددة بالعرض", "Paid in View"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = WesternFormatters.formatIls(filteredPaidTotalIls),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = TraderTheme.financial.income
                                )
                            }
                        }
                    }

                    FilledTonalButton(
                        onClick = onStartNewSale,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = lang.tr("بيع جديد", "New Sale"),
                            style = MaterialTheme.typography.labelLarge
                        )
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

                // Status Filter Chips (All / Paid Cash / On Credit)
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
                            modifier = Modifier.testTag("invoice_filter_ALL")
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
                            modifier = Modifier.testTag("invoice_filter_PAID")
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
                            modifier = Modifier.testTag("invoice_filter_CREDIT")
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

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
