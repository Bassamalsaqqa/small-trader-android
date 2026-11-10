package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.viewmodel.TraderUiState
import kotlinx.coroutines.launch

/**
 * Compact People Hub Tab Switcher with Native Thumb Swipe (ST-32 & ST-33 Canonical Direction).
 *
 * Provides a tactile two-segment switcher between Customers (الزبائن) and Suppliers (الموردون),
 * synchronized with Compose Foundation HorizontalPager:
 * - 48dp minimum interactive touch target height
 * - 12dp outer container radius with subtle 1dp border (outlineVariant)
 * - 10dp inner active segment radius with primary container styling
 * - Full bidirectional synchronization between tap gestures and thumb swipe gestures
 * - Native RTL layout direction mirroring
 * - Proper accessibility semantics with Role.Tab and selected state
 * - Strict role-enforced privacy: Cashier role never composes Suppliers
 * - Role downgrade immediately resets pager to Customers (page 0)
 * - Retains child screen and query state across tab switches
 */
@Composable
fun PeopleHubScreen(
    state: TraderUiState,
    customers: @Composable () -> Unit,
    suppliers: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val canManageSuppliers = state.currentSession.canManageSuppliers()
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { 2 }
    )
    val coroutineScope = rememberCoroutineScope()

    // Downgrade guard: If role drops permission while viewing suppliers,
    // immediately reset back to Customers (page 0)
    LaunchedEffect(canManageSuppliers) {
        if (!canManageSuppliers && pagerState.currentPage != 0) {
            pagerState.scrollToPage(0)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (canManageSuppliers) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("people_hub_segmented_control"),
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
                    // Customers Segment (Page 0)
                    val isCustomersSelected = pagerState.currentPage == 0
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isCustomersSelected) MaterialTheme.colorScheme.primary
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .selectable(
                                selected = isCustomersSelected,
                                role = Role.Tab,
                                onClick = {
                                    if (!isCustomersSelected) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(0)
                                        }
                                    }
                                }
                            )
                            .testTag("people_hub_tab_customers"),
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

                    // Suppliers Segment (Page 1)
                    val isSuppliersSelected = pagerState.currentPage == 1
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSuppliersSelected) MaterialTheme.colorScheme.primary
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .selectable(
                                selected = isSuppliersSelected,
                                role = Role.Tab,
                                onClick = {
                                    if (!isSuppliersSelected) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(1)
                                        }
                                    }
                                }
                            )
                            .testTag("people_hub_tab_suppliers"),
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

        if (canManageSuppliers) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("people_hub_pager"),
                beyondViewportPageCount = 1,
                key = { pageIndex -> pageIndex }
            ) { page ->
                when (page) {
                    0 -> customers()
                    1 -> suppliers()
                    else -> customers()
                }
            }
        } else {
            // Strict cashier security boundary: suppliers() is NEVER composed
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("people_hub_cashier_content")
            ) {
                customers()
            }
        }
    }
}
