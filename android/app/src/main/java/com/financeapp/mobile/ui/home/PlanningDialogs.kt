package com.financeapp.mobile.ui.home

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import java.time.format.DateTimeFormatter
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AccountBalanceWallet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.BudgetDto
import com.financeapp.mobile.data.remote.CategoryDto
import com.financeapp.mobile.data.remote.GoalContributionDto
import com.financeapp.mobile.data.remote.GoalDto
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

@Composable
internal fun PlanningDialog(
    categories: List<CategoryDto>,
    budgets: List<BudgetDto>,
    goals: List<GoalDto>,
    goalContributions: List<GoalContributionDto>,
    onOpenGoalHistory: (GoalDto) -> Unit,
    transactions: List<TransactionEntity>,
    initialBudgetCategoryId: Int? = null,
    onDismiss: () -> Unit,
    onCreateCategory: (String, String?) -> Unit,
    onUpdateCategory: (Int, String, String?) -> Unit,
    onDeleteCategory: (Int) -> Unit,
    onSetBudget: (Int, Double) -> Unit,
    onDeleteBudget: (Int) -> Unit,
    onCreateGoal: (String, Double, Double, String?) -> Unit,
    onUpdateGoal: (Int, String, Double, Double, String?) -> Unit,
    onAddGoalContribution: (Int, Double) -> Unit,
    onDeleteGoal: (Int) -> Unit
) {
    var showNewCategory by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf<CategoryDto?>(null) }
    var editingBudget by remember { mutableStateOf<CategoryDto?>(null) }
    var deletingCategory by remember { mutableStateOf<CategoryDto?>(null) }

    var showNewGoal by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<GoalDto?>(null) }
    var deletingGoal by remember { mutableStateOf<GoalDto?>(null) }
    var addingValueGoal by remember {
        mutableStateOf<GoalDto?>(null)
    }

    var selectedPlanningTab by remember(initialBudgetCategoryId) {
        mutableStateOf(if (initialBudgetCategoryId != null) 1 else 0)
    }

    LaunchedEffect(initialBudgetCategoryId, categories) {
        if (initialBudgetCategoryId != null) {
            selectedPlanningTab = 1
            editingBudget = categories.firstOrNull { it.id == initialBudgetCategoryId }
        }
    }

    val locale = remember { Locale("pt", "BR") }
    val currency = remember {
        NumberFormat.getCurrencyInstance(locale)
    }
    val currentMonth = remember {
        YearMonth.now()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Planejamento")
                Text(
                    currentMonth.month
                        .getDisplayName(TextStyle.FULL, locale)
                        .replaceFirstChar { it.uppercase() } +
                        " de ${currentMonth.year}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 580.dp)
            ) {
                TabRow(
                    selectedTabIndex = selectedPlanningTab
                ) {
                    Tab(
                        selected = selectedPlanningTab == 0,
                        onClick = { selectedPlanningTab = 0 },
                        text = { Text("Metas") },
                        icon = {
                            Icon(
                                Icons.Default.Flag,
                                contentDescription = null
                            )
                        }
                    )

                    Tab(
                        selected = selectedPlanningTab == 1,
                        onClick = { selectedPlanningTab = 1 },
                        text = { Text("Categorias") },
                        icon = {
                            Icon(
                                Icons.Default.Category,
                                contentDescription = null
                            )
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (selectedPlanningTab == 0) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = { showNewGoal = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Nova meta financeira")
                        }

                        Spacer(Modifier.height(10.dp))

                        if (goals.isEmpty()) {
                            Text(
                                "Nenhuma meta criada.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement =
                                    Arrangement.spacedBy(10.dp)
                            ) {
                                items(
                                    items = goals,
                                    key = { it.id }
                                ) { goal ->
                                    val progress =
                                        if (goal.targetAmount > 0.0) {
                                            goal.currentAmount /
                                                goal.targetAmount
                                        } else {
                                            0.0
                                        }

                                    val percent =
                                        (progress * 100.0)
                                            .coerceAtLeast(0.0)
                                            .toInt()

                                    val remaining =
                                        (
                                            goal.targetAmount -
                                                goal.currentAmount
                                        ).coerceAtLeast(0.0)

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = {
                                                    onOpenGoalHistory(
                                                        goal
                                                    )
                                                },
                                                onLongClick = {
                                                    onOpenGoalHistory(
                                                        goal
                                                    )
                                                }
                                            )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement =
                                                Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier =
                                                    Modifier.fillMaxWidth(),
                                                verticalAlignment =
                                                    Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Flag,
                                                    null
                                                )
                                                Spacer(
                                                    Modifier.width(8.dp)
                                                )
                                                Text(
                                                    goal.name,
                                                    modifier =
                                                        Modifier.weight(1f),
                                                    fontWeight =
                                                        FontWeight.Bold
                                                )
                                                IconButton(
                                                    onClick = {
                                                        editingGoal = goal
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.Edit,
                                                        "Editar meta"
                                                    )
                                                }
                                                IconButton(
                                                    onClick = {
                                                        deletingGoal = goal
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default
                                                            .DeleteOutline,
                                                        "Excluir meta",
                                                        tint =
                                                            MaterialTheme
                                                                .colorScheme
                                                                .error
                                                    )
                                                }
                                            }

                                            Text(
                                                "${currency.format(goal.currentAmount)} de " +
                                                    currency.format(
                                                        goal.targetAmount
                                                    )
                                            )

                                            LinearProgressIndicator(
                                                progress = {
                                                    progress
                                                        .coerceIn(0.0, 1.0)
                                                        .toFloat()
                                                },
                                                modifier =
                                                    Modifier.fillMaxWidth()
                                            )

                                            Text(
                                                if (
                                                    goal.currentAmount >=
                                                    goal.targetAmount
                                                ) {
                                                    "Meta atingida! • $percent%"
                                                } else {
                                                    "$percent% concluído • Faltam ${
                                                        currency.format(
                                                            remaining
                                                        )
                                                    }"
                                                },
                                                style =
                                                    MaterialTheme.typography
                                                        .bodySmall
                                            )

                                            goal.targetDate?.let {
                                                    rawDate ->
                                                runCatching {
                                                    LocalDate.parse(rawDate)
                                                }.getOrNull()?.let { date ->
                                                    Text(
                                                        "Prazo: ${
                                                            date.format(
                                                                DateTimeFormatter
                                                                    .ofPattern(
                                                                        "dd/MM/yyyy"
                                                                    )
                                                            )
                                                        }",
                                                        style =
                                                            MaterialTheme
                                                                .typography
                                                                .bodySmall
                                                    )
                                                }
                                            }

                                            FilledTonalButton(
                                                onClick = {
                                                    addingValueGoal = goal
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(
                                                    Icons.Default.AddCircleOutline,
                                                    contentDescription = null
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text("Adicionar valor")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = { showNewCategory = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Nova categoria")
                        }

                        Spacer(Modifier.height(10.dp))

                        if (categories.isEmpty()) {
                            Text(
                                "Nenhuma categoria criada.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement =
                                    Arrangement.spacedBy(10.dp)
                            ) {
                                item {
                                    BudgetTotalsCard(monthlyBudgetOverview(budgets, categories, transactions, currentMonth))
                                }
                                items(
                                    items = categories,
                                    key = { it.id }
                                ) { category ->
                                    val budget = budgets.firstOrNull {
                                        it.categoryId == category.id
                                    }

                                    val spent = transactions
                                        .asSequence()
                                        .filter {
                                            it.categoryId ==
                                                category.id &&
                                                it.amount < 0 &&
                                                planningParseTxDate(it.date)
                                                    ?.let(
                                                        YearMonth::from
                                                    ) ==
                                                    currentMonth
                                        }
                                        .sumOf { abs(it.amount) }

                                    val limit = budget?.amount ?: 0.0
                                    val progress =
                                        if (limit > 0) {
                                            spent / limit
                                        } else {
                                            0.0
                                        }
                                    val percent =
                                        if (limit > 0) {
                                            (progress * 100).toInt()
                                        } else {
                                            0
                                        }

                                    Card(
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement =
                                                Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier =
                                                    Modifier.fillMaxWidth(),
                                                verticalAlignment =
                                                    Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    categoryIconVector(category.icon, category.name),
                                                    null
                                                )
                                                Spacer(
                                                    Modifier.width(8.dp)
                                                )
                                                Text(
                                                    category.name,
                                                    modifier =
                                                        Modifier.weight(1f),
                                                    fontWeight =
                                                        FontWeight.Bold
                                                )
                                                IconButton(
                                                    onClick = {
                                                        editingName =
                                                            category
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.Edit,
                                                        "Editar categoria"
                                                    )
                                                }
                                                IconButton(
                                                    onClick = {
                                                        deletingCategory =
                                                            category
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default
                                                            .DeleteOutline,
                                                        "Excluir categoria",
                                                        tint =
                                                            MaterialTheme
                                                                .colorScheme
                                                                .error
                                                    )
                                                }
                                            }

                                            Text(
                                                "Gasto no mês: ${
                                                    currency.format(spent)
                                                }"
                                            )

                                            if (budget == null) {
                                                Text(
                                                    "Sem limite mensal definido.",
                                                    style =
                                                        MaterialTheme
                                                            .typography
                                                            .bodySmall
                                                )
                                                OutlinedButton(
                                                    onClick = {
                                                        editingBudget =
                                                            category
                                                    },
                                                    modifier =
                                                        Modifier.fillMaxWidth()
                                                ) {
                                                    Icon(
                                                        Icons.Default
                                                            .AccountBalanceWallet,
                                                        null
                                                    )
                                                    Spacer(
                                                        Modifier.width(6.dp)
                                                    )
                                                    Text(
                                                        "Definir limite mensal"
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    "Limite: ${
                                                        currency.format(limit)
                                                    }",
                                                    fontWeight =
                                                        FontWeight.SemiBold
                                                )

                                                LinearProgressIndicator(
                                                    progress = {
                                                        progress
                                                            .coerceIn(
                                                                0.0,
                                                                1.0
                                                            )
                                                            .toFloat()
                                                    },
                                                    modifier =
                                                        Modifier.fillMaxWidth()
                                                )

                                                val remaining =
                                                    limit - spent

                                                Text(
                                                    when {
                                                        remaining > 0 ->
                                                            "Pode gastar: ${
                                                                currency
                                                                    .format(
                                                                        remaining
                                                                    )
                                                            }"
                                                        remaining < 0 ->
                                                            "$percent% utilizado • Excedido em ${
                                                                currency
                                                                    .format(
                                                                        abs(
                                                                            remaining
                                                                        )
                                                                    )
                                                            }"
                                                        else ->
                                                            "100% utilizado • Limite atingido"
                                                    },
                                                    style =
                                                        MaterialTheme
                                                            .typography
                                                            .bodySmall
                                                )

                                                Row(
                                                    modifier =
                                                        Modifier.fillMaxWidth(),
                                                    horizontalArrangement =
                                                        Arrangement.End
                                                ) {
                                                    TextButton(
                                                        onClick = {
                                                            editingBudget =
                                                                category
                                                        }
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Edit,
                                                            null
                                                        )
                                                        Spacer(
                                                            Modifier.width(
                                                                4.dp
                                                            )
                                                        )
                                                        Text(
                                                            "Editar limite"
                                                        )
                                                    }

                                                    TextButton(
                                                        onClick = {
                                                            onDeleteBudget(
                                                                category.id
                                                            )
                                                        },
                                                        colors =
                                                            ButtonDefaults
                                                                .textButtonColors(
                                                                    contentColor =
                                                                        MaterialTheme
                                                                            .colorScheme
                                                                            .error
                                                                )
                                                    ) {
                                                        Icon(
                                                            Icons.Default
                                                                .DeleteOutline,
                                                            null
                                                        )
                                                        Spacer(
                                                            Modifier.width(
                                                                4.dp
                                                            )
                                                        )
                                                        Text(
                                                            "Remover limite"
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar")
            }
        }
    )

    if (showNewGoal) {
        GoalDialog(
            goal = null,
            onDismiss = {
                showNewGoal = false
            },
            onSave = {
                name,
                targetAmount,
                currentAmount,
                targetDate ->
                onCreateGoal(
                    name,
                    targetAmount,
                    currentAmount,
                    targetDate
                )
                showNewGoal = false
            }
        )
    }

    addingValueGoal?.let { goal ->
        AddGoalValueDialog(
            goal = goal,
            onDismiss = {
                addingValueGoal = null
            },
            onAdd = { amount ->
                onAddGoalContribution(
                    goal.id,
                    amount
                )
                addingValueGoal = null
            }
        )
    }

    editingGoal?.let { goal ->
        GoalDialog(
            goal = goal,
            onDismiss = {
                editingGoal = null
            },
            onSave = {
                name,
                targetAmount,
                currentAmount,
                targetDate ->
                onUpdateGoal(
                    goal.id,
                    name,
                    targetAmount,
                    currentAmount,
                    targetDate
                )
                editingGoal = null
            }
        )
    }

    deletingGoal?.let { goal ->
        AlertDialog(
            onDismissRequest = {
                deletingGoal = null
            },
            title = {
                Text("Excluir meta?")
            },
            text = {
                Text(
                    "Deseja excluir definitivamente a meta “${goal.name}”?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteGoal(goal.id)
                        deletingGoal = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor =
                            MaterialTheme.colorScheme.error,
                        contentColor =
                            MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Excluir")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deletingGoal = null
                    }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showNewCategory) {
        NewCategoryDialog(
            existingCategories = categories,
            onDismiss = { showNewCategory = false },
            onSave = { name, icon ->
                onCreateCategory(name, icon)
                showNewCategory = false
            }
        )
    }

    editingName?.let { category ->
        RenameCategoryDialog(
            category = category,
            onDismiss = { editingName = null },
            onSave = { name, icon ->
                onUpdateCategory(category.id, name, icon)
                editingName = null
            }
        )
    }

    editingBudget?.let { category ->
        SetBudgetDialog(
            category = category,
            currentAmount = budgets.firstOrNull {
                it.categoryId == category.id
            }?.amount,
            onDismiss = { editingBudget = null },
            onSave = {
                onSetBudget(category.id, it)
                editingBudget = null
            },
            onDelete = if (budgets.any { it.categoryId == category.id }) ({
                onDeleteBudget(category.id)
                editingBudget = null
            }) else null
        )
    }

    deletingCategory?.let { category ->
        AlertDialog(
            onDismissRequest = { deletingCategory = null },
            title = { Text("Excluir categoria?") },
            text = {
                Text(
                    "As transações continuarão existindo e ficarão como “Sem categoria”."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteCategory(category.id)
                        deletingCategory = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor =
                            MaterialTheme.colorScheme.error,
                        contentColor =
                            MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Excluir")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deletingCategory = null
                    }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
internal fun BudgetManagerDialog(
    categories: List<CategoryDto>,
    budgets: List<BudgetDto>,
    transactions: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onSetBudget: (Int, Double) -> Unit,
    onDeleteBudget: (Int) -> Unit
) {
    var editingCategory by remember {
        mutableStateOf<CategoryDto?>(null)
    }

    val locale = remember { Locale("pt", "BR") }
    val currency = remember {
        NumberFormat.getCurrencyInstance(locale)
    }
    val currentMonth = remember { YearMonth.now() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Orçamentos mensais")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Defina quanto pretende gastar por mês em cada categoria.",
                    style = MaterialTheme.typography.bodySmall
                )

                if (categories.isEmpty()) {
                    Text(
                        "Crie uma categoria antes de definir um orçamento."
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = categories,
                            key = { it.id }
                        ) { category ->
                            val budget = budgets.firstOrNull {
                                it.categoryId == category.id
                            }

                            val spent = transactions
                                .asSequence()
                                .filter {
                                    it.categoryId == category.id &&
                                        it.amount < 0 &&
                                        planningParseTxDate(it.date)
                                            ?.let(YearMonth::from) == currentMonth
                                }
                                .sumOf { abs(it.amount) }

                            val limit = budget?.amount ?: 0.0
                            val progress = if (limit > 0.0) {
                                (spent / limit).coerceAtLeast(0.0)
                            } else {
                                0.0
                            }
                            val percent = if (limit > 0.0) {
                                (progress * 100).toInt()
                            } else {
                                0
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Category, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            category.name,
                                            modifier = Modifier.weight(1f),
                                            fontWeight = FontWeight.SemiBold
                                        )

                                        IconButton(
                                            onClick = {
                                                editingCategory = category
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = "Definir orçamento"
                                            )
                                        }

                                        if (budget != null) {
                                            IconButton(
                                                onClick = {
                                                    onDeleteBudget(category.id)
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.DeleteOutline,
                                                    contentDescription = "Remover orçamento",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }

                                    if (budget == null) {
                                        Text(
                                            "Gasto neste mês: ${currency.format(spent)}"
                                        )
                                        OutlinedButton(
                                            onClick = {
                                                editingCategory = category
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.Add, null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Definir orçamento")
                                        }
                                    } else {
                                        Text(
                                            "${currency.format(spent)} de ${currency.format(limit)}"
                                        )

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(8.dp)
                                                .clip(MaterialTheme.shapes.small)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                )
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .fillMaxWidth(
                                                        progress
                                                            .coerceIn(0.0, 1.0)
                                                            .toFloat()
                                                    )
                                                    .background(
                                                        if (progress > 1.0) {
                                                            MaterialTheme.colorScheme.error
                                                        } else {
                                                            MaterialTheme.colorScheme.primary
                                                        }
                                                    )
                                            )
                                        }

                                        val remaining = limit - spent
                                        Text(
                                            when {
                                                remaining > 0 ->
                                                    "Pode gastar: ${currency.format(remaining)}"
                                                remaining < 0 ->
                                                    "$percent% utilizado • Excedido em ${currency.format(abs(remaining))}"
                                                else ->
                                                    "100% utilizado • Limite atingido"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (remaining < 0) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar")
            }
        }
    )

    editingCategory?.let { category ->
        val currentBudget = budgets.firstOrNull {
            it.categoryId == category.id
        }?.amount

        SetBudgetDialog(
            category = category,
            currentAmount = currentBudget,
            onDismiss = {
                editingCategory = null
            },
            onSave = { amount ->
                onSetBudget(category.id, amount)
                editingCategory = null
            }
        )
    }
}

@Composable
internal fun SetBudgetDialog(
    category: CategoryDto,
    currentAmount: Double?,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var amountDigits by remember(category.id, currentAmount) {
        mutableStateOf(
            currentAmount
                ?.let { (it * 100).toLong().toString() }
                ?: ""
        )
    }

    val amount = BrlMoney.toDouble(amountDigits)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Orçamento • ${category.name}")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Informe o limite mensal para esta categoria.")
                OutlinedTextField(
                    value = amountDigits,
                    onValueChange = { typed ->
                        amountDigits = typed
                            .filter(Char::isDigit)
                            .trimStart('0')
                            .take(12)
                    },
                    label = { Text("Limite mensal") },
                    singleLine = true,
                    visualTransformation = BrlMoneyVisualTransformation,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(amount) },
                enabled = amount > 0.0
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Remover", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}



private fun planningFormatCurrencyFromDigits(
    digits: String
): String {
    val numericDigits =
        digits.filter(Char::isDigit)

    if (numericDigits.isEmpty()) {
        return "R$ 0,00"
    }

    val cents =
        numericDigits.toLongOrNull()
            ?: 0L

    return NumberFormat
        .getCurrencyInstance(
            Locale("pt", "BR")
        )
        .format(cents / 100.0)
}

private fun planningParseTxDate(
    raw: String
): LocalDate? {
    if (raw.isBlank()) {
        return null
    }

    return runCatching {
        LocalDate.parse(
            raw.take(10)
        )
    }.getOrElse {
        runCatching {
            OffsetDateTime
                .parse(raw)
                .toLocalDate()
        }.getOrElse {
            runCatching {
                LocalDateTime
                    .parse(raw)
                    .toLocalDate()
            }.getOrElse {
                runCatching {
                    Instant.parse(raw)
                        .atZone(
                            ZoneOffset.UTC
                        )
                        .toLocalDate()
                }.getOrNull()
            }
        }
    }
}
