package com.financeapp.mobile.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financeapp.mobile.data.local.AccountEntity
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.CreditCardDto
import com.financeapp.mobile.data.remote.CategoryDto
import java.text.NumberFormat
import java.text.Normalizer
import java.util.Locale
import java.time.LocalDate
import java.time.YearMonth
import java.time.OffsetDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


private fun isAccountCashFlowTransaction(tx: TransactionEntity): Boolean =
    tx.source != "card_purchase" && !(tx.cardId != null && tx.source != "card_payment")

private fun isCardMovement(tx: TransactionEntity): Boolean =
    tx.source == "card_purchase" || (tx.cardId != null && tx.source != "card_payment")

private fun accountMovementDateTime(raw: String): String {
    val localDateTime = runCatching {
        OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
    }.getOrElse {
        runCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(raw) }.getOrNull()
    }
    if (localDateTime != null) {
        return localDateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy • HH:mm"))
    }
    val date = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
    return date?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) ?: raw
}

private fun movementTypeLabel(tx: TransactionEntity): String = when {
    tx.amount > 0 -> "Entrada"
    tx.amount < 0 -> "Saída"
    else -> tx.transactionType
}

private fun movementSourceLabel(tx: TransactionEntity): String =
    if (tx.source == "open_finance") "Automática" else "Manual"

private fun paymentAccountLabel(account: AccountEntity): String {
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

private fun invoiceWindow(closingDay: Int?, dueDay: Int?, monthOffset: Long = 0): Pair<LocalDate, LocalDate> {
    val invoiceMonth = YearMonth.from(LocalDate.now()).plusMonths(monthOffset)
    val close = (closingDay ?: 25).coerceIn(1, 31)
    val due = (dueDay ?: 1).coerceIn(1, 31)
    // O mes exibido e o mes de vencimento, nao o mes da compra.
    // Quando o vencimento e antes/igual ao fechamento, o ciclo fecha no mes anterior.
    val closingMonth = if (due <= close) invoiceMonth.minusMonths(1) else invoiceMonth
    fun closing(month: YearMonth): LocalDate = month.atDay(close.coerceAtMost(month.lengthOfMonth()))
    val end = closing(closingMonth)
    val previous = closing(closingMonth.minusMonths(1))
    return previous.plusDays(1) to end
}

private fun invoiceDueDate(end: LocalDate, dueDay: Int?): LocalDate? {
    val due = dueDay ?: return null
    var month = YearMonth.from(end)
    if (due <= end.dayOfMonth) month = month.plusMonths(1)
    return month.atDay(due.coerceAtMost(month.lengthOfMonth()))
}

private val BRAZILIAN_CARD_ISSUERS = listOf(
    "Banco do Brasil",
    "Caixa Econômica Federal",
    "Itaú Unibanco",
    "Bradesco",
    "Santander",
    "Nubank",
    "Banco Inter",
    "C6 Bank",
    "BTG Pactual",
    "Banco PAN",
    "Banco Original",
    "Banco Safra",
    "Banco BV",
    "Banco Daycoval",
    "Banco BMG",
    "Banco Mercantil",
    "Banco ABC Brasil",
    "Banco Alfa",
    "Banco Pine",
    "Banco Master",
    "Banco Digio",
    "Will Bank",
    "Neon",
    "Next",
    "PagBank",
    "PicPay",
    "Mercado Pago",
    "99Pay",
    "RecargaPay",
    "Stone",
    "Ton",
    "InfinitePay",
    "Sicredi",
    "Sicoob",
    "Banrisul",
    "Banestes",
    "BRB",
    "Banco da Amazônia",
    "Banco do Nordeste",
    "Credicard",
    "Porto Bank",
    "XP",
    "Rico",
    "Genial",
    "Nomad",
    "Cora",
    "Conta Simples",
    "Banco BS2",
    "Banco Sofisa Direto",
    "Banco Bari",
    "Banco Rendimento",
    "Banco Topázio",
    "Banco Fibra",
    "Banco Industrial do Brasil",
    "Banco Votorantim",
    "Banco Carrefour",
    "Banco Cetelem",
    "Banco CSF",
    "Banco Volkswagen",
    "Banco Toyota",
    "Banco Honda",
    "Banco Mercedes-Benz",
    "Banco GM",
    "Banco Yamaha",
    "Banco Hyundai Capital",
    "Ame Digital",
    "MagaluPay",
    "iti Itaú",
    "Zro Bank",
    "Banco Arbi",
    "Banco Paulista",
    "Banco Semear",
    "Banco Triângulo",
    "Unicred",
    "Ailos",
    "Cresol",
    "Outro"
)


private fun normalizedBankKey(value: String): String =
    Normalizer.normalize(value.trim().lowercase(Locale("pt", "BR")), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

private fun isGenericBankName(value: String): Boolean {
    val n = normalizedBankKey(value)
    return n.isBlank() || n == "conta manual" || n == "banco manual" || n == "manual" || n == "conta"
}

private fun accountVisualBankName(account: AccountEntity): String {
    val institution = account.institutionName.trim()
    val accountName = account.accountName?.trim().orEmpty()
    return when {
        !isGenericBankName(institution) -> institution
        !isGenericBankName(accountName) -> accountName
        institution.isNotBlank() -> institution
        accountName.isNotBlank() -> accountName
        else -> "Banco"
    }
}

private data class BankVisual(
    val mark: String,
    val background: Color,
    val foreground: Color
)

private fun bankVisual(bankName: String): BankVisual {
    val n = normalizedBankKey(bankName)
    return when {
        "nubank" in n || n == "nu" -> BankVisual("nu", Color(0xFF820AD1), Color.White)
        "itau" in n || "341" in n -> BankVisual("itaú", Color(0xFFFF7A00), Color(0xFF132F63))
        "santander" in n || "033" in n -> BankVisual("S", Color(0xFFEC0000), Color.White)
        "bradesco" in n || "237" in n -> BankVisual("B", Color(0xFFCC092F), Color.White)
        "caixa" in n || "104" in n -> BankVisual("X", Color(0xFF0067B1), Color(0xFFFFB81C))
        ("banco" in n && "brasil" in n) || n == "bb" || n.startsWith("bb ") || n == "001" || n.startsWith("001 ") -> BankVisual("BB", Color(0xFFFFD900), Color(0xFF163A70))
        "inter" in n || "077" in n -> BankVisual("inter", Color(0xFFFF6B00), Color.White)
        "c6" in n || "336" in n -> BankVisual("C6", Color(0xFF111111), Color.White)
        "btg" in n || "208" in n -> BankVisual("BTG", Color(0xFF123B70), Color.White)
        "picpay" in n -> BankVisual("P", Color(0xFF21C25E), Color.White)
        "mercado pago" in n -> BankVisual("MP", Color(0xFF19A8E0), Color.White)
        "sicredi" in n -> BankVisual("S", Color(0xFF48A23F), Color.White)
        "sicoob" in n -> BankVisual("S", Color(0xFF126B5A), Color.White)
        "banrisul" in n -> BankVisual("B", Color(0xFF1565C0), Color.White)
        "neon" in n -> BankVisual("N", Color(0xFF00AEEF), Color.White)
        "next" in n -> BankVisual("next", Color(0xFF111111), Color(0xFF00E676))
        "pagbank" in n || "pagseguro" in n -> BankVisual("P", Color(0xFF00A868), Color.White)
        else -> BankVisual(
            bankName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.take(1).uppercase() }.ifBlank { "B" },
            Color(0xFFEAF2FF),
            Color(0xFF173B70)
        )
    }
}

@Composable
private fun BankLogoBadge(
    bankName: String,
    modifier: Modifier = Modifier.size(48.dp)
) {
    val visual = remember(bankName) { bankVisual(bankName) }
    val normalized = normalizedBankKey(bankName)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = visual.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(5.dp)) {
            when {
                ("banco" in normalized && "brasil" in normalized) || normalized == "bb" || normalized.startsWith("bb ") || normalized == "001" || normalized.startsWith("001 ") -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((-3).dp)) {
                        Text("BB", color = visual.foreground, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("Brasil", color = visual.foreground, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
                "itau" in normalized || "341" in normalized -> Text("itaú", color = visual.foreground, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                "nubank" in normalized -> Text("nu", color = visual.foreground, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                "inter" in normalized -> Text("inter", color = visual.foreground, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                "santander" in normalized -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("S", color = visual.foreground, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                    Text("Santander", color = visual.foreground, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                "bradesco" in normalized -> Text("bradesco", color = visual.foreground, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                "caixa" in normalized -> Text("X", color = visual.foreground, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                "c6" in normalized -> Text("C6", color = visual.foreground, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                else -> Text(visual.mark, color = visual.foreground, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CardMiniature(card: CreditCardDto) {
    val visual = remember(card.bankName) { bankVisual(card.bankName) }
    Surface(
        modifier = Modifier.width(86.dp).height(54.dp),
        shape = RoundedCornerShape(12.dp),
        color = visual.background,
        tonalElevation = 0.dp
    ) {
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            Text(
                visual.mark,
                color = visual.foreground,
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.TopStart)
            )
            Surface(
                modifier = Modifier.width(17.dp).height(12.dp).align(Alignment.CenterStart),
                shape = RoundedCornerShape(3.dp),
                color = Color(0xFFFFD88A).copy(alpha = 0.95f)
            ) {}
            Text(
                "•••• ${card.lastFour}",
                color = visual.foreground,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
private fun SectionSelector(selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(Modifier.padding(4.dp)) {
            listOf("Bancos", "Cartões").forEachIndexed { index, label ->
                val isSelected = selected == index
                Surface(
                    modifier = Modifier.weight(1f).clickable { onSelect(index) },
                    shape = RoundedCornerShape(11.dp),
                    color = if (isSelected) Color(0xFF1769D2) else Color.Transparent,
                    tonalElevation = if (isSelected) 2.dp else 0.dp
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(vertical = 11.dp),
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountsList(
    guest: Boolean = false,
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
    categories: List<CategoryDto>,
    cards: List<CreditCardDto>,
    onCreateCard: (String, String, String, String?, Double?, Int?, Int?) -> Unit,
    onPayCardInvoice: (Int, Int, String, Double, String, (Boolean) -> Unit) -> Unit,
    onDeleteCard: (Int) -> Unit,
    onUpdateCardDetails:
        (Int, String, String, String, String?, Double?, Int?, Int?) -> Unit,
    onUpdateAccountDetails:
        (Int, String, String?, String?) -> Unit,
    onCreateManualAccount: (String, String?, String?) -> Unit,
    onConnectBank: () -> Unit,
    onDisconnect: (AccountEntity) -> Unit,
    onReconnect: (AccountEntity) -> Unit,
    onDelete: (AccountEntity) -> Unit,
    onOpenInvoices: () -> Unit,
    onEditCardTransaction: (TransactionEntity) -> Unit
) {
    var showNewCard by remember {
        mutableStateOf(false)
    }
    var showNewManualAccount by remember { mutableStateOf(false) }
    var pendingCardDelete by remember {
        mutableStateOf<CreditCardDto?>(null)
    }
    var editingCard by remember {
        mutableStateOf<CreditCardDto?>(null)
    }
    var viewingCard by remember { mutableStateOf<CreditCardDto?>(null) }
    var payingCard by remember { mutableStateOf<CreditCardDto?>(null) }
    var invoiceMonthOffset by remember { mutableStateOf(0L) }
    var editingAccount by remember {
        mutableStateOf<AccountEntity?>(null)
    }
    var viewingAccount by remember {
        mutableStateOf<AccountEntity?>(null)
    }
    var showBalances by remember {
        mutableStateOf(false)
    }
    var selectedSection by remember { mutableStateOf(0) }
    var addAccountMenuExpanded by remember { mutableStateOf(false) }
    val currency = remember {
        NumberFormat.getCurrencyInstance(
            Locale("pt", "BR")
        )
    }
    // Mesma regra do Dashboard: quando o banco possui um saldo-base,
    // soma somente movimentações de caixa posteriores à atualização desse saldo.
    // Assim a aba Contas não fica presa ao saldo antigo depois de uma importação.
    val consolidatedBalance = remember(accounts, transactions) {
        accounts.sumOf { account ->
            val cashTransactions = transactions.filter {
                it.accountId == account.id && isAccountCashFlowTransaction(it)
            }
            val baseBalance = account.currentBalance
            if (baseBalance == null) {
                cashTransactions.sumOf { it.amount }
            } else {
                val balanceDate = account.balanceUpdatedAt?.let { raw ->
                    runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull()
                        ?: runCatching { java.time.Instant.parse(raw) }.getOrNull()
                        ?: runCatching { java.time.LocalDateTime.parse(raw).toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
                        ?: runCatching { java.time.LocalDate.parse(raw.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() }.getOrNull()
                }
                val adjustments = cashTransactions
                    .asSequence()
                    .filter { it.source != "open_finance" }
                    .filter { tx ->
                        val txInstant = runCatching { java.time.OffsetDateTime.parse(tx.date).toInstant() }.getOrNull()
                            ?: runCatching { java.time.Instant.parse(tx.date) }.getOrNull()
                            ?: runCatching { java.time.LocalDateTime.parse(tx.date).toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
                            ?: runCatching { java.time.LocalDate.parse(tx.date.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() }.getOrNull()
                        balanceDate == null || txInstant == null || txInstant.isAfter(balanceDate)
                    }
                    .sumOf { it.amount }
                baseBalance + adjustments
            }
        }
    }

    if (showNewManualAccount) {
        var manualAccountName by remember { mutableStateOf("") }
        var manualAccountAgency by remember { mutableStateOf("") }
        var manualAccountNumber by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewManualAccount = false },
            title = { Text("Adicionar conta manual") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Cadastre uma conta para organizar lançamentos manuais. Ela não importa saldo nem movimentações do banco.")
                    OutlinedTextField(
                        value = manualAccountName,
                        onValueChange = { if (it.length <= 160) manualAccountName = it },
                        label = { Text("Nome da conta *") },
                        supportingText = { Text("Obrigatório") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = manualAccountAgency,
                        onValueChange = { if (it.length <= 30) manualAccountAgency = it },
                        label = { Text("Agência") },
                        supportingText = { Text("Opcional") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = manualAccountNumber,
                        onValueChange = { if (it.length <= 50) manualAccountNumber = it },
                        label = { Text("Número da conta") },
                        supportingText = { Text("Opcional") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = manualAccountName.isNotBlank(),
                    onClick = {
                        onCreateManualAccount(manualAccountName.trim(), manualAccountAgency.trim().ifBlank { null }, manualAccountNumber.trim().ifBlank { null })
                        showNewManualAccount = false
                    }
                ) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { showNewManualAccount = false }) { Text("Cancelar") } }
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Bancos e cartões",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    FilledIconButton(
                        onClick = {
                            if (selectedSection == 0) addAccountMenuExpanded = true else showNewCard = true
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF246BFD))
                    ) {
                        Icon(Icons.Default.Add, contentDescription = if (selectedSection == 0) "Adicionar conta" else "Adicionar cartão")
                    }
                    DropdownMenu(
                        expanded = selectedSection == 0 && addAccountMenuExpanded,
                        onDismissRequest = { addAccountMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Adicionar manual") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = { addAccountMenuExpanded = false; showNewManualAccount = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Open Finance — Em desenvolvimento") },
                            leadingIcon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
                            enabled = false,
                            onClick = { }
                        )
                    }
                }
            }
        }

        item {
            SectionSelector(selected = selectedSection, onSelect = { selectedSection = it })
        }

        if (selectedSection == 0) {
            if (accounts.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Saldo consolidado", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (showBalances) currency.format(consolidatedBalance) else "••••••",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = { showBalances = !showBalances }) {
                                Icon(
                                    if (showBalances) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showBalances) "Ocultar saldos" else "Mostrar saldos",
                                    tint = Color(0xFF246BFD)
                                )
                            }
                        }
                    }
                }
            }

            items(accounts, key = { "account-${it.id}" }) { account ->
                val accountTransactions = transactions.filter { tx -> tx.accountId == account.id }
                val displayedBalance = account.currentBalance
                    ?: accountTransactions.filter(::isAccountCashFlowTransaction).sumOf { tx -> tx.amount }
                val statusText = when (account.connectionStatus) {
                    "connected" -> "Conectado"
                    "disconnected" -> "Desconectado"
                    else -> "Manual"
                }
                val statusColor = when (account.connectionStatus) {
                    "connected" -> Color(0xFF178A3A)
                    "disconnected" -> Color(0xFFC62828)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                var accountMenuExpanded by remember(account.id) { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth().clickable { viewingAccount = account },
                    shape = RoundedCornerShape(15.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val visualBankName = accountVisualBankName(account)
                        BankLogoBadge(visualBankName)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(visualBankName, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                account.accountName?.takeIf { it.isNotBlank() && !it.equals("Conta manual", true) } ?: "Conta bancária",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            account.maskedAccount?.takeIf { it.isNotBlank() }?.let {
                                Text("Conta •••$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                if (showBalances) currency.format(displayedBalance) else "••••••",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.SemiBold)
                        }
                        Box {
                            IconButton(onClick = { accountMenuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Opções da conta", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = accountMenuExpanded, onDismissRequest = { accountMenuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Movimentações") }, onClick = { accountMenuExpanded = false; viewingAccount = account })
                                DropdownMenuItem(text = { Text("Editar") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { accountMenuExpanded = false; editingAccount = account })
                                if (account.externalAccountId != null) {
                                    DropdownMenuItem(
                                        text = { Text(if (account.connectionStatus == "connected") "Desconectar" else "Conectar") },
                                        leadingIcon = { Icon(if (account.connectionStatus == "connected") Icons.Default.LinkOff else Icons.Default.Link, null) },
                                        onClick = {
                                            accountMenuExpanded = false
                                            if (account.connectionStatus == "connected") onDisconnect(account) else onReconnect(account)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { accountMenuExpanded = false; onDelete(account) }
                                )
                            }
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }

            if (accounts.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text("Nenhuma conta conectada.", modifier = Modifier.fillMaxWidth().padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            item {
                Text(
                    "Seus cartões",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            items(items = cards, key = { "card-${it.id}" }) { card ->
                val invoiceRange = invoiceWindow(card.closingDay, card.dueDay)
                val cardSpent = (-transactions.filter { tx ->
                    tx.cardId == card.id && tx.source != "card_payment" && runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull()?.let { !it.isBefore(invoiceRange.first) && !it.isAfter(invoiceRange.second) } == true
                }.sumOf { it.amount }).coerceAtLeast(0.0)
                var cardMenuExpanded by remember(card.id) { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth().clickable { invoiceMonthOffset = 0L; viewingCard = card },
                    shape = RoundedCornerShape(15.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CardMiniature(card)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(card.nickname ?: card.bankName, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                            Text("${card.bankName} • ${card.brand}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("•••• ${card.lastFour}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text("Fatura atual: ${currency.format(cardSpent)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            card.creditLimit?.let { limit ->
                                Text("Disponível: ${currency.format((limit - cardSpent).coerceAtLeast(0.0))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (card.closingDay != null || card.dueDay != null) {
                                Text(
                                    listOfNotNull(card.closingDay?.let { "Fecha $it" }, card.dueDay?.let { "Vence $it" }).joinToString(" • "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { cardMenuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Opções do cartão", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = cardMenuExpanded, onDismissRequest = { cardMenuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("Central de Faturas") },
                                    leadingIcon = { Icon(Icons.Default.CreditCard, null) },
                                    onClick = { cardMenuExpanded = false; onOpenInvoices() }
                                )
                                DropdownMenuItem(text = { Text("Editar") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { cardMenuExpanded = false; editingCard = card })
                                DropdownMenuItem(
                                    text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { cardMenuExpanded = false; pendingCardDelete = card }
                                )
                            }
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }

            if (cards.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CreditCard, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("Nenhum cartão cadastrado.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    viewingCard?.let { card ->
        val range = invoiceWindow(card.closingDay, card.dueDay, invoiceMonthOffset)
        val cardTransactions = transactions.filter { tx ->
            tx.cardId == card.id && tx.source != "card_payment" && runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull()?.let { !it.isBefore(range.first) && !it.isAfter(range.second) } == true
        }.sortedByDescending { it.date }
        val invoicePayments = transactions.filter { tx ->
            tx.cardId == card.id && tx.source == "card_payment" && tx.purchaseDate?.take(10) == range.second.toString()
        }.sortedByDescending { it.date }
        // Compras são negativas; estornos/devoluções no cartão são positivos e reduzem a fatura.
        val spent = (-cardTransactions.sumOf { it.amount }).coerceAtLeast(0.0)
        val paid = invoicePayments.sumOf { kotlin.math.abs(it.amount) }
        val remaining = (spent - paid).coerceAtLeast(0.0)
        val dueDate = invoiceDueDate(range.second, card.dueDay)
        val available = card.creditLimit?.let { (it - spent).coerceAtLeast(0.0) }
        val invoiceCompetence = YearMonth.from(LocalDate.now()).plusMonths(invoiceMonthOffset)
        val competence = "${invoiceCompetence.monthValue.toString().padStart(2, '0')}/${invoiceCompetence.year}"
        AlertDialog(
            onDismissRequest = { viewingCard = null; invoiceMonthOffset = 0L },
            title = { Text(card.nickname ?: card.bankName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${card.bankName} • ${card.brand} •••• ${card.lastFour}")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { invoiceMonthOffset-- }) { Text("‹ Anterior") }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Fatura $competence", fontWeight = FontWeight.Bold)
                            if (invoiceMonthOffset == 0L) Text("Atual", style = MaterialTheme.typography.labelSmall)
                            else if (invoiceMonthOffset > 0L) Text("Futura", style = MaterialTheme.typography.labelSmall)
                            else Text("Anterior", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { invoiceMonthOffset++ }) { Text("Próxima ›") }
                    }
                    val invoiceStatusText: String
                    val invoiceStatusBackground: Color
                    val invoiceStatusForeground: Color
                    when {
                        spent <= 0.0 -> {
                            invoiceStatusText = "Sem compras"
                            invoiceStatusBackground = MaterialTheme.colorScheme.surfaceVariant
                            invoiceStatusForeground = MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        remaining <= 0.005 -> {
                            invoiceStatusText = "Fatura paga"
                            invoiceStatusBackground = Color(0xFFDDF3E4)
                            invoiceStatusForeground = Color(0xFF24713A)
                        }
                        paid > 0.0 -> {
                            invoiceStatusText = "Pendente de pagamento"
                            invoiceStatusBackground = Color(0xFFFFE9C7)
                            invoiceStatusForeground = Color(0xFF9A5A00)
                        }
                        else -> {
                            invoiceStatusText = "A pagar"
                            invoiceStatusBackground = Color(0xFFFFDEDE)
                            invoiceStatusForeground = Color(0xFFB3261E)
                        }
                    }
                    Surface(
                        color = invoiceStatusBackground,
                        contentColor = invoiceStatusForeground,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            invoiceStatusText,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("Valor total da fatura", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        currency.format(spent),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    dueDate?.let { due ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                                Text(
                                    "VENCIMENTO",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${due.dayOfMonth.toString().padStart(2,'0')}/${due.monthValue.toString().padStart(2,'0')}/${due.year}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Text("Pago: ${currency.format(paid)} • Restante: ${currency.format(remaining)}", fontWeight = if (remaining > 0.005) FontWeight.SemiBold else FontWeight.Normal)
                    card.creditLimit?.let { Text("Limite total: ${currency.format(it)}") }
                    available?.let { Text("Limite disponível nesta fatura: ${currency.format(it)}") }
                    Text("Período: ${range.first.dayOfMonth.toString().padStart(2,'0')}/${range.first.monthValue.toString().padStart(2,'0')}/${range.first.year} a ${range.second.dayOfMonth.toString().padStart(2,'0')}/${range.second.monthValue.toString().padStart(2,'0')}/${range.second.year}")
                    HorizontalDivider()
                    Text("Transações da fatura (${cardTransactions.size}) • Toque para editar", fontWeight=FontWeight.SemiBold)
                    if (cardTransactions.isEmpty()) Text("Nenhuma transação encontrada nesta fatura. Confira a data da compra e o fechamento do cartão.") else LazyColumn(Modifier.fillMaxWidth().heightIn(max=340.dp)) {
                        items(cardTransactions, key={ "card-detail-${it.id}" }) { tx ->
                            Row(Modifier.fillMaxWidth().clickable { onEditCardTransaction(tx) }.padding(vertical=8.dp), horizontalArrangement=Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(tx.description, maxLines=1)
                                    Text(listOfNotNull(tx.date.take(10), if (tx.installmentTotal != null && tx.installmentTotal > 1) "${tx.installmentNumber ?: 1}/${tx.installmentTotal}" else null).joinToString(" • "), style=MaterialTheme.typography.bodySmall)
                                }
                                Text(currency.format(kotlin.math.abs(tx.amount)), fontWeight=FontWeight.SemiBold)
                                IconButton(onClick = { onEditCardTransaction(tx) }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Editar compra do cartao")
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Histórico de pagamentos (${invoicePayments.size})", fontWeight=FontWeight.SemiBold)
                    if (invoicePayments.isEmpty()) {
                        Text("Nenhum pagamento registrado para esta fatura.", style=MaterialTheme.typography.bodySmall)
                    } else {
                        invoicePayments.forEach { payment ->
                            Text("${payment.date.take(10)} • ${currency.format(kotlin.math.abs(payment.amount))}", style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton={
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (remaining > 0.005 && accounts.isNotEmpty() && invoiceMonthOffset <= 0L) {
                        Button(onClick={ payingCard=card }) { Text(if (invoiceMonthOffset < 0L) "Registrar pagamento anterior" else "Registrar pagamento") }
                    }
                    TextButton(onClick={ viewingCard=null; invoiceMonthOffset=0L }) { Text("Fechar") }
                }
            }
        )
    }

    payingCard?.let { card ->
        val range = invoiceWindow(card.closingDay, card.dueDay, invoiceMonthOffset)
        val purchases = transactions.filter { tx ->
            tx.cardId == card.id && tx.source != "card_payment" && runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull()?.let { !it.isBefore(range.first) && !it.isAfter(range.second) } == true
        }
        val payments = transactions.filter { it.cardId == card.id && it.source == "card_payment" && it.purchaseDate?.take(10) == range.second.toString() }
        val remaining = (purchases.filter { it.amount < 0 }.sumOf { -it.amount } - payments.sumOf { kotlin.math.abs(it.amount) }).coerceAtLeast(0.0)
        var selectedAccountId by remember(card.id) { mutableStateOf(accounts.firstOrNull()?.id) }
        var accountMenu by remember { mutableStateOf(false) }
        fun centsText(value: Double): String = kotlin.math.round(value * 100.0).toLong().toString()
        fun formatCurrencyInput(raw: String): String {
            val digits = raw.filter(Char::isDigit).take(15)
            if (digits.isBlank()) return ""
            val value = digits.toLongOrNull()?.div(100.0) ?: 0.0
            return NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(value)
        }
        fun currencyInputValue(raw: String): Double? {
            val digits = raw.filter(Char::isDigit)
            return digits.toLongOrNull()?.div(100.0)
        }
        val brDateFormatter = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")
        var amountDigits by remember(card.id, remaining) { mutableStateOf(BrlMoney.fromDouble(remaining)) }
        var paymentDate by remember(card.id) { mutableStateOf(LocalDate.now()) }
        var showPaymentDatePicker by remember(card.id) { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest={ if (!saving) payingCard=null },
            title={ Text("Registrar pagamento") },
            text={ Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Restante da fatura: ${currency.format(remaining)}")
                Box {
                    OutlinedButton(onClick={accountMenu=true}, modifier=Modifier.fillMaxWidth()) {
                        val account=accounts.firstOrNull { it.id==selectedAccountId }
                        Text(account?.let { paymentAccountLabel(it) } ?: "Selecionar conta", modifier=Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded=accountMenu, onDismissRequest={accountMenu=false}) {
                        accounts.forEach { account -> DropdownMenuItem(text={Text(paymentAccountLabel(account))}, onClick={selectedAccountId=account.id; accountMenu=false}) }
                    }
                }
                OutlinedTextField(
                    amountDigits,
                    { amountDigits = BrlMoney.digits(it) },
                    label={Text("Valor pago")},
                    singleLine=true,
                    visualTransformation=BrlMoneyVisualTransformation, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                    modifier=Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = paymentDate.format(brDateFormatter),
                    onValueChange = {},
                    readOnly = true,
                    label={Text("Data do pagamento")},
                    trailingIcon={ IconButton(onClick={ showPaymentDatePicker=true }) { Text("📅") } },
                    modifier=Modifier.fillMaxWidth().clickable { showPaymentDatePicker=true }
                )
                Text("O pagamento será lançado em Despesas e reduzirá o saldo da conta escolhida. As compras continuam somente em Cartões.", style=MaterialTheme.typography.bodySmall)
            } },
            confirmButton={ Button(onClick={
                val accountId=selectedAccountId ?: return@Button
                val amount=BrlMoney.toDouble(amountDigits)
                if (amount <= 0.0 || amount > remaining + 0.005) return@Button
                val date=paymentDate
                saving=true
                onPayCardInvoice(accountId, card.id, range.second.toString(), amount, date.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime().toString()) { ok -> saving=false; if(ok) payingCard=null }
            }, enabled=!saving && selectedAccountId!=null && (BrlMoney.toDouble(amountDigits).let { it>0 && it<=remaining+0.005 }) ) { if(saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth=2.dp) else Text("Confirmar pagamento") } },
            dismissButton={ TextButton(onClick={payingCard=null}, enabled=!saving) { Text("Cancelar") } }
        )

        if (showPaymentDatePicker) {
        val zone = java.time.ZoneOffset.UTC
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = paymentDate.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showPaymentDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        paymentDate = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                    }
                    showPaymentDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick={ showPaymentDatePicker=false }) { Text("Cancelar") } }
        ) { DatePicker(state = pickerState) }
        }
    }

    viewingAccount?.let { account ->
        val accountTransactions = transactions
            .filter { it.accountId == account.id }
            .sortedByDescending { it.date }
        val accountCashTransactions = accountTransactions.filter(::isAccountCashFlowTransaction)
        val accountCardMovements = accountTransactions.filter(::isCardMovement)
        val displayedBalance = account.currentBalance
            ?: accountCashTransactions.sumOf { it.amount }

        AlertDialog(
            onDismissRequest = { viewingAccount = null },
            title = { Text(account.accountName ?: account.institutionName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "${if (account.currentBalance != null) "Saldo atual" else "Saldo calculado pelas movimentações"}: ${currency.format(displayedBalance)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (accountTransactions.isEmpty()) {
                        Text(
                            "Nenhuma movimentação vinculada a esta conta.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 390.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            item {
                                Text("Movimentações da conta (${accountCashTransactions.size})", style = MaterialTheme.typography.labelLarge)
                            }
                            if (accountCashTransactions.isEmpty()) {
                                item { Text("Nenhuma movimentação da conta.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant) }
                            } else {
                                items(accountCashTransactions, key = { "account-cash-${it.id}" }) { tx ->
                                    val categoryName = categories.firstOrNull { it.id == tx.categoryId }?.name ?: "Sem categoria"
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(tx.description, fontWeight = FontWeight.SemiBold, maxLines=1)
                                            Text(
                                                "${account.institutionName.ifBlank { account.accountName ?: "Sem banco" }} • $categoryName",
                                                style=MaterialTheme.typography.bodySmall,
                                                color=MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines=1
                                            )
                                            Text(
                                                "${accountMovementDateTime(tx.date)} • ${movementTypeLabel(tx)} • ${movementSourceLabel(tx)}",
                                                style=MaterialTheme.typography.bodySmall,
                                                color=MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines=1
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(currency.format(tx.amount), fontWeight=FontWeight.SemiBold, color=when { tx.amount>0 -> Color(0xFF178A3A); tx.amount<0 -> Color(0xFFC62828); else -> MaterialTheme.colorScheme.onSurface })
                                    }
                                    HorizontalDivider()
                                }
                            }
                            item {
                                Spacer(Modifier.height(8.dp))
                                Text("Movimentações do cartão de crédito (${accountCardMovements.size})", style = MaterialTheme.typography.labelLarge)
                                Text("Estas compras não alteram o saldo da conta. O saldo só é reduzido quando a fatura é paga.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (accountCardMovements.isEmpty()) {
                                item { Text("Nenhuma compra de cartão vinculada a esta conta.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant) }
                            } else {
                                items(accountCardMovements, key = { "account-card-${it.id}" }) { tx ->
                                    val categoryName = categories.firstOrNull { it.id == tx.categoryId }?.name ?: "Sem categoria"
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(tx.description, fontWeight = FontWeight.SemiBold, maxLines=1)
                                            Text(
                                                "${account.institutionName.ifBlank { account.accountName ?: "Sem banco" }} • $categoryName",
                                                style=MaterialTheme.typography.bodySmall,
                                                color=MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines=1
                                            )
                                            Text(
                                                "${accountMovementDateTime(tx.date)} • ${movementTypeLabel(tx)} • ${movementSourceLabel(tx)}",
                                                style=MaterialTheme.typography.bodySmall,
                                                color=MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines=1
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(currency.format(kotlin.math.abs(tx.amount)), fontWeight=FontWeight.SemiBold, color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingAccount = null }) { Text("Fechar") }
            }
        )
    }

    if (showNewCard) {
        NewCreditCardDialog(
            accounts = accounts,
            onDismiss = {
                showNewCard = false
            },
            onSave = {
                bank,
                brand,
                lastFour,
                nickname,
                creditLimit,
                closingDay,
                dueDay ->
                onCreateCard(bank, brand, lastFour, nickname, creditLimit, closingDay, dueDay)
                showNewCard = false
            }
        )
    }

    editingAccount?.let {
            account ->
        EditAccountDialog(
            account = account,
            onDismiss = {
                editingAccount = null
            },
            onSave = {
                    institutionName,
                    accountName,
                    maskedAccount ->
                onUpdateAccountDetails(
                    account.id,
                    institutionName,
                    accountName,
                    maskedAccount
                )
                editingAccount = null
            }
        )
    }

    editingCard?.let {
            card ->
        EditCreditCardDialog(
            card = card,
            accounts = accounts,
            onDismiss = {
                editingCard = null
            },
            onSave = {
                    bank,
                    brand,
                    lastFour,
                    nickname,
                    creditLimit,
                    closingDay,
                    dueDay ->
                onUpdateCardDetails(card.id, bank, brand, lastFour, nickname, creditLimit, closingDay, dueDay)
                editingCard = null
            }
        )
    }

    pendingCardDelete?.let { card ->
        AlertDialog(
            onDismissRequest = {
                pendingCardDelete = null
            },
            title = {
                Text("Excluir cartão?")
            },
            text = {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Deseja realmente excluir este cartão?"
                    )
                    Text(
                        "${card.bankName} • ${card.brand} •••• ${card.lastFour}",
                        fontWeight =
                            FontWeight.SemiBold
                    )
                    Text(
                        "O cartão será removido da sua lista. " +
                            "As transações já registradas continuarão no histórico.",
                        style =
                            MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteCard(card.id)
                        pendingCardDelete = null
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                MaterialTheme.colorScheme.error,
                            contentColor =
                                MaterialTheme.colorScheme.onError
                        )
                ) {
                    Text("Excluir cartão")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingCardDelete = null
                    }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

}

@Composable
internal fun NewCreditCardDialog(
    accounts: List<AccountEntity>,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String?, Double?, Int?, Int?) -> Unit
) {
    var selectedAccountId by remember { mutableStateOf<Int?>(null) }
    var accountMenu by remember { mutableStateOf(false) }
    var brand by remember { mutableStateOf("Mastercard") }
    var lastFour by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var creditLimitDigits by remember { mutableStateOf("") }
    var closingDayText by remember { mutableStateOf("") }
    var dueDayText by remember { mutableStateOf("") }
    var brandMenu by remember { mutableStateOf(false) }

    val brands = listOf("Mastercard", "Visa", "Elo", "American Express", "Hipercard", "Outro")
    val selectedAccount = accounts.firstOrNull { it.id == selectedAccountId }
    fun accountLabel(account: AccountEntity): String {
        // A instituição é o banco/nome informado pelo usuário. Em contas manuais,
        // accountName costuma ser apenas o marcador técnico "Conta manual" e não
        // deve substituir o nome do banco na seleção do cartão.
        val bankName = account.institutionName.trim().ifBlank { "Conta" }
        val accountName = account.accountName
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("Conta manual", ignoreCase = true) && !it.equals(bankName, ignoreCase = true) }
        val detail = account.maskedAccount?.trim()?.takeIf { it.isNotBlank() }
        return listOfNotNull(bankName, accountName, detail).joinToString(" • ")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo cartão") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Vincule o cartão a uma conta já cadastrada manualmente ou via Open Finance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box {
                    OutlinedButton(
                        onClick = { accountMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = accounts.isNotEmpty()
                    ) {
                        Icon(Icons.Default.AccountBalance, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            selectedAccount?.let(::accountLabel)
                                ?: if (accounts.isEmpty()) "Cadastre uma conta primeiro" else "Selecionar conta / banco",
                            modifier = Modifier.weight(1f)
                        )
                        if (accounts.isNotEmpty()) Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = accountMenu,
                        onDismissRequest = { accountMenu = false },
                        modifier = Modifier.heightIn(max = 360.dp)
                    ) {
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(accountLabel(account))
                                        Text(
                                            if (account.connectionStatus.equals("manual", true)) "Conta manual" else "Open Finance",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
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
                    OutlinedButton(onClick = { brandMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Bandeira: $brand")
                    }
                    DropdownMenu(expanded = brandMenu, onDismissRequest = { brandMenu = false }) {
                        brands.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { brand = option; brandMenu = false })
                        }
                    }
                }

                OutlinedTextField(
                    value = lastFour,
                    onValueChange = { lastFour = it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Últimos 4 dígitos") },
                    placeholder = { Text("4827") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Apelido (opcional)") },
                    placeholder = { Text("Cartão principal") },
                    singleLine = true
                )
                OutlinedTextField(creditLimitDigits, { creditLimitDigits = BrlMoney.digits(it) }, Modifier.fillMaxWidth(), label={Text("Limite do cartão (opcional)")}, placeholder={Text("R$ 0,00")}, singleLine=true, visualTransformation=BrlMoneyVisualTransformation, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(closingDayText, { closingDayText=it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label={Text("Dia fechamento")}, placeholder={Text("10")}, singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                    OutlinedTextField(dueDayText, { dueDayText=it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label={Text("Dia vencimento")}, placeholder={Text("17")}, singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val account = selectedAccount ?: return@Button
                    // O campo já existente bankName passa a representar a conta escolhida.
                    // O rótulo preserva a instituição e diferencia contas do mesmo banco.
                    onSave(accountLabel(account), brand, lastFour, nickname.trim().takeIf { it.isNotBlank() }, BrlMoney.toDouble(creditLimitDigits).takeIf { it > 0.0 }, closingDayText.toIntOrNull(), dueDayText.toIntOrNull())
                },
                enabled = selectedAccount != null && lastFour.length == 4 && (closingDayText.isBlank() || closingDayText.toIntOrNull()?.let { it in 1..31 } == true) && (dueDayText.isBlank() || dueDayText.toIntOrNull()?.let { it in 1..31 } == true)
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun EditAccountDialog(
    account: AccountEntity,
    onDismiss: () -> Unit,
    onSave: (String, String?, String?) -> Unit
) {
    val isManual = account.connectionStatus.equals("manual", true) || account.externalAccountId == null

    fun extractPart(prefix: String): String {
        return account.maskedAccount.orEmpty()
            .split("•")
            .map { it.trim() }
            .firstOrNull { it.startsWith(prefix, ignoreCase = true) }
            ?.substringAfter(prefix, "")
            ?.trim()
            .orEmpty()
    }

    if (isManual) {
        var name by remember(account.id) { mutableStateOf(account.institutionName) }
        var agency by remember(account.id) { mutableStateOf(extractPart("Ag.")) }
        var number by remember(account.id) { mutableStateOf(extractPart("Conta")) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Editar conta manual") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Altere os mesmos dados informados ao adicionar a conta.")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 160) name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nome da conta *") },
                        supportingText = { Text("Obrigatório") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = agency,
                        onValueChange = { if (it.length <= 30) agency = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Agência") },
                        supportingText = { Text("Opcional") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = number,
                        onValueChange = { if (it.length <= 50) number = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Número da conta") },
                        supportingText = { Text("Opcional") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val details = listOfNotNull(
                            agency.trim().takeIf { it.isNotBlank() }?.let { "Ag. $it" },
                            number.trim().takeIf { it.isNotBlank() }?.let { "Conta $it" }
                        ).joinToString(" • ").ifBlank { null }
                        onSave(name.trim(), "Conta manual", details)
                    }
                ) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
        )
        return
    }

    var institutionName by remember(account.id) { mutableStateOf(account.institutionName) }
    var accountName by remember(account.id) { mutableStateOf(account.accountName.orEmpty()) }
    var maskedAccount by remember(account.id) { mutableStateOf(account.maskedAccount.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar dados do banco") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(institutionName, { institutionName = it.take(120) }, Modifier.fillMaxWidth(), label={Text("Banco / instituição")}, singleLine=true)
                OutlinedTextField(accountName, { accountName = it.take(120) }, Modifier.fillMaxWidth(), label={Text("Nome da conta")}, singleLine=true)
                OutlinedTextField(maskedAccount, { maskedAccount = it.take(80) }, Modifier.fillMaxWidth(), label={Text("Final / número mascarado")}, singleLine=true)
                Text("A identificação técnica do Open Finance não será alterada.", style=MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick={ onSave(institutionName.trim(), accountName.trim().ifBlank{null}, maskedAccount.trim().ifBlank{null}) }, enabled=institutionName.isNotBlank()){Text("Salvar")} },
        dismissButton = { TextButton(onClick=onDismiss){Text("Cancelar")} }
    )
}

@Composable
private fun EditCreditCardDialog(
    card: CreditCardDto,
    accounts: List<AccountEntity>,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String?, Double?, Int?, Int?) -> Unit
) {
    fun accountLabel(account: AccountEntity): String {
        val bankName = account.institutionName.trim().ifBlank { "Conta" }
        val accountName = account.accountName?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("Conta manual", true) && !it.equals(bankName, true)
        }
        val detail = account.maskedAccount?.trim()?.takeIf { it.isNotBlank() }
        return listOfNotNull(bankName, accountName, detail).joinToString(" • ")
    }

    val initialAccount = remember(card.id, accounts) {
        accounts.firstOrNull { accountLabel(it) == card.bankName }
            ?: accounts.firstOrNull { card.bankName.startsWith(it.institutionName, ignoreCase = true) }
    }
    var selectedAccountId by remember(card.id) { mutableStateOf(initialAccount?.id) }
    var accountMenu by remember { mutableStateOf(false) }
    val brands = listOf("Mastercard", "Visa", "Elo", "American Express", "Hipercard", "Outro")
    var brand by remember(card.id) { mutableStateOf(card.brand) }
    var brandMenu by remember { mutableStateOf(false) }
    var lastFour by remember(card.id) { mutableStateOf(card.lastFour) }
    var nickname by remember(card.id) { mutableStateOf(card.nickname.orEmpty()) }
    var creditLimitDigits by remember(card.id) { mutableStateOf(BrlMoney.fromDouble(card.creditLimit)) }
    var closingDayText by remember(card.id) { mutableStateOf(card.closingDay?.toString().orEmpty()) }
    var dueDayText by remember(card.id) { mutableStateOf(card.dueDay?.toString().orEmpty()) }
    val selectedAccount = accounts.firstOrNull { it.id == selectedAccountId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar cartão") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedButton(onClick={accountMenu=true}, modifier=Modifier.fillMaxWidth(), enabled=accounts.isNotEmpty()) {
                        Icon(Icons.Default.AccountBalance, null); Spacer(Modifier.width(6.dp))
                        Text(selectedAccount?.let(::accountLabel) ?: card.bankName.ifBlank { "Selecionar conta / banco" }, Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded=accountMenu, onDismissRequest={accountMenu=false}, modifier=Modifier.heightIn(max=360.dp)) {
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text={ Column { Text(accountLabel(account)); Text(if(account.connectionStatus.equals("manual",true)) "Conta manual" else "Open Finance", style=MaterialTheme.typography.bodySmall) } },
                                onClick={selectedAccountId=account.id; accountMenu=false}
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick={brandMenu=true}, modifier=Modifier.fillMaxWidth()) {
                        Text("Bandeira: $brand", Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded=brandMenu, onDismissRequest={brandMenu=false}) {
                        brands.forEach { option -> DropdownMenuItem(text={Text(option)}, onClick={brand=option; brandMenu=false}) }
                    }
                }
                OutlinedTextField(lastFour, { lastFour=it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), label={Text("Últimos 4 dígitos")}, singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                OutlinedTextField(nickname, { nickname=it.take(80) }, Modifier.fillMaxWidth(), label={Text("Apelido (opcional)")}, singleLine=true)
                OutlinedTextField(creditLimitDigits, { creditLimitDigits=BrlMoney.digits(it) }, Modifier.fillMaxWidth(), label={Text("Limite do cartão (opcional)")}, singleLine=true, visualTransformation=BrlMoneyVisualTransformation, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(closingDayText, { closingDayText=it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label={Text("Dia fechamento")}, singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                    OutlinedTextField(dueDayText, { dueDayText=it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label={Text("Dia vencimento")}, singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                }
            }
        },
        confirmButton = {
            Button(
                onClick={ val account=selectedAccount ?: return@Button; onSave(accountLabel(account), brand, lastFour, nickname.trim().ifBlank{null}, BrlMoney.toDouble(creditLimitDigits).takeIf { it > 0.0 }, closingDayText.toIntOrNull(), dueDayText.toIntOrNull()) },
                enabled=selectedAccount != null && brand.isNotBlank() && lastFour.length==4 && (closingDayText.isBlank() || closingDayText.toIntOrNull()?.let { it in 1..31 } == true) && (dueDayText.isBlank() || dueDayText.toIntOrNull()?.let { it in 1..31 } == true)
            ){Text("Salvar")}
        },
        dismissButton={TextButton(onClick=onDismiss){Text("Cancelar")}}
    )
}

