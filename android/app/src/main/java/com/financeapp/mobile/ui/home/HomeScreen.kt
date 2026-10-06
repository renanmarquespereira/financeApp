package com.financeapp.mobile.ui.home

import android.app.Activity
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.financeapp.mobile.notifications.FinanceNotificationPreferences
import com.financeapp.mobile.notifications.FinanceNotificationSettings
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeapp.mobile.data.local.AccountEntity
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.local.WorkspaceEntity
import com.financeapp.mobile.data.remote.UserDataDeleteOptionsRequest
import com.financeapp.mobile.data.remote.UserDto
import com.financeapp.mobile.util.FinancialDataImporter
import com.financeapp.mobile.util.ImportedFinancialRow
import com.financeapp.mobile.data.remote.CategoryDto
import com.financeapp.mobile.data.remote.CreditCardDto
import com.financeapp.mobile.data.remote.BudgetDto
import com.financeapp.mobile.data.remote.GoalDto
import com.financeapp.mobile.data.remote.GoalContributionDto
import java.text.NumberFormat
import java.text.Normalizer
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import com.financeapp.mobile.util.formatCpfInput
import com.financeapp.mobile.util.isValidCpf
import com.financeapp.mobile.util.normalizeCpf
import com.financeapp.mobile.ui.legal.LegalPrivacyDialog
import kotlinx.coroutines.launch

private enum class HomeTab { DASHBOARD, TRANSACTIONS, FORECAST, ACCOUNTS, INVOICES }
internal enum class SourceFilter(val label: String) { ALL("Todas"), OPEN_FINANCE("Automáticas"), MANUAL("Manuais") }
internal enum class TypeFilter(val label: String) { ALL("Todos"), CREDIT("Entradas"), DEBIT("Saídas") }
internal enum class AmountFilter(val label: String) { ALL("Todos"), UP_TO_100("Até R$ 100"), FROM_100_TO_500("R$ 100–500"), FROM_500_TO_1000("R$ 500–1.000"), ABOVE_1000("Acima de R$ 1.000") }
internal enum class DateSortOrder { NEWEST_FIRST, OLDEST_FIRST }



internal enum class TransactionViewTab(
    val label: String
) {
    EXPENSE("Despesas"),
    CARD("Cartões")
}

internal data class TransactionFilters(
    val source: SourceFilter = SourceFilter.ALL,
    val type: TypeFilter = TypeFilter.ALL,
    val amount: AmountFilter = AmountFilter.ALL,
    val accountId: Int? = null,
    val categoryId: Int? = null,
    val cardId: Int? = null,
    val specificDate: LocalDate? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val showAllTransactions: Boolean = false,
)

private const val PAYABLE_PREFIX = "__PAYABLE_V1__|"

private data class PayableMeta(
    val reminderDays: Int,
    val amount: Double,
    val description: String
)

private fun encodePayableDescription(description: String, amount: Double, reminderDays: Int): String {
    val cents = kotlin.math.round(kotlin.math.abs(amount) * 100.0).toLong()
    val encoded = Base64.encodeToString(description.trim().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return "$PAYABLE_PREFIX${reminderDays.coerceIn(1, 30)}|$cents|$encoded"
}

private fun decodePayableDescription(value: String): PayableMeta? {
    if (!value.startsWith(PAYABLE_PREFIX)) return null
    val parts = value.removePrefix(PAYABLE_PREFIX).split('|', limit = 3)
    if (parts.size != 3) return null
    val reminder = parts[0].toIntOrNull() ?: return null
    val cents = parts[1].toLongOrNull() ?: return null
    val description = runCatching {
        String(Base64.decode(parts[2], Base64.NO_WRAP), Charsets.UTF_8)
    }.getOrNull()?.trim().orEmpty()
    if (description.isBlank()) return null
    return PayableMeta(reminder.coerceIn(1, 30), cents.toDouble() / 100.0, description)
}

private fun isPendingPayable(tx: TransactionEntity): Boolean = decodePayableDescription(tx.description) != null

private fun isCashFlowTransaction(tx: TransactionEntity): Boolean =
    !isPendingPayable(tx) && !isPlannedDebtTransaction(tx) && tx.source != "card_purchase" && !(tx.cardId != null && tx.source != "card_payment")


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    workspaceId: String,
    workspaceName: String,
    guest: Boolean = false,
    guestFrozen: Boolean = false,
    onGuestRegister: () -> Unit = {},
    onGuestLogin: () -> Unit = {},
    onCreateGuestAccount: (String) -> Unit = {},
    onCreateManualAccount: (String, String?, String?) -> Unit = { _, _, _ -> },
    onDeleteGuestAccount: (Int) -> Unit = {},
    onManageWorkspaces: () -> Unit,
    onUpdateProfilePhoto: (String?) -> Unit = {},
    onLoadPersonalProfile: suspend () -> UserDto,
    onUpdatePersonalProfile: suspend (String, String, String?, String?) -> UserDto,
    onUseGoogleProfilePhoto: () -> Unit = {},
    onThemeModeChange: (String) -> Unit = {},
    onShowOnboarding: () -> Unit = {},
    appLockEnabled: Boolean = false,
    onAppLockChange: (Boolean) -> Unit = {},
    state: HomeUiState,
    workspaces: List<WorkspaceEntity> = emptyList(),
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
    categories: List<CategoryDto>,
    cards: List<CreditCardDto>,
    budgets: List<BudgetDto>,
    goals: List<GoalDto>,
    goalContributions: List<GoalContributionDto>,
    onRefresh: () -> Unit,
    onConnectBank: () -> Unit,
    onDismissBankConnection: () -> Unit,
    onCompleteBankConnection: (String, String?) -> Unit,
    onDismissOpenFinanceProfile: () -> Unit,
    onSaveOpenFinanceProfile:
        (String, String) -> Unit,
    onSync: () -> Unit,
    onExportBackup: suspend () -> String,
    onRestoreBackup: suspend (String, String) -> Int,
    onCreateManual: (
        Int?,
        Int?,
        String,
        Double,
        String,
        String,
        Int?,
        Int,
        String?,
        (Boolean) -> Unit
    ) -> Unit,
    onCreateCard: (String, String, String, String?, Double?, Int?, Int?) -> Unit,
    onPayCardInvoice: (Int, Int, String, Double, String, (Boolean) -> Unit) -> Unit,
    onDeleteCard: (Int) -> Unit,
    onUpdateCardDetails:
        (Int, String, String, String, String?, Double?, Int?, Int?) -> Unit,
    onUpdateAccountDetails:
        (Int, String, String?, String?) -> Unit,
    onCreateCategory: (String, String?, (CategoryDto) -> Unit) -> Unit,
    onUpdateCategory: (Int, String, String?) -> Unit,
    onDeleteCategory: (Int) -> Unit,
    onSetBudget: (Int, Double) -> Unit,
    onDeleteBudget: (Int) -> Unit,
    onCreateGoal: (String, Double, Double, String?) -> Unit,
    onUpdateGoal: (Int, String, Double, Double, String?) -> Unit,
    onAddGoalContribution: (Int, Double) -> Unit,
    onDeleteGoalContribution: (Int) -> Unit,
    onDeleteGoal: (Int) -> Unit,
    onUpdateManualTransaction: (
        Int, Int?, Int?, String, Double, String, String
    ) -> Unit,
    onUpdateTransactionCategory: (Int, Int?) -> Unit,
    onListTransactionAttachments: suspend (Int) -> List<com.financeapp.mobile.data.remote.TransactionAttachmentDto>,
    onUploadTransactionAttachment: suspend (Int, String, String, ByteArray) -> com.financeapp.mobile.data.remote.TransactionAttachmentDto,
    onDownloadTransactionAttachment: suspend (Int, Int) -> ByteArray,
    onDeleteTransactionAttachment: suspend (Int, Int) -> Unit,
    onDeleteTransaction: (TransactionEntity) -> Unit,
    onDeleteTransactionsBulk:
        (List<TransactionEntity>) -> Unit,
    onUpdateTransactionsCategoryBulk:
        (List<TransactionEntity>, Int?) -> Unit,
    onUpdateTransactionsAccountBulk:
        (List<TransactionEntity>, Int?) -> Unit,
    onDisconnectAccount: (Int) -> Unit,
    onReconnectAccount: (Int) -> Unit,
    onRequestAccountDeleteCode: (Int, () -> Unit) -> Unit,
    onConfirmAccountDelete: (Int, String, () -> Unit) -> Unit,
    onRequestDeleteAllUserDataCode: (() -> Unit) -> Unit,
    onConfirmDeleteAllUserData: (String, UserDataDeleteOptionsRequest, () -> Unit) -> Unit,
    onLogout: () -> Unit,
    onLoadForecastState: suspend () -> Map<String, Any?> = { emptyMap() },
    onSaveForecastState: suspend (Map<String, Any?>) -> Map<String, Any?> = { emptyMap() },
    onClearMessage: () -> Unit
) {
    val appContext = LocalContext.current
    BackupAutoRunner(workspaceId = workspaceId, onExportBackup = onExportBackup)
    val backupScope = rememberCoroutineScope()
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    var pendingRestoreJson by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showLegalPrivacy by remember { mutableStateOf(false) }
    val diagnosticPrefs = remember(state.userEmail, workspaceName) {
        appContext.getSharedPreferences("financeapp_diagnostics_${state.userEmail ?: "local"}_${workspaceName}", Context.MODE_PRIVATE)
    }
    var lastIndependentBackupAt by remember { mutableStateOf(diagnosticPrefs.getLong("last_backup_at", 0L)) }
    val createBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && !backupBusy) backupScope.launch {
            backupBusy = true
            runCatching {
                val json = onExportBackup()
                appContext.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                    ?: error("Não foi possível abrir o arquivo de destino")
            }.onSuccess {
                lastIndependentBackupAt = System.currentTimeMillis()
                diagnosticPrefs.edit().putLong("last_backup_at", lastIndependentBackupAt).apply()
                backupMessage = "Backup criado com sucesso"
            }.onFailure {
                backupMessage = it.message ?: "Não foi possível criar o backup"
            }
            backupBusy = false
        }
    }
    val restoreBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !backupBusy) backupScope.launch {
            backupBusy = true
            runCatching {
                appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?.takeIf { it.isNotBlank() } ?: error("Arquivo de backup vazio")
            }.onSuccess { json ->
                pendingRestoreJson = json
            }.onFailure {
                backupMessage = it.message ?: "Não foi possível ler o backup"
            }
            backupBusy = false
        }
    }
    var pendingImportRows by remember { mutableStateOf<List<ImportedFinancialRow>>(emptyList()) }
    var pendingImportName by remember { mutableStateOf("") }
    var pendingImportMapping by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val importDataLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupScope.launch {
            runCatching {
                val name = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "dados.csv"
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Não foi possível abrir o arquivo")
                name to FinancialDataImporter.parse(name, bytes)
            }.onSuccess { (name, rows) ->
                pendingImportName = name
                pendingImportRows = rows
                pendingImportMapping = FinancialDataImporter.detectedMapping()
                if (rows.isEmpty()) backupMessage = "Nenhuma transação válida encontrada no arquivo."
            }.onFailure { backupMessage = it.message ?: "Não foi possível analisar o arquivo" }
        }
    }
    var showEditPersonal by remember { mutableStateOf(false) }
    var personalProfile by remember { mutableStateOf<UserDto?>(null) }
    var customization by remember(state.userEmail) { mutableStateOf(loadCustomization(appContext, state.userEmail ?: "local")) }
    LaunchedEffect(customization.themeMode) { onThemeModeChange(customization.themeMode) }
    var showAppCustomization by remember { mutableStateOf(false) }
    var showNotificationSettings by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = appContext.getSharedPreferences("financeapp_notifications", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("permission_prompted", false)) {
                prefs.edit().putBoolean("permission_prompted", true).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    var selectedTab by remember { mutableStateOf(HomeTab.DASHBOARD) }
    var voiceDraft by remember { mutableStateOf<VoiceTransactionDraft?>(null) }
    var voiceProcessing by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }

    val voiceTransactionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()

            if (spoken.isNullOrBlank()) {
                voiceError = "Não consegui entender o que foi falado. Tente novamente."
            } else {
                val localDraft = VoiceTransactionParser.parse(spoken, accounts, categories, cards)
                voiceProcessing = false
                if (localDraft.amount <= 0.0) {
                    voiceError = "Entendi a frase, mas não consegui identificar o valor. Fale, por exemplo: ‘Gastei na padaria dois reais e cinquenta e dois centavos’."
                } else {
                    voiceDraft = localDraft
                }
            }
        }
    }

    val startVoiceTransaction: () -> Unit = {
        voiceError = null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale o lançamento. Ex.: gastei na padaria R$ 2,52")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { voiceTransactionLauncher.launch(intent) }
            .onFailure { voiceError = "O reconhecimento de voz não está disponível neste aparelho." }
    }
    var categoryRuleCandidate by remember { mutableStateOf<Pair<TransactionEntity, Int>?>(null) }
    var categoryRulesVersion by remember { mutableIntStateOf(0) }
    val categoryRulesPrefs = remember(state.userEmail, workspaceName) {
        appContext.getSharedPreferences(
            "financeapp_category_rules_${state.userEmail ?: "local"}_${workspaceName}",
            Context.MODE_PRIVATE
        )
    }

    LaunchedEffect(transactions, categoryRulesVersion) {
        val rules = categoryRulesPrefs.all.mapNotNull { (key, value) ->
            (value as? Int)?.let { key to it }
        }.toMap()
        if (rules.isNotEmpty()) {
            val grouped = transactions
                .filter { it.categoryId == null }
                .mapNotNull { tx ->
                    rules[categoryRuleKey(tx.description)]?.let { categoryId -> tx to categoryId }
                }
                .groupBy({ it.second }, { it.first })
            grouped.forEach { (categoryId, matches) ->
                if (matches.isNotEmpty()) onUpdateTransactionsCategoryBulk(matches, categoryId)
            }
        }
    }
    var showManualDialog by remember { mutableStateOf(false) }
    var showTransactionTypeDialog by remember { mutableStateOf(false) }
    var showDebtCenter by remember { mutableStateOf(false) }
    var showNewDebtDirect by remember { mutableStateOf(false) }
    var showPayablesDialog by remember { mutableStateOf(false) }
    var showNewPayableDialog by remember { mutableStateOf(false) }
    var scannedExpenseDraft by remember { mutableStateOf<ExpenseCodeDraft?>(null) }
    var expenseScanError by remember { mutableStateOf<String?>(null) }
    var showScannedExpenseChoice by remember { mutableStateOf(false) }
    var manualTransactionInitialType by remember {
        mutableStateOf("debit")
    }
    var manualForceCardPurchase by remember {
        mutableStateOf(false)
    }
    var manualInitialCardId by remember {
        mutableStateOf<Int?>(null)
    }
    var showGlobalNewCard by remember {
        mutableStateOf(false)
    }
    var showSettings by remember { mutableStateOf(false) }
    var showBackupSyncDialog by remember { mutableStateOf(false) }
    var showAttentionCenter by remember { mutableStateOf(false) }
    var attentionOldestFirstRequest by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val launchExpenseCodeScanner = rememberExpenseCodeScanner(
        onResult = { draft ->
            scannedExpenseDraft = draft
            if (draft.dueDate != null) {
                showScannedExpenseChoice = true
            } else {
                manualTransactionInitialType = "debit"
                manualForceCardPurchase = false
                manualInitialCardId = null
                showManualDialog = true
            }
        },
        onError = { expenseScanError = it }
    )
    var pendingProfileBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showPhotoSource by remember { mutableStateOf(false) }
    var showRemoveProfilePhotoConfirm by remember { mutableStateOf(false) }
    val profilePhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: error("Não foi possível abrir a foto selecionada")
        }.onSuccess { pendingProfileBitmap = it }
    }
    val profileCameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) pendingProfileBitmap = bitmap
    }
    var showDeleteAllDataWarning by remember {
        mutableStateOf(false)
    }
    var showDeleteAllDataCode by remember { mutableStateOf(false) }
    var deleteDataOptions by remember { mutableStateOf(UserDataDeleteOptionsRequest()) }
    var filters by remember { mutableStateOf(TransactionFilters()) }
    var searchQuery by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    var pendingAccountDelete by remember { mutableStateOf<AccountEntity?>(null) }
    var accountDeleteCodeFor by remember {
        mutableStateOf<AccountEntity?>(null)
    }
    var attachmentTransaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedTransaction by remember {
        mutableStateOf<TransactionEntity?>(null)
    }
    var selectedGoalHistory by remember {
        mutableStateOf<GoalDto?>(null)
    }
    var goalHistoryAddValue by remember {
        mutableStateOf<GoalDto?>(null)
    }
    var pendingGoalContributionDelete by remember {
        mutableStateOf<GoalContributionDto?>(null)
    }
    var editingTransaction by remember {
        mutableStateOf<TransactionEntity?>(null)
    }
    var showPlanning by remember {
        mutableStateOf(false)
    }
    var planningBudgetCategoryId by remember { mutableStateOf<Int?>(null) }
    var directBudgetCategoryId by remember { mutableStateOf<Int?>(null) }
    var showMonthlySummary by remember {
        mutableStateOf(false)
    }

    state.message?.let {
        LaunchedEffect(it) {
            kotlinx.coroutines.delay(2500)
            onClearMessage()
        }
    }

    val isOnline =
        rememberIsDeviceOnline()

    val filteredTransactions = remember(transactions, filters) {
        transactions.filter { tx ->
            val txDate = parseTxDate(tx.date)
            val sourceOk = when (filters.source) {
                SourceFilter.ALL -> true
                SourceFilter.OPEN_FINANCE -> tx.source == "open_finance"
                SourceFilter.MANUAL -> tx.source == "manual"
            }
            val typeOk = when (filters.type) {
                TypeFilter.ALL -> true
                TypeFilter.CREDIT -> tx.amount >= 0
                TypeFilter.DEBIT -> tx.amount < 0
            }
            val amountAbs = kotlin.math.abs(tx.amount)
            val amountOk = when (filters.amount) {
                AmountFilter.ALL -> true
                AmountFilter.UP_TO_100 -> amountAbs <= 100.0
                AmountFilter.FROM_100_TO_500 -> amountAbs > 100.0 && amountAbs <= 500.0
                AmountFilter.FROM_500_TO_1000 -> amountAbs > 500.0 && amountAbs <= 1000.0
                AmountFilter.ABOVE_1000 -> amountAbs > 1000.0
            }
            val bankOk = filters.accountId == null || tx.accountId == filters.accountId
            val categoryOk =
                filters.categoryId == null || tx.categoryId == filters.categoryId

            val cardOk =
                filters.cardId == null || tx.cardId == filters.cardId
            val specificDateOk =
                filters.specificDate == null ||
                    (txDate != null && txDate == filters.specificDate)
            val startOk =
                filters.specificDate != null ||
                    filters.startDate == null ||
                    (txDate != null && !txDate.isBefore(filters.startDate))
            val endOk =
                filters.specificDate != null ||
                    filters.endDate == null ||
                    (txDate != null && !txDate.isAfter(filters.endDate))
            sourceOk && typeOk && amountOk && bankOk && categoryOk && cardOk && specificDateOk && startOk && endOk
        }
    }

    val attentionUncategorizedCount = remember(transactions) {
        transactions.count { it.categoryId == null && it.source != "card_payment" }
    }
    val attentionDuplicateCount = remember(transactions) {
        val candidates = transactions.filter { !isPendingPayable(it) && it.source != "card_purchase" && it.source != "card_payment" }
        val matched = mutableSetOf<Int>()
        candidates.forEachIndexed { index, first ->
            for (second in candidates.drop(index + 1)) {
                val differentOrigins = (first.source == "open_finance") != (second.source == "open_finance")
                if (!differentOrigins) continue
                if (kotlin.math.abs(first.amount - second.amount) > 0.009) continue
                if (first.accountId != null && second.accountId != null && first.accountId != second.accountId) continue
                val firstDate = parseTxDate(first.date) ?: continue
                val secondDate = parseTxDate(second.date) ?: continue
                if (kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(firstDate, secondDate)) > 2L) continue
                matched += first.id
                matched += second.id
            }
        }
        matched.size / 2
    }
    val attentionCardsMissingDue = remember(cards) { cards.count { it.active && it.dueDay == null } }
    val attentionUpcomingDue = remember(cards, transactions) {
        val today = LocalDate.now()
        var count = 0
        cards.filter { it.active && it.dueDay != null }.forEach { card ->
            fun closingFor(month: YearMonth): LocalDate {
                val day = (card.closingDay ?: 31).coerceIn(1, 31)
                return month.atDay(day.coerceAtMost(month.lengthOfMonth()))
            }
            fun invoiceEndFor(date: LocalDate): LocalDate {
                val close = closingFor(YearMonth.from(date))
                return if (!date.isAfter(close)) close else closingFor(YearMonth.from(date).plusMonths(1))
            }

            val grouped = transactions.asSequence()
                .filter { it.cardId == card.id && it.source != "card_payment" }
                .mapNotNull { tx ->
                    runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull()?.let { invoiceEndFor(it) to tx }
                }
                .groupBy({ it.first }, { it.second })

            grouped.forEach invoiceLoop@ { (end, rows) ->
                val total = (-rows.sumOf { it.amount }).coerceAtLeast(0.0)
                if (total <= 0.005) return@invoiceLoop

                val paid = transactions.asSequence()
                    .filter {
                        it.cardId == card.id &&
                            it.source == "card_payment" &&
                            it.purchaseDate?.take(10) == end.toString()
                    }
                    .sumOf { kotlin.math.abs(it.amount) }
                if (total - paid <= 0.005) return@invoiceLoop

                var dueMonth = YearMonth.from(end)
                val dueDay = card.dueDay ?: return@invoiceLoop
                if (dueDay <= end.dayOfMonth) dueMonth = dueMonth.plusMonths(1)
                val due = dueMonth.atDay(dueDay.coerceAtMost(dueMonth.lengthOfMonth()))
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, due).toInt()
                if (days in 0..7) count++
            }
        }
        count
    }
    val attentionTotal = attentionUncategorizedCount + attentionDuplicateCount +
        attentionCardsMissingDue + attentionUpcomingDue

    Scaffold(
        topBar = {
            if (selectedTab == HomeTab.DASHBOARD) {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            val firstName = state.userEmail
                                ?.substringBefore("@")
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                            Text(
                                if (firstName != null) "Olá, $firstName 👋" else "Olá 👋",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            Text(
                                "Aqui está o seu resumo financeiro",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.72f)
                            )
                        }
                    },
                    actions = {
                        if (!guest) {
                            BadgedBox(
                                badge = {
                                    if (attentionTotal > 0) {
                                        Badge(containerColor = Color(0xFFFF4D67)) {
                                            Text(if (attentionTotal > 99) "99+" else attentionTotal.toString())
                                        }
                                    }
                                }
                            ) {
                                IconButton(onClick = { showAttentionCenter = true }) {
                                    Icon(
                                        Icons.Default.Notifications,
                                        contentDescription = "Central de pendências",
                                        tint = Color.White
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = Color.White.copy(alpha = 0.14f),
                            modifier = Modifier.padding(end = 10.dp)
                        ) {
                            IconButton(onClick = { showSettings = true }) {
                                val photoBitmap = remember(state.profilePhoto) {
                                    state.profilePhoto?.substringAfter("base64,", "")?.takeIf { it.isNotBlank() }?.let { raw ->
                                        runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull()?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                                    }
                                }
                                if (photoBitmap != null) {
                                    Image(
                                        photoBitmap.asImageBitmap(),
                                        "Perfil",
                                        Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(Icons.Default.Person, contentDescription = "Perfil", tint = Color.White)
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF071B33),
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Surface(
                                onClick = { if (guest) showSettings = true else onManageWorkspaces() },
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Icon(
                                    Icons.Default.AccountBalanceWallet,
                                    contentDescription = "Trocar workspace",
                                    modifier = Modifier.padding(9.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                Text("FinanceApp", fontWeight=FontWeight.Bold, style=MaterialTheme.typography.titleLarge, maxLines=1)
                                Text(
                                    workspaceName.ifBlank { "Workspace" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    actions = {
                        if (guest) Text("Só neste aparelho", style=MaterialTheme.typography.labelSmall)
                        else {
                            BadgedBox(
                                badge = { if (attentionTotal > 0) Badge { Text(if (attentionTotal > 99) "99+" else attentionTotal.toString()) } }
                            ) {
                                IconButton(onClick = { showAttentionCenter = true }) {
                                    Icon(Icons.Default.Notifications, contentDescription = "Central de pendências")
                                }
                            }
                        }

                        Surface(shape=androidx.compose.foundation.shape.CircleShape, color=MaterialTheme.colorScheme.primaryContainer, modifier=Modifier.padding(end=8.dp)) {
                            IconButton(onClick={showSettings=true}) {
                                val photoBitmap = remember(state.profilePhoto) {
                                    state.profilePhoto?.substringAfter("base64,", "")?.takeIf { it.isNotBlank() }?.let { raw ->
                                        runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull()?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                                    }
                                }
                                if (photoBitmap != null) Image(photoBitmap.asImageBitmap(), "Perfil", Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape), contentScale=ContentScale.Crop)
                                else Icon(Icons.Default.Person, contentDescription="Perfil", tint=MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                // Mantém o tamanho normal quando há espaço e só reduz em telas realmente estreitas.
                val navLabelSize = when {
                    maxWidth >= 400.dp -> 11.sp
                    maxWidth >= 350.dp -> 10.sp
                    maxWidth >= 320.dp -> 9.sp
                    else -> 8.sp
                }
                val navLineHeight = when {
                    maxWidth >= 400.dp -> 12.sp
                    maxWidth >= 350.dp -> 11.sp
                    maxWidth >= 320.dp -> 10.sp
                    else -> 9.sp
                }
                val navIconSize = when {
                    maxWidth >= 380.dp -> 24.dp
                    maxWidth >= 330.dp -> 23.dp
                    else -> 21.dp
                }

                NavigationBar(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF0D1B2E) else Color(0xFFFFFFFF),
                    tonalElevation = 0.dp
                ) {
                    customization.navOrder.filter { it == "TRANSACTIONS" || it !in customization.hiddenNav }.forEach { id ->
                        val tab = runCatching { HomeTab.valueOf(id) }.getOrNull() ?: return@forEach
                        val label = when(tab){ HomeTab.DASHBOARD->"Início"; HomeTab.TRANSACTIONS->"Transações"; HomeTab.FORECAST->"Previsão"; HomeTab.ACCOUNTS->"Bancos"; HomeTab.INVOICES->"Faturas" }
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF4F46E5),
                                selectedTextColor = Color(0xFF4F46E5),
                                indicatorColor = Color(0xFFEDE9FE),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                            ),
                            icon = {
                                Icon(
                                    when(tab){HomeTab.DASHBOARD->Icons.Default.Home;HomeTab.TRANSACTIONS->Icons.Default.ReceiptLong;HomeTab.FORECAST->Icons.Default.ShowChart;HomeTab.ACCOUNTS->Icons.Default.AccountBalance;HomeTab.INVOICES->Icons.Default.CreditCard},
                                    null,
                                    modifier = Modifier.size(navIconSize)
                                )
                            },
                            label = {
                                Text(
                                    text = label,
                                    fontWeight = if (selectedTab == tab) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = navLabelSize,
                                    lineHeight = navLineHeight,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                HomeTab.DASHBOARD -> DashboardScreen(
                    workspaceName = workspaceName,
                    onManageWorkspaces = onManageWorkspaces,
                    accounts = accounts,
                    cards = cards.filter { it.active },
                    transactions = transactions,
                    categories = categories,
                    budgets = budgets,
                    goals = goals,
                    goalContributions =
                        goalContributions,
                    onOpenGoalHistory = {
                        selectedGoalHistory = it
                    },
                    pendingSyncCount =
                        state.pendingSyncCount,
                    offlineSyncing =
                        state.offlineSyncing,
                    onAddTransaction = { showTransactionTypeDialog = true },
                    onOpenPayables = { showPayablesDialog = true },
                    onVoiceTransaction = startVoiceTransaction,
                    onOpenTransactions = {
                        selectedTab = HomeTab.TRANSACTIONS
                    },
                    onOpenPlanning = { categoryId ->
                        if (categoryId == null) {
                            planningBudgetCategoryId = null
                            showPlanning = true
                        } else {
                            // Dashboard budget cards open only the budget editor.
                            // Do not put the full Planning dialog behind it.
                            directBudgetCategoryId = categoryId
                        }
                    },
                    onOpenInvoices = { selectedTab = HomeTab.INVOICES },
                    onOpenDebts = { showDebtCenter = true },
                    dashboardCustomization = customization,
                    onDashboardCustomizationChange = { updated ->
                        customization = updated
                        saveCustomization(appContext, state.userEmail ?: "local", updated)
                    },
                    onOpenSummary = {
                        showMonthlySummary = true
                    }
                )
                HomeTab.TRANSACTIONS -> MonthlyTransactionsScreen(
                    accounts = accounts,
                    cards = cards.filter { it.active },
                    categories = categories,
                    budgets = budgets,
                    pendingSyncCount =
                        state.pendingSyncCount,
                    offlineSyncing =
                        state.offlineSyncing,
                    transactions = filteredTransactions,
                    allTransactions = transactions,
                    filters = filters,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    highlightedTransactionId =
                        state.highlightedTransactionId,
                    oldestFirstRequest = attentionOldestFirstRequest,
                    onFiltersChange = { filters = it },
                    onOpenPlanning = { showPlanning = true },
                    onOpenMonthlySummary = {
                        showMonthlySummary = true
                    },
                    onAddManualTransaction = { initialType ->
                        manualTransactionInitialType = initialType
                        manualForceCardPurchase = false
                        manualInitialCardId = null
                        showManualDialog = true
                    },
                    onAddCardPurchase = {
                            selectedCardId ->
                        manualTransactionInitialType = "debit"
                        manualForceCardPurchase = true
                        manualInitialCardId = selectedCardId
                        showManualDialog = true
                    },
                    onRequestAddCard = {
                        showGlobalNewCard = true
                    },
                    onOpenDetails = {
                        if (
                            isPlannedDebtTransaction(it) ||
                            it.description.startsWith("Pagamento de dívida •", ignoreCase = true)
                        ) {
                            showDebtCenter = true
                        } else {
                            selectedTransaction = it
                        }
                    },
                    onDelete = {
                        pendingDelete = it
                    },
                    onDeleteBulk =
                        onDeleteTransactionsBulk,
                    onUpdateCategoryBulk =
                        onUpdateTransactionsCategoryBulk,
                    onUpdateAccountBulk =
                        onUpdateTransactionsAccountBulk,
                    debtCreditorFor = { tx ->
                        debtCreditorForTransaction(
                            appContext,
                            state.userEmail ?: "local",
                            workspaceId,
                            tx
                        )
                    }
                )
                HomeTab.FORECAST -> FinancialForecastScreen(
                    accounts = accounts,
                    transactions = transactions.filterNot(::isPendingPayable),
                    cards = cards,
                    userKey = state.userEmail ?: "local",
                    workspaceName = workspaceName,
                    workspaceId = workspaceId,
                    syncRevision = state.lastSyncAt,
                    onLoadForecastState = onLoadForecastState,
                    onSaveForecastState = onSaveForecastState,
                    onTransformSimulation = { description, amount, isIncome, firstDate, installments, recurring ->
                        val count = if (recurring) 12 else 1
                        repeat(count) { index ->
                            onCreateManual(
                                null, null, description, amount,
                                if (isIncome) "credit" else "debit",
                                firstDate.plusMonths(index.toLong()).toString(),
                                null, if (!isIncome && !recurring) installments else 1, null
                            ) { }
                        }
                    },
                    onRecordDebtPayment = { debtName, amount, date, accountId ->
                        onCreateManual(accountId, null, "Pagamento de dívida • $debtName", -kotlin.math.abs(amount), "debit", date.toString(), null, 1, null) { }
                    }
                )
                HomeTab.INVOICES -> InvoiceCenterScreen(accounts, cards, transactions, onPayCardInvoice)
                HomeTab.ACCOUNTS -> AccountsList(
                    accounts = accounts,
                    transactions = transactions,
                    categories = categories,
                    cards = cards.filter { it.active },
                    onCreateCard = onCreateCard,
                    onPayCardInvoice = onPayCardInvoice,
                    onDeleteCard = onDeleteCard,
                    onUpdateCardDetails =
                        onUpdateCardDetails,
                    onUpdateAccountDetails =
                        onUpdateAccountDetails,
                    guest = guest,
                    onCreateManualAccount = onCreateManualAccount,
                    onConnectBank = { if (guest) showSettings=true else onConnectBank() },
                    onDisconnect = {
                        if (guest) showSettings=true else onDisconnectAccount(it.id)
                    },
                    onReconnect = {
                        if (guest) showSettings=true else onReconnectAccount(it.id)
                    },
                    onDelete = {
                        pendingAccountDelete = it
                    },
                    onOpenInvoices = { selectedTab = HomeTab.INVOICES }
                )
            }

            if (
                state.loading &&
                accounts.isEmpty() &&
                transactions.isEmpty()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(
                            Alignment.TopCenter
                        )
                ) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth()
                    )
                    Text(
                        "Restaurando seus dados…",
                        modifier =
                            Modifier.padding(12.dp),
                        style =
                            MaterialTheme.typography
                                .bodySmall
                    )
                }
            }

            state.error?.let {
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { Text(it) }
            }
            state.message?.let {
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { Text(it) }
            }
        }
    }

    if (state.needsOpenFinanceProfile) {
        OpenFinanceProfileDialog(
            initialName =
                state.openFinanceProfileName,
            loading = state.loading,
            error = state.error,
            onDismiss =
                onDismissOpenFinanceProfile,
            onSave =
                onSaveOpenFinanceProfile
        )
    }

    state.openFinanceWidgetUrl?.let {
            widgetUrl ->
        OpenFinanceConnectDialog(
            url = widgetUrl,
            onDismiss =
                onDismissBankConnection,
            onRetry = {
                onDismissBankConnection()
                onConnectBank()
            },
            onSuccess = {
                    linkId,
                    institution ->
                onCompleteBankConnection(
                    linkId,
                    institution
                )
            }
        )
    }

    selectedGoalHistory?.let {
            selectedGoal ->
        val currentGoal =
            goals.firstOrNull {
                it.id == selectedGoal.id
            } ?: selectedGoal

        val currentContributions =
            goalContributions.filter {
                it.goalId == currentGoal.id
            }

        GoalHistoryDialog(
            goal = currentGoal,
            contributions =
                currentContributions,
            onAddValue = {
                goalHistoryAddValue =
                    currentGoal
            },
            onDeleteContribution = {
                    contribution ->
                pendingGoalContributionDelete =
                    contribution
            },
            onDismiss = {
                selectedGoalHistory = null
            }
        )
    }

    goalHistoryAddValue?.let {
            selectedGoal ->
        val currentGoal =
            goals.firstOrNull {
                it.id == selectedGoal.id
            } ?: selectedGoal

        AddGoalValueDialog(
            goal = currentGoal,
            onDismiss = {
                goalHistoryAddValue = null
            },
            onAdd = { amount ->
                onAddGoalContribution(
                    currentGoal.id,
                    amount
                )
                goalHistoryAddValue = null
            }
        )
    }

    pendingGoalContributionDelete?.let {
            contribution ->
        val currency =
            remember {
                NumberFormat.getCurrencyInstance(
                    Locale("pt", "BR")
                )
            }

        AlertDialog(
            onDismissRequest = {
                pendingGoalContributionDelete =
                    null
            },
            title = {
                Text("Excluir aporte?")
            },
            text = {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Deseja excluir este valor da meta?"
                    )
                    Text(
                        currency.format(
                            contribution.amount
                        ),
                        style =
                            MaterialTheme
                                .typography
                                .headlineSmall,
                        fontWeight =
                            FontWeight.Bold
                    )
                    Text(
                        "O valor será descontado do total acumulado, " +
                            "mas a meta continuará existindo.",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteGoalContribution(
                            contribution.id
                        )
                        pendingGoalContributionDelete =
                            null
                    },
                    colors =
                        ButtonDefaults.buttonColors(
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
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null
                    )
                    Spacer(
                        Modifier.width(6.dp)
                    )
                    Text("Excluir aporte")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingGoalContributionDelete =
                            null
                    }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showAttentionCenter && !guest) {
        AttentionCenterDialog(
            uncategorizedCount = attentionUncategorizedCount,
            duplicateCount = attentionDuplicateCount,
            cardsMissingDue = attentionCardsMissingDue,
            upcomingDueCount = attentionUpcomingDue,
            onOpenUncategorized = {
                showAttentionCenter = false
                filters = TransactionFilters(showAllTransactions = true)
                searchQuery = "sem categoria"
                attentionOldestFirstRequest++
                selectedTab = HomeTab.TRANSACTIONS
            },
            onOpenDuplicates = {
                showAttentionCenter = false
                filters = TransactionFilters(showAllTransactions = true)
                searchQuery = ""
                selectedTab = HomeTab.TRANSACTIONS
            },
            onOpenCards = { showAttentionCenter = false; selectedTab = HomeTab.ACCOUNTS },
            onOpenInvoices = { showAttentionCenter = false; selectedTab = HomeTab.INVOICES },
            onDismiss = { showAttentionCenter = false }
        )
    }

    pendingRestoreJson?.let { json ->
        AlertDialog(
            onDismissRequest = { if (!backupBusy) pendingRestoreJson = null },
            title = { Text("Restaurar este backup?") },
            text = {
                Text("Os dados locais do Workspace atual serão substituídos pelos dados deste arquivo. Esta ação não pode ser desfeita automaticamente.")
            },
            confirmButton = {
                Button(
                    enabled = !backupBusy,
                    onClick = {
                        backupScope.launch {
                            backupBusy = true
                            runCatching { onRestoreBackup(json, "replace") }
                                .onSuccess { backupMessage = "Backup restaurado: $it registros" }
                                .onFailure { backupMessage = it.message ?: "Não foi possível restaurar o backup" }
                            pendingRestoreJson = null
                            backupBusy = false
                        }
                    }
                ) {
                    if (backupBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (backupBusy) "Restaurando..." else "Restaurar")
                }
            },
            dismissButton = {
                TextButton(enabled = !backupBusy, onClick = { pendingRestoreJson = null }) { Text("Cancelar") }
            }
        )
    }
    backupMessage?.let { msg -> AlertDialog(onDismissRequest={backupMessage=null}, title={Text("Backup")}, text={Text(msg)}, confirmButton={TextButton(onClick={backupMessage=null}){Text("OK")}}) }

    if (showEditPersonal && personalProfile != null) {
        PersonalDataDialog(
            user = personalProfile!!,
            onDismiss = { showEditPersonal = false },
            onSave = { name, cpf, birth, sex ->
                backupScope.launch {
                    runCatching { onUpdatePersonalProfile(name, cpf, birth, sex) }
                        .onSuccess { personalProfile = it; showEditPersonal = false; backupMessage = "Dados pessoais atualizados." }
                        .onFailure { backupMessage = it.message ?: "Não foi possível atualizar seus dados" }
                }
            },
            onChoosePhoto = { showPhotoSource = true },
            onRemovePhoto = { showRemoveProfilePhotoConfirm = true },
            hasProfilePhoto = !state.profilePhoto.isNullOrBlank()
        )
    }
    if (pendingImportRows.isNotEmpty()) {
        val duplicates = pendingImportRows.count { row -> transactions.any { tx -> tx.description.equals(row.description, true) && kotlin.math.abs(tx.amount - row.amount) < 0.005 && tx.date.take(10) == row.date } }
        AlertDialog(
            onDismissRequest = { pendingImportRows = emptyList() },
            title = { Text("Importar dados financeiros") },
            text = {
                val mappingText = if (pendingImportMapping.isEmpty())
                    "Nenhum cabeçalho conhecido foi identificado automaticamente."
                else pendingImportMapping.entries.joinToString("\n") { (target, source) -> "$source  →  $target" }
                Text("Arquivo: $pendingImportName\nEncontrados: ${pendingImportRows.size} registros\nPossíveis duplicados: $duplicates\n\nMapeamento identificado:\n$mappingText\n\nOs duplicados serão ignorados. Contas, categorias e cartões serão associados pelo nome quando encontrados.")
            },
            confirmButton = {
                Button(onClick = {
                    val rows = pendingImportRows.filterNot { row -> transactions.any { tx -> tx.description.equals(row.description, true) && kotlin.math.abs(tx.amount - row.amount) < 0.005 && tx.date.take(10) == row.date } }
                    if (rows.isEmpty()) {
                        pendingImportRows = emptyList()
                        backupMessage = "Nenhum registro novo para importar."
                    } else {
                        var completed = 0
                        var failed = 0
                        val importedCategories = mutableMapOf<String, Int>()
                        categories.forEach { category ->
                            importedCategories[categoryRuleKey(category.name)] = category.id
                        }

                        fun finishOne(ok: Boolean) {
                            if (ok) completed++ else failed++
                            if (completed + failed == rows.size) {
                                if (failed == 0) {
                                    onSync()
                                    selectedTab = HomeTab.DASHBOARD
                                    backupMessage = "${rows.size} registro(s) importados. Categorias conferidas no workspace atual e Dashboard atualizado."
                                } else {
                                    backupMessage = "Importação parcial: $completed concluído(s), $failed com erro."
                                }
                            }
                        }

                        rows.forEach { row ->
                            val accountId = row.account?.let { n -> accounts.firstOrNull { it.institutionName.equals(n,true) || it.accountName.equals(n,true) }?.id }
                            val cardId = row.card?.let { n -> cards.firstOrNull { (it.nickname ?: it.bankName).equals(n,true) || it.lastFour == n.takeLast(4) }?.id }

                            fun createTransaction(categoryId: Int?) {
                                onCreateManual(accountId, categoryId, row.description, row.amount, if(cardId != null) "debit" else row.type, row.date, cardId, 1, null) { ok ->
                                    finishOne(ok)
                                }
                            }

                            val categoryName = row.category?.trim().orEmpty()
                            if (categoryName.isBlank()) {
                                createTransaction(null)
                            } else {
                                val key = categoryRuleKey(categoryName)
                                val existingId = importedCategories[key]
                                if (existingId != null) {
                                    createTransaction(existingId)
                                } else {
                                    // onCreateCategory usa o repository do workspace atual.
                                    // Categoria homonima em outro workspace nao interfere aqui.
                                    onCreateCategory(categoryName, null) { created ->
                                        importedCategories[key] = created.id
                                        createTransaction(created.id)
                                    }
                                }
                            }
                        }
                        pendingImportRows = emptyList()
                    }
                }) { Text("Importar") }
            },
            dismissButton = { TextButton(onClick = { pendingImportRows = emptyList() }) { Text("Cancelar") } }
        )
    }

    if (showSettings && guest) {
        GuestOptionsDialog(guestFrozen,onDismiss={showSettings=false},onRegister=onGuestRegister,onLogin=onGuestLogin,
            onCreateAccount=onCreateGuestAccount,onLogout=onLogout)
    }
    if (showSettings && !guest) {
        AccountSettingsDialog(
            email = state.userEmail,
            lastSyncAt = state.lastSyncAt,
            onSyncNow = onRefresh,
            onBackupSync = { showSettings = false; showBackupSyncDialog = true },
            onEditPersonal = { showSettings = false; backupScope.launch { runCatching { onLoadPersonalProfile() }.onSuccess { personalProfile = it; showEditPersonal = true }.onFailure { backupMessage = it.message ?: "Não foi possível carregar seus dados" } } },
            onAppSettings = { showSettings = false; showAppCustomization = true },
            onDiagnostics = { showSettings = false; showDiagnostics = true },
            onLegalPrivacy = { showSettings = false; showLegalPrivacy = true },
            onShowOnboarding = { showSettings = false; onShowOnboarding() },
            onNotifications = { showSettings = false; showNotificationSettings = true },
            onCreateBackup = { createBackupLauncher.launch("FinanceApp_backup_${java.time.LocalDate.now()}.json") },
            onRestoreBackup = { restoreBackupLauncher.launch(arrayOf("application/json","text/plain")) },
            onImportData = { importDataLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/csv","application/json","text/plain","application/xml","text/xml")) },
            onChoosePhoto = { showPhotoSource = true },
            onRemovePhoto = { showRemoveProfilePhotoConfirm = true },
            onUseGooglePhoto = { onUseGoogleProfilePhoto() },
            hasGoogleProfilePhoto = state.hasGoogleProfilePhoto,
            hasProfilePhoto = !state.profilePhoto.isNullOrBlank(),
            appLockEnabled = appLockEnabled,
            onAppLockChange = onAppLockChange,
            onDeleteAllData = {
                showSettings = false
                showDeleteAllDataWarning = true
            },
            onDismiss = { showSettings = false },
            onLogout = { showSettings = false; onLogout() }
        )
    }

    if (showBackupSyncDialog) {
        BackupSyncDialog(
            onDismiss = { showBackupSyncDialog = false },
            onExportBackup = onExportBackup,
            onRestoreBackup = onRestoreBackup
        )
    }

    if (showLegalPrivacy) {
        LegalPrivacyDialog(onDismiss = { showLegalPrivacy = false })
    }

    if (showDiagnostics) {
        DiagnosticsDialog(
            workspaceName = workspaceName,
            serverAvailable = state.serverAvailable,
            backupStatus = readFinanceBackupStatus(appContext),
            onOpenBackup = {
                showDiagnostics = false
                showBackupSyncDialog = true
            },
            onDismiss = { showDiagnostics = false }
        )
    }

    if (showNotificationSettings) {
        NotificationSettingsDialog(onDismiss = { showNotificationSettings = false })
    }

    if (showAppCustomization) {
        AppCustomizationDialog(customization, onThemeChange = { mode ->
            customization = customization.copy(themeMode = mode)
            saveCustomization(appContext, state.userEmail ?: "local", customization)
            onThemeModeChange(mode)
        }, onSave = {
            customization = it
            onThemeModeChange(it.themeMode)
            saveCustomization(appContext, state.userEmail ?: "local", it)
            if (selectedTab.name in it.hiddenNav && selectedTab !in setOf(HomeTab.TRANSACTIONS, HomeTab.ACCOUNTS)) selectedTab = HomeTab.TRANSACTIONS
            showAppCustomization = false
        }, onDismiss = { showAppCustomization = false })
    }

    if (showRemoveProfilePhotoConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveProfilePhotoConfirm = false },
            title = { Text("Excluir foto do perfil?") },
            text = { Text("A foto será removida do FinanceApp. Ela não voltará automaticamente no próximo login com Google.") },
            confirmButton = {
                TextButton(onClick = { showRemoveProfilePhotoConfirm = false; onUpdateProfilePhoto(null) }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { showRemoveProfilePhotoConfirm = false }) { Text("Cancelar") } }
        )
    }

    if (showPhotoSource) {
        AlertDialog(
            onDismissRequest = { showPhotoSource = false },
            title = { Text("Foto do perfil") },
            text = { Text("Escolha como deseja adicionar sua foto.") },
            confirmButton = {
                TextButton(onClick = { showPhotoSource = false; profileCameraLauncher.launch(null) }) {
                    Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Câmera")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPhotoSource = false; profilePhotoLauncher.launch("image/*") }) {
                    Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("Galeria")
                }
            }
        )
    }
    pendingProfileBitmap?.let { bitmap ->
        ProfilePhotoCropDialog(
            bitmap = bitmap,
            onDismiss = { pendingProfileBitmap = null },
            onConfirm = { scale, offset ->
                runCatching { profileBitmapToDataUrl(bitmap, scale, offset) }
                    .onSuccess { onUpdateProfilePhoto(it); pendingProfileBitmap = null }
            }
        )
    }

    if (showDeleteAllDataWarning) {
        val o = deleteDataOptions
        val removableWorkspaceIds = workspaces.filter { !it.isDefault }.map { it.id }
        val allAccountIds = accounts.map { it.id }
        val allFinancialSelected =
            o.transactions && o.categories && o.cards && o.budgets && o.goals && o.openFinance &&
                (accounts.isEmpty() || allAccountIds.all { it in o.accountIds }) &&
                removableWorkspaceIds.all { it in o.workspaceIds }
        val selectedCount = listOf(o.transactions, o.categories, o.accounts, o.cards,
            o.budgets, o.goals, o.openFinance, o.deleteAccount).count { it } +
            o.workspaceIds.size + o.accountIds.size
        AlertDialog(
            onDismissRequest = { showDeleteAllDataWarning = false },
            title = { Text("O que você deseja apagar?") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("Escolha exatamente os dados que serão removidos do workspace atual.", style = MaterialTheme.typography.bodySmall)
                    @Composable fun option(label: String, checked: Boolean, change: (Boolean) -> Unit) {
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(onClick = { change(!checked) }, onLongClick = {}),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = change)
                            Text(label)
                        }
                    }
                    option("Transações", o.transactions) { deleteDataOptions = o.copy(transactions = it) }
                    option("Categorias personalizadas", o.categories) { deleteDataOptions = o.copy(categories = it) }
                    Text("Contas / Bancos", fontWeight = FontWeight.SemiBold)
                    if (accounts.isEmpty()) {
                        Text("Nenhuma conta cadastrada.", style = MaterialTheme.typography.bodySmall)
                    } else accounts.forEach { account ->
                        val detail = account.maskedAccount?.takeIf { it.isNotBlank() }
                        val label = if (detail == null) account.institutionName else "${account.institutionName} • $detail"
                        option(label, account.id in o.accountIds) { checked ->
                            deleteDataOptions = o.copy(
                                accounts = false,
                                accountIds = if (checked) (o.accountIds + account.id).distinct() else o.accountIds - account.id
                            )
                        }
                    }
                    if (o.accountIds.isNotEmpty()) {
                        Text("As transações desses bancos serão preservadas; apenas o vínculo com a conta será removido.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    option("Cartões", o.cards) { deleteDataOptions = o.copy(cards = it) }
                    if (o.cards) {
                        Text("As transações dos cartões serão preservadas; apenas o vínculo com o cartão será removido.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    option("Limites / Orçamentos", o.budgets) { deleteDataOptions = o.copy(budgets = it) }
                    option("Metas financeiras", o.goals) { deleteDataOptions = o.copy(goals = it) }
                    option("Conexões Open Finance", o.openFinance) { deleteDataOptions = o.copy(openFinance = it) }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Workspaces adicionais", fontWeight = FontWeight.SemiBold)
                    val removable = workspaces.filter { !it.isDefault }
                    if (removable.isEmpty()) {
                        Text("Nenhum workspace adicional para excluir.", style = MaterialTheme.typography.bodySmall)
                    } else removable.forEach { ws ->
                        option(ws.name, ws.id in o.workspaceIds) { checked ->
                            deleteDataOptions = o.copy(workspaceIds = if (checked) (o.workspaceIds + ws.id).distinct() else o.workspaceIds - ws.id)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    option("Apagar todos os dados financeiros", allFinancialSelected) { checked ->
                        deleteDataOptions = if (checked) {
                            o.copy(
                                transactions = true,
                                categories = true,
                                accounts = true,
                                accountIds = allAccountIds,
                                cards = true,
                                budgets = true,
                                goals = true,
                                openFinance = true,
                                workspaceIds = removableWorkspaceIds
                            )
                        } else {
                            o.copy(
                                transactions = false,
                                categories = false,
                                accounts = false,
                                accountIds = emptyList(),
                                cards = false,
                                budgets = false,
                                goals = false,
                                openFinance = false,
                                workspaceIds = emptyList()
                            )
                        }
                    }
                    option("Excluir minha conta e login", o.deleteAccount) {
                        deleteDataOptions = o.copy(deleteAccount = it)
                    }
                    if (o.deleteAccount) {
                        Text("Isso encerra sua conta, remove o login e todos os dados de todos os workspaces. Não pode ser desfeito.",
                            color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Após continuar, enviaremos um código de 4 dígitos para ${state.userEmail ?: "seu e-mail"}.",
                        style = MaterialTheme.typography.bodySmall)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearMessage()
                        onRequestDeleteAllUserDataCode {
                            showDeleteAllDataWarning = false
                            showDeleteAllDataCode = true
                        }
                    },
                    enabled = selectedCount > 0 && !state.loading,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                        Spacer(Modifier.width(8.dp)); Text("Enviando...")
                    } else Text("Continuar")
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteAllDataWarning = false }) { Text("Cancelar") } }
        )
    }

    if (showDeleteAllDataCode) {
        DeleteAllUserDataCodeDialog(
            email = state.userEmail,
            loading = state.loading,
            error = state.error,
            onClearError = onClearMessage,
            onDismiss = {
                showDeleteAllDataCode = false
            },
            onResend = {
                onRequestDeleteAllUserDataCode {}
            },
            onConfirm = { code ->
                onConfirmDeleteAllUserData(
                    code,
                    deleteDataOptions
                ) {
                    showDeleteAllDataCode = false
                    if (deleteDataOptions.deleteAccount) onLogout()
                    deleteDataOptions = UserDataDeleteOptionsRequest()
                }
            }
        )
    }

    pendingDelete?.let { tx ->
        val isInstallmentPurchase =
            tx.installmentGroup != null &&
                (tx.installmentTotal ?: 1) > 1

        AlertDialog(
            onDismissRequest = {
                pendingDelete = null
            },
            title = {
                Text(
                    if (isInstallmentPurchase) {
                        "Excluir compra parcelada?"
                    } else {
                        "Excluir transação?"
                    }
                )
            },
            text = {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Deseja excluir definitivamente “${tx.description}”?"
                    )

                    if (isInstallmentPurchase) {
                        Text(
                            "Esta parcela faz parte de uma compra parcelada. " +
                                "Ao excluir, todas as parcelas relacionadas " +
                                "a esta compra serão removidas.",
                            color =
                                MaterialTheme.colorScheme.error,
                            fontWeight =
                                FontWeight.SemiBold
                        )

                        Text(
                            "Parcela selecionada: ${
                                tx.installmentNumber ?: "-"
                            }/${tx.installmentTotal}",
                            style =
                                MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteTransaction(tx)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Excluir")
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } }
        )
    }

    pendingAccountDelete?.let { account ->
        AlertDialog(
            onDismissRequest = {
                pendingAccountDelete = null
            },
            title = {
                Text("Excluir banco?")
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "A conta “${account.institutionName} - " +
                            "${account.accountName ?: "Conta"}” e todas as " +
                            "transações dela serão excluídas."
                    )
                    if (!guest) Text(
                        "Para sua segurança, enviaremos um código de 4 dígitos " +
                            "para ${state.userEmail ?: "seu e-mail"}."
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (guest) { onDeleteGuestAccount(account.id); pendingAccountDelete=null }
                        else onRequestAccountDeleteCode(account.id) {
                            pendingAccountDelete = null
                            accountDeleteCodeFor = account
                        }
                    },
                    enabled = !state.loading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    if (!guest && state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Enviando...")
                    } else {
                        Icon(Icons.Default.Email, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (guest) "Excluir do aparelho" else "Enviar código")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingAccountDelete = null
                    }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    accountDeleteCodeFor?.let { account ->
        AccountDeleteCodeDialog(
            account = account,
            email = state.userEmail,
            loading = state.loading,
            onDismiss = {
                accountDeleteCodeFor = null
            },
            onResend = {
                onRequestAccountDeleteCode(account.id) {}
            },
            onConfirm = { code ->
                onConfirmAccountDelete(
                    account.id,
                    code
                ) {
                    accountDeleteCodeFor = null
                }
            }
        )
    }

    selectedTransaction?.let { tx ->
        TransactionDetailsDialog(
            tx = tx,
            account = accounts.firstOrNull { it.id == tx.accountId },
            category = categories.firstOrNull { it.id == tx.categoryId },
            categories = categories,
            onCreateCategory = onCreateCategory,
            onDismiss = { selectedTransaction = null },
            onEdit = {
                selectedTransaction = null
                editingTransaction = tx
            },
            onUpdateCategory = { categoryId ->
                onUpdateTransactionCategory(tx.id, categoryId)
                selectedTransaction = tx.copy(categoryId = categoryId)
                if (categoryId != null) {
                    val key = categoryRuleKey(tx.description)
                    val existing = categoryRulesPrefs.getInt(key, Int.MIN_VALUE)
                    if (key.isNotBlank() && existing != categoryId) {
                        categoryRuleCandidate = tx to categoryId
                    }
                }
            },
            onAttachments = { attachmentTransaction = tx },
            onDelete = {
                selectedTransaction = null
                pendingDelete = tx
            }
        )
    }

    attachmentTransaction?.let { tx ->
        TransactionAttachmentsDialog(
            tx = tx,
            onDismiss = { attachmentTransaction = null },
            onList = onListTransactionAttachments,
            onUpload = onUploadTransactionAttachment,
            onDownload = onDownloadTransactionAttachment,
            onDelete = onDeleteTransactionAttachment
        )
    }

    categoryRuleCandidate?.let { (tx, categoryId) ->
        val categoryName = categories.firstOrNull { it.id == categoryId }?.name ?: "esta categoria"
        AlertDialog(
            onDismissRequest = { categoryRuleCandidate = null },
            title = { Text("Criar regra automática?") },
            text = {
                Text(
                    "Sempre categorizar movimentações com a descrição “${tx.description}” como $categoryName? " +
                        "A regra vale neste espaço de trabalho e pode ser corrigida manualmente depois."
                )
            },
            confirmButton = {
                Button(onClick = {
                    categoryRulesPrefs.edit()
                        .putInt(categoryRuleKey(tx.description), categoryId)
                        .apply()
                    categoryRuleCandidate = null
                    categoryRulesVersion++
                }) { Text("Sempre categorizar") }
            },
            dismissButton = {
                TextButton(onClick = { categoryRuleCandidate = null }) {
                    Text("Só desta vez")
                }
            }
        )
    }

    editingTransaction?.let { tx ->
        EditTransactionDialog(
            tx = tx,
            accounts = accounts,
            categories = categories,
            onCreateCategory = onCreateCategory,
            onDismiss = { editingTransaction = null },
            onSave = {
                accountId,
                categoryId,
                description,
                amount,
                type,
                date ->
                onUpdateManualTransaction(
                    tx.id,
                    accountId,
                    categoryId,
                    description,
                    amount,
                    type,
                    date
                )
                editingTransaction = null
            }
        )
    }

    if (showMonthlySummary) {
        MonthlySummaryDialog(
            transactions = transactions,
            categories = categories,
            cards = cards,
            onDismiss = {
                showMonthlySummary = false
            }
        )
    }

    if (showPlanning) {
        PlanningDialog(
            categories = categories,
            budgets = budgets,
            goals = goals,
            goalContributions =
                goalContributions,
            onOpenGoalHistory = {
                selectedGoalHistory = it
            },
            transactions = transactions,
            initialBudgetCategoryId = planningBudgetCategoryId,
            onDismiss = { showPlanning = false; planningBudgetCategoryId = null },
            onCreateCategory = { name, icon -> onCreateCategory(name, icon) {} },
            onUpdateCategory = onUpdateCategory,
            onDeleteCategory = onDeleteCategory,
            onSetBudget = onSetBudget,
            onDeleteBudget = onDeleteBudget,
            onCreateGoal = onCreateGoal,
            onUpdateGoal = onUpdateGoal,
            onAddGoalContribution =
                onAddGoalContribution,
            onDeleteGoal = onDeleteGoal
        )
    }

    directBudgetCategoryId?.let { categoryId ->
        categories.firstOrNull { it.id == categoryId }?.let { category ->
            val currentBudget = budgets.firstOrNull { it.categoryId == categoryId }?.amount
            SetBudgetDialog(
                category = category,
                currentAmount = currentBudget,
                onDismiss = { directBudgetCategoryId = null },
                onSave = { amount ->
                    onSetBudget(categoryId, amount)
                    directBudgetCategoryId = null
                },
                onDelete = if (currentBudget != null) {
                    {
                        onDeleteBudget(categoryId)
                        directBudgetCategoryId = null
                    }
                } else null
            )
        }
    }

    if (voiceProcessing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Entendendo seu lançamento") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Analisando o que você falou e preparando a transação...")
                }
            },
            confirmButton = {}
        )
    }

    voiceError?.let { message ->
        AlertDialog(
            onDismissRequest = { voiceError = null },
            title = { Text("Lançamento por voz") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { voiceError = null }) { Text("Entendido") } }
        )
    }

    voiceDraft?.let { draft ->
        VoiceTransactionConfirmDialog(
            initial = draft,
            accounts = accounts,
            categories = categories,
            cards = cards.filter { it.active },
            onDismiss = { voiceDraft = null },
            onConfirm = { confirmed, done ->
                val isCard = confirmed.paymentMethod == "card"
                val effectiveType = if (isCard && confirmed.cardRefund) "credit" else confirmed.type
                val signedAmount = if (effectiveType == "debit") -kotlin.math.abs(confirmed.amount) else kotlin.math.abs(confirmed.amount)
                onCreateManual(
                    if (isCard) null else confirmed.accountId,
                    confirmed.categoryId,
                    confirmed.description,
                    signedAmount,
                    effectiveType,
                    VoiceTransactionParser.toIsoDateTime(confirmed.date),
                    if (isCard) confirmed.cardId else null,
                    if (isCard) confirmed.installmentCount.coerceIn(1, 360) else 1,
                    if (isCard) VoiceTransactionParser.toIsoDateTime(confirmed.date) else null
                ) { success ->
                    done(success)
                    if (success) {
                        voiceDraft = null
                        filters = TransactionFilters(showAllTransactions = true)
                        selectedTab = HomeTab.TRANSACTIONS
                    }
                }
            }
        )
    }

    DebtCenterFlow(
        visible = showDebtCenter,
        openNewDirectly = showNewDebtDirect,
        userKey = state.userEmail ?: "local",
        workspaceId = workspaceId,
        accounts = accounts,
        transactions = transactions,
        onDismiss = { showDebtCenter = false },
        onNewConsumed = { showNewDebtDirect = false },
        onLoadForecastState = onLoadForecastState,
        onSaveForecastState = onSaveForecastState,
        onCreateManual = onCreateManual,
        onUpdateManualTransaction = onUpdateManualTransaction,
        onDeleteTransaction = onDeleteTransaction
    )

    if (showTransactionTypeDialog) {
        AlertDialog(
            onDismissRequest = { showTransactionTypeDialog = false },
            title = { Text("Adicionar despesa") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                        ),
                        shadowElevation = 1.dp
                    ) {
                        ListItem(
                            headlineContent = { Text("Adicionar manualmente") },
                            supportingContent = { Text("Lança a despesa imediatamente") },
                            leadingContent = { Icon(Icons.Default.Edit, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.combinedClickable(onClick = {
                                showTransactionTypeDialog = false
                                manualTransactionInitialType = "debit"
                                manualForceCardPurchase = false
                                manualInitialCardId = null
                                showManualDialog = true
                            }, onLongClick = {})
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                        ),
                        shadowElevation = 1.dp
                    ) {
                        ListItem(
                            headlineContent = { Text("Ler boleto / QR Code") },
                            supportingContent = { Text("Usa a câmera e preenche os dados disponíveis") },
                            leadingContent = { Icon(Icons.Default.QrCodeScanner, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.combinedClickable(onClick = {
                                showTransactionTypeDialog = false
                                launchExpenseCodeScanner()
                            }, onLongClick = {})
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                        ),
                        shadowElevation = 1.dp
                    ) {
                        ListItem(
                            headlineContent = { Text("Conta com vencimento") },
                            supportingContent = { Text("Só entra nas despesas quando você pagar") },
                            leadingContent = { Icon(Icons.Default.EventNote, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.combinedClickable(onClick = {
                                showTransactionTypeDialog = false
                                showNewPayableDialog = true
                            }, onLongClick = {})
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                        ),
                        shadowElevation = 1.dp
                    ) {
                        ListItem(
                            headlineContent = { Text("Adicionar dívida") },
                            supportingContent = { Text("Cadastre parcelas e acompanhe a amortização") },
                            leadingContent = { Icon(Icons.Default.CreditCard, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.combinedClickable(onClick = {
                                showTransactionTypeDialog = false
                                showNewDebtDirect = true
                            }, onLongClick = {})
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTransactionTypeDialog = false }) { Text("Fechar") } }
        )
    }

    expenseScanError?.let { message ->
        AlertDialog(
            onDismissRequest = { expenseScanError = null },
            title = { Text("Leitura não concluída") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { expenseScanError = null }) { Text("Entendido") } }
        )
    }

    if (showScannedExpenseChoice) {
        val draft = scannedExpenseDraft
        AlertDialog(
            onDismissRequest = { showScannedExpenseChoice = false },
            title = { Text("Como deseja salvar?") },
            text = { Text("O documento possui vencimento. Você pode deixá-lo em Contas a pagar e só lançar a despesa quando pagar.") },
            confirmButton = {
                Button(onClick = {
                    showScannedExpenseChoice = false
                    showNewPayableDialog = true
                }) { Text("Conta a pagar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showScannedExpenseChoice = false
                    manualTransactionInitialType = "debit"
                    manualForceCardPurchase = false
                    manualInitialCardId = null
                    showManualDialog = true
                }) { Text("Lançar agora") }
            }
        )
    }

    if (showNewPayableDialog) {
        NewPayableDialog(
            accounts = accounts,
            categories = categories,
            initialDescription = scannedExpenseDraft?.description.orEmpty(),
            initialAmount = scannedExpenseDraft?.amount,
            initialDueDate = scannedExpenseDraft?.dueDate,
            onDismiss = { showNewPayableDialog = false; scannedExpenseDraft = null },
            onSave = { accountId, categoryId, description, amount, dueDate, reminderDays, done ->
                val encoded = encodePayableDescription(description, amount, reminderDays)
                onCreateManual(
                    accountId, categoryId, encoded, 0.0, "debit",
                    dueDate.atStartOfDay().atOffset(ZoneOffset.UTC).toString(),
                    null, 1, null
                ) { success ->
                    done(success)
                    if (success) { showNewPayableDialog = false; scannedExpenseDraft = null; showPayablesDialog = true }
                }
            }
        )
    }

    if (showPayablesDialog) {
        PayablesDialog(
            transactions = transactions,
            accounts = accounts,
            categories = categories,
            onDismiss = { showPayablesDialog = false },
            onNew = { showPayablesDialog = false; showNewPayableDialog = true },
            onPay = { tx, accountId, categoryId, paymentDate, done ->
                val meta = decodePayableDescription(tx.description)
                if (meta == null) { done(false) } else {
                    onUpdateManualTransaction(
                        tx.id, accountId, categoryId, "Conta paga • ${meta.description}", -kotlin.math.abs(meta.amount),
                        "debit", paymentDate.atStartOfDay().atOffset(ZoneOffset.UTC).toString()
                    )
                    done(true)
                }
            },
            onDelete = { tx -> onDeleteTransaction(tx) }
        )
    }

    if (showManualDialog) {
        ManualTransactionDialog(
            accounts = accounts,
            saveError = state.error,
            cards = cards.filter { it.active },
            categories = categories,
            initialType = manualTransactionInitialType,
            forceCardPurchase =
                manualForceCardPurchase,
            initialCardId =
                manualInitialCardId,
            initialDescription = scannedExpenseDraft?.description.orEmpty(),
            initialAmount = scannedExpenseDraft?.amount,
            initialDate = scannedExpenseDraft?.dueDate,
            onCreateCategory = onCreateCategory,
            onDismiss = {
                showManualDialog = false
                scannedExpenseDraft = null
            },
            onSave = {
                accountId,
                categoryId,
                description,
                amount,
                type,
                date,
                cardId,
                installmentCount,
                firstChargeDate,
                recurringMonths,
                done ->
                if (recurringMonths > 1 && type == "debit") {
                    val baseDate = runCatching { LocalDate.parse(date.take(10)) }.getOrDefault(LocalDate.now())
                    repeat(recurringMonths) { index ->
                        val occurrenceDate = baseDate.plusMonths(index.toLong())
                        val occurrence = occurrenceDate.toString()
                        onCreateManual(
                            accountId, categoryId, description, amount, type,
                            occurrence, cardId, 1, if (cardId != null) occurrence else null
                        ) { ok -> if (index == recurringMonths - 1) done(ok) }
                    }
                } else {
                    onCreateManual(
                        accountId,
                        categoryId,
                        description,
                        amount,
                        type,
                        date,
                        cardId,
                        installmentCount,
                        firstChargeDate,
                        done
                    )
                }
            }
        )
    }

    if (showGlobalNewCard) {
        NewCreditCardDialog(
            accounts = accounts,
            onDismiss = {
                showGlobalNewCard = false
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
                showGlobalNewCard = false
            }
        )
    }

}

@Composable
private fun AccountDeleteCodeDialog(
    account: AccountEntity,
    email: String?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onResend: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var code by remember(account.id) {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Confirmar exclusão")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Digite o código de 4 dígitos enviado para " +
                        (email ?: "seu e-mail") + "."
                )
                Text(
                    "${account.institutionName} - " +
                        (account.accountName ?: "Conta"),
                    fontWeight = FontWeight.SemiBold
                )
                SixDigitCodeInput(
                    code = code,
                    digits = 4,
                    onCodeChange = {
                        code = it
                    },
                    onDone = {
                        onConfirm(code)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = onResend,
                    enabled = !loading
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Refresh, null)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(if (loading) "Enviando..." else "Reenviar código")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(code)
                },
                enabled = code.length == 4 && !loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Icon(Icons.Default.Delete, null)
                Spacer(Modifier.width(6.dp))
                Text("Confirmar exclusão")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun DeleteAllUserDataCodeDialog(
    email: String?,
    loading: Boolean,
    error: String?,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
    onResend: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var code by remember {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Confirmar exclusão dos dados")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Digite o código de 4 dígitos enviado para ${email ?: "seu e-mail"}."
                )
                Text(
                    "Esta ação não pode ser desfeita.",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
                SixDigitCodeInput(
                    code = code,
                    digits = 4,
                    onCodeChange = {
                        code = it
                        if (error != null) {
                            onClearError()
                        }
                    },
                    onDone = {
                        if (!loading) {
                            onConfirm(code)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Você tem até 5 tentativas. Após o limite, será necessário solicitar outro código.",
                    style =
                        MaterialTheme.typography.bodySmall
                )

                error?.let {
                    Text(
                        it,
                        color =
                            MaterialTheme.colorScheme.error,
                        style =
                            MaterialTheme.typography.bodySmall,
                        fontWeight =
                            FontWeight.SemiBold
                    )
                }

                TextButton(
                    onClick = {
                        onClearError()
                        onResend()
                    },
                    enabled = !loading
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            null
                        )
                    }
                    Spacer(
                        Modifier.width(4.dp)
                    )
                    Text(
                        if (loading) {
                            "Enviando..."
                        } else {
                            "Reenviar código"
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onClearError()
                    onConfirm(code)
                },
                enabled =
                    code.length == 4 &&
                        !loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color =
                            MaterialTheme.colorScheme.onError
                    )
                    Spacer(
                        Modifier.width(8.dp)
                    )
                    Text("Apagando...")
                } else {
                    Icon(
                        Icons.Default.DeleteForever,
                        null
                    )
                    Spacer(
                        Modifier.width(6.dp)
                    )
                    Text("Apagar tudo")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

private fun profileBitmapToDataUrl(source: Bitmap, scale: Float, offset: Offset): String {
    val size = minOf(source.width, source.height)
    val baseScale = maxOf(size.toFloat() / source.width, size.toFloat() / source.height)
    val totalScale = baseScale * scale
    val matrix = Matrix().apply {
        postScale(totalScale, totalScale)
        val sw = source.width * totalScale
        val sh = source.height * totalScale
        postTranslate((size - sw) / 2f + offset.x, (size - sh) / 2f + offset.y)
    }
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(source, matrix, null)
    val resized = if (size > 512) Bitmap.createScaledBitmap(out, 512, 512, true) else out
    val bytes = ByteArrayOutputStream().use { stream -> resized.compress(Bitmap.CompressFormat.JPEG, 86, stream); stream.toByteArray() }
    return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}

@Composable
private fun ProfilePhotoCropDialog(bitmap: Bitmap, onDismiss: () -> Unit, onConfirm: (Float, Offset) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustar foto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Arraste para posicionar e use dois dedos ou o controle abaixo para ajustar o zoom.", style = MaterialTheme.typography.bodySmall)
                Box(
                    Modifier.size(260.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(bitmap) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 4f); offset += pan } }
                ) {
                    Image(bitmap.asImageBitmap(), "Prévia da foto", Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Crop)
                }
                Text("Zoom")
                Slider(value = scale, onValueChange = { scale = it }, valueRange = 1f..4f)
                TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("Centralizar") }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(scale, offset) }) { Text("Usar esta foto") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun DiagnosticsDialog(
    workspaceName: String,
    serverAvailable: Boolean?,
    backupStatus: FinanceBackupStatus,
    onOpenBackup: () -> Unit,
    onDismiss: () -> Unit
) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm") }
    val lastBackupLabel = if (backupStatus.lastBackupAt > 0L) {
        Instant.ofEpochMilli(backupStatus.lastBackupAt)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    } else {
        "Ainda não realizado"
    }
    val intervalLabel = when (backupStatus.intervalDays) {
        7 -> "A cada 7 dias"
        15 -> "A cada 15 dias"
        30 -> "A cada 30 dias"
        else -> "Desativado"
    }
    val nextBackupLabel = when {
        backupStatus.intervalDays <= 0 -> "Backup automático desativado"
        !backupStatus.hasAutomaticDestination -> "Destino do Google Drive ainda não configurado"
        backupStatus.lastBackupAt <= 0L -> "Será definido após o primeiro backup"
        else -> Instant.ofEpochMilli(
            backupStatus.lastBackupAt + backupStatus.intervalDays * 86_400_000L
        ).atZone(ZoneId.systemDefault()).format(formatter)
    }
    val serverLabel = when (serverAvailable) {
        true -> "Conectado — serviços online disponíveis"
        false -> "Indisponível ou offline"
        null -> "Ainda não verificado"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.HealthAndSafety, null) },
        title = { Text("Diagnóstico e segurança") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Workspace", style = MaterialTheme.typography.labelMedium)
                Text(workspaceName.ifBlank { "Workspace" }, fontWeight = FontWeight.SemiBold)
                HorizontalDivider()
                DiagnosticLine("Banco local", "OK — dados financeiros disponíveis neste aparelho", DiagnosticStatus.SUCCESS)
                DiagnosticLine(
                    "Servidor",
                    serverLabel,
                    when (serverAvailable) {
                        true -> DiagnosticStatus.SUCCESS
                        false -> DiagnosticStatus.ERROR
                        null -> DiagnosticStatus.ATTENTION
                    }
                )
                HorizontalDivider()
                Text("Backup", style = MaterialTheme.typography.labelLarge)
                DiagnosticLine(
                    "Último backup",
                    lastBackupLabel,
                    if (backupStatus.lastBackupAt > 0L) DiagnosticStatus.SUCCESS else DiagnosticStatus.ATTENTION
                )
                DiagnosticLine(
                    "Backup automático",
                    intervalLabel,
                    if (backupStatus.intervalDays > 0 && backupStatus.hasAutomaticDestination) DiagnosticStatus.SUCCESS else DiagnosticStatus.INFO
                )
                DiagnosticLine(
                    "Próximo backup",
                    nextBackupLabel,
                    if (backupStatus.intervalDays > 0 && backupStatus.hasAutomaticDestination) DiagnosticStatus.INFO else DiagnosticStatus.ATTENTION
                )
                OutlinedButton(onClick = onOpenBackup, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CloudUpload, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Abrir Backup e sincronização")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

private enum class DiagnosticStatus { SUCCESS, ATTENTION, ERROR, INFO }

@Composable
private fun DiagnosticLine(label: String, value: String, status: DiagnosticStatus) {
    val icon = when (status) {
        DiagnosticStatus.SUCCESS -> Icons.Default.CheckCircle
        DiagnosticStatus.ATTENTION -> Icons.Default.Warning
        DiagnosticStatus.ERROR -> Icons.Default.Error
        DiagnosticStatus.INFO -> Icons.Default.Info
    }
    val statusColor = when (status) {
        DiagnosticStatus.SUCCESS -> Color(0xFF2E7D32)
        DiagnosticStatus.ATTENTION -> Color(0xFFEF6C00)
        DiagnosticStatus.ERROR -> MaterialTheme.colorScheme.error
        DiagnosticStatus.INFO -> MaterialTheme.colorScheme.primary
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = statusColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalDataDialog(
    user: UserDto,
    onDismiss: () -> Unit,
    onSave: (String, String, String?, String?) -> Unit,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    hasProfilePhoto: Boolean
) {
    var name by remember(user.id) { mutableStateOf(user.name.orEmpty()) }
    var cpf by remember(user.id) { mutableStateOf(formatCpfInput(user.cpf.orEmpty())) }
    var birth by remember(user.id) { mutableStateOf(user.birthDate.orEmpty().let { raw -> runCatching { LocalDate.parse(raw).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrDefault(raw) }) }
    var sex by remember(user.id) { mutableStateOf(user.sex ?: "prefer_not_to_say") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar dados") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label={Text("Nome completo")}, singleLine=true, modifier=Modifier.fillMaxWidth())
                OutlinedTextField(cpf, { cpf = formatCpfInput(it) }, label={Text("CPF")}, singleLine=true, modifier=Modifier.fillMaxWidth())
                OutlinedTextField(user.email, {}, enabled=false, label={Text("E-mail")}, modifier=Modifier.fillMaxWidth())
                OutlinedTextField(birth, { if(it.length<=10) birth=it }, label={Text("Data de nascimento")}, placeholder={Text("dd/MM/aaaa")}, singleLine=true, modifier=Modifier.fillMaxWidth())
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
                    OutlinedTextField(value=when(sex){"male"->"Masculino";"female"->"Feminino";"other"->"Outro";else->"Prefiro não informar"},onValueChange={},readOnly=true,label={Text("Sexo")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)},modifier=Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                        listOf("male" to "Masculino","female" to "Feminino","other" to "Outro","prefer_not_to_say" to "Prefiro não informar").forEach { (v,l) -> DropdownMenuItem(text={Text(l)},onClick={sex=v;expanded=false}) }
                    }
                }
                OutlinedButton(onClick=onChoosePhoto, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.PhotoCamera,null); Spacer(Modifier.width(6.dp)); Text(if(hasProfilePhoto) "Alterar foto" else "Adicionar foto") }
                if(hasProfilePhoto) TextButton(onClick=onRemovePhoto, modifier=Modifier.fillMaxWidth(), colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("Excluir foto") }
            }
        },
        confirmButton = { Button(onClick={
            val iso = runCatching { LocalDate.parse(birth, DateTimeFormatter.ofPattern("dd/MM/yyyy")).toString() }.getOrNull()
            onSave(name.trim(), normalizeCpf(cpf), iso, sex)
        }, enabled=name.isNotBlank() && isValidCpf(cpf)) { Text("Salvar") } },
        dismissButton = { TextButton(onClick=onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun AccountSettingsDialog(
    email: String?,
    lastSyncAt: String?,
    onSyncNow: () -> Unit,
    onBackupSync: () -> Unit,
    onEditPersonal: () -> Unit,
    onAppSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    onLegalPrivacy: () -> Unit,
    onShowOnboarding: () -> Unit,
    onNotifications: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onImportData: () -> Unit,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onUseGooglePhoto: () -> Unit,
    hasGoogleProfilePhoto: Boolean,
    hasProfilePhoto: Boolean,
    appLockEnabled: Boolean,
    onAppLockChange: (Boolean) -> Unit,
    onDeleteAllData: () -> Unit,
    onDismiss: () -> Unit,
    onLogout: () -> Unit
) {
    val syncLabel = lastSyncAt?.let { raw ->
        runCatching {
            Instant.parse(raw)
                .atZone(ZoneId.systemDefault())
                .format(
                    DateTimeFormatter.ofPattern(
                        "dd/MM/yyyy 'às' HH:mm"
                    )
                )
        }.getOrDefault(raw)
    } ?: "Ainda não sincronizado"

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Person, null) },
        title = {
            Text("Conta")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 470.dp)
                    .verticalScroll(
                        rememberScrollState()
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {
                Text("Perfil", style = MaterialTheme.typography.labelLarge)
                Text(email ?: "E-mail não disponível", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onEditPersonal, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Editar dados")
                }
                HorizontalDivider()

                Text("Backup e sincronização", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Seus dados financeiros ficam neste aparelho. Não existe mais sincronização automática com o servidor. O Google Drive é usado somente para backup.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = onBackupSync,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudUpload, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Backup e sincronização")
                }
                OutlinedButton(onClick=onImportData, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.UploadFile,null); Spacer(Modifier.width(6.dp)); Text("Importar dados financeiros") }
                Text("XLSX, CSV, JSON, TXT e XML", style=MaterialTheme.typography.bodySmall)

                HorizontalDivider()

                Text("Configurações do aplicativo", style = MaterialTheme.typography.labelLarge)
                Text("Escolha as abas da navegação, a ordem e o que aparece no Dashboard.", style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick=onAppSettings, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Tune,null); Spacer(Modifier.width(6.dp)); Text("Personalizar aplicativo") }
                OutlinedButton(onClick=onDiagnostics, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.HealthAndSafety,null); Spacer(Modifier.width(6.dp)); Text("Diagnóstico e segurança dos dados") }
                OutlinedButton(onClick=onLegalPrivacy, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Gavel,null); Spacer(Modifier.width(6.dp)); Text("Legal e privacidade") }
                OutlinedButton(onClick=onShowOnboarding, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Slideshow,null); Spacer(Modifier.width(6.dp)); Text("Ver apresentação novamente") }
                OutlinedButton(onClick=onNotifications, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.NotificationsActive,null); Spacer(Modifier.width(6.dp)); Text("Notificações") }

                HorizontalDivider()

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bloqueio ao abrir", style = MaterialTheme.typography.labelLarge)
                        Text("Use biometria, rosto ou PIN do aparelho para proteger o FinanceApp.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = appLockEnabled, onCheckedChange = onAppLockChange)
                }

                HorizontalDivider()

                Text(
                    "Zona de segurança",
                    style =
                        MaterialTheme.typography.labelLarge
                )
                Text(
                    "Apague permanentemente bancos, transações, categorias, metas, limites, conexões e o backup dos seus dados financeiros.",
                    style =
                        MaterialTheme.typography.bodySmall
                )

                OutlinedButton(
                    onClick = onDeleteAllData,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor =
                                MaterialTheme.colorScheme.error
                        )
                ) {
                    Icon(
                        Icons.Default.DeleteForever,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Apagar todos os meus dados")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onLogout
            ) {
                Icon(Icons.Default.Logout, null)
                Spacer(Modifier.width(6.dp))
                Text("Sair da conta")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Fechar")
            }
        }
    )
}


@Composable
internal fun TransactionSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.height(46.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = {
            Text(
                "Pesquisar",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = "Pesquisar"
            )
        },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(
                    onClick = {
                        onQueryChange("")
                    }
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Limpar pesquisa"
                    )
                }
            }
        }
    )
}


internal fun normalizeSearchText(value: String): String {
    return Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .lowercase(Locale("pt", "BR"))
        .trim()
}

private fun homeAccountLabel(account: AccountEntity): String {
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

internal fun transactionMatchesSearch(
    tx: TransactionEntity,
    account: AccountEntity?,
    categoryName: String?,
    card: CreditCardDto?,
    rawQuery: String
): Boolean {
    val query = normalizeSearchText(rawQuery)
    if (query.isBlank()) return true

    val locale = Locale("pt", "BR")
    val currency = NumberFormat.getCurrencyInstance(locale)
    val date = parseTxDate(tx.date)
    val formattedDate = date?.format(
        DateTimeFormatter.ofPattern("dd/MM/yyyy")
    ).orEmpty()

    val typeText = when {
        tx.amount < 0 -> "saida despesa debito"
        tx.amount > 0 -> "entrada receita credito"
        else -> "sem valor"
    }

    val sourceText = when (tx.source) {
        "manual" -> "manual"
        "open_finance" -> "automatica automatico open finance banco"
        else -> tx.source
    }

    val accountText = if (account != null) {
        listOfNotNull(
            account.institutionName,
            account.accountName,
            account.maskedAccount
        ).joinToString(" ")
    } else {
        "sem conta vinculada sem banco"
    }

    val amountAbs = kotlin.math.abs(tx.amount)
    val monthTokens = if (date != null) {
        val monthFull = date.format(DateTimeFormatter.ofPattern("MMMM", locale))
        val monthShort = date.format(DateTimeFormatter.ofPattern("MMM", locale))
        val monthNumber = date.format(DateTimeFormatter.ofPattern("MM"))
        val monthYear = date.format(DateTimeFormatter.ofPattern("MM/yyyy"))
        val monthNameYear = date.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
        "$monthFull $monthShort $monthNumber $monthYear $monthNameYear ${date.year}"
    } else {
        ""
    }
    val searchableText = listOf(
        tx.description,
        accountText,
        categoryName ?: "sem categoria",
        card?.bankName.orEmpty(),
        card?.brand.orEmpty(),
        card?.lastFour.orEmpty(),
        card?.nickname.orEmpty(),
        if (card != null) {
            "cartao cartão compra cartão compra cartao parcela cartão parcela cartao"
        } else {
            ""
        },
        tx.installmentNumber?.toString().orEmpty(),
        tx.installmentTotal?.toString().orEmpty(),
        typeText,
        sourceText,
        tx.transactionType,
        tx.status,
        formattedDate,
        monthTokens,
        tx.date,
        currency.format(tx.amount),
        currency.format(amountAbs),
        String.format(locale, "%.2f", tx.amount),
        String.format(locale, "%.2f", amountAbs)
    ).joinToString(" ")

    return normalizeSearchText(searchableText)
        .contains(query)
}

@Composable
private fun AttentionCenterDialog(
    uncategorizedCount: Int,
    duplicateCount: Int,
    cardsMissingDue: Int,
    upcomingDueCount: Int,
    onOpenUncategorized: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenInvoices: () -> Unit,
    onDismiss: () -> Unit
) {
    val total = uncategorizedCount + duplicateCount + cardsMissingDue + upcomingDueCount
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (total > 0) Icons.Default.NotificationsActive else Icons.Default.CheckCircle, null) },
        title = { Text("Central de pendências") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (total == 0) {
                    Text("Tudo em ordem neste workspace.", fontWeight = FontWeight.SemiBold)
                    Text("Quando algo precisar da sua atenção, aparecerá aqui.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("$total item(ns) pedem sua atenção.", fontWeight = FontWeight.SemiBold)
                    if (uncategorizedCount > 0) AttentionItem(Icons.Default.Category, "$uncategorizedCount transação(ões) sem categoria", "Ir para a mais antiga", onOpenUncategorized)
                    if (duplicateCount > 0) AttentionItem(Icons.Default.ContentCopy, "$duplicateCount possível(is) duplicidade(s)", "Comparar e conciliar", onOpenDuplicates)
                    if (cardsMissingDue > 0) AttentionItem(Icons.Default.CreditCard, "$cardsMissingDue cartão(ões) sem dia de vencimento", "Completar cadastro", onOpenCards)
                    if (upcomingDueCount > 0) AttentionItem(Icons.Default.Event, "$upcomingDueCount fatura(s) com valor em aberto e vencimento nos próximos 7 dias", "Ver faturas", onOpenInvoices)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
private fun AttentionItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, action: String, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) { Text(action) }
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    workspaceName: String,
    onManageWorkspaces: () -> Unit,
    accounts: List<AccountEntity>,
    cards: List<CreditCardDto>,
    transactions: List<TransactionEntity>,
    categories: List<CategoryDto>,
    budgets: List<BudgetDto>,
    goals: List<GoalDto>,
    goalContributions: List<GoalContributionDto>,
    onOpenGoalHistory: (GoalDto) -> Unit,
    pendingSyncCount: Int,
    offlineSyncing: Boolean,
    onAddTransaction: () -> Unit,
    onOpenPayables: () -> Unit,
    onVoiceTransaction: () -> Unit,
    onOpenTransactions: () -> Unit,
    onOpenInvoices: () -> Unit,
    onOpenDebts: () -> Unit,
    dashboardCustomization: AppCustomization,
    onDashboardCustomizationChange: (AppCustomization) -> Unit,
    onOpenPlanning: (Int?) -> Unit,
    onOpenSummary: () -> Unit
) {
    val locale = remember {
        Locale("pt", "BR")
    }
    val currency = remember {
        NumberFormat.getCurrencyInstance(locale)
    }
    var dashboardAnnual by remember { mutableStateOf(false) }
    var dashboardValuesVisible by rememberSaveable { mutableStateOf(false) }
    var showFinanceCenters by remember { mutableStateOf(false) }
    val currentMonth = remember {
        YearMonth.now()
    }
    val previousMonth = remember {
        currentMonth.minusMonths(1)
    }

    val currentTransactions = remember(
        transactions,
        currentMonth
    ) {
        transactions.filter { tx ->
            parseTxDate(tx.date)
                ?.let(YearMonth::from) == currentMonth
        }
    }

    val previousTransactions = remember(
        transactions,
        previousMonth
    ) {
        transactions.filter { tx ->
            parseTxDate(tx.date)
                ?.let(YearMonth::from) == previousMonth
        }
    }

    val currentYear = remember {
        Year.now().value
    }

    val currentYearTransactions = remember(
        transactions,
        currentYear
    ) {
        transactions.filter { tx ->
            parseTxDate(tx.date)?.year ==
                currentYear
        }
    }

    val periodTransactions =
        if (dashboardAnnual) {
            currentYearTransactions
        } else {
            currentTransactions
        }

    val totalBalance = remember(
        accounts,
        transactions
    ) {
        val accountsWithBalance =
            accounts.filter {
                it.currentBalance != null
            }

        if (accountsWithBalance.isEmpty()) {
            transactions
                .filter(::isCashFlowTransaction)
                .sumOf { it.amount }
        } else {
            val bankBalance =
                accountsWithBalance.sumOf {
                    it.currentBalance ?: 0.0
                }

            val adjustments =
                transactions
                    .asSequence()
                    .filter {
                        it.source !=
                            "open_finance"
                    }
                    // Saldo consolidado segue apenas o fluxo real de caixa:
                    // compra no cartao nao reduz a conta; pagamento da fatura reduz.
                    .filter(::isCashFlowTransaction)
                    .filter { tx ->
                        val account =
                            accounts.firstOrNull {
                                it.id ==
                                    tx.accountId
                            }

                        if (
                            account?.currentBalance ==
                            null
                        ) {
                            true
                        } else {
                            val balanceTime =
                                parseOffsetDateTime(
                                    account
                                        .balanceUpdatedAt
                                )
                            val txTime =
                                parseOffsetDateTime(
                                    tx.date
                                )

                            balanceTime == null ||
                                txTime == null ||
                                txTime.isAfter(
                                    balanceTime
                                )
                        }
                    }
                    .sumOf {
                        it.amount
                    }

            bankBalance + adjustments
        }
    }

    val periodIncome = remember(periodTransactions) {
        periodTransactions
            .filter { it.amount > 0.0 && isCashFlowTransaction(it) }
            .sumOf { it.amount }
    }

    val periodExpense = remember(periodTransactions) {
        periodTransactions
            .filter { it.amount < 0.0 && isCashFlowTransaction(it) }
            .sumOf { abs(it.amount) }
    }

    val periodBalance = periodIncome - periodExpense

    val previousExpense = remember(previousTransactions) {
        previousTransactions
            .filter { it.amount < 0.0 && isCashFlowTransaction(it) }
            .sumOf { abs(it.amount) }
    }

    val comparison = remember(
        periodExpense,
        previousExpense
    ) {
        if (previousExpense > 0.0) {
            (
                (periodExpense - previousExpense) /
                    previousExpense
                ) * 100.0
        } else {
            null
        }
    }

    val monthlyBudgets = remember(budgets, categories, transactions, currentMonth, dashboardAnnual) {
        monthlyBudgetOverview(budgets, categories, transactions, currentMonth, annual = dashboardAnnual)
    }

    val biggestExpenseTransaction = remember(periodTransactions) {
        periodTransactions
            .filter { it.amount < 0.0 && isCashFlowTransaction(it) }
            .maxByOrNull { abs(it.amount) }
    }

    val latestTransactions = remember(transactions) {
        transactions
            .filter(::isCashFlowTransaction)
            .sortedWith(
                compareByDescending<TransactionEntity> {
                    parseTxDate(it.date)
                }.thenByDescending {
                    parseTxTime(it.date)
                }
            )
            .take(4)
    }

    val upcomingInvoiceAlerts = remember(cards, transactions) {
        val today = LocalDate.now()
        val alerts = mutableListOf<Triple<com.financeapp.mobile.data.remote.CreditCardDto, LocalDate, Int>>()
        cards.filter { it.active && it.dueDay != null }.forEach { card ->
            fun closingFor(month: YearMonth): LocalDate {
                val day = (card.closingDay ?: 31).coerceIn(1, 31)
                return month.atDay(day.coerceAtMost(month.lengthOfMonth()))
            }
            fun invoiceEndFor(date: LocalDate): LocalDate {
                val close = closingFor(YearMonth.from(date))
                return if (!date.isAfter(close)) close else closingFor(YearMonth.from(date).plusMonths(1))
            }
            val grouped = transactions.asSequence()
                .filter { it.cardId == card.id && it.source != "card_payment" }
                .mapNotNull { tx -> runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull()?.let { invoiceEndFor(it) to tx } }
                .groupBy({ it.first }, { it.second })
            grouped.forEach invoiceLoop@ { (end, rows) ->
                val total = (-rows.sumOf { it.amount }).coerceAtLeast(0.0)
                if (total <= 0.005) return@invoiceLoop
                val paid = transactions.asSequence()
                    .filter { it.cardId == card.id && it.source == "card_payment" && it.purchaseDate?.take(10) == end.toString() }
                    .sumOf { kotlin.math.abs(it.amount) }
                if (total - paid <= 0.005) return@invoiceLoop
                var dueMonth = YearMonth.from(end)
                val dueDay = card.dueDay ?: return@invoiceLoop
                if (dueDay <= end.dayOfMonth) dueMonth = dueMonth.plusMonths(1)
                val due = dueMonth.atDay(dueDay.coerceAtMost(dueMonth.lengthOfMonth()))
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, due).toInt()
                if (days in 0..5) alerts += Triple(card, due, days)
            }
        }
        alerts.sortedBy { it.third }
    }

    val dashboardListState = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sectionSpacingPx = with(density) { 10.dp.toPx() }
    val localDashboardOrder = remember { mutableStateListOf<String>() }
    val sectionHeights = remember { mutableStateMapOf<String, Int>() }
    var draggingDashboardId by remember { mutableStateOf<String?>(null) }
    var dashboardDragOffset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(dashboardCustomization.dashboardOrder, draggingDashboardId) {
        if (draggingDashboardId == null && localDashboardOrder != dashboardCustomization.dashboardOrder) {
            localDashboardOrder.clear()
            localDashboardOrder.addAll(dashboardCustomization.dashboardOrder)
        }
    }
    if (localDashboardOrder.isEmpty()) {
        localDashboardOrder.addAll(dashboardCustomization.dashboardOrder)
    }

    fun finishDashboardDrag() {
        val dragged = draggingDashboardId ?: return
        draggingDashboardId = null
        dashboardDragOffset = 0f
        if (localDashboardOrder != dashboardCustomization.dashboardOrder) {
            onDashboardCustomizationChange(
                dashboardCustomization.copy(dashboardOrder = localDashboardOrder.toList())
            )
        }
    }

    fun updateDashboardDrag(id: String, deltaY: Float) {
        dashboardDragOffset += deltaY
        var index = localDashboardOrder.indexOf(id)
        if (index < 0) return

        if (dashboardDragOffset > 0f && index < localDashboardOrder.lastIndex) {
            val nextId = localDashboardOrder[index + 1]
            val distance = ((sectionHeights[id] ?: 1) + (sectionHeights[nextId] ?: 1)) / 2f + sectionSpacingPx
            if (dashboardDragOffset > distance / 2f) {
                localDashboardOrder.removeAt(index)
                localDashboardOrder.add(index + 1, id)
                dashboardDragOffset -= distance
            }
        } else if (dashboardDragOffset < 0f && index > 0) {
            val previousId = localDashboardOrder[index - 1]
            val distance = ((sectionHeights[id] ?: 1) + (sectionHeights[previousId] ?: 1)) / 2f + sectionSpacingPx
            if (-dashboardDragOffset > distance / 2f) {
                localDashboardOrder.removeAt(index)
                localDashboardOrder.add(index - 1, id)
                dashboardDragOffset += distance
            }
        }
    }

    LazyColumn(
        state = dashboardListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 0.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val visibleMetricIds = localDashboardOrder.filter {
            it in setOf("INCOME", "EXPENSES") && it !in dashboardCustomization.hiddenDashboard
        }

        localDashboardOrder.forEach { dashboardId ->
            val isGroupedMetric = dashboardId in setOf("INCOME", "EXPENSES")
            val shouldRender = dashboardId != "INVOICES" &&
                dashboardId !in dashboardCustomization.hiddenDashboard &&
                (!isGroupedMetric || dashboardId == visibleMetricIds.firstOrNull())
            if (shouldRender) {
                item(key = "dashboard_section_$dashboardId") {
                    val isDragging = draggingDashboardId == dashboardId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { sectionHeights[dashboardId] = it.height }
                            .zIndex(if (isDragging) 10f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) dashboardDragOffset else 0f
                                scaleX = if (isDragging) 1.02f else 1f
                                scaleY = if (isDragging) 1.02f else 1f
                                alpha = if (isDragging) 0.96f else 1f
                                shadowElevation = if (isDragging) 22.dp.toPx() else 0f
                            }
                            .pointerInput(dashboardId) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingDashboardId = dashboardId
                                        dashboardDragOffset = 0f
                                    },
                                    onDragEnd = { finishDashboardDrag() },
                                    onDragCancel = { finishDashboardDrag() },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        updateDashboardDrag(dashboardId, dragAmount.y)
                                    }
                                )
                            },
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (dashboardId) {
                            "CURRENT_VALUE" -> DashboardBalanceCard(
                                value = currency.format(totalBalance),
                                annual = dashboardAnnual,
                                valuesVisible = dashboardValuesVisible,
                                workspaceName = workspaceName,
                                onManageWorkspaces = onManageWorkspaces,
                                onValuesVisibleChange = { dashboardValuesVisible = it },
                                onAnnualChange = { dashboardAnnual = it }
                            )

                            "INCOME", "EXPENSES" -> Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if ("INCOME" in visibleMetricIds) {
                                    DashboardMetricCard(
                                        Modifier.weight(1f), "Entradas", if (dashboardValuesVisible) currency.format(periodIncome) else "R$ ••••••",
                                        Icons.Default.ArrowDownward, Color(0xFF178A3A),
                                        Color(0xFFE9F8EF)
                                    )
                                }
                                if ("EXPENSES" in visibleMetricIds) {
                                    DashboardMetricCard(
                                        Modifier.weight(1f), "Saídas", if (dashboardValuesVisible) currency.format(periodExpense) else "R$ ••••••",
                                        Icons.Default.ArrowUpward, MaterialTheme.colorScheme.error,
                                        Color(0xFFFFEFF1)
                                    )
                                }
                            }

                            "INVOICES" -> ElevatedCard(
                                onClick = onOpenInvoices,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = dashboardCardColor()),
                                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CreditCard, contentDescription = null)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Faturas de cartão", fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text("Consultar faturas e registrar pagamentos", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                    }
                                }
                            }

                            "BUDGETS" -> {
                                Text(
                                    if (dashboardAnnual) "Gastos por categoria · ${currentMonth.year}"
                                    else "Orçamentos do mês · ${currentMonth.monthValue}/${currentMonth.year}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (monthlyBudgets.rows.isNotEmpty()) {
                                    CategoryBudgetCarousel(monthlyBudgets.rows, dashboardAnnual) { row -> onOpenPlanning(row.id) }
                                } else {
                                    TextButton(onClick = { onOpenPlanning(null) }) { Text("Definir limites por categoria") }
                                }
                            }

                            "BIGGEST_SPEND" -> {
                                Text(
                                    if (dashboardAnnual) "Maior gasto do ano" else "Maior gasto do mês",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                biggestExpenseTransaction?.let { tx ->
                                    val category = categories.firstOrNull { it.id == tx.categoryId }
                                    val categoryName = category?.name ?: "Sem categoria"
                                    val accountName = accounts.firstOrNull { it.id == tx.accountId }?.let(::homeAccountLabel) ?: cards.firstOrNull { it.id == tx.cardId }?.bankName ?: "Sem banco"
                                    val sourceLabel = if (tx.source.contains("open", ignoreCase = true)) "Open Finance" else "Manual"
                                    Card(
                                        Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(containerColor = dashboardCardColor()),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                    ) {
                                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(tx.description, fontWeight = FontWeight.Bold, maxLines = 1)
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    BankBadge(accountName)
                                                    Icon(categoryIconVector(category?.icon, categoryName), null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Text(categoryName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                                }
                                                Text("${formatDateForDisplay(tx.date)} • Saída • $sourceLabel", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                            }
                                            Text(currency.format(tx.amount), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                } ?: Card(
                                    Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = dashboardCardColor()),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                ) {
                                    Text(
                                        if (dashboardAnnual) "Nenhum gasto neste ano." else "Nenhum gasto neste mês.",
                                        modifier = Modifier.padding(14.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            "GOALS" -> if (goals.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Metas financeiras", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Deslize para ver mais", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                LazyRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(end = 12.dp)
                                ) {
                                    items(items = goals, key = { it.id }) { goal ->
                                        val progress = if (goal.targetAmount > 0.0) (goal.currentAmount / goal.targetAmount).coerceIn(0.0, 1.0) else 0.0
                                        Card(
                                            modifier = Modifier.width(265.dp).combinedClickable(
                                                onClick = { onOpenGoalHistory(goal) },
                                                onLongClick = { onOpenGoalHistory(goal) }
                                            ),
                                            shape = RoundedCornerShape(20.dp),
                                            colors = CardDefaults.cardColors(containerColor = dashboardCardColor()),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                        ) {
                                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Flag, contentDescription = null)
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(goal.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                                    }
                                                    Text("${(progress * 100.0).toInt()}%", fontWeight = FontWeight.Bold)
                                                }
                                                LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                                                Text("${currency.format(goal.currentAmount)} de ${currency.format(goal.targetAmount)}", style = MaterialTheme.typography.bodySmall)
                                                val contributionCount = goalContributions.count { it.goalId == goal.id }
                                                Text(
                                                    if (contributionCount == 1) "1 aporte registrado • toque para ver" else "$contributionCount aportes registrados • toque para ver",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            "QUICK_ACTIONS" -> {
                                val hasDuePayableAlert = transactions.any { tx ->
                                    val meta = decodePayableDescription(tx.description) ?: return@any false
                                    val due = runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull() ?: return@any false
                                    java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), due).toInt() <= meta.reminderDays
                                }
                                if (upcomingInvoiceAlerts.isNotEmpty() || hasDuePayableAlert) {
                                    Spacer(Modifier.height(18.dp))
                                }
                                if (upcomingInvoiceAlerts.isNotEmpty()) {
                                    val firstInvoice = upcomingInvoiceAlerts.first()
                                    val card = firstInvoice.first
                                    val days = firstInvoice.third
                                    val title = card.nickname?.takeIf { it.isNotBlank() } ?: card.bankName
                                    Card(
                                        onClick = onOpenInvoices,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1479F8).copy(alpha = 0.09f))
                                    ) {
                                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CreditCard, contentDescription = null, tint = Color(0xFF1479F8))
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text("Fatura próxima do vencimento", fontWeight = FontWeight.Bold, color = Color(0xFF1479F8))
                                                Text(
                                                    if (days == 0) "$title vence hoje" else "$title vence em $days dia(s)",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                if (upcomingInvoiceAlerts.size > 1) {
                                                    Text("+ ${upcomingInvoiceAlerts.size - 1} outra(s) fatura(s)", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            Icon(Icons.Default.ChevronRight, contentDescription = "Abrir Central de Faturas")
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                val today = LocalDate.now()
                                val duePayables = transactions.mapNotNull { tx ->
                                    val meta = decodePayableDescription(tx.description) ?: return@mapNotNull null
                                    val due = runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull() ?: return@mapNotNull null
                                    val days = java.time.temporal.ChronoUnit.DAYS.between(today, due).toInt()
                                    if (days <= meta.reminderDays) Triple(tx, meta, days) else null
                                }.sortedBy { it.third }
                                if (duePayables.isNotEmpty()) {
                                    val first = duePayables.first()
                                    val tone = if (first.third < 0) Color(0xFFC62828) else Color(0xFFE58A00)
                                    Card(
                                        onClick = onOpenPayables,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = tone.copy(alpha = 0.10f))
                                    ) {
                                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = tone)
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(if (first.third < 0) "Conta vencida" else "Conta próxima do vencimento", fontWeight = FontWeight.Bold, color = tone)
                                                Text(
                                                    if (first.third < 0) "${first.second.description} venceu há ${-first.third} dia(s) • ${currency.format(first.second.amount)}"
                                                    else "${first.second.description} vence em ${first.third} dia(s) • ${currency.format(first.second.amount)}",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                if (duePayables.size > 1) Text("+ ${duePayables.size - 1} outra(s) conta(s)", style = MaterialTheme.typography.labelSmall)
                                            }
                                            Icon(Icons.Default.ChevronRight, contentDescription = "Ver contas a pagar")
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                Text("Ações rápidas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    QuickActionTile(
                                        modifier = Modifier.weight(1f),
                                        label = "Lançar por voz",
                                        icon = Icons.Default.Mic,
                                        accent = Color(0xFF6D3DF5),
                                        onClick = onVoiceTransaction
                                    )
                                    QuickActionTile(
                                        modifier = Modifier.weight(1f),
                                        label = "Nova transação",
                                        icon = Icons.Default.Add,
                                        accent = Color(0xFF1479F8),
                                        onClick = onAddTransaction
                                    )
                                    QuickActionTile(
                                        modifier = Modifier.weight(1f),
                                        label = "Transações",
                                        icon = Icons.Default.ReceiptLong,
                                        accent = Color(0xFFFF7A2F),
                                        onClick = onOpenTransactions
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    QuickActionTile(
                                        modifier = Modifier.weight(1f),
                                        label = "Planejamento",
                                        icon = Icons.Default.AccountBalanceWallet,
                                        accent = Color(0xFF00A67A),
                                        onClick = { onOpenPlanning(null) }
                                    )
                                    QuickActionTile(
                                        modifier = Modifier.weight(1f),
                                        label = "Resumo",
                                        icon = Icons.Default.BarChart,
                                        accent = Color(0xFF4F62D8),
                                        onClick = onOpenSummary
                                    )
                                    QuickActionTile(
                                        modifier = Modifier.weight(1f),
                                        label = "Central financeira",
                                        icon = Icons.Default.EventNote,
                                        accent = Color(0xFF0B6E75),
                                        onClick = { showFinanceCenters = true }
                                    )

                                }
                            }

                            "LATEST" -> {
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Últimas transações", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    TextButton(onClick = onOpenTransactions) { Text("Ver todas") }
                                }
                                if (latestTransactions.isEmpty()) {
                                    Text("Nenhuma transação registrada.", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    latestTransactions.forEach { tx ->
                                        val category = categories.firstOrNull { it.id == tx.categoryId }
                                        val categoryName = category?.name ?: "Sem categoria"
                                        Card(
                                            Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(18.dp),
                                            colors = CardDefaults.cardColors(containerColor = dashboardCardColor()),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                        ) {
                                            Row(
                                                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    if (tx.amount >= 0.0) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                                    contentDescription = null,
                                                    tint = if (tx.amount >= 0.0) Color(0xFF178A3A) else MaterialTheme.colorScheme.error
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Column(Modifier.weight(1f)) {
                                                    val accountName = accounts.firstOrNull { it.id == tx.accountId }?.let(::homeAccountLabel) ?: cards.firstOrNull { it.id == tx.cardId }?.bankName ?: "Sem banco"
                                                    val typeLabel = if (tx.amount >= 0.0) "Entrada" else "Saída"
                                                    val sourceLabel = if (tx.source.contains("open", ignoreCase = true)) "Open Finance" else "Manual"
                                                    Text(tx.description, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        BankBadge(accountName)
                                                        Icon(categoryIconVector(category?.icon, categoryName), null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        Text(categoryName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                                    }
                                                    Text("${formatDateForDisplay(tx.date)} • $typeLabel • $sourceLabel", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                                }
                                                Text(
                                                    currency.format(tx.amount),
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (tx.amount >= 0.0) Color(0xFF178A3A) else MaterialTheme.colorScheme.error
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

    if (showFinanceCenters) {
        AlertDialog(
            onDismissRequest = { showFinanceCenters = false },
            title = { Text("Central financeira") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                        ),
                        shadowElevation = 1.dp
                    ) {
                        ListItem(
                            headlineContent = { Text("Central de Faturas") },
                            supportingContent = { Text("Consulte e pague suas faturas de cartão") },
                            leadingContent = { Icon(Icons.Default.CreditCard, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.combinedClickable(
                                onClick = { showFinanceCenters = false; onOpenInvoices() },
                                onLongClick = {}
                            )
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                        ),
                        shadowElevation = 1.dp
                    ) {
                        ListItem(
                            headlineContent = { Text("Contas a pagar") },
                            supportingContent = { Text("Pendentes, próximas do vencimento e vencidas") },
                            leadingContent = { Icon(Icons.Default.EventNote, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.combinedClickable(
                                onClick = { showFinanceCenters = false; onOpenPayables() },
                                onLongClick = {}
                            )
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                        ),
                        shadowElevation = 1.dp
                    ) {
                        ListItem(
                            headlineContent = { Text("Minhas dívidas") },
                            supportingContent = { Text("Acompanhe parcelas, saldo devedor e pagamentos") },
                            leadingContent = { Icon(Icons.Default.CreditCard, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.combinedClickable(
                                onClick = { showFinanceCenters = false; onOpenDebts() },
                                onLongClick = {}
                            )
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showFinanceCenters = false }) { Text("Fechar") } }
        )
    }

}

@Composable
private fun dashboardCardColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF15243A) else Color(0xFFFFFFFF)

@Composable
private fun DashboardPeriodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        tonalElevation = if (selected) 1.dp else 0.dp
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DashboardBalanceCard(
    value: String,
    annual: Boolean,
    valuesVisible: Boolean,
    workspaceName: String,
    onManageWorkspaces: () -> Unit,
    onValuesVisibleChange: (Boolean) -> Unit,
    onAnnualChange: (Boolean) -> Unit
) {
    val today = remember { LocalDate.now() }
    val ptBr = remember { Locale("pt", "BR") }
    val monthName = remember(today, ptBr) {
        today.month
            .getDisplayName(TextStyle.FULL, ptBr)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(ptBr) else it.toString() }
    }
    val periodDetail = if (annual) today.year.toString() else "$monthName de ${today.year}"
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 360.dp
        val veryNarrow = maxWidth < 320.dp
        val largeFont = fontScale > 1.20f
        val veryLargeFont = fontScale > 1.35f
        val stackedHeader = veryNarrow || (narrow && largeFont)

        // Responsividade de layout: tamanhos normais são preservados; em telas pequenas
        // os controles mudam de posição em vez de simplesmente encolher tudo.
        val cardHeight = when {
            stackedHeader && veryLargeFont -> 244.dp
            stackedHeader -> 230.dp
            veryLargeFont -> 218.dp
            largeFont -> 208.dp
            else -> 196.dp
        }
        val outerHeight = cardHeight + 28.dp
        val horizontalPadding = when {
            veryNarrow -> 12.dp
            narrow -> 16.dp
            else -> 20.dp
        }
        val titleSize = if (veryNarrow) 15.sp else 17.sp
        val balanceSize = if (veryNarrow) 32.sp else 38.sp
        val periodSize = if (veryNarrow) 16.sp else 18.sp
        val selectorWidth = if (stackedHeader) 156.dp else 148.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(outerHeight)
        ) {
            // Faixa azul-marinho contínua atrás do card, ligada visualmente ao cabeçalho.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .graphicsLayer {
                        scaleX = 1.14f
                        clip = false
                    }
                    .background(Color(0xFF071B33))
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .zIndex(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF0E7BF6), Color(0xFF4E6BF7), Color(0xFF925AF4))
                        )
                    )
                    .padding(horizontal = horizontalPadding, vertical = if (largeFont) 14.dp else 17.dp)
            ) {
                Icon(
                    Icons.Default.ShowChart,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(if (narrow) 88.dp else 104.dp)
                )

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    if (stackedHeader) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Saldo consolidado",
                                    modifier = Modifier.weight(1f),
                                    color = Color.White.copy(alpha = 0.96f),
                                    fontSize = titleSize,
                                    lineHeight = titleSize * 1.18f,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(
                                    onClick = { onValuesVisibleChange(!valuesVisible) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        if (valuesVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (valuesVisible) "Ocultar valores" else "Mostrar valores",
                                        tint = Color.White.copy(alpha = 0.96f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                modifier = Modifier.align(Alignment.End).width(selectorWidth),
                                shape = RoundedCornerShape(999.dp),
                                color = Color.White.copy(alpha = 0.15f)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(Modifier.weight(1f)) {
                                        DashboardPeriodChipOnGradient(
                                            label = "Mensal",
                                            selected = !annual,
                                            onClick = { onAnnualChange(false) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Box(Modifier.weight(1f)) {
                                        DashboardPeriodChipOnGradient(
                                            label = "Anual",
                                            selected = annual,
                                            onClick = { onAnnualChange(true) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Saldo consolidado",
                                    modifier = Modifier.weight(1f, fill = false),
                                    color = Color.White.copy(alpha = 0.96f),
                                    fontSize = titleSize,
                                    lineHeight = titleSize * 1.18f,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(6.dp))
                                IconButton(
                                    onClick = { onValuesVisibleChange(!valuesVisible) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        if (valuesVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (valuesVisible) "Ocultar valores" else "Mostrar valores",
                                        tint = Color.White.copy(alpha = 0.96f),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.width(8.dp))
                            Surface(
                                modifier = Modifier.width(selectorWidth),
                                shape = RoundedCornerShape(999.dp),
                                color = Color.White.copy(alpha = 0.15f)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(Modifier.weight(1f)) {
                                        DashboardPeriodChipOnGradient(
                                            label = "Mensal",
                                            selected = !annual,
                                            onClick = { onAnnualChange(false) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Box(Modifier.weight(1f)) {
                                        DashboardPeriodChipOnGradient(
                                            label = "Anual",
                                            selected = annual,
                                            onClick = { onAnnualChange(true) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        if (valuesVisible) value else "R$ ••••••",
                        color = Color.White,
                        fontSize = balanceSize,
                        lineHeight = balanceSize * 1.05f,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            periodDetail,
                            modifier = Modifier.weight(1f),
                            color = Color.White.copy(alpha = 0.96f),
                            fontSize = periodSize,
                            lineHeight = periodSize * 1.15f,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Surface(
                            onClick = onManageWorkspaces,
                            modifier = Modifier.widthIn(max = if (veryNarrow) 112.dp else 148.dp),
                            shape = RoundedCornerShape(999.dp),
                            color = Color.White.copy(alpha = 0.18f),
                            contentColor = Color.White
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = if (veryNarrow) 8.dp else 10.dp,
                                    vertical = if (largeFont) 5.dp else 6.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Workspaces,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color.White.copy(alpha = 0.95f)
                                )
                                Text(
                                    workspaceName.ifBlank { "Workspace" },
                                    modifier = Modifier.weight(1f, fill = false),
                                    color = Color.White,
                                    fontSize = if (veryNarrow) 10.sp else 11.sp,
                                    lineHeight = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Default.ExpandMore,
                                    contentDescription = "Trocar workspace",
                                    modifier = Modifier.size(14.dp),
                                    tint = Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardPeriodChipOnGradient(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) Color.White else Color.Transparent
    ) {
        Text(
            label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 7.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 12.sp),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            color = if (selected) Color(0xFF3555D8) else Color.White.copy(alpha = 0.86f)
        )
    }
}

@Composable
private fun DashboardMetricCard(
    modifier: Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    valueColor: Color,
    lightBackground: Color
) {
    Card(
        modifier = modifier.heightIn(min = 122.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
                MaterialTheme.colorScheme.surfaceVariant
            else lightBackground
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = valueColor.copy(alpha = 0.13f)
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = valueColor,
                        modifier = Modifier.padding(7.dp).size(18.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                if (title == "Entradas") "Valores recebidos" else "Valores gastos",
                style = MaterialTheme.typography.labelSmall,
                color = valueColor
            )
        }
    }
}

@Composable
private fun NewPayableDialog(
    accounts: List<AccountEntity>,
    categories: List<CategoryDto>,
    initialDescription: String = "",
    initialAmount: Double? = null,
    initialDueDate: LocalDate? = null,
    onDismiss: () -> Unit,
    onSave: (Int?, Int?, String, Double, LocalDate, Int, (Boolean) -> Unit) -> Unit
) {
    var description by remember { mutableStateOf(initialDescription) }
    var amountDigits by remember { mutableStateOf(initialAmount?.let { ((it * 100).toLong()).toString() }.orEmpty()) }
    var dueDate by remember { mutableStateOf(initialDueDate ?: LocalDate.now().plusDays(7)) }
    var accountId by remember { mutableStateOf<Int?>(null) }
    var categoryId by remember { mutableStateOf<Int?>(null) }
    var reminderDays by remember { mutableIntStateOf(5) }
    var saving by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val amount = BrlMoney.toDouble(amountDigits)


    if (showDatePicker) {
        val zone = ZoneOffset.UTC
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = dueDate.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        dueDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancelar") } }
        ) { DatePicker(state = pickerState) }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Nova conta a pagar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = description, onValueChange = { description = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Descrição") }, singleLine = true)
                OutlinedTextField(
                    value = amountDigits,
                    onValueChange = { amountDigits = BrlMoney.digits(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Valor") },
                    singleLine = true,
                    visualTransformation = BrlMoneyVisualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = dueDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Vencimento") },
                    trailingIcon = { IconButton(onClick = { showDatePicker = true }) { Icon(Icons.Default.CalendarMonth, null) } }
                )
                DropdownMenuField("Banco/conta (opcional)", accountId, accounts.map { it.id to it.institutionName }) { accountId = it }
                DropdownMenuField("Categoria (opcional)", categoryId, categories.map { it.id to it.name }) { categoryId = it }
                Text("Avisar antes do vencimento", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15).forEach { days -> FilterChip(selected = reminderDays == days, onClick = { reminderDays = days }, label = { Text("$days dias") }) }
                }
                Text("Esta conta não entra em Saídas nem no Saldo consolidado até ser marcada como paga.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(enabled = !saving && description.isNotBlank() && amount > 0, onClick = { saving = true; onSave(accountId, categoryId, description.trim(), amount, dueDate, reminderDays) { ok -> saving = false; if (!ok) {} } }) { Text(if (saving) "Salvando..." else "Salvar") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun <T> DropdownMenuField(label: String, value: T?, items: List<Pair<T, String>>, onValue: (T?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(items.firstOrNull { it.first == value }?.second ?: label, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Nenhum") }, onClick = { onValue(null); expanded = false })
            items.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onValue(id); expanded = false }) }
        }
    }
}

@Composable
private fun PayablesDialog(
    transactions: List<TransactionEntity>,
    accounts: List<AccountEntity>,
    categories: List<CategoryDto>,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onPay: (TransactionEntity, Int?, Int?, LocalDate, (Boolean) -> Unit) -> Unit,
    onDelete: (TransactionEntity) -> Unit
) {
    val locale = Locale("pt", "BR")
    val currency = remember { NumberFormat.getCurrencyInstance(locale) }
    val today = LocalDate.now()
    val payables = transactions.mapNotNull { tx ->
        decodePayableDescription(tx.description)?.let {
            Triple(tx, it, runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull())
        }
    }.sortedBy { it.third ?: LocalDate.MAX }
    val totalPending = payables.sumOf { it.second.amount }
    val overdueCount = payables.count { row -> row.third?.isBefore(today) == true }
    val upcomingCount = payables.count { row ->
        val due = row.third ?: return@count false
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, due)
        days in 0..7
    }
    var paying by remember { mutableStateOf<Triple<TransactionEntity, PayableMeta, LocalDate?>?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contas a pagar") },
        text = {
            Box(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .heightIn(max = 620.dp)
            ) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Adicionar conta")
                        }
                    }
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("Resumo", style = MaterialTheme.typography.labelMedium)
                                Text(currency.format(totalPending), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(
                                    "${payables.size} pendente(s) • $upcomingCount nos próximos 7 dias • $overdueCount vencida(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (payables.isEmpty()) {
                        item {
                            Text(
                                "Nenhuma conta pendente. Use “Adicionar conta” para cadastrar.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(payables, key = { it.first.id }) { row ->
                        val (tx, meta, due) = row
                        val days = due?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt() }
                        val status = when {
                            days == null -> "Pendente"
                            days < 0 -> "Vencida"
                            days == 0 -> "Vence hoje"
                            days <= 7 -> "Próxima do vencimento"
                            else -> "Em aberto"
                        }
                        val statusColor = when {
                            days != null && days < 0 -> MaterialTheme.colorScheme.error
                            days != null && days <= 7 -> Color(0xFFE87900)
                            else -> MaterialTheme.colorScheme.primary
                        }
                        val bankName = accounts.firstOrNull { it.id == tx.accountId }?.institutionName
                        val categoryName = categories.firstOrNull { it.id == tx.categoryId }?.name
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(meta.description, fontWeight = FontWeight.Bold)
                                        if (!bankName.isNullOrBlank() || !categoryName.isNullOrBlank()) {
                                            Text(
                                                listOfNotNull(bankName, categoryName).joinToString(" • "),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                    Text(currency.format(meta.amount), fontWeight = FontWeight.Bold)
                                }
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = statusColor.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        status,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        color = statusColor,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (due != null) {
                                    Text(
                                        "Vencimento ${due.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))} • aviso ${meta.reminderDays} dias antes",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { onDelete(tx) }) {
                                        Icon(Icons.Default.Delete, null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Excluir")
                                    }
                                    FilledTonalButton(onClick = { paying = row }) {
                                        Icon(Icons.Default.Payments, null)
                                        Spacer(Modifier.width(5.dp))
                                        Text("Pagar")
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

    paying?.let { row ->
        val (tx, meta, _) = row
        var accountId by remember(tx.id) { mutableStateOf(tx.accountId) }
        var categoryId by remember(tx.id) { mutableStateOf(tx.categoryId) }
        var paymentDate by remember(tx.id) { mutableStateOf(LocalDate.now()) }
        AlertDialog(
            onDismissRequest = { paying = null },
            title = { Text("Confirmar pagamento") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${meta.description} • ${currency.format(meta.amount)}")
                    DropdownMenuField("Banco/conta", accountId, accounts.map { it.id to it.institutionName }) { accountId = it }
                    DropdownMenuField("Categoria", categoryId, categories.map { it.id to it.name }) { categoryId = it }
                    Text("Data do pagamento: ${paymentDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}")
                }
            },
            confirmButton = {
                Button(onClick = { onPay(tx, accountId, categoryId, paymentDate) { if (it) paying = null } }) {
                    Text("Marcar como paga")
                }
            },
            dismissButton = { TextButton(onClick = { paying = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun QuickActionTile(
    modifier: Modifier,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.height(108.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
                MaterialTheme.colorScheme.surfaceVariant
            else Color(0xFFF9FAFC)
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = accent.copy(alpha = 0.13f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.padding(10.dp).size(22.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}


@Composable
private fun MonthlySummaryDialog(
    transactions: List<TransactionEntity>,
    categories: List<CategoryDto>,
    cards: List<CreditCardDto>,
    onDismiss: () -> Unit
) {
    var selectedMode by remember {
        mutableStateOf(0)
    }
    var selectedMonth by remember {
        mutableStateOf(YearMonth.now())
    }
    var selectedYear by remember {
        mutableStateOf(Year.now().value)
    }

    val currentMonth = remember {
        YearMonth.now()
    }
    val currentYear = remember {
        Year.now().value
    }

    val context = LocalContext.current
    val locale = remember { Locale("pt", "BR") }
    val currency = remember {
        NumberFormat.getCurrencyInstance(locale)
    }

    val periodTransactions = remember(
        transactions,
        selectedMode,
        selectedMonth,
        selectedYear
    ) {
        transactions.filter { tx ->
            val date = parseTxDate(tx.date)
            if (selectedMode == 0) {
                date?.let(YearMonth::from) == selectedMonth
            } else {
                date?.year == selectedYear
            }
        }
    }

    val previousPeriodTransactions = remember(
        transactions,
        selectedMode,
        selectedMonth,
        selectedYear
    ) {
        transactions.filter { tx ->
            val date = parseTxDate(tx.date)
            if (selectedMode == 0) {
                date?.let(YearMonth::from) ==
                    selectedMonth.minusMonths(1)
            } else {
                date?.year == selectedYear - 1
            }
        }
    }

    val income = periodTransactions
        .filter { it.amount > 0 && isCashFlowTransaction(it) }
        .sumOf { it.amount }

    val commonExpenses = periodTransactions
        .filter {
            it.amount < 0 && isCashFlowTransaction(it) && it.source != "card_payment"
        }
        .sumOf { abs(it.amount) }

    val cardExpenses = periodTransactions
        .filter { it.amount < 0 && it.source == "card_payment" }
        .sumOf { abs(it.amount) }

    val cardExpenseTotals = remember(
        periodTransactions,
        cards
    ) {
        periodTransactions
            .filter {
                it.amount < 0 &&
                    it.cardId != null && it.source == "card_payment"
            }
            .groupBy {
                it.cardId
            }
            .mapNotNull { entry ->
                val card =
                    cards.firstOrNull {
                        it.id == entry.key
                    }

                if (card == null) {
                    null
                } else {
                    card to
                        entry.value.sumOf {
                            abs(it.amount)
                        }
                }
            }
            .sortedByDescending {
                it.second
            }
    }

    val expenses =
        commonExpenses + cardExpenses

    val openingBalance = if (selectedMode == 0) {
        val start = selectedMonth.atDay(1)
        transactions
            .filter { tx ->
                val date = parseTxDate(tx.date)
                date != null && date.isBefore(start) && isCashFlowTransaction(tx)
            }
            .sumOf { it.amount }
    } else {
        0.0
    }

    // Entradas e saídas continuam sendo somente do período selecionado.
    // No mensal, o saldo carrega o caixa acumulado dos meses anteriores.
    val balance = openingBalance + income - expenses

    val previousExpenses = previousPeriodTransactions
        .filter { it.amount < 0 && isCashFlowTransaction(it) }
        .sumOf { abs(it.amount) }

    val variation =
        if (previousExpenses > 0.0) {
            ((expenses - previousExpenses) /
                previousExpenses) * 100.0
        } else {
            null
        }

    val categoryExpenses = remember(
        periodTransactions,
        categories
    ) {
        periodTransactions
            .filter { it.amount < 0 && isCashFlowTransaction(it) }
            .groupBy { it.categoryId }
            .map { entry ->
                val name = categories.firstOrNull {
                    it.id == entry.key
                }?.name ?: "Sem categoria"

                name to entry.value.sumOf {
                    abs(it.amount)
                }
            }
            .sortedByDescending { it.second }
    }

    val maxCategoryAmount =
        categoryExpenses.maxOfOrNull { it.second } ?: 0.0

    val annualMonths = remember(
        transactions,
        selectedYear,
        currentMonth,
        currentYear
    ) {
        val lastMonth =
            if (selectedYear == currentYear) {
                currentMonth.monthValue
            } else {
                12
            }

        (1..lastMonth).map { month ->
            val items = transactions.filter { tx ->
                val date = parseTxDate(tx.date)
                date?.year == selectedYear &&
                    date.monthValue == month
            }

            Triple(
                month,
                items.filter { it.amount > 0 && isCashFlowTransaction(it) }
                    .sumOf { it.amount },
                items.filter { it.amount < 0 && isCashFlowTransaction(it) }
                    .sumOf { abs(it.amount) }
            )
        }
    }

    val periodTitle =
        if (selectedMode == 0) {
            selectedMonth.month
                .getDisplayName(TextStyle.FULL, locale)
                .replaceFirstChar { it.uppercase() } +
                " de ${selectedMonth.year}"
        } else {
            "Ano $selectedYear"
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Resumo financeiro")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 610.dp)
            ) {
                TabRow(
                    selectedTabIndex = selectedMode
                ) {
                    Tab(
                        selected = selectedMode == 0,
                        onClick = {
                            selectedMode = 0
                        },
                        text = {
                            Text("Mensal")
                        }
                    )
                    Tab(
                        selected = selectedMode == 1,
                        onClick = {
                            selectedMode = 1
                        },
                        text = {
                            Text("Anual")
                        }
                    )
                }

                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment =
                                Alignment.CenterVertically,
                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = {
                                    if (selectedMode == 0) {
                                        selectedMonth =
                                            selectedMonth.minusMonths(1)
                                    } else {
                                        selectedYear -= 1
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.ChevronLeft,
                                    "Período anterior"
                                )
                            }

                            Text(
                                periodTitle,
                                fontWeight = FontWeight.Bold
                            )

                            val canGoForward =
                                if (selectedMode == 0) {
                                    selectedMonth.isBefore(
                                        currentMonth
                                    )
                                } else {
                                    selectedYear < currentYear
                                }

                            IconButton(
                                onClick = {
                                    if (!canGoForward) {
                                        return@IconButton
                                    }

                                    if (selectedMode == 0) {
                                        selectedMonth =
                                            selectedMonth.plusMonths(1)
                                    } else {
                                        selectedYear += 1
                                    }
                                },
                                enabled = canGoForward
                            ) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    "Próximo período"
                                )
                            }
                        }
                    }

                    if (periodTransactions.isNotEmpty()) {
                        item {
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        createAndShareFinancialSummaryPdf(
                                            context = context,
                                            transactions =
                                                periodTransactions,
                                            categories = categories,
                                            cards = cards,
                                            periodTitle = periodTitle,
                                            annualMonths =
                                                if (selectedMode == 1) {
                                                    annualMonths
                                                } else {
                                                    emptyList()
                                                },
                                            openingBalance = openingBalance
                                        )
                                    },
                                    modifier =
                                        Modifier.weight(1f),
                                    contentPadding =
                                        PaddingValues(
                                            horizontal = 10.dp,
                                            vertical = 10.dp
                                        )
                                ) {
                                    Icon(
                                        Icons.Default.PictureAsPdf,
                                        contentDescription = null
                                    )
                                    Spacer(
                                        Modifier.width(5.dp)
                                    )
                                    Text("PDF")
                                }


                            }
                        }
                    }

                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    if (selectedMode == 0) {
                                        "Visão do mês"
                                    } else {
                                        "Visão do ano"
                                    },
                                    fontWeight = FontWeight.Bold
                                )

                                Row(Modifier.fillMaxWidth()) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Entradas",
                                            style =
                                                MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            currency.format(income),
                                            color = Color(0xFF178A3A),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Saídas totais",
                                            style =
                                                MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            currency.format(expenses),
                                            color = Color(0xFFC62828),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                Row(Modifier.fillMaxWidth()) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Outros meios de pagamento",
                                            style =
                                                MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            currency.format(commonExpenses),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Gastos com cartões",
                                            style =
                                                MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            currency.format(cardExpenses),
                                            color = Color(0xFFC62828),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                if (cardExpenseTotals.isNotEmpty()) {
                                    Spacer(
                                        Modifier.height(8.dp)
                                    )

                                    Text(
                                        "Por cartão",
                                        style =
                                            MaterialTheme.typography.labelLarge,
                                        fontWeight =
                                            FontWeight.SemiBold
                                    )

                                    cardExpenseTotals.forEach {
                                            item ->
                                        val card = item.first
                                        val total = item.second

                                        Row(
                                            modifier =
                                                Modifier.fillMaxWidth(),
                                            horizontalArrangement =
                                                Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "${card.bankName} •••• ${card.lastFour}",
                                                style =
                                                    MaterialTheme.typography.bodySmall,
                                                modifier =
                                                    Modifier.weight(1f)
                                            )
                                            Text(
                                                currency.format(total),
                                                style =
                                                    MaterialTheme.typography.bodySmall,
                                                fontWeight =
                                                    FontWeight.SemiBold,
                                                color =
                                                    Color(0xFFC62828)
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider()

                                Text(
                                    "Saldo: ${currency.format(balance)}",
                                    fontWeight = FontWeight.Bold,
                                    color =
                                        if (balance >= 0) {
                                            Color(0xFF178A3A)
                                        } else {
                                            Color(0xFFC62828)
                                        }
                                )

                                Text(
                                    "Total de transações: ${
                                        periodTransactions.size
                                    }",
                                    style =
                                        MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    if (selectedMode == 0) {
                                        "Comparação com o mês anterior"
                                    } else {
                                        "Comparação com o ano anterior"
                                    },
                                    fontWeight = FontWeight.Bold
                                )

                                if (variation == null) {
                                    Text(
                                        "Não há despesas suficientes no período anterior para comparar.",
                                        style =
                                            MaterialTheme.typography.bodySmall
                                    )
                                } else {
                                    Text(
                                        when {
                                            variation > 0 ->
                                                "Você gastou ${
                                                    String.format(
                                                        locale,
                                                        "%.1f",
                                                        abs(variation)
                                                    )
                                                }% a mais."
                                            variation < 0 ->
                                                "Você gastou ${
                                                    String.format(
                                                        locale,
                                                        "%.1f",
                                                        abs(variation)
                                                    )
                                                }% a menos."
                                            else ->
                                                "Seus gastos ficaram iguais."
                                        },
                                        color =
                                            if (variation > 0) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            }
                                    )
                                    Text(
                                        "Período anterior: ${
                                            currency.format(previousExpenses)
                                        }",
                                        style =
                                            MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    if (selectedMode == 1) {
                        item {
                            Text(
                                "Evolução mensal",
                                style =
                                    MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        items(
                            items = annualMonths,
                            key = { it.first }
                        ) { monthItem ->
                            val month = Month.of(monthItem.first)

                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        month
                                            .getDisplayName(
                                                TextStyle.FULL,
                                                locale
                                            )
                                            .replaceFirstChar {
                                                it.uppercase()
                                            },
                                        fontWeight =
                                            FontWeight.SemiBold
                                    )
                                    Text(
                                        "Entradas: ${
                                            currency.format(monthItem.second)
                                        }",
                                        style =
                                            MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "Saídas: ${
                                            currency.format(monthItem.third)
                                        }",
                                        style =
                                            MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            "Gastos por categoria",
                            style =
                                MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (categoryExpenses.isEmpty()) {
                        item {
                            Text(
                                "Nenhuma saída registrada neste período.",
                                style =
                                    MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        items(
                            items = categoryExpenses.take(10),
                            key = { it.first }
                        ) { categoryItem ->
                            val amount = categoryItem.second
                            val progress =
                                if (maxCategoryAmount > 0.0) {
                                    amount / maxCategoryAmount
                                } else {
                                    0.0
                                }
                            val share =
                                if (expenses > 0.0) {
                                    amount / expenses * 100.0
                                } else {
                                    0.0
                                }

                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Text(
                                            categoryItem.first,
                                            modifier =
                                                Modifier.weight(1f),
                                            fontWeight =
                                                FontWeight.SemiBold
                                        )
                                        Text(currency.format(amount))
                                    }

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
                                        "${
                                            String.format(
                                                locale,
                                                "%.1f",
                                                share
                                            )
                                        }% das saídas do período",
                                        style =
                                            MaterialTheme.typography.labelSmall
                                    )
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
}

@Composable
private fun CategoryManagerDialog(
    categories: List<CategoryDto>,
    onDismiss: () -> Unit,
    onCreate: (String, String?) -> Unit,
    onUpdate: (Int, String, String?) -> Unit,
    onDelete: (Int) -> Unit
) {
    var showNew by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CategoryDto?>(null) }
    var deleting by remember { mutableStateOf<CategoryDto?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gerenciar categorias") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showNew = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Nova categoria")
                }

                HorizontalDivider()

                if (categories.isEmpty()) {
                    Text("Nenhuma categoria criada.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(categories, key = { it.id }) { category ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(categoryIconVector(category.icon, category.name), null)
                                    Spacer(Modifier.width(10.dp))
                                    Text(category.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    IconButton(onClick = { editing = category }) {
                                        Icon(Icons.Default.Edit, "Editar categoria")
                                    }
                                    IconButton(onClick = { deleting = category }) {
                                        Icon(Icons.Default.DeleteOutline, "Excluir categoria", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )

    if (showNew) {
        NewCategoryDialog(
            existingCategories = categories,
            onDismiss = { showNew = false },
            onSave = { name, icon -> onCreate(name, icon); showNew = false }
        )
    }

    editing?.let { category ->
        RenameCategoryDialog(
            category = category,
            onDismiss = { editing = null },
            onSave = { name, icon -> onUpdate(category.id, name, icon); editing = null }
        )
    }

    deleting?.let { category ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir categoria?") },
            text = { Text("As transações que usam “${category.name}” serão mantidas e ficarão como “Sem categoria”.") },
            confirmButton = {
                Button(
                    onClick = { onDelete(category.id); deleting = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Excluir")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
internal fun RenameCategoryDialog(
    category: CategoryDto,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit
) {
    var name by remember(category.id) { mutableStateOf(category.name) }
    var icon by remember(category.id) { mutableStateOf(category.icon) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar categoria") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nome da categoria") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Ícone da categoria", fontWeight = FontWeight.SemiBold)
                CategoryIconPicker(selectedIcon = icon, categoryName = name, onSelected = { icon = it })
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name.trim(), icon ?: inferredCategoryIconId(name)) }, enabled = name.isNotBlank()) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
internal fun NewCategoryDialog(
    existingCategories:
        List<CategoryDto>,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf<String?>(null) }

    fun categoryKey(
        value: String
    ): String =
        java.text.Normalizer
            .normalize(
                value.trim(),
                java.text.Normalizer.Form.NFD
            )
            .replace(
                Regex("\\p{M}+"),
                ""
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .lowercase(
                Locale("pt", "BR")
            )

    val duplicate =
        remember(
            name,
            existingCategories
        ) {
            val typed =
                categoryKey(name)

            typed.isNotBlank() &&
                existingCategories.any {
                    categoryKey(it.name) ==
                        typed
                }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Nova categoria")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = duplicate,
                    label = { Text("Nome da categoria") },
                    supportingText = { if (duplicate) Text("Essa categoria já existe.") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Ícone da categoria", fontWeight = FontWeight.SemiBold)
                CategoryIconPicker(selectedIcon = icon, categoryName = name, onSelected = { icon = it })
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!duplicate) {
                        onSave(name.trim(), icon ?: inferredCategoryIconId(name))
                    }
                },
                enabled =
                    name.isNotBlank() &&
                        !duplicate
            ) {
                Text("Criar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancelar")
            }
        }
    )
}


private fun legacyFormatCurrencyFromDigits(digits: String): String {
    val numericDigits = digits.filter(Char::isDigit)

    if (numericDigits.isEmpty()) {
        return "R$ 0,00"
    }

    val cents = numericDigits.toLongOrNull() ?: 0L
    val value = cents / 100.0

    return NumberFormat
        .getCurrencyInstance(Locale("pt", "BR"))
        .format(value)
}


private fun parseOffsetDateTime(
    raw: String?
): OffsetDateTime? {
    if (raw.isNullOrBlank()) {
        return null
    }

    return runCatching {
        OffsetDateTime.parse(raw)
    }.getOrNull()
}

internal fun parseTxDate(raw: String): LocalDate? {
    if (raw.isBlank()) return null

    /*
     * Para o Finance App, "date" representa o dia da movimentação.
     * Preservamos primeiro o YYYY-MM-DD recebido do backend, sem converter
     * para o fuso do aparelho (o que poderia transformar dia 18 em dia 17).
     */
    return runCatching {
        LocalDate.parse(raw.take(10))
    }.getOrElse {
        runCatching {
            OffsetDateTime.parse(raw).toLocalDate()
        }.getOrElse {
            runCatching {
                LocalDateTime.parse(raw).toLocalDate()
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

private fun parseTxTime(raw: String): LocalTime? {
    if (raw.isBlank()) return null

    /*
     * Se o backend enviou um instante/offset, convertemos para o fuso
     * do aparelho para mostrar a hora local ao usuário.
     */
    return runCatching {
        OffsetDateTime
            .parse(raw)
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalTime()
    }.getOrElse {
        runCatching {
            Instant
                .parse(raw)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
        }.getOrElse {
            runCatching {
                LocalDateTime
                    .parse(raw)
                    .toLocalTime()
            }.getOrNull()
        }
    }
}

private fun formatDateForDisplay(raw: String): String =
    parseTxDate(raw)
        ?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        ?: raw

private fun formatDateTimeForDisplay(raw: String): String {
    val date = parseTxDate(raw)
    val time = parseTxTime(raw)

    return when {
        date != null && time != null ->
            "${date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))} • " +
                time.format(DateTimeFormatter.ofPattern("HH:mm"))

        date != null ->
            date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

        else ->
            raw
    }
}


@Composable
private fun rememberIsDeviceOnline(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager
    }

    fun status(): Boolean {
        val network =
            manager.activeNetwork
                ?: return false
        val caps =
            manager.getNetworkCapabilities(
                network
            ) ?: return false
        return caps.hasCapability(
            NetworkCapabilities
                .NET_CAPABILITY_VALIDATED
        )
    }

    var online by remember {
        mutableStateOf(status())
    }

    DisposableEffect(manager) {
        val callback =
            object :
                ConnectivityManager
                    .NetworkCallback() {
                override fun onAvailable(
                    network: Network
                ) {
                    online = status()
                }

                override fun onLost(
                    network: Network
                ) {
                    online = status()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities:
                        NetworkCapabilities
                ) {
                    online =
                        capabilities.hasCapability(
                            NetworkCapabilities
                                .NET_CAPABILITY_VALIDATED
                        )
                }
            }

        manager.registerDefaultNetworkCallback(
            callback
        )

        onDispose {
            runCatching {
                manager.unregisterNetworkCallback(
                    callback
                )
            }
        }
    }

    return online
}


@Composable
private fun OpenFinanceProfileDialog(
    initialName: String,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave:
        (String, String) -> Unit
) {
    var name by remember(
        initialName
    ) {
        mutableStateOf(
            initialName
        )
    }

    var cpfField by remember {
        mutableStateOf(
            TextFieldValue("")
        )
    }

    val cpf = cpfField.text

    val validName =
        name.trim()
            .split(
                Regex("\\s+")
            )
            .size >= 2

    val validCpf =
        isValidCpf(cpf)

    AlertDialog(
        onDismissRequest = {
            if (!loading) {
                onDismiss()
            }
        },
        title = {
            Text(
                "Complete seus dados"
            )
        },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        10.dp
                    )
            ) {
                Text(
                    "Precisamos do seu nome completo e CPF para iniciar a autorização do Open Finance com seu banco.",
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(
                            "Nome completo"
                        )
                    },
                    isError =
                        name.isNotBlank() &&
                            !validName
                )

                OutlinedTextField(
                    value = cpfField,
                    onValueChange = {
                            typed ->
                        val formatted =
                            formatCpfInput(
                                typed.text
                            )

                        cpfField =
                            TextFieldValue(
                                text = formatted,
                                selection =
                                    TextRange(
                                        formatted.length
                                    )
                            )
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text("CPF")
                    },
                    placeholder = {
                        Text(
                            "000.000.000-00"
                        )
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Number
                        ),
                    isError =
                        normalizeCpf(cpf)
                            .length == 11 &&
                            !validCpf,
                    supportingText = {
                        if (
                            normalizeCpf(cpf)
                                .length == 11 &&
                            !validCpf
                        ) {
                            Text(
                                "CPF inválido."
                            )
                        }
                    }
                )

                error?.let {
                    Text(
                        it,
                        color =
                            MaterialTheme
                                .colorScheme
                                .error,
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )
                }

                Text(
                    "Esses dados ficam associados ao seu perfil e não serão solicitados novamente nas próximas conexões.",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name.trim(),
                        normalizeCpf(
                            cpf
                        )
                    )
                },
                enabled =
                    !loading &&
                        validName &&
                        validCpf
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier.size(
                                18.dp
                            ),
                        strokeWidth =
                            2.dp
                    )
                    Spacer(
                        Modifier.width(
                            7.dp
                        )
                    )
                }
                Text(
                    if (loading) {
                        "Salvando..."
                    } else {
                        "Continuar"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !loading
            ) {
                Text("Cancelar")
            }
        }
    )
}


private fun categoryRuleKey(description: String): String =
    Normalizer.normalize(description.trim().lowercase(Locale("pt", "BR")), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()


@Composable
private fun NotificationSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(FinanceNotificationSettings.read(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun save(next: FinanceNotificationPreferences) {
        prefs = next
        FinanceNotificationSettings.save(context, next)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (next.payables || next.invoices || next.budgets || next.monthlySummary)
        ) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.NotificationsActive, null) },
        title = { Text("Notificações") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Escolha quais lembretes o FinanceApp pode enviar.", style = MaterialTheme.typography.bodyMedium)
                SwitchListTile("Contas a pagar", "Usa o aviso de 5, 10, 15 dias (ou o prazo escolhido no cadastro).", prefs.payables) { save(prefs.copy(payables = it)) }
                SwitchListTile("Faturas de cartão", "Aviso 3 dias antes e no dia do vencimento.", prefs.invoices) { save(prefs.copy(invoices = it)) }
                SwitchListTile("Categorias / limites", "Avisa ao chegar a 80% e 100% do limite mensal.", prefs.budgets) { save(prefs.copy(budgets = it)) }
                SwitchListTile("Resumo mensal", "No primeiro dia do mês, mostra entradas e saídas do mês anterior.", prefs.monthlySummary) { save(prefs.copy(monthlySummary = it)) }
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    TextButton(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("Permitir notificações no Android")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Concluir") } }
    )
}

@Composable
private fun SwitchListTile(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

