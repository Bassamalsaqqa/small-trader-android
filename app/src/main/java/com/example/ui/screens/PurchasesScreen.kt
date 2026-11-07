package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.model.Supplier
import com.example.model.WesternFormatters
import com.example.viewmodel.TraderUiState

@Composable
fun PurchasesScreen(state: TraderUiState, onNewPurchase: () -> Unit, onSupplier: (Supplier) -> Unit) {
    if (!state.currentSession.canManageSuppliers()) return
    val lang = state.language
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Button(onClick = onNewPurchase, modifier = Modifier.fillMaxWidth()) { Text(lang.tr("شراء جديد", "New purchase")) } }
        if (state.purchaseBills.isEmpty()) item { Text(lang.tr("ما في مشتريات", "No purchases yet")) }
        items(state.purchaseBills, key = { it.id }) { bill ->
            OutlinedCard(onClick = { state.suppliers.firstOrNull { it.id == bill.supplierId }?.let(onSupplier) }) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(WesternFormatters.formatBillCode(bill.billNumber), style = MaterialTheme.typography.titleMedium)
                    Text(bill.itemsSummary)
                    Text(WesternFormatters.formatIls(bill.totalIls))
                    Text(lang.tr("المتبقي: ", "Remaining: ") + WesternFormatters.formatIls(bill.remainingPayableIls))
                    Text(if (bill.isReceived) lang.tr("تم استلام البضاعة", "Goods received")
                        else if (bill.expectedProductId != null && bill.expectedQuantity > 0) lang.tr("بانتظار استلام البضاعة", "Awaiting goods receipt")
                        else lang.tr("فاتورة نصية • استلام المخزون غير متاح", "Text-only bill • stock receiving unavailable"))
                }
            }
        }
    }
}
