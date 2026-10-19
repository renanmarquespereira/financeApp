package com.financeapp.mobile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financeapp.mobile.data.local.AccountEntity
import com.financeapp.mobile.data.remote.CategoryDto
import com.financeapp.mobile.data.remote.CreditCardDto
import org.json.JSONObject
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

internal data class VoiceTransactionDraft(
    val transcript: String,
    val description: String,
    val amount: Double,
    val type: String,
    val date: LocalDate,
    val categoryId: Int? = null,
    val accountId: Int? = null,
    val paymentMethod: String = "account",
    val cardId: Int? = null,
    val installmentCount: Int = 1,
    val firstChargeDate: LocalDate? = null,
    val cardRefund: Boolean = false,
    val interpretedByAi: Boolean = false
)

internal object VoiceTransactionParser {
    private val expenseWords = listOf(
        "gastei", "paguei", "comprei", "despesa", "saída", "saida", "debitei", "custou"
    )
    private val incomeWords = listOf(
        "recebi", "ganhei", "entrou", "entrada", "vendi", "depositaram", "caiu"
    )
    private val refundWords = listOf(
        "estorno", "estornaram", "estornado", "devolucao", "devolução", "devolveram",
        "reembolso", "reembolsaram", "cancelaram", "cancelamento"
    )
    private val cardWords = listOf(
        "cartao", "cartão", "credito", "crédito", "visa", "mastercard", "elo", "amex"
    )

    private val units = mapOf(
        "zero" to 0, "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3,
        "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9,
        "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13, "quatorze" to 14,
        "catorze" to 14, "quinze" to 15, "dezesseis" to 16, "dezessete" to 17,
        "dezoito" to 18, "dezenove" to 19
    )
    private val tens = mapOf(
        "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50,
        "sessenta" to 60, "setenta" to 70, "oitenta" to 80, "noventa" to 90
    )
    private val hundreds = mapOf(
        "cem" to 100, "cento" to 100, "duzentos" to 200, "duzentas" to 200,
        "trezentos" to 300, "trezentas" to 300, "quatrocentos" to 400, "quatrocentas" to 400,
        "quinhentos" to 500, "quinhentas" to 500, "seiscentos" to 600, "seiscentas" to 600,
        "setecentos" to 700, "setecentas" to 700, "oitocentos" to 800, "oitocentas" to 800,
        "novecentos" to 900, "novecentas" to 900
    )

    fun parse(
        spoken: String,
        accounts: List<AccountEntity>,
        categories: List<CategoryDto>,
        cards: List<CreditCardDto>
    ): VoiceTransactionDraft {
        val normalized = normalize(spoken)
        val refund = refundWords.any { normalize(it) in normalized }
        val inferredCardId = inferCardId(spoken, cards)
        val mentionsCard = cardWords.any { normalize(it) in normalized } || inferredCardId != null
        val paymentMethod = if (mentionsCard || (refund && cards.isNotEmpty())) "card" else "account"
        val type = when {
            paymentMethod == "card" && refund -> "credit"
            incomeWords.any { normalize(it) in normalized } -> "credit"
            expenseWords.any { normalize(it) in normalized } -> "debit"
            else -> "debit"
        }
        val installments = parseInstallments(spoken).coerceIn(1, 48)
        val amount = parseAmount(spoken, installments)
        val date = parseDate(spoken)
        val description = extractDescription(spoken).ifBlank {
            when {
                paymentMethod == "card" && refund -> "Estorno no cartão"
                paymentMethod == "card" -> "Compra no cartão"
                type == "credit" -> "Entrada por voz"
                else -> "Despesa por voz"
            }
        }
        return VoiceTransactionDraft(
            transcript = spoken,
            description = description,
            amount = amount,
            type = type,
            date = date,
            categoryId = inferCategoryId(spoken, categories),
            accountId = if (paymentMethod == "card") null else inferAccountId(spoken, accounts),
            paymentMethod = paymentMethod,
            cardId = if (paymentMethod == "card") inferredCardId else null,
            installmentCount = if (paymentMethod == "card") installments else 1,
            firstChargeDate = if (paymentMethod == "card") date else null,
            cardRefund = paymentMethod == "card" && refund,
            interpretedByAi = false
        )
    }

    fun aiPrompt(
        spoken: String,
        accounts: List<AccountEntity>,
        categories: List<CategoryDto>,
        cards: List<CreditCardDto>
    ): String {
        val categoryNames = categories.joinToString(", ") { it.name }
        val accountNames = accounts.joinToString(", ") {
            listOfNotNull(it.institutionName, it.accountName).joinToString(" - ")
        }
        val cardNames = cards.joinToString(", ") { card ->
            listOfNotNull(
                card.nickname?.takeIf { it.isNotBlank() },
                card.bankName.takeIf { it.isNotBlank() },
                card.brand.takeIf { it.isNotBlank() },
                card.lastFour.takeIf { it.isNotBlank() }?.let { "final $it" }
            ).joinToString(" - ")
        }
        return """
            Interprete uma frase falada para criar UM lançamento financeiro no FinanceApp.
            Responda SOMENTE com um objeto JSON válido, sem markdown e sem explicações, com estes campos:
            {"description":"texto curto","amount":12.34,"type":"debit ou credit","date":"YYYY-MM-DD","category":"nome ou vazio","account":"nome ou vazio","payment_method":"account ou card","card":"nome/apelido/final ou vazio","installments":1,"card_refund":false}
            Regras:
            - Compra/gasto normal: debit. Recebimento/entrada: credit.
            - Se a frase indicar cartão de crédito, use payment_method="card" e NÃO trate como saída da conta bancária.
            - Se indicar estorno, devolução, reembolso ou cancelamento de compra no cartão, use payment_method="card", type="credit" e card_refund=true. Esse crédito deve reduzir a fatura do cartão, não virar entrada na conta.
            - installments é a quantidade total de parcelas. "R$ 2.400 em 12x" significa amount=2400 e installments=12. "12 parcelas de R$ 200" significa amount=2400 e installments=12.
            - amount é sempre o VALOR TOTAL da compra/estorno e sempre positivo no JSON.
            - Se a data não for dita, use hoje (${LocalDate.now()}).
            - Não invente conta nem cartão. Se não der para identificar, deixe vazio.
            - Para categoria, escolha somente entre as categorias disponíveis se houver correspondência razoável; caso contrário deixe vazio.
            Categorias disponíveis: $categoryNames
            Contas disponíveis: $accountNames
            Cartões disponíveis: $cardNames
            Frase: $spoken
        """.trimIndent()
    }

    fun parseAiAnswer(
        answer: String,
        fallback: VoiceTransactionDraft,
        accounts: List<AccountEntity>,
        categories: List<CategoryDto>,
        cards: List<CreditCardDto>
    ): VoiceTransactionDraft? {
        return runCatching {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val json = JSONObject(answer.substring(start, end + 1))
            val aiDescription = json.optString("description").trim()
            val aiAmount = json.optDouble("amount", 0.0)
            val aiPayment = json.optString("payment_method").lowercase().let {
                if (it == "card") "card" else if (it == "account") "account" else fallback.paymentMethod
            }
            val aiRefund = json.optBoolean("card_refund", fallback.cardRefund)
            val aiType = if (aiPayment == "card" && aiRefund) {
                "credit"
            } else {
                json.optString("type").lowercase().let {
                    if (it == "credit") "credit" else if (it == "debit") "debit" else fallback.type
                }
            }
            val aiDate = runCatching {
                LocalDate.parse(json.optString("date"), DateTimeFormatter.ISO_LOCAL_DATE)
            }.getOrDefault(fallback.date)
            val aiCategory = json.optString("category").trim()
            val aiAccount = json.optString("account").trim()
            val aiCard = json.optString("card").trim()
            val aiInstallments = json.optInt("installments", fallback.installmentCount).coerceIn(1, 48)

            fallback.copy(
                description = aiDescription.ifBlank { fallback.description },
                amount = if (aiAmount > 0.0) aiAmount else fallback.amount,
                type = aiType,
                date = aiDate,
                categoryId = findCategoryId(aiCategory, categories) ?: fallback.categoryId,
                accountId = if (aiPayment == "card") null else (findAccountId(aiAccount, accounts) ?: fallback.accountId),
                paymentMethod = aiPayment,
                cardId = if (aiPayment == "card") (findCardId(aiCard, cards) ?: fallback.cardId) else null,
                installmentCount = if (aiPayment == "card") aiInstallments else 1,
                firstChargeDate = if (aiPayment == "card") aiDate else null,
                cardRefund = aiPayment == "card" && aiRefund,
                interpretedByAi = true
            )
        }.getOrNull()
    }

    fun toIsoDateTime(date: LocalDate): String =
        date.atTime(LocalTime.now()).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()

    private fun parseInstallments(text: String): Int {
        Regex("\\b(\\d{1,2})\\s*(?:x|vezes|parcelas?)\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val normalized = normalize(text)
        Regex("((?:[a-z]+\\s+){0,3}[a-z]+)\\s+(?:vezes|parcelas?)\\b")
            .find(normalized)?.groupValues?.getOrNull(1)?.let { words ->
                val n = wordsToNumber(words)
                if (n in 1..48) return n
            }
        return 1
    }

    private fun parseAmount(text: String, installments: Int): Double {
        // "12 parcelas de R$ 200" / "12x de 200 reais" = total de R$ 2.400.
        val perInstallment = Regex(
            """(?:\\d{1,2}\\s*(?:x|vezes|parcelas?)\\s*(?:de|a)?\\s*)(?:r\\$\\s*)?(\\d{1,3}(?:\\.\\d{3})*(?:,\\d{1,2})?|\\d+(?:,\\d{1,2})?)\\s*(?:reais|real)?""",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.getOrNull(1)
        if (!perInstallment.isNullOrBlank() && installments > 1) {
            parseBrazilianNumber(perInstallment)?.takeIf { it > 0.0 }?.let { return it * installments }
        }

        Regex("\\b(\\d+)\\s*(?:reais|real)\\s+e\\s+(\\d{1,2})\\s*centavos?\\b", RegexOption.IGNORE_CASE)
            .find(text)?.let { match ->
                val reais = match.groupValues[1].toDoubleOrNull() ?: 0.0
                val cents = match.groupValues[2].toDoubleOrNull() ?: 0.0
                if (reais > 0.0 || cents > 0.0) return reais + cents.coerceIn(0.0, 99.0) / 100.0
            }

        val currencyNumberPattern = Regex("""(?:r\$\s*)(\d{1,3}(?:\.\d{3})*(?:,\d{1,2})?|\d+(?:,\d{1,2})?)|(\d{1,3}(?:\.\d{3})*(?:,\d{1,2})?|\d+(?:,\d{1,2})?)\s*(?:reais|real)\b|(\d+,\d{1,2})""", RegexOption.IGNORE_CASE)
        currencyNumberPattern.find(text)?.let { match ->
            val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
            parseBrazilianNumber(raw)?.takeIf { it > 0 }?.let { return it }
        }

        val normalized = normalize(text)
        val realMatch = Regex("(.+?)\\s+(reais|real)\\b").find(normalized)
        if (realMatch != null) {
            val beforeReal = realMatch.groupValues[1]
            val words = beforeReal.split(' ').takeLast(8).joinToString(" ")
            val reais = wordsToNumber(words)
            val afterReal = normalized.substring(realMatch.range.last + 1)
            val centsMatch = Regex("(?:e\\s+)?(.+?)\\s+centavos?\\b").find(afterReal)
            val cents = centsMatch?.groupValues?.getOrNull(1)?.let(::wordsToNumber) ?: 0
            if (reais > 0 || cents > 0) return reais + (cents.coerceIn(0, 99) / 100.0)
        }

        val centsOnly = Regex("(.+?)\\s+centavos?\\b").find(normalized)
        if (centsOnly != null) {
            val cents = wordsToNumber(centsOnly.groupValues[1].split(' ').takeLast(6).joinToString(" "))
            if (cents > 0) return cents.coerceIn(0, 99) / 100.0
        }
        return 0.0
    }

    private fun parseBrazilianNumber(raw: String): Double? {
        val cleaned = if (raw.contains(',')) raw.replace(".", "").replace(',', '.') else raw
        return cleaned.toDoubleOrNull()
    }

    private fun wordsToNumber(segment: String): Int {
        var total = 0
        var current = 0
        normalize(segment).split(Regex("\\s+")).forEach { token ->
            when {
                token == "e" || token == "de" -> Unit
                units.containsKey(token) -> current += units.getValue(token)
                tens.containsKey(token) -> current += tens.getValue(token)
                hundreds.containsKey(token) -> current += hundreds.getValue(token)
                token == "mil" -> {
                    total += (if (current == 0) 1 else current) * 1000
                    current = 0
                }
            }
        }
        return total + current
    }

    private fun parseDate(text: String): LocalDate {
        val normalized = normalize(text)
        val today = LocalDate.now()
        when {
            "anteontem" in normalized -> return today.minusDays(2)
            "ontem" in normalized -> return today.minusDays(1)
            "amanha" in normalized -> return today.plusDays(1)
        }
        Regex("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b").find(text)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            val month = match.groupValues[2].toIntOrNull() ?: return@let
            val yearRaw = match.groupValues.getOrNull(3).orEmpty()
            val year = when {
                yearRaw.isBlank() -> today.year
                yearRaw.length == 2 -> 2000 + (yearRaw.toIntOrNull() ?: 0)
                else -> yearRaw.toIntOrNull() ?: today.year
            }
            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { return it }
        }
        return today
    }

    private fun extractDescription(text: String): String {
        var result = text.trim()
        result = result.replace(Regex("(?i)\\b(gastei|paguei|comprei|recebi|ganhei|entrou|entrada|despesa|sa[ií]da|vendi|custou|estorno|estornaram|estornado|devolu[cç][aã]o|devolveram|reembolso|reembolsaram|cancelaram|cancelamento)\\b"), " ")
        result = result.replace(Regex("(?i)\\b(?:no|na|pelo|pelo meu)?\\s*cart[aã]o(?:\\s+de\\s+cr[eé]dito)?\\b"), " ")
        result = result.replace(Regex("(?i)\\b\\d{1,2}\\s*(?:x|vezes|parcelas?)\\b(?:\\s+de)?"), " ")
        result = result.replace(Regex("(?i)\\br\\$\\s*\\d[\\d.,]*"), " ")
        result = result.replace(Regex("(?i)\\b\\d[\\d.]*,\\d{1,2}\\b"), " ")
        result = result.replace(Regex("(?i)\\b\\d[\\d.,]*\\s*(?:reais|real)(?:\\s+e\\s+\\d+\\s+centavos?)?\\b"), " ")
        result = result.replace(Regex("(?i)\\b(hoje|ontem|anteontem|amanh[aã])\\b"), " ")
        result = result.replace(Regex("\\b\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?\\b"), " ")
        result = result.replace(Regex("\\s+"), " ").trim(' ', ',', '.', ';', '-')
        result = result.replace(Regex("(?i)^(com|na|no|em|de|do|da|para|por)\\s+"), "").trim()
        return result.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun inferCategoryId(text: String, categories: List<CategoryDto>): Int? {
        val normalized = normalize(text)
        categories.firstOrNull { normalize(it.name) in normalized }?.let { return it.id }

        val groups = listOf(
            listOf("padaria", "mercado", "supermercado", "restaurante", "lanche", "comida", "almoco", "jantar", "cafe") to listOf("aliment", "comida", "mercado", "restaurante"),
            listOf("gasolina", "combustivel", "uber", "taxi", "onibus", "estacionamento", "pedagio") to listOf("transport", "combust"),
            listOf("farmacia", "remedio", "medico", "consulta", "hospital") to listOf("saude", "farmacia"),
            listOf("aluguel", "condominio", "energia", "luz", "agua", "internet") to listOf("moradia", "casa", "contas"),
            listOf("cinema", "bar", "viagem", "passeio") to listOf("lazer", "entreten")
        )
        groups.firstOrNull { (keywords, _) -> keywords.any { it in normalized } }?.second?.let { categoryHints ->
            categories.firstOrNull { category ->
                val name = normalize(category.name)
                categoryHints.any { it in name }
            }?.let { return it.id }
        }
        return null
    }

    private fun inferAccountId(text: String, accounts: List<AccountEntity>): Int? {
        val normalized = normalize(text)
        return accounts.firstOrNull { account ->
            val institution = normalize(account.institutionName)
            val accountName = normalize(account.accountName.orEmpty())
            (institution.length >= 3 && institution in normalized) ||
                (accountName.length >= 3 && accountName in normalized)
        }?.id
    }

    private fun inferCardId(text: String, cards: List<CreditCardDto>): Int? {
        val normalized = normalize(text)
        val matches = cards.filter { card ->
            listOf(card.nickname.orEmpty(), card.bankName, card.brand, card.lastFour)
                .map(::normalize)
                .any { token -> token.length >= 3 && token in normalized }
        }
        if (matches.size == 1) return matches.first().id
        if (matches.isEmpty() && cards.size == 1 && cardWords.any { normalize(it) in normalized }) return cards.first().id
        return null
    }

    private fun findCategoryId(name: String, categories: List<CategoryDto>): Int? {
        if (name.isBlank()) return null
        val target = normalize(name)
        return categories.firstOrNull { normalize(it.name) == target }?.id
            ?: categories.firstOrNull { normalize(it.name).contains(target) || target.contains(normalize(it.name)) }?.id
    }

    private fun findAccountId(name: String, accounts: List<AccountEntity>): Int? {
        if (name.isBlank()) return null
        val target = normalize(name)
        return accounts.firstOrNull {
            normalize(it.institutionName).contains(target) || target.contains(normalize(it.institutionName)) ||
                normalize(it.accountName.orEmpty()).contains(target)
        }?.id
    }

    private fun findCardId(name: String, cards: List<CreditCardDto>): Int? {
        if (name.isBlank()) return null
        val target = normalize(name)
        return cards.firstOrNull { card ->
            listOf(card.nickname.orEmpty(), card.bankName, card.brand, card.lastFour)
                .map(::normalize)
                .any { it.isNotBlank() && (it.contains(target) || target.contains(it)) }
        }?.id
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9$.,/ -]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceTransactionConfirmDialog(
    initial: VoiceTransactionDraft,
    accounts: List<AccountEntity>,
    categories: List<CategoryDto>,
    cards: List<CreditCardDto>,
    onDismiss: () -> Unit,
    onConfirm: (VoiceTransactionDraft, (Boolean) -> Unit) -> Unit
) {
    var description by remember(initial) { mutableStateOf(initial.description) }
    var amountDigits by remember(initial) { mutableStateOf(BrlMoney.fromDouble(abs(initial.amount))) }
    var type by remember(initial) { mutableStateOf(initial.type) }
    var accountId by remember(initial) { mutableStateOf(initial.accountId) }
    var categoryId by remember(initial) { mutableStateOf(initial.categoryId) }
    var paymentMethod by remember(initial) { mutableStateOf(initial.paymentMethod) }
    var cardId by remember(initial) { mutableStateOf(initial.cardId) }
    var cardRefund by remember(initial) { mutableStateOf(initial.cardRefund) }
    var installmentText by remember(initial) { mutableStateOf(initial.installmentCount.coerceIn(1, 48).toString()) }
    var accountExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var cardExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val amount = BrlMoney.toDouble(amountDigits)
    val installments = installmentText.toIntOrNull()?.coerceIn(1, 48) ?: 1
    val isCard = paymentMethod == "card"
    val valid = description.isNotBlank() && amount > 0.0 && !saving && (!isCard || cardId != null)

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Confirmar lançamento por voz") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (initial.interpretedByAi) "Interpretação com IA" else "Interpretação local",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("Você disse: “${initial.transcript}”", style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isCard,
                        onClick = { paymentMethod = "account"; cardId = null; cardRefund = false },
                        label = { Text("Conta") }
                    )
                    FilterChip(
                        selected = isCard,
                        onClick = { paymentMethod = "card"; accountId = null; if (cardId == null && cards.size == 1) cardId = cards.first().id },
                        label = { Text("Cartão de crédito") }
                    )
                }

                if (isCard) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !cardRefund,
                            onClick = { cardRefund = false; type = "debit" },
                            label = { Text("Compra") }
                        )
                        FilterChip(
                            selected = cardRefund,
                            onClick = { cardRefund = true; type = "credit" },
                            label = { Text("Estorno/devolução") }
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = type == "debit", onClick = { type = "debit" }, label = { Text("Saída") })
                        FilterChip(selected = type == "credit", onClick = { type = "credit" }, label = { Text("Entrada") })
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descrição") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = amountDigits,
                    onValueChange = { amountDigits = BrlMoney.digits(it) },
                    visualTransformation = BrlMoneyVisualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(if (isCard) "Valor total" else "Valor") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = initial.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Data entendida") },
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = it }) {
                    OutlinedTextField(
                        value = categories.firstOrNull { it.id == categoryId }?.name ?: "Sem categoria",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Categoria") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        DropdownMenuItem(text = { Text("Sem categoria") }, onClick = { categoryId = null; categoryExpanded = false })
                        categories.forEach { category ->
                            DropdownMenuItem(text = { Text(category.name) }, onClick = { categoryId = category.id; categoryExpanded = false })
                        }
                    }
                }

                if (isCard) {
                    ExposedDropdownMenuBox(expanded = cardExpanded, onExpandedChange = { cardExpanded = it }) {
                        val selectedCard = cards.firstOrNull { it.id == cardId }
                        val cardLabel = selectedCard?.let { card ->
                            listOfNotNull(
                                card.nickname?.takeIf { it.isNotBlank() },
                                card.bankName.takeIf { it.isNotBlank() },
                                card.lastFour.takeIf { it.isNotBlank() }?.let { "•••• $it" }
                            ).joinToString(" • ")
                        } ?: "Selecione o cartão"
                        OutlinedTextField(
                            value = cardLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Cartão") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cardExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        DropdownMenu(expanded = cardExpanded, onDismissRequest = { cardExpanded = false }) {
                            cards.forEach { card ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            listOfNotNull(
                                                card.nickname?.takeIf { it.isNotBlank() },
                                                card.bankName.takeIf { it.isNotBlank() },
                                                card.lastFour.takeIf { it.isNotBlank() }?.let { "•••• $it" }
                                            ).joinToString(" • ")
                                        )
                                    },
                                    onClick = { cardId = card.id; cardExpanded = false }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = installmentText,
                        onValueChange = { raw -> installmentText = raw.filter(Char::isDigit).take(2) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("Parcelas") },
                        supportingText = {
                            if (installments > 1 && amount > 0.0) {
                                Text("$installments x de ${BrlMoney.formatDigits(BrlMoney.fromDouble(amount / installments))}")
                            } else {
                                Text("1 = à vista no cartão")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    ExposedDropdownMenuBox(expanded = accountExpanded, onExpandedChange = { accountExpanded = it }) {
                        OutlinedTextField(
                            value = accounts.firstOrNull { it.id == accountId }?.institutionName?.takeIf { it.isNotBlank() } ?: "Sem conta vinculada",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Conta") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        DropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                            DropdownMenuItem(text = { Text("Sem conta vinculada") }, onClick = { accountId = null; accountExpanded = false })
                            accounts.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text(account.institutionName.takeIf { it.isNotBlank() } ?: account.accountName.orEmpty()) },
                                    onClick = { accountId = account.id; accountExpanded = false }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    saving = true
                    val effectiveType = if (isCard) if (cardRefund) "credit" else "debit" else type
                    onConfirm(
                        initial.copy(
                            description = description.trim(),
                            amount = amount,
                            type = effectiveType,
                            categoryId = categoryId,
                            accountId = if (isCard) null else accountId,
                            paymentMethod = if (isCard) "card" else "account",
                            cardId = if (isCard) cardId else null,
                            installmentCount = if (isCard) installments else 1,
                            firstChargeDate = if (isCard) initial.date else null,
                            cardRefund = isCard && cardRefund
                        )
                    ) { success -> saving = false; if (success) onDismiss() }
                }
            ) { Text(if (saving) "Salvando..." else "Confirmar e lançar") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancelar") } }
    )
}
