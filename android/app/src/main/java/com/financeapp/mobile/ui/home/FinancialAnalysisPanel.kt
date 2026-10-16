package com.financeapp.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.CategoryDto
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

private data class AnalysisMonth(val month: YearMonth, val income: Double, val expense: Double)
private data class AnalysisCategory(val name: String, val value: Double)
private val analysisGreen = Color(0xFF169A6B)
private val analysisRed = Color(0xFFDE5962)
private val analysisBlue = Color(0xFF3478D8)

/** Dashboard de leitura: não cria transações nem interfere na previsão já existente. */
@Composable
internal fun FinancialAnalysisPanel(
    transactions: List<TransactionEntity>,
    categories: List<CategoryDto>,
    forecastMonths: List<Triple<YearMonth, Double, Double>>
) {
    val now = remember { LocalDate.now() }
    val pt = remember { Locale("pt", "BR") }
    val currency = remember { NumberFormat.getCurrencyInstance(pt) }
    var selectedWindow by remember { mutableIntStateOf(6) }
    val months = remember(transactions, selectedWindow, now) {
        val first = YearMonth.from(now).minusMonths((selectedWindow - 1).toLong())
        val groups = transactions.mapNotNull { tx ->
            val date = runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull() ?: return@mapNotNull null
            if (date.isAfter(now)) return@mapNotNull null
            val month = YearMonth.from(date)
            if (month.isBefore(first) || month.isAfter(YearMonth.from(now))) return@mapNotNull null
            // Pagamento de fatura não é outra despesa: compra no cartão já é computada.
            if (tx.source == "card_payment") return@mapNotNull null
            // Transferência entre contas não é receita ou despesa de consumo.
            if (tx.source.contains("transfer", ignoreCase = true)) return@mapNotNull null
            val incoming = tx.transactionType.lowercase(Locale.ROOT) in setOf("income", "credit", "entrada")
            Triple(month, incoming, abs(tx.amount))
        }
        (0 until selectedWindow).map { i ->
            val month = first.plusMonths(i.toLong())
            AnalysisMonth(month,
                groups.filter { it.first == month && it.second }.sumOf { it.third },
                groups.filter { it.first == month && !it.second }.sumOf { it.third })
        }
    }
    val thisMonth = months.lastOrNull()
    val lastMonth = months.dropLast(1).lastOrNull()
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    val expenseCategories = remember(transactions, categories, now) {
        transactions.asSequence().filter { tx ->
            val date = runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull()
            date != null && !date.isAfter(now) && YearMonth.from(date) == YearMonth.from(now) &&
                tx.transactionType.lowercase(Locale.ROOT) !in setOf("income", "credit", "entrada") &&
                tx.source != "card_payment" && !tx.source.contains("transfer", ignoreCase = true)
        }.groupBy { categoryNames[it.categoryId] ?: "Sem categoria" }
            .map { AnalysisCategory(it.key, it.value.sumOf { tx -> abs(tx.amount) }) }
            .filter { it.value > 0.001 }.sortedByDescending { it.value }
    }
    val maxBar = (months.maxOfOrNull { maxOf(it.income, it.expense) } ?: 0.0).coerceAtLeast(1.0)
    val expenseDelta = if (lastMonth != null && lastMonth.expense > 0.0 && thisMonth != null)
        (thisMonth.expense - lastMonth.expense) / lastMonth.expense * 100.0 else null

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Análise financeira", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Entradas e despesas já registradas neste Workspace. Compras no cartão são contabilizadas uma vez; pagamentos de fatura não entram novamente.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(3 to "3 meses", 6 to "6 meses", 12 to "12 meses").forEachIndexed { index, choice ->
                    SegmentedButton(selectedWindow == choice.first, onClick = { selectedWindow = choice.first },
                        shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(choice.second) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                AnalysisStat("Entradas do mês", currency.format(thisMonth?.income ?: 0.0), analysisGreen, Modifier.weight(1f))
                AnalysisStat("Despesas do mês", currency.format(thisMonth?.expense ?: 0.0), analysisRed, Modifier.weight(1f))
            }
            Text("Evolução por mês", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AnalysisLegend(analysisGreen, "Entradas")
                AnalysisLegend(analysisRed, "Despesas")
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                months.forEach { m ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.height(125.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            AnalysisBar(m.income, maxBar, analysisGreen)
                            AnalysisBar(m.expense, maxBar, analysisRed)
                        }
                        Text(m.month.month.getDisplayName(TextStyle.SHORT, pt).replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall)
                        Text(m.month.year.toString(), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider()
            Text("Gastos por categoria no mês", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (expenseCategories.isEmpty()) Text("Nenhuma despesa registrada neste mês.", style = MaterialTheme.typography.bodySmall)
            else {
                val total = expenseCategories.sumOf { it.value }.coerceAtLeast(0.01)
                // Exibe todas as categorias sem alongar a tela de Previsao.
                // A barra lateral representa a posicao da rolagem nesta lista.
                val categoryScroll = rememberScrollState()
                val categoryBarColor = MaterialTheme.colorScheme.primary
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(categoryScroll)
                        .drawWithContent {
                            drawContent()
                            if (categoryScroll.maxValue > 0) {
                                val barWidth = 3.dp.toPx()
                                val barHeight = (size.height * size.height /
                                    (size.height + categoryScroll.maxValue.toFloat()))
                                    .coerceAtLeast(24.dp.toPx()).coerceAtMost(size.height)
                                val travel = size.height - barHeight
                                val y = travel * categoryScroll.value.toFloat() / categoryScroll.maxValue
                                drawRoundRect(
                                    color = categoryBarColor.copy(alpha = 0.85f),
                                    topLeft = Offset(size.width - barWidth, y),
                                    size = Size(barWidth, barHeight),
                                    cornerRadius = CornerRadius(barWidth / 2f)
                                )
                            }
                        }
                        .padding(end = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    expenseCategories.forEach { category ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(category.name, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(currency.format(category.value), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                            LinearProgressIndicator(progress = { (category.value / total).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(7.dp), color = analysisBlue,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }
            if (expenseDelta != null) {
                val trend = if (expenseDelta > 0.5) "aumentaram" else if (expenseDelta < -0.5) "diminuíram" else "ficaram estáveis"
                Text("Comparação: as despesas $trend em ${"%.1f".format(pt, abs(expenseDelta))}% em relação ao mês anterior. O mês atual pode estar incompleto.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Text("Próximos meses • projeção", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Estimativas baseadas nos lançamentos futuros já previstos. Não são valores recebidos ou pagos.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val next = forecastMonths.filter { it.first.isAfter(YearMonth.from(now)) }.take(3)
            if (next.isEmpty()) Text("Ainda não há lançamentos previstos para os próximos meses.", style = MaterialTheme.typography.bodySmall)
            next.forEach { (month, income, expense) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(month.month.getDisplayName(TextStyle.SHORT, pt).replaceFirstChar { it.uppercase() } + " ${month.year}",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("+${currency.format(income)}  −${currency.format(expense)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AnalysisStat(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = accent.copy(alpha = 0.09f), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleSmall, color = accent, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun AnalysisLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AnalysisBar(value: Double, max: Double, color: Color) {
    val px = ((value / max) * 116.0).coerceIn(0.0, 116.0).toFloat()
    Box(Modifier.width(19.dp).height(px.dp.coerceAtLeast(2.dp)).background(color, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
}
