package com.financeapp.mobile.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financeapp.mobile.data.local.AccountEntity
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.CreditCardDto
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

private fun closingForMonth(month: YearMonth, closingDay: Int?): LocalDate {
    val close = (closingDay ?: 31).coerceIn(1, 31)
    return month.atDay(close.coerceAtMost(month.lengthOfMonth()))
}

private fun invoiceEndForDate(date: LocalDate, closingDay: Int?): LocalDate {
    val thisClose = closingForMonth(YearMonth.from(date), closingDay)
    return if (!date.isAfter(thisClose)) thisClose else closingForMonth(YearMonth.from(date).plusMonths(1), closingDay)
}

private fun invoiceStartForEnd(end: LocalDate, closingDay: Int?): LocalDate =
    closingForMonth(YearMonth.from(end).minusMonths(1), closingDay).plusDays(1)

private fun centralDueDate(end: LocalDate, dueDay: Int?): LocalDate? {
    val due = dueDay ?: return null
    var month = YearMonth.from(end)
    if (due <= end.dayOfMonth) month = month.plusMonths(1)
    return month.atDay(due.coerceAtMost(month.lengthOfMonth()))
}

private fun txLocalDate(tx: TransactionEntity) = runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull()

private enum class InvoiceStatus(val label: String) {
    OVERDUE("Vencida"),
    DUE_SOON("Próxima do vencimento"),
    OPEN("Em aberto"),
    PAID("Paga")
}

private data class InvoiceSummary(
    val card: CreditCardDto,
    val invoiceEnd: LocalDate,
    val dueDate: LocalDate?,
    val total: Double,
    val paid: Double,
    val remaining: Double,
    val status: InvoiceStatus,
)

private fun invoiceStatus(remaining: Double, dueDate: LocalDate?, today: LocalDate): InvoiceStatus {
    if (remaining <= 0.005) return InvoiceStatus.PAID
    if (dueDate != null && dueDate.isBefore(today)) return InvoiceStatus.OVERDUE
    if (dueDate != null) {
        val days = ChronoUnit.DAYS.between(today, dueDate).toInt()
        if (days in 0..5) return InvoiceStatus.DUE_SOON
    }
    return InvoiceStatus.OPEN
}

private fun buildInvoiceSummaries(
    cards: List<CreditCardDto>,
    transactions: List<TransactionEntity>,
): List<InvoiceSummary> {
    val today = LocalDate.now()
    val result = mutableListOf<InvoiceSummary>()

    cards.filter { it.active }.forEach { card ->
        val movements = transactions.filter { it.cardId == card.id && it.source != "card_payment" }
        val groups = movements.mapNotNull { tx ->
            val date = txLocalDate(tx) ?: return@mapNotNull null
            invoiceEndForDate(date, card.closingDay) to tx
        }.groupBy({ it.first }, { it.second })

        groups.forEach { (invoiceEnd, invoiceTransactions) ->
            // Compras são negativas e estornos/devoluções positivos; o saldo líquido compõe a fatura.
            val total = (-invoiceTransactions.sumOf { it.amount }).coerceAtLeast(0.0)
            if (total <= 0.005) return@forEach

            val payments = transactions.filter {
                it.cardId == card.id &&
                    it.source == "card_payment" &&
                    it.purchaseDate?.take(10) == invoiceEnd.toString()
            }
            val paid = payments.sumOf { abs(it.amount) }
            val remaining = (total - paid).coerceAtLeast(0.0)
            val due = centralDueDate(invoiceEnd, card.dueDay)
            result += InvoiceSummary(
                card = card,
                invoiceEnd = invoiceEnd,
                dueDate = due,
                total = total,
                paid = paid,
                remaining = remaining,
                status = invoiceStatus(remaining, due, today),
            )
        }
    }

    fun priority(status: InvoiceStatus) = when (status) {
        InvoiceStatus.OVERDUE -> 0
        InvoiceStatus.DUE_SOON -> 1
        InvoiceStatus.OPEN -> 2
        InvoiceStatus.PAID -> 3
    }

    return result.sortedWith(
        compareBy<InvoiceSummary> { priority(it.status) }
            .thenBy { it.dueDate ?: LocalDate.MAX }
            .thenByDescending { it.invoiceEnd }
    )
}

private fun statusColors(status: InvoiceStatus): Pair<Color, Color> = when (status) {
    InvoiceStatus.PAID -> Color(0xFFDDF3E4) to Color(0xFF24713A)
    InvoiceStatus.OVERDUE -> Color(0xFFFFDEDE) to Color(0xFFB3261E)
    InvoiceStatus.DUE_SOON -> Color(0xFFFFE9C7) to Color(0xFF9A5A00)
    InvoiceStatus.OPEN -> Color(0xFFDDEBFF) to Color(0xFF0B57B7)
}

private fun invoicePaymentAccountLabel(account: AccountEntity): String {
    val institution = account.institutionName.trim()
    val accountName = account.accountName?.trim().orEmpty()
    fun isGeneric(value: String): Boolean {
        val normalized = value.lowercase(Locale("pt", "BR"))
        return normalized.isBlank() || normalized == "conta manual" || normalized == "banco manual" || normalized == "manual"
    }
    val bankName = when {
        !isGeneric(institution) -> institution
        !isGeneric(accountName) -> accountName
        institution.isNotBlank() -> institution
        accountName.isNotBlank() -> accountName
        else -> "Conta"
    }
    val detail = accountName.takeIf { !isGeneric(it) && !it.equals(bankName, ignoreCase = true) }
    return listOfNotNull(bankName, detail).joinToString(" • ")
}

private fun bankCardColor(name: String): Color {
    val n = name.lowercase(Locale("pt", "BR")).replace(" ", "")
    return when {
        "nubank" in n || "nu" == n -> Color(0xFF6F2DBD)
        "bancodobrasil" in n || n == "bb" || "001" in n -> Color(0xFFF4C400)
        "itau" in n || "341" in n -> Color(0xFFEC7000)
        "bradesco" in n || "237" in n -> Color(0xFFCC092F)
        "santander" in n || "033" in n -> Color(0xFFEC0000)
        "inter" in n || "077" in n -> Color(0xFFFF7A00)
        "c6" in n || "336" in n -> Color(0xFF202124)
        "caixa" in n || "104" in n -> Color(0xFF005CA9)
        "picpay" in n -> Color(0xFF11C76F)
        "mercadopago" in n -> Color(0xFF009EE3)
        else -> Color(0xFF1769E0)
    }
}

private fun bankCardContentColor(name: String): Color {
    val n = name.lowercase(Locale("pt", "BR")).replace(" ", "")
    return if ("bancodobrasil" in n || n == "bb" || "001" in n) Color(0xFF12335B) else Color.White
}

private data class MonthInvoice(
    val end: LocalDate,
    val due: LocalDate?,
    val purchases: List<TransactionEntity>,
    val payments: List<TransactionEntity>,
    val total: Double,
    val paid: Double,
    val remaining: Double,
    val status: InvoiceStatus?,
)

private fun monthInvoice(card: CreditCardDto, month: YearMonth, transactions: List<TransactionEntity>): MonthInvoice {
    val end = closingForMonth(month, card.closingDay)
    val start = invoiceStartForEnd(end, card.closingDay)
    val purchases = transactions.filter { tx ->
        tx.cardId == card.id && tx.source != "card_payment" &&
            txLocalDate(tx)?.let { !it.isBefore(start) && !it.isAfter(end) } == true
    }
    val total = (-purchases.sumOf { it.amount }).coerceAtLeast(0.0)
    val payments = transactions.filter {
        it.cardId == card.id && it.source == "card_payment" && it.purchaseDate?.take(10) == end.toString()
    }
    val paid = payments.sumOf { abs(it.amount) }
    val remaining = (total - paid).coerceAtLeast(0.0)
    val due = centralDueDate(end, card.dueDay)
    val status = if (total <= 0.005) null else invoiceStatus(remaining, due, LocalDate.now())
    return MonthInvoice(end, due, purchases, payments, total, paid, remaining, status)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceCenterScreen(
    accounts: List<AccountEntity>,
    cards: List<CreditCardDto>,
    transactions: List<TransactionEntity>,
    onRegister: (Int, Int, String, Double, String, (Boolean) -> Unit) -> Unit,
) {
    val activeCards = remember(cards) { cards.filter { it.active } }
    var selectedCardId by remember(activeCards) { mutableStateOf(activeCards.firstOrNull()?.id) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var paymentInvoice by remember { mutableStateOf<Pair<CreditCardDto, LocalDate>?>(null) }
    val br = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy") }

    LaunchedEffect(activeCards) {
        if (selectedCardId == null || activeCards.none { it.id == selectedCardId }) {
            selectedCardId = activeCards.firstOrNull()?.id
        }
    }

    val selectedCard = activeCards.firstOrNull { it.id == selectedCardId }
    val invoice = selectedCard?.let { monthInvoice(it, month, transactions) }
    val monthLabel = month.month.getDisplayName(java.time.format.TextStyle.FULL, Locale("pt", "BR")).replaceFirstChar { it.uppercase() } + " de ${month.year}"

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Central de faturas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Escolha um cartão e navegue pelos meses para consultar a fatura correspondente.", style = MaterialTheme.typography.bodySmall)

        if (activeCards.isEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth()) { Text("Nenhum cartão cadastrado.", Modifier.padding(18.dp)) }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                activeCards.forEach { card ->
                    val selected = selectedCardId == card.id
                    val cardInvoice = monthInvoice(card, month, transactions)
                    val bg = bankCardColor(card.bankName)
                    val fg = bankCardContentColor(card.bankName)
                    Card(
                        modifier = Modifier.width(250.dp).height(145.dp).clickable { selectedCardId = card.id },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = bg),
                        border = if (selected) androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 8.dp else 3.dp)
                    ) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(card.nickname?.takeIf { it.isNotBlank() } ?: card.bankName, color = fg, fontWeight = FontWeight.Bold, maxLines = 1)
                                Icon(Icons.Default.CreditCard, contentDescription = null, tint = fg)
                            }
                            Text("${card.brand}  •••• ${card.lastFour}", color = fg.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium)
                            Column {
                                Text("Fatura do mês", color = fg.copy(alpha = .8f), style = MaterialTheme.typography.labelMedium)
                                Text(
                                    if (cardInvoice.total > 0.005) BrlMoney.formatDigits(BrlMoney.fromDouble(cardInvoice.remaining)) else "R$ 0,00",
                                    color = fg,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, contentDescription = "Mês anterior") }
                Text(monthLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, contentDescription = "Próximo mês") }
            }

            if (selectedCard != null && invoice != null) {
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(selectedCard.nickname?.takeIf { it.isNotBlank() } ?: selectedCard.bankName, fontWeight = FontWeight.Bold)
                                Text("Fatura de $monthLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            invoice.status?.let { status ->
                                val (bg, fg) = statusColors(status)
                                Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
                                    Text(status.label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (invoice.total <= 0.005) {
                            Text("Sem fatura neste mês", fontWeight = FontWeight.SemiBold)
                            Text("Não houve valor a pagar neste ciclo. Este mês não gera aviso nem notificação.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Total: ${BrlMoney.formatDigits(BrlMoney.fromDouble(invoice.total))}")
                            Text("Pago: ${BrlMoney.formatDigits(BrlMoney.fromDouble(invoice.paid))}  •  Em aberto: ${BrlMoney.formatDigits(BrlMoney.fromDouble(invoice.remaining))}")
                            Text("Fechamento: ${invoice.end.format(br)}  •  Vencimento: ${invoice.due?.format(br) ?: "não informado"}", style = MaterialTheme.typography.bodySmall)
                        }

                        HorizontalDivider()
                        Text("Movimentações da fatura", fontWeight = FontWeight.Bold)
                        if (invoice.purchases.isEmpty()) {
                            Text("Nenhuma compra neste período.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            invoice.purchases.sortedByDescending { txLocalDate(it) }.forEach { tx ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text(tx.description, maxLines = 2)
                                        Text(txLocalDate(tx)?.format(br) ?: tx.date.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(BrlMoney.formatDigits(BrlMoney.fromDouble(abs(tx.amount))), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        if (invoice.payments.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Pagamentos", fontWeight = FontWeight.Bold)
                            invoice.payments.sortedByDescending { it.date }.forEach { tx ->
                                Text("${BrlMoney.formatDigits(BrlMoney.fromDouble(abs(tx.amount)))} • ${tx.date.take(10)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        if (invoice.total > 0.005 && invoice.remaining > 0.005) {
                            Button(onClick = { paymentInvoice = selectedCard to invoice.end }, modifier = Modifier.fillMaxWidth()) {
                                Text("Registrar pagamento")
                            }
                        }
                    }
                }
            }
        }
    }

    paymentInvoice?.let { (card, end) ->
        RegisterInvoicePaymentDialog(
            card = card,
            invoiceEnd = end,
            accounts = accounts,
            transactions = transactions,
            onDismiss = { paymentInvoice = null },
            onRegister = onRegister,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegisterInvoicePaymentDialog(
    card: CreditCardDto,
    invoiceEnd: LocalDate,
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onRegister: (Int, Int, String, Double, String, (Boolean) -> Unit) -> Unit,
) {
    val start = invoiceStartForEnd(invoiceEnd, card.closingDay)
    val purchases = transactions.filter { tx ->
        tx.cardId == card.id &&
            tx.source != "card_payment" &&
            txLocalDate(tx)?.let { !it.isBefore(start) && !it.isAfter(invoiceEnd) } == true
    }
    val payments = transactions.filter {
        it.cardId == card.id && it.source == "card_payment" && it.purchaseDate?.take(10) == invoiceEnd.toString()
    }
    val total = (-purchases.sumOf { it.amount }).coerceAtLeast(0.0)
    val remaining = (total - payments.sumOf { abs(it.amount) }).coerceAtLeast(0.0)

    if (remaining <= 0.005 || accounts.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Fatura") },
            text = { Text(if (accounts.isEmpty()) "Cadastre uma conta para registrar o pagamento." else "Esta fatura já está paga.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
        )
        return
    }

    var accountId by remember(card.id, invoiceEnd) { mutableStateOf(accounts.first().id) }
    var menu by remember { mutableStateOf(false) }
    var cents by remember(card.id, invoiceEnd, remaining) { mutableStateOf(BrlMoney.fromDouble(remaining)) }
    var saving by remember { mutableStateOf(false) }
    var paymentDate by remember(card.id, invoiceEnd) { mutableStateOf(LocalDate.now()) }
    var showDate by remember { mutableStateOf(false) }
    val amount = BrlMoney.toDouble(cents)
    val br = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy") }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Registrar pagamento da fatura") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Restante: ${BrlMoney.formatDigits(BrlMoney.fromDouble(remaining))}")
                Box {
                    OutlinedButton(onClick = { menu = true }, Modifier.fillMaxWidth()) {
                        Text(accounts.firstOrNull { it.id == accountId }?.let(::invoicePaymentAccountLabel) ?: "Selecionar conta")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(invoicePaymentAccountLabel(account)) },
                                onClick = { accountId = account.id; menu = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = cents,
                    onValueChange = { cents = BrlMoney.digits(it) },
                    label = { Text("Valor pago") },
                    visualTransformation = BrlMoneyVisualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = paymentDate.format(br),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Data do pagamento") },
                    modifier = Modifier.fillMaxWidth().clickable { showDate = true }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && amount > 0.005 && amount <= remaining + 0.005,
                onClick = {
                    val aid = accountId ?: return@Button
                    saving = true
                    onRegister(
                        aid,
                        card.id,
                        invoiceEnd.toString(),
                        amount,
                        paymentDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime().toString()
                    ) { ok ->
                        saving = false
                        if (ok) onDismiss()
                    }
                }
            ) { Text(if (saving) "Registrando..." else "Registrar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = paymentDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { paymentDate = java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancelar") } }
        ) { DatePicker(state = state) }
    }
}
