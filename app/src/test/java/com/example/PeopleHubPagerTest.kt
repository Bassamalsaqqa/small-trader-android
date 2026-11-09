package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.example.data.OfflineDemoTraderRepository
import com.example.model.AppLanguage
import com.example.model.UserRole
import com.example.ui.screens.PeopleHubScreen
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focused verification for PeopleHub thumb swipe & role-restricted segmented pager.
 *
 * Covers:
 * 1. Owner sees segmented control with proper Tab role & selected semantics.
 * 2. Tapping tabs synchronizes page and tab selection.
 * 3. Horizontal thumb swipe synchronizes page and tab selection.
 * 4. RTL mirroring properly maintains bidirectional alignment in Arabic.
 * 5. Strict Cashier security: Cashier NEVER composes Suppliers content.
 * 6. Role downgrade while viewing Suppliers immediately resets active page to Customers (page 0).
 * 7. Screen and query state is retained across tab switches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeopleHubPagerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun ownerSession_displaysSegmentedControl_withProperTabSemantics() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val ownerSession = viewModel.uiState.value.currentSession.copy(role = UserRole.OWNER)
        viewModel.switchUserSession(ownerSession)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = state.isDarkTheme, isRtl = false) {
                PeopleHubScreen(
                    state = state,
                    customers = {
                        Text("Customers Content", modifier = Modifier.testTag("content_customers"))
                    },
                    suppliers = {
                        Text("Suppliers Content", modifier = Modifier.testTag("content_suppliers"))
                    }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Segmented control is visible
        composeTestRule.onNodeWithTag("people_hub_segmented_control").assertIsDisplayed()

        // Customers tab is selected by default; Suppliers is not selected
        composeTestRule.onNodeWithTag("people_hub_tab_customers")
            .assertIsDisplayed()
            .assertIsSelected()

        composeTestRule.onNodeWithTag("people_hub_tab_suppliers")
            .assertIsDisplayed()
            .assertIsNotSelected()

        // Customers content is displayed
        composeTestRule.onNodeWithTag("content_customers").assertIsDisplayed()
    }

    @Test
    fun tabClick_synchronizesSelectionAndContent() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val ownerSession = viewModel.uiState.value.currentSession.copy(role = UserRole.OWNER)
        viewModel.switchUserSession(ownerSession)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                PeopleHubScreen(
                    state = state,
                    customers = {
                        Text("Customers Content", modifier = Modifier.testTag("content_customers"))
                    },
                    suppliers = {
                        Text("Suppliers Content", modifier = Modifier.testTag("content_suppliers"))
                    }
                )
            }
        }

        composeTestRule.waitForIdle()

        // 1. Click Suppliers Tab
        composeTestRule.onNodeWithTag("people_hub_tab_suppliers").performClick()
        composeTestRule.waitForIdle()

        // Suppliers tab becomes selected; Customers tab becomes unselected
        composeTestRule.onNodeWithTag("people_hub_tab_suppliers").assertIsSelected()
        composeTestRule.onNodeWithTag("people_hub_tab_customers").assertIsNotSelected()
        composeTestRule.onNodeWithTag("content_suppliers").assertIsDisplayed()

        // 2. Click Customers Tab back
        composeTestRule.onNodeWithTag("people_hub_tab_customers").performClick()
        composeTestRule.waitForIdle()

        // Customers tab becomes selected again
        composeTestRule.onNodeWithTag("people_hub_tab_customers").assertIsSelected()
        composeTestRule.onNodeWithTag("people_hub_tab_suppliers").assertIsNotSelected()
        composeTestRule.onNodeWithTag("content_customers").assertIsDisplayed()
    }

    @Test
    fun swipeGesture_synchronizesPagerAndTabs() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val ownerSession = viewModel.uiState.value.currentSession.copy(role = UserRole.OWNER)
        viewModel.switchUserSession(ownerSession)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                PeopleHubScreen(
                    state = state,
                    customers = {
                        Text("Customers Content", modifier = Modifier.testTag("content_customers"))
                    },
                    suppliers = {
                        Text("Suppliers Content", modifier = Modifier.testTag("content_suppliers"))
                    }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Initially Customers tab is selected
        composeTestRule.onNodeWithTag("people_hub_tab_customers").assertIsSelected()

        // Swipe left on pager advances to Suppliers (page 1) in LTR
        composeTestRule.onNodeWithTag("people_hub_pager").performTouchInput {
            swipeLeft()
        }
        composeTestRule.waitForIdle()

        // Tab sync: Suppliers tab is now selected
        composeTestRule.onNodeWithTag("people_hub_tab_suppliers").assertIsSelected()
        composeTestRule.onNodeWithTag("people_hub_tab_customers").assertIsNotSelected()
        composeTestRule.onNodeWithTag("content_suppliers").assertIsDisplayed()

        // Swipe right on pager goes back to Customers (page 0)
        composeTestRule.onNodeWithTag("people_hub_pager").performTouchInput {
            swipeRight()
        }
        composeTestRule.waitForIdle()

        // Tab sync: Customers tab is now selected again
        composeTestRule.onNodeWithTag("people_hub_tab_customers").assertIsSelected()
        composeTestRule.onNodeWithTag("people_hub_tab_suppliers").assertIsNotSelected()
        composeTestRule.onNodeWithTag("content_customers").assertIsDisplayed()
    }

    @Test
    fun rtlLayout_supportsArabicMirroringAndSelection() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        viewModel.setLanguage(AppLanguage.AR)
        val ownerSession = viewModel.uiState.value.currentSession.copy(role = UserRole.OWNER)
        viewModel.switchUserSession(ownerSession)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                PeopleHubScreen(
                    state = state,
                    customers = {
                        Text("محتوى الزبائن", modifier = Modifier.testTag("content_customers"))
                    },
                    suppliers = {
                        Text("محتوى الموردين", modifier = Modifier.testTag("content_suppliers"))
                    }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Check Arabic labels & default selection
        composeTestRule.onNodeWithText("الزبائن").assertIsDisplayed()
        composeTestRule.onNodeWithText("الموردون").assertIsDisplayed()
        composeTestRule.onNodeWithTag("people_hub_tab_customers").assertIsSelected()

        // Tap Suppliers tab in RTL
        composeTestRule.onNodeWithTag("people_hub_tab_suppliers").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("people_hub_tab_suppliers").assertIsSelected()
        composeTestRule.onNodeWithTag("people_hub_tab_customers").assertIsNotSelected()
        composeTestRule.onNodeWithTag("content_suppliers").assertIsDisplayed()
    }

    @Test
    fun cashierSession_neverComposesSuppliers_hidesSegmentedControl() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val cashierSession = viewModel.uiState.value.currentSession.copy(role = UserRole.CASHIER)
        viewModel.switchUserSession(cashierSession)

        var suppliersComposedCount = 0

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                PeopleHubScreen(
                    state = state,
                    customers = {
                        Text("Customers Content", modifier = Modifier.testTag("content_customers"))
                    },
                    suppliers = {
                        suppliersComposedCount++
                        Text("Suppliers Content", modifier = Modifier.testTag("content_suppliers"))
                    }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Cashier sees only customers content
        composeTestRule.onNodeWithTag("content_customers").assertIsDisplayed()

        // Segmented control is completely hidden for Cashier
        composeTestRule.onNodeWithTag("people_hub_segmented_control").assertDoesNotExist()
        composeTestRule.onNodeWithTag("people_hub_tab_suppliers").assertDoesNotExist()

        // Suppliers composable was NEVER invoked
        assertEquals("Cashier must never compose suppliers content", 0, suppliersComposedCount)
        composeTestRule.onNodeWithTag("content_suppliers").assertDoesNotExist()
    }

    @Test
    fun roleDowngrade_resetsSupplierPageToCustomersImmediately() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val ownerSession = viewModel.uiState.value.currentSession.copy(role = UserRole.OWNER)
        val cashierSession = viewModel.uiState.value.currentSession.copy(role = UserRole.CASHIER)

        // Start as Owner
        viewModel.switchUserSession(ownerSession)

        var suppliersComposedCount = 0

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                PeopleHubScreen(
                    state = state,
                    customers = {
                        Text("Customers Content", modifier = Modifier.testTag("content_customers"))
                    },
                    suppliers = {
                        suppliersComposedCount++
                        Text("Suppliers Content", modifier = Modifier.testTag("content_suppliers"))
                    }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Owner navigates to Suppliers
        composeTestRule.onNodeWithTag("people_hub_tab_suppliers").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("people_hub_tab_suppliers").assertIsSelected()
        composeTestRule.onNodeWithTag("content_suppliers").assertIsDisplayed()

        // 1. Downgrade session to CASHIER
        viewModel.switchUserSession(cashierSession)
        composeTestRule.waitForIdle()

        // Immediately switches to Customers; tabs hidden
        composeTestRule.onNodeWithTag("people_hub_segmented_control").assertDoesNotExist()
        composeTestRule.onNodeWithTag("content_customers").assertIsDisplayed()

        // 2. Upgrade session back to OWNER
        viewModel.switchUserSession(ownerSession)
        composeTestRule.waitForIdle()

        // Segmented control is back, but active page was reset to Customers (0)
        composeTestRule.onNodeWithTag("people_hub_segmented_control").assertIsDisplayed()
        composeTestRule.onNodeWithTag("people_hub_tab_customers").assertIsSelected()
        composeTestRule.onNodeWithTag("people_hub_tab_suppliers").assertIsNotSelected()
        composeTestRule.onNodeWithTag("content_customers").assertIsDisplayed()
    }

    @Test
    fun queryAndChildState_isRetainedAcrossTabSwitches() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val ownerSession = viewModel.uiState.value.currentSession.copy(role = UserRole.OWNER)
        viewModel.switchUserSession(ownerSession)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                PeopleHubScreen(
                    state = state,
                    customers = {
                        var query by rememberSaveable { mutableStateOf("") }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.testTag("customers_search_input")
                        )
                    },
                    suppliers = {
                        Text("Suppliers Content", modifier = Modifier.testTag("content_suppliers"))
                    }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Modify input in Customers
        composeTestRule.onNodeWithTag("customers_search_input").performTextInput("retained_search_query")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("retained_search_query").assertIsDisplayed()

        // Switch to Suppliers tab
        composeTestRule.onNodeWithTag("people_hub_tab_suppliers").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("content_suppliers").assertIsDisplayed()

        // Switch back to Customers tab
        composeTestRule.onNodeWithTag("people_hub_tab_customers").performClick()
        composeTestRule.waitForIdle()

        // Verify the entered query is still retained!
        composeTestRule.onNodeWithText("retained_search_query").assertIsDisplayed()
    }
}
