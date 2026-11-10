package com.example

import com.example.data.OfflineDemoTraderRepository
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression for correcting cash tender with the newly exposed on-screen keypad. */
class PhaseOneWorkflowTest {
    @Test
    fun correctingTender_doesNotPostOrChangeDrawerUntilConfirmation() {
        val repository = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repository)
        val product = viewModel.uiState.value.products.first { it.stockCount > 0 }
        viewModel.addProductToBasket(product, 1)
        val before = viewModel.uiState.value
        viewModel.onCashKeypadPress("C")
        listOf("1", "2", ".", "3", "4", "9").forEach(viewModel::onCashKeypadPress)
        assertEquals("12.34", viewModel.uiState.value.cashInputText)
        viewModel.onCashKeypadPress("BACK")
        viewModel.onCashKeypadPress("5")
        assertEquals("12.35", viewModel.uiState.value.cashInputText)
        viewModel.onCashKeypadPress("C")
        assertEquals("", viewModel.uiState.value.cashInputText)
        val after = viewModel.uiState.value
        assertEquals(before.invoices.size, after.invoices.size)
        assertEquals(before.drawerTotalCashIls, after.drawerTotalCashIls, 0.001)
        assertEquals(before.products, after.products)
        assertEquals(before.basket, after.basket)
    }
}
