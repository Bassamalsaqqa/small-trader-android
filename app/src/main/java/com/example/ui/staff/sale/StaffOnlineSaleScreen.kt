package com.example.ui.staff.sale

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.model.AppLanguage
import java.math.BigDecimal

/**
 * Screen presenting the staff online cash sale workspace.
 *
 * Architecture and Governance:
 * 1. Truthful Authority: Basket totals are strictly non-authoritative client ESTIMATES.
 *    The central Laravel accounting platform is the sole posting authority for ledger entries and taxes.
 * 2. Contract Honest: Displays explicit warnings when V1 contracts lack required V2 authorities
 *    (currency_minor_units, tax_inclusive on taxed products, server quote endpoint).
 * 3. Fails Closed: Submission is disabled whenever authority resolution is incomplete.
 * 4. Exact Decimal Arithmetic: Preserves exact decimal strings without Double conversion.
 */
@Composable
fun StaffOnlineSaleView(
    draft: StaffOnlineSaleDraft,
    authority: StaffOnlineSaleAuthorityResolution?,
    currencyCode: String,
    lang: AppLanguage,
    onUpdateLineQuantity: (lineId: String, newQuantity: PositiveDecimalQuantity) -> Unit,
    onRemoveLine: (lineId: String) -> Unit,
    onClearDraft: () -> Unit,
    onSubmitSale: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("staff_online_sale_view")
    ) {
        // 1. Non-authoritative estimate banner
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("staff_sale_estimate_banner"),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = lang.tr(
                            "معاينة تقديرية — الخادم هو المرجع المالي الوحيد",
                            "Estimated Preview — Server is Sole Financial Authority"
                        ),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = lang.tr(
                            "جميع المجاميع وحسابات الضريبة المعروضة هنا تقديرية محلية. الخادم المركزي يعتمد الفواتير والقيود المحاسبية الرسمية.",
                            "All totals and taxes shown here are local client estimates. The central server authoritatively posts official ledger records."
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // 2. Missing Contracts Fails-Closed Alert (when contracts are missing)
        if (authority != null && !authority.isDispatchPermitted) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("staff_sale_missing_contracts_card"),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = lang.tr(
                                "عقود الإرسال غير مكتملة — تم تعطيل الإرسال للحماية",
                                "Missing Dispatch Contracts — Submission Disabled (Fails Closed)"
                            ),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = lang.tr(
                            "تعمل السلة محلياً للعرض والتحرير، لكن لا يمكن إرسالها إلى الخادم لوجود فجوات في عقود V1 الحالية:",
                            "The draft basket works locally for editing, but dispatch is blocked due to V1 contract gaps:"
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    authority.missingContracts.forEach { gap ->
                        Text(
                            text = "• $gap",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                        )
                    }
                }
            }
        }

        // 3. Receipt view if sale was confirmed
        if (draft.receipt != null) {
            val receipt = draft.receipt
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("staff_sale_receipt_card"),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = lang.tr("تم تأكيد البيع النقدي", "Cash Sale Confirmed"),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        if (receipt.wasReplay) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = lang.tr("إعادة تأكيد", "Replayed"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "${lang.tr("رقم الفاتورة", "Invoice")}: ${receipt.invoiceNumber}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "${lang.tr("معرّف الفاتورة", "Invoice ULID")}: ${receipt.invoiceId.value}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "${lang.tr("المبلغ الإجمالي", "Grand Total")}: ${receipt.grandTotal.toPlainString()} $currencyCode",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${lang.tr("سند القبض", "Payment Receipt")}: ${receipt.paymentReceiptNumber ?: lang.tr("غير متوفر (مسجل دون رقم إيصال)", "None (recorded without payment receipt number)")}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "${lang.tr("وقت الاعتماد", "Posted At")}: ${receipt.postedAt}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onClearDraft,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("staff_sale_new_transaction_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(lang.tr("بدء معاملة جديدة", "Start New Transaction"), fontSize = 14.sp)
                    }
                }
            }
        }

        // 4. Draft basket list
        if (draft.lines.isEmpty() && draft.receipt == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("staff_sale_empty_basket"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PointOfSale,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = lang.tr("سلة البيع فارغة", "Sale basket is empty"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = lang.tr(
                            "انتقل إلى تبويب «سجل الأصناف» لإضافة منتجات إلى السلة",
                            "Go to the 'Product Catalog' tab to add items to the basket"
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (draft.lines.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("staff_sale_basket_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(draft.lines, key = { it.lineId }) { line ->
                    SaleDraftLineCard(
                        line = line,
                        currencyCode = currencyCode,
                        lang = lang,
                        onUpdateQuantity = { newQty -> onUpdateLineQuantity(line.lineId, newQty) },
                        onRemove = { onRemoveLine(line.lineId) }
                    )
                }
            }

            // 5. Totals & Tender action footer
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag("staff_sale_summary_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Subtotal
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = lang.tr("المجموع الفرعي (تقديري)", "Subtotal (Estimated)"),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${draft.estimatedSubtotal.toPlainString()} $currencyCode",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Tax
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = lang.tr("الضريبة (تقديري)", "Tax (Estimated)"),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${draft.estimatedTax.toPlainString()} $currencyCode",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Grand total tender
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = lang.tr("المبلغ المطلوب نقداً (كامل)", "Full Cash Tender Required"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = lang.tr("يشمل الضريبة المؤهلة", "Includes qualified tax"),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${draft.estimatedTotal.toPlainString()} $currencyCode",
                            fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Submission error notice
                    if (draft.submissionError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = draft.submissionError,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("staff_sale_submission_error")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onClearDraft,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("staff_sale_clear_button"),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !draft.isSubmitting
                        ) {
                            Text(lang.tr("إفراغ السلة", "Clear"), fontSize = 13.sp)
                        }

                        val canSubmit = authority?.isDispatchPermitted == true &&
                                draft.lines.isNotEmpty() &&
                                !draft.hasUnresolvedTaxContract &&
                                !draft.isSubmitting

                        Button(
                            onClick = onSubmitSale,
                            modifier = Modifier
                                .weight(2f)
                                .heightIn(min = 48.dp)
                                .testTag("staff_sale_submit_button"),
                            shape = RoundedCornerShape(8.dp),
                            enabled = canSubmit,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            if (draft.isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(lang.tr("جاري الإرسال...", "Submitting..."), fontSize = 14.sp)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PointOfSale,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(lang.tr("تأكيد البيع النقدي", "Confirm Cash Sale"), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual line card within the sale basket.
 */
@Composable
private fun SaleDraftLineCard(
    line: StaffOnlineSaleDraftLine,
    currencyCode: String,
    lang: AppLanguage,
    onUpdateQuantity: (PositiveDecimalQuantity) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("staff_sale_line_${line.lineId}"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = line.product.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "SKU: ${line.product.sku} • ${lang.tr("الوحدة", "Unit")}: ${line.unitCode}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("staff_sale_line_remove_${line.lineId}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = lang.tr("حذف", "Remove"),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Pricing & Quantity Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${line.unitPrice.toPlainString()} $currencyCode",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (line.taxRateId != null) {
                        Text(
                            text = "${lang.tr("ضريبة", "Tax")}: ${line.taxRate.toPlainString()}%",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    } else {
                        Text(
                            text = lang.tr("معفى من الضريبة", "No Tax (0.000000)"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                    if (line.hasUnresolvedTaxContract) {
                        Text(
                            text = lang.tr("عقد الضريبة الشاملة غير متوفر في V1", "tax_inclusive missing in V1"),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Quantity Stepper
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("staff_sale_quantity_stepper_${line.lineId}")
                ) {
                    IconButton(
                        onClick = {
                            val currentBd = line.quantity.value
                            if (currentBd > BigDecimal.ONE) {
                                val newBd = currentBd.subtract(BigDecimal.ONE)
                                onUpdateQuantity(PositiveDecimalQuantity(newBd.toPlainString()))
                            }
                        },
                        modifier = Modifier.size(36.dp),
                        enabled = line.quantity.value > BigDecimal.ONE
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "-",
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Text(
                        text = line.quantity.toPlainString(),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    IconButton(
                        onClick = {
                            val currentBd = line.quantity.value
                            val newBd = currentBd.add(BigDecimal.ONE)
                            onUpdateQuantity(PositiveDecimalQuantity(newBd.toPlainString()))
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "+",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Line Total
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${line.estimatedSubtotal.toPlainString()} $currencyCode",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
