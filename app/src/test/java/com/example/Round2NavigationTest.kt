package com.example

import com.example.model.TraderScreen
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.*
import org.junit.Test

/**
 * Round 2 focused state transition tests for Settings and Profile navigation.
 * Validates TC-NAV-01, DEF-NAV-01 fixes, cycle-prevention, entry origin restoration,
 * and preservation of auth/financial state during lateral settings/profile switching.
 */
class Round2NavigationTest {

    @Test
    fun entryOrigin_to_profile_to_settings_backReturnsProfileThenOrigin() {
        val vm = TraderViewModel()
        assertEquals(TraderScreen.HOME, vm.uiState.value.currentScreen)
        assertTrue(vm.uiState.value.screenHistory.isEmpty())

        // 1. Origin (HOME) -> Profile
        vm.navigateTo(TraderScreen.USER_PROFILE)
        assertEquals(TraderScreen.USER_PROFILE, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME), vm.uiState.value.screenHistory)

        // 2. Profile -> Settings
        vm.navigateTo(TraderScreen.BUSINESS_SETTINGS)
        assertEquals(TraderScreen.BUSINESS_SETTINGS, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME, TraderScreen.USER_PROFILE), vm.uiState.value.screenHistory)

        // 3. Back from Settings -> must return to Profile (NOT Home directly)
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.USER_PROFILE, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME), vm.uiState.value.screenHistory)

        // 4. Back from Profile -> must return to Origin (HOME)
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.HOME, vm.uiState.value.currentScreen)
        assertTrue(vm.uiState.value.screenHistory.isEmpty())

        // 5. Back from Root HOME -> returns false (cannot pop further)
        assertFalse(vm.navigateBack())
    }

    @Test
    fun nonRootOrigin_to_profile_to_settings_backReturnsProfileThenOriginThenHome() {
        val vm = TraderViewModel()
        // Start from People directory
        vm.navigateTo(TraderScreen.PEOPLE)
        assertEquals(TraderScreen.PEOPLE, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME), vm.uiState.value.screenHistory)

        // People -> Profile
        vm.navigateTo(TraderScreen.USER_PROFILE)
        assertEquals(TraderScreen.USER_PROFILE, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME, TraderScreen.PEOPLE), vm.uiState.value.screenHistory)

        // Profile -> Settings
        vm.navigateTo(TraderScreen.BUSINESS_SETTINGS)
        assertEquals(TraderScreen.BUSINESS_SETTINGS, vm.uiState.value.currentScreen)
        assertEquals(
            listOf(TraderScreen.HOME, TraderScreen.PEOPLE, TraderScreen.USER_PROFILE),
            vm.uiState.value.screenHistory
        )

        // Back from Settings -> Profile
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.USER_PROFILE, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME, TraderScreen.PEOPLE), vm.uiState.value.screenHistory)

        // Back from Profile -> Origin (People)
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.PEOPLE, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME), vm.uiState.value.screenHistory)

        // Back from People -> Home
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.HOME, vm.uiState.value.currentScreen)
        assertTrue(vm.uiState.value.screenHistory.isEmpty())
    }

    @Test
    fun directSettings_returnsExactOriginOnBack() {
        val vm = TraderViewModel()

        // Case A: Direct from HOME
        vm.navigateTo(TraderScreen.BUSINESS_SETTINGS)
        assertEquals(TraderScreen.BUSINESS_SETTINGS, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME), vm.uiState.value.screenHistory)
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.HOME, vm.uiState.value.currentScreen)
        assertTrue(vm.uiState.value.screenHistory.isEmpty())

        // Case B: Direct from PRODUCTS_CATALOG
        vm.navigateTo(TraderScreen.PRODUCTS_CATALOG)
        assertEquals(TraderScreen.PRODUCTS_CATALOG, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME), vm.uiState.value.screenHistory)

        vm.navigateTo(TraderScreen.BUSINESS_SETTINGS)
        assertEquals(TraderScreen.BUSINESS_SETTINGS, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME, TraderScreen.PRODUCTS_CATALOG), vm.uiState.value.screenHistory)

        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.PRODUCTS_CATALOG, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME), vm.uiState.value.screenHistory)
    }

    @Test
    fun tcNav01_rootToSettings_toProfile_backReturnsSettingsThenOrigin() {
        val vm = TraderViewModel()

        // 1. HOME -> Settings
        vm.navigateTo(TraderScreen.BUSINESS_SETTINGS)
        assertEquals(TraderScreen.BUSINESS_SETTINGS, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME), vm.uiState.value.screenHistory)

        // 2. In Settings, tap My Profile
        vm.navigateTo(TraderScreen.USER_PROFILE)
        assertEquals(TraderScreen.USER_PROFILE, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME, TraderScreen.BUSINESS_SETTINGS), vm.uiState.value.screenHistory)

        // 3. Back from Profile -> returns to BUSINESS_SETTINGS
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.BUSINESS_SETTINGS, vm.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME), vm.uiState.value.screenHistory)

        // 4. Back from Settings -> returns to HOME
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.HOME, vm.uiState.value.currentScreen)
        assertTrue(vm.uiState.value.screenHistory.isEmpty())
    }

    @Test
    fun repeatedProfileSettingsNavigation_avoidsStackCycles() {
        val vm = TraderViewModel()
        vm.navigateTo(TraderScreen.USER_PROFILE)
        assertEquals(listOf(TraderScreen.HOME), vm.uiState.value.screenHistory)

        // Repeatedly toggle between Settings and Profile (5 round trips)
        for (i in 1..5) {
            vm.navigateTo(TraderScreen.BUSINESS_SETTINGS)
            assertEquals(TraderScreen.BUSINESS_SETTINGS, vm.uiState.value.currentScreen)
            assertEquals(
                "History stack must not grow past origin + profile on loop $i",
                listOf(TraderScreen.HOME, TraderScreen.USER_PROFILE),
                vm.uiState.value.screenHistory
            )

            vm.navigateTo(TraderScreen.USER_PROFILE)
            assertEquals(TraderScreen.USER_PROFILE, vm.uiState.value.currentScreen)
            assertEquals(
                "History stack must cleanly unwind to origin on loop $i",
                listOf(TraderScreen.HOME),
                vm.uiState.value.screenHistory
            )
        }

        // After repeated switching ending at Profile, single Back returns directly to HOME
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.HOME, vm.uiState.value.currentScreen)
        assertTrue(vm.uiState.value.screenHistory.isEmpty())
    }

    @Test
    fun repeatedSettingsProfileNavigation_startingAtSettings_avoidsStackCycles() {
        val vm = TraderViewModel()
        vm.navigateTo(TraderScreen.BUSINESS_SETTINGS)
        assertEquals(listOf(TraderScreen.HOME), vm.uiState.value.screenHistory)

        // Repeatedly toggle between Profile and Settings starting from Settings (5 round trips)
        for (i in 1..5) {
            vm.navigateTo(TraderScreen.USER_PROFILE)
            assertEquals(TraderScreen.USER_PROFILE, vm.uiState.value.currentScreen)
            assertEquals(
                "History stack must not grow past origin + settings on loop $i",
                listOf(TraderScreen.HOME, TraderScreen.BUSINESS_SETTINGS),
                vm.uiState.value.screenHistory
            )

            vm.navigateTo(TraderScreen.BUSINESS_SETTINGS)
            assertEquals(TraderScreen.BUSINESS_SETTINGS, vm.uiState.value.currentScreen)
            assertEquals(
                "History stack must cleanly unwind to origin on loop $i",
                listOf(TraderScreen.HOME),
                vm.uiState.value.screenHistory
            )
        }

        // After repeated switching ending at Settings, single Back returns directly to HOME
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.HOME, vm.uiState.value.currentScreen)
        assertTrue(vm.uiState.value.screenHistory.isEmpty())
    }

    @Test
    fun reselectingCurrentScreen_isNoOp() {
        val vm = TraderViewModel()
        vm.navigateTo(TraderScreen.USER_PROFILE)
        val state1 = vm.uiState.value

        vm.navigateTo(TraderScreen.USER_PROFILE)
        val state2 = vm.uiState.value
        assertSame(state1, state2)

        vm.navigateTo(TraderScreen.BUSINESS_SETTINGS)
        val state3 = vm.uiState.value

        vm.navigateTo(TraderScreen.BUSINESS_SETTINGS)
        val state4 = vm.uiState.value
        assertSame(state3, state4)
    }

    @Test
    fun activeBasketAndAuthGuards_preservedAcrossProfileAndSettingsTransitions() {
        val vm = TraderViewModel()
        val product = vm.uiState.value.products.first { it.stockCount > 0 }
        vm.addProductToBasket(product, 2)
        assertEquals(2, vm.uiState.value.basketItemCount)
        val basketTotal = vm.uiState.value.basketNetTotalIls

        // Navigate to Profile then Settings
        vm.navigateTo(TraderScreen.USER_PROFILE)
        vm.navigateTo(TraderScreen.BUSINESS_SETTINGS)

        // Financial state and active basket must remain untouched
        assertEquals(2, vm.uiState.value.basketItemCount)
        assertEquals(basketTotal, vm.uiState.value.basketNetTotalIls, 0.001)

        // Role downgrade guard: switch to Cashier session
        val cashier = vm.uiState.value.availableUsers.first { it.role == UserRole.CASHIER }
        vm.switchUserSession(cashier)
        assertEquals(UserRole.CASHIER, vm.uiState.value.currentSession.role)

        // Cashier cannot access restricted destinations like SUPPLIERS
        vm.navigateTo(TraderScreen.SUPPLIERS)
        assertEquals(
            "Cashier must not navigate to restricted SUPPLIERS",
            TraderScreen.BUSINESS_SETTINGS,
            vm.uiState.value.currentScreen
        )

        // Back unwinds properly
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.USER_PROFILE, vm.uiState.value.currentScreen)
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.HOME, vm.uiState.value.currentScreen)

        // Basket still preserved on Home
        assertEquals(2, vm.uiState.value.basketItemCount)
        assertEquals(basketTotal, vm.uiState.value.basketNetTotalIls, 0.001)
    }
}
