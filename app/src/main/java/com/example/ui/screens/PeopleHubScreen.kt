package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.viewmodel.TraderUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleHubScreen(state: TraderUiState, customers: @Composable () -> Unit, suppliers: @Composable () -> Unit) {
    var supplierSegment by rememberSaveable { mutableStateOf(false) }
    val allowed = state.currentSession.canManageSuppliers()
    Column {
        if (allowed) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SegmentedButton(selected = !supplierSegment, onClick = { supplierSegment = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text(state.language.tr("الزبائن", "Customers"))
            }
            SegmentedButton(selected = supplierSegment, onClick = { supplierSegment = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text(state.language.tr("الموردون", "Suppliers"))
            }
        }
        Box(Modifier.weight(1f)) { if (allowed && supplierSegment) suppliers() else customers() }
    }
}
