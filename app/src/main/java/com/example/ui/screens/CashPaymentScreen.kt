package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.Customer
import com.example.model.WesternFormatters
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState
import kotlin.math.abs

@Composable
fun CashPaymentScreen(
    state: TraderUiState,
    onSetExactAmount: () -> Unit,
    onSelectDenomination: (Int) -> Unit,
    onAddCashIncrement: (Int) -> Unit = {},
    onKeypadPress: (String) -> Unit = {},
    onUpdateCashText: (String) -> Unit = {},
    onAssignCustomer: (Customer?) -> Unit = {},
    onDismissCheckoutError: () -> Unit = {},
    onEditInvoice: () -> Unit = {},
    onCompleteSale: () -> Unit,
    onCompleteCreditSale: () -> Unit = {}
) {
    val lang = state.language
    val financial = TraderTheme.financial
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    var isCustomerPickerOpen by rememberSaveable { mutableStateOf(false) }

    val selectedCustomer = state.selectedCustomer
    val sufficient = state.isCashSufficient
    val isExact = state.isExactCashTendered
    val errorMsg = state.checkoutErrorMessage

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("cash_payment_screen")
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Recoverable Checkout Error Banner (shown only when a submit attempt failed)
            if (errorMsg != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = financial.debitContainer,
                    border = BorderStroke(1.dp, financial.debit.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("payment_error_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = financial.debit,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = financial.debit,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissCheckoutError,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = lang.tr("إغلاق التنبيه", "Dismiss error"),
                                tint = financial.debit,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 1. Primary Cash Settlement Board: Amount Due • Cash Received • Change Due
            // =========================================================================
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Top Row: Amount Due & Cash Received at a glance
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("المبلغ المطلوب للدفع", "Amount Due"),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = WesternFormatters.formatIls(state.basketNetTotalIls),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("payment_total_due_text")
                            )
                            Text(
                                text = buildString {
                                    append(
                                        lang.tr(
                                            "${WesternFormatters.formatInt(state.basketItemCount)} وحدات",
                                            "${WesternFormatters.formatInt(state.basketItemCount)} units"
                                        )
                                    )
                                    if (state.calculatedDiscountIls > 0.005) {
                                        append(" • ")
                                        append(
                                            lang.tr(
                                                "خصم ${WesternFormatters.formatIls(state.calculatedDiscountIls)}",
                                                "${WesternFormatters.formatIls(state.calculatedDiscountIls)} off"
                                            )
                                        )
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = onEditInvoice,
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("payment_edit_invoice_button")
                            ) {
                                Text(
                                    text = lang.tr("تعديل الأصناف أو الخصم", "Edit Items or Discount"),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        VerticalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = lang.tr("النقد المستلم", "Cash Received"),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = WesternFormatters.formatIls(state.cashTenderedIls),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (sufficient) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    financial.warning
                                },
                                modifier = Modifier.testTag("payment_cash_received_text")
                            )
                            Text(
                                text = if (isExact) {
                                    lang.tr("مطابق للمطلوب بالضبط", "Exact amount")
                                } else if (sufficient) {
                                    lang.tr("أكبر من المطلوب (يوجد باقي)", "Exceeds due (change below)")
                                } else {
                                    lang.tr("أقل من المطلوب", "Less than amount due")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sufficient) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    financial.warning
                                }
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

                    // Native Tender Entry + Quick Bills
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = state.cashInputText,
                            onValueChange = { raw ->
                                onUpdateCashText(raw)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            label = {
                                Text(
                                    text = lang.tr(
                                        "أدخل النقد المستلم من الزبون (₪)",
                                        "Enter cash received from customer (₪)"
                                    )
                                )
                            },
                            placeholder = {
                                Text(WesternFormatters.formatNumber(state.basketNetTotalIls))
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Payments,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            trailingIcon = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(end = 6.dp)
                                ) {
                                    if (!isExact) {
                                        TextButton(
                                            onClick = {
                                                onSetExactAmount()
                                                focusManager.clearFocus()
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text(
                                                text = lang.tr("بالضبط", "Exact"),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    if (state.cashInputText.isNotEmpty()) {
                                        IconButton(
                                            onClick = {
                                                onKeypadPress("C")
                                                onUpdateCashText("")
                                            },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("keypad_btn_C")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = lang.tr("مسح المبلغ", "Clear amount"),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() }
                            ),
                            textStyle = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.background,
                                unfocusedContainerColor = MaterialTheme.colorScheme.background
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cash_tender_input")
                        )

                        // Quick Shekel Presets Row: Exact + Standard Bills
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = {
                                    onSetExactAmount()
                                    focusManager.clearFocus()
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isExact) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                },
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isExact) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    }
                                ),
                                modifier = Modifier
                                    .weight(1.25f)
                                    .heightIn(min = 48.dp)
                                    .testTag("cash_exact_button")
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                ) {
                                    Text(
                                        text = lang.tr("بالضبط", "Exact"),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isExact) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1
                                    )
                                }
                            }

                            val bills = listOf(10, 20, 50, 100, 200)
                            bills.forEach { bill ->
                                val isSelectedBill = abs(state.cashTenderedIls - bill.toDouble()) < 0.005 && !isExact
                                val coversDue = bill.toDouble() + 0.005 >= state.basketNetTotalIls
                                Surface(
                                    onClick = {
                                        onSelectDenomination(bill)
                                        focusManager.clearFocus()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = when {
                                        isSelectedBill -> MaterialTheme.colorScheme.primaryContainer
                                        coversDue -> MaterialTheme.colorScheme.surface
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    },
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = when {
                                            isSelectedBill -> MaterialTheme.colorScheme.primary
                                            coversDue -> MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        }
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("cash_bill_$bill")
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$bill",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = if (coversDue || isSelectedBill) FontWeight.Bold else FontWeight.Medium
                                            ),
                                            color = when {
                                                isSelectedBill -> MaterialTheme.colorScheme.onPrimaryContainer
                                                coversDue -> MaterialTheme.colorScheme.onSurface
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Change to Return / Shortfall Callout Banner
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (sufficient) financial.incomeContainer else financial.warningContainer,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (sufficient) {
                                financial.income.copy(alpha = 0.35f)
                            } else {
                                financial.warning.copy(alpha = 0.45f)
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (sufficient) {
                                            lang.tr("الباقي لإرجاعه للزبون", "Change to Return")
                                        } else {
                                            lang.tr("النقص عن المبلغ المطلوب", "Shortfall (Still Due)")
                                        },
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (sufficient) financial.income else financial.warning
                                    )
                                    Text(
                                        text = when {
                                            isExact -> lang.tr(
                                                "المبلغ المستلم مطابق تماماً — لا يوجد باقي",
                                                "Exact cash received — no change to return"
                                            )
                                            sufficient -> lang.tr(
                                                "سلّم الزبون هذا الباقي نقداً مع الإيصال",
                                                "Hand this change back to the customer"
                                            )
                                            else -> lang.tr(
                                                "لا يُقبل الدفع النقدي الجزئي؛ استلم كامل المبلغ",
                                                "Partial cash is not accepted; collect full amount"
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (sufficient) {
                                            financial.income.copy(alpha = 0.9f)
                                        } else {
                                            financial.warning.copy(alpha = 0.95f)
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Text(
                                    text = if (sufficient) {
                                        WesternFormatters.formatIls(state.changeDueIls)
                                    } else {
                                        WesternFormatters.formatIls(state.remainingUnpaidIls)
                                    },
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (sufficient) financial.income else financial.warning,
                                    modifier = Modifier.testTag("payment_change_due_text")
                                )
                            }

                            // One-tap recovery when cash tendered is insufficient
                            if (!sufficient && state.basket.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            onSetExactAmount()
                                            focusManager.clearFocus()
                                        },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("recover_exact_cash_button")
                                    ) {
                                        Text(
                                            text = lang.tr(
                                                "تعيين المبلغ الكامل (${WesternFormatters.formatIls(state.basketNetTotalIls)})",
                                                "Set Exact Due (${WesternFormatters.formatIls(state.basketNetTotalIls)})"
                                            ),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 2. Distinct & Truthful Credit-to-Customer Section
            // =========================================================================
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (state.wouldCreditExceedCustomerLimit) {
                        financial.debit.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
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
                                imageVector = if (selectedCustomer != null) {
                                    Icons.Default.Person
                                } else {
                                    Icons.Default.PersonAddAlt1
                                },
                                contentDescription = null,
                                tint = if (selectedCustomer != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = lang.tr(
                                        "البيع الآجل على حساب زبون",
                                        "Credit to Customer Account"
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (selectedCustomer != null) {
                                        selectedCustomer.name(lang)
                                    } else {
                                        lang.tr("زبون نقدي مباشر (غير مربوط بحساب)", "Walk-in customer (no account linked)")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (selectedCustomer != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { isCustomerPickerOpen = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("payment_select_customer_button")
                        ) {
                            Icon(
                                imageVector = if (selectedCustomer != null) {
                                    Icons.Default.SwapHoriz
                                } else {
                                    Icons.Default.PersonAddAlt1
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (selectedCustomer != null) {
                                    lang.tr("تغيير الزبون", "Change")
                                } else {
                                    lang.tr("اختيار زبون", "Select Customer")
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (selectedCustomer == null) {
                        // Walk-in explanation: credit is distinct and requires an assigned customer
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = lang.tr(
                                    "لتسجيل الفاتورة بالذمة (آجل)، اختر حساب زبون مسجل أولاً. البيع النقدي المباشر يُحصّل بالكامل في الصندوق.",
                                    "To post on credit, select a registered customer first. Walk-in sales must be paid in full in cash."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    } else {
                        val projected = state.projectedCustomerBalanceIfCreditIls ?: selectedCustomer.balanceIls
                        val overLimit = state.wouldCreditExceedCustomerLimit

                        // Customer Debt Consequence Strip
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (overLimit) {
                                financial.debitContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Min),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = lang.tr("الدين الحالي", "Current Debt"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = WesternFormatters.formatIls(selectedCustomer.balanceIls),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    VerticalDivider(
                                        modifier = Modifier.fillMaxHeight(),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                    )

                                    Column(modifier = Modifier.weight(1.2f)) {
                                        Text(
                                            text = lang.tr("الرصيد بعد الآجل", "Balance After Credit"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = WesternFormatters.formatIls(projected),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (overLimit) financial.debit else financial.warning
                                        )
                                    }

                                    VerticalDivider(
                                        modifier = Modifier.fillMaxHeight(),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = lang.tr("سقف الدين", "Credit Limit"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = WesternFormatters.formatIls(selectedCustomer.creditLimitIls),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (overLimit) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("payment_credit_limit_warning")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.WarningAmber,
                                            contentDescription = null,
                                            tint = financial.debit,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = lang.tr(
                                                "يتجاوز سقف الدين المسموح بمقدار ${WesternFormatters.formatIls(state.creditCeilingExcessIls)}. استلم الفاتورة نقداً بالأسفل، أو غيّر الزبون، أو خفّض الأصناف.",
                                                "Exceeds credit limit by ${WesternFormatters.formatIls(state.creditCeilingExcessIls)}. Collect cash below, change customer, or reduce items."
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = financial.debit,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                } else {
                                    Text(
                                        text = lang.tr(
                                            "عند التسجيل على الحساب: لا يدخل نقد للصندوق (0.00 ₪) ويُضاف ${WesternFormatters.formatIls(state.basketNetTotalIls)} لذمة الزبون.",
                                            "On credit confirmation: 0.00 ₪ drawer cash; full ${WesternFormatters.formatIls(state.basketNetTotalIls)} added to customer debt."
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onCompleteCreditSale,
                            enabled = state.basket.isNotEmpty() && !state.isSubmittingSale && !overLimit,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (overLimit) {
                                    MaterialTheme.colorScheme.outlineVariant
                                } else {
                                    financial.debit.copy(alpha = 0.65f)
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("complete_credit_sale_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                tint = if (overLimit) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    financial.debit
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (overLimit) {
                                    lang.tr(
                                        "البيع الآجل متوقف لتجاوز سقف الدين",
                                        "Credit Blocked: Exceeds Credit Limit"
                                    )
                                } else {
                                    lang.tr(
                                        "تسجيل كامل الفاتورة آجل على الحساب (${WesternFormatters.formatIls(state.basketNetTotalIls)})",
                                        "Post Full Invoice on Customer Credit (${WesternFormatters.formatIls(state.basketNetTotalIls)})"
                                    )
                                },
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = if (overLimit) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        financial.debit
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 3. Authoritative Cash Confirmation Dock (Consequence + Primary Action)
        // =========================================================================
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Consequence line: exactly what will happen on confirmation
                Text(
                    text = when {
                        !sufficient -> lang.tr(
                            "أدخل نقداً مستلماً لا يقل عن ${WesternFormatters.formatIls(state.basketNetTotalIls)} لتأكيد القبض النقدي",
                            "Enter at least ${WesternFormatters.formatIls(state.basketNetTotalIls)} cash received to confirm cash sale"
                        )
                        state.changeDueIls > 0.005 -> lang.tr(
                            "عند التأكيد: صافي الصندوق +${WesternFormatters.formatIls(state.basketNetTotalIls)} • إرجاع باقي ${WesternFormatters.formatIls(state.changeDueIls)} • خصم ${WesternFormatters.formatInt(state.stockUnitsToDeduct)} مخزون",
                            "On confirm: Drawer +${WesternFormatters.formatIls(state.basketNetTotalIls)} • Return ${WesternFormatters.formatIls(state.changeDueIls)} change • Deduct ${WesternFormatters.formatInt(state.stockUnitsToDeduct)} stock"
                        )
                        else -> lang.tr(
                            "عند التأكيد: إيداع ${WesternFormatters.formatIls(state.basketNetTotalIls)} في الصندوق • بدون باقي • خصم ${WesternFormatters.formatInt(state.stockUnitsToDeduct)} مخزون",
                            "On confirm: Drawer +${WesternFormatters.formatIls(state.basketNetTotalIls)} • No change due • Deduct ${WesternFormatters.formatInt(state.stockUnitsToDeduct)} stock"
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (sufficient) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        financial.warning
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Button(
                    onClick = onCompleteSale,
                    enabled = state.basket.isNotEmpty() && sufficient && !state.isSubmittingSale,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("complete_sale_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (sufficient && state.changeDueIls > 0.005) {
                            lang.tr(
                                "تأكيد القبض وإرجاع باقي ${WesternFormatters.formatIls(state.changeDueIls)}",
                                "Confirm Cash & Return ${WesternFormatters.formatIls(state.changeDueIls)} Change"
                            )
                        } else {
                            lang.tr(
                                "إتمام البيع نقداً (${WesternFormatters.formatIls(state.basketNetTotalIls)})",
                                "Complete Cash Sale (${WesternFormatters.formatIls(state.basketNetTotalIls)})"
                            )
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }

    if (isCustomerPickerOpen) {
        CustomerPickerModalSheet(
            customers = state.customers,
            selectedCustomerId = state.selectedCustomerId,
            basketNetTotalIls = state.basketNetTotalIls,
            lang = lang,
            onDismiss = { isCustomerPickerOpen = false },
            onSelectCustomer = { customer ->
                onAssignCustomer(customer)
                isCustomerPickerOpen = false
            }
        )
    }
}
