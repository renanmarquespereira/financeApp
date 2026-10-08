package com.financeapp.mobile.ui.home

import androidx.compose.ui.Alignment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financeapp.mobile.data.local.AccountEntity
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.CategoryDto
import com.financeapp.mobile.data.remote.CreditCardDto
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs


private fun canonicalBankKey(value: String): String {
    val n = value.lowercase(Locale("pt", "BR"))
        .replace("á", "a").replace("à", "a").replace("ã", "a").replace("â", "a")
        .replace("é", "e").replace("ê", "e")
        .replace("í", "i").replace("ó", "o").replace("ô", "o").replace("õ", "o")
        .replace("ú", "u").replace("ç", "c")
        .trim()
    return when {
        "nubank" in n || "nu pagamentos" in n -> "nubank"
        "banco do brasil" in n || n == "bb" -> "bb"
        "itau" in n -> "itau"
        "santander" in n -> "santander"
        "bradesco" in n -> "bradesco"
        "caixa" in n -> "caixa"
        "inter" in n -> "inter"
        "c6" in n -> "c6"
        else -> n.replace(Regex("[^a-z0-9]"), "")
    }
}

@Composable
internal fun TransactionDetailsDialog(
    tx: TransactionEntity,
    account: AccountEntity?,
    category: CategoryDto?,
    categories: List<CategoryDto>,
    onCreateCategory: (String, String?, (CategoryDto) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onUpdateCategory: (Int?) -> Unit,
    onAttachments: () -> Unit,
    onDelete: () -> Unit
) {
    var categoryMenu by remember { mutableStateOf(false) }
    var showNewCategory by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx.description) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    NumberFormat
                        .getCurrencyInstance(Locale("pt", "BR"))
                        .format(tx.amount),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (tx.amount < 0)
                        Color(0xFFC62828)
                    else
                        Color(0xFF178A3A)
                )

                Text(
                    "Conta: ${account?.institutionName ?: "Sem conta vinculada"}"
                )

                Box {
                    OutlinedButton(
                        onClick = { categoryMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Categoria: ${category?.name ?: "Sem categoria"}")
                    }

                    DropdownMenu(
                        expanded = categoryMenu,
                        onDismissRequest = { categoryMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Sem categoria") },
                            onClick = {
                                onUpdateCategory(null)
                                categoryMenu = false
                            }
                        )
                        categories.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name) },
                                onClick = {
                                    onUpdateCategory(item.id)
                                    categoryMenu = false
                                }
                            )
                        }

                        HorizontalDivider()

                        DropdownMenuItem(
                            text = { Text("+ Nova categoria") },
                            onClick = {
                                categoryMenu = false
                                showNewCategory = true
                            }
                        )
                    }
                }

                Text(
                    "Tipo: ${if (tx.amount < 0) "Saída" else "Entrada"} • " +
                        if (tx.source == "open_finance") "Automática" else "Manual"
                )
                Text("Data: ${transactionFormatDateTimeForDisplay(tx.date)}")

                OutlinedButton(
                    onClick = onAttachments,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Spacer(Modifier.width(6.dp))
                    Text("Comprovantes")
                }

                if (tx.source == "open_finance") {
                    Text(
                        "Movimentação bancária: somente a categoria pode ser alterada.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            if (tx.source == "manual") {
                Button(onClick = onEdit) {
                    Icon(Icons.Default.Edit, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Editar")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Fechar")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.DeleteOutline, null)
                Spacer(Modifier.width(6.dp))
                Text("Excluir")
            }
        }
    )

    if (showNewCategory) {
        NewCategoryDialog(
            existingCategories = categories,
            onDismiss = { showNewCategory = false },
            onSave = { name, icon ->
                onCreateCategory(name, icon) { created ->
                    onUpdateCategory(created.id)
                }
                showNewCategory = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditTransactionDialog(
    tx: TransactionEntity,
    accounts: List<AccountEntity>,
    categories: List<CategoryDto>,
    onCreateCategory: (String, String?, (CategoryDto) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Int?, Int?, String, Double, String, String) -> Unit
) {
    var selectedAccountId by remember(tx.id) { mutableStateOf(tx.accountId) }
    var selectedCategoryId by remember(tx.id) { mutableStateOf(tx.categoryId) }
    var accountMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var showNewCategory by remember { mutableStateOf(false) }
    var description by remember(tx.id) { mutableStateOf(tx.description) }
    var amountDigits by remember(tx.id) {
        mutableStateOf(
            (kotlin.math.abs(tx.amount) * 100)
                .toLong()
                .toString()
        )
    }
    var type by remember(tx.id) {
        mutableStateOf(if (tx.amount < 0) "debit" else "credit")
    }
    var selectedDate by remember(tx.id) {
        mutableStateOf(transactionParseTxDate(tx.date) ?: LocalDate.now())
    }
    var showDatePicker by remember { mutableStateOf(false) }
    val originalTime = remember(tx.id) {
        transactionParseTxTime(tx.date) ?: LocalTime.now()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tx.cardId != null) "Editar compra do cartão" else "Editar transação") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tx.cardId != null) {
                    Text("Compra vinculada ao cartão #${tx.cardId}. Este editor altera descrição, valor, data e categoria. Troca de cartão e parcelamento ainda não estão disponíveis.", style = MaterialTheme.typography.bodySmall)
                }
                Box {
                    OutlinedButton(
                        onClick = { accountMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            accounts.firstOrNull {
                                it.id == selectedAccountId
                            }?.let {
                                it.institutionName + " - " +
                                    (it.accountName ?: "Conta")
                            } ?: "Sem conta vinculada"
                        )
                    }
                    DropdownMenu(
                        expanded = accountMenu,
                        onDismissRequest = { accountMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Sem conta vinculada") },
                            onClick = {
                                selectedAccountId = null
                                accountMenu = false
                            }
                        )
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        account.institutionName + " - " +
                                            (account.accountName ?: "Conta")
                                    )
                                },
                                onClick = {
                                    selectedAccountId = account.id
                                    accountMenu = false
                                }
                            )
                        }
                    }
                }

                Box {
                    OutlinedButton(
                        onClick = { categoryMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            categories.firstOrNull {
                                it.id == selectedCategoryId
                            }?.name ?: "Sem categoria"
                        )
                    }
                    DropdownMenu(
                        expanded = categoryMenu,
                        onDismissRequest = { categoryMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Sem categoria") },
                            onClick = {
                                selectedCategoryId = null
                                categoryMenu = false
                            }
                        )
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    selectedCategoryId = category.id
                                    categoryMenu = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ Nova categoria") },
                            onClick = {
                                categoryMenu = false
                                showNewCategory = true
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descrição") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Mesma configuracao monetaria usada em "Limite do cartao".
                OutlinedTextField(
                    amountDigits,
                    { amountDigits = BrlMoney.digits(it) },
                    Modifier.fillMaxWidth(),
                    label = { Text("Valor") },
                    placeholder = { Text("R$ 0,00") },
                    singleLine = true,
                    visualTransformation = BrlMoneyVisualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CalendarMonth, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Data: ${
                            selectedDate.format(
                                DateTimeFormatter.ofPattern("dd/MM/yyyy")
                            )
                        }"
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = type == "debit",
                        onClick = { type = "debit" }
                    )
                    Text("Saída")
                    Spacer(Modifier.width(14.dp))
                    RadioButton(
                        selected = type == "credit",
                        onClick = { type = "credit" }
                    )
                    Text("Entrada")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val raw = BrlMoney.toDouble(amountDigits)
                    val signed = if (type == "debit") -abs(raw) else abs(raw)
                    val date = selectedDate
                        .atTime(originalTime)
                        .atZone(ZoneId.systemDefault())
                        .toOffsetDateTime()
                        .toString()
                    onSave(
                        selectedAccountId,
                        selectedCategoryId,
                        description,
                        signed,
                        type,
                        date
                    )
                },
                enabled = description.isNotBlank() &&
                    BrlMoney.toDouble(amountDigits) > 0.0
            ) { Text("Salvar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )

    if (showDatePicker) {
        val zone = ZoneOffset.UTC
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            selectedDate = Instant
                                .ofEpochMilli(millis)
                                .atZone(zone)
                                .toLocalDate()
                        }
                        showDatePicker = false
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancelar")
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showNewCategory) {
        NewCategoryDialog(
            existingCategories = categories,
            onDismiss = { showNewCategory = false },
            onSave = { name, icon ->
                onCreateCategory(name, icon) { category ->
                    selectedCategoryId = category.id
                }
                showNewCategory = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualTransactionDialog(
    accounts: List<AccountEntity>,
    cards: List<CreditCardDto>,
    categories: List<CategoryDto>,
    initialType: String = "debit",
    forceCardPurchase: Boolean = false,
    initialCardId: Int? = null,
    initialDescription: String = "",
    initialAmount: Double? = null,
    initialDate: LocalDate? = null,
    onCreateCategory: (
        String,
        String?,
        (CategoryDto) -> Unit
    ) -> Unit,
    onDismiss: () -> Unit,
    saveError: String? = null,
    onSave: (
        Int?,
        Int?,
        String,
        Double,
        String,
        String,
        Int?,
        Int,
        String?,
        Int,
        (Boolean) -> Unit
    ) -> Unit
) {
    var selectedAccountId by remember {
        mutableStateOf<Int?>(null)
    }
    var selectedCardId by remember(
        forceCardPurchase,
        initialCardId,
        cards
    ) {
        mutableStateOf<Int?>(
            if (forceCardPurchase) {
                cards.firstOrNull {
                    it.id == initialCardId
                }?.id
                    ?: cards.firstOrNull()?.id
            } else {
                initialCardId
            }
        )
    }
    var selectedCategoryId by remember {
        mutableStateOf<Int?>(null)
    }
    var accountMenu by remember {
        mutableStateOf(false)
    }
    var cardMenu by remember {
        mutableStateOf(false)
    }
    var categoryMenu by remember {
        mutableStateOf(false)
    }
    var installmentMenu by remember {
        mutableStateOf(false)
    }
    var showNewCategory by remember {
        mutableStateOf(false)
    }
    var description by remember(initialDescription) {
        mutableStateOf(initialDescription)
    }
    var amountDigits by remember(initialAmount) {
        mutableStateOf(
            initialAmount
                ?.takeIf { it > 0.0 }
                ?.let { (kotlin.math.abs(it) * 100).toLong().toString() }
                .orEmpty()
        )
    }
    var type by remember(
        initialType,
        forceCardPurchase
    ) {
        mutableStateOf(
            if (
                !forceCardPurchase &&
                initialType == "credit"
            ) {
                "credit"
            } else {
                "debit"
            }
        )
    }

    var selectedDate by remember(initialDate) {
        mutableStateOf(initialDate ?: LocalDate.now())
    }
    var showDatePicker by remember {
        mutableStateOf(false)
    }
    var installmentCount by remember { mutableStateOf(1) }
    var customInstallments by remember { mutableStateOf(false) }
    var customInstallmentText by remember { mutableStateOf("") }
    var recurringExpense by remember { mutableStateOf(false) }
    var recurringMonths by remember { mutableStateOf(12) }

    val dateFormatter = remember {
        DateTimeFormatter.ofPattern(
            "dd/MM/yyyy"
        )
    }

    val totalValue =
        BrlMoney.toDouble(
            amountDigits
        )

    val selectedAccount = accounts.firstOrNull { it.id == selectedAccountId }
    val cardsFromSelectedBank = remember(cards, selectedAccountId, accounts) {
        val bank = accounts.firstOrNull { it.id == selectedAccountId }?.institutionName?.trim()
        if (bank.isNullOrBlank()) emptyList() else cards.filter { canonicalBankKey(it.bankName) == canonicalBankKey(bank) }
    }

    LaunchedEffect(selectedAccountId, cards) {
        val selected = cards.firstOrNull { it.id == selectedCardId }
        val bank = selectedAccount?.institutionName?.trim()
        if (!forceCardPurchase && selected != null && (bank.isNullOrBlank() || canonicalBankKey(selected.bankName) != canonicalBankKey(bank))) {
            selectedCardId = null
            installmentCount = 1
        }
    }

    var saving by remember { mutableStateOf(false) }
    val canSave = !saving && description.isNotBlank() && totalValue > 0.0 && (!forceCardPurchase || selectedCardId != null)
    val save: (Boolean) -> Unit = { another ->
        if (canSave) {
            saving = true

                    val raw =
                        BrlMoney.toDouble(
                            amountDigits
                        )

                    val effectiveType =
                        if (
                            forceCardPurchase ||
                            selectedCardId != null
                        ) {
                            "debit"
                        } else {
                            type
                        }

                    val signed =
                        if (effectiveType == "debit") {
                            -abs(raw)
                        } else {
                            abs(raw)
                        }

                    val currentTime =
                        LocalTime.now()
                    val localZone =
                        ZoneId.systemDefault()

                    val purchaseIso =
                        selectedDate
                            .atTime(currentTime)
                            .atZone(localZone)
                            .toOffsetDateTime()
                            .toString()

                    // A fatura e definida automaticamente pela data da compra
                    // e pelo dia de fechamento configurado no cartao.
                    val chargeIso = purchaseIso

                    onSave(
                        selectedAccountId,
                        selectedCategoryId,
                        description,
                        signed,
                        effectiveType,
                        purchaseIso,
                        selectedCardId,
                        installmentCount,
                        chargeIso,
                        if (recurringExpense && effectiveType == "debit") recurringMonths else 1
                    ) { success ->
                        saving = false
                        if (success) {
                            if (another) {
                                description = ""
                                amountDigits = ""
                                installmentCount = 1
                                recurringExpense = false
                            } else onDismiss()
                        }
                    }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(
                when {
                    forceCardPurchase ->
                        "Nova compra no cartão"

                    type == "credit" ->
                        "Adicionar nova entrada"

                    else ->
                        "Adicionar nova despesa"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 580.dp)
                    .verticalScroll(
                        rememberScrollState()
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {
                saveError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!forceCardPurchase) {
                    Box {
                    OutlinedButton(
                        onClick = {
                            accountMenu = true
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            accounts.firstOrNull {
                                it.id ==
                                    selectedAccountId
                            }?.let {
                                it.institutionName +
                                    " - " +
                                    (
                                        it.accountName
                                            ?: "Conta"
                                        )
                            }
                                ?: "Sem conta vinculada"
                        )
                    }

                    DropdownMenu(
                        expanded = accountMenu,
                        onDismissRequest = {
                            accountMenu = false
                        }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Sem conta vinculada"
                                )
                            },
                            onClick = {
                                selectedAccountId =
                                    null
                                accountMenu = false
                            }
                        )

                        accounts.forEach {
                                account ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        account
                                            .institutionName +
                                            " - " +
                                            (
                                                account
                                                    .accountName
                                                    ?: "Conta"
                                                )
                                    )
                                },
                                onClick = {
                                    selectedAccountId = account.id
                                    val currentCard = cards.firstOrNull { it.id == selectedCardId }
                                    if (currentCard != null && canonicalBankKey(currentCard.bankName) != canonicalBankKey(account.institutionName)) {
                                        selectedCardId = null
                                        installmentCount = 1
                                    }
                                    accountMenu = false
                                }
                            )
                        }
                    }
                }

                }

                if (
                    type == "debit" &&
                    cards.isNotEmpty()
                ) {
                    Column(
                        verticalArrangement =
                            Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            if (forceCardPurchase) {
                                "Cartão selecionado"
                            } else {
                                "Cartão (opcional)"
                            },
                            style =
                                MaterialTheme.typography.labelMedium,
                            fontWeight =
                                FontWeight.SemiBold
                        )

                        Box {
                        OutlinedButton(
                            onClick = {
                                cardMenu = true
                            },
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            val card =
                                cards.firstOrNull {
                                    it.id ==
                                        selectedCardId
                                }

                            Text(
                                card?.let {
                                    "${it.bankName} • ${it.brand} •••• ${it.lastFour}"
                                }
                                    ?: if (forceCardPurchase) {
                                        "Selecione o cartão"
                                    } else if (selectedAccountId == null) {
                                        "Selecione o banco/conta primeiro"
                                    } else if (cardsFromSelectedBank.isEmpty()) {
                                        "Nenhum cartão deste banco"
                                    } else {
                                        "Sem cartão"
                                    }
                            )
                        }

                        DropdownMenu(
                            expanded = cardMenu,
                            onDismissRequest = {
                                cardMenu = false
                            }
                        ) {
                            if (!forceCardPurchase) {
                                DropdownMenuItem(
                                text = {
                                    Text("Sem cartão")
                                },
                                onClick = {
                                    selectedCardId =
                                        null
                                    installmentCount =
                                        1
                                    cardMenu = false
                                }
                            )

                            }

                            val availableCards = if (forceCardPurchase) cards else cardsFromSelectedBank
                            availableCards.forEach { card ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${card.bankName} • ${card.brand} •••• ${card.lastFour}"
                                        )
                                    },
                                    onClick = {
                                        selectedCardId =
                                            card.id
                                        type = "debit"
                                        cardMenu = false
                                    }
                                )
                            }
                        }
                    }
                    }
                }

                Box {
                    OutlinedButton(
                        onClick = {
                            categoryMenu = true
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            categories.firstOrNull {
                                it.id ==
                                    selectedCategoryId
                            }?.name
                                ?: "Sem categoria"
                        )
                    }

                    DropdownMenu(
                        expanded = categoryMenu,
                        onDismissRequest = {
                            categoryMenu = false
                        }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text("Sem categoria")
                            },
                            onClick = {
                                selectedCategoryId =
                                    null
                                categoryMenu = false
                            }
                        )

                        categories.forEach {
                                category ->
                            DropdownMenuItem(
                                text = {
                                    Text(category.name)
                                },
                                onClick = {
                                    selectedCategoryId =
                                        category.id
                                    categoryMenu = false
                                }
                            )
                        }

                        HorizontalDivider()

                        DropdownMenuItem(
                            text = {
                                Text(
                                    "+ Nova categoria"
                                )
                            },
                            onClick = {
                                categoryMenu = false
                                showNewCategory = true
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        description = it
                    },
                    label = {
                        Text("Descrição")
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                )

                // Mesmo comportamento do campo "Limite do cartão" em Inserir novo cartão.
                OutlinedTextField(
                    amountDigits,
                    { amountDigits = BrlMoney.digits(it) },
                    Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            if (selectedCardId != null && installmentCount > 1) {
                                "Valor total da compra"
                            } else {
                                "Valor"
                            }
                        )
                    },
                    placeholder = { Text("R$ 0,00") },
                    singleLine = true,
                    visualTransformation = BrlMoneyVisualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                OutlinedButton(
                    onClick = {
                        showDatePicker = true
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Data da compra: ${
                            selectedDate.format(
                                dateFormatter
                            )
                        }"
                    )
                }

                if (type == "debit") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = recurringExpense,
                            onCheckedChange = { enabled ->
                                recurringExpense = enabled
                                if (enabled && selectedCardId != null) {
                                    installmentCount = 1
                                    customInstallments = false
                                    customInstallmentText = ""
                                }
                            }
                        )
                        Column {
                            Text(
                                if (selectedCardId != null || forceCardPurchase)
                                    "Despesa recorrente no cartão"
                                else
                                    "Despesa recorrente",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (selectedCardId != null || forceCardPurchase)
                                    "Cria uma nova compra mensal neste cartão"
                                else
                                    "Repete este lançamento mensalmente",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (recurringExpense) {
                        Text(
                            "Repetir por $recurringMonths meses",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = recurringMonths.toFloat(),
                            onValueChange = { recurringMonths = it.toInt().coerceIn(2, 60) },
                            valueRange = 2f..60f,
                            steps = 57
                        )
                    }
                }

                if (
                    selectedCardId != null &&
                    type == "debit" &&
                    !recurringExpense
                ) {
                    Text(
                        if (installmentCount == 1) "Parcelas: à vista / 1x" else "Parcelas: ${installmentCount}x",
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = installmentCount.coerceIn(1, 48).toFloat(),
                        onValueChange = {
                            customInstallments = false
                            installmentCount = it.toInt().coerceIn(1, 48)
                        },
                        valueRange = 1f..48f,
                        steps = 46
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = customInstallments,
                            onCheckedChange = {
                                customInstallments = it
                                if (!it) installmentCount = installmentCount.coerceIn(1, 48)
                                else if (installmentCount <= 48) installmentCount = 49
                            }
                        )
                        Text("Mais de 48 parcelas")
                    }
                    if (customInstallments) {
                        OutlinedTextField(
                            value = customInstallmentText,
                            onValueChange = { raw ->
                                customInstallmentText = raw.filter(Char::isDigit).take(3)
                                installmentCount = customInstallmentText.toIntOrNull()?.coerceIn(49, 360) ?: 49
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Quantidade de parcelas") },
                            placeholder = { Text("Ex.: 60") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }

                    if (
                        totalValue > 0.0 &&
                        installmentCount > 1
                    ) {
                        val installmentValue =
                            totalValue /
                                installmentCount

                        Text(
                            "≈ ${
                                NumberFormat
                                    .getCurrencyInstance(
                                        Locale(
                                            "pt",
                                            "BR"
                                        )
                                    )
                                    .format(
                                        installmentValue
                                    )
                            } por mês",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            fontWeight =
                                FontWeight.SemiBold
                        )
                    }
                }

                if (
                    !forceCardPurchase &&
                    selectedCardId == null
                ) {
                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected =
                            type == "debit",
                        onClick = {
                            type = "debit"
                        }
                    )
                    Text("Saída")

                    Spacer(Modifier.width(14.dp))

                    RadioButton(
                        selected =
                            type == "credit",
                        onClick = {
                            type = "credit"
                            selectedCardId = null
                            installmentCount = 1
                        }
                    )
                    Text("Entrada")
                }
                }
            }
        },
        confirmButton = {
            Column {
                Button(onClick={save(true)},enabled=canSave) { Text("Salvar e cadastrar outra") }
                TextButton(onClick={save(false)},enabled=canSave) { Text("Salvar") }
            }
        },
        dismissButton = { TextButton(onClick=onDismiss,enabled=!saving) { Text("Cancelar") } }
    )

    if (showDatePicker) {
        val zone = ZoneOffset.UTC
        val pickerState =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    selectedDate
                        .atStartOfDay(zone)
                        .toInstant()
                        .toEpochMilli()
            )

        DatePickerDialog(
            onDismissRequest = {
                showDatePicker = false
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState
                            .selectedDateMillis
                            ?.let {
                                selectedDate =
                                    Instant
                                        .ofEpochMilli(
                                            it
                                        )
                                        .atZone(zone)
                                        .toLocalDate()

                            }

                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                    }
                ) {
                    Text("Cancelar")
                }
            }
        ) {
            DatePicker(
                state = pickerState
            )
        }
    }

    if (showNewCategory) {
        NewCategoryDialog(
            existingCategories = categories,
            onDismiss = {
                showNewCategory = false
            },
            onSave = { name, icon ->
                onCreateCategory(name, icon) {
                        category ->
                    selectedCategoryId =
                        category.id
                }
                showNewCategory = false
            }
        )
    }
}



private fun transactionFormatCurrencyFromDigits(
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

private fun transactionParseTxDate(
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

private fun transactionParseTxTime(
    raw: String
): LocalTime? {
    if (raw.isBlank()) {
        return null
    }

    return runCatching {
        OffsetDateTime
            .parse(raw)
            .atZoneSameInstant(
                ZoneId.systemDefault()
            )
            .toLocalTime()
    }.getOrElse {
        runCatching {
            Instant
                .parse(raw)
                .atZone(
                    ZoneId.systemDefault()
                )
                .toLocalTime()
        }.getOrElse {
            runCatching {
                LocalDateTime.parse(raw)
                    .toLocalTime()
            }.getOrNull()
        }
    }
}

private fun transactionFormatDateTimeForDisplay(
    raw: String
): String {
    val date =
        transactionParseTxDate(raw)
    val time =
        transactionParseTxTime(raw)

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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberTransactionNoFutureSelectableDates(): SelectableDates {
    val today =
        remember {
            LocalDate.now()
        }

    return remember(today) {
        object : SelectableDates {
            override fun isSelectableDate(
                utcTimeMillis: Long
            ): Boolean {
                val date =
                    Instant
                        .ofEpochMilli(
                            utcTimeMillis
                        )
                        .atZone(
                            ZoneOffset.UTC
                        )
                        .toLocalDate()

                return !date.isAfter(today)
            }

            override fun isSelectableYear(
                year: Int
            ): Boolean =
                year <= today.year
        }
    }
}
