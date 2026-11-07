package com.example.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.navigation.*
import com.example.viewmodel.TraderUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionSheet(state: TraderUiState, onDismiss: () -> Unit, onAction: (MerchantAction) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            MerchantAction.entries.forEachIndexed { index, action ->
                if (index == 4) {
                    HorizontalDivider()
                    Text(state.language.tr("المزيد", "More"), modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
                }
                if (action.allowed(state.currentSession)) TextButton(
                    onClick = { onAction(action) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("quick_${action.name.lowercase()}")
                ) {
                    Icon(when(action) {
                        MerchantAction.SALE -> Icons.Default.ShoppingCart
                        MerchantAction.PURCHASE -> Icons.Default.AddShoppingCart
                        MerchantAction.COLLECT, MerchantAction.PAY -> Icons.Default.Payments
                        MerchantAction.PRODUCT -> Icons.Default.Inventory2
                        MerchantAction.CUSTOMER, MerchantAction.SUPPLIER -> Icons.Default.People
                        MerchantAction.EXPENSE -> Icons.Default.AccountBalanceWallet
                    }, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(state.language.tr(when(action) {
                        MerchantAction.SALE -> "بيع جديد"
                        MerchantAction.PURCHASE -> "شراء جديد"
                        MerchantAction.COLLECT -> "قبض من زبون"
                        MerchantAction.PAY -> "دفع لمورد"
                        MerchantAction.PRODUCT -> "صنف جديد"
                        MerchantAction.CUSTOMER -> "زبون جديد"
                        MerchantAction.SUPPLIER -> "مورد جديد"
                        MerchantAction.EXPENSE -> "مصروف جديد"
                    }, when(action) {
                        MerchantAction.SALE -> "New sale"
                        MerchantAction.PURCHASE -> "New purchase"
                        MerchantAction.COLLECT -> "Receive customer payment"
                        MerchantAction.PAY -> "Pay supplier"
                        MerchantAction.PRODUCT -> "New product"
                        MerchantAction.CUSTOMER -> "New customer"
                        MerchantAction.SUPPLIER -> "New supplier"
                        MerchantAction.EXPENSE -> "New expense"
                    }))
                }
            }
        }
    }
}
