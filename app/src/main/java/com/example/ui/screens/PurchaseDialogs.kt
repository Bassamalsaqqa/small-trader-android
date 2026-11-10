package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.Product
import com.example.model.PurchaseBill
import com.example.model.PurchaseLineItem
import com.example.model.StockMovement
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.model.WesternFormatters
import com.example.ui.components.StitchBadge
import com.example.ui.components.StitchBadgeTone
import com.example.ui.components.StitchBidiUtils
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Cohesive Purchasing Dialogs and Document Presentation Components.
 *
 * Implements strict domain validation, multi-line structured purchasing,
 * idempotent GRN operation replay guards, and immediate typed Result error surfacing.
 */

@Composable
fun PaySupplierDialog(
    supplier: Supplier,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onPaySupplier: (supplier: Supplier, amount: Double, paymentId: String) -> Result<SupplierPayment>
) {
    var submissionId by rememberSaveable(supplier.id) { mutableStateOf(UUID.randomUUID().toString()) }
    val initialPayStr = if (supplier.balanceOwedIls > 0.005) {
        WesternFormatters.formatNumber(supplier.balanceOwedIls).removeSuffix(".00")
    } else {
        ""
    }
    var payAmountText by rememberSaveable(supplier.id) { mutableStateOf(initialPayStr) }
    val enteredAmount = payAmountText.toDoubleOrNull() ?: 0.0
    var isSubmittingPay by rememberSaveable(supplier.id) { mutableStateOf(false) }
    var payInlineError by rememberSaveable(supplier.id) { mutableStateOf<String?>(null) }

    val projectedOwed = remember(supplier.balanceOwedIls, enteredAmount) {
        BigDecimal(supplier.balanceOwedIls.toString())
            .subtract(BigDecimal(enteredAmount.toString()))
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }

    AlertDialog(
        onDismissRequest = {
            if (!isSubmittingPay) {
                onDismiss()
            }
        },
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = lang.tr("صرف دفعة نقدية للمورد", "Pay Supplier from Cash Drawer"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (payInlineError != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { }
                            .testTag("supplier_pay_inline_error")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = payInlineError!!,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // Current Payable Status Card
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = supplier.name(lang),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${lang.tr("الرصيد المستحق:", "Balance Due:")} ${StitchBidiUtils.formatIls(supplier.balanceOwedIls)}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (supplier.balanceOwedIls > 0.005) TraderTheme.financial.debit else TraderTheme.financial.income
                        )
                    }
                }

                OutlinedTextField(
                    value = payAmountText,
                    onValueChange = {
                        payAmountText = WesternFormatters.sanitizeWesternDigits(it)
                        payInlineError = null
                    },
                    label = { Text(lang.tr("المبلغ المصروف نقداً (₪)", "Cash Amount (₪)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        textDirection = TextDirection.Ltr
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_pay_amount_input")
                )

                // Live Settlement Projection Card
                if (enteredAmount > 0.0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = when {
                                    projectedOwed > 0.005 -> lang.tr(
                                        "المتبقي للمورد: ${StitchBidiUtils.formatIls(projectedOwed)}",
                                        "Remaining due: ${StitchBidiUtils.formatIls(projectedOwed)}"
                                    )
                                    projectedOwed < -0.005 -> lang.tr(
                                        "دفعة مقدمة للمتجر: ${StitchBidiUtils.formatIls(-projectedOwed)}",
                                        "Store credit: ${StitchBidiUtils.formatIls(-projectedOwed)}"
                                    )
                                    else -> lang.tr(
                                        "حساب المورد خالص بالكامل (0.00 ₪)",
                                        "Account settled (0.00 ₪)"
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = when {
                                    projectedOwed > 0.005 -> TraderTheme.financial.debit
                                    projectedOwed < -0.005 -> TraderTheme.financial.warning
                                    else -> TraderTheme.financial.income
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isSubmittingPay && enteredAmount > 0.0) {
                        isSubmittingPay = true
                        payInlineError = null
                        val result = onPaySupplier(supplier, enteredAmount, submissionId)
                        isSubmittingPay = false
                        if (result.isSuccess) {
                            onDismiss()
                        } else {
                            val rawErr = result.exceptionOrNull()?.message ?: lang.tr(
                                "فشلت عملية صرف الدفعة للمورد، يُرجى المحاولة مرة أخرى",
                                "Supplier payment failed, please retry"
                            )
                            val parts = rawErr.split(" | ", limit = 2)
                            payInlineError = if (lang == AppLanguage.AR) {
                                parts.firstOrNull()?.trim() ?: rawErr
                            } else {
                                parts.getOrNull(1)?.trim() ?: parts.firstOrNull()?.trim() ?: rawErr
                            }
                        }
                    }
                },
                enabled = !isSubmittingPay && enteredAmount > 0.0,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_supplier_payment_button")
            ) {
                Text(
                    text = if (enteredAmount > 0.0) {
                        lang.tr(
                            "تأكيد صرف ${StitchBidiUtils.formatIls(enteredAmount)} للمورد",
                            "Confirm ${StitchBidiUtils.formatIls(enteredAmount)} Payment"
                        )
                    } else {
                        lang.tr("تأكيد الصرف", "Confirm Payment")
                    },
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (!isSubmittingPay) {
                        onDismiss()
                    }
                },
                enabled = !isSubmittingPay,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}

@Composable
fun PhysicalGoodsReceiptDialog(
    bill: PurchaseBill,
    products: List<Product>,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirmGrnResult: (operationId: String, productId: String, receivedQty: Int, batchNote: String) -> Result<StockMovement>,
    errorMessage: String? = null,
    onConfirmGrnResultWithLine: ((operationId: String, productId: String, receivedQty: Int, batchNote: String, lineId: String?) -> Result<StockMovement>)? = null
) {
    var submissionId by rememberSaveable(bill.id) { mutableStateOf(UUID.randomUUID().toString()) }

    // Multi-line support: select active line
    val lines = bill.effectiveLines
    var selectedLineId by rememberSaveable(bill.id) {
        mutableStateOf(lines.firstOrNull { (it.quantity - it.receivedQuantity) > 0 }?.id ?: lines.firstOrNull()?.id ?: "")
    }

    val selectedLine = remember(lines, selectedLineId) {
        lines.firstOrNull { it.id == selectedLineId } ?: lines.firstOrNull()
    }

    val expectedProduct = remember(products, selectedLine) {
        products.firstOrNull { it.id == selectedLine?.productId && it.isStockTracked }
    }

    val pendingQty = if (selectedLine != null) {
        (selectedLine.quantity - selectedLine.receivedQuantity).coerceAtLeast(0)
    } else {
        bill.pendingQuantity.coerceAtLeast(0)
    }

    val maxReceivableQty = if (pendingQty > 0) pendingQty else (selectedLine?.quantity ?: bill.expectedQuantity).coerceAtLeast(0)
    var receivingQtyText by rememberSaveable(bill.id, selectedLineId) { mutableStateOf(maxReceivableQty.toString()) }
    val receivingQty = receivingQtyText.toIntOrNull() ?: 0
    val isQtyValid = receivingQty > 0 && receivingQty <= maxReceivableQty

    var batchNote by rememberSaveable(bill.id) {
        mutableStateOf("استلام مخزني مطابق للفاتورة ${WesternFormatters.formatBillCode(bill.billNumber)}")
    }
    var isSubmittingGrn by rememberSaveable(bill.id) { mutableStateOf(false) }
    var grnInlineError by rememberSaveable(bill.id) { mutableStateOf(errorMessage) }
    val canConfirm = !bill.isReceived && expectedProduct != null && maxReceivableQty > 0

    val previouslyReceived = selectedLine?.receivedQuantity ?: bill.receivedQuantity
    val remainingAfterThis = (maxReceivableQty - receivingQty).coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = {
            if (!isSubmittingGrn) {
                onDismiss()
            }
        },
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = lang.tr(
                    "سند استلام بضاعة للمستودع • ${WesternFormatters.formatBillCode(bill.billNumber)}",
                    "Goods Receipt • ${WesternFormatters.formatBillCode(bill.billNumber)}"
                ),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val currentError = grnInlineError ?: errorMessage
                if (!currentError.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { }
                            .testTag("goods_receipt_inline_error")
                    ) {
                        Text(
                            text = currentError,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("goods_receipt_error")
                        )
                    }
                }

                // If multiple lines, show line selector chips
                if (lines.size > 1) {
                    Text(
                        text = lang.tr("اختر الصنف المراد استلامه:", "Select line to receive:"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(lines, key = { it.id }) { line ->
                            val linePending = (line.quantity - line.receivedQuantity).coerceAtLeast(0)
                            FilterChip(
                                selected = line.id == selectedLineId,
                                onClick = {
                                    selectedLineId = line.id
                                    val newMax = linePending.coerceAtLeast(0)
                                    receivingQtyText = newMax.toString()
                                },
                                label = {
                                    Text("${line.name(lang)} ($linePending)")
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("grn_product_chip_${line.productId}")
                            )
                        }
                    }
                }

                if (expectedProduct != null && maxReceivableQty > 0) {
                    val unitStr = expectedProduct.unit(lang)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("grn_product_chip_${expectedProduct.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = expectedProduct.name(lang),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = lang.tr("الكمية المطلوبة بالفاتورة:", "Ordered Qty:"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${WesternFormatters.formatInt(selectedLine?.quantity ?: bill.expectedQuantity)} $unitStr",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            if (previouslyReceived > 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = lang.tr("المستلم سابقاً:", "Previously Received:"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${WesternFormatters.formatInt(previouslyReceived)} $unitStr",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = lang.tr("المتبقي بعد هذا الاستلام:", "Remaining After:"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${WesternFormatters.formatInt(remainingAfterThis)} $unitStr",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (remainingAfterThis == 0) MaterialTheme.colorScheme.primary else TraderTheme.financial.warning
                                    ),
                                    modifier = Modifier.testTag("grn_qty_label")
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = receivingQtyText,
                        onValueChange = {
                            receivingQtyText = WesternFormatters.sanitizeWesternDigits(it)
                            grnInlineError = null
                        },
                        label = { Text(lang.tr("الكمية المستلمة الآن", "Quantity Received Now")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        isError = !isQtyValid && receivingQtyText.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("grn_qty_input")
                    )

                    OutlinedTextField(
                        value = batchNote,
                        onValueChange = { batchNote = it },
                        label = { Text(lang.tr("ملاحظة الاستلام المخزني", "Receipt Note")) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("grn_note_input")
                    )
                } else {
                    Text(
                        text = lang.tr(
                            "هذه الفاتورة لا تحتوي على صنف محدد قابل للتتبع المخزني.",
                            "This bill does not have a linked stock-tracked product."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isSubmittingGrn && canConfirm && expectedProduct != null) {
                        isSubmittingGrn = true
                        grnInlineError = null
                        val result = onConfirmGrnResultWithLine?.invoke(submissionId, expectedProduct.id, receivingQty, batchNote, selectedLine?.id)
                            ?: onConfirmGrnResult(submissionId, expectedProduct.id, receivingQty, batchNote)
                        isSubmittingGrn = false
                        if (result.isSuccess) {
                            onDismiss()
                        } else {
                            val rawErr = result.exceptionOrNull()?.message ?: lang.tr(
                                "فشل تأكيد الاستلام المخزني، يُرجى التحقق وإعادة المحاولة",
                                "Goods receipt confirmation failed, please verify and retry"
                            )
                            val parts = rawErr.split(" | ", limit = 2)
                            grnInlineError = if (lang == AppLanguage.AR) {
                                parts.firstOrNull()?.trim() ?: rawErr
                            } else {
                                parts.getOrNull(1)?.trim() ?: parts.firstOrNull()?.trim() ?: rawErr
                            }
                        }
                    }
                },
                enabled = !isSubmittingGrn && canConfirm && isQtyValid,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_grn_submit_button")
            ) {
                Text(
                    text = lang.tr("تأكيد الاستلام المخزني", "Confirm Goods Receipt"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (!isSubmittingGrn) {
                        onDismiss()
                    }
                },
                enabled = !isSubmittingGrn,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}

@Composable
fun PhysicalGoodsReceiptDialog(
    bill: PurchaseBill,
    products: List<Product>,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirmGrn: (productId: String, receivedQty: Int, batchNote: String) -> Boolean,
    errorMessage: String? = null
) {
    PhysicalGoodsReceiptDialog(
        bill = bill,
        products = products,
        lang = lang,
        onDismiss = onDismiss,
        onConfirmGrnResult = { _, productId, receivedQty, batchNote ->
            val ok = onConfirmGrn(productId, receivedQty, batchNote)
            if (ok) {
                Result.success(
                    StockMovement(
                        id = "sm_legacy",
                        productId = productId,
                        date = "today",
                        delta = receivedQty,
                        reasonAr = batchNote,
                        reasonEn = batchNote,
                        resultingStock = 0
                    )
                )
            } else {
                Result.failure(Exception(errorMessage ?: "Goods receipt failed"))
            }
        },
        errorMessage = errorMessage
    )
}

/**
 * Data class representing a temporary line entered in the purchase dialog.
 */
data class EnteredPurchaseLine(
    val product: Product,
    var quantityText: String = "1",
    var unitCostText: String = ""
) {
    val quantity: Int get() = quantityText.toIntOrNull() ?: 0
    val unitCost: Double get() = unitCostText.toDoubleOrNull() ?: 0.0
    val lineTotal: BigDecimal get() = if (quantity > 0 && unitCost >= 0.0) {
        BigDecimal(unitCost.toString()).multiply(BigDecimal(quantity)).setScale(2, RoundingMode.HALF_UP)
    } else BigDecimal.ZERO
}

enum class PurchaseBillMode {
    STRUCTURED,
    FINANCIAL_ONLY
}

val PurchaseLineListSaver: Saver<SnapshotStateList<PurchaseLineItem>, Any> = listSaver(
    save = { stateList ->
        stateList.map { line ->
            listOf(
                line.productId,
                line.productNameAr,
                line.productNameEn,
                line.quantity,
                line.unitCostIls,
                line.receivedQuantity,
                line.id
            )
        }
    },
    restore = { rawList ->
        val restored = mutableStateListOf<PurchaseLineItem>()
        @Suppress("UNCHECKED_CAST")
        (rawList as List<List<Any>>).forEach { items ->
            restored.add(
                PurchaseLineItem(
                    productId = items[0] as String,
                    productNameAr = items[1] as String,
                    productNameEn = items[2] as String,
                    quantity = (items[3] as Number).toInt(),
                    unitCostIls = (items[4] as Number).toDouble(),
                    receivedQuantity = (items[5] as Number).toInt(),
                    id = items[6] as String
                )
            )
        }
        restored
    }
)

@Composable
fun ReceivePurchaseBillDialog(
    state: TraderUiState,
    onDismiss: () -> Unit,
    onConfirmBill: ((supplierId: String, itemsSummary: String, totalIls: Double, paidNowIls: Double, expectedProductId: String?, expectedQuantity: Int) -> Boolean)? = null,
    onConfirmStructuredBill: ((supplierId: String, lines: List<PurchaseLineItem>, paidNowIls: Double, note: String) -> Result<PurchaseBill>)? = null
) {
    val lang = state.language
    val suppliers = state.suppliers
    if (suppliers.isEmpty()) return

    var billMode by rememberSaveable { mutableStateOf(PurchaseBillMode.STRUCTURED) }
    var submissionId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var selectedSupplierId by rememberSaveable(state.selectedSupplierForDetail?.id) {
        mutableStateOf(state.selectedSupplierForDetail?.id ?: suppliers.first().id)
    }
    var purchaseDesc by rememberSaveable { mutableStateOf("") }
    var freeformTotal by rememberSaveable { mutableStateOf("") }
    var purchasePaid by rememberSaveable { mutableStateOf("0") }
    var linkedProductId by rememberSaveable { mutableStateOf<String?>(null) }
    var linkedQuantityText by rememberSaveable { mutableStateOf("") }
    var unitCostText by rememberSaveable { mutableStateOf("") }
    var isSubmitting by rememberSaveable { mutableStateOf(false) }
    var inlineError by rememberSaveable { mutableStateOf<String?>(null) }

    val trackedProducts = remember(state.products) { state.products.filter { it.isStockTracked } }

    val structuredLines = rememberSaveable(saver = PurchaseLineListSaver) { mutableStateListOf<PurchaseLineItem>() }

    val derivedTotal: BigDecimal = structuredLines.fold(BigDecimal.ZERO) { acc, l ->
        acc.add(BigDecimal(l.unitCostIls.toString()).multiply(BigDecimal(l.quantity.toString())))
    }.setScale(2, RoundingMode.HALF_UP)

    val totalVal: Double = if (billMode == PurchaseBillMode.STRUCTURED) {
        derivedTotal.toDouble()
    } else {
        freeformTotal.toDoubleOrNull() ?: 0.0
    }

    val paidVal = if (purchasePaid.isBlank()) 0.0 else (purchasePaid.toDoubleOrNull() ?: -1.0)
    val isPaidValid = paidVal >= 0.0 && (totalVal == 0.0 || paidVal <= totalVal + 0.001)

    val effectiveDesc = if (billMode == PurchaseBillMode.STRUCTURED) {
        purchaseDesc.ifBlank {
            structuredLines.joinToString(" • ") { "${it.quantity}x ${it.name(lang)}" }
        }
    } else {
        purchaseDesc.trim()
    }

    val isFormValid = if (billMode == PurchaseBillMode.STRUCTURED) {
        structuredLines.isNotEmpty() && totalVal > 0.0 && isPaidValid && effectiveDesc.isNotBlank()
    } else {
        effectiveDesc.isNotBlank() && totalVal > 0.0 && isPaidValid
    }

    val payableAdded = if (totalVal > 0.0 && isPaidValid) {
        BigDecimal(totalVal.toString())
            .subtract(BigDecimal(paidVal.toString()))
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
            .coerceAtLeast(0.0)
    } else 0.0

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = lang.tr("فاتورة مشتريات وتوريد", "Purchase & Inward Bill"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (inlineError != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { }
                            .testTag("purchase_bill_inline_error")
                    ) {
                        Text(
                            text = inlineError!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }

                // Bill Mode Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = billMode == PurchaseBillMode.STRUCTURED,
                        onClick = {
                            billMode = PurchaseBillMode.STRUCTURED
                            inlineError = null
                        },
                        label = { Text(lang.tr("فاتورة أصناف مخزنية", "Itemized Stock Bill")) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("purchase_bill_mode_structured")
                    )
                    FilterChip(
                        selected = billMode == PurchaseBillMode.FINANCIAL_ONLY,
                        onClick = {
                            billMode = PurchaseBillMode.FINANCIAL_ONLY
                            inlineError = null
                        },
                        label = { Text(lang.tr("قيد مالي فقط", "Financial-Only Bill")) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("purchase_bill_mode_financial")
                    )
                }

                // Supplier Picker
                Text(
                    text = lang.tr("المورد:", "Supplier:"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(suppliers, key = { it.id }) { s ->
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                            FilterChip(
                                selected = s.id == selectedSupplierId,
                                onClick = { selectedSupplierId = s.id },
                                label = { Text(s.name(lang)) },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = purchaseDesc,
                    onValueChange = {
                        purchaseDesc = it
                        inlineError = null
                    },
                    label = {
                        Text(
                            if (billMode == PurchaseBillMode.STRUCTURED) {
                                lang.tr("بيان الفاتورة (اختياري، يولد تلقائياً)", "Bill Summary (Optional, Auto-generated)")
                            } else {
                                lang.tr("بيان الفاتورة / الأصناف *", "Bill Summary / Items *")
                            }
                        )
                    },
                    placeholder = { Text(lang.tr("مثال: توريد زيت زيتون وزعتر", "e.g. Olive Oil & Zaatar")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchase_bill_summary_input")
                )

                if (billMode == PurchaseBillMode.STRUCTURED && trackedProducts.isNotEmpty()) {
                    Text(
                        text = lang.tr("إضافة صنف لمخزون الفاتورة:", "Add item to bill inventory:"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(trackedProducts, key = { it.id }) { p ->
                            FilterChip(
                                selected = linkedProductId == p.id,
                                onClick = {
                                    linkedProductId = p.id
                                    if (purchaseDesc.isBlank()) {
                                        purchaseDesc = "توريد ${p.name(lang)}"
                                    }
                                    if (unitCostText.isBlank()) {
                                        unitCostText = WesternFormatters.formatNumber(p.costIls).removeSuffix(".00")
                                    }
                                    if (linkedQuantityText.isBlank()) {
                                        linkedQuantityText = "1"
                                    }
                                },
                                label = { Text(p.name(lang)) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("chip_product_${p.id}")
                            )
                        }
                    }

                    if (linkedProductId != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = linkedQuantityText,
                                onValueChange = {
                                    linkedQuantityText = WesternFormatters.sanitizeWesternDigits(it)
                                    inlineError = null
                                },
                                label = { Text(lang.tr("الكمية", "Quantity")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("purchase_bill_linked_qty_input")
                            )
                            OutlinedTextField(
                                value = unitCostText,
                                onValueChange = {
                                    unitCostText = WesternFormatters.sanitizeWesternDigits(it)
                                    inlineError = null
                                },
                                label = { Text(lang.tr("تكلفة الوحدة (₪)", "Unit Cost (₪)")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("purchase_bill_unit_cost_input")
                            )
                        }

                        val candidateProd = trackedProducts.firstOrNull { it.id == linkedProductId }
                        val candidateQty = linkedQuantityText.toIntOrNull() ?: 0
                        val candidateCostDouble = unitCostText.toDoubleOrNull()
                        val isCostEntered = unitCostText.isNotBlank() && candidateCostDouble != null && candidateCostDouble >= 0.0 && !candidateCostDouble.isNaN() && !candidateCostDouble.isInfinite()
                        val canAddCandidate = candidateProd != null && candidateQty > 0 && isCostEntered

                        OutlinedButton(
                            onClick = {
                                val p = candidateProd ?: return@OutlinedButton
                                val q = candidateQty
                                val c = candidateCostDouble ?: return@OutlinedButton
                                if (q > 0 && c >= 0.0) {
                                    // Preserve exact priced lines with unique line identity
                                    structuredLines.add(
                                        PurchaseLineItem(
                                            productId = p.id,
                                            productNameAr = p.nameAr,
                                            productNameEn = p.nameEn,
                                            quantity = q,
                                            unitCostIls = c,
                                            receivedQuantity = 0,
                                            id = UUID.randomUUID().toString()
                                        )
                                    )
                                    if (purchaseDesc.isBlank()) {
                                        purchaseDesc = structuredLines.joinToString(" • ") { "${it.quantity}x ${it.name(lang)}" }
                                    }
                                    linkedProductId = null
                                    linkedQuantityText = ""
                                    unitCostText = ""
                                    inlineError = null
                                }
                            },
                            enabled = canAddCandidate,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("add_purchase_bill_line_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(lang.tr("إضافة هذا الصنف للفاتورة", "Add This Item to Bill"))
                        }
                    }

                    if (structuredLines.isNotEmpty()) {
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("structured_purchase_lines_container")
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = lang.tr("الأصناف المسجلة في الفاتورة (${structuredLines.size}):", "Recorded Bill Items (${structuredLines.size}):"),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                structuredLines.forEachIndexed { idx, line ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().testTag("structured_purchase_line_${line.id}"),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                line.name(lang),
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                                            )
                                            Text(
                                                "${line.quantity} × ${WesternFormatters.formatIls(line.unitCostIls)} = ${WesternFormatters.formatIls(line.totalCostIls)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                structuredLines.removeAt(idx)
                                            },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("btn_remove_line_${line.productId}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Remove",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Authoritative Total Field
                OutlinedTextField(
                    value = if (billMode == PurchaseBillMode.STRUCTURED) {
                        if (structuredLines.isNotEmpty()) derivedTotal.toPlainString() else ""
                    } else {
                        freeformTotal
                    },
                    onValueChange = {
                        if (billMode == PurchaseBillMode.FINANCIAL_ONLY) {
                            freeformTotal = WesternFormatters.sanitizeWesternDigits(it)
                            inlineError = null
                        }
                    },
                    readOnly = (billMode == PurchaseBillMode.STRUCTURED),
                    label = {
                        Text(
                            if (billMode == PurchaseBillMode.STRUCTURED) {
                                lang.tr("إجمالي الفاتورة (₪) [محسوب تلقائياً من الأصناف]", "Bill Total (₪) [Calculated from Lines]")
                            } else {
                                lang.tr("إجمالي الفاتورة (₪) *", "Bill Total (₪) *")
                            }
                        )
                    },
                    supportingText = if (billMode == PurchaseBillMode.STRUCTURED) {
                        {
                            Text(
                                lang.tr(
                                    "القيمة محسوبة ومطابقة لبنود الأصناف لمنع أي تباين مالي",
                                    "Authoritative total derived strictly from line items"
                                ),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        textDirection = TextDirection.Ltr
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchase_bill_total_input")
                )

                OutlinedTextField(
                    value = purchasePaid,
                    onValueChange = {
                        purchasePaid = WesternFormatters.sanitizeWesternDigits(it)
                        inlineError = null
                    },
                    label = { Text(lang.tr("المدفوع نقداً الآن من الصندوق (₪)", "Paid Cash Now (₪)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        textDirection = TextDirection.Ltr
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchase_bill_paid_input")
                )

                if (totalVal > 0.0 && isPaidValid) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = lang.tr(
                                "ذمة المورد: ${StitchBidiUtils.formatIls(payableAdded)} • مدفوع: ${StitchBidiUtils.formatIls(paidVal)}",
                                "Payable: ${StitchBidiUtils.formatIls(payableAdded)} • Paid: ${StitchBidiUtils.formatIls(paidVal)}"
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (payableAdded > 0.005) TraderTheme.financial.debit else TraderTheme.financial.income,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isSubmitting && isFormValid) {
                        isSubmitting = true
                        inlineError = null

                        if (billMode == PurchaseBillMode.STRUCTURED) {
                            if (structuredLines.isEmpty()) {
                                isSubmitting = false
                                inlineError = lang.tr("يُرجى إضافة صنف واحد على الأقل", "Please add at least one line item")
                                return@Button
                            }
                            if (onConfirmStructuredBill != null) {
                                val res = onConfirmStructuredBill(selectedSupplierId, structuredLines.toList(), paidVal, effectiveDesc)
                                isSubmitting = false
                                if (res.isSuccess) {
                                    onDismiss()
                                } else {
                                    inlineError = res.exceptionOrNull()?.message ?: lang.tr("فشل قيد الفاتورة", "Failed to record bill")
                                }
                            } else {
                                isSubmitting = false
                                inlineError = "Structured bill handler not provided"
                            }
                        } else {
                            if (onConfirmBill != null) {
                                val success = onConfirmBill(
                                    selectedSupplierId,
                                    effectiveDesc,
                                    totalVal,
                                    paidVal,
                                    null,
                                    0
                                )
                                isSubmitting = false
                                if (success) {
                                    onDismiss()
                                } else {
                                    inlineError = state.supplierActionMessage(lang).ifBlank {
                                        lang.tr("فشل قيد الفاتورة، يُرجى المحاولة مرة أخرى", "Failed to record bill, please retry")
                                    }
                                }
                            } else {
                                isSubmitting = false
                                inlineError = "Financial bill handler not provided"
                            }
                        }
                    }
                },
                enabled = !isSubmitting && isFormValid,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_purchase_bill_button")
            ) {
                Text(
                    text = lang.tr("قيد الفاتورة", "Record Bill"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (!isSubmitting) onDismiss() },
                enabled = !isSubmitting,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}

@Composable
fun SupplierEditorDialog(
    initialSupplier: Supplier?,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onSave: (name: String, company: String, phone: String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialSupplier?.nameAr ?: "") }
    var company by rememberSaveable { mutableStateOf(initialSupplier?.companyAr ?: "") }
    var phone by rememberSaveable { mutableStateOf(initialSupplier?.phone ?: "") }
    val isValid = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = if (initialSupplier == null) lang.tr("إضافة مورد جديد", "Add Supplier") else lang.tr("تعديل بيانات المورد", "Edit Supplier"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(lang.tr("اسم المورد *", "Supplier Name *")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_name_input")
                )
                OutlinedTextField(
                    value = company,
                    onValueChange = { company = it },
                    label = { Text(lang.tr("اسم الشركة / التجارة", "Company / Business Name")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_company_input")
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = WesternFormatters.sanitizeWesternDigits(it) },
                    label = { Text(lang.tr("رقم الهاتف", "Phone Number")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_phone_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (isValid) onSave(name.trim(), company.trim(), phone.trim()) },
                enabled = isValid,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("save_supplier_button")
            ) {
                Text(lang.tr("حفظ المورد", "Save Supplier"))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}

@Composable
fun PurchaseReturnPreviewDialog(
    supplier: Supplier,
    lang: AppLanguage,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = lang.tr("سند مردودات مشتريات", "Purchase Return Note"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = lang.tr(
                        "مردودات المشتريات تخصم من ذمة المورد (${supplier.name(lang)}) وتعيد البضاعة من المخزون إلى المورد.",
                        "Purchase returns credit supplier balance (${supplier.name(lang)}) and remove inventory."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = lang.tr(
                            "ميزة تجريبية مقيدة — سيتم توفيرها رسمياً في التحديث القادم لربط حركة المردود مع الفاتورة الأصلية ورقم التشغيلة بدقة.",
                            "Feature preview — will be enabled in next release to link return notes to original bills and batches."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إغلاق", "Close"))
            }
        }
    )
}

@Composable
fun PurchaseBillRow(
    bill: PurchaseBill,
    lang: AppLanguage,
    canReceiveStock: Boolean = false,
    onOpenReceiveGoodsDialog: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("purchase_bill_${bill.billNumber}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = StitchBidiUtils.formatBillCode(bill.billNumber),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    val hasExpectedSku = (!bill.expectedProductId.isNullOrBlank() && bill.expectedQuantity > 0) ||
                        bill.lines.any { it.quantity > 0 }
                    val totalExpected = if (bill.lines.isNotEmpty()) bill.lines.sumOf { it.quantity } else bill.expectedQuantity
                    val totalReceived = if (bill.lines.isNotEmpty()) bill.lines.sumOf { it.receivedQuantity } else bill.receivedQuantity

                    StitchBadge(
                        text = when {
                            bill.isReceived -> lang.tr("تم استلام البضاعة (GRN)", "Goods Received (GRN)")
                            bill.isPartiallyReceived -> lang.tr(
                                "استلام جزئي (${WesternFormatters.formatInt(totalReceived)}/${WesternFormatters.formatInt(totalExpected)})",
                                "Partial GRN (${WesternFormatters.formatInt(totalReceived)}/${WesternFormatters.formatInt(totalExpected)})"
                            )
                            hasExpectedSku -> lang.tr(
                                "بانتظار استلام (${WesternFormatters.formatInt(totalExpected - totalReceived)} وحدة)",
                                "Pending GRN (${WesternFormatters.formatInt(totalExpected - totalReceived)} units)"
                            )
                            else -> lang.tr("قيد مالي فقط", "Financial only")
                        },
                        tone = when {
                            bill.isReceived -> StitchBadgeTone.SUCCESS
                            bill.isPartiallyReceived -> StitchBadgeTone.WARNING
                            hasExpectedSku -> StitchBadgeTone.WARNING
                            else -> StitchBadgeTone.NEUTRAL
                        },
                        shape = RoundedCornerShape(6.dp)
                    )
                }

                Text(
                    text = bill.date,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = bill.itemsSummary,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )

            if (bill.lines.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    bill.lines.forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "• ${line.name(lang)} (×${line.quantity})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${WesternFormatters.formatIls(line.unitCostIls)}/${lang.tr("وحدة", "unit")}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${lang.tr("القيمة:", "Total:")} ${StitchBidiUtils.formatIls(bill.totalIls)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${lang.tr("دُفع نقداً:", "Paid Cash:")} ${StitchBidiUtils.formatIls(bill.paidIls)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bill.paidIls > 0.005) TraderTheme.financial.income else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${lang.tr("ذمة للمورد:", "Payable:")} ${StitchBidiUtils.formatIls(bill.remainingPayableIls)}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (bill.remainingPayableIls > 0.005) TraderTheme.financial.debit else TraderTheme.financial.income
                )
            }

            val canConfirmExpectedSku = !bill.isReceived &&
                ((!bill.expectedProductId.isNullOrBlank() && bill.expectedQuantity > 0) || bill.lines.any { it.quantity > it.receivedQuantity }) &&
                canReceiveStock &&
                onOpenReceiveGoodsDialog != null

            if (canConfirmExpectedSku) {
                OutlinedButton(
                    onClick = onOpenReceiveGoodsDialog!!,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("open_grn_dialog_button_${bill.billNumber}")
                ) {
                    Text(
                        text = lang.tr(
                            "استلام بضاعة للمستودع (GRN)",
                            "Receive Goods to Stock (GRN)"
                        ),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun SupplierPaymentRow(
    payment: SupplierPayment,
    lang: AppLanguage
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("supplier_payment_${payment.id}")
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
                            Icons.Default.Payments,
                            contentDescription = null,
                            tint = TraderTheme.financial.income,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = payment.id,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = payment.note.ifBlank { lang.tr("سداد نقدي للمورد", "Cash Payment to Supplier") },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = WesternFormatters.formatIls(payment.amountIls),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = TraderTheme.financial.income
                )
                Text(
                    text = payment.date,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
