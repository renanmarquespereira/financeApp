package com.financeapp.mobile.ui.home

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financeapp.mobile.data.local.AccountEntity
import com.financeapp.mobile.data.local.TransactionEntity
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Emprestimos pessoais a receber.
 * Metadados locais (inclusive recebimentos de versoes antigas sem conta) permanecem
 * no backup JSON por Workspace, junto aos registros de Minhas Dividas.
 * Novos emprestimos e recebimentos criam transacoes bancarias reais vinculadas por ID.
 * Nao criamos saidas retroativas para cadastros anteriores a esta versao.
 */
internal data class LentPayment(val id: String, val date: String, val cents: Long)
internal data class LentMoney(
    val id: String,
    val borrower: String,
    val amountCents: Long,
    val lentOn: String,
    val firstDueOn: String?,
    val accountId: Int?,
    val notes: String,
    val payments: List<LentPayment> = emptyList(),
    // True somente enquanto a saida inicial aguarda confirmacao do lancamento.
    val outflowPending: Boolean = false
)

private val loanDateFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu")
    .withResolverStyle(java.time.format.ResolverStyle.STRICT)
private val loanCurrency = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
private fun loanMoney(cents: Long): String = loanCurrency.format(cents / 100.0)
private fun loanDateText(iso: String?): String = iso?.let {
    runCatching { LocalDate.parse(it).format(loanDateFormatter) }.getOrDefault(it)
} ?: "Sem vencimento"

private fun loanParseDate(input: String): LocalDate? = if (input.isBlank()) null else try {
    LocalDate.parse(input.trim(), loanDateFormatter)
} catch (_: DateTimeParseException) {
    runCatching { LocalDate.parse(input.trim()) }.getOrNull()
}

/** Aceita R$ 1.234,56 ou 1234.56; sempre em centavos. */
internal fun parseLentCents(input: String): Long? {
    val raw = input.trim().replace("R$", "").replace(" ", "")
    if (raw.isBlank() || raw.any { !it.isDigit() && it != ',' && it != '.' }) return null
    val comma = raw.lastIndexOf(',')
    val dot = raw.lastIndexOf('.')
    val decimalIndex = when {
        comma >= 0 -> comma
        dot >= 0 && raw.length - dot - 1 in 1..2 -> dot
        else -> -1
    }
    val whole = if (decimalIndex < 0) raw.filter { it.isDigit() }
                else raw.substring(0, decimalIndex).filter { it.isDigit() }
    val fraction = if (decimalIndex < 0) "" else raw.substring(decimalIndex + 1)
    if (whole.isBlank() || fraction.length > 2 || !fraction.all { it.isDigit() }) return null
    return runCatching {
        BigDecimal("$whole.${fraction.padEnd(2, '0')}")
            .multiply(BigDecimal(100)).setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact().takeIf { it > 0L }
    }.getOrNull()
}

private fun lentPrefsKey(userKey: String, workspaceId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(userKey.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it) }
    return "loans_receivable_v1_${digest}_$workspaceId"
}

internal fun loadLentMoney(context: Context, userKey: String, workspaceId: String): List<LentMoney> {
    val raw = context.getSharedPreferences("financeapp_debts", Context.MODE_PRIVATE)
        .getString(lentPrefsKey(userKey, workspaceId), null) ?: return emptyList()
    return runCatching {
        val rows = JSONArray(raw)
        (0 until rows.length()).mapNotNull { i ->
            runCatching {
                val obj = rows.getJSONObject(i)
                val payRows = obj.optJSONArray("payments") ?: JSONArray()
                LentMoney(
                    id = obj.getString("id"), borrower = obj.getString("borrower"),
                    amountCents = obj.getLong("amountCents"), lentOn = obj.getString("lentOn"),
                    firstDueOn = obj.optString("firstDueOn").takeIf { it.isNotBlank() && it != "null" },
                    accountId = if (!obj.has("accountId") || obj.isNull("accountId")) null else obj.optInt("accountId"),
                    notes = obj.optString("notes"),
                    outflowPending = obj.optBoolean("outflowPending", false),
                    payments = (0 until payRows.length()).mapNotNull { j ->
                        runCatching {
                            val pay = payRows.getJSONObject(j)
                            LentPayment(pay.getString("id"), pay.getString("date"), pay.getLong("cents"))
                        }.getOrNull()?.takeIf { it.cents > 0L }
                    }
                )
            }.getOrNull()?.takeIf { it.borrower.isNotBlank() && it.amountCents > 0L }
        }
    }.getOrDefault(emptyList())
}

internal fun saveLentMoney(context: Context, userKey: String, workspaceId: String, loans: List<LentMoney>) {
    val array = JSONArray()
    loans.forEach { loan ->
        val payments = JSONArray()
        loan.payments.forEach { pay ->
            payments.put(JSONObject().put("id", pay.id).put("date", pay.date).put("cents", pay.cents))
        }
        array.put(JSONObject().put("id", loan.id).put("borrower", loan.borrower)
            .put("amountCents", loan.amountCents).put("lentOn", loan.lentOn)
            .put("firstDueOn", loan.firstDueOn ?: JSONObject.NULL)
            .put("accountId", loan.accountId ?: JSONObject.NULL)
            .put("notes", loan.notes).put("outflowPending", loan.outflowPending)
            .put("payments", payments))
    }
    check(context.getSharedPreferences("financeapp_debts", Context.MODE_PRIVATE).edit()
        .putString(lentPrefsKey(userKey, workspaceId), array.toString()).commit()) {
        "Nao foi possivel salvar os emprestimos neste aparelho."
    }
}

/** Sufixo estavel associa entrada no extrato a emprestimo; nao depende do nome do devedor. */
private fun loanReference(loanId: String): String = "[emprestimo:$loanId]"
/** Identificacao estrita do vinculo; nao exclui transacoes pela pessoa, data ou valor. */
internal fun isLentMovementFor(loanId: String, tx: TransactionEntity): Boolean =
    tx.cardId == null && tx.description.endsWith(loanReference(loanId)) &&
        ((tx.transactionType == "debit" && tx.amount < 0.0) ||
            (tx.transactionType == "credit" && tx.amount > 0.0))

internal fun isLentReceiptFor(loanId: String, tx: TransactionEntity): Boolean =
    tx.cardId == null && tx.accountId != null && tx.amount > 0.0 &&
    tx.transactionType == "credit" && tx.description.endsWith(loanReference(loanId))

private fun loanPaidCents(loan: LentMoney, transactions: List<TransactionEntity>): Long {
    val bank = transactions.asSequence().filter { isLentReceiptFor(loan.id, it) }
        .sumOf { (it.amount * 100.0).roundToLong().coerceAtLeast(0L) }
    return (loan.payments.sumOf { it.cents } + bank).coerceAtMost(loan.amountCents)
}
private fun loanOpenCents(loan: LentMoney, transactions: List<TransactionEntity>): Long =
    (loan.amountCents - loanPaidCents(loan, transactions)).coerceAtLeast(0L)

private fun loanIsOverdue(loan: LentMoney, transactions: List<TransactionEntity>, today: LocalDate): Boolean {
    val due = loan.firstDueOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return due != null && today.isAfter(due) && loanOpenCents(loan, transactions) > 0L
}

internal fun isLentDisbursementFor(loanId: String, tx: TransactionEntity): Boolean =
    tx.cardId == null && tx.accountId != null && tx.amount < 0.0 &&
        tx.transactionType == "debit" && tx.description.endsWith(loanReference(loanId))

private data class LoanReceiptSelection(val loanId: String, val transactionId: Int?, val legacyPaymentId: String?)

private data class LoanHistoryItem(
    val date: String,
    val label: String,
    val cents: Long,
    val account: String?,
    val isBankTransaction: Boolean,
    val transactionId: Int? = null,
    val legacyPaymentId: String? = null
)

private fun loanHistory(
    loan: LentMoney,
    transactions: List<TransactionEntity>,
    accounts: List<AccountEntity>
): List<LoanHistoryItem> {
    fun bankName(id: Int?): String? = accounts.firstOrNull { it.id == id }
        ?.let { "${it.institutionName} • ${it.accountName.orEmpty()}" }
    val paidOut = transactions.filter { isLentDisbursementFor(loan.id, it) }
    return buildList {
        if (paidOut.isEmpty()) {
            // Emprestimos antigos nao geravam uma saida bancaria. Nunca criar uma retroativamente.
            add(LoanHistoryItem(loan.lentOn, "Emprestimo cadastrado (sem saida bancaria)",
                -loan.amountCents, bankName(loan.accountId), false))
        } else paidOut.forEach { tx ->
            add(LoanHistoryItem(tx.date.take(10), "Dinheiro emprestado", 
                (tx.amount * 100.0).roundToLong(), bankName(tx.accountId), true))
        }
        transactions.filter { isLentReceiptFor(loan.id, it) }.forEach { tx ->
            add(LoanHistoryItem(tx.date.take(10), "Pagamento recebido",
                (tx.amount * 100.0).roundToLong(), bankName(tx.accountId), true,
                transactionId = tx.id))
        }
        loan.payments.forEach { payment ->
            add(LoanHistoryItem(payment.date, "Pagamento antigo (sem conta vinculada)",
                payment.cents, null, false, legacyPaymentId = payment.id))
        }
    }.sortedWith(compareByDescending<LoanHistoryItem> { it.date }.thenBy { it.cents })
}


/** Mesmo seletor Material3 usado no restante do FinanceApp. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoanDateSelector(
    label: String,
    date: LocalDate?,
    onDateChange: (LocalDate?) -> Unit,
    optional: Boolean = false,
    enabled: Boolean = true
) {
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { picking = true },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled
    ) {
        Icon(Icons.Default.CalendarMonth, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("$label: ${date?.format(loanDateFormatter) ?: "Selecionar data"}")
    }
    if (optional && date != null) {
        TextButton(onClick = { onDateChange(null) }, enabled = enabled) {
            Text("Sem vencimento definido")
        }
    }
    if (picking) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        onDateChange(java.time.Instant.ofEpochMilli(it)
                            .atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancelar") } }
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
internal fun LoansReceivableScreen(
    userKey: String,
    workspaceId: String,
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
    onCreateManualAccount: (String, String?, String?) -> Unit,
    onCreateManual: (Int?, Int?, String, Double, String, String, Int?, Int, String?, (Boolean) -> Unit) -> Unit,
    onDeleteLoanMovements: (String, List<TransactionEntity>, (Boolean) -> Unit) -> Unit,
    onUpdateLoanReceipt: (String, TransactionEntity, Int, Long, String, (Boolean) -> Unit) -> Unit,
    onDeleteLoanReceipt: (String, TransactionEntity, (Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var loans by remember(userKey, workspaceId) {
        mutableStateOf(loadLentMoney(context, userKey, workspaceId))
    }
    var showCreate by remember(userKey, workspaceId) { mutableStateOf(false) }
    var receiveLoan by remember(userKey, workspaceId) { mutableStateOf<LentMoney?>(null) }
    var detailLoanId by remember(userKey, workspaceId) { mutableStateOf<String?>(null) }
    var editReceipt by remember(userKey, workspaceId) { mutableStateOf<LoanReceiptSelection?>(null) }
    var deleteReceipt by remember(userKey, workspaceId) { mutableStateOf<LoanReceiptSelection?>(null) }
    var deleteLoan by remember(userKey, workspaceId) { mutableStateOf<LentMoney?>(null) }
    var deleteWithMovements by remember(userKey, workspaceId) { mutableStateOf<Boolean?>(null) }
    var deletingLoan by remember(userKey, workspaceId) { mutableStateOf(false) }
    var error by remember(userKey, workspaceId) { mutableStateOf<String?>(null) }
    val today = LocalDate.now()

    fun update(next: List<LentMoney>): Boolean = runCatching {
        saveLentMoney(context, userKey, workspaceId, next)
    }.fold(onSuccess = {
        loans = next
        error = null
        true
    }, onFailure = {
        error = it.message ?: "Nao foi possivel salvar"
        false
    })

    // Se o aplicativo foi fechado apos salvar a saida, mas antes de confirmar o
    // controle local, recupera o status pelo vinculo presente em Transacoes.
    LaunchedEffect(loans, transactions) {
        val reconciled = loans.map { loan ->
            if (loan.outflowPending && transactions.any { isLentDisbursementFor(loan.id, it) })
                loan.copy(outflowPending = false)
            else loan
        }
        if (reconciled != loans) update(reconciled)
    }

    val total = loans.sumOf { it.amountCents }
    val received = loans.sumOf { loanPaidCents(it, transactions) }
    val overdue = loans.filter { loanIsOverdue(it, transactions, today) }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Dinheiro emprestado", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Valores que outras pessoas devem a voce", style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalIconButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "Novo emprestimo")
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("A receber", style = MaterialTheme.typography.bodySmall)
                Text(loanMoney((total - received).coerceAtLeast(0)),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Emprestado: ${loanMoney(total)}  •  Recebido: ${loanMoney(received)}",
                    style = MaterialTheme.typography.bodySmall)
                if (overdue.isNotEmpty()) Text("${overdue.size} emprestimo(s) em atraso",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        if (loans.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Payments, null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text("Nenhum emprestimo cadastrado")
                Text("Toque em + para registrar um valor emprestado.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(loans.sortedWith(compareBy<LentMoney> { loanOpenCents(it, transactions) == 0L }
                    .thenBy { it.firstDueOn ?: "9999" }), key = { it.id }) { loan ->
                    val paid = loanPaidCents(loan, transactions)
                    val open = loanOpenCents(loan, transactions)
                    val late = loanIsOverdue(loan, transactions, today)
                    val outflowExists = transactions.any { isLentDisbursementFor(loan.id, it) }
                    ElevatedCard(onClick = { detailLoanId = loan.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(loan.borrower, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(when { open == 0L -> "Quitado"; late -> "Atrasado";
                                    paid > 0L -> "Parcial"; else -> "Em aberto" },
                                    color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Text("Falta ${loanMoney(open)} de ${loanMoney(loan.amountCents)}")
                            Text("Recebido: ${loanMoney(paid)}", style = MaterialTheme.typography.bodySmall)
                            Text("Banco de origem: ${accounts.firstOrNull { it.id == loan.accountId }?.institutionName ?: "Nao informado (cadastro antigo)"}",
                                style = MaterialTheme.typography.bodySmall)
                            if (open > 0L) Text("Vencimento previsto: ${loanDateText(loan.firstDueOn)}",
                                style = MaterialTheme.typography.bodySmall)
                            if (loan.outflowPending && !outflowExists) {
                                Text("Saida bancaria ainda nao confirmada. Verifique o extrato antes de tentar novamente.",
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (loan.notes.isNotBlank()) Text(loan.notes, style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { detailLoanId = loan.id }) {
                                    Icon(Icons.Default.History, null); Spacer(Modifier.width(4.dp)); Text("Historico")
                                }
                                TextButton(onClick = {
                                    deleteWithMovements = null
                                    deleteLoan = loan
                                }) {
                                    Icon(Icons.Default.DeleteOutline, null); Text("Excluir")
                                }
                            }
                            Button(onClick = { receiveLoan = loan }, modifier = Modifier.fillMaxWidth(),
                                enabled = open > 0L && !loan.outflowPending) {
                                Text("Registrar recebimento")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        var borrower by remember { mutableStateOf("") }
        var amountDigits by remember { mutableStateOf("") }
        var lentDate by remember { mutableStateOf(today) }
        var dueDate by remember { mutableStateOf<LocalDate?>(null) }
        var notes by remember { mutableStateOf("") }
        var accountId by remember { mutableStateOf<Int?>(null) }
        var accountExpanded by remember { mutableStateOf(false) }
        var showQuickBankForm by remember { mutableStateOf(false) }
        var quickBankName by remember { mutableStateOf("") }
        var quickBankAgency by remember { mutableStateOf("") }
        var quickBankNumber by remember { mutableStateOf("") }
        var awaitingBankName by remember { mutableStateOf<String?>(null) }
        var existingBankIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var submitting by remember { mutableStateOf(false) }
        var validation by remember { mutableStateOf<String?>(null) }

        // O banco e criado pelo fluxo normal do FinanceApp. Aguarde a lista de
        // contas atualizar antes de seleciona-lo; nao afirme sucesso antecipado.
        LaunchedEffect(accounts, awaitingBankName) {
            val requested = awaitingBankName
            if (requested != null) {
                val added = accounts.firstOrNull {
                    it.id !in existingBankIds && it.institutionName.equals(requested, ignoreCase = true)
                }
                if (added != null) {
                    accountId = added.id
                    awaitingBankName = null
                    showQuickBankForm = false
                    validation = null
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!submitting) showCreate = false },
            title = { Text("Novo dinheiro emprestado") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 490.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(borrower, { borrower = it }, label = { Text("Emprestei para *") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !submitting)
                    OutlinedTextField(
                        value = amountDigits,
                        onValueChange = { amountDigits = BrlMoney.digits(it) },
                        label = { Text("Valor emprestado (R$) *") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = BrlMoneyVisualTransformation,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !submitting
                    )
                    LoanDateSelector("Data do emprestimo", lentDate, { selected ->
                        selected?.let { lentDate = it }
                    }, enabled = !submitting)
                    LoanDateSelector("Vencimento previsto", dueDate, { dueDate = it }, optional = true,
                        enabled = !submitting)
                    Box {
                        OutlinedButton(onClick = { accountExpanded = true }, modifier = Modifier.fillMaxWidth(),
                            enabled = !submitting && accounts.isNotEmpty()) {
                            Text(accounts.firstOrNull { it.id == accountId }
                                ?.let { "${it.institutionName} • ${it.accountName.orEmpty()}" }
                                ?: "Banco de onde saiu o dinheiro *")
                        }
                        DropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                            accounts.forEach { account ->
                                DropdownMenuItem(text = { Text("${account.institutionName} • ${account.accountName.orEmpty()}") },
                                    onClick = { accountId = account.id; accountExpanded = false })
                            }
                        }
                    }
                    if (accounts.isEmpty()) Text(
                        "Nenhum banco cadastrado. Cadastre uma conta aqui sem sair do emprestimo.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { showQuickBankForm = !showQuickBankForm },
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (showQuickBankForm) "Fechar cadastro de banco" else "Cadastrar banco agora")
                    }
                    if (showQuickBankForm) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                quickBankName, { if (it.length <= 160) quickBankName = it },
                                label = { Text("Nome do banco / conta *") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            OutlinedTextField(
                                quickBankAgency, { if (it.length <= 30) quickBankAgency = it },
                                label = { Text("Agencia (opcional)") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            OutlinedTextField(
                                quickBankNumber, { if (it.length <= 50) quickBankNumber = it },
                                label = { Text("Numero da conta (opcional)") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            Button(
                                onClick = {
                                    existingBankIds = accounts.map { it.id }.toSet()
                                    awaitingBankName = quickBankName.trim()
                                    onCreateManualAccount(
                                        quickBankName.trim(),
                                        quickBankAgency.trim().ifBlank { null },
                                        quickBankNumber.trim().ifBlank { null }
                                    )
                                },
                                enabled = !submitting && quickBankName.isNotBlank() && awaitingBankName == null,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Salvar novo banco") }
                        }
                    }
                    if (awaitingBankName != null) {
                        Text("Aguardando o novo banco aparecer na lista...",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { awaitingBankName = null }) {
                            Text("Banco nao apareceu? Tentar novamente")
                        }
                    }
                    OutlinedTextField(notes, { notes = it }, label = { Text("Observacoes") },
                        modifier = Modifier.fillMaxWidth(), enabled = !submitting)
                    Text("Ao cadastrar, uma SAIDA sera registrada automaticamente em Transacoes, " +
                        "na conta selecionada. O emprestimo sera controlado por recebimentos livres, sem parcelas.",
                        style = MaterialTheme.typography.bodySmall)
                    if (submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (validation != null) Text(validation.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(enabled = !submitting && accounts.isNotEmpty(), onClick = {
                    val cents = amountDigits.toLongOrNull()?.takeIf { it > 0L }
                    val bankId = accountId
                    validation = when {
                        borrower.trim().isBlank() -> "Informe a pessoa que recebeu o emprestimo."
                        cents == null -> "Informe um valor valido maior que zero."
                        dueDate != null && dueDate!!.isBefore(lentDate) -> "Vencimento anterior ao emprestimo."
                        bankId == null || accounts.none { it.id == bankId } -> "Selecione o banco de origem. Ele e obrigatorio."
                        else -> null
                    }
                    if (validation == null && cents != null && bankId != null) {
                        val loan = LentMoney(UUID.randomUUID().toString(), borrower.trim(), cents,
                            lentDate.toString(), dueDate?.toString(), bankId, notes.trim(),
                            outflowPending = true)
                        // Persistir primeiro a identidade do emprestimo, usada na descricao da transacao.
                        // Se o lancamento falhar, removemos o cadastro pendente.
                        if (update(loans + loan)) {
                            submitting = true
                            val description = "Dinheiro emprestado • ${loan.borrower} ${loanReference(loan.id)}"
                            onCreateManual(bankId, null, description, -cents / 100.0, "debit",
                                lentDate.toString(), null, 1, null) { success ->
                                submitting = false
                                if (success) {
                                    val saved = update(loans.map { current ->
                                        if (current.id == loan.id) current.copy(outflowPending = false) else current
                                    })
                                    showCreate = false
                                    if (!saved) error = "Saida criada em Transacoes, mas o status do controle ficou pendente. Confira o historico."
                                } else {
                                    val undone = update(loans.filterNot { it.id == loan.id })
                                    validation = if (undone)
                                        "Falha ao registrar a saida no banco. Emprestimo nao foi cadastrado."
                                    else "Falha ao registrar a saida e ao remover o cadastro pendente. Verifique seu extrato."
                                }
                            }
                        }
                    }
                }) { Text("Cadastrar e registrar saida") }
            },
            dismissButton = { TextButton(enabled = !submitting, onClick = { showCreate = false }) { Text("Cancelar") } }
        )
    }


    receiveLoan?.let { loan ->
        var amountDigits by remember(loan.id) { mutableStateOf("") }
        var receivedDate by remember(loan.id) { mutableStateOf(today) }
        var selectedAccountId by remember(loan.id) { mutableStateOf<Int?>(null) }
        var accountExpanded by remember(loan.id) { mutableStateOf(false) }
        var submitting by remember(loan.id) { mutableStateOf(false) }
        var validation by remember(loan.id) { mutableStateOf<String?>(null) }
        val open = loanOpenCents(loan, transactions)
        AlertDialog(
            onDismissRequest = { if (!submitting) receiveLoan = null },
            title = { Text("Registrar pagamento recebido") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${loan.borrower} • A receber ${loanMoney(open)}")
                    OutlinedTextField(
                        value = amountDigits,
                        onValueChange = { amountDigits = BrlMoney.digits(it) },
                        label = { Text("Valor recebido (R$)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = BrlMoneyVisualTransformation,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    LoanDateSelector("Data do recebimento", receivedDate,
                        { selected -> selected?.let { receivedDate = it } }, enabled = !submitting)
                    Box {
                        OutlinedButton(onClick = { accountExpanded = true },
                            modifier = Modifier.fillMaxWidth(), enabled = !submitting) {
                            val account = accounts.firstOrNull { it.id == selectedAccountId }
                            Text(account?.let { "${it.institutionName} • ${it.accountName.orEmpty()}" }
                                ?: "Banco que recebeu o pagamento *")
                        }
                        DropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                            accounts.forEach { account ->
                                DropdownMenuItem(text = { Text("${account.institutionName} • ${account.accountName.orEmpty()}") },
                                    onClick = { selectedAccountId = account.id; accountExpanded = false })
                            }
                        }
                    }
                    if (accounts.isEmpty()) Text("Cadastre primeiro um banco ou conta na aba Bancos.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("Selecione a conta onde o pagamento entrou. Uma ENTRADA sera registrada em Transacoes e no saldo desse banco.",
                        style = MaterialTheme.typography.bodySmall)
                    if (submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (validation != null) Text(validation.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(enabled = !submitting && open > 0L && accounts.isNotEmpty(), onClick = {
                    val cents = amountDigits.toLongOrNull()?.takeIf { it > 0L }
                    val date = receivedDate
                    validation = when {
                        cents == null -> "Informe um valor valido maior que zero."
                        cents > open -> "Valor maior que o saldo pendente."
                        date.isBefore(LocalDate.parse(loan.lentOn)) -> "Data anterior ao emprestimo."
                        selectedAccountId == null || accounts.none { it.id == selectedAccountId } -> "Selecione o banco que recebeu o pagamento."
                        else -> null
                    }
                    if (validation == null && cents != null && date != null) {
                        val bankId = selectedAccountId
                        if (bankId != null) {
                            submitting = true
                            val description = "Recebimento de emprestimo • ${loan.borrower} ${loanReference(loan.id)}"
                            onCreateManual(bankId, null, description, cents / 100.0, "credit",
                                date.toString(), null, 1, null) { success ->
                                submitting = false
                                if (success) {
                                    receiveLoan = null
                                } else {
                                    validation = "Falha ao salvar a entrada no banco. Nenhum recebimento foi confirmado."
                                }
                            }
                        }
                    }
                }) { Text("Registrar") }
            },
            dismissButton = { TextButton(enabled = !submitting, onClick = { receiveLoan = null }) { Text("Cancelar") } }
        )
    }

    detailLoanId?.let { id ->
        val loan = loans.firstOrNull { it.id == id }
        if (loan != null) {
            val history = loanHistory(loan, transactions, accounts)
            AlertDialog(
                onDismissRequest = { detailLoanId = null },
                title = { Text("Historico • ${loan.borrower}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Emprestado: ${loanMoney(loan.amountCents)}  •  Recebido: ${loanMoney(loanPaidCents(loan, transactions))}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("A receber: ${loanMoney(loanOpenCents(loan, transactions))}",
                            fontWeight = FontWeight.Bold)
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp),
                            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(history) { item ->
                                ElevatedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("${loanDateText(item.date)} • ${item.label}",
                                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text("${if (item.cents > 0) "+" else "-"}${loanMoney(kotlin.math.abs(item.cents))}",
                                            color = if (item.cents < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.titleSmall)
                                        if (item.account != null) Text("Banco: ${item.account}",
                                            style = MaterialTheme.typography.bodySmall)
                                        if (!item.isBankTransaction) Text("Registro de controle; nao e movimentacao do banco.",
                                            style = MaterialTheme.typography.labelSmall)
                                        if (item.transactionId != null || item.legacyPaymentId != null) {
                                            Row(Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TextButton(modifier = Modifier.weight(1f), onClick = {
                                                    editReceipt = LoanReceiptSelection(loan.id, item.transactionId, item.legacyPaymentId)
                                                    detailLoanId = null
                                                }) { Text("Editar", maxLines = 1) }
                                                TextButton(modifier = Modifier.weight(1f), onClick = {
                                                    deleteReceipt = LoanReceiptSelection(loan.id, item.transactionId, item.legacyPaymentId)
                                                    detailLoanId = null
                                                }) { Text("Excluir", maxLines = 1,
                                                    color = MaterialTheme.colorScheme.error) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { detailLoanId = null }) { Text("Fechar") } }
            )
        }
    }

    editReceipt?.let { selected ->
        val loan = loans.firstOrNull { it.id == selected.loanId }
        val bankReceipt = selected.transactionId?.let { txId ->
            transactions.firstOrNull { it.id == txId && isLentReceiptFor(selected.loanId, it) }
        }
        val oldPayment = loan?.payments?.firstOrNull { it.id == selected.legacyPaymentId }
        if (loan == null || (bankReceipt == null && oldPayment == null)) {
            LaunchedEffect(selected) { editReceipt = null }
        } else {
            val oldCents = bankReceipt?.let { (it.amount * 100.0).roundToLong() } ?: oldPayment!!.cents
            val oldDate = bankReceipt?.date?.take(10) ?: oldPayment!!.date
            var amountDigits by remember(selected) { mutableStateOf(oldCents.toString()) }
            var paymentDate by remember(selected) {
                mutableStateOf(runCatching { LocalDate.parse(oldDate) }.getOrDefault(LocalDate.now()))
            }
            var accountId by remember(selected) { mutableStateOf(bankReceipt?.accountId) }
            var showAccounts by remember(selected) { mutableStateOf(false) }
            var saving by remember(selected) { mutableStateOf(false) }
            var validation by remember(selected) { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { if (!saving) editReceipt = null },
                title = { Text("Editar pagamento recebido") },
                text = {
                    Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Devedor: ${loan.borrower}")
                        OutlinedTextField(
                            value = amountDigits, onValueChange = { amountDigits = BrlMoney.digits(it) },
                            label = { Text("Valor recebido (R$)") },
                            visualTransformation = BrlMoneyVisualTransformation,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        LoanDateSelector("Data do recebimento", paymentDate,
                            { it?.let { date -> paymentDate = date } }, enabled = !saving)
                        if (bankReceipt != null) {
                            Box {
                                OutlinedButton(onClick = { showAccounts = true },
                                    enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                                    Text(accounts.firstOrNull { it.id == accountId }?.let {
                                        "${it.institutionName} - ${it.accountName.orEmpty()}"
                                    } ?: "Banco do recebimento *")
                                }
                                DropdownMenu(expanded = showAccounts, onDismissRequest = { showAccounts = false }) {
                                    accounts.forEach { account ->
                                        DropdownMenuItem(text = {
                                            Text("${account.institutionName} - ${account.accountName.orEmpty()}")
                                        }, onClick = { accountId = account.id; showAccounts = false })
                                    }
                                }
                            }
                        } else {
                            Text("Pagamento antigo sem banco vinculado; alterar este registro nao movimenta o saldo bancario.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (validation != null) Text(validation.orEmpty(), color = MaterialTheme.colorScheme.error)
                        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    Button(enabled = !saving, onClick = {
                        val cents = amountDigits.toLongOrNull()
                        val possibleMax = loanOpenCents(loan, transactions) + oldCents
                        validation = when {
                            cents == null || cents <= 0L -> "Informe um valor valido maior que zero."
                            cents > possibleMax -> "O valor ultrapassa o saldo total deste emprestimo."
                            paymentDate.isBefore(LocalDate.parse(loan.lentOn)) -> "Data anterior ao emprestimo."
                            bankReceipt != null && (accountId == null || accounts.none { it.id == accountId }) ->
                                "Escolha o banco que recebeu o valor."
                            else -> null
                        }
                        if (validation == null && cents != null) {
                            if (bankReceipt != null && accountId != null) {
                                saving = true
                                onUpdateLoanReceipt(loan.id, bankReceipt, accountId!!, cents, paymentDate.toString()) { ok ->
                                    saving = false
                                    if (ok) {
                                        editReceipt = null
                                        detailLoanId = loan.id
                                    } else validation = "Nao foi possivel alterar a entrada no banco."
                                }
                            } else if (oldPayment != null) {
                                val next = loans.map { item ->
                                    if (item.id != loan.id) item else item.copy(payments = item.payments.map { payment ->
                                        if (payment.id != oldPayment.id) payment
                                        else payment.copy(cents = cents, date = paymentDate.toString())
                                    })
                                }
                                if (update(next)) {
                                    editReceipt = null
                                    detailLoanId = loan.id
                                }
                            }
                        }
                    }) { Text("Salvar alteracoes") }
                },
                dismissButton = {
                    TextButton(enabled = !saving, onClick = { editReceipt = null }) { Text("Cancelar") }
                }
            )
        }
    }

    deleteReceipt?.let { selected ->
        val loan = loans.firstOrNull { it.id == selected.loanId }
        val bankReceipt = selected.transactionId?.let { txId ->
            transactions.firstOrNull { it.id == txId && isLentReceiptFor(selected.loanId, it) }
        }
        val oldPayment = loan?.payments?.firstOrNull { it.id == selected.legacyPaymentId }
        if (loan == null || (bankReceipt == null && oldPayment == null)) {
            LaunchedEffect(selected) { deleteReceipt = null }
        } else {
            var deleting by remember(selected) { mutableStateOf(false) }
            val amount = bankReceipt?.let { (it.amount * 100.0).roundToLong() } ?: oldPayment!!.cents
            AlertDialog(
                onDismissRequest = { if (!deleting) deleteReceipt = null },
                title = { Text("Excluir recebimento?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Excluir o pagamento de ${loanMoney(amount)} registrado para ${loan.borrower}?")
                        Text(if (bankReceipt != null)
                            "A entrada bancaria correspondente tambem sera excluida de Transacoes, e o valor voltara ao saldo a receber."
                            else "O registro antigo sera removido do historico. Nenhuma entrada bancaria sera alterada.")
                        if (deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    Button(enabled = !deleting,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            if (bankReceipt != null) {
                                deleting = true
                                onDeleteLoanReceipt(loan.id, bankReceipt) { ok ->
                                    deleting = false
                                    if (ok) {
                                        deleteReceipt = null
                                        detailLoanId = loan.id
                                    } else error = "Nao foi possivel excluir o recebimento do banco."
                                }
                            } else if (oldPayment != null) {
                                val next = loans.map { item -> if (item.id != loan.id) item else item.copy(
                                    payments = item.payments.filterNot { it.id == oldPayment.id }) }
                                if (update(next)) {
                                    deleteReceipt = null
                                    detailLoanId = loan.id
                                }
                            }
                        }) { Text("Sim, excluir") }
                },
                dismissButton = {
                    TextButton(enabled = !deleting, onClick = { deleteReceipt = null }) { Text("Nao, cancelar") }
                }
            )
        }
    }

    deleteLoan?.let { loan ->
        val linked = transactions.filter { isLentMovementFor(loan.id, it) }.distinctBy { it.id }
        val outgoing = linked.count { it.transactionType == "debit" }
        val incoming = linked.count { it.transactionType == "credit" }
        val isPending = loan.outflowPending && linked.none { it.transactionType == "debit" }
        AlertDialog(
            onDismissRequest = {
                if (!deletingLoan) {
                    deleteLoan = null
                    deleteWithMovements = null
                }
            },
            title = {
                Text(if (deleteWithMovements == null) "Como deseja excluir?" else "Confirmar exclusao?")
            },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Emprestimo de ${loan.borrower} • ${loanMoney(loan.amountCents)}")
                    if (deleteWithMovements == null) {
                        Text("Escolha o que deseja apagar:")
                        Text("Somente emprestimo: remove o controle e o historico local, mantendo o extrato dos bancos.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Emprestimo e movimentacoes: apaga tambem as entradas e saidas vinculadas, atualizando os saldos bancarios.",
                            style = MaterialTheme.typography.bodySmall)
                    } else if (deleteWithMovements == true) {
                        Text("Serao apagados o emprestimo e ${linked.size} lancamento(s) vinculado(s) " +
                            "($outgoing saida(s) e $incoming entrada(s)). Os saldos das contas serao recalculados.")
                        if (linked.isEmpty()) {
                            Text("Nenhuma movimentacao vinculada foi encontrada. Se voce editou a descricao de um lancamento, " +
                                "ele podera continuar no extrato. Confira antes de prosseguir.",
                                color = MaterialTheme.colorScheme.error)
                        }
                        Text("A exclusao tambem remove comprovantes anexados a esses lancamentos " +
                            "e sera sincronizada com o servidor, quando conectado.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Apenas o cadastro e o historico local serao excluidos. " +
                            "As entradas e saidas ja registradas permanecerao em Transacoes e nos saldos dos bancos.")
                    }
                    if (isPending) {
                        Text("A saida inicial ainda esta pendente. Aguarde a confirmacao " +
                            "em Transacoes antes de excluir para evitar movimentacoes soltas.",
                            color = MaterialTheme.colorScheme.error)
                    }
                    if (deletingLoan) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    if (deleteWithMovements == null) {
                        TextButton(modifier = Modifier.fillMaxWidth(), enabled = !isPending,
                            onClick = { deleteWithMovements = false }) {
                            Text("Somente emprestimo")
                        }
                        TextButton(modifier = Modifier.fillMaxWidth(), enabled = !isPending,
                            onClick = { deleteWithMovements = true }) {
                            Text("Emprestimo e movimentacoes", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                    Button(
                        enabled = !deletingLoan && !isPending,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                        if (deleteWithMovements == false || linked.isEmpty()) {
                            if (update(loans.filterNot { it.id == loan.id })) {
                                deleteLoan = null
                                deleteWithMovements = null
                            }
                        } else {
                            deletingLoan = true
                            onDeleteLoanMovements(loan.id, linked) { success ->
                                deletingLoan = false
                                if (success) {
                                    if (update(loans.filterNot { it.id == loan.id })) {
                                        deleteLoan = null
                                        deleteWithMovements = null
                                    } else {
                                        error = "Movimentacoes excluidas, mas falhou a exclusao do cadastro. " +
                                            "Tente excluir somente o emprestimo."
                                    }
                                } else {
                                    error = "Nao foi possivel excluir as movimentacoes. " +
                                        "O cadastro do emprestimo foi preservado."
                                }
                            }
                        }
                    }) {
                        Text("Sim, excluir")
                    }
                    }
                    Spacer(Modifier.height(16.dp))
                    TextButton(modifier = Modifier.align(Alignment.CenterHorizontally),
                        enabled = !deletingLoan, onClick = {
                            if (deleteWithMovements != null) deleteWithMovements = null
                            else deleteLoan = null
                        }) { Text(if (deleteWithMovements == null) "Cancelar" else "Nao, voltar") }
                }
            }
        )
    }

}
