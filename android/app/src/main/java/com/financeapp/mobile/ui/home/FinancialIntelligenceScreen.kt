package com.financeapp.mobile.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financeapp.mobile.data.local.AccountEntity
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.CategoryDto
import com.financeapp.mobile.data.remote.CreditCardDto
import com.financeapp.mobile.data.remote.FinancialAiRequest
import com.financeapp.mobile.data.remote.FinancialAiResponse
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class IntelligenceInsight(val icon: ImageVector, val title: String, val text: String)
private data class SavedAiPlan(val id: Long, val title: String, val goal: String, val target: Double, val months: Int, val content: String, val createdAt: String)

private fun loadAiPlans(context: android.content.Context, userKey: String): List<SavedAiPlan> = runCatching {
    val raw = context.getSharedPreferences("ai_plans", android.content.Context.MODE_PRIVATE).getString("plans_$userKey", "[]") ?: "[]"
    val a = JSONArray(raw)
    (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> SavedAiPlan(o.getLong("id"), o.getString("title"), o.optString("goal"), o.optDouble("target", 0.0), o.optInt("months"), o.getString("content"), o.getString("createdAt")) } }.sortedByDescending { it.id }
}.getOrDefault(emptyList())

private fun saveAiPlans(context: android.content.Context, userKey: String, plans: List<SavedAiPlan>) {
    val a = JSONArray(); plans.forEach { p -> a.put(JSONObject().put("id",p.id).put("title",p.title).put("goal",p.goal).put("target",p.target).put("months",p.months).put("content",p.content).put("createdAt",p.createdAt)) }
    context.getSharedPreferences("ai_plans", android.content.Context.MODE_PRIVATE).edit().putString("plans_$userKey", a.toString()).apply()
}

private fun cleanAiMarkup(raw: String): String = raw
    .replace("**", "")
    .replace("### ", "")
    .replace("## ", "")
    .replace(Regex("(?m)^#\\s+"), "")
    .replace(Regex("(?m)^[-*]\\s+"), "• ")
    .trim()

private fun shareAiPlanPdf(context: android.content.Context, plan: SavedAiPlan) {
    val doc = PdfDocument()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val pageWidth = 595
    val pageHeight = 842
    val margin = 42f
    val contentWidth = pageWidth - margin * 2
    var pageNumber = 1
    var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
    var canvas = page.canvas
    var y = 0f

    fun drawHeader() {
        paint.color = android.graphics.Color.rgb(17, 64, 135)
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), 116f, paint)
        paint.color = android.graphics.Color.rgb(104, 86, 229)
        canvas.drawRect(0f, 96f, pageWidth.toFloat(), 116f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = 21f
        canvas.drawText("FinanceApp", margin, 48f, paint)
        paint.textSize = 13f
        paint.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText("Plano financeiro com Inteligência Artificial", margin, 73f, paint)
        y = 145f
    }

    fun newPage() {
        doc.finishPage(page)
        pageNumber++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        canvas = page.canvas
        drawHeader()
    }

    fun ensure(space: Float) { if (y + space > pageHeight - 52f) newPage() }

    fun wrapped(text: String, size: Float = 11f, bold: Boolean = false, color: Int = android.graphics.Color.rgb(37, 47, 63), indent: Float = 0f) {
        val clean = cleanAiMarkup(text)
        if (clean.isBlank()) { y += 6f; return }
        paint.textSize = size
        paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        paint.color = color
        val max = contentWidth - indent
        val words = clean.split(Regex("\\s+"))
        var current = ""
        for (w in words) {
            val test = if (current.isEmpty()) w else "$current $w"
            if (paint.measureText(test) > max && current.isNotEmpty()) {
                ensure(size + 9f)
                canvas.drawText(current, margin + indent, y, paint)
                y += size + 7f
                current = w
            } else current = test
        }
        if (current.isNotEmpty()) {
            ensure(size + 9f)
            canvas.drawText(current, margin + indent, y, paint)
            y += size + 7f
        }
    }

    fun section(title: String) {
        ensure(34f)
        y += 6f
        paint.color = android.graphics.Color.rgb(237, 243, 255)
        canvas.drawRoundRect(margin, y - 18f, pageWidth - margin, y + 10f, 10f, 10f, paint)
        wrapped(title, 12.5f, true, android.graphics.Color.rgb(17, 64, 135), 8f)
        y += 3f
    }

    drawHeader()
    wrapped(plan.title, 18f, true, android.graphics.Color.rgb(23, 31, 48))
    wrapped("Criado em ${plan.createdAt}", 9.5f, false, android.graphics.Color.rgb(102, 112, 133))
    if (plan.goal.isNotBlank()) wrapped("Objetivo: ${plan.goal}", 11f, true)
    wrapped("Prazo: ${plan.months} meses", 10.5f)
    y += 8f

    val lines = plan.content.lines().map { it.trim() }.filter { it.isNotBlank() }
    for (raw in lines) {
        val plain = cleanAiMarkup(raw)
        when {
            raw.startsWith("###") || raw.startsWith("##") || raw.startsWith("# ") -> section(plain)
            raw.startsWith("**") && raw.endsWith("**") && plain.length < 90 -> section(plain)
            raw.startsWith("-") || raw.startsWith("*") -> wrapped("• ${plain.removePrefix("• ")}", 10.8f, false, android.graphics.Color.rgb(52, 64, 84), 8f)
            Regex("^\\d+[.)]\\s+").containsMatchIn(plain) -> wrapped(plain, 10.8f, false, android.graphics.Color.rgb(52, 64, 84), 8f)
            else -> wrapped(plain, 10.8f)
        }
    }
    y += 10f
    wrapped("Plano informativo gerado pelo FinanceApp. Revise valores, prazos e decisões antes de executá-los.", 8.5f, false, android.graphics.Color.rgb(102, 112, 133))
    doc.finishPage(page)
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    val file = File(dir, "plano_ia_${plan.id}.pdf")
    file.outputStream().use { doc.writeTo(it) }
    doc.close()
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Compartilhar PDF do plano"))
}

private fun intelligenceDate(raw: String?): LocalDate? = try { raw?.take(10)?.let(LocalDate::parse) } catch (_: Exception) { null }
private fun intelligenceCashFlow(tx: TransactionEntity) = tx.source != "card_purchase" && !(tx.cardId != null && tx.source != "card_payment")

@Composable
internal fun FinancialIntelligenceScreen(
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
    categories: List<CategoryDto>,
    cards: List<CreditCardDto>,
    onAskFinancialAi: suspend (FinancialAiRequest) -> FinancialAiResponse,
    userKey: String = "local",
    workspaceName: String = "Principal",
    workspaceId: String,
    syncRevision: String? = null,
    onLoadForecastState: suspend () -> Map<String, Any?>,
    onSaveForecastState: suspend (Map<String, Any?>) -> Map<String, Any?>
) {
    val locale = remember { Locale("pt", "BR") }
    val money = remember { NumberFormat.getCurrencyInstance(locale) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val plansKey="${userKey}_workspace_$workspaceId"
    val planPrefs=remember { context.getSharedPreferences("ai_plans",android.content.Context.MODE_PRIVATE) }
    remember(plansKey) {
        val oldScope="${userKey}_${workspaceName}"
        if(!planPrefs.contains("plans_$plansKey")) {
            val oldPlans=planPrefs.getString("plans_$oldScope",null)
            if(oldPlans!=null) {
                val removed=planPrefs.getStringSet("deleted_$oldScope",emptySet()).orEmpty()+
                    planPrefs.getStringSet("pending_deletes_$oldScope",emptySet()).orEmpty()
                // Preserve legacy bytes; import once into the stable workspace key.
                planPrefs.edit().putString("plans_$plansKey",oldPlans)
                    .putStringSet("deleted_$plansKey",removed).commit()
            }
        }
        true
    }
    var savedPlans by remember(plansKey) { mutableStateOf(loadAiPlans(context, plansKey)) }
    var deletedPlans by remember(plansKey) { mutableStateOf(planPrefs.getStringSet("deleted_$plansKey",emptySet())!!.mapNotNull{it.toLongOrNull()}.toSet()) }
    var planSyncError by remember(plansKey) { mutableStateOf<String?>(null) }
    var showLegacyConfirm by remember { mutableStateOf(false) }
    var hasLegacy by remember(userKey) { mutableStateOf(loadAiPlans(context,userKey).isNotEmpty()&&!planPrefs.contains("migrated_$userKey")) }
    fun saveLocalPlans() {
        saveAiPlans(context,plansKey,savedPlans)
        planPrefs.edit().putStringSet("deleted_$plansKey",deletedPlans.map{it.toString()}.toSet()).commit()
    }
    suspend fun syncPlansNow() {
        try {
            val payload=mapOf("aiPlans" to savedPlans.map { p -> mapOf("id" to p.id,"title" to p.title,"goal" to p.goal,"target" to p.target,"months" to p.months,"content" to p.content,"createdAt" to p.createdAt) },"deletedAiPlanIds" to deletedPlans.toList())
            val response=onSaveForecastState(payload)
            val remote=response["payload"] as? Map<*,*> ?: emptyMap<Any?,Any?>()
            deletedPlans=deletedPlans+((remote["deletedAiPlanIds"] as? List<*>)?:emptyList<Any>()).mapNotNull { (it as? Number)?.toLong()?:it?.toString()?.toLongOrNull() }
            val plans=((remote["aiPlans"] as? List<*>)?:emptyList<Any>()).mapNotNull { raw ->
                val p=raw as? Map<*,*> ?: return@mapNotNull null
                val id=(p["id"] as? Number)?.toLong()?:p["id"]?.toString()?.toLongOrNull()?:return@mapNotNull null
                SavedAiPlan(id,(p["title"]?:"Plano financeiro").toString(),(p["goal"]?:"").toString(),(p["target"] as? Number)?.toDouble()?:0.0,(p["months"] as? Number)?.toInt()?:12,(p["content"]?:"").toString(),(p["createdAt"]?:"").toString())
            }
            savedPlans=(savedPlans+plans).associateBy{it.id}.values.filterNot{it.id in deletedPlans}.sortedByDescending{it.id}
            saveLocalPlans();planSyncError=null
        } catch(e:kotlinx.coroutines.CancellationException){throw e} catch(e:Exception){planSyncError="Salvo neste aparelho. Sincronização pendente: ${e.message ?: "sem conexão"}"}
    }
    fun persistPlans(){saveLocalPlans();scope.launch{syncPlansNow()}}
    LaunchedEffect(plansKey){while(true){syncPlansNow();kotlinx.coroutines.delay(30000)}}
    LaunchedEffect(syncRevision){syncPlansNow()}
    if(showLegacyConfirm) AlertDialog(onDismissRequest={showLegacyConfirm=false},title={Text("Importar planos antigos?")},text={Text("Os planos antigos desta conta não tinham carteira identificada. Deseja vinculá-los à carteira $workspaceName?")},confirmButton={TextButton(onClick={
        savedPlans=(savedPlans+loadAiPlans(context,userKey)).associateBy{it.id}.values.toList()
        persistPlans();planPrefs.edit().putString("migrated_$userKey",workspaceId).commit();hasLegacy=false;showLegacyConfirm=false
    }){Text("Importar nesta carteira")}},dismissButton={TextButton(onClick={showLegacyConfirm=false}){Text("Cancelar")}})
    var currentPlan by remember { mutableStateOf<SavedAiPlan?>(null) }
    var showPlans by remember { mutableStateOf(false) }
    var planToDelete by remember { mutableStateOf<SavedAiPlan?>(null) }
    var aiLoading by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    val currentMonth = YearMonth.from(today)
    val previousMonth = currentMonth.minusMonths(1)

    val cash = transactions.filter(::intelligenceCashFlow)
    fun inMonth(tx: TransactionEntity, month: YearMonth): Boolean = intelligenceDate(tx.date)?.let { YearMonth.from(it) == month } == true
    val current = cash.filter { inMonth(it, currentMonth) && (intelligenceDate(it.date)?.isAfter(today) != true) }
    val previous = cash.filter { inMonth(it, previousMonth) }
    val currentIncome = current.filter { it.amount > 0 }.sumOf { it.amount }
    val currentExpenses = abs(current.filter { it.amount < 0 }.sumOf { it.amount })
    val previousIncome = previous.filter { it.amount > 0 }.sumOf { it.amount }
    val previousExpenses = abs(previous.filter { it.amount < 0 }.sumOf { it.amount })
    val balanceMonth = currentIncome - currentExpenses
    val previousBalanceMonth = previousIncome - previousExpenses
    val expenseChange = if (previousExpenses > 0) ((currentExpenses - previousExpenses) / previousExpenses) * 100.0 else null
    val savingsRate = if (currentIncome > 0) (balanceMonth / currentIncome) * 100.0 else null

    val categoryTotals = current.filter { it.amount < 0 }.groupBy { it.categoryId }.mapValues { (_, list) -> abs(list.sumOf { it.amount }) }
    val topCategory = categoryTotals.maxByOrNull { it.value }
    val topCategoryName = topCategory?.key?.let { id -> categories.firstOrNull { it.id == id }?.name } ?: if (topCategory != null) "Sem categoria" else null

    val categoryGrowth = categoryTotals.mapNotNull { (id, now) ->
        val before = abs(previous.filter { it.amount < 0 && it.categoryId == id }.sumOf { it.amount })
        if (before <= 0 || now <= before) null else Triple(id, now, ((now - before) / before) * 100.0)
    }.maxByOrNull { it.third }

    val recurrencePatterns = remember(transactions, userKey, workspaceName) { detectRecurringPatterns(transactions).filter { recurrenceDecision(context,userKey,workspaceName,it.key) == "accepted" } }
    val recurringExpenseMonthly = recurrencePatterns.filter { !it.isIncome }.sumOf { it.amount }
    val recurringIncomeMonthly = recurrencePatterns.filter { it.isIncome }.sumOf { it.amount }
    val recurringCommitmentRate = if (currentIncome > 0) (recurringExpenseMonthly / currentIncome) * 100.0 else null

    val cardPurchases = transactions.filter { tx ->
        (tx.source == "card_purchase" || (tx.cardId != null && tx.source != "card_payment")) && inMonth(tx, currentMonth)
    }
    val cardSpend = abs(cardPurchases.sumOf { it.amount })

    val knownBalances = accounts.mapNotNull { it.currentBalance }
    val currentBalance = if (knownBalances.isNotEmpty()) knownBalances.sum() else cash.filter { intelligenceDate(it.date)?.isAfter(today) != true }.sumOf { it.amount }
    val future = cash.mapNotNull { tx -> intelligenceDate(tx.date)?.let { d -> if (d.isAfter(today) && !d.isAfter(today.plusDays(90))) d to tx.amount else null } }.sortedBy { it.first }
    var running = currentBalance
    var negativeDate: LocalDate? = null
    for ((date, amount) in future) { running += amount; if (running < 0 && negativeDate == null) negativeDate = date }

    var assistantQuestion by remember { mutableStateOf("") }
    var assistantAnswer by remember { mutableStateOf<String?>(null) }
    var planGoal by remember { mutableStateOf("") }
    var planTargetDigits by remember { mutableStateOf("") }
    var planMonthsText by remember { mutableStateOf("12") }
    val planTarget = BrlMoney.toDouble(planTargetDigits)
    val planMonths = planMonthsText.toIntOrNull()?.coerceIn(1, 120) ?: 12
    val planMonthly = if (planTarget > 0) planTarget / planMonths else 0.0

    fun categoryName(id: Int?) = id?.let { key -> categories.firstOrNull { it.id == key }?.name } ?: "Sem categoria"

    fun answerFinancialQuestion(raw: String): String {
        val q = raw.trim().lowercase(locale)
        if (q.isBlank()) return "Digite uma pergunta sobre suas finanças."
        val top = categoryTotals.maxByOrNull { it.value }
        val amountRegex = Regex("(?:r\\$\\s*)?([0-9]{1,3}(?:\\.[0-9]{3})*(?:,[0-9]{1,2})?|[0-9]+(?:[.,][0-9]{1,2})?)")
        val askedAmount = amountRegex.find(q)?.groupValues?.getOrNull(1)?.replace(".", "")?.replace(",", ".")?.toDoubleOrNull()
        return when {
            (q.contains("onde") && q.contains("gast")) || q.contains("maior categoria") ->
                if (top == null) "Ainda não há despesas de fluxo de caixa neste mês para comparar." else "Neste mês, sua maior categoria de saída é ${categoryName(top.key)}, com ${money.format(top.value)}. Compras de cartão ficam separadas até o pagamento da fatura."
            q.contains("quanto") && q.contains("gast") ->
                "Até hoje, suas saídas de fluxo de caixa no mês somam ${money.format(currentExpenses)}. Além disso, há ${money.format(cardSpend)} em compras de cartão registradas no mês, que não entram novamente no caixa antes do pagamento da fatura."
            q.contains("cart") || q.contains("fatura") ->
                if (cardSpend <= 0) "Não encontrei compras de cartão registradas neste mês." else "As compras de cartão registradas neste mês somam ${money.format(cardSpend)}. Elas são acompanhadas separadamente; no fluxo de caixa, a saída acontece no pagamento da fatura."
            q.contains("aument") || q.contains("cresceu") ->
                categoryGrowth?.let { (id, _, growth) -> "A categoria que mais cresceu foi ${categoryName(id)}, com aumento de ${"%.0f".format(locale, growth)}% frente ao mês anterior." } ?: "Não encontrei uma categoria com aumento comparável ao mês anterior."
            q.contains("negativ") || q.contains("risco") ->
                negativeDate?.let { "Com os lançamentos futuros já registrados, o saldo pode ficar negativo em ${it.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}." } ?: "Com os lançamentos futuros registrados para os próximos 90 dias, não identifiquei saldo negativo."
            (q.contains("posso") || q.contains("gastar") || q.contains("comprar")) && askedAmount != null -> {
                var projected = currentBalance
                var minimum = currentBalance
                future.forEach { (_, value) -> projected += value; if (projected < minimum) minimum = projected }
                val afterPurchase = minimum - askedAmount
                when {
                    afterPurchase < 0 -> "Uma saída adicional de ${money.format(askedAmount)} faria o menor saldo projetado dos próximos 90 dias chegar a ${money.format(afterPurchase)}. Pelo cenário atual, essa compra traz risco de saldo negativo."
                    afterPurchase < askedAmount * 0.25 -> "Uma saída de ${money.format(askedAmount)} deixaria pouca margem: o menor saldo projetado ficaria em ${money.format(afterPurchase)}. Vale revisar os próximos compromissos antes de decidir."
                    else -> "Considerando apenas os lançamentos já registrados, uma saída de ${money.format(askedAmount)} manteria o menor saldo projetado em aproximadamente ${money.format(afterPurchase)} nos próximos 90 dias. Isso é uma simulação, não uma garantia de disponibilidade."
                }
            }
            q.contains("saldo") -> "Seu saldo atual calculado é ${money.format(currentBalance)}. O resultado do mês até agora é ${money.format(balanceMonth)}."
            else -> "Posso responder sobre gastos do mês, categorias, cartões, saldo, risco de saldo negativo e simular perguntas como ‘Posso gastar R$ 2.000?’."
        }
    }

    fun aiSummary(): Map<String, Any?> {
        val categoryTotals = current.filter { it.amount < 0 }.groupBy { it.categoryId }.map { (id, rows) ->
            mapOf("category" to categoryName(id), "amount" to abs(rows.sumOf { it.amount }))
        }.sortedByDescending { (it["amount"] as Double) }.take(8)
        val future90 = cash.mapNotNull { tx -> intelligenceDate(tx.date)?.let { it to tx.amount } }
            .filter { (date, _) -> date.isAfter(today) && !date.isAfter(today.plusDays(90)) }
        var projected = currentBalance
        var minimum = currentBalance
        future90.sortedBy { it.first }.forEach { (_, amount) -> projected += amount; if (projected < minimum) minimum = projected }
        return mapOf(
            "reference_date" to today.toString(),
            "current_balance" to currentBalance,
            "month_income" to currentIncome,
            "month_cash_expenses" to currentExpenses,
            "month_result" to balanceMonth,
            "month_card_purchases" to cardSpend,
            "cards_count" to cards.count { it.active },
            "recurring_patterns_count" to recurrencePatterns.size,
            "recurring_income_monthly" to recurringIncomeMonthly,
            "recurring_expenses_monthly" to recurringExpenseMonthly,
            "top_expense_categories" to categoryTotals,
            "projected_balance_90d" to projected,
            "minimum_projected_balance_90d" to minimum,
            "first_negative_date" to negativeDate?.toString(),
            "accounting_rule" to "card_purchase_separate; card_payment_is_cash_expense"
        )
    }

    fun askAi(question: String, asPlan: Boolean = false) {
        if (question.isBlank() || aiLoading) return
        scope.launch {
            aiLoading = true
            aiError = null
            try {
                val answer = onAskFinancialAi(FinancialAiRequest(question.trim(), aiSummary())).answer
                assistantAnswer = answer
                if (asPlan) currentPlan = SavedAiPlan(System.currentTimeMillis(), planGoal.ifBlank { "Plano financeiro" }, planGoal, planTarget, planMonths, answer, java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm")))
            } catch (e: Exception) {
                aiError = "IA indisponível agora. Você ainda pode usar a análise local."
            } finally { aiLoading = false }
        }
    }

    val insights = buildList {
        expenseChange?.let { change ->
            if (change >= 10) add(IntelligenceInsight(Icons.Default.TrendingUp, "Gastos em alta", "Suas despesas neste mês estão ${"%.0f".format(locale, change)}% acima do mês anterior."))
            else if (change <= -10) add(IntelligenceInsight(Icons.Default.TrendingDown, "Gastos diminuíram", "Suas despesas estão ${"%.0f".format(locale, abs(change))}% menores que no mês anterior."))
        }
        categoryGrowth?.let { (id, _, growth) ->
            val name = categories.firstOrNull { it.id == id }?.name ?: "Sem categoria"
            add(IntelligenceInsight(Icons.Default.Category, "$name cresceu", "Os gastos nessa categoria aumentaram ${"%.0f".format(locale, growth)}% em relação ao mês anterior."))
        }
        topCategoryName?.let { add(IntelligenceInsight(Icons.Default.PieChart, "Maior gasto do mês", "$it concentra ${money.format(topCategory!!.value)} das suas despesas até agora.")) }
        if (recurrencePatterns.isNotEmpty()) add(IntelligenceInsight(Icons.Default.Repeat, "Compromissos recorrentes", "Detectei ${recurrencePatterns.size} padrão(ões) mensais: ${money.format(recurringIncomeMonthly)} em entradas e ${money.format(recurringExpenseMonthly)} em saídas recorrentes. Eles também alimentam a Previsão Financeira."))
                if (cardSpend > 0) add(IntelligenceInsight(Icons.Default.CreditCard, "Compras no cartão", "Você registrou ${money.format(cardSpend)} em compras de cartão neste mês. Elas entram no caixa somente quando a fatura for paga."))
        negativeDate?.let { add(IntelligenceInsight(Icons.Default.Warning, "Atenção ao saldo", "Com os lançamentos futuros já registrados, seu saldo pode ficar negativo em ${it.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}.")) }
        savingsRate?.let { rate ->
            if (rate >= 20) add(IntelligenceInsight(Icons.Default.Savings, "Boa margem no mês", "Até agora, sua diferença entre entradas e saídas equivale a ${"%.0f".format(locale, rate)}% das entradas."))
            else if (rate in 0.0..9.99) add(IntelligenceInsight(Icons.Default.Info, "Margem apertada", "Sua sobra atual representa apenas ${"%.0f".format(locale, rate)}% das entradas do mês."))
        }
        if (isEmpty()) add(IntelligenceInsight(Icons.Default.AutoAwesome, "Tudo acompanhado", "Ainda não há variações relevantes suficientes para gerar alertas neste mês."))
    }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if(planSyncError!=null) item { Text(planSyncError!!,color=MaterialTheme.colorScheme.error);TextButton(onClick={scope.launch{syncPlansNow()}}){Text("Tentar sincronizar")} }
        if(hasLegacy) item { TextButton(onClick={showLegacyConfirm=true}){Text("Importar planos antigos desta conta")} }
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF0E4FA3), Color(0xFF3F67D9), Color(0xFF6D5CE7))))
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Inteligência financeira", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                            Text("Análises automáticas para entender melhor o seu dinheiro.", color = Color(0xFFDCE8FF), style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { IntelligenceValueCard("Entradas do mês", money.format(currentIncome), Modifier.weight(1f), Color(0xFF16845B)); IntelligenceValueCard("Saídas do mês", money.format(currentExpenses), Modifier.weight(1f), Color(0xFFC73E47)) } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { IntelligenceValueCard("Resultado do mês", money.format(balanceMonth), Modifier.weight(1f), Color(0xFF0E4FA3)); IntelligenceValueCard("Saldo atual", money.format(currentBalance), Modifier.weight(1f), Color(0xFF6D5CE7)) } }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Saúde financeira do mês", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (balanceMonth >= 0) "Até agora, suas entradas cobrem as saídas do mês." else "Até agora, as saídas do mês estão acima das entradas.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()
                    Text("Resultado atual: ${money.format(balanceMonth)}", fontWeight = FontWeight.SemiBold)
                    Text("Mês anterior: ${money.format(previousBalanceMonth)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    expenseChange?.let { change ->
                        val direction = if (change >= 0) "acima" else "abaixo"
                        Text("Despesas: ${"%.0f".format(locale, abs(change))}% $direction do mês anterior.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } ?: Text("Comparação de despesas ainda sem base suficiente no mês anterior.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    savingsRate?.let { rate ->
                        Text("Margem entre entradas e saídas: ${"%.0f".format(locale, rate)}% das entradas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    recurringCommitmentRate?.let { rate ->
                        Text("Compromissos recorrentes confirmados equivalem a ${"%.0f".format(locale, rate)}% das entradas deste mês.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Compras no cartão continuam separadas do fluxo de caixa até o pagamento da fatura.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Planejamento financeiro com IA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Defina um objetivo e o app calcula o esforço mensal. A IA pode sugerir um plano usando apenas o resumo financeiro.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(planGoal, { planGoal = it.take(120) }, label={Text("Objetivo")}, placeholder={Text("Ex.: formar reserva de emergência")}, modifier=Modifier.fillMaxWidth(), singleLine=true)
                    OutlinedTextField(planTargetDigits, { planTargetDigits = BrlMoney.digits(it) }, label={Text("Valor da meta")}, modifier=Modifier.fillMaxWidth(), singleLine=true, visualTransformation=BrlMoneyVisualTransformation, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                    OutlinedTextField(planMonthsText, { planMonthsText = it.filter(Char::isDigit).take(3) }, label={Text("Prazo em meses")}, modifier=Modifier.fillMaxWidth(), singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                    if (planTarget > 0) Text("Para atingir ${money.format(planTarget)} em $planMonths meses: ${money.format(planMonthly)} por mês.", fontWeight=FontWeight.SemiBold)
                    Button(enabled=planGoal.isNotBlank() && planTarget>0 && !aiLoading, onClick={ askAi("Monte um plano financeiro prático para o objetivo '$planGoal', no valor de ${money.format(planTarget)}, em $planMonths meses. O esforço matemático é ${money.format(planMonthly)} por mês. Use meu resumo financeiro, sugira ajustes por categoria quando houver dados e destaque riscos. Não crie lançamentos automaticamente.", true) }, modifier=Modifier.fillMaxWidth()) {
                        if(aiLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth=2.dp) else Icon(Icons.Default.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp)); Text(if(aiLoading) "Preparando plano" else "Preparar plano com IA")
                    }
                    currentPlan?.let { plan ->
                        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape=RoundedCornerShape(18.dp), tonalElevation=0.dp) { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)), contentAlignment = androidx.compose.ui.Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) }
                                Spacer(Modifier.width(10.dp)); Text("Plano gerado", style = MaterialTheme.typography.titleMedium, fontWeight=FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                            }
                            AiPlanContent(plan.content)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(onClick={ if(savedPlans.none{it.id==plan.id}) { savedPlans=listOf(plan)+savedPlans; persistPlans() } }, modifier=Modifier.weight(1f)) { Icon(Icons.Default.BookmarkAdd,null); Spacer(Modifier.width(5.dp)); Text(if(savedPlans.any{it.id==plan.id}) "Salvo" else "Salvar plano") }
                                OutlinedButton(onClick={shareAiPlanPdf(context,plan)}, modifier=Modifier.weight(1f)) { Icon(Icons.Default.PictureAsPdf,null); Spacer(Modifier.width(5.dp)); Text("Gerar PDF") }
                            }
                        } }
                    }
                    OutlinedButton(onClick={showPlans=true}, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderOpen,null); Spacer(Modifier.width(6.dp)); Text("Meus planos da IA (${savedPlans.size})") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pergunte sobre suas finanças", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("A análise local funciona no aparelho. A IA recebe somente um resumo financeiro estruturado, nunca sua chave de API.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val suggestions = listOf("Onde estou gastando mais?", "Quanto gastei este mês?", "Como estão meus cartões?", "Meu saldo corre risco?")
                    suggestions.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { suggestion -> AssistSuggestion(suggestion, Modifier.weight(1f)) { assistantQuestion = suggestion; assistantAnswer = answerFinancialQuestion(suggestion) } }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    OutlinedTextField(value = assistantQuestion, onValueChange = { assistantQuestion = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Sua pergunta") }, placeholder = { Text("Ex.: Posso gastar R$ 2.000?") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { assistantAnswer = answerFinancialQuestion(assistantQuestion); aiError = null }, enabled = assistantQuestion.isNotBlank() && !aiLoading, modifier = Modifier.weight(1f)) { Text("Análise local") }
                        Button(onClick = { askAi(assistantQuestion) }, enabled = assistantQuestion.isNotBlank() && !aiLoading, modifier = Modifier.weight(1f)) {
                            if (aiLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.AutoAwesome, null)
                            Spacer(Modifier.width(6.dp)); Text(if (aiLoading) "Consultando" else "Perguntar à IA")
                        }
                    }
                    aiError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    assistantAnswer?.let { answer -> Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) { Box(Modifier.padding(14.dp)) { AiPlanContent(answer) } } }
                }
            }
        }
        item { Text("Insights", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(insights) { insight -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(insight.icon, null); Column { Text(insight.title, fontWeight = FontWeight.Bold); Text(insight.text, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }
        item { Text("Os insights são calculados a partir dos lançamentos disponíveis no app. Compras de cartão são analisadas separadamente e não são somadas ao fluxo de caixa antes do pagamento da fatura.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(20.dp)) }
    }

    if (showPlans) {
        AlertDialog(
            onDismissRequest = { showPlans = false },
            title = { Text("Meus planos da IA") },
            text = {
                if (savedPlans.isEmpty()) {
                    Text("Nenhum plano salvo ainda.")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(savedPlans, key = { it.id }) { plan ->
                            Card {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(plan.title, fontWeight = FontWeight.Bold)
                                    Text(plan.createdAt, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        cleanAiMarkup(plan.content),
                                        maxLines = 4,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Row {
                                        TextButton(onClick = {
                                            currentPlan = plan
                                            assistantAnswer = plan.content
                                            showPlans = false
                                        }) { Text("Abrir") }
                                        TextButton(onClick = { shareAiPlanPdf(context, plan) }) { Text("PDF") }
                                        TextButton(onClick = { planToDelete = plan }) { Text("Excluir") }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlans = false }) { Text("Fechar") }
            }
        )
    }

    planToDelete?.let { plan ->
        AlertDialog(
            onDismissRequest = { planToDelete = null },
            title = { Text("Excluir plano?") },
            text = { Text("O plano '${plan.title}' será excluído dos seus aparelhos sincronizados.") },
            confirmButton = {
                TextButton(onClick = {
                    deletedPlans=deletedPlans+plan.id
                    savedPlans = savedPlans.filterNot { it.id == plan.id }
                    persistPlans()
                    if (currentPlan?.id == plan.id) currentPlan = null
                    planToDelete = null
                }) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { planToDelete = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun AiPlanContent(content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { raw ->
            val plain = cleanAiMarkup(raw)
            when {
                raw.startsWith("###") || raw.startsWith("##") || raw.startsWith("# ") ->
                    Text(plain, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                raw.startsWith("**") && raw.endsWith("**") && plain.length < 90 ->
                    Text(plain, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
                raw.startsWith("-") || raw.startsWith("*") ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(plain.removePrefix("• "), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                else -> Text(plain, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun IntelligenceValueCard(title: String, value: String, modifier: Modifier = Modifier, accent: Color = Color(0xFF0E4FA3)) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(width = 34.dp, height = 4.dp).background(accent, RoundedCornerShape(8.dp)))
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

@Composable
private fun AssistSuggestion(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}
