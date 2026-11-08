package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.viewmodel.TraderUiState

/**
 * Compact People Hub Tab Switcher (ST-32 & ST-33 Canonical Direction).
 *
 * Provides a tactile two-segment switcher between Customers (الزبائن) and Suppliers (الموردون),
 * adhering to Stitch Project 15641450350510420845 and DESIGN_LOCK.md:
 * - 48dp minimum interactive touch target height
 * - 12dp outer container radius with subtle 1dp border (#E4E9F1)
 * - 10dp inner active segment radius with primary container styling
 * - Role-enforced supplier privacy (Cashier role sees Customers directly without tabs)
 */
@Composable
fun PeopleHubScreen(
    state: TraderUiState,
    customers: @Composable () -> Unit,
    suppliers: @Composable () -> Unit
) {
    var supplierSegment by rememberSaveable { mutableStateOf(false) }
    val canManageSuppliers = state.currentSession.canManageSuppliers()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (canManageSuppliers) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Customers Segment
                    val isCustomersSelected = !supplierSegment
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isCustomersSelected) MaterialTheme.colorScheme.primary
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .clickable(
                                role = Role.Tab,
                                onClick = { supplierSegment = false }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.language.tr("الزبائن", "Customers"),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = if (isCustomersSelected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (isCustomersSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Suppliers Segment
                    val isSuppliersSelected = supplierSegment
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSuppliersSelected) MaterialTheme.colorScheme.primary
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .clickable(
                                role = Role.Tab,
                                onClick = { supplierSegment = true }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.language.tr("الموردون", "Suppliers"),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = if (isSuppliersSelected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (isSuppliersSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (canManageSuppliers && supplierSegment) {
                suppliers()
            } else {
                customers()
            }
        }
    }
}
