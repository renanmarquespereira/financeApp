package com.financeapp.mobile.ui.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financeapp.mobile.data.local.AccountEntity
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.CreditCardDto
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

private data class ForecastMovement(val date: LocalDate, val label: String, val amount: Double, val kind: String)
private data class ForecastMonth(val month: YearMonth, val income: Double, val expenses: Double, val endingBalance: Double)
private data class Simulation(val description: String, val amount: Double, val installments: Int, val firstDate: LocalDate, val isIncome: Boolean = false, val recurring: Boolean = false)
private data class SavedForecastScenario(val id: Long, val name: String, val horizon: Int, val items: List<Simulation>, val createdAt: String)

private data class DebtPayment(val amount: Double, val date: LocalDate, val accountId: Int?)
private data class DebtRecord(val id: Long, val name: String, val creditor: String, val originalAmount: Double, val nextDueDate: LocalDate, val installmentAmount: Double, val payments: List<DebtPayment>) {
    val paid: Double get() = payments.sumOf { it.amount }
    val remaining: Double get() = (originalAmount - paid).coerceAtLeast(0.0)
}
private fun debtKey(userKey: String, workspaceId: String) = "financeapp_debts_v1_${userKey}_$workspaceId"
private fun loadDebts(context: Context, userKey: String, workspaceId: String): List<DebtRecord> = try {
    val raw=context.getSharedPreferences("financeapp_debts",Context.MODE_PRIVATE).getString(debtKey(userKey,workspaceId),"[]")?:"[]"
    val a=JSONArray(raw); buildList { repeat(a.length()){i-> val o=a.getJSONObject(i); val pa=o.optJSONArray("payments")?:JSONArray(); val payments=buildList{repeat(pa.length()){j->val x=pa.getJSONObject(j);add(DebtPayment(x.optDouble("amount",0.0),runCatching{LocalDate.parse(x.optString("date"))}.getOrDefault(LocalDate.now()),if(x.has("accountId")&&!x.isNull("accountId"))x.optInt("accountId") else null))}}; add(DebtRecord(o.optLong("id",System.currentTimeMillis()),o.optString("name","Dívida"),o.optString("creditor",""),o.optDouble("originalAmount",0.0),runCatching{LocalDate.parse(o.optString("nextDueDate"))}.getOrDefault(LocalDate.now().plusDays(30)),o.optDouble("installmentAmount",0.0),payments)) } }
} catch(_:Exception){ emptyList() }
private fun saveDebts(context: Context,userKey:String,workspaceId:String,debts:List<DebtRecord>){ val a=JSONArray();debts.forEach{d->val pa=JSONArray();d.payments.forEach{p->pa.put(JSONObject().put("amount",p.amount).put("date",p.date.toString()).put("accountId",p.accountId))};a.put(JSONObject().put("id",d.id).put("name",d.name).put("creditor",d.creditor).put("originalAmount",d.originalAmount).put("nextDueDate",d.nextDueDate.toString()).put("installmentAmount",d.installmentAmount).put("payments",pa))};context.getSharedPreferences("financeapp_debts",Context.MODE_PRIVATE).edit().putString(debtKey(userKey,workspaceId),a.toString()).apply() }
private fun deletedDebtKey(userKey:String,workspaceId:String)="financeapp_deleted_debts_v1_${userKey}_$workspaceId"
private fun loadDeletedDebtIds(context:Context,userKey:String,workspaceId:String):Set<Long> = context.getSharedPreferences("financeapp_debts",Context.MODE_PRIVATE).getStringSet(deletedDebtKey(userKey,workspaceId), emptySet())?.mapNotNull{it.toLongOrNull()}?.toSet()?: emptySet()
private fun saveDeletedDebtIds(context:Context,userKey:String,workspaceId:String,ids:Set<Long>){context.getSharedPreferences("financeapp_debts",Context.MODE_PRIVATE).edit().putStringSet(deletedDebtKey(userKey,workspaceId),ids.map{it.toString()}.toSet()).apply()}

private fun forecastDate(raw: String?): LocalDate? = try { raw?.take(10)?.let(LocalDate::parse) } catch (_: Exception) { null }
private fun forecastCashFlow(tx: TransactionEntity) = tx.source != "card_purchase" && !(tx.cardId != null && tx.source != "card_payment")
private fun isIncomeType(raw: String): Boolean = raw.lowercase(Locale.ROOT) in setOf("income", "credit", "entrada")
private fun signedCashAmount(tx: TransactionEntity): Double = if (isIncomeType(tx.transactionType)) abs(tx.amount) else -abs(tx.amount)
private fun simulationMoney(raw: String): Double = raw.filter { it.isDigit() }.toLongOrNull()?.div(100.0) ?: 0.0

private fun scenarioKey(userKey: String, workspaceName: String) = "forecast_scenarios_${userKey}_${workspaceName}"
private fun loadSavedScenarios(context: Context, userKey: String, workspaceName: String): List<SavedForecastScenario> = try {
    val raw = context.getSharedPreferences("forecast_scenarios", Context.MODE_PRIVATE).getString(scenarioKey(userKey, workspaceName), "[]") ?: "[]"
    val array = JSONArray(raw)
    buildList {
        repeat(array.length()) { index ->
            val obj = array.getJSONObject(index)
            val itemsArray = obj.optJSONArray("items") ?: JSONArray()
            val scenarioItems = buildList {
                repeat(itemsArray.length()) { i ->
                    val item = itemsArray.getJSONObject(i)
                    add(Simulation(
                        description = item.optString("description", "Item"),
                        amount = item.optDouble("amount", 0.0),
                        installments = item.optInt("installments", 1).coerceAtLeast(1),
                        firstDate = runCatching { LocalDate.parse(item.optString("firstDate")) }.getOrDefault(LocalDate.now().plusDays(1)),
                        isIncome = item.optBoolean("isIncome", false),
                        recurring = item.optBoolean("recurring", false)
                    ))
                }
            }
            add(SavedForecastScenario(
                id = obj.optLong("id", System.currentTimeMillis()),
                name = obj.optString("name", "Cenário"),
                horizon = obj.optInt("horizon", 90),
                items = scenarioItems,
                createdAt = obj.optString("createdAt", "")
            ))
        }
    }
} catch (_: Exception) { emptyList() }

private fun saveSavedScenarios(context: Context, userKey: String, workspaceName: String, scenarios: List<SavedForecastScenario>) {
    val array = JSONArray()
    scenarios.forEach { saved ->
        val items = JSONArray()
        saved.items.forEach { item ->
            items.put(JSONObject()
                .put("description", item.description)
                .put("amount", item.amount)
                .put("installments", item.installments)
                .put("firstDate", item.firstDate.toString())
                .put("isIncome", item.isIncome)
                .put("recurring", item.recurring))
        }
        array.put(JSONObject()
            .put("id", saved.id)
            .put("name", saved.name)
            .put("horizon", saved.horizon)
            .put("createdAt", saved.createdAt)
            .put("items", items))
    }
    context.getSharedPreferences("forecast_scenarios", Context.MODE_PRIVATE).edit().putString(scenarioKey(userKey, workspaceName), array.toString()).apply()
}

private fun deletedScenarioKey(userKey: String, workspaceName: String) = "forecast_deleted_scenarios_${userKey}_${workspaceName}"
private fun loadDeletedScenarioIds(context: Context, userKey: String, workspaceName: String): Set<Long> =
    context.getSharedPreferences("forecast_scenarios", Context.MODE_PRIVATE)
        .getStringSet(deletedScenarioKey(userKey, workspaceName), emptySet())
        ?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
private fun saveDeletedScenarioIds(context: Context, userKey: String, workspaceName: String, ids: Set<Long>) {
    context.getSharedPreferences("forecast_scenarios", Context.MODE_PRIVATE).edit()
        .putStringSet(deletedScenarioKey(userKey, workspaceName), ids.map { it.toString() }.toSet()).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinancialForecastScreen(
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
    cards: List<CreditCardDto>,
    userKey: String,
    workspaceName: String,
    workspaceId: String,
    syncRevision: String? = null,
    onLoadForecastState: suspend () -> Map<String, Any?> = { emptyMap() },
    onSaveForecastState: suspend (Map<String, Any?>) -> Map<String, Any?> = { emptyMap() },
    onTransformSimulation: (String, Double, Boolean, LocalDate, Int, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onRecordDebtPayment: (String, Double, LocalDate, Int?) -> Unit = { _, _, _, _ -> }
) {
    val br = remember { Locale("pt", "BR") }
    val money = remember { NumberFormat.getCurrencyInstance(br) }
    val today = LocalDate.now()
    val context = androidx.compose.ui.platform.LocalContext.current
    var horizon by remember { mutableIntStateOf(90) }
    val end = today.plusDays(horizon.toLong())
    var simulations by remember { mutableStateOf(emptyList<Simulation>()) }
    var showSimulation by remember { mutableStateOf(false) }
    var keepAdding by remember { mutableStateOf(false) }
    var showSaveScenario by remember { mutableStateOf(false) }
    var debts by remember(userKey, workspaceId) { mutableStateOf(loadDebts(context, userKey, workspaceId)) }
    var showDebtDialog by remember { mutableStateOf(false) }
    var debtToPay by remember { mutableStateOf<DebtRecord?>(null) }
    var deletedDebtIds by remember(userKey, workspaceId) { mutableStateOf(loadDeletedDebtIds(context,userKey,workspaceId)) }
    remember(userKey, workspaceId) {
        val prefs=context.getSharedPreferences("forecast_scenarios",Context.MODE_PRIVATE)
        val stable=scenarioKey(userKey,workspaceId)
        if(!prefs.contains(stable)) {
            val legacy=prefs.getString(scenarioKey(userKey,workspaceName),"[]")?:"[]"
            prefs.edit().putString(stable,legacy).commit()
            saveDeletedScenarioIds(context,userKey,workspaceId,loadDeletedScenarioIds(context,userKey,workspaceName))
        }
        true
    }
    var savedScenarios by remember(userKey, workspaceId) { mutableStateOf(loadSavedScenarios(context, userKey, workspaceId)) }
    var deletedScenarioIds by remember(userKey, workspaceId) { mutableStateOf(loadDeletedScenarioIds(context, userKey, workspaceId)) }
    var scenarioToDelete by remember { mutableStateOf<SavedForecastScenario?>(null) }
    var scenarioMenuId by remember { mutableStateOf<Long?>(null) }
    val syncScope = rememberCoroutineScope()
    fun scenarioPayload(): List<Map<String, Any?>> = savedScenarios.map { saved ->
        mapOf("id" to saved.id, "name" to saved.name, "horizon" to saved.horizon, "createdAt" to saved.createdAt,
            "items" to saved.items.map { item -> mapOf("description" to item.description,"amount" to item.amount,"installments" to item.installments,"firstDate" to item.firstDate.toString(),"isIncome" to item.isIncome,"recurring" to item.recurring) })
    }
    fun parseRemoteScenarios(payload: Map<*, *>): List<SavedForecastScenario> = (payload["scenarios"] as? List<*>)?.mapNotNull { raw ->
        val obj=raw as? Map<*,*> ?: return@mapNotNull null
        val items=(obj["items"] as? List<*>)?.mapNotNull { ir -> val im=ir as? Map<*,*> ?: return@mapNotNull null; Simulation((im["description"]?:"Item").toString(),(im["amount"] as? Number)?.toDouble()?:0.0,(im["installments"] as? Number)?.toInt()?.coerceAtLeast(1)?:1,runCatching{LocalDate.parse((im["firstDate"]?:"").toString().take(10))}.getOrDefault(LocalDate.now().plusDays(1)),im["isIncome"]==true,im["recurring"]==true) } ?: emptyList()
        SavedForecastScenario((obj["id"] as? Number)?.toLong()?:obj["id"]?.toString()?.toLongOrNull()?:System.currentTimeMillis(),(obj["name"]?:"Cenário").toString(),(obj["horizon"] as? Number)?.toInt()?:90,items,(obj["createdAt"]?:"").toString())
    } ?: emptyList()
    fun parseDeleted(payload: Map<*, *>): Set<Long> = (payload["deletedScenarioIds"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() ?: it?.toString()?.toLongOrNull() }?.toSet() ?: emptySet()
    fun debtPayload(): List<Map<String,Any?>> = debts.map { d -> mapOf("id" to d.id,"name" to d.name,"creditor" to d.creditor,"originalAmount" to d.originalAmount,"nextDueDate" to d.nextDueDate.toString(),"installmentAmount" to d.installmentAmount,"payments" to d.payments.map{p->mapOf("amount" to p.amount,"date" to p.date.toString(),"accountId" to p.accountId)}) }
    fun parseRemoteDebts(payload:Map<*,*>):List<DebtRecord> = (payload["debts"] as? List<*>)?.mapNotNull{raw-> val o=raw as? Map<*,*> ?: return@mapNotNull null; val ps=(o["payments"] as? List<*>)?.mapNotNull{pr->val pm=pr as? Map<*,*>?:return@mapNotNull null;DebtPayment((pm["amount"] as? Number)?.toDouble()?:0.0,runCatching{LocalDate.parse((pm["date"]?:"").toString().take(10))}.getOrDefault(LocalDate.now()),(pm["accountId"] as? Number)?.toInt())}?:emptyList();DebtRecord((o["id"] as? Number)?.toLong()?:o["id"]?.toString()?.toLongOrNull()?:return@mapNotNull null,(o["name"]?:"Dívida").toString(),(o["creditor"]?:"").toString(),(o["originalAmount"] as? Number)?.toDouble()?:0.0,runCatching{LocalDate.parse((o["nextDueDate"]?:"").toString().take(10))}.getOrDefault(LocalDate.now().plusDays(30)),(o["installmentAmount"] as? Number)?.toDouble()?:0.0,ps)}?:emptyList()
    fun parseDeletedDebts(payload: Map<*, *>): Set<Long> = (payload["deletedDebtIds"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() ?: it?.toString()?.toLongOrNull() }?.toSet() ?: emptySet()
    var forecastSyncError by remember { mutableStateOf<String?>(null) }
    suspend fun syncForecastStateNow() {
        try {
            val response=onSaveForecastState(mapOf("scenarios" to scenarioPayload(),"deletedScenarioIds" to deletedScenarioIds.toList(),"debts" to debtPayload(),"deletedDebtIds" to deletedDebtIds.toList()))
            val remote=response["payload"] as? Map<*,*> ?: emptyMap<Any?,Any?>()
            // Re-read live Compose state after I/O: edits made during the request survive.
            deletedScenarioIds=deletedScenarioIds+parseDeleted(remote)
            savedScenarios=(savedScenarios+parseRemoteScenarios(remote)).associateBy{it.id}.values.filterNot{it.id in deletedScenarioIds}.sortedByDescending{it.id}
            saveDeletedScenarioIds(context,userKey,workspaceId,deletedScenarioIds)
            saveSavedScenarios(context,userKey,workspaceId,savedScenarios)
            deletedDebtIds=deletedDebtIds+parseDeletedDebts(remote)
            debts=(debts+parseRemoteDebts(remote)).associateBy{it.id}.values.filterNot{it.id in deletedDebtIds}.sortedByDescending{it.id}
            saveDeletedDebtIds(context,userKey,workspaceId,deletedDebtIds)
            saveDebts(context,userKey,workspaceId,debts)
            forecastSyncError=null
        } catch(e:kotlinx.coroutines.CancellationException){throw e} catch(e:Exception){forecastSyncError="Salvo neste aparelho. Sincronização pendente: ${e.message ?: "sem conexão"}"}
    }
    fun syncForecastState() { syncScope.launch { syncForecastStateNow() } }
    LaunchedEffect(userKey, workspaceId) { while(true){syncForecastStateNow();kotlinx.coroutines.delay(30000)} }
    LaunchedEffect(syncRevision) { syncForecastStateNow() }

    // Regra única para Android/Web/iOS: saldo da previsão vem do fluxo de caixa registrado.
    // Compras no cartão ficam fora até a fatura/pagamento entrar no fluxo.
    val currentBalance = remember(transactions, today) {
        transactions
            .filter { forecastCashFlow(it) && (forecastDate(it.date)?.isAfter(today) != true) }
            .sumOf(::signedCashAmount)
    }


    val realMovements = remember(transactions, cards, horizon, today) {
        val result = mutableListOf<ForecastMovement>()
        transactions.forEach { tx ->
            val d = forecastDate(tx.date) ?: return@forEach
            if (!d.isAfter(today) || d.isAfter(end)) return@forEach
            if (forecastCashFlow(tx)) {
                val signed = signedCashAmount(tx)
                result += ForecastMovement(d, tx.description, signed, if (signed >= 0) "Entrada" else "Saída")
            } else if (tx.source == "card_purchase" || (tx.cardId != null && tx.source != "card_payment")) {
                val card = cards.firstOrNull { it.id == tx.cardId }
                val due = card?.dueDay?.coerceIn(1, 28)
                if (due != null) {
                    var payDate = LocalDate.of(d.year, d.month, due)
                    if (payDate.isBefore(d)) payDate = payDate.plusMonths(1)
                    if (payDate.isAfter(today) && !payDate.isAfter(end)) {
                        val cardName = card.nickname?.takeIf { it.isNotBlank() } ?: "Cartão •••• ${card.lastFour}"
                        result += ForecastMovement(payDate, "Fatura prevista • $cardName", -abs(tx.amount), "Cartão")
                    }
                }
            }
        }
        result.sortedBy { it.date }
    }

    val simulatedMovements = remember(simulations, horizon, today) {
        simulations.flatMap { sim ->
            val count = sim.installments.coerceAtLeast(1)
            val part = if (sim.isIncome) sim.amount else sim.amount / count
            (0 until count).mapNotNull { i ->
                val d = sim.firstDate.plusMonths(i.toLong())
                if (!d.isAfter(today) || d.isAfter(end)) null else {
                    val signed = if (sim.isIncome) part else -part
                    ForecastMovement(d, "Simulação • ${sim.description}", signed, if (sim.isIncome) "Entrada simulada" else "Despesa simulada")
                }
            }
        }
    }
    val movements = (realMovements + simulatedMovements).sortedBy { it.date }
    val baseMovements = realMovements
    val realIncome = baseMovements.filter { it.amount > 0 }.sumOf { it.amount }
    val realExpenses = abs(baseMovements.filter { it.amount < 0 }.sumOf { it.amount })
    val realProjected = currentBalance + realIncome - realExpenses
    val income = movements.filter { it.amount > 0 }.sumOf { it.amount }
    val expenses = abs(movements.filter { it.amount < 0 }.sumOf { it.amount })
    val projected = currentBalance + income - expenses

    var runningRisk = currentBalance
    var minimumBalance = currentBalance
    var minimumDate = today
    var firstNegative: LocalDate? = null
    movements.forEach { mv ->
        runningRisk += mv.amount
        if (runningRisk < minimumBalance) { minimumBalance = runningRisk; minimumDate = mv.date }
        if (runningRisk < 0 && firstNegative == null) firstNegative = mv.date
    }
    val riskLabel = when { firstNegative != null -> "Saldo negativo previsto"; projected < 0 -> "Atenção"; else -> "Tranquilo" }

    val months = remember(movements, currentBalance, horizon) {
        val list = mutableListOf<ForecastMonth>(); var running = currentBalance; var m = YearMonth.from(today); val last = YearMonth.from(end)
        while (!m.isAfter(last)) {
            val mm = movements.filter { YearMonth.from(it.date) == m }
            val inc = mm.filter { it.amount > 0 }.sumOf { it.amount }
            val out = abs(mm.filter { it.amount < 0 }.sumOf { it.amount })
            running += inc - out
            list += ForecastMonth(m, inc, out, running)
            m = m.plusMonths(1)
        }
        list
    }


    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if(forecastSyncError!=null) item { Text(forecastSyncError!!,color=MaterialTheme.colorScheme.error);TextButton(onClick={syncForecastState()}){Text("Tentar sincronizar")} }
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Column(
                    Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF0E4FA3), Color(0xFF416AD9), Color(0xFF6D5CE7)))).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Previsão financeira", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Filled.AutoGraph, contentDescription = null, tint = Color.White)
                    }
                    Text("Uma visão simples do que já está previsto e dos cenários que você simular.", color = Color(0xFFE7EEFF))
                }
            }
        }
        item { SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { listOf(30,60,90).forEachIndexed { i, days -> SegmentedButton(horizon == days, { horizon = days }, SegmentedButtonDefaults.itemShape(i,3)) { Text("$days dias") } } } }
        item {
            val riskColor = if (firstNegative != null) Color(0xFFD64545) else Color(0xFF16845B)
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = riskColor.copy(alpha=.10f)) { Icon(if (firstNegative != null) Icons.Filled.WarningAmber else Icons.Filled.Verified, contentDescription = null, tint = riskColor, modifier = Modifier.padding(10.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Risco da projeção", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(riskLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (firstNegative != null) "Pode ficar negativo em ${firstNegative!!.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}." else "Nenhum saldo negativo foi identificado neste período.")
                    }
                }
            }
        }
        item {
            Text("Resumo da previsão", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ForecastValueCard("Saldo atual", money.format(currentBalance), Modifier.weight(1f))
                    ForecastValueCard(if (simulations.isEmpty()) "Saldo previsto" else "Saldo com cenário", money.format(projected), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ForecastValueCard("Entradas previstas", money.format(income), Modifier.weight(1f), Color(0xFF16845B))
                    ForecastValueCard("Saídas previstas", money.format(expenses), Modifier.weight(1f), Color(0xFFC73E47))
                }
                if (simulations.isNotEmpty()) Text("Sem o cenário: ${money.format(realProjected)} • impacto do cenário: ${money.format(projected-realProjected)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Button(onClick = { showSimulation = true; keepAdding = false }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Filled.AddCircleOutline, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Adicionar")
            }
        }
        item { Text("Cenários realizados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
        if (savedScenarios.isEmpty()) item { Text("Nenhum cenário salvo ainda.", color = MaterialTheme.colorScheme.onSurfaceVariant) } else items(savedScenarios, key = { it.id }) { saved ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(saved.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("${saved.items.size} item(ns) • ${saved.horizon} dias", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (saved.createdAt.isNotBlank()) Text(saved.createdAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f))
                    }
                    Box {
                        IconButton(onClick = { scenarioMenuId = saved.id }) { Icon(Icons.Filled.MoreVert, contentDescription = "Opções do cenário", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        DropdownMenu(expanded = scenarioMenuId == saved.id, onDismissRequest = { scenarioMenuId = null }) {
                            DropdownMenuItem(text = { Text("Usar") }, onClick = { simulations = saved.items; horizon = saved.horizon; scenarioMenuId = null })
                            DropdownMenuItem(text = { Text("Transformar em lançamento real") }, onClick = {
                                saved.items.forEach { item -> onTransformSimulation(item.description, item.amount, item.isIncome, item.firstDate, item.installments, false) }
                                scenarioMenuId = null
                            })
                            DropdownMenuItem(text = { Text("Excluir") }, onClick = { scenarioMenuId = null; scenarioToDelete = saved })
                        }
                    }
                }
            }
        }
        if (simulations.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cenário atual", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showSaveScenario = true }) { Icon(Icons.Filled.Save, null); Spacer(Modifier.width(4.dp)); Text("Salvar") }
                }
            }
            items(simulations) { sim ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(sim.description, fontWeight=FontWeight.SemiBold)
                        Text((if(sim.isIncome) "Entrada" else "Despesa") + " • " + money.format(sim.amount) + (if(sim.installments>1) " • ${sim.installments}x" else " • à vista"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Primeira em ${sim.firstDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}", style = MaterialTheme.typography.bodySmall)
                        Row { TextButton(onClick={ onTransformSimulation(sim.description, sim.amount, sim.isIncome, sim.firstDate, sim.installments, false) }) { Text("Transformar em lançamento") }; TextButton(onClick={ simulations = simulations - sim }) { Text("Remover") } }
                    }
                }
            }
        }
        item { Text("Projeção por mês", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(months) { m ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text(m.month.month.getDisplayName(TextStyle.FULL, br).replaceFirstChar { it.uppercase() }+" ${m.month.year}", fontWeight=FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Entradas: ${money.format(m.income)}")
                    Text("Saídas e faturas: ${money.format(m.expenses)}")
                    HorizontalDivider(Modifier.padding(vertical=6.dp))
                    Text("Saldo ao fim do mês: ${money.format(m.endingBalance)}", fontWeight=FontWeight.SemiBold)
                }
            }
        }
        item { Text("Simulações só viram transações quando você escolher transformar um item em lançamento.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(20.dp)) }
    }


    if (showSimulation) {
        var description by remember(showSimulation, keepAdding) { mutableStateOf("") }
        var rawAmount by remember(showSimulation, keepAdding) { mutableStateOf("") }
        var installments by remember(showSimulation, keepAdding) { mutableIntStateOf(1) }
        var firstDate by remember(showSimulation, keepAdding) { mutableStateOf(today.plusDays(1)) }
        var showDate by remember(showSimulation, keepAdding) { mutableStateOf(false) }
        var isIncome by remember(showSimulation, keepAdding) { mutableStateOf(false) }
        fun addCurrent(): Boolean {
            val value = simulationMoney(rawAmount)
            if (description.isBlank() || value <= 0) return false
            simulations = simulations + Simulation(description.trim(), value, if(isIncome) 1 else installments, firstDate, isIncome, false)
            return true
        }
        AlertDialog(
            onDismissRequest={showSimulation=false},
            title={Text("Adicionar ao cenário")},
            text={ Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { SegmentedButton(!isIncome,{isIncome=false},SegmentedButtonDefaults.itemShape(0,2)){Text("Despesa")}; SegmentedButton(isIncome,{isIncome=true},SegmentedButtonDefaults.itemShape(1,2)){Text("Entrada")} }
                OutlinedTextField(description,{description=it},label={Text("Descrição")},singleLine=true)
                OutlinedTextField(value=rawAmount, onValueChange={rawAmount=BrlMoney.digits(it)}, label={Text("Valor")}, visualTransformation=BrlMoneyVisualTransformation, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), singleLine=true)
                if (!isIncome) { Text("Parcelas: $installments"); Slider(installments.toFloat(), {installments=it.toInt().coerceIn(1,24)}, valueRange=1f..24f, steps=22) }
                OutlinedButton(onClick={showDate=true},modifier=Modifier.fillMaxWidth()){Text("Primeira data: ${firstDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}")}
            } },
            confirmButton={ Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled=description.isNotBlank() && simulationMoney(rawAmount)>0, onClick={ if (addCurrent()) showSimulation=false }) { Text("Adicionar e sair") }
                Button(enabled=description.isNotBlank() && simulationMoney(rawAmount)>0, onClick={ if (addCurrent()) { keepAdding = !keepAdding } }) { Text("Adicionar mais") }
            } },
            dismissButton={TextButton(onClick={showSimulation=false}){Text("Cancelar")}}
        )
        if(showDate){ val state=rememberDatePickerState(initialSelectedDateMillis=firstDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()); DatePickerDialog(onDismissRequest={showDate=false},confirmButton={TextButton(onClick={state.selectedDateMillis?.let{firstDate=java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()};showDate=false}){Text("OK")}},dismissButton={TextButton(onClick={showDate=false}){Text("Cancelar")}}){DatePicker(state=state)} }
    }

    if (showSaveScenario) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showSaveScenario = false }, title = { Text("Salvar cenário") }, text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Nome do cenário") }, placeholder = { Text("Ex.: Reforma da casa") }, singleLine = true) }, confirmButton = {
            Button(enabled = name.isNotBlank() && simulations.isNotEmpty(), onClick = {
                val saved = SavedForecastScenario(System.currentTimeMillis(), name.trim(), horizon, simulations.toList(), java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm")))
                deletedScenarioIds = deletedScenarioIds - saved.id
                saveDeletedScenarioIds(context, userKey, workspaceId, deletedScenarioIds)
                savedScenarios = listOf(saved) + savedScenarios
                saveSavedScenarios(context, userKey, workspaceId, savedScenarios)
                syncForecastState()
                showSaveScenario = false
            }) { Text("Salvar") }
        }, dismissButton = { TextButton(onClick = { showSaveScenario = false }) { Text("Cancelar") } })
    }

    scenarioToDelete?.let { saved ->
        AlertDialog(onDismissRequest = { scenarioToDelete = null }, title = { Text("Excluir cenário?") }, text = { Text("O cenário '${saved.name}' será excluído dos seus cenários sincronizados.") }, confirmButton = {
            TextButton(onClick = {
                deletedScenarioIds = deletedScenarioIds + saved.id
                saveDeletedScenarioIds(context, userKey, workspaceId, deletedScenarioIds)
                savedScenarios = savedScenarios.filterNot { it.id == saved.id }
                saveSavedScenarios(context, userKey, workspaceId, savedScenarios)
                syncForecastState()
                scenarioToDelete = null
            }) { Text("Excluir") }
        }, dismissButton = { TextButton(onClick = { scenarioToDelete = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun ForecastValueCard(title: String, value: String, modifier: Modifier = Modifier, accent: Color = Color(0xFF0E4FA3)) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent)
        }
    }
}


@Composable
private fun DebtAccountDropdown(value:Int?, accounts:List<AccountEntity>, onValue:(Int?)->Unit){
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()){
        OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){
            Text(accounts.firstOrNull{it.id==value}?.institutionName ?: "Banco/conta (opcional)",modifier=Modifier.weight(1f))
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
            DropdownMenuItem(text={Text("Nenhum")},onClick={onValue(null);expanded=false})
            accounts.forEach{a->DropdownMenuItem(text={Text(a.institutionName)},onClick={onValue(a.id);expanded=false})}
        }
    }
}
