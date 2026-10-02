package com.financeapp.mobile.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class BudgetCarouselTest {
    @get:Rule val compose = createComposeRule()
    @Test fun horizontalListReachesLastCategory() {
        val rows=(1..8).map { CategoryBudgetStatus(it,"Categoria $it",100.0,20.0) }
        compose.setContent { MaterialTheme { CategoryBudgetCarousel(rows,false) } }
        compose.onNodeWithTag("category-budget-carousel").performScrollToIndex(7)
        compose.onNodeWithText("Categoria 8").assertIsDisplayed()
        compose.onAllNodesWithText("Pode gastar:",substring=true).onFirst().assertExists()
    }
    @Test fun annualShowsAnnualBudgetAndRemaining() {
        compose.setContent { MaterialTheme {
            CategoryBudgetCard(CategoryBudgetStatus(1,"Café",1200.0,80.0),annual=true)
        } }
        compose.onNodeWithText("Gasto no ano:",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Limite anual:",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Pode gastar:",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Limite mensal × 12").assertIsDisplayed()
        compose.onNodeWithText("Resumo do mês").assertDoesNotExist()
    }
}
