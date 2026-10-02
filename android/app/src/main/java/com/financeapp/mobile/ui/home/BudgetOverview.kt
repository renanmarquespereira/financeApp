package com.financeapp.mobile.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.BudgetDto
import com.financeapp.mobile.data.remote.CategoryDto
import java.time.LocalDate
import java.time.YearMonth
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

internal data class CategoryBudgetStatus(val id: Int, val name: String, val limit: Double, val spent: Double) {
    val remaining get() = limit - spent
}
internal data class MonthlyBudgetOverview(val rows: List<CategoryBudgetStatus>, val totalSpent: Double) {
    val planned get() = rows.sumOf { it.limit }
    val budgetedSpent get() = rows.sumOf { it.spent }
    val unbudgetedSpent get() = (totalSpent - budgetedSpent).coerceAtLeast(0.0)
    val remaining get() = planned - budgetedSpent
}

internal fun monthlyBudgetOverview(budgets: List<BudgetDto>, categories: List<CategoryDto>,
    transactions: List<TransactionEntity>, month: YearMonth, annual: Boolean = false): MonthlyBudgetOverview {
    val expenses = transactions.filter { tx ->
        val date = runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull()
        tx.amount < 0 &&
            tx.source != "card_purchase" &&
            !(tx.cardId != null && tx.source != "card_payment") &&
            date != null &&
            if (annual) date.year == month.year else YearMonth.from(date) == month
    }
    val spent = expenses.groupBy { it.categoryId }.mapValues { (_, rows) -> rows.sumOf { abs(it.amount) } }
    val rows = budgets.distinctBy { it.categoryId }.map { budget ->
        CategoryBudgetStatus(budget.categoryId, categories.firstOrNull { it.id == budget.categoryId }?.name ?: "Categoria",
            budget.amount * if (annual) 12 else 1, spent[budget.categoryId] ?: 0.0)
    }.sortedBy { it.name.lowercase() }
    return MonthlyBudgetOverview(rows, expenses.sumOf { abs(it.amount) })
}

@Composable
internal fun BudgetTotalsCard(overview: MonthlyBudgetOverview) {
    val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Resumo do mês", fontWeight = FontWeight.Bold)
            Text("Total planejado: ${currency.format(overview.planned)}")
            Text("Total gasto: ${currency.format(overview.totalSpent)}", fontWeight = FontWeight.SemiBold)
            Text("Nas categorias com limite: ${currency.format(overview.budgetedSpent)}")
            Text("Sem orçamento ou sem categoria: ${currency.format(overview.unbudgetedSpent)}", style = MaterialTheme.typography.bodySmall)
            Text(if (overview.remaining >= 0) "Saldo dos orçamentos: ${currency.format(overview.remaining)}"
                else "Orçamentos excedidos em: ${currency.format(abs(overview.remaining))}",
                color = if (overview.remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("O saldo total não altera o limite de cada categoria.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun CategoryBudgetCard(row: CategoryBudgetStatus, annual: Boolean = false, onClick: (() -> Unit)? = null) {
    val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"))
    Card(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF15243A) else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.name, fontWeight = FontWeight.SemiBold)
            Text(if (annual) "Limite anual: ${currency.format(row.limit)}" else "Limite mensal: ${currency.format(row.limit)}")
            Text(if (annual) "Gasto no ano: ${currency.format(row.spent)}" else "Gasto no mês: ${currency.format(row.spent)}")
            LinearProgressIndicator(progress = { if (row.limit > 0) (row.spent / row.limit).toFloat().coerceIn(0f, 1f) else if (row.spent > 0) 1f else 0f }, modifier = Modifier.fillMaxWidth())
            Text(if (row.remaining >= 0) "Pode gastar: ${currency.format(row.remaining)}"
                else "Excedido em: ${currency.format(abs(row.remaining))}", fontWeight = FontWeight.Bold,
                color = if (row.remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (annual) Text("Limite mensal × 12", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun CategoryBudgetCarousel(rows: List<CategoryBudgetStatus>, annual: Boolean, onCardClick: (CategoryBudgetStatus) -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth * 0.86f).coerceAtMost(320.dp)
        LazyRow(Modifier.fillMaxWidth().testTag("category-budget-carousel"),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rows, key = { it.id }) { row ->
                Box(Modifier.width(cardWidth)) { CategoryBudgetCard(row, annual) { onCardClick(row) } }
            }
        }
    }
}
