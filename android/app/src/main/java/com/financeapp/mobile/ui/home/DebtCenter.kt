package com.financeapp.mobile.ui.home

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financeapp.mobile.data.local.AccountEntity
import com.financeapp.mobile.data.local.TransactionEntity
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

internal const val PLANNED_DEBT_PREFIX = "Prevista • Dívida • "

internal fun isPlannedDebtTransaction(tx: TransactionEntity): Boolean =
    tx.description.startsWith(PLANNED_DEBT_PREFIX, ignoreCase = true)

private fun canonicalDebtName(raw: String): String {
    val value = raw.trim()
    val cut = listOf(" • Credor:", " • Parcela")
        .map { marker -> value.indexOf(marker, ignoreCase = true) }
        .filter { it >= 0 }
        .minOrNull()
        ?: value.length
    return value.substring(0, cut).trim()
}

private fun debtNameFromDescription(description: String): String? {
    val body = when {
        description.startsWith(PLANNED_DEBT_PREFIX, ignoreCase = true) ->
            description.substring(PLANNED_DEBT_PREFIX.length)
        description.startsWith("Pagamento de dívida •", ignoreCase = true) ->
            description.substringAfter('•')
        else -> return null
    }.trim()
    return canonicalDebtName(body).takeIf { it.isNotBlank() }
}

private fun debtCreditorFromDescription(description: String): String? =
    Regex("""•\s*Credor:\s*(.*?)\s*(?:•\s*Parcela|$)""", RegexOption.IGNORE_CASE)
        .find(description)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun debtInstallmentNumber(tx: TransactionEntity): Int? =
    tx.installmentNumber ?: Regex("Parcela\\s+(\\d+)/", RegexOption.IGNORE_CASE)
        .find(tx.description)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun debtInstallmentTotal(tx: TransactionEntity): Int? =
    tx.installmentTotal ?: Regex("Parcela\\s+\\d+/(\\d+)", RegexOption.IGNORE_CASE)
        .find(tx.description)?.groupValues?.getOrNull(1)?.toIntOrNull()

internal fun debtCreditorForTransaction(
    context: Context,
    userKey: String,
    workspaceId: String,
    tx: TransactionEntity
): String? {
    if (!isPlannedDebtTransaction(tx)) return null
    val debtName = debtNameFromDescription(tx.description) ?: return null
    return loadCenterDebts(context, userKey, workspaceId)
        .firstOrNull { it.name.equals(debtName, ignoreCase = true) }
        ?.creditor
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: debtCreditorFromDescription(tx.description)
}

private data class DebtCenterPayment(
    val amount: Double,
    val date: LocalDate,
    val accountId: Int?,
    val installments: Int = 1
)

private data class DebtCenterRecord(
    val id: Long,
    val name: String,
    val creditor: String,
    val installmentAmount: Double,
    val installmentCount: Int,
    val firstDueDate: LocalDate,
    val originalAmount: Double,
    val payments: List<DebtCenterPayment>
) {
    val paid: Double get() = payments.sumOf { it.amount }
    val remaining: Double get() = (originalAmount - paid).coerceAtLeast(0.0)
    val paidInstallments: Int get() = payments.sumOf { it.installments.coerceAtLeast(1) }.coerceAtMost(installmentCount)
    val nextDueDate: LocalDate get() = firstDueDate.plusMonths(paidInstallments.toLong())
}

private fun normalizeDebtRecords(rows: List<DebtCenterRecord>): List<DebtCenterRecord> {
    return rows
        .map { debt ->
            val cleanName = canonicalDebtName(debt.name)
            val recoveredCreditor = debt.creditor.trim().ifBlank {
                debtCreditorFromDescription(debt.name).orEmpty()
            }
            debt.copy(name = cleanName.ifBlank { debt.name.trim() }, creditor = recoveredCreditor)
        }
        .groupBy { debt ->
            val nameKey = debt.name.trim().lowercase(Locale("pt", "BR"))
            val creditorKey = debt.creditor.trim().lowercase(Locale("pt", "BR"))
            "$nameKey|$creditorKey"
        }
        .values
        .map { group ->
            val preferred = group.firstOrNull { it.id > 0 } ?: group.first()
            val payments = group
                .flatMap { it.payments }
                .distinctBy { payment ->
                    "${payment.date}|${payment.amount}|${payment.accountId}|${payment.installments}"
                }
            preferred.copy(
                name = group.first().name,
                creditor = group.firstOrNull { it.creditor.isNotBlank() }?.creditor.orEmpty(),
                installmentAmount = group.map { it.installmentAmount }.firstOrNull { it > 0.0 } ?: preferred.installmentAmount,
                installmentCount = group.maxOf { it.installmentCount },
                firstDueDate = group.minOf { it.firstDueDate },
                originalAmount = group.maxOf { it.originalAmount },
                payments = payments
            )
        }
}

private fun recoverDebtRecordsFromTransactions(
    transactions: List<TransactionEntity>,
    existing: List<DebtCenterRecord>
): List<DebtCenterRecord> {
    val existingNames = existing.map { it.name.trim().lowercase(Locale("pt", "BR")) }.toSet()
    val rows = transactions.filter {
        isPlannedDebtTransaction(it) || it.description.startsWith("Pagamento de dívida •", ignoreCase = true)
    }.mapNotNull { tx ->
        debtNameFromDescription(tx.description)?.let { name -> name to tx }
    }.groupBy({ it.first }, { it.second })

    return rows.mapNotNull { (name, debtRows) ->
        if (name.trim().lowercase(Locale("pt", "BR")) in existingNames) return@mapNotNull null
        val totals = debtRows.mapNotNull(::debtInstallmentTotal)
        val numbers = debtRows.mapNotNull(::debtInstallmentNumber)
        val count = (totals + numbers).maxOrNull()?.coerceAtLeast(1) ?: debtRows.size.coerceAtLeast(1)
        val planned = debtRows.filter(::isPlannedDebtTransaction)
        val reference = (planned.ifEmpty { debtRows }).maxByOrNull { kotlin.math.abs(it.amount) } ?: return@mapNotNull null
        val installmentAmount = kotlin.math.abs(reference.amount).takeIf { it > 0.0 } ?: return@mapNotNull null
        val firstDueCandidates = debtRows.mapNotNull { tx ->
            val date = runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull() ?: return@mapNotNull null
            val number = debtInstallmentNumber(tx) ?: 1
            date.minusMonths((number - 1).coerceAtLeast(0).toLong())
        }
        val firstDue = firstDueCandidates.minOrNull() ?: LocalDate.now().plusMonths(1)
        val creditor = debtRows.mapNotNull { debtCreditorFromDescription(it.description) }.firstOrNull().orEmpty()
        val payments = debtRows.filter { it.description.startsWith("Pagamento de dívida •", ignoreCase = true) }.map { tx ->
            DebtCenterPayment(
                amount = kotlin.math.abs(tx.amount),
                date = runCatching { LocalDate.parse(tx.date.take(10)) }.getOrDefault(LocalDate.now()),
                accountId = tx.accountId,
                installments = 1
            )
        }
        val stableId = -kotlin.math.abs((name.lowercase(Locale("pt", "BR")) + "|" + firstDue).hashCode().toLong()).coerceAtLeast(1L)
        DebtCenterRecord(
            id = stableId,
            name = name,
            creditor = creditor,
            installmentAmount = installmentAmount,
            installmentCount = count,
            firstDueDate = firstDue,
            originalAmount = installmentAmount * count,
            payments = payments
        )
    }
}

private fun debtKey(userKey: String, workspaceId: String) = "financeapp_debts_v1_${userKey}_$workspaceId"
private fun deletedDebtKey(userKey: String, workspaceId: String) = "financeapp_deleted_debts_v1_${userKey}_$workspaceId"

private fun loadCenterDebts(context: Context, userKey: String, workspaceId: String): List<DebtCenterRecord> = try {
    val raw = context.getSharedPreferences("financeapp_debts", Context.MODE_PRIVATE)
        .getString(debtKey(userKey, workspaceId), "[]") ?: "[]"
    val arr = JSONArray(raw)
    buildList {
        repeat(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val installment = o.optDouble("installmentAmount", 0.0)
            val original = o.optDouble("originalAmount", 0.0)
            val count = o.optInt("installmentCount", 0).takeIf { it > 0 }
                ?: if (installment > 0) ceil(original / installment).toInt().coerceAtLeast(1) else 1
            val dueRaw = o.optString("firstDueDate", o.optString("dueDate", o.optString("nextDueDate", "")))
            val due = runCatching { LocalDate.parse(dueRaw.take(10)) }.getOrDefault(LocalDate.now().plusMonths(1))
            val pa = o.optJSONArray("payments") ?: JSONArray()
            val payments = buildList {
                repeat(pa.length()) { j ->
                    val p = pa.getJSONObject(j)
                    add(
                        DebtCenterPayment(
                            p.optDouble("amount", 0.0),
                            runCatching { LocalDate.parse(p.optString("date").take(10)) }.getOrDefault(LocalDate.now()),
                            if (p.has("accountId") && !p.isNull("accountId")) p.optInt("accountId") else null,
                            p.optInt("installments", 1).coerceAtLeast(1)
                        )
                    )
                }
            }
            add(
                DebtCenterRecord(
                    id = o.optLong("id", System.currentTimeMillis()),
                    name = o.optString("name", "Dívida"),
                    creditor = o.optString("creditor", ""),
                    installmentAmount = installment.takeIf { it > 0 } ?: original,
                    installmentCount = count,
                    firstDueDate = due,
                    originalAmount = original.takeIf { it > 0 } ?: installment * count,
                    payments = payments
                )
            )
        }
    }
} catch (_: Exception) { emptyList() }

private fun saveCenterDebts(context: Context, userKey: String, workspaceId: String, debts: List<DebtCenterRecord>) {
    val arr = JSONArray()
    debts.forEach { d ->
        val payments = JSONArray()
        d.payments.forEach { p ->
            payments.put(JSONObject().put("amount", p.amount).put("date", p.date.toString()).put("accountId", p.accountId).put("installments", p.installments))
        }
        arr.put(
            JSONObject()
                .put("id", d.id)
                .put("name", d.name)
                .put("creditor", d.creditor)
                .put("originalAmount", d.originalAmount)
                .put("installmentAmount", d.installmentAmount)
                .put("installmentCount", d.installmentCount)
                .put("firstDueDate", d.firstDueDate.toString())
                .put("dueDate", d.firstDueDate.toString())
                .put("nextDueDate", d.nextDueDate.toString())
                .put("payments", payments)
        )
    }
    context.getSharedPreferences("financeapp_debts", Context.MODE_PRIVATE)
        .edit().putString(debtKey(userKey, workspaceId), arr.toString()).apply()
}

private fun loadCenterDeleted(context: Context, userKey: String, workspaceId: String): Set<Long> =
    context.getSharedPreferences("financeapp_debts", Context.MODE_PRIVATE)
        .getStringSet(deletedDebtKey(userKey, workspaceId), emptySet())
        ?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()

private fun saveCenterDeleted(context: Context, userKey: String, workspaceId: String, ids: Set<Long>) {
    context.getSharedPreferences("financeapp_debts", Context.MODE_PRIVATE)
        .edit().putStringSet(deletedDebtKey(userKey, workspaceId), ids.map { it.toString() }.toSet()).apply()
}

private fun DebtCenterRecord.toPayload(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "creditor" to creditor,
    "originalAmount" to originalAmount,
    "installmentAmount" to installmentAmount,
    "installmentCount" to installmentCount,
    "firstDueDate" to firstDueDate.toString(),
    "dueDate" to firstDueDate.toString(),
    "nextDueDate" to nextDueDate.toString(),
    "payments" to payments.map { mapOf("amount" to it.amount, "date" to it.date.toString(), "accountId" to it.accountId, "installments" to it.installments) }
)

private fun debtFromPayload(raw: Map<*, *>): DebtCenterRecord? {
    val id = (raw["id"] as? Number)?.toLong() ?: raw["id"]?.toString()?.toLongOrNull() ?: return null
    val installment = (raw["installmentAmount"] as? Number)?.toDouble() ?: 0.0
    val original = (raw["originalAmount"] as? Number)?.toDouble() ?: 0.0
    val count = ((raw["installmentCount"] as? Number)?.toInt() ?: 0).takeIf { it > 0 }
        ?: if (installment > 0) ceil(original / installment).toInt().coerceAtLeast(1) else 1
    val dueText = (raw["firstDueDate"] ?: raw["dueDate"] ?: raw["nextDueDate"] ?: "").toString()
    val due = runCatching { LocalDate.parse(dueText.take(10)) }.getOrDefault(LocalDate.now().plusMonths(1))
    val payments = (raw["payments"] as? List<*>)?.mapNotNull { pRaw ->
        val p = pRaw as? Map<*, *> ?: return@mapNotNull null
        DebtCenterPayment(
            (p["amount"] as? Number)?.toDouble() ?: 0.0,
            runCatching { LocalDate.parse((p["date"] ?: "").toString().take(10)) }.getOrDefault(LocalDate.now()),
            (p["accountId"] as? Number)?.toInt(),
            ((p["installments"] as? Number)?.toInt() ?: 1).coerceAtLeast(1)
        )
    } ?: emptyList()
    return DebtCenterRecord(id, (raw["name"] ?: "Dívida").toString(), (raw["creditor"] ?: "").toString(), installment, count, due, original.takeIf { it > 0 } ?: installment * count, payments)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DebtCenterFlow(
    visible: Boolean,
    openNewDirectly: Boolean,
    userKey: String,
    workspaceId: String,
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onNewConsumed: () -> Unit,
    onLoadForecastState: suspend () -> Map<String, Any?>,
    onSaveForecastState: suspend (Map<String, Any?>) -> Map<String, Any?>,
    onCreateManual: (Int?, Int?, String, Double, String, String, Int?, Int, String?, (Boolean) -> Unit) -> Unit,
    onUpdateManualTransaction: (Int, Int?, Int?, String, Double, String, String) -> Unit,
    onDeleteTransaction: (TransactionEntity) -> Unit
) {
    if (!visible && !openNewDirectly) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var debts by remember(userKey, workspaceId) { mutableStateOf(loadCenterDebts(context, userKey, workspaceId)) }
    var deletedIds by remember(userKey, workspaceId) { mutableStateOf(loadCenterDeleted(context, userKey, workspaceId)) }
    var showNew by remember(openNewDirectly) { mutableStateOf(openNewDirectly) }
    var editing by remember { mutableStateOf<DebtCenterRecord?>(null) }
    var paying by remember { mutableStateOf<DebtCenterRecord?>(null) }
    val money = remember { NumberFormat.getCurrencyInstance(Locale("pt", "BR")) }
    val df = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy") }

    fun persist(next: List<DebtCenterRecord>, deleted: Set<Long> = deletedIds) {
        debts = next
        deletedIds = deleted
        saveCenterDebts(context, userKey, workspaceId, next)
        saveCenterDeleted(context, userKey, workspaceId, deleted)
        scope.launch {
            runCatching {
                val current = onLoadForecastState().toMutableMap()
                current["debts"] = next.map { it.toPayload() }
                current["deletedDebtIds"] = deleted.toList()
                onSaveForecastState(current)
            }
        }
    }

    LaunchedEffect(userKey, workspaceId, transactions) {
        runCatching {
            val remote = onLoadForecastState()
            val remoteDeleted = (remote["deletedDebtIds"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() ?: it?.toString()?.toLongOrNull() }?.toSet() ?: emptySet()
            val allDeleted = deletedIds + remoteDeleted
            val remoteDebts = (remote["debts"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(::debtFromPayload) } ?: emptyList()
            val rawBase = (debts + remoteDebts).associateBy { it.id }.values.filterNot { it.id in allDeleted }.toList()
            // Versao 43.0.78 podia interpretar cada parcela como uma divida diferente.
            // Normalizamos nomes antigos ("Nome • Credor: X • Parcela N/T") e consolidamos
            // tudo antes de tentar recuperar qualquer registro ausente.
            val base = normalizeDebtRecords(rawBase)
            val recovered = recoverDebtRecordsFromTransactions(transactions, base)
            val merged = normalizeDebtRecords(base + recovered).sortedByDescending { it.id }
            debts = merged
            deletedIds = allDeleted
            saveCenterDebts(context, userKey, workspaceId, merged)
            saveCenterDeleted(context, userKey, workspaceId, allDeleted)
            val repairedExisting = rawBase.size != base.size || rawBase.any { canonicalDebtName(it.name) != it.name.trim() }
            if (recovered.isNotEmpty() || repairedExisting) {
                val current = remote.toMutableMap()
                current["debts"] = merged.map { it.toPayload() }
                current["deletedDebtIds"] = allDeleted.toList()
                onSaveForecastState(current)
            }
        }
    }

    if (visible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Minhas dívidas") },
            text = {
                Box(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .heightIn(max = 620.dp)
                ) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Button(onClick = { showNew = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Adicionar dívida")
                            }
                        }
                        if (debts.isEmpty()) item {
                            Text("Nenhuma dívida cadastrada. Use “Adicionar dívida” para começar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(debts, key = { it.id }) { d ->
                            val progress = if (d.originalAmount <= 0) 0f else (d.paid / d.originalAmount).coerceIn(0.0, 1.0).toFloat()
                            val status = when {
                                d.remaining <= 0.009 -> "Quitada"
                                d.nextDueDate.isBefore(LocalDate.now()) -> "Atrasada"
                                else -> "Em dia"
                            }
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(d.name, fontWeight = FontWeight.Bold)
                                            if (d.creditor.isNotBlank()) Text(d.creditor, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text(status, fontWeight = FontWeight.Bold, color = if (status == "Atrasada") MaterialTheme.colorScheme.error else if (status == "Quitada") Color(0xFF16845B) else MaterialTheme.colorScheme.primary)
                                    }
                                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                                    Text("${d.paidInstallments}/${d.installmentCount} parcelas • ${money.format(d.installmentAmount)} cada")
                                    Text("Pago: ${money.format(d.paid)} • Restante: ${money.format(d.remaining)}", fontWeight = FontWeight.SemiBold)
                                    if (d.remaining > 0.009) Text("Próxima: ${d.nextDueDate.format(df)}")
                                    if (d.payments.isNotEmpty()) {
                                        HorizontalDivider()
                                        d.payments.takeLast(4).reversed().forEach { p ->
                                            val bank = accounts.firstOrNull { it.id == p.accountId }?.institutionName ?: "Banco não informado"
                                            Text("${p.date.format(df)} • ${money.format(p.amount)} • ${p.installments} parcela(s) • $bank", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            TextButton(onClick = { editing = d }) {
                                                Icon(Icons.Default.Edit, null)
                                                Spacer(Modifier.width(4.dp))
                                                Text("Editar")
                                            }
                                            TextButton(onClick = {
                                                val del = deletedIds + d.id
                                                persist(debts.filterNot { it.id == d.id }, del)
                                            }) {
                                                Icon(Icons.Default.Delete, null)
                                                Spacer(Modifier.width(4.dp))
                                                Text("Excluir")
                                            }
                                        }
                                        if (d.remaining > 0.009) {
                                            FilledTonalButton(
                                                onClick = { paying = d },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.Payments, null)
                                                Spacer(Modifier.width(6.dp))
                                                Text("Registrar pagamento", maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
        )
    }

    if (showNew || editing != null) {
        val existing = editing
        val dialogKey = existing?.id ?: -1L
        val paidAmount = existing?.paid ?: 0.0
        val paidInstallments = existing?.paidInstallments ?: 0
        var name by remember(dialogKey) { mutableStateOf(existing?.name.orEmpty()) }
        var creditor by remember(dialogKey) { mutableStateOf(existing?.creditor.orEmpty()) }
        var calculateFromTotal by remember(dialogKey) { mutableStateOf(existing != null) }
        var totalDigits by remember(dialogKey) {
            mutableStateOf(existing?.originalAmount?.let { (it * 100).toLong().toString() }.orEmpty())
        }
        var installmentDigits by remember(dialogKey) {
            mutableStateOf(existing?.installmentAmount?.let { (it * 100).toLong().toString() }.orEmpty())
        }
        var countText by remember(dialogKey) { mutableStateOf((existing?.installmentCount ?: 12).toString()) }
        var dueInput by remember(dialogKey) {
            mutableStateOf(existing?.nextDueDate ?: LocalDate.now().plusMonths(1))
        }
        var pickDate by remember(dialogKey) { mutableStateOf(false) }

        val requestedCount = countText.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 360) ?: 0
        val minCount = if (existing == null) 1 else paidInstallments + if (existing.remaining > 0.009) 1 else 0
        val count = requestedCount.coerceAtLeast(0)
        val remainingCount = (count - paidInstallments).coerceAtLeast(0)
        val typedTotal = BrlMoney.toDouble(totalDigits)
        val typedInstallment = BrlMoney.toDouble(installmentDigits)
        val total = if (calculateFromTotal) typedTotal else paidAmount + typedInstallment * remainingCount
        val installment = if (calculateFromTotal) {
            if (remainingCount > 0) ((total - paidAmount).coerceAtLeast(0.0) / remainingCount) else 0.0
        } else typedInstallment
        val firstDue = if (existing == null) dueInput else dueInput.minusMonths(paidInstallments.toLong())
        val valid = name.isNotBlank() && count >= minCount && total + 0.009 >= paidAmount &&
            ((remainingCount == 0 && kotlin.math.abs(total - paidAmount) < 0.01) || (remainingCount > 0 && installment > 0.0))

        AlertDialog(
            onDismissRequest = {
                showNew = false
                editing = null
                onNewConsumed()
            },
            title = { Text(if (existing == null) "Adicionar dívida" else "Editar dívida") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nome da dívida *") }, singleLine = true)
                    OutlinedTextField(creditor, { creditor = it }, label = { Text("Credor") }, singleLine = true)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = calculateFromTotal, onCheckedChange = { calculateFromTotal = it })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Informar valor total", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (calculateFromTotal) "O valor da parcela é calculado automaticamente" else "O total é calculado pelo valor e quantidade de parcelas",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (calculateFromTotal) {
                        OutlinedTextField(
                            totalDigits,
                            { totalDigits = BrlMoney.digits(it) },
                            label = { Text("Valor total da dívida *") },
                            visualTransformation = BrlMoneyVisualTransformation,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    } else {
                        OutlinedTextField(
                            installmentDigits,
                            { installmentDigits = BrlMoney.digits(it) },
                            label = { Text("Valor da parcela *") },
                            visualTransformation = BrlMoneyVisualTransformation,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }

                    OutlinedTextField(
                        countText,
                        { countText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Quantidade de parcelas *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )

                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Resumo do acordo", style = MaterialTheme.typography.labelMedium)
                            Text("Total: ${money.format(total)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (remainingCount > 0) {
                                Text("Parcelas restantes: $remainingCount × ${money.format(installment)}")
                            }
                            if (paidAmount > 0) Text("Já pago: ${money.format(paidAmount)}")
                        }
                    }

                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${if (existing == null) "Primeiro vencimento" else "Próximo vencimento"}: ${dueInput.format(df)}")
                    }
                    if (existing != null && existing.payments.isNotEmpty()) {
                        Text("Os pagamentos já registrados serão preservados.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Button(enabled = valid, onClick = {
                    val debt = if (existing == null) {
                        DebtCenterRecord(System.currentTimeMillis(), name.trim(), creditor.trim(), installment, count, firstDue, total, emptyList())
                    } else {
                        existing.copy(
                            name = name.trim(),
                            creditor = creditor.trim(),
                            installmentAmount = installment,
                            installmentCount = count,
                            firstDueDate = firstDue,
                            originalAmount = total
                        )
                    }
                    if (existing == null) {
                        persist(listOf(debt) + debts)
                        onCreateManual(
                            null, null, "$PLANNED_DEBT_PREFIX${debt.name}${if (debt.creditor.isNotBlank()) " • Credor: ${debt.creditor}" else ""}", -total, "debit", firstDue.toString(), null, count, firstDue.toString()
                        ) { }
                    } else {
                        persist(debts.map { if (it.id == existing.id) debt else it })
                        val oldPlans = transactions.filter {
                            isPlannedDebtTransaction(it) &&
                                it.description.startsWith("$PLANNED_DEBT_PREFIX${existing.name}", ignoreCase = true)
                        }
                        oldPlans.forEach { tx ->
                            val number = tx.installmentNumber ?: Regex("Parcela\\s+(\\d+)/", RegexOption.IGNORE_CASE)
                                .find(tx.description)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            if (number != null && number > debt.installmentCount) onDeleteTransaction(tx)
                            else if (number != null && number > debt.paidInstallments) {
                                val date = debt.firstDueDate.plusMonths((number - 1).toLong()).toString()
                                onUpdateManualTransaction(
                                    tx.id, tx.accountId, tx.categoryId,
                                    "$PLANNED_DEBT_PREFIX${debt.name}${if (debt.creditor.isNotBlank()) " • Credor: ${debt.creditor}" else ""} • Parcela $number/${debt.installmentCount}",
                                    -debt.installmentAmount, "debit", date
                                )
                            }
                        }
                        val existingNumbers = oldPlans.mapNotNull { tx ->
                            tx.installmentNumber ?: Regex("Parcela\\s+(\\d+)/", RegexOption.IGNORE_CASE)
                                .find(tx.description)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        }.toSet()
                        for (number in (debt.paidInstallments + 1)..debt.installmentCount) {
                            if (number !in existingNumbers) {
                                val date = debt.firstDueDate.plusMonths((number - 1).toLong()).toString()
                                onCreateManual(
                                    null, null,
                                    "$PLANNED_DEBT_PREFIX${debt.name}${if (debt.creditor.isNotBlank()) " • Credor: ${debt.creditor}" else ""} • Parcela $number/${debt.installmentCount}",
                                    -debt.installmentAmount, "debit", date, null, 1, date
                                ) { }
                            }
                        }
                    }
                    showNew = false
                    editing = null
                    onNewConsumed()
                }) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNew = false
                    editing = null
                    onNewConsumed()
                }) { Text("Cancelar") }
            }
        )
        if (pickDate) {
            val state = rememberDatePickerState(initialSelectedDateMillis = dueInput.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { pickDate = false },
                confirmButton = { TextButton(onClick = {
                    state.selectedDateMillis?.let { dueInput = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancelar") } }
            ) { DatePicker(state = state) }
        }
    }

    paying?.let { debt ->
        val remainingInstallments = (debt.installmentCount - debt.paidInstallments).coerceAtLeast(1)
        var installmentsToPay by remember(debt.id) { mutableStateOf(1) }
        var accountId by remember(debt.id) { mutableStateOf<Int?>(null) }
        var payDate by remember(debt.id) { mutableStateOf(LocalDate.now()) }
        var pickDate by remember(debt.id) { mutableStateOf(false) }
        var accountMenu by remember(debt.id) { mutableStateOf(false) }
        val firstInstallment = debt.paidInstallments + 1
        val lastInstallment = (firstInstallment + installmentsToPay - 1).coerceAtMost(debt.installmentCount)
        val installmentsActuallyPaid = (lastInstallment - firstInstallment + 1).coerceAtLeast(1)
        val paymentValue = (debt.installmentAmount * installmentsActuallyPaid).coerceAtMost(debt.remaining)
        AlertDialog(
            onDismissRequest = { paying = null },
            title = { Text("Registrar pagamento") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(debt.name, fontWeight = FontWeight.Bold)
                    if (debt.creditor.isNotBlank()) Text("Credor: ${debt.creditor}", style = MaterialTheme.typography.bodyMedium)
                    if (remainingInstallments > 1) {
                        Text("Quantidade de parcelas: $installmentsToPay", fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = installmentsToPay.toFloat(),
                            onValueChange = { installmentsToPay = it.toInt().coerceIn(1, remainingInstallments) },
                            valueRange = 1f..remainingInstallments.toFloat(),
                            steps = (remainingInstallments - 2).coerceAtLeast(0)
                        )
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (firstInstallment == lastInstallment) {
                                    "Pagando parcela $firstInstallment/${debt.installmentCount}"
                                } else {
                                    "Pagando parcelas $firstInstallment a $lastInstallment de ${debt.installmentCount}"
                                },
                                fontWeight = FontWeight.Bold
                            )
                            Text("Total do pagamento: ${money.format(paymentValue)}")
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { accountMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(accounts.firstOrNull { it.id == accountId }?.institutionName ?: "Banco/conta do pagamento *", modifier = Modifier.weight(1f))
                        }
                        DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                            accounts.forEach { a -> DropdownMenuItem(text = { Text(a.institutionName) }, onClick = { accountId = a.id; accountMenu = false }) }
                        }
                    }
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) { Text("Data: ${payDate.format(df)}") }
                    Text("As parcelas selecionadas deixam de ser previstas e passam a ser saídas reais no banco escolhido.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(enabled = paymentValue > 0 && accountId != null, onClick = {
                    val payment = DebtCenterPayment(paymentValue, payDate, accountId, installmentsActuallyPaid)
                    val updated = debt.copy(payments = debt.payments + payment)
                    persist(debts.map { if (it.id == debt.id) updated else it })
                    for (installmentNumber in firstInstallment..lastInstallment) {
                        val planned = transactions
                            .filter { isPlannedDebtTransaction(it) && it.description.startsWith("$PLANNED_DEBT_PREFIX${debt.name}", ignoreCase = true) }
                            .firstOrNull {
                                it.installmentNumber == installmentNumber ||
                                    Regex("Parcela\\s+$installmentNumber/", RegexOption.IGNORE_CASE).containsMatchIn(it.description)
                            }
                        val installmentValue = if (installmentNumber == lastInstallment) {
                            (paymentValue - debt.installmentAmount * (installmentsActuallyPaid - 1)).coerceAtLeast(0.0)
                        } else debt.installmentAmount
                        if (planned != null) {
                            onUpdateManualTransaction(
                                planned.id, accountId, planned.categoryId,
                                "Pagamento de dívida • ${debt.name}${if (debt.creditor.isNotBlank()) " • Credor: ${debt.creditor}" else ""} • Parcela $installmentNumber/${debt.installmentCount}",
                                -installmentValue, "debit", payDate.toString()
                            )
                        } else {
                            onCreateManual(
                                accountId, null,
                                "Pagamento de dívida • ${debt.name}${if (debt.creditor.isNotBlank()) " • Credor: ${debt.creditor}" else ""} • Parcela $installmentNumber/${debt.installmentCount}",
                                -installmentValue, "debit", payDate.toString(), null, 1, null
                            ) { }
                        }
                    }
                    paying = null
                }) { Text("Registrar") }
            },
            dismissButton = { TextButton(onClick = { paying = null }) { Text("Cancelar") } }
        )
        if (pickDate) {
            val state = rememberDatePickerState(initialSelectedDateMillis = payDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { payDate = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() }; pickDate = false }) { Text("OK") } }, dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancelar") } }) { DatePicker(state = state) }
        }
    }
}
