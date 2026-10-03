package com.financeapp.mobile.ui.home

import android.util.Base64
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financeapp.mobile.data.local.AccountEntity
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.BudgetDto
import com.financeapp.mobile.data.remote.CategoryDto
import com.financeapp.mobile.data.remote.CreditCardDto
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

internal data class BudgetAlertUi(
    val categoryName: String,
    val spent: Double,
    val limit: Double,
    val percentage: Double
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MonthlyTransactionsScreen(
    accounts: List<AccountEntity>,
    cards: List<CreditCardDto>,
    categories: List<CategoryDto>,
    budgets: List<BudgetDto>,
    pendingSyncCount: Int,
    offlineSyncing: Boolean,
    transactions: List<TransactionEntity>,
    allTransactions: List<TransactionEntity>,
    filters: TransactionFilters,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    highlightedTransactionId: Int?,
    oldestFirstRequest: Int = 0,
    onFiltersChange: (TransactionFilters) -> Unit,
    onOpenPlanning: () -> Unit,
    onOpenMonthlySummary: () -> Unit,
    onAddManualTransaction: (String) -> Unit,
    onAddCardPurchase: (Int?) -> Unit,
    onRequestAddCard: () -> Unit,
    onOpenDetails: (TransactionEntity) -> Unit,
    onDelete: (TransactionEntity) -> Unit,
    onDeleteBulk:
        (List<TransactionEntity>) -> Unit,
    onUpdateCategoryBulk:
        (List<TransactionEntity>, Int?) -> Unit,
    onUpdateAccountBulk:
        (List<TransactionEntity>, Int?) -> Unit
) {

    val visibleCards = remember(cards) {
        cards.filter { card ->
            card.active
        }
    }

    val locale = Locale("pt", "BR")
    val currency = remember {
        NumberFormat.getCurrencyInstance(locale)
    }

    var showFilters by remember {
        mutableStateOf(false)
    }

    var dateSortOrder by remember {
        mutableStateOf(DateSortOrder.NEWEST_FIRST)
    }

    LaunchedEffect(oldestFirstRequest) {
        if (oldestFirstRequest > 0) {
            dateSortOrder = DateSortOrder.OLDEST_FIRST
        }
    }

    var showSortMenu by remember {
        mutableStateOf(false)
    }
    var showAddTransactionMenu by remember {
        mutableStateOf(false)
    }

    var transactionViewTab by remember {
        mutableStateOf(TransactionViewTab.EXPENSE)
    }

    var quickCardId by remember {
        mutableStateOf<Int?>(null)
    }

    var selectedTransactionIds by remember {
        mutableStateOf<Set<Int>>(
            emptySet()
        )
    }

    var showBulkDeleteDialog by remember {
        mutableStateOf(false)
    }

    var showBulkCategoryDialog by remember {
        mutableStateOf(false)
    }

    var showBulkAccountDialog by remember {
        mutableStateOf(false)
    }

    val selectionMode =
        selectedTransactionIds.isNotEmpty()

    fun toggleSelection(
        transactionId: Int
    ) {
        selectedTransactionIds =
            if (
                transactionId in
                selectedTransactionIds
            ) {
                selectedTransactionIds -
                    transactionId
            } else {
                selectedTransactionIds +
                    transactionId
            }
    }

    fun clearSelection() {
        selectedTransactionIds =
            emptySet()
    }

    val scopedTransactions = remember(
        transactions,
        transactionViewTab,
        quickCardId
    ) {
        transactions.filter { tx ->
            when (transactionViewTab) {
                // Compras no cartão ficam somente em Cartões/Fatura. Em Despesas
                // entra apenas a saída real quando a fatura é paga.
                TransactionViewTab.EXPENSE -> tx.source != "card_purchase" && !(tx.cardId != null && tx.source != "card_payment")

                TransactionViewTab.CARD ->
                    !isPlannedDebtTransaction(tx) &&
                    tx.source != "card_payment" && (
                        tx.cardId != null ||
                            tx.installmentGroup != null
                        ) &&
                        (
                            quickCardId == null ||
                                tx.cardId == quickCardId
                            )
            }
        }
    }

    val scopedAllTransactions = remember(
        allTransactions,
        transactionViewTab,
        quickCardId
    ) {
        allTransactions.filter { tx ->
            when (transactionViewTab) {
                // Compras no cartão ficam somente em Cartões/Fatura. Em Despesas
                // entra apenas a saída real quando a fatura é paga.
                TransactionViewTab.EXPENSE -> tx.source != "card_purchase" && !(tx.cardId != null && tx.source != "card_payment")

                TransactionViewTab.CARD ->
                    !isPlannedDebtTransaction(tx) &&
                    tx.source != "card_payment" && (
                        tx.cardId != null ||
                            tx.installmentGroup != null
                        ) &&
                        (
                            quickCardId == null ||
                                tx.cardId == quickCardId
                            )
            }
        }
    }

    // V3.25.8 - conciliacao inteligente: sinaliza possiveis duplicidades
    // entre uma movimentacao importada pelo Open Finance e outra ja registrada.
    // Nao exclui nem altera dados automaticamente: apenas chama a atencao do usuario.
    val possibleDuplicatePairs = remember(allTransactions) {
        val pairs = mutableMapOf<Int, Int>()
        val candidates = allTransactions.filter { tx ->
            tx.source != "card_purchase" && tx.source != "card_payment"
        }

        candidates.forEachIndexed { index, first ->
            for (second in candidates.drop(index + 1)) {
                val differentOrigins =
                    (first.source == "open_finance") != (second.source == "open_finance")
                if (!differentOrigins) continue
                if (kotlin.math.abs(first.amount - second.amount) > 0.009) continue
                if (first.accountId != null && second.accountId != null && first.accountId != second.accountId) continue

                val firstDate = transactionsParseTxDate(first.date) ?: continue
                val secondDate = transactionsParseTxDate(second.date) ?: continue
                if (kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(firstDate, secondDate)) > 2L) continue

                pairs.putIfAbsent(first.id, second.id)
                pairs.putIfAbsent(second.id, first.id)
            }
        }
        pairs.toMap()
    }
    val possibleDuplicateIds = possibleDuplicatePairs.keys
    var duplicateToCompare by remember { mutableStateOf<TransactionEntity?>(null) }
    var duplicateCounterpart by remember { mutableStateOf<TransactionEntity?>(null) }
    var confirmReconciliation by remember { mutableStateOf(false) }

    fun openDuplicateComparison(tx: TransactionEntity) {
        val counterpartId = possibleDuplicatePairs[tx.id] ?: return
        val counterpart = allTransactions.firstOrNull { it.id == counterpartId } ?: return
        duplicateToCompare = tx
        duplicateCounterpart = counterpart
    }

    if (duplicateToCompare != null && duplicateCounterpart != null) {
        val first = duplicateToCompare!!
        val second = duplicateCounterpart!!
        val manual = if (first.source == "open_finance") second else first
        val automatic = if (first.source == "open_finance") first else second
        AlertDialog(
            onDismissRequest = {
                duplicateToCompare = null
                duplicateCounterpart = null
            },
            title = { Text("Comparar movimentações") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DuplicateComparisonBlock("Importada / automática", automatic, accounts)
                    HorizontalDivider()
                    DuplicateComparisonBlock("Cadastrada manualmente", manual, accounts)
                    Text(
                        "Se forem a mesma operação, o FinanceApp manterá a movimentação importada e removerá somente a cópia manual após sua confirmação.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(onClick = { confirmReconciliation = true }) {
                    Text("São a mesma movimentação")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    duplicateToCompare = null
                    duplicateCounterpart = null
                }) { Text("Manter as duas") }
            }
        )

        if (confirmReconciliation) {
            AlertDialog(
                onDismissRequest = { confirmReconciliation = false },
                title = { Text("Confirmar conciliação") },
                text = {
                    Text("A movimentação automática será mantida e a duplicata manual será excluída. Essa exclusão seguirá a sincronização normal do FinanceApp.")
                },
                confirmButton = {
                    Button(onClick = {
                        onDelete(manual)
                        confirmReconciliation = false
                        duplicateToCompare = null
                        duplicateCounterpart = null
                    }) { Text("Confirmar") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmReconciliation = false }) { Text("Cancelar") }
                }
            )
        }
    }

    val scope = rememberCoroutineScope()

    val hasPeriodFilter =
        filters.startDate != null ||
            filters.endDate != null

    val hasSpecificDateFilter =
        filters.specificDate != null

    val showAllMode =
        filters.showAllTransactions

    val hasSearchQuery =
        searchQuery.isNotBlank()

    val consolidatedMode =
        hasPeriodFilter ||
            hasSpecificDateFilter ||
            showAllMode ||
            hasSearchQuery

    val normalizedSearch =
        remember(searchQuery) {
            normalizeSearchText(
                searchQuery
            )
        }

    val cardSearchMatches =
        remember(
            normalizedSearch,
            cards
        ) {
            if (
                normalizedSearch.isBlank()
            ) {
                false
            } else {
                val explicitCardWord =
                    normalizedSearch.contains(
                        "cartao"
                    ) ||
                        normalizedSearch.contains(
                            "parcela"
                        ) ||
                        normalizedSearch.contains(
                            "credito"
                        )

                explicitCardWord ||
                    cards.any { card ->
                        listOf(
                            card.bankName,
                            card.brand,
                            card.lastFour,
                            card.nickname.orEmpty()
                        ).any { value ->
                            normalizeSearchText(
                                value
                            ).contains(
                                normalizedSearch
                            )
                        }
                    }
            }
        }

    LaunchedEffect(
        cardSearchMatches,
        searchQuery
    ) {
        if (
            cardSearchMatches &&
            transactionViewTab ==
            TransactionViewTab.EXPENSE
        ) {
            transactionViewTab =
                TransactionViewTab.CARD

            quickCardId = null

            if (filters.cardId != null) {
                onFiltersChange(
                    filters.copy(
                        cardId = null
                    )
                )
            }
        }
    }

    /*
     * Pesquisa global.
     */
    val searchFilteredTransactions = remember(
        scopedTransactions,
        scopedAllTransactions,
        accounts,
        categories,
        cards,
        searchQuery
    ) {
        if (!hasSearchQuery) {
            scopedTransactions
        } else {
            // Pesquisa global: quando há texto, procura em todos os meses disponíveis,
            // sem ficar presa ao mês atualmente selecionado no carrossel.
            scopedAllTransactions.filter { tx ->
                val account = accounts.firstOrNull {
                    it.id == tx.accountId
                }

                val categoryName = categories.firstOrNull {
                    it.id == tx.categoryId
                }?.name

                val card = visibleCards.firstOrNull {
                    it.id == tx.cardId
                }

                transactionMatchesSearch(
                    tx = tx,
                    account = account,
                    categoryName = categoryName,
                    card = card,
                    rawQuery = searchQuery
                )
            }
        }
    }

    /*
     * Faixa de meses da visualização normal.
     */
    val monthRange = remember(scopedAllTransactions) {
        val current = YearMonth.now()

        val parsed = scopedAllTransactions
            .mapNotNull {
                transactionsParseTxDate(it.date)
                    ?.let(YearMonth::from)
            }

        val oldestWithData =
            parsed.minOrNull()

        val newestWithData =
            parsed.maxOrNull()

        val defaultOldest =
            current.minusMonths(11)

        val oldest =
            if (
                oldestWithData != null &&
                oldestWithData.isBefore(defaultOldest)
            ) {
                oldestWithData
            } else {
                defaultOldest
            }

        val newest =
            if (
                newestWithData != null &&
                newestWithData.isAfter(current)
            ) {
                newestWithData
            } else {
                current
            }

        buildList {
            var cursor = oldest

            while (!cursor.isAfter(newest)) {
                add(cursor)
                cursor = cursor.plusMonths(1)
            }
        }
    }

    val currentMonthIndex =
        monthRange
            .indexOf(YearMonth.now())
            .let {
                if (it >= 0) it
                else monthRange.lastIndex
            }

    val pagerState = rememberPagerState(
        initialPage = currentMonthIndex
    ) {
        monthRange.size
    }

    val monthTabsState =
        rememberLazyListState()

    val selectedMonth =
        monthRange
            .getOrNull(pagerState.currentPage)
            ?: YearMonth.now()

    /*
     * Se o usuário cria manualmente uma transação
     * em outro mês, abre automaticamente aquele mês.
     */
    LaunchedEffect(
        highlightedTransactionId,
        allTransactions,
        monthRange,
        transactionViewTab
    ) {
        if (!consolidatedMode) {
            val highlighted =
                allTransactions.firstOrNull {
                    it.id ==
                        highlightedTransactionId
                }

            if (highlighted != null) {
                // Ao editar uma transação, apenas navegue até o novo mês.
                // Não force a aba Cartões: compras de cartão também pertencem
                // à visão geral de Despesas e devem continuar visíveis nela.

                val highlightedMonth =
                    parseTxDate(
                        highlighted.date
                    )?.let(
                        YearMonth::from
                    )

                if (highlightedMonth != null) {
                    val target =
                        monthRange.indexOf(
                            highlightedMonth
                        )

                    if (
                        target >= 0 &&
                        target !=
                        pagerState.currentPage
                    ) {
                        pagerState.scrollToPage(
                            target
                        )
                    }
                }
            }
        }
    }

    /*
     * Lista mensal usada no resumo.
     */
    val monthTransactions = remember(
        scopedTransactions,
        selectedMonth,
        dateSortOrder,
        highlightedTransactionId
    ) {
        val sorted =
            scopedTransactions
                .filter {
                    transactionsParseTxDate(it.date)
                        ?.let(YearMonth::from) ==
                        selectedMonth
                }
                .sortedWith(
                    compareBy<TransactionEntity> {
                        transactionsParseTxDate(it.date)
                            ?: LocalDate.MIN
                    }.let { comparator ->
                        if (
                            dateSortOrder ==
                            DateSortOrder.NEWEST_FIRST
                        ) {
                            comparator.reversed()
                        } else {
                            comparator
                        }
                    }
                )

        val highlighted =
            sorted.firstOrNull {
                it.id ==
                    highlightedTransactionId
            }

        if (highlighted != null) {
            listOf(highlighted) +
                sorted.filter {
                    it.id !=
                        highlightedTransactionId
                }
        } else {
            sorted
        }
    }

    /*
     * Lista consolidada usada em:
     * pesquisa, período, data e todas.
     */
    val consolidatedTransactions = remember(
        searchFilteredTransactions,
        dateSortOrder,
        highlightedTransactionId
    ) {
        val sorted =
            searchFilteredTransactions
                .sortedWith(
                    compareBy<TransactionEntity> {
                        transactionsParseTxDate(it.date)
                            ?: LocalDate.MIN
                    }.let { comparator ->
                        if (
                            dateSortOrder ==
                            DateSortOrder.NEWEST_FIRST
                        ) {
                            comparator.reversed()
                        } else {
                            comparator
                        }
                    }
                )

        val highlighted =
            sorted.firstOrNull {
                it.id ==
                    highlightedTransactionId
            }

        if (highlighted != null) {
            listOf(highlighted) +
                sorted.filter {
                    it.id !=
                        highlightedTransactionId
                }
        } else {
            sorted
        }
    }

    val summaryTransactions =
        if (consolidatedMode) {
            consolidatedTransactions
        } else {
            monthTransactions
        }

    val cashSummaryTransactions = summaryTransactions.filter { transactionStatusUi(it)?.label !in setOf("Pendente", "Vencida") }

    val income =
        cashSummaryTransactions
            .filter {
                it.amount > 0
            }
            .sumOf {
                it.amount
            }

    val expenses =
        cashSummaryTransactions
            .filter {
                it.amount < 0
            }
            .sumOf {
                abs(it.amount)
            }

    val balance =
        income - expenses

    val formatter =
        DateTimeFormatter.ofPattern(
            "dd/MM/yyyy"
        )

    val periodLabel =
        when {
            filters.startDate != null &&
                filters.endDate != null ->
                "${filters.startDate.format(formatter)} a " +
                    filters.endDate.format(formatter)

            filters.startDate != null ->
                "A partir de ${
                    filters.startDate.format(formatter)
                }"

            filters.endDate != null ->
                "Até ${
                    filters.endDate.format(formatter)
                }"

            else -> ""
        }

    val summaryTitle =
        when {
            hasSearchQuery ->
                "Resultados da pesquisa"

            showAllMode ->
                "Todas as movimentações"

            hasSpecificDateFilter ->
                "Movimentação do dia"

            hasPeriodFilter ->
                "Movimentação do período"

            else ->
                if (selectedMonth == YearMonth.now()) {
                    "Movimentação do mês vigente • ${selectedMonth.year}"
                } else {
                    "Movimentação de ${
                        selectedMonth.month
                            .getDisplayName(
                                TextStyle.FULL,
                                locale
                            )
                            .replaceFirstChar { it.uppercase() }
                    } • ${selectedMonth.year}"
                }
        }

    val budgetAlerts = remember(
        budgets,
        categories,
        scopedAllTransactions
    ) {
        val month = YearMonth.now()

        budgets.mapNotNull { budget ->
            val category = categories.firstOrNull {
                it.id == budget.categoryId
            } ?: return@mapNotNull null

            if (budget.amount <= 0.0) {
                return@mapNotNull null
            }

            val spent = scopedAllTransactions
                .asSequence()
                .filter {
                    it.categoryId == budget.categoryId &&
                        it.amount < 0 &&
                        transactionsParseTxDate(it.date)
                            ?.let(YearMonth::from) == month
                }
                .sumOf { abs(it.amount) }

            val percentage =
                (spent / budget.amount) * 100.0

            if (percentage >= 80.0) {
                BudgetAlertUi(
                    categoryName = category.name,
                    spent = spent,
                    limit = budget.amount,
                    percentage = percentage
                )
            } else {
                null
            }
        }.sortedByDescending {
            it.percentage
        }
    }

    val currentVisibleForSelection =
        if (consolidatedMode) {
            consolidatedTransactions
        } else {
            monthTransactions
        }

    val selectedTransactions =
        remember(
            allTransactions,
            selectedTransactionIds
        ) {
            allTransactions.filter {
                it.id in
                    selectedTransactionIds
            }
        }

    val bulkAffectedTransactions =
        remember(
            allTransactions,
            selectedTransactions
        ) {
            val selectedGroups =
                selectedTransactions
                    .mapNotNull {
                        transaction ->
                        transaction
                            .installmentGroup
                            ?.takeIf {
                                (
                                    transaction
                                        .installmentTotal
                                        ?: 1
                                ) > 1
                            }
                    }
                    .toSet()

            allTransactions.filter {
                    transaction ->
                transaction.id in
                    selectedTransactionIds ||
                    (
                        transaction
                            .installmentGroup !=
                            null &&
                            transaction
                                .installmentGroup in
                                selectedGroups
                    )
            }
        }


    /*
     * UMA ÚNICA estrutura superior:
     *
     * 1) resumo
     * 2) pesquisa + filtro + ordenação
     * 3) conteúdo mensal ou consolidado
     *
     * O TextField nunca é destruído ao pesquisar.
     */
    var commandsExpanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 10.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(10.dp)
    ) {
        if (selectionMode) {
            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            MaterialTheme
                                .colorScheme
                                .secondaryContainer
                    )
            ) {
                Column(
                    modifier =
                        Modifier.padding(
                            horizontal = 8.dp,
                            vertical = 4.dp
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            2.dp
                        )
                ) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Text(
                            "${
                                selectedTransactionIds.size
                            } selecionada${
                                if (
                                    selectedTransactionIds.size ==
                                    1
                                ) "" else "s"
                            }",
                            style =
                                MaterialTheme
                                    .typography
                                    .titleSmall,
                            fontWeight =
                                FontWeight.SemiBold,
                            modifier =
                                Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = {
                                clearSelection()
                            }
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription =
                                    "Cancelar seleção"
                            )
                        }
                    }

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                6.dp
                            )
                    ) {
                        TextButton(
                            onClick = {
                                val visibleIds =
                                    currentVisibleForSelection
                                        .map {
                                            it.id
                                        }
                                        .toSet()

                                selectedTransactionIds =
                                    if (
                                        visibleIds.isNotEmpty() &&
                                        visibleIds.all {
                                            it in
                                                selectedTransactionIds
                                        }
                                    ) {
                                        selectedTransactionIds -
                                            visibleIds
                                    } else {
                                        selectedTransactionIds +
                                            visibleIds
                                    }
                            },
                            modifier =
                                Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default
                                    .SelectAll,
                                contentDescription =
                                    null
                            )
                            Spacer(
                                Modifier.width(
                                    4.dp
                                )
                            )
                            Text("Selecionar tudo")
                        }

                        TextButton(
                            onClick = {
                                showBulkCategoryDialog =
                                    true
                            },
                            modifier =
                                Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default
                                    .Category,
                                contentDescription =
                                    null
                            )
                            Spacer(
                                Modifier.width(
                                    4.dp
                                )
                            )
                            Text("Categoria")
                        }

                        TextButton(
                            onClick = {
                                showBulkAccountDialog =
                                    true
                            },
                            modifier =
                                Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default
                                    .AccountBalance,
                                contentDescription =
                                    null
                            )
                            Spacer(
                                Modifier.width(
                                    4.dp
                                )
                            )
                            Text("Banco")
                        }

                        TextButton(
                            onClick = {
                                showBulkDeleteDialog =
                                    true
                            },
                            modifier =
                                Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription =
                                    null
                            )
                            Spacer(
                                Modifier.width(
                                    4.dp
                                )
                            )
                            Text("Excluir")
                        }
                    }
                }
            }
        }

        if (!selectionMode) {
            TransactionCommandsToggle(commandsExpanded) { commandsExpanded = !commandsExpanded }
            if (commandsExpanded) {
                Column(Modifier.fillMaxWidth().heightIn(max=300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF1677F0),
                            Color(0xFF6D5AF5)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Text(
                    summaryTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                when {
                    hasSearchQuery -> {
                        Text(
                            "Busca: “${
                                searchQuery.trim()
                            }”",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                    }

                    hasSpecificDateFilter -> {
                        Text(
                            filters
                                .specificDate!!
                                .format(formatter),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                    }

                    hasPeriodFilter -> {
                        Text(
                            periodLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                    }
                }

                Spacer(
                    Modifier.height(6.dp)
                )

                if (transactionViewTab == TransactionViewTab.CARD) {
                    Text(
                        "Total de compras",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.82f)
                    )
                }
                Text(
                    currency.format(
                        if (transactionViewTab == TransactionViewTab.CARD) expenses else balance
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(
                    Modifier.height(4.dp)
                )

                if (transactionViewTab == TransactionViewTab.CARD) {
                    // Cartão não tem conceito de entrada/saída nesta visão: aqui
                    // mostramos o consumo da fatura. O pagamento da fatura pertence
                    // à conta bancária e não deve duplicar a despesa da compra.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Compras", color = Color.White.copy(alpha = 0.78f))
                            Text(
                                summaryTransactions.count { it.amount < 0 }.toString(),
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Visão", color = Color.White.copy(alpha = 0.78f))
                            Text(
                                if (quickCardId == null) "Todos os cartões" else "Cartão selecionado",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            val incomeAccent = Color(0xFF8FF0B2)
                            Text(
                                "Entradas",
                                color = incomeAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                currency.format(income),
                                fontWeight = FontWeight.ExtraBold,
                                color = incomeAccent
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            val expenseAccent = Color(0xFFFFA5AE)
                            Text(
                                "Saídas",
                                color = expenseAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                currency.format(expenses),
                                fontWeight = FontWeight.ExtraBold,
                                color = expenseAccent
                            )
                        }
                    }
                }


            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                onClick = onOpenPlanning,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFEAF2FF),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFD8E8FF)
                    ) {
                        Icon(
                            Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = Color(0xFF2563EB),
                            modifier = Modifier.padding(8.dp).size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Planejamento",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1F2937)
                    )
                }
            }

            Surface(
                onClick = onOpenMonthlySummary,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFF2ECFF),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFE7DCFF)
                    ) {
                        Icon(
                            Icons.Default.BarChart,
                            contentDescription = null,
                            tint = Color(0xFF6D4CEB),
                            modifier = Modifier.padding(8.dp).size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Resumo",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1F2937)
                    )
                }
            }
        }

        if (budgetAlerts.isNotEmpty()) {
            val alert = budgetAlerts.first()
            val critical =
                alert.percentage >= 100.0
            val alertCurrency = remember {
                NumberFormat.getCurrencyInstance(
                    Locale("pt", "BR")
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor =
                        if (critical) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 10.dp,
                            vertical = 6.dp
                        ),
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Icon(
                        if (critical) {
                            Icons.Default.Warning
                        } else {
                            Icons.Default.NotificationsActive
                        },
                        contentDescription = null
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        "${alert.categoryName} · ${
                            alert.percentage.toInt()
                        }% · ${
                            alertCurrency.format(alert.spent)
                        } de ${
                            alertCurrency.format(alert.limit)
                        }",
                        modifier = Modifier.weight(1f),
                        style =
                            MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )

                    if (budgetAlerts.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "+${budgetAlerts.size - 1}",
                            style =
                                MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }


                }
            }
        /*
         * Pesquisa logo ABAIXO do resumo.
         * Continua sendo uma única instância.
         */
        if (
            transactionViewTab ==
            TransactionViewTab.CARD
        ) {
            if (visibleCards.isEmpty()) {
                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CreditCard,
                            contentDescription = null
                        )
                        Spacer(
                            Modifier.width(10.dp)
                        )
                        Column(
                            modifier =
                                Modifier.weight(1f)
                        ) {
                            Text(
                                "Cadastre um cartão para prosseguir",
                                fontWeight =
                                    FontWeight.SemiBold
                            )
                            Text(
                                "Depois você poderá lançar compras à vista ou parceladas.",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall
                            )
                        }
                        TextButton(
                            onClick =
                                onRequestAddCard
                        ) {
                            Text("Adicionar cartão")
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = {
                        onAddCardPurchase(
                            quickCardId
                                ?: cards
                                    .firstOrNull {
                                        it.active
                                    }?.id
                        )
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    contentPadding =
                        PaddingValues(
                            horizontal = 12.dp,
                            vertical = 7.dp
                        )
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null
                    )
                    Spacer(
                        Modifier.width(6.dp)
                    )
                    Text("Nova compra de cartão")
                }
            }
        } else {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        onAddManualTransaction(
                            "debit"
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp
                    )
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null
                    )
                    Spacer(
                        Modifier.width(6.dp)
                    )
                    Text("Nova despesa")
                }
            }
        }


        Row(
            modifier =
                Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            TransactionSearchField(
                query = searchQuery,
                onQueryChange =
                    onSearchQueryChange,
                modifier =
                    Modifier.weight(1f)
            )

            Spacer(
                Modifier.width(6.dp)
            )

            FilledIconButton(
                onClick = {
                    showFilters = true
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(0xFFF3F6FA),
                    contentColor = Color(0xFF123B6D)
                )
            ) {
                Icon(
                    Icons.Default.FilterList,
                    contentDescription =
                        "Filtros"
                )
            }

            Spacer(
                Modifier.width(6.dp)
            )

            Box {
                FilledIconButton(
                    onClick = {
                        showSortMenu = true
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFFF3F6FA),
                        contentColor = Color(0xFF123B6D)
                    )
                ) {
                    Icon(
                        if (
                            dateSortOrder ==
                            DateSortOrder
                                .NEWEST_FIRST
                        ) {
                            Icons.Default
                                .ArrowDownward
                        } else {
                            Icons.Default
                                .ArrowUpward
                        },
                        contentDescription =
                            "Ordenar por data"
                    )
                }

                DropdownMenu(
                    expanded =
                        showSortMenu,
                    onDismissRequest = {
                        showSortMenu = false
                    }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Mais recentes primeiro"
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default
                                    .ArrowDownward,
                                null
                            )
                        },
                        onClick = {
                            dateSortOrder =
                                DateSortOrder
                                    .NEWEST_FIRST

                            showSortMenu =
                                false
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                "Mais antigas primeiro"
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default
                                    .ArrowUpward,
                                null
                            )
                        },
                        onClick = {
                            dateSortOrder =
                                DateSortOrder
                                    .OLDEST_FIRST

                            showSortMenu =
                                false
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(6.dp)
        ) {
            TransactionViewTab.entries.forEach { tab ->
                FilterChip(
                    selected = transactionViewTab == tab,
                    onClick = {
                        transactionViewTab = tab
                        if (tab != TransactionViewTab.CARD) {
                            quickCardId = null
                        }
                    },
                    label = {
                        Text(
                            tab.label,
                            fontWeight = if (transactionViewTab == tab) FontWeight.SemiBold else FontWeight.Medium
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        selectedContainerColor = Color(0xFFE8F1FF),
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedLabelColor = Color(0xFF1267D6),
                        iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedLeadingIconColor = Color(0xFF1267D6)
                    )
                )
            }
        }

        if (
            transactionViewTab ==
            TransactionViewTab.CARD
        ) {
            val cardsWithTransactions =
                visibleCards.filter { card ->
                    allTransactions.any {
                        it.cardId == card.id
                    }
                }

            LazyRow(
                horizontalArrangement =
                    Arrangement.spacedBy(7.dp),
                contentPadding =
                    PaddingValues(end = 8.dp)
            ) {
                item {
                    FilterChip(
                        selected = quickCardId == null,
                        onClick = { quickCardId = null },
                        label = { Text("Todos") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xFFF8FAFD),
                            selectedContainerColor = Color(0xFFE8F1FF),
                            labelColor = Color(0xFF536274),
                            selectedLabelColor = Color(0xFF123B6D)
                        )
                    )
                }

                items(
                    items = cardsWithTransactions,
                    key = {
                        "quick-card-${it.id}"
                    }
                ) { card ->
                    FilterChip(
                        selected = quickCardId == card.id,
                        onClick = { quickCardId = card.id },
                        label = { Text("${card.bankName} •••• ${card.lastFour}") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xFFF8FAFD),
                            selectedContainerColor = Color(0xFFE8F1FF),
                            labelColor = Color(0xFF536274),
                            selectedLabelColor = Color(0xFF123B6D)
                        )
                    )
                }
            }
        }



        }

        /*
         * CONTEÚDO ABAIXO DA PESQUISA.
         */
        if (consolidatedMode) {
            val listState =
                rememberLazyListState()

            LaunchedEffect(
                filters,
                dateSortOrder,
                searchQuery,
                highlightedTransactionId,
                consolidatedTransactions
                    .map { it.id }
            ) {
                if (
                    consolidatedTransactions
                        .isNotEmpty()
                ) {
                    listState.scrollToItem(0)
                }
            }

            if (!selectionMode) {
            TransactionPeriodHeading(
                title = when {
                    hasSearchQuery ->
                        "Transações encontradas"

                    showAllMode ->
                        "Todas as transações"

                    hasSpecificDateFilter ->
                        "Transações do dia"

                    else ->
                        "Transações do período"
                },
                count = consolidatedTransactions.size
            )

            if (
                filters !=
                TransactionFilters()
            ) {
                AppliedFiltersRow(
                    filters = filters,
                    accounts = accounts,
                    cards = cards,
                    categories = categories,
                    transactionViewTab =
                        transactionViewTab,
                    onChange =
                        onFiltersChange
                )
            }

            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding =
                    PaddingValues(
                        bottom = 96.dp
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        10.dp
                    )
            ) {
                if (
                    consolidatedTransactions
                        .isEmpty()
                ) {
                    item {
                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFF8FAFD),
                                contentColor = Color(0xFF26364A)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))
                        ) {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            vertical =
                                                32.dp,
                                            horizontal =
                                                20.dp
                                        ),
                                horizontalAlignment =
                                    Alignment
                                        .CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default
                                        .ReceiptLong,
                                    contentDescription =
                                        null,
                                    modifier =
                                        Modifier
                                            .size(
                                                36.dp
                                            )
                                )

                                Spacer(
                                    Modifier
                                        .height(
                                            10.dp
                                        )
                                )

                                Text(
                                    when {
                                        hasSearchQuery ->
                                            "Nenhuma transação encontrada para essa pesquisa"

                                        showAllMode ->
                                            "Nenhuma transação encontrada"

                                        hasSpecificDateFilter ->
                                            "Nenhuma movimentação nesta data"

                                        else ->
                                            "Nenhuma movimentação neste período"
                                    },
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodyLarge,
                                    fontWeight =
                                        FontWeight
                                            .SemiBold
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items =
                            consolidatedTransactions,
                        key = {
                            tx -> tx.id
                        }
                    ) { tx ->
                        SwipeableTransactionRow(
                            tx = tx,
                            possibleDuplicate = tx.id in possibleDuplicateIds,
                            onCompareDuplicate = { openDuplicateComparison(tx) },
                            account =
                                accounts
                                    .firstOrNull {
                                        it.id ==
                                            tx.accountId
                                    },
                            categoryName = categories.firstOrNull {
                                it.id == tx.categoryId
                            }?.name,
                            categoryIcon = categories.firstOrNull {
                                it.id == tx.categoryId
                            }?.icon,
                            card = visibleCards.firstOrNull {
                                it.id == tx.cardId
                            },
                            highlighted =
                                tx.id ==
                                    highlightedTransactionId,
                            selected =
                                tx.id in
                                    selectedTransactionIds,
                            selectionMode =
                                selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    toggleSelection(
                                        tx.id
                                    )
                                } else {
                                    onOpenDetails(tx)
                                }
                            },
                            onLongClick = {
                                toggleSelection(
                                    tx.id
                                )
                            },
                            onDelete =
                                onDelete
                        )
                    }
                }
            }
        } else {
            LaunchedEffect(
                pagerState.currentPage,
                monthRange
            ) {
                if (
                    monthRange.isNotEmpty()
                ) {
                    val firstVisible =
                        (
                            pagerState
                                .currentPage - 2
                        )
                            .coerceAtLeast(0)

                    monthTabsState
                        .animateScrollToItem(
                            firstVisible
                        )
                }
            }

            if (!selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = {
                        val previousPage = (pagerState.currentPage - 1).coerceAtLeast(0)
                        if (previousPage != pagerState.currentPage) {
                            scope.launch { pagerState.animateScrollToPage(previousPage) }
                        }
                    },
                    enabled = pagerState.currentPage > 0
                ) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = "Mês anterior",
                        tint = if (pagerState.currentPage > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                    )
                }

                Text(
                    selectedMonth.month
                        .getDisplayName(TextStyle.FULL, locale)
                        .uppercase(locale),
                    modifier = Modifier.widthIn(min = 150.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                IconButton(
                    onClick = {
                        val nextPage = (pagerState.currentPage + 1).coerceAtMost(monthRange.lastIndex)
                        if (nextPage != pagerState.currentPage) {
                            scope.launch { pagerState.animateScrollToPage(nextPage) }
                        }
                    },
                    enabled = pagerState.currentPage < monthRange.lastIndex
                ) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Próximo mês",
                        tint = if (pagerState.currentPage < monthRange.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                    )
                }
            }

            if (
                filters !=
                TransactionFilters()
            ) {
                AppliedFiltersRow(
                    filters = filters,
                    accounts = accounts,
                    cards = cards,
                    categories = categories,
                    transactionViewTab =
                        transactionViewTab,
                    onChange =
                        onFiltersChange
                )
            }

            }

            HorizontalPager(
                state =
                    pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                beyondViewportPageCount =
                    1,
                key = {
                    page ->
                    monthRange[page]
                        .toString()
                }
            ) { page ->
                val pageMonth =
                    monthRange[page]

                val pageTransactions =
                    scopedTransactions
                        .filter {
                            parseTxDate(
                                it.date
                            )
                                ?.let(
                                    YearMonth::from
                                ) ==
                                pageMonth
                        }
                        .sortedWith(
                            compareBy<TransactionEntity> {
                                parseTxDate(
                                    it.date
                                )
                                    ?: LocalDate.MIN
                            }.let {
                                comparator ->
                                if (
                                    dateSortOrder ==
                                    DateSortOrder
                                        .NEWEST_FIRST
                                ) {
                                    comparator
                                        .reversed()
                                } else {
                                    comparator
                                }
                            }
                        )
                        .let { sorted ->
                            val highlighted =
                                sorted
                                    .firstOrNull {
                                        it.id ==
                                            highlightedTransactionId
                                    }

                            if (
                                highlighted != null
                            ) {
                                listOf(
                                    highlighted
                                ) +
                                    sorted.filter {
                                        it.id !=
                                            highlightedTransactionId
                                    }
                            } else {
                                sorted
                            }
                        }

                val pageListState =
                    rememberLazyListState()

                LaunchedEffect(
                    pagerState.currentPage,
                    filters,
                    dateSortOrder,
                    highlightedTransactionId,
                    pageTransactions
                        .map { it.id }
                ) {
                    if (
                        pagerState
                            .currentPage ==
                            page &&
                        pageTransactions
                            .isNotEmpty()
                    ) {
                        pageListState
                            .scrollToItem(0)
                    }
                }

                LazyColumn(
                    state =
                        pageListState,
                    modifier =
                        Modifier
                            .fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            bottom = 96.dp
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            10.dp
                        )
                ) {
                    if (
                        pageTransactions
                            .isEmpty()
                    ) {
                        item {
                            Card(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                            ) {
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                vertical =
                                                    32.dp,
                                                horizontal =
                                                    20.dp
                                            ),
                                    horizontalAlignment =
                                        Alignment
                                            .CenterHorizontally
                                ) {
                                    Icon(
                                        Icons.Default
                                            .ReceiptLong,
                                        contentDescription =
                                            null,
                                        modifier =
                                            Modifier
                                                .size(
                                                    36.dp
                                                )
                                    )

                                    Spacer(
                                        Modifier
                                            .height(
                                                10.dp
                                            )
                                    )

                                    Text(
                                        "Nenhuma movimentação neste mês",
                                        style =
                                            MaterialTheme
                                                .typography
                                                .bodyLarge,
                                        fontWeight =
                                            FontWeight
                                                .SemiBold
                                    )
                                }
                            }
                        }
                    } else {
                        items(
                            items =
                                pageTransactions,
                            key = {
                                tx -> tx.id
                            }
                        ) { tx ->
                            SwipeableTransactionRow(
                                tx = tx,
                                possibleDuplicate = tx.id in possibleDuplicateIds,
                                onCompareDuplicate = { openDuplicateComparison(tx) },
                                account =
                                    accounts
                                        .firstOrNull {
                                            it.id ==
                                                tx.accountId
                                        },
                                categoryName = categories.firstOrNull {
                                    it.id == tx.categoryId
                                }?.name,
                                categoryIcon = categories.firstOrNull {
                                    it.id == tx.categoryId
                                }?.icon,
                                card = visibleCards.firstOrNull {
                                    it.id == tx.cardId
                                },
                                highlighted =
                                    tx.id ==
                                        highlightedTransactionId,
                                selected =
                                    tx.id in
                                        selectedTransactionIds,
                                selectionMode =
                                    selectionMode,
                                onClick = {
                                    if (
                                        selectionMode
                                    ) {
                                        toggleSelection(
                                            tx.id
                                        )
                                    } else {
                                        onOpenDetails(
                                            tx
                                        )
                                    }
                                },
                                onLongClick = {
                                    toggleSelection(
                                        tx.id
                                    )
                                },
                                onDelete =
                                    onDelete
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBulkDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                showBulkDeleteDialog =
                    false
            },
            title = {
                Text(
                    "Excluir transações?"
                )
            },
            text = {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        )
                ) {
                    Text(
                        "${
                            selectedTransactions
                                .size
                        } transação${
                            if (
                                selectedTransactions
                                    .size ==
                                1
                            ) "" else "ões"
                        } selecionada${
                            if (
                                selectedTransactions
                                    .size ==
                                1
                            ) "" else "s"
                        }."
                    )

                    if (
                        bulkAffectedTransactions
                            .size >
                        selectedTransactions
                            .size
                    ) {
                        Text(
                            "Algumas pertencem a compras parceladas. " +
                                "As demais parcelas relacionadas também serão excluídas."
                        )
                    }

                    Text(
                        "Total afetado: ${
                            bulkAffectedTransactions
                                .size
                        } transação${
                            if (
                                bulkAffectedTransactions
                                    .size ==
                                1
                            ) "" else "ões"
                        }.",
                        fontWeight =
                            FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteBulk(
                            selectedTransactions
                        )
                        showBulkDeleteDialog =
                            false
                        clearSelection()
                    },
                    colors =
                        ButtonDefaults
                            .buttonColors(
                                containerColor =
                                    MaterialTheme
                                        .colorScheme
                                        .error,
                                contentColor =
                                    MaterialTheme
                                        .colorScheme
                                        .onError
                            )
                ) {
                    Text("Excluir")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBulkDeleteDialog =
                            false
                    }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showBulkCategoryDialog) {
        BulkCategoryDialog(
            categories = categories,
            affectedCount =
                bulkAffectedTransactions
                    .size,
            onDismiss = {
                showBulkCategoryDialog =
                    false
            },
            onApply = {
                    categoryId ->
                onUpdateCategoryBulk(
                    selectedTransactions,
                    categoryId
                )
                showBulkCategoryDialog =
                    false
                clearSelection()
            }
        )
    }

    if (showBulkAccountDialog) {
        BulkAccountDialog(
            accounts = accounts,
            affectedCount =
                bulkAffectedTransactions.size,
            onDismiss = {
                showBulkAccountDialog = false
            },
            onApply = { accountId ->
                onUpdateAccountBulk(
                    selectedTransactions,
                    accountId
                )
                showBulkAccountDialog = false
                clearSelection()
            }
        )
    }

    if (showFilters) {
        FilterDialog(
            accounts = accounts,
            cards = visibleCards,
            categories = categories,
            transactionViewTab =
                transactionViewTab,
            filters =
                if (
                    transactionViewTab ==
                    TransactionViewTab.EXPENSE
                ) {
                    filters.copy(
                        cardId = null
                    )
                } else {
                    filters
                },
            onDismiss = {
                showFilters = false
            },
            onApply = {
                onFiltersChange(it)
                showFilters = false
            }
        )
    }
}

@Composable
private fun MonthTransactionsList(
    accounts: List<AccountEntity>,
    categories: List<CategoryDto>,
    transactions: List<TransactionEntity>,
    onDelete: (TransactionEntity) -> Unit,
    possibleDuplicateIds: Set<Int> = emptySet()
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (transactions.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.ReceiptLong,
                        null,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Nenhuma movimentação neste mês")
                }
            }
        } else {
            transactions.forEach { tx ->
                key(tx.id) {
                    SwipeableTransactionRow(
                        tx = tx,
                        possibleDuplicate = tx.id in possibleDuplicateIds,
                        account = accounts.firstOrNull {
                            it.id == tx.accountId
                        },
                        categoryName = categories.firstOrNull {
                            it.id == tx.categoryId
                        }?.name,
                        categoryIcon = categories.firstOrNull {
                            it.id == tx.categoryId
                        }?.icon,
                        onClick = {},
                        onLongClick = {
                            onDelete(tx)
                        },
                        onDelete = onDelete
                    )
                }
            }
        }
    }
}


@Composable
private fun BulkAccountDialog(
    accounts: List<AccountEntity>,
    affectedCount: Int,
    onDismiss: () -> Unit,
    onApply: (Int?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Relacionar banco") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "O banco/conta será aplicado a $affectedCount transação${if (affectedCount == 1) "" else "ões"}. " +
                        "Parcelas da mesma compra recebem o mesmo banco."
                )
                HorizontalDivider()
                TextButton(
                    onClick = { onApply(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sem banco/conta")
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    items(items = accounts, key = { it.id }) { account ->
                        TextButton(
                            onClick = { onApply(account.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                account.institutionName,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun BulkCategoryDialog(
    categories: List<CategoryDto>,
    affectedCount: Int,
    onDismiss: () -> Unit,
    onApply: (Int?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Alterar categoria"
            )
        },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        8.dp
                    )
            ) {
                Text(
                    "A categoria será aplicada a $affectedCount transação${
                        if (affectedCount == 1) "" else "ões"
                    }. Parcelas da mesma compra recebem a mesma categoria."
                )

                HorizontalDivider()

                TextButton(
                    onClick = {
                        onApply(null)
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text("Sem categoria")
                }

                LazyColumn(
                    modifier =
                        Modifier.heightIn(
                            max = 360.dp
                        )
                ) {
                    items(
                        items = categories,
                        key = {
                            category ->
                            category.id
                        }
                    ) { category ->
                        TextButton(
                            onClick = {
                                onApply(
                                    category.id
                                )
                            },
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            Text(
                                category.name,
                                modifier =
                                    Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun AppliedFiltersRow(
    filters: TransactionFilters,
    accounts: List<AccountEntity>,
    cards: List<CreditCardDto>,
    categories: List<CategoryDto>,
    transactionViewTab:
        TransactionViewTab,
    onChange: (TransactionFilters) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            AssistChip(
                onClick = { onChange(TransactionFilters()) },
                label = { Text("Limpar filtros") },
                leadingIcon = { Icon(Icons.Default.Clear, null) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Color(0xFFF8FAFD),
                    labelColor = Color(0xFF123B6D),
                    leadingIconContentColor = Color(0xFF123B6D)
                )
            )
        }
        if (filters.showAllTransactions) item {
            RemovableFilterChip("Todas as transações") {
                onChange(filters.copy(showAllTransactions = false))
            }
        }
        filters.specificDate?.let { date ->
            item {
                val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                RemovableFilterChip("Data: ${date.format(fmt)}") {
                    onChange(filters.copy(specificDate = null))
                }
            }
        }
        if (filters.source != SourceFilter.ALL) item {
            RemovableFilterChip(filters.source.label) { onChange(filters.copy(source = SourceFilter.ALL)) }
        }
        if (filters.type != TypeFilter.ALL) item {
            RemovableFilterChip(filters.type.label) { onChange(filters.copy(type = TypeFilter.ALL)) }
        }
        if (filters.amount != AmountFilter.ALL) item {
            RemovableFilterChip(filters.amount.label) { onChange(filters.copy(amount = AmountFilter.ALL)) }
        }
        filters.accountId?.let { id -> item {
            RemovableFilterChip(accounts.firstOrNull { it.id == id }?.institutionName ?: "Banco") {
                onChange(filters.copy(accountId = null))
            }
        }}
        filters.categoryId?.let { id -> item {
            RemovableFilterChip(
                "Categoria: ${categories.firstOrNull { it.id == id }?.name ?: "Categoria"}"
            ) {
                onChange(filters.copy(categoryId = null))
            }
        }}
        if (
            transactionViewTab ==
            TransactionViewTab.CARD
        ) {
            filters.cardId?.let {
                    id ->
                item {
                    val card =
                        cards.firstOrNull {
                            it.id == id
                        }

                    RemovableFilterChip(
                        card?.let {
                            "Cartão: ${it.bankName} •••• ${it.lastFour}"
                        } ?: "Cartão"
                    ) {
                        onChange(
                            filters.copy(
                                cardId = null
                            )
                        )
                    }
                }
            }
        }
        if (filters.startDate != null || filters.endDate != null) item {
            val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            val label = if (filters.startDate != null && filters.endDate != null)
                "${filters.startDate.format(fmt)} – ${filters.endDate.format(fmt)}" else "Período"
            RemovableFilterChip(label) { onChange(filters.copy(startDate = null, endDate = null)) }
        }
    }
}

@Composable
private fun RemovableFilterChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remover filtro") },
        colors = InputChipDefaults.inputChipColors(
            selectedContainerColor = Color(0xFFE8F1FF),
            selectedLabelColor = Color(0xFF123B6D),
            selectedTrailingIconColor = Color(0xFF123B6D)
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDialog(
    accounts: List<AccountEntity>,
    cards: List<CreditCardDto>,
    categories: List<CategoryDto>,
    transactionViewTab:
        TransactionViewTab,
    filters: TransactionFilters,
    onDismiss: () -> Unit,
    onApply: (TransactionFilters) -> Unit
) {
    var source by remember(filters) { mutableStateOf(filters.source) }
    var type by remember(filters) { mutableStateOf(filters.type) }
    var amount by remember(filters) { mutableStateOf(filters.amount) }
    var accountId by remember(filters) { mutableStateOf(filters.accountId) }
    var categoryId by remember(filters) { mutableStateOf(filters.categoryId) }
    var cardId by remember(
        filters,
        transactionViewTab
    ) {
        mutableStateOf(
            if (
                transactionViewTab ==
                TransactionViewTab.CARD
            ) {
                filters.cardId
            } else {
                null
            }
        )
    }
    var specificDate by remember(filters) { mutableStateOf(filters.specificDate) }
    var startDate by remember(filters) { mutableStateOf(filters.startDate) }
    var endDate by remember(filters) { mutableStateOf(filters.endDate) }
    var showAllTransactions by remember(filters) {
        mutableStateOf(filters.showAllTransactions)
    }
    var showSpecificDatePicker by remember { mutableStateOf(false) }
    var showDateRange by remember { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var cardMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filtrar transações") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Visualização", fontWeight = FontWeight.SemiBold)

                FilterChip(
                    selected = showAllTransactions,
                    onClick = {
                        showAllTransactions = !showAllTransactions
                        if (showAllTransactions) {
                            specificDate = null
                            startDate = null
                            endDate = null
                        }
                    },
                    label = { Text("Todas as transações") },
                    leadingIcon = {
                        Icon(Icons.Default.List, contentDescription = null)
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFFF8FAFD),
                        selectedContainerColor = Color(0xFFE8F1FF),
                        labelColor = Color(0xFF536274),
                        selectedLabelColor = Color(0xFF123B6D),
                        iconColor = Color(0xFF536274),
                        selectedLeadingIconColor = Color(0xFF123B6D)
                    )
                )

                Text("Origem", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceFilter.entries.forEach { item ->
                        FilterChip(
                            selected = source == item,
                            onClick = { source = item },
                            label = { Text(item.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0xFFF8FAFD),
                                selectedContainerColor = Color(0xFFE8F1FF),
                                labelColor = Color(0xFF536274),
                                selectedLabelColor = Color(0xFF123B6D)
                            )
                        )
                    }
                }
                Text("Tipo", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TypeFilter.entries.forEach { item ->
                        FilterChip(
                            selected = type == item,
                            onClick = { type = item },
                            label = { Text(item.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0xFFF8FAFD),
                                selectedContainerColor = Color(0xFFE8F1FF),
                                labelColor = Color(0xFF536274),
                                selectedLabelColor = Color(0xFF123B6D)
                            )
                        )
                    }
                }
                Text("Faixa de valor", fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AmountFilter.entries.chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowItems.forEach { item ->
                                FilterChip(
                                    selected = amount == item,
                                    onClick = { amount = item },
                                    label = { Text(item.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = Color(0xFFF8FAFD),
                                        selectedContainerColor = Color(0xFFE8F1FF),
                                        labelColor = Color(0xFF536274),
                                        selectedLabelColor = Color(0xFF123B6D)
                                    )
                                )
                            }
                        }
                    }
                }
                Text("Banco", fontWeight = FontWeight.SemiBold)
                Box {
                    OutlinedButton(
                        onClick = { accountMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF123B6D))
                    ) {
                        Text(accounts.firstOrNull { it.id == accountId }?.institutionName ?: "Todos os bancos")
                    }
                    DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                        DropdownMenuItem(text = { Text("Todos os bancos") }, onClick = { accountId = null; accountMenu = false })
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text("${account.institutionName} - ${account.accountName ?: "Conta"}") },
                                onClick = { accountId = account.id; accountMenu = false }
                            )
                        }
                    }
                }
                Text("Categoria", fontWeight = FontWeight.SemiBold)
                Box {
                    OutlinedButton(
                        onClick = { categoryMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF123B6D))
                    ) {
                        Text(
                            categories.firstOrNull { it.id == categoryId }?.name
                                ?: "Todas as categorias"
                        )
                    }
                    DropdownMenu(
                        expanded = categoryMenu,
                        onDismissRequest = { categoryMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Todas as categorias") },
                            onClick = {
                                categoryId = null
                                categoryMenu = false
                            }
                        )
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    categoryId = category.id
                                    categoryMenu = false
                                }
                            )
                        }
                    }
                }

                if (
                    transactionViewTab ==
                    TransactionViewTab.CARD
                ) {
                    Text(
                        "Cartão",
                        fontWeight =
                            FontWeight.SemiBold
                    )

                    Box {
                        OutlinedButton(
                            onClick = {
                                cardMenu = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF123B6D))
                        ) {
                            Text(
                                cards.firstOrNull {
                                    it.id == cardId
                                }?.let {
                                    "${it.bankName} • ${it.brand} •••• ${it.lastFour}"
                                } ?: "Todos os cartões"
                            )
                        }

                        DropdownMenu(
                            expanded =
                                cardMenu,
                            onDismissRequest = {
                                cardMenu = false
                            }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Todos os cartões"
                                    )
                                },
                                onClick = {
                                    cardId = null
                                    cardMenu = false
                                }
                            )

                            cards.forEach {
                                    card ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${card.bankName} • ${card.brand} •••• ${card.lastFour}"
                                        )
                                    },
                                    onClick = {
                                        cardId =
                                            card.id

                                        cardMenu =
                                            false
                                    }
                                )
                            }
                        }
                    }
                }

                Text("Data específica", fontWeight = FontWeight.SemiBold)
                OutlinedButton(
                    onClick = { showSpecificDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF123B6D))
                ) {
                    Icon(Icons.Default.CalendarMonth, null)
                    Spacer(Modifier.width(8.dp))
                    val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                    Text(specificDate?.format(fmt) ?: "Selecionar data")
                }

                Text("Período personalizado", fontWeight = FontWeight.SemiBold)
                OutlinedButton(
                    onClick = { showDateRange = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF123B6D))
                ) {
                    Icon(Icons.Default.DateRange, null); Spacer(Modifier.width(8.dp))
                    val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                    Text(if (startDate != null && endDate != null) "${startDate!!.format(fmt)} – ${endDate!!.format(fmt)}" else "Selecionar período")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(
                        TransactionFilters(
                            source = source,
                            type = type,
                            amount = amount,
                            accountId = accountId,
                            categoryId = categoryId,
                            cardId =
                                if (
                                    transactionViewTab ==
                                    TransactionViewTab.CARD
                                ) {
                                    cardId
                                } else {
                                    null
                                },
                            specificDate = specificDate,
                            startDate = startDate,
                            endDate = endDate,
                            showAllTransactions = showAllTransactions
                        )
                    )
                }
            ) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )

    if (showSpecificDatePicker) {
        SingleDatePickerDialog(
            initialDate = specificDate,
            onDismiss = { showSpecificDatePicker = false },
            onConfirm = { date ->
                specificDate = date
                startDate = null
                endDate = null
                showAllTransactions = false
                showSpecificDatePicker = false
            }
        )
    }

    if (showDateRange) {
        DateRangePickerDialog(startDate, endDate, { showDateRange = false }) { start, end ->
            startDate = start
            endDate = end
            specificDate = null
            showAllTransactions = false
            showDateRange = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberNoFutureSelectableDates(): SelectableDates {
    val today = LocalDate.now()
    val maxDateMillis = remember(today) {
        today
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }
    val maxYear = today.year

    return remember(maxDateMillis, maxYear) {
        object : SelectableDates {
            override fun isSelectableDate(
                utcTimeMillis: Long
            ): Boolean {
                return utcTimeMillis <= maxDateMillis
            }

            override fun isSelectableYear(
                year: Int
            ): Boolean {
                return year <= maxYear
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleDatePickerDialog(
    initialDate: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit
) {
    val zone = ZoneOffset.UTC
    val selectableDates =
        rememberNoFutureSelectableDates()

    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDate
            ?.coerceAtMost(LocalDate.now())
            ?.atStartOfDay(zone)
            ?.toInstant()
            ?.toEpochMilli(),
        selectableDates = selectableDates
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant
                            .ofEpochMilli(millis)
                            .atZone(zone)
                            .toLocalDate()
                        onConfirm(date)
                    }
                },
                enabled = state.selectedDateMillis != null
            ) {
                Text("Aplicar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    ) {
        DatePicker(
            state = state,
            title = {
                Text(
                    "Selecione a data",
                    modifier = Modifier.padding(16.dp)
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangePickerDialog(
    initialStart: LocalDate?,
    initialEnd: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate?, LocalDate?) -> Unit
) {
    val zone = ZoneOffset.UTC
    val selectableDates =
        rememberNoFutureSelectableDates()
    val today = LocalDate.now()

    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis =
            initialStart
                ?.coerceAtMost(today)
                ?.atStartOfDay(zone)
                ?.toInstant()
                ?.toEpochMilli(),
        initialSelectedEndDateMillis =
            initialEnd
                ?.coerceAtMost(today)
                ?.atStartOfDay(zone)
                ?.toInstant()
                ?.toEpochMilli(),
        selectableDates = selectableDates
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = state.selectedStartDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                    val end = state.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                    onConfirm(start, end)
                },
                enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null
            ) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    ) {
        DateRangePicker(state = state, modifier = Modifier.heightIn(max = 620.dp))
    }
}

@Composable
private fun DuplicateComparisonBlock(
    label: String,
    tx: TransactionEntity,
    accounts: List<AccountEntity>
) {
    val currency = remember { NumberFormat.getCurrencyInstance(Locale("pt", "BR")) }
    val accountName = accounts.firstOrNull { it.id == tx.accountId }?.institutionName ?: "Sem banco"
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(tx.description, fontWeight = FontWeight.SemiBold)
        Text(currency.format(tx.amount))
        Text("Data: ${transactionsFormatDateTimeForDisplay(tx.date)}", style = MaterialTheme.typography.bodySmall)
        Text("Banco: $accountName", style = MaterialTheme.typography.bodySmall)
        Text(
            if (tx.source == "open_finance") "Origem: Open Finance" else "Origem: Manual",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTransactionRow(
    tx: TransactionEntity,
    account: AccountEntity?,
    categoryName: String?,
    categoryIcon: String? = null,
    card: CreditCardDto? = null,
    highlighted: Boolean = false,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    possibleDuplicate: Boolean = false,
    onCompareDuplicate: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: (TransactionEntity) -> Unit
) {
    val pulse = remember(tx.id) { Animatable(0f) }

    LaunchedEffect(highlighted) {
        if (highlighted) {
            repeat(3) {
                pulse.animateTo(
                    1f,
                    animationSpec = tween(durationMillis = 220)
                )
                pulse.animateTo(
                    0f,
                    animationSpec = tween(durationMillis = 320)
                )
            }
        } else {
            pulse.snapTo(0f)
        }
    }

    val highlightBackground =
        MaterialTheme.colorScheme.primary.copy(
            alpha = 0.06f + (pulse.value * 0.18f)
        )

    if (selectionMode) {
        TransactionRow(
            tx = tx,
            account = account,
            categoryName =
                categoryName,
            categoryIcon = categoryIcon,
            card = card,
            selected = selected,
            possibleDuplicate = possibleDuplicate,
            onCompareDuplicate = onCompareDuplicate,
            onClick = onClick,
            onLongClick =
                onLongClick
        )
    } else {
        val dismissState =
            rememberSwipeToDismissBoxState(
                confirmValueChange = {
                        target ->
                    if (
                        target !=
                        SwipeToDismissBoxValue
                            .Settled
                    ) {
                        onDelete(tx)
                    }
                    false
                }
            )

        val shape =
            MaterialTheme.shapes.medium

        SwipeToDismissBox(
            state = dismissState,
            modifier =
                Modifier.clip(shape),
            backgroundContent = {
                if (
                    dismissState
                        .dismissDirection !=
                    SwipeToDismissBoxValue
                        .Settled
                ) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Color(
                                    0xFFC62828
                                )
                            )
                            .padding(
                                horizontal =
                                    20.dp
                            ),
                        verticalAlignment =
                            Alignment
                                .CenterVertically,
                        horizontalArrangement =
                            if (
                                dismissState
                                    .dismissDirection ==
                                SwipeToDismissBoxValue
                                    .StartToEnd
                            ) {
                                Arrangement.Start
                            } else {
                                Arrangement.End
                            }
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            "Excluir",
                            tint =
                                Color.White
                        )
                    }
                }
            }
        ) {
            TransactionRow(
                tx = tx,
                account = account,
                categoryName =
                    categoryName,
                categoryIcon = categoryIcon,
                card = card,
                selected = selected,
                possibleDuplicate = possibleDuplicate,
                onCompareDuplicate = onCompareDuplicate,
                onClick = onClick,
                onLongClick =
                    onLongClick
            )
        }
    }

}


private data class TransactionStatusUi(val label: String, val color: Color, val description: String, val displayAmount: Double)

private fun transactionStatusUi(tx: TransactionEntity): TransactionStatusUi? {
    val today = LocalDate.now()
    val due = transactionsParseTxDate(tx.date)
    val raw = tx.description
    if (raw.startsWith("__PAYABLE_V1__|")) {
        val parts = raw.removePrefix("__PAYABLE_V1__|").split('|', limit = 3)
        if (parts.size == 3) {
            val cents = parts[1].toLongOrNull() ?: 0L
            val description = runCatching {
                String(Base64.decode(parts[2], Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("Conta a pagar")
            val overdue = due?.isBefore(today) == true
            return TransactionStatusUi(
                if (overdue) "Vencida" else "Pendente",
                if (overdue) Color(0xFFC62828) else Color(0xFFE87900),
                description,
                -(cents.toDouble() / 100.0)
            )
        }
    }
    if (isPlannedDebtTransaction(tx)) {
        val overdue = due?.isBefore(today) == true
        val clean = raw.removePrefix(PLANNED_DEBT_PREFIX).trim()
        return TransactionStatusUi(
            if (overdue) "Vencida" else "Pendente",
            if (overdue) Color(0xFFC62828) else Color(0xFFE87900),
            clean.ifBlank { "Parcela de dívida" },
            tx.amount
        )
    }
    if (raw.startsWith("Conta paga •", ignoreCase = true)) {
        return TransactionStatusUi("Paga", Color(0xFF16845B), raw.substringAfter('•').trim(), tx.amount)
    }
    if (raw.startsWith("Pagamento de dívida •", ignoreCase = true)) {
        return TransactionStatusUi("Paga", Color(0xFF16845B), raw, tx.amount)
    }
    return null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionRow(
    tx: TransactionEntity,
    account: AccountEntity?,
    categoryName: String?,
    categoryIcon: String? = null,
    card: CreditCardDto? = null,
    selected: Boolean = false,
    possibleDuplicate: Boolean = false,
    onCompareDuplicate: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val currency = remember {
        NumberFormat.getCurrencyInstance(
            Locale("pt", "BR")
        )
    }
    val statusUi = transactionStatusUi(tx)
    val displayAmount = statusUi?.displayAmount ?: tx.amount
    val displayDescription = statusUi?.description ?: tx.description

    val amountColor =
        if (displayAmount > 0) {
            Color(0xFF178A3A)
        } else if (displayAmount < 0) {
            Color(0xFFC62828)
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    val typeLabel =
        if (displayAmount > 0) {
            "Entrada"
        } else if (displayAmount < 0) {
            "Saída"
        } else {
            tx.transactionType
        }

    val transactionCardShape = RoundedCornerShape(18.dp)
    val transactionCardShadow = when {
        selected -> 7.dp
        MaterialTheme.colorScheme.background.luminance() > 0.5f -> 6.dp
        else -> 2.dp
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = transactionCardShadow,
                shape = transactionCardShape,
                clip = false
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = transactionCardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 12.dp
            ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(
                    Icons.Default
                        .CheckCircle,
                    contentDescription =
                        "Selecionada",
                    tint =
                        MaterialTheme
                            .colorScheme
                            .primary
                )

                Spacer(
                    Modifier.width(6.dp)
                )
            }

            Icon(
                if (tx.source == "open_finance") {
                    Icons.Default.CloudSync
                } else if (displayAmount >= 0) {
                    Icons.Default.ArrowDownward
                } else {
                    Icons.Default.ArrowUpward
                },
                contentDescription = null,
                tint = if (displayAmount >= 0) Color(0xFF178A3A) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )

            Spacer(Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    displayDescription,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BankBadge(account?.institutionName ?: card?.bankName ?: "Sem banco")
                    Icon(
                        categoryIconVector(categoryIcon, categoryName ?: "Sem categoria"),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        categoryName ?: "Sem categoria",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Text(
                    "${
                        transactionsFormatDateTimeForDisplay(tx.date)
                    } • $typeLabel • ${
                        if (tx.source == "open_finance") {
                            "Automática"
                        } else {
                            "Manual"
                        }
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )

                if (statusUi != null) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = statusUi.color.copy(alpha = 0.13f),
                        contentColor = statusUi.color,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            statusUi.label,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                }

                if (possibleDuplicate) {
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Possível duplicidade",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(
                            onClick = onCompareDuplicate,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("Comparar")
                        }
                    }
                }

                val isCardTransaction =
                    tx.cardId != null ||
                        tx.installmentGroup != null ||
                        tx.installmentTotal != null

                if (isCardTransaction) {
                    val cardEnding =
                        card?.lastFour
                            ?.let {
                                " •••• $it"
                            }
                            .orEmpty()

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.Start
                    ) {
                        Surface(
                            color =
                                Color(0xFFE2F5E8),
                            contentColor =
                                Color(0xFF137333),
                            shape =
                                MaterialTheme
                                    .shapes.small
                        ) {
                            Text(
                                text =
                                    if (
                                        tx.installmentNumber !=
                                        null &&
                                        tx.installmentTotal !=
                                        null
                                    ) {
                                        "Parcela cartão ${
                                            tx.installmentNumber
                                        }/${tx.installmentTotal}$cardEnding"
                                    } else {
                                        "Compra cartão$cardEnding"
                                    },
                                modifier =
                                    Modifier.padding(
                                        horizontal = 7.dp,
                                        vertical = 3.dp
                                    ),
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelSmall,
                                fontWeight =
                                    FontWeight
                                        .SemiBold
                            )
                        }
                    }
                }

            }

            Spacer(Modifier.width(8.dp))

            Text(
                currency.format(displayAmount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = amountColor
            )
        }
    }
}


private fun transactionsParseTxDate(
    raw: String
): LocalDate? {
    if (raw.isBlank()) {
        return null
    }

    return runCatching {
        LocalDate.parse(raw.take(10))
    }.getOrElse {
        runCatching {
            OffsetDateTime.parse(raw)
                .toLocalDate()
        }.getOrElse {
            runCatching {
                LocalDateTime.parse(raw)
                    .toLocalDate()
            }.getOrElse {
                runCatching {
                    Instant.parse(raw)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                }.getOrNull()
            }
        }
    }
}

private fun transactionsFormatDateTimeForDisplay(
    raw: String
): String {
    val date =
        transactionsParseTxDate(raw)

    val time =
        runCatching {
            OffsetDateTime
                .parse(raw)
                .atZoneSameInstant(
                    ZoneId.systemDefault()
                )
                .toLocalTime()
        }.getOrElse {
            runCatching {
                Instant.parse(raw)
                    .atZone(
                        ZoneId.systemDefault()
                    )
                    .toLocalTime()
            }.getOrNull()
        }

    return when {
        date != null &&
            time != null ->
            "${
                date.format(
                    DateTimeFormatter.ofPattern(
                        "dd/MM/yyyy"
                    )
                )
            } • ${
                time.format(
                    DateTimeFormatter.ofPattern(
                        "HH:mm"
                    )
                )
            }"

        date != null ->
            date.format(
                DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy"
                )
            )

        else ->
            raw
    }
}
