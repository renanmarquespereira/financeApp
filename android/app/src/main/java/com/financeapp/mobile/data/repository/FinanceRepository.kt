package com.financeapp.mobile.data.repository
import kotlinx.coroutines.flow.first

import com.financeapp.mobile.data.remote.AccountUpdateRequest
import com.financeapp.mobile.data.remote.AccountCreateRequest
import com.financeapp.mobile.data.remote.CreditCardUpdateRequest
import com.financeapp.mobile.data.remote.RegisterBelvoLinkRequest
import com.financeapp.mobile.data.remote.BelvoWidgetTokenRequest
import android.app.backup.BackupManager
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.financeapp.mobile.data.local.AccountEntity
import com.financeapp.mobile.data.local.BudgetEntity
import com.financeapp.mobile.data.local.CategoryEntity
import com.financeapp.mobile.data.local.CreditCardEntity
import com.financeapp.mobile.data.local.FinanceDao
import com.financeapp.mobile.data.local.GoalEntity
import com.financeapp.mobile.data.local.GoalContributionEntity
import com.financeapp.mobile.data.local.PendingSyncOperationEntity
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.AccountDeleteConfirmRequest
import com.financeapp.mobile.data.remote.BudgetDto
import com.financeapp.mobile.data.remote.BudgetUpsertRequest
import com.financeapp.mobile.data.remote.CategoryCreateRequest
import com.financeapp.mobile.data.remote.CategoryDto
import com.financeapp.mobile.data.remote.CategoryUpdateRequest
import com.financeapp.mobile.data.remote.CreditCardDto
import com.financeapp.mobile.data.remote.CreditCardCreateRequest
import com.financeapp.mobile.data.remote.FinanceApi
import com.financeapp.mobile.data.remote.GoalDto
import com.financeapp.mobile.data.remote.GoalContributionDto
import com.financeapp.mobile.data.remote.GoalContributionCreateRequest
import com.financeapp.mobile.data.remote.GoalUpsertRequest
import com.financeapp.mobile.data.remote.TransactionCreateRequest
import com.financeapp.mobile.data.remote.TransactionUpdateRequest
import com.financeapp.mobile.data.remote.UserDataDeleteConfirmRequest
import com.financeapp.mobile.sync.OfflineSyncWorker
import com.financeapp.mobile.sync.OfflineSyncTracker
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import androidx.room.withTransaction
import com.financeapp.mobile.data.local.AppDatabase
import com.financeapp.mobile.data.local.WorkspaceEntity
import com.financeapp.mobile.util.SessionManager
import com.financeapp.mobile.util.WorkspaceOperation
import com.financeapp.mobile.util.WorkspaceScope
import androidx.work.workDataOf
import retrofit2.HttpException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.financeapp.mobile.data.remote.TransactionAttachmentDto
import java.util.UUID
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private fun androidTransactionType(raw: String): String {
    val key = Normalizer.normalize(raw.trim().lowercase(Locale.forLanguageTag("pt-BR")), Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
    return when (key) {
        "expense", "debit", "saida", "despesa" -> "debit"
        "income", "credit", "entrada", "receita" -> "credit"
        else -> raw.trim().lowercase(Locale.ROOT)
    }
}

private fun androidTransactionAmount(amount: Double, rawType: String): Double =
    when (androidTransactionType(rawType)) {
        "debit" -> -kotlin.math.abs(amount)
        "credit" -> kotlin.math.abs(amount)
        else -> amount
    }

private data class AccountUpdatePayload(val institutionName: String, val accountName: String?, val maskedAccount: String?)
private data class CardUpdatePayload(val bankName: String, val brand: String, val lastFour: String, val nickname: String?, val creditLimit: Double?, val closingDay: Int?, val dueDay: Int?)

private data class CategoryUpdatePayload(
    val name: String,
    val icon: String? = null
)

private data class TransactionUpdatePayload(
    val accountId: Int?,
    val categoryId: Int?,
    val date: String,
    val description: String,
    val amount: Double,
    val transactionType: String,
    val status: String
)

private data class TransactionCategoryUpdatePayload(
    val categoryId: Int?
)

private data class GoalUpdatePayload(
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double,
    val targetDate: String?
)

private data class GoalContributionDeletePayload(
    val goalId: Int
)

private data class PendingAttachmentCreatePayload(
    val localAttachmentId: Int,
    val fileName: String,
    val contentType: String,
    val storedName: String,
    val sizeBytes: Long,
    val createdAt: String
)

private data class PendingAttachmentDeletePayload(
    val attachmentId: Int
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class FinanceRepository @Inject constructor(
    private val api: FinanceApi,
    private val dao: FinanceDao,
    private val database: AppDatabase,
    private val session: SessionManager,
    private val syncTracker: OfflineSyncTracker,
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val operationMutex = Mutex()
    // 43.0.85: Android local-first estrito. O servidor permanece para login e
    // servicos online, mas nao envia nem baixa automaticamente dados financeiros.
    private val financialCloudSyncEnabled = false
    // 43.0.84: Android local-first estrito. O servidor permanece para login e
    // servicos online, mas nao envia nem baixa automaticamente dados financeiros.
// 43.0.82: Android passa a ser local-first estrito. O servidor continua
    // disponivel para autenticacao/IA/Open Finance, mas nao sincroniza o banco
    // financeiro local. Backup/restauracao sao explicitamente controlados pelo usuario.
    val activeWorkspace = session.workspaceScope
    fun isGuest() = session.isGuest()
    fun guestTransferPending() = session.guestTransferPending()
    private val guestTransferFile get() = java.io.File(context.filesDir, "guest-transfer.json")
    fun guestTransferFrozen() = guestTransferFile.exists() || java.io.File(guestTransferFile.path + ".bak").exists()

    fun screenWorkspace(): WorkspaceScope = session.requireWorkspace()
    val workspaces: Flow<List<WorkspaceEntity>> = session.workspaceScope.flatMapLatest { scope ->
        if (scope == null) flowOf(emptyList()) else dao.observeWorkspaces(scope.userId)
    }

    private suspend fun <T> manageWorkspaces(block: suspend (WorkspaceOperation) -> T): T {
        val operation = session.captureOperation()
        operationMutex.lock()
        try {
            check(session.localUserId() == operation.userId) { "A conta mudou" }
            return WorkspaceOperation.run(operation) { block(operation) }
        } finally { operationMutex.unlock() }
    }

    private suspend fun cacheWorkspace(row: com.financeapp.mobile.data.remote.WorkspaceDto) {
        require(row.userId == WorkspaceOperation.current().userId)
        dao.upsertWorkspaces(listOf(WorkspaceEntity(row.userId, row.id, row.name, row.kind, row.isDefault, row.archivedAt)))
    }

    private suspend fun purgeWorkspace(userId: Int, id: String) {
        database.withTransaction {
            dao.clearTransactions(userId, id)
            dao.clearAccounts(userId, id)
            dao.clearCategories(userId, id)
            dao.clearCards(userId, id)
            dao.clearGoals(userId, id)
            dao.clearBudgets(userId, id)
            dao.clearGoalContributions(userId, id)
            dao.clearPendingOperations(userId, id)
            dao.deleteWorkspace(userId, id)
        }
    }

    private suspend fun reconcileWorkspaces(rows: List<com.financeapp.mobile.data.remote.WorkspaceDto>) {
        val userId = WorkspaceOperation.current().userId
        require(rows.all { it.userId == userId })
        require(rows.any { it.archivedAt == null })
        val active = session.workspaceScope.value
        database.withTransaction {
            dao.workspacesForUser(userId).filter { cached ->
                rows.none { it.id == cached.id } &&
                    // Um workspace UUID ativo apenas local pode ter sido criado offline.
                    // Preserve-o até o worker conseguir criá-lo no servidor.
                    !(active?.userId == userId && active.workspaceId == cached.id && !cached.isDefault && cached.archivedAt == null)
            }.forEach { purgeWorkspace(userId, it.id) }
            rows.forEach { cacheWorkspace(it) }
        }
        if (active?.userId == userId && rows.none { it.id == active.workspaceId && it.archivedAt == null }) {
            val localOnly = dao.workspacesForUser(userId).firstOrNull {
                it.id == active.workspaceId && !it.isDefault && it.archivedAt == null
            }
            if (localOnly == null) {
                session.activateWorkspace(WorkspaceScope(userId, rows.first { it.archivedAt == null }.id))
            }
        }
    }

    suspend fun requestWorkspaceDeleteBatch(ids: List<String>) = manageWorkspaces { operation ->
        val selected = ids.distinct()
        check(selected.isNotEmpty()) { "Selecione ao menos um workspace" }
        val rows = dao.workspacesForUser(operation.userId).filter { it.id in selected }
        check(rows.size == selected.size) { "Atualize a lista de workspaces" }
        val activeRemaining = dao.workspacesForUser(operation.userId).count { it.archivedAt == null && it.id !in selected }
        check(activeRemaining >= 1) { "Você precisa manter pelo menos um workspace ativo." }
        api.workspaceBatchDeleteCode(com.financeapp.mobile.data.remote.WorkspaceBatchDeleteRequest(selected)).message
    }

    suspend fun confirmWorkspaceDeleteBatch(ids: List<String>, code: String) = manageWorkspaces { operation ->
        val selected = ids.distinct()
        api.workspaceBatchDeleteConfirm(com.financeapp.mobile.data.remote.WorkspaceBatchDeleteRequest(selected), code)
        val fallback = dao.workspacesForUser(operation.userId).firstOrNull { it.archivedAt == null && it.id !in selected }
        selected.forEach { purgeWorkspace(operation.userId, it) }
        if (session.workspaceScope.value?.workspaceId in selected && fallback != null) {
            session.activateWorkspace(WorkspaceScope(operation.userId, fallback.id))
        }
    }

    suspend fun requestWorkspaceDelete(id: String) = manageWorkspaces { operation ->
        val row = dao.workspacesForUser(operation.userId).firstOrNull { it.id == id } ?: error("Atualize a lista")
        val activeRemaining = dao.workspacesForUser(operation.userId).count { it.archivedAt == null && it.id != row.id }
        check(row.archivedAt != null || activeRemaining >= 1) { "Você precisa manter pelo menos um workspace ativo." }
        api.workspaceDeleteCode(id).message
    }

    suspend fun confirmWorkspaceDelete(id: String, code: String) = manageWorkspaces { operation ->
        api.workspaceDeleteConfirm(id, com.financeapp.mobile.data.remote.SecurityCodeRequest(code))
        val fallback = dao.workspacesForUser(operation.userId).firstOrNull { it.archivedAt == null && it.id != id }
        purgeWorkspace(operation.userId, id)
        if (session.workspaceScope.value == WorkspaceScope(operation.userId, id) && fallback != null) {
            session.activateWorkspace(WorkspaceScope(operation.userId, fallback.id))
        }
    }

    suspend fun refreshWorkspaces() = manageWorkspaces { operation ->
        if (operation.userId == 0) {
            dao.upsertWorkspaces(listOf(WorkspaceEntity(0,"default-0","Visitante","personal",true,null)))
            return@manageWorkspaces
        }
        val rows = api.workspaces()
        require(rows.all { it.userId == operation.userId })
        reconcileWorkspaces(rows)
        val current = session.requireWorkspace()
        check(current.userId == operation.userId)
        if (rows.none { it.id == current.workspaceId && it.archivedAt == null }) {
            val localOnly = dao.workspacesForUser(operation.userId).firstOrNull {
                it.id == current.workspaceId && !it.isDefault && it.archivedAt == null
            }
            if (localOnly == null) {
                val fallback = rows.first { it.archivedAt == null }
                session.activateWorkspace(WorkspaceScope(operation.userId, fallback.id))
            }
        }
    }

    suspend fun selectWorkspace(id: String) {
        // Running requests already captured their original scope. A local switch
        // need not wait for their network timeout; it only changes future requests.
        val operation = session.captureOperation()
        val row = dao.workspacesForUser(operation.userId).firstOrNull { it.id == id }
            ?: error("Conecte-se à internet para carregar este workspace")
        check(row.archivedAt == null) { "Restaure o workspace antes de abri-lo" }
        session.activateWorkspace(WorkspaceScope(operation.userId, row.id))
    }

    suspend fun createWorkspace(name: String, kind: String, clientId: String) = manageWorkspaces { operation ->
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "Informe o nome do workspace" }
        val local = WorkspaceEntity(operation.userId, clientId, cleanName, kind, false, null)
        dao.upsertWorkspaces(listOf(local))
        session.activateWorkspace(WorkspaceScope(operation.userId, clientId))
        // Offline-first: a criação remota é feita pelo worker quando houver rede.
        runCatching { scheduleOfflineSync() }
    }

    suspend fun editWorkspace(id: String, name: String, kind: String) = manageWorkspaces {
        cacheWorkspace(api.editWorkspace(id, com.financeapp.mobile.data.remote.WorkspaceEditRequest(name.trim(), kind)))
    }

    suspend fun archiveWorkspace(id: String) {
        // Sem excecao para default-<userId>: antes de arquivar, tenta enviar
        // qualquer fila offline e depois aplica apenas a regra de manter 1 ativo.
        val captured = session.captureOperation()
        if (captured.userId > 0) runCatching { syncPendingNow() }
        manageWorkspaces { operation ->
            val row = dao.workspacesForUser(operation.userId).firstOrNull { it.id == id } ?: error("Workspace não encontrado")
            val activeRows = dao.workspacesForUser(operation.userId).filter { it.archivedAt == null }
            check(row.archivedAt != null || activeRows.count { it.id != id } >= 1) { "Você precisa manter pelo menos um workspace ativo." }
            check(dao.pendingSyncCountNow(operation.userId, id) == 0) {
                "Não foi possível sincronizar todas as alterações deste workspace antes de arquivar. Conecte-se e tente novamente."
            }
            val fallback = dao.workspacesForUser(operation.userId).firstOrNull { it.archivedAt == null && it.id != id }
                ?: error("Você precisa manter pelo menos um workspace ativo.")
            cacheWorkspace(api.archiveWorkspace(id))
            if (session.requireWorkspace() == WorkspaceScope(operation.userId, id)) {
                session.activateWorkspace(WorkspaceScope(operation.userId, fallback.id))
            }
        }
    }

    suspend fun restoreWorkspace(id: String) = manageWorkspaces {
        cacheWorkspace(api.restoreWorkspace(id))
    }

    suspend fun createManualAccount(name: String, agency: String? = null, accountNumber: String? = null) = withWorkspace {
        val operation = WorkspaceOperation.current()
        require(name.isNotBlank()) { "Informe o nome da conta" }
        val cleanName = name.trim()
        val cleanAgency = agency?.trim()?.takeIf { it.isNotBlank() }
        val cleanNumber = accountNumber?.trim()?.takeIf { it.isNotBlank() }
        val accountDetails = listOfNotNull(
            cleanAgency?.let { "Ag. $it" },
            cleanNumber?.let { "Conta $it" }
        ).joinToString(" • ").ifBlank { null }
        val local = AccountEntity(nextLocalId(), cleanName, null, "Conta manual", accountDetails, null,
            syncState = if (operation.userId == 0) "SYNCED" else "PENDING_CREATE")
        dao.upsertAccount(local)
        if (operation.userId > 0) runCatching { scheduleOfflineSync() }
    }

    suspend fun createGuestAccount(name: String) = withWorkspace {
        check(WorkspaceOperation.current().userId == 0)
        require(name.isNotBlank()) { "Informe o nome da conta" }
        dao.upsertAccount(AccountEntity(nextLocalId(),name.trim(),null,"Conta manual",null,null))
    }

    suspend fun deleteGuestAccount(id: Int) = withWorkspace {
        check(WorkspaceOperation.current().userId == 0)
        database.withTransaction {
            dao.observeTransactions(0,"default-0").first().filter { it.accountId == id }.forEach {
                dao.upsertTransaction(it.copy(accountId = null))
            }
            dao.deleteGuestAccount(id)
        }
    }

    suspend fun transferGuestData() = manageWorkspaces { operation ->
        check(operation.userId > 0) { "Entre em uma conta para transferir" }
        val file = android.util.AtomicFile(guestTransferFile)
        val envelope = if (guestTransferFrozen()) {
            file.openRead().bufferedReader().use { gson.fromJson(it, com.google.gson.JsonObject::class.java) }
        } else {
            val snapshot = database.withTransaction {
                com.google.gson.JsonObject().apply {
                    addProperty("transferId",UUID.randomUUID().toString())
                    add("accounts",gson.toJsonTree(dao.observeAccounts(0,"default-0").first()))
                    add("categories",gson.toJsonTree(dao.observeCategories(0,"default-0").first()))
                    add("cards",gson.toJsonTree(dao.observeCards(0,"default-0").first()))
                    add("goals",gson.toJsonTree(dao.observeGoals(0,"default-0").first()))
                    add("contributions",gson.toJsonTree(dao.observeGoalContributions(0,"default-0").first()))
                    add("budgets",gson.toJsonTree(dao.observeBudgets(0,"default-0").first()))
                    add("transactions",gson.toJsonTree(dao.observeTransactions(0,"default-0").first()))
                }
            }
            val saved = com.google.gson.JsonObject().apply {
                addProperty("ownerUserId",operation.userId)
                add("snapshot",snapshot)
            }
            val stream=file.startWrite()
            try { stream.write(gson.toJson(saved).toByteArray(Charsets.UTF_8));file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream);throw e }
            saved
        }
        check(envelope.get("ownerUserId").asInt == operation.userId) {
            "Esta transferência foi iniciada em outra conta. Entre nela para concluir."
        }
        val row = api.importGuest(envelope.getAsJsonObject("snapshot"))
        require(row.userId == operation.userId && row.id == envelope.getAsJsonObject("snapshot").get("transferId").asString)
        cacheWorkspace(row)
        // Download server-assigned IDs before retiring the local visitor copy.
        WorkspaceOperation.run(operation.copy(scope=WorkspaceScope(operation.userId,row.id))) { refresh() }
        purgeWorkspace(0,"default-0")
        file.delete()
        session.finishGuestTransfer()
        session.activateWorkspace(WorkspaceScope(operation.userId,row.id))
    }

    suspend fun <T> withWorkspace(
        requested: WorkspaceScope? = null,
        block: suspend () -> T
    ): T {
        val existing = WorkspaceOperation.currentOrNull()
        if (existing != null) {
            require(requested == null || requested == existing.scope)
            return block()
        }
        val operation = session.captureOperation(requested ?: com.financeapp.mobile.util.WorkspaceScreenContext.current())

        /*
         * LOCAL-FIRST: não segure o mutex global durante operações normais do
         * workspace. O worker de sincronização faz chamadas HTTP dentro de
         * withWorkspace(); antes, enquanto o servidor estava lento/offline,
         * esse mutex também bloqueava cadastros e edições locais na UI.
         *
         * WorkspaceOperation é propagado pelo contexto da coroutine e as
         * gravações do Room são transacionais. Assim, a UI pode persistir no
         * aparelho imediatamente enquanto o backup continua em outra coroutine.
         * O mutex continua reservado às operações estruturais de workspace
         * (manageWorkspaces), onde a serialização realmente é necessária.
         */
        return WorkspaceOperation.run(operation) {
            check(operation.userId != 0 || session.isGuest()) { "O modo visitante foi encerrado; tente novamente na conta atual." }
            val known = dao.workspacesForUser(operation.userId).firstOrNull { it.id == operation.workspaceId }
            if (known == null) {
                check(operation.workspaceId == "default-${operation.userId}") { "Workspace não está disponível neste aparelho" }
                dao.upsertWorkspaces(listOf(WorkspaceEntity(operation.userId, operation.workspaceId, if (operation.userId == 0) "Visitante" else "Pessoal", "personal", true, null)))
            } else {
                check(known.archivedAt == null) { "Workspace arquivado; a fila foi preservada" }
            }
            check(operation.userId != 0 || !guestTransferFrozen()) {
                "Há uma transferência em andamento. Entre na conta usada para concluí-la; seus dados continuam salvos."
            }
            block()
        }
    }


    val accounts: Flow<List<AccountEntity>> =
        session.workspaceScope.flatMapLatest { scope ->
            if (scope == null) flowOf(emptyList())
            else dao.observeAccounts(scope.userId, scope.workspaceId)
        }

    val transactions: Flow<List<TransactionEntity>> =
        session.workspaceScope.flatMapLatest { scope ->
            if (scope == null) flowOf(emptyList())
            else dao.observeTransactions(scope.userId, scope.workspaceId)
        }

    val categoriesFlow: Flow<List<CategoryDto>> =
        session.workspaceScope.flatMapLatest { scope ->
            if (scope == null) flowOf(emptyList())
            else dao.observeCategories(scope.userId, scope.workspaceId)
        }.map { items ->
            items.map {
                CategoryDto(
                    id = it.id,
                    name = it.name,
                    icon = it.icon
                )
            }
        }

    val cardsFlow: Flow<List<CreditCardDto>> =
        session.workspaceScope.flatMapLatest { scope ->
            if (scope == null) flowOf(emptyList())
            else dao.observeCards(scope.userId, scope.workspaceId)
        }.map { items ->
            items.map {
                CreditCardDto(
                    id = it.id,
                    bankName = it.bankName,
                    brand = it.brand,
                    lastFour = it.lastFour,
                    nickname = it.nickname,
                    creditLimit = it.creditLimit,
                    closingDay = it.closingDay,
                    dueDay = it.dueDay,
                    active = it.active
                )
            }
        }

    val goalsFlow: Flow<List<GoalDto>> =
        session.workspaceScope.flatMapLatest { scope ->
            if (scope == null) flowOf(emptyList())
            else dao.observeGoals(scope.userId, scope.workspaceId)
        }.map { items ->
            items.map {
                GoalDto(
                    id = it.id,
                    name = it.name,
                    targetAmount = it.targetAmount,
                    currentAmount = it.currentAmount,
                    targetDate = it.targetDate
                )
            }
        }

    val goalContributionsFlow:
        Flow<List<GoalContributionDto>> =
        session.workspaceScope.flatMapLatest { scope ->
            if (scope == null) flowOf(emptyList())
            else dao.observeGoalContributions(scope.userId, scope.workspaceId)
        }.map { items ->
            items.map {
                GoalContributionDto(
                    id = it.id,
                    goalId = it.goalId,
                    amount = it.amount,
                    clientKey = it.clientKey,
                    createdAt = it.createdAt
                )
            }
        }

    val budgetsFlow: Flow<List<BudgetDto>> =
        session.workspaceScope.flatMapLatest { scope ->
            if (scope == null) flowOf(emptyList())
            else dao.observeBudgets(scope.userId, scope.workspaceId)
        }.map { items ->
            items.map {
                BudgetDto(
                    id = it.serverId ?: it.categoryId,
                    categoryId = it.categoryId,
                    amount = it.amount
                )
            }
        }

    val pendingSyncCount: Flow<Int> =
        session.workspaceScope.flatMapLatest { scope ->
            if (scope == null) flowOf(0)
            else dao.observePendingSyncCount(scope.userId, scope.workspaceId)
        }

    val offlineSyncing = session.workspaceScope.flatMapLatest { scope ->
        if (scope == null) flowOf(false) else syncTracker.observe(scope)
    }

    suspend fun pendingSyncCountNow(): Int = withWorkspace {

        dao.pendingSyncCountNow()
    }

    /** Diagnostico somente-leitura da fila atual. Nao remove nenhuma pendencia. */
    suspend fun pendingSyncDiagnosticsNow(): List<String> = withWorkspace {
        val result = mutableListOf<String>()
        fun add(label: String, count: Int) { if (count > 0) result += "$label: $count" }
        add("Contas novas", dao.pendingAccounts().size)
        add("Cartoes novos", dao.pendingCards().size)
        add("Categorias novas", dao.pendingCategories().size)
        add("Metas novas", dao.pendingGoals().size)
        add("Aportes de metas", dao.pendingGoalContributions().size)
        add("Limites por categoria", dao.pendingBudgets().size)
        add("Transacoes novas", dao.pendingTransactions().size)
        add("Comprovantes pendentes", dao.pendingOperations().count { it.entityType == "ATTACHMENT" })
        dao.pendingOperations()
            .groupingBy { "${it.entityType}:${it.action}" }
            .eachCount()
            .toSortedMap()
            .forEach { (type, count) -> add(type, count) }
        result
    }

    /**
     * V3.25.6.3: limpeza explicita da fila legada durante o desenvolvimento.
     * Nao apaga os dados locais; remove operacoes antigas e normaliza flags pendentes.
     */
    suspend fun discardLegacyPendingSync(): Unit = withWorkspace {
        dao.clearPendingOperations()
        dao.acceptPendingAccountsAsLocal()
        dao.acceptPendingCategoriesAsLocal()
        dao.acceptPendingCardsAsLocal()
        dao.acceptPendingTransactionsAsLocal()
        dao.acceptPendingGoalsAsLocal()
        dao.acceptPendingGoalContributionsAsLocal()
        dao.acceptPendingBudgetsAsLocal()
    }

    suspend fun reconcilePendingAfterSync(): Unit = withWorkspace {
        /*
         * Recarrega o snapshot remoto sem apagar registros locais
         * ainda pendentes (refresh já protege PENDING_*).
         * Isso resolve dependências de IDs/estado que podem sobrar
         * depois da primeira passagem da fila.
         */
        refresh()
    }

    private fun categoryComparisonKey(
        value: String
    ): String {
        val normalized =
            Normalizer.normalize(
                value.trim(),
                Normalizer.Form.NFD
            )
                .replace(
                    Regex("\\p{M}+"),
                    ""
                )
                .replace(
                    Regex("\\s+"),
                    " "
                )

        return normalized.lowercase(
            Locale("pt", "BR")
        )
    }

    private suspend fun categoryNameExists(
        name: String,
        ignoreCategoryId: Int? = null
    ): Boolean = withWorkspace {
        val key =
            categoryComparisonKey(name)

        return@withWorkspace dao.allCategoriesNow()
            .any { category ->
                category.id !=
                    ignoreCategoryId &&
                    categoryComparisonKey(
                        category.name
                    ) == key
            }
    }

    private suspend fun validateReferences(accountId: Int? = null, categoryId: Int? = null, cardId: Int? = null) {
        require(accountId == null || dao.accountById(accountId) != null) { "Conta fora do workspace" }
        require(categoryId == null || dao.categoryById(categoryId) != null) { "Categoria fora do workspace" }
        require(cardId == null || dao.cardById(cardId) != null) { "Cartão fora do workspace" }
    }

    private fun nextLocalId(): Int {
        val raw =
            (System.nanoTime() % 2_000_000_000L)
                .toInt()

        return -(raw.coerceAtLeast(1))
    }

    fun scheduleOfflineSync(
        force: Boolean = false
    ) {
        if (!financialCloudSyncEnabled) return
        // Connectivity callbacks also run before login and after logout.
        val activeScope = session.workspaceScope.value ?: return
        if (activeScope.userId == 0) return
        val scope = WorkspaceOperation.currentOrNull()?.scope ?: activeScope
        if (scope != activeScope) return
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    NetworkType.CONNECTED
                )
                .build()

        val request =
            OneTimeWorkRequestBuilder<
                OfflineSyncWorker
                >()
                .setInputData(workDataOf("userId" to scope.userId, "workspaceId" to scope.workspaceId))
                .setConstraints(constraints)
                .build()

        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                OfflineSyncWorker
                    .UNIQUE_WORK_NAME + "-${scope.userId}-${scope.workspaceId}",
                if (force) {
                    ExistingWorkPolicy.REPLACE
                } else {
                    ExistingWorkPolicy.KEEP
                },
                request
            )
    }

    private suspend fun replaceLatestOperation(
        entityType: String,
        action: String,
        entityId: Int,
        payload: String?
    ): Unit = withWorkspace {
        dao.deleteOperationsForEntityAction(
            entityType,
            entityId,
            action
        )

        dao.enqueueOperation(
            PendingSyncOperationEntity(
                entityType = entityType,
                action = action,
                entityId = entityId,
                payload = payload
            )
        )

        scheduleOfflineSync()
    }

    suspend fun refresh(): Unit = withWorkspace {
        if (!financialCloudSyncEnabled) {
            // Nunca baixa snapshot financeiro do servidor. Room e a fonte oficial.
            dao.clearPendingOperations()
            return@withWorkspace
        }
        if (WorkspaceOperation.current().userId == 0) return@withWorkspace
        // OFFLINE-FIRST: refresh nunca tenta enviar a fila antes de ler o servidor.
        // A fila e enviada exclusivamente pelo WorkManager/sync explicito. Isso evita
        // que uma simples abertura/atualizacao de tela transforme uma alteracao local
        // em estado remoto parcial e depois a sobrescreva com um snapshot antigo.
        // Enquanto houver fila, os IDs correspondentes continuam protegidos abaixo.

        val workspaces = api.workspaces()
        reconcileWorkspaces(workspaces)
        check(workspaces.any { it.id == WorkspaceOperation.current().workspaceId && it.archivedAt == null })
        try { forecastState() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { /* Cache continua pendente; tela informa falha. */ }
        // Primeiro le contas e cartoes pelos endpoints dedicados. O backend usa
        // essas leituras para consolidar duplicatas e remapear as transacoes
        // antes de montar o snapshot completo.
        val remoteAccounts = api.accounts()
        val remoteCards = api.cards()
        val snapshot = api.syncSnapshot()

        // Exclusoes feitas no Web precisam invalidar tambem o cache offline do
        // Android. O marcador e persistido localmente para ser aplicado uma unica
        // vez; assim dados antigos/pending nao ressuscitam no proximo sync.
        val resetPrefs = context.getSharedPreferences("finance_remote_resets", Context.MODE_PRIVATE)
        val resetKey = "${WorkspaceOperation.current().userId}:${WorkspaceOperation.current().workspaceId}"
        val remoteResetAt = snapshot.resetAt
        val resetChanged = remoteResetAt != null && resetPrefs.getString(resetKey, null) != remoteResetAt
        if (resetChanged) {
            database.withTransaction {
                dao.clearTransactions()
                dao.clearAccounts()
                dao.clearCategories()
                dao.clearCards()
                dao.clearGoals()
                dao.clearBudgets()
                dao.clearGoalContributions()
                dao.clearPendingOperations()
            }
            resetPrefs.edit().putString(resetKey, remoteResetAt).apply()
        }

        val pending =
            dao.pendingOperations()

        val protectedAccounts = pending.filter { it.entityType == "ACCOUNT" }.map { it.entityId }.toSet()

        val protectedCategories =
            pending
                .filter {
                    it.entityType == "CATEGORY"
                }
                .map { it.entityId }
                .toSet()

        val protectedCards =
            pending
                .filter {
                    it.entityType == "CARD"
                }
                .map { it.entityId }
                .toSet()

        val protectedTransactions =
            pending
                .filter {
                    it.entityType == "TRANSACTION"
                }
                .map { it.entityId }
                .toSet()

        val protectedGoals =
            pending
                .filter {
                    it.entityType == "GOAL"
                }
                .map { it.entityId }
                .toSet()

        val protectedBudgets =
            pending
                .filter {
                    it.entityType == "BUDGET"
                }
                .map { it.entityId }
                .toSet()


        val remoteCategories =
            api.categories()
        val remoteBudgets =
            api.budgets()
        val remoteGoals =
            api.goals()

        val remoteGoalContributions =
            remoteGoals.flatMap { goal ->
                api.goalContributions(goal.id)
            }

        // Quando nao existe nenhuma alteracao local pendente, o snapshot remoto
        // e a fonte autoritativa. Isso e essencial depois de uma exclusao feita
        // pelo Web: registros que deixaram de existir no servidor tambem precisam
        // desaparecer do Room, em vez de permanecer como dados orfaos.
        //
        // Se houver qualquer PENDING_*, mantemos o comportamento conservador e
        // limpamos apenas linhas ja sincronizadas para nao perder trabalho offline.
        val hasLocalPending =
            dao.pendingAccounts().isNotEmpty() ||
            dao.pendingCards().isNotEmpty() ||
            dao.pendingCategories().isNotEmpty() ||
            dao.pendingGoals().isNotEmpty() ||
            dao.pendingGoalContributions().isNotEmpty() ||
            dao.pendingBudgets().isNotEmpty() ||
            dao.pendingTransactions().isNotEmpty() ||
            pending.isNotEmpty()

        database.withTransaction {
        if (!hasLocalPending) {
            dao.clearTransactions()
            dao.clearAccounts()
            dao.clearCategories()
            // Cartões recém-criados offline e confirmados pelo POST ficam como
            // LOCAL_CONFIRMED até aparecerem em uma leitura remota. Assim um
            // snapshot do servidor que ainda esteja defasado nunca apaga o
            // cartão que acabou de ser sincronizado pelo aparelho.
            dao.clearSyncedCards()
            dao.clearGoals()
            dao.clearBudgets()
            dao.clearGoalContributions()
        } else {
            dao.clearSyncedTransactions()
            dao.clearAccounts()
            dao.clearSyncedCategories()
            // Com alteracoes offline pendentes, cartoes continuam preservados.
            dao.clearSyncedBudgets()
            dao.clearSyncedGoals()
            dao.clearSyncedGoalContributions()
        }

        dao.upsertAccounts(
            remoteAccounts.filter { it.id !in protectedAccounts }.map {
                AccountEntity(
                    id = it.id,
                    institutionName =
                        it.institutionName,
                    institutionId =
                        it.institutionId,
                    accountName =
                        it.accountName,
                    maskedAccount =
                        it.maskedAccount,
                    externalAccountId =
                        it.externalAccountId,
                    connectionStatus =
                        it.connectionStatus,
                    currentBalance =
                        it.currentBalance,
                    balanceUpdatedAt =
                        it.balanceUpdatedAt
                )
            }
        )

        dao.upsertCards(
            remoteCards
                .filter {
                    it.id !in protectedCards
                }
                .map {
                CreditCardEntity(
                    id = it.id,
                    bankName = it.bankName,
                    brand = it.brand,
                    lastFour = it.lastFour,
                    nickname = it.nickname,
                    creditLimit = it.creditLimit,
                    closingDay = it.closingDay,
                    dueDay = it.dueDay,
                    active = it.active,
                    syncState = "SYNCED"
                )
            }
        )

        dao.upsertCategories(
            remoteCategories
                .filter {
                    it.id !in protectedCategories
                }
                .map {
                    CategoryEntity(
                        id = it.id,
                        name = it.name,
                        icon = it.icon,
                        syncState = "SYNCED"
                    )
                }
        )

        dao.upsertBudgets(
            remoteBudgets
                .filter {
                    it.categoryId !in
                        protectedBudgets
                }
                .map {
                    BudgetEntity(
                        categoryId =
                            it.categoryId,
                        serverId = it.id,
                        amount = it.amount,
                        syncState = "SYNCED"
                    )
                }
        )

        dao.upsertGoals(
            remoteGoals
                .filter {
                    it.id !in protectedGoals
                }
                .map {
                    GoalEntity(
                        id = it.id,
                        name = it.name,
                        targetAmount =
                            it.targetAmount,
                        currentAmount =
                            it.currentAmount,
                        targetDate =
                            it.targetDate,
                        syncState = "SYNCED"
                    )
                }
        )

        dao.upsertGoalContributions(
            remoteGoalContributions.map {
                GoalContributionEntity(
                    id = it.id,
                    goalId = it.goalId,
                    amount = it.amount,
                    createdAt = it.createdAt,
                    clientKey = it.clientKey,
                    syncState = "SYNCED"
                )
            }
        )

        dao.upsertTransactions(
            snapshot.transactions
                .filter {
                    it.id !in
                        protectedTransactions
                }
                .map {
                    TransactionEntity(
                        id = it.id,
                        accountId =
                            it.accountId,
                        categoryId =
                            it.categoryId,
                        cardId = it.cardId,
                        installmentGroup =
                            it.installmentGroup,
                        installmentNumber =
                            it.installmentNumber,
                        installmentTotal =
                            it.installmentTotal,
                        purchaseDate =
                            it.purchaseDate,
                        date = it.date,
                        description =
                            it.description,
                        // O servidor/Web usa income/expense e normalmente mantem o valor
                        // positivo. O Android usa credit/debit e o sinal do valor para
                        // calcular Entrada/Saida. Normalizamos ao receber o snapshot.
                        amount = androidTransactionAmount(it.amount, it.transactionType),
                        transactionType =
                            androidTransactionType(it.transactionType),
                        status = it.status,
                        source = it.source,
                        externalTransactionId =
                            it.externalTransactionId,
                        syncState = "SYNCED"
                    )
                }
        )
        }
    }

    private suspend fun migrateLegacySyncedLocalRows() {
        val scope = WorkspaceOperation.current()
        if (scope.userId == 0) return

        // Versoes antigas podiam ter linhas locais marcadas como SYNCED mesmo sem
        // terem sido persistidas no servidor. Enviamos somente essas linhas antigas
        // para um endpoint idempotente; PENDING_* continua no fluxo normal abaixo.
        val payload = FinanceBackupPayload(
            accounts = dao.backupAccounts().filter { it.syncState == "SYNCED" },
            transactions = dao.backupTransactions().filter { it.syncState == "SYNCED" },
            categories = dao.backupCategories().filter { it.syncState == "SYNCED" },
            cards = dao.backupCards().filter { it.syncState == "SYNCED" },
            goals = dao.backupGoals().filter { it.syncState == "SYNCED" },
            budgets = dao.backupBudgets().filter { it.syncState == "SYNCED" },
            contributions = dao.backupGoalContributions().filter { it.syncState == "SYNCED" }
        )
        val json = gson.toJsonTree(payload).asJsonObject
        val result = api.importLocalSnapshot(json)

        // O banco novo pode atribuir IDs diferentes aos registros que ja estavam
        // marcados como SYNCED no aparelho. Remapeamos as referencias das linhas
        // locais (inclusive PENDING_CREATE) antes de continuar a fila normal.
        val mappings = result.getAsJsonObject("id_mappings")
        fun remaps(name: String): List<Pair<Int, Int>> {
            val obj = mappings?.getAsJsonObject(name) ?: return emptyList()
            return obj.entrySet().mapNotNull { (localId, value) ->
                val oldId = localId.toIntOrNull() ?: return@mapNotNull null
                val newId = runCatching { value.asInt }.getOrNull() ?: return@mapNotNull null
                oldId to newId
            }
        }
        remaps("accounts").forEach { (oldId, newId) ->
            if (oldId != newId) dao.remapTransactionAccount(oldId, newId)
        }
        remaps("categories").forEach { (oldId, newId) ->
            if (oldId != newId) dao.remapTransactionCategory(oldId, newId)
        }
        remaps("cards").forEach { (oldId, newId) ->
            if (oldId != newId) dao.remapTransactionCard(oldId, newId)
        }
    }

    suspend fun syncPendingNow(): Unit = withWorkspace {
        if (!financialCloudSyncEnabled) {
            dao.clearPendingOperations()
            return@withWorkspace
        }
        if (WorkspaceOperation.current().userId == 0) return@withWorkspace

        // Limpa duplicatas antigas no servidor antes de importar/migrar IDs.
        // As chamadas sao idempotentes e preservam todas as transacoes.
        runCatching { api.accounts() }
        runCatching { api.cards() }

        // Primeiro reconcilia os dados legados que ja estavam marcados como
        // SYNCED no aparelho. Isso e essencial na primeira conexao com um banco
        // de nuvem novo, antes de enviar transacoes PENDING_CREATE.
        migrateLegacySyncedLocalRows()

        var remoteWorkspaces = api.workspaces()
        var remoteWorkspace = remoteWorkspaces.firstOrNull { it.id == WorkspaceOperation.current().workspaceId }
        if (remoteWorkspace == null) {
            val localWorkspace = dao.workspacesForUser(WorkspaceOperation.current().userId)
                .firstOrNull { it.id == WorkspaceOperation.current().workspaceId && it.archivedAt == null }
            if (localWorkspace != null && !localWorkspace.isDefault) {
                api.createWorkspace(com.financeapp.mobile.data.remote.WorkspaceCreateRequest(localWorkspace.name, localWorkspace.kind, localWorkspace.id))
                remoteWorkspaces = api.workspaces()
                remoteWorkspace = remoteWorkspaces.firstOrNull { it.id == WorkspaceOperation.current().workspaceId }
            }
        }
        reconcileWorkspaces(remoteWorkspaces)
        // The authoritative list confirms deletion: local rows/queue have been purged.
        if (remoteWorkspace == null) return@withWorkspace
        check(remoteWorkspace.archivedAt == null) { "Workspace arquivado; as alterações continuam salvas no aparelho" }
        // 1) Contas criadas offline. IDs locais são remapeados nas transações.
        // Antes de criar, tentamos reconciliar com uma conta equivalente que já
        // exista no servidor. Isso torna a operação idempotente caso o envio tenha
        // sido concluído no servidor, mas o app tenha fechado antes do remapeamento local.
        val remoteAccountsForReconcile = api.accounts().toMutableList()
        dao.pendingAccounts().forEach { local ->
            val existing = remoteAccountsForReconcile.firstOrNull { remote ->
                remote.institutionName.trim().equals(local.institutionName.trim(), ignoreCase = true) &&
                    (remote.accountName ?: "").trim().equals((local.accountName ?: "").trim(), ignoreCase = true) &&
                    (remote.maskedAccount ?: "").trim().equals((local.maskedAccount ?: "").trim(), ignoreCase = true)
            }
            val server = existing ?: api.createAccount(AccountCreateRequest(
                institutionName = local.institutionName, accountName = local.accountName, maskedAccount = local.maskedAccount
            )).also { remoteAccountsForReconcile.add(it) }
            dao.replacePendingAccount(local.id, AccountEntity(
                id = server.id, institutionName = server.institutionName, institutionId = server.institutionId,
                accountName = server.accountName, maskedAccount = server.maskedAccount, externalAccountId = server.externalAccountId,
                connectionStatus = server.connectionStatus, currentBalance = server.currentBalance, balanceUpdatedAt = server.balanceUpdatedAt,
                syncState = "SYNCED"))
        }

        // 2) Cartões criados offline. Antes de criar, reconciliamos com um
        // cartão equivalente que ja exista na nuvem. Isso evita duplicacao se o
        // POST anterior tiver sido concluido e o app fechado antes do remapeamento.
        val remoteCardsForReconcile = api.cards().toMutableList()
        dao.pendingCards().forEach { local ->
            val existing = remoteCardsForReconcile.firstOrNull { remote ->
                remote.bankName.trim().equals(local.bankName.trim(), ignoreCase = true) &&
                    remote.brand.trim().equals(local.brand.trim(), ignoreCase = true) &&
                    remote.lastFour.trim() == local.lastFour.trim() &&
                    (remote.nickname ?: "").trim().equals((local.nickname ?: "").trim(), ignoreCase = true)
            }
            val server = existing ?: api.createCard(
                CreditCardCreateRequest(
                    bankName = local.bankName,
                    brand = local.brand,
                    lastFour = local.lastFour,
                    nickname = local.nickname,
                    creditLimit = local.creditLimit,
                    closingDay = local.closingDay,
                    dueDay = local.dueDay
                )
            ).also { remoteCardsForReconcile.add(it) }

            dao.replacePendingCard(
                localId = local.id,
                serverCard = CreditCardEntity(
                    id = server.id,
                    bankName = server.bankName,
                    brand = server.brand,
                    lastFour = server.lastFour,
                    nickname = server.nickname,
                    creditLimit = server.creditLimit,
                    closingDay = server.closingDay,
                    dueDay = server.dueDay,
                    active = server.active,
                    // O servidor confirmou a criação, porém a listagem remota
                    // pode levar um instante para refletir o novo cartão.
                    // LOCAL_CONFIRMED impede que um refresh concorrente o apague.
                    syncState = "LOCAL_CONFIRMED"
                )
            )
        }

        // 2) Categorias novas.
        dao.pendingCategories().forEach {
                local ->

            val server =
                api.createCategory(
                    CategoryCreateRequest(
                        name = local.name,
                        icon = local.icon
                    )
                )

            dao.replacePendingCategory(
                localId = local.id,
                serverCategory =
                    CategoryEntity(
                        id = server.id,
                        name = server.name,
                        icon = server.icon,
                        syncState = "SYNCED"
                    )
            )
        }

        // 2) Metas novas.
        dao.pendingGoals().forEach {
                local ->

            val pendingContributionTotal =
                dao.pendingContributionTotalForGoal(
                    local.id
                )

            val server =
                api.createGoal(
                    GoalUpsertRequest(
                        name = local.name,
                        targetAmount =
                            local.targetAmount,
                        currentAmount =
                            (
                                local.currentAmount -
                                    pendingContributionTotal
                                ).coerceAtLeast(0.0),
                        targetDate =
                            local.targetDate
                    )
                )

            dao.replacePendingGoal(
                localId = local.id,
                serverGoal =
                    GoalEntity(
                        id = server.id,
                        name = server.name,
                        targetAmount =
                            server.targetAmount,
                        currentAmount =
                            server.currentAmount,
                        targetDate =
                            server.targetDate,
                        syncState = "SYNCED"
                    )
            )
        }

        // 3) Aportes de metas.
        dao.pendingGoalContributions().forEach {
                local ->

            if (local.goalId > 0) {
                val server =
                    api.addGoalContribution(
                        local.goalId,
                        GoalContributionCreateRequest(
                            amount = local.amount,
                            clientKey = local.clientKey
                        )
                    )

                dao.deleteGoalContributionById(
                    local.id
                )

                dao.upsertGoalContribution(
                    GoalContributionEntity(
                        id = server.id,
                        goalId = server.goalId,
                        amount = server.amount,
                        createdAt = server.createdAt,
                        clientKey = server.clientKey,
                        syncState = "SYNCED"
                    )
                )
            }
        }

        // 4) Limites, depois que IDs locais de categoria já foram remapeados.
        dao.pendingBudgets().forEach {
                local ->

            if (local.categoryId > 0) {
                val server =
                    api.setBudget(
                        local.categoryId,
                        BudgetUpsertRequest(
                            local.amount
                        )
                    )

                dao.upsertBudget(
                    BudgetEntity(
                        categoryId =
                            server.categoryId,
                        serverId =
                            server.id,
                        amount =
                            server.amount,
                        syncState =
                            "SYNCED"
                    )
                )
            }
        }

        // 5) Transações novas, já com IDs de categorias definitivos.
        dao.pendingTransactions().forEach {
                local ->

            val server =
                api.createTransaction(
                    TransactionCreateRequest(
                        accountId =
                            local.accountId,
                        categoryId =
                            local.categoryId,
                        cardId = local.cardId,
                        installmentGroup =
                            local.installmentGroup,
                        installmentNumber =
                            local.installmentNumber,
                        installmentTotal =
                            local.installmentTotal,
                        purchaseDate =
                            local.purchaseDate,
                        externalTransactionId =
                            local.externalTransactionId,
                        date = local.date,
                        description =
                            local.description,
                        amount = local.amount,
                        transactionType =
                            local.transactionType,
                        status = local.status,
                        source = local.source
                    )
                )

            dao.replacePendingTransaction(
                localId = local.id,
                serverTransaction =
                    TransactionEntity(
                        id = server.id,
                        accountId =
                            server.accountId,
                        categoryId =
                            server.categoryId,
                        cardId = server.cardId,
                        installmentGroup =
                            server.installmentGroup,
                        installmentNumber =
                            server.installmentNumber,
                        installmentTotal =
                            server.installmentTotal,
                        purchaseDate =
                            server.purchaseDate,
                        date = server.date,
                        description =
                            server.description,
                        amount =
                            server.amount,
                        transactionType =
                            server.transactionType,
                        status =
                            server.status,
                        source =
                            server.source,
                        externalTransactionId =
                            server.externalTransactionId,
                        syncState = "SYNCED"
                    )
            )
        }

        // 6) Atualizações e exclusões feitas offline.
        dao.pendingOperations().forEach {
                operation ->

            try {
                when (
                    "${operation.entityType}:${operation.action}"
                ) {
                    "CATEGORY:UPDATE" -> {
                        val payload =
                            gson.fromJson(
                                operation.payload,
                                CategoryUpdatePayload::class.java
                            )

                        api.updateCategory(
                            operation.entityId,
                            CategoryUpdateRequest(
                                name = payload.name,
                                icon = payload.icon
                            )
                        )
                    }

                    "CATEGORY:DELETE" -> {
                        api.deleteCategory(
                            operation.entityId
                        )
                    }

                    "ACCOUNT:UPDATE" -> {
                        val payload = gson.fromJson(operation.payload, AccountUpdatePayload::class.java)
                        api.updateAccount(operation.entityId, AccountUpdateRequest(payload.institutionName, payload.accountName, payload.maskedAccount))
                    }

                    "CARD:UPDATE" -> {
                        val payload = gson.fromJson(operation.payload, CardUpdatePayload::class.java)
                        api.updateCard(operation.entityId, CreditCardUpdateRequest(payload.bankName, payload.brand, payload.lastFour,
                            payload.nickname, payload.creditLimit, payload.closingDay, payload.dueDay))
                    }

                    "CARD:DELETE" -> {
                        api.deleteCard(
                            operation.entityId
                        )
                    }

                    "TRANSACTION:UPDATE" -> {
                        val payload =
                            gson.fromJson(
                                operation.payload,
                                TransactionUpdatePayload::class.java
                            )

                        api.updateTransaction(
                            operation.entityId,
                            TransactionUpdateRequest(
                                accountId =
                                    payload.accountId,
                                categoryId =
                                    payload.categoryId,
                                date =
                                    payload.date,
                                description =
                                    payload.description,
                                amount =
                                    payload.amount,
                                transactionType =
                                    payload.transactionType,
                                status =
                                    payload.status
                            )
                        )
                    }

                    "TRANSACTION:CATEGORY_UPDATE" -> {
                        val payload =
                            gson.fromJson(
                                operation.payload,
                                TransactionCategoryUpdatePayload::class.java
                            )

                        api.updateTransaction(
                            operation.entityId,
                            TransactionUpdateRequest(
                                categoryId =
                                    payload.categoryId
                            )
                        )
                    }

                    "TRANSACTION:DELETE" -> {
                        api.deleteTransaction(
                            operation.entityId
                        )
                    }

                    "ATTACHMENT:CREATE" -> {
                        // A transacao pode ter sido criada offline. O replacePendingTransaction
                        // remapeia entityId para o ID definitivo antes de chegarmos aqui.
                        if (operation.entityId <= 0) error("Transacao ainda nao sincronizada")
                        val payload = gson.fromJson(operation.payload, PendingAttachmentCreatePayload::class.java)
                        val file = pendingAttachmentFile(payload.storedName)
                        require(file.exists()) { "Arquivo local do comprovante nao encontrado" }
                        val bytes = file.readBytes()
                        require(bytes.isNotEmpty()) { "Arquivo local do comprovante esta vazio" }
                        val body = bytes.toRequestBody(payload.contentType.toMediaTypeOrNull())
                        val part = MultipartBody.Part.createFormData("file", payload.fileName, body)
                        api.uploadTransactionAttachment(operation.entityId, part)
                        file.delete()
                    }

                    "ATTACHMENT:DELETE" -> {
                        val payload = gson.fromJson(operation.payload, PendingAttachmentDeletePayload::class.java)
                        api.deleteTransactionAttachment(operation.entityId, payload.attachmentId)
                    }

                    "GOAL:UPDATE" -> {
                        val payload =
                            gson.fromJson(
                                operation.payload,
                                GoalUpdatePayload::class.java
                            )

                        api.updateGoal(
                            operation.entityId,
                            GoalUpsertRequest(
                                name =
                                    payload.name,
                                targetAmount =
                                    payload.targetAmount,
                                currentAmount =
                                    payload.currentAmount,
                                targetDate =
                                    payload.targetDate
                            )
                        )
                    }

                    "GOAL:DELETE" -> {
                        api.deleteGoal(
                            operation.entityId
                        )
                    }

                    "GOAL_CONTRIBUTION:DELETE" -> {
                        val payload =
                            gson.fromJson(
                                operation.payload,
                                GoalContributionDeletePayload::class.java
                            )

                        api.deleteGoalContribution(
                            payload.goalId,
                            operation.entityId
                        )
                    }

                    "BUDGET:DELETE" -> {
                        api.deleteBudget(
                            operation.entityId
                        )
                    }
                }

                dao.deleteOperation(
                    operation.id
                )
            } catch (e: HttpException) {
                // Exclusão repetida é idempotente: se já não existe,
                // consideramos a fila concluída.
                if (
                    operation.action == "DELETE" &&
                    e.code() == 404
                ) {
                    dao.deleteOperation(
                        operation.id
                    )
                } else {
                    /*
                     * Uma alteração com erro não pode impedir que
                     * as demais alterações da fila sejam enviadas.
                     * Mantemos somente este item pendente.
                     */
                    Log.e(
                        "OfflineSync",
                        "Falha ${
                            operation.entityType
                        }:${
                            operation.action
                        } id=${
                            operation.entityId
                        } HTTP ${
                            e.code()
                        }",
                        e
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "OfflineSync",
                    "Falha ${
                        operation.entityType
                    }:${
                        operation.action
                    } id=${
                        operation.entityId
                    }",
                    e
                )
            }
        }
        forecastState()

    }

    suspend fun syncStatus() = withWorkspace {

        api.syncStatus()
    }

    fun hasCachedProfilePhoto(): Boolean = session.hasCachedProfilePhoto()
    fun cachedProfilePhoto(): String? = session.cachedProfilePhoto()
    fun cacheProfilePhoto(value: String?) = session.cacheProfilePhoto(value)

    suspend fun currentUserProfile() = withWorkspace { api.me() }

    suspend fun updatePersonalProfile(name: String, cpf: String, birthDate: String?, sex: String?) = withWorkspace {
        api.updatePersonalProfile(com.financeapp.mobile.data.remote.PersonalProfileUpdateRequest(name, cpf, birthDate, sex))
    }

    suspend fun updateProfilePhoto(profilePhoto: String?) = withWorkspace {
        api.updateProfilePhoto(com.financeapp.mobile.data.remote.ProfilePhotoUpdateRequest(profilePhoto))
    }

    suspend fun useGoogleProfilePhoto() = withWorkspace { api.useGoogleProfilePhoto() }

    suspend fun serverIsAvailable():
        Boolean = withWorkspace {

        runCatching {
            api.health()
            true
        }.getOrDefault(false)
    }

    suspend fun belvoWidgetUrl(
        name: String,
        cpf: String
    ): String = withWorkspace {
        val response =
            api.belvoWidgetToken(
                BelvoWidgetTokenRequest(
                    cpf =
                        cpf.filter(
                            Char::isDigit
                        ),
                    name = name.trim()
                )
            )
        return@withWorkspace response.hostedWidgetUrl
            ?: error(
                "Não foi possível iniciar a conexão bancária."
            )
    }

    suspend fun registerBelvoLink(
        linkId: String,
        institutionName: String?
    ): Int = withWorkspace {

        api.registerBelvoLink(
            RegisterBelvoLinkRequest(
                linkId = linkId,
                institutionName = institutionName
            )
        ).id
    }

    suspend fun firstConnectionId(): Int? = withWorkspace {
        if (WorkspaceOperation.current().userId == 0) return@withWorkspace null

        api.connections()
            .firstOrNull()
            ?.id
    }

    suspend fun sync(
        connectionId: Int
    ): Unit = withWorkspace {
        api.syncConnection(
            connectionId
        )
    }

    suspend fun createManual(
        accountId: Int?,
        categoryId: Int?,
        description: String,
        amount: Double,
        type: String,
        date: String,
        cardId: Int? = null,
        installmentTotal: Int = 1,
        firstChargeDate: String? = null
    ): Int = withWorkspace {
        validateReferences(accountId, categoryId, cardId)
        val count = installmentTotal.coerceIn(1, 360)
        val firstDate = firstChargeDate ?: date
        val group =
            if (count > 1) {
                "installment:${UUID.randomUUID()}"
            } else {
                null
            }

        val totalCents =
            kotlin.math.round(
                kotlin.math.abs(amount) * 100.0
            ).toLong()

        val baseCents = totalCents / count
        val remainder = (totalCents % count).toInt()
        val sign = if (type == "debit") -1.0 else 1.0
        var firstCreatedId = 0

        database.withTransaction {
        repeat(count) { index ->
            val cents =
                baseCents +
                    if (index < remainder) 1 else 0

            // Aceita tanto timestamp completo quanto data simples (yyyy-MM-dd).
            // Arquivos importados e respostas da API podem trazer apenas o dia.
            val firstDateTime =
                runCatching { java.time.OffsetDateTime.parse(firstDate) }.getOrElse {
                    val localDate = runCatching { java.time.LocalDate.parse(firstDate.take(10)) }
                        .getOrElse { error("Data invalida: $firstDate") }
                    localDate.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
                }
            val chargeDate = firstDateTime
                .plusMonths(index.toLong())
                .toString()

            val localId = nextLocalId()
            if (index == 0) {
                firstCreatedId = localId
            }

            dao.upsertTransaction(
                TransactionEntity(
                    id = localId,
                    accountId = accountId,
                    categoryId = categoryId,
                    cardId = cardId,
                    installmentGroup = group,
                    installmentNumber =
                        if (count > 1) index + 1 else null,
                    installmentTotal =
                        if (count > 1) count else null,
                    purchaseDate =
                        if (count > 1) date else null,
                    date = chargeDate,
                    description = description,
                    amount =
                        sign * cents.toDouble() / 100.0,
                    transactionType = type,
                    status = "posted",
                    source = if (cardId != null) "card_purchase" else "manual",
                    externalTransactionId =
                        "manual:${UUID.randomUUID()}",
                    syncState = "PENDING_CREATE"
                )
            )
        }

        }
        runCatching { scheduleOfflineSync() }
        return@withWorkspace firstCreatedId
    }

    suspend fun createCardPayment(
        accountId: Int,
        cardId: Int,
        invoiceEnd: String,
        amount: Double,
        date: String
    ): Int = withWorkspace {
        require(amount > 0.0) { "Informe um valor de pagamento maior que zero." }
        validateReferences(accountId, null, cardId)
        val card = dao.cardById(cardId) ?: error("Cartão não encontrado")
        val account = dao.accountById(accountId) ?: error("Conta não encontrada")
        val accountLabel = if (account.accountName.isNullOrBlank() || account.accountName.equals("Conta manual", ignoreCase = true)) {
            account.institutionName
        } else {
            "${account.institutionName} • ${account.accountName}"
        }
        val cardLabel = listOfNotNull(
            card.bankName.takeIf { it.isNotBlank() },
            card.brand.takeIf { it.isNotBlank() },
            card.lastFour.takeIf { it.isNotBlank() }?.let { "final $it" }
        ).joinToString(" • ")
        val paymentDescription = "Pagamento de fatura • $cardLabel • $accountLabel"
        val localId = nextLocalId()
        dao.upsertTransaction(
            TransactionEntity(
                id = localId,
                accountId = accountId,
                categoryId = null,
                cardId = cardId,
                installmentGroup = null,
                installmentNumber = null,
                installmentTotal = null,
                purchaseDate = invoiceEnd + "T00:00:00Z",
                date = date,
                description = paymentDescription,
                amount = -kotlin.math.abs(amount),
                transactionType = "debit",
                status = "posted",
                source = "card_payment",
                externalTransactionId = "card-payment:${cardId}:${invoiceEnd}:${UUID.randomUUID()}",
                syncState = "PENDING_CREATE"
            )
        )
        runCatching { scheduleOfflineSync() }
        return@withWorkspace localId
    }

    suspend fun createCard(
        bankName: String,
        brand: String,
        lastFour: String,
        nickname: String?,
        creditLimit: Double?,
        closingDay: Int?,
        dueDay: Int?
    ): CreditCardDto = withWorkspace {
        val digits =
            lastFour.filter(Char::isDigit)

        require(digits.length == 4) {
            "Informe os 4 últimos dígitos."
        }

        val local = CreditCardEntity(
            id = nextLocalId(),
            bankName = bankName.trim(),
            brand = brand.trim(),
            lastFour = digits,
            nickname =
                nickname?.trim()
                    ?.takeIf { it.isNotBlank() },
            creditLimit = creditLimit,
            closingDay = closingDay,
            dueDay = dueDay,
            active = true,
            syncState = "PENDING_CREATE"
        )

        dao.upsertCard(local)
        // Persistencia local ja terminou; falha ao agendar sync nao desfaz o dado.
        runCatching { scheduleOfflineSync() }

        return@withWorkspace CreditCardDto(
            id = local.id,
            bankName = local.bankName,
            brand = local.brand,
            lastFour = local.lastFour,
            nickname = local.nickname,
            creditLimit = local.creditLimit,
            closingDay = local.closingDay,
            dueDay = local.dueDay,
            active = local.active
        )
    }

    suspend fun updateCardDetails(
        cardId: Int,
        bankName: String,
        brand: String,
        lastFour: String,
        nickname: String?,
        creditLimit: Double?,
        closingDay: Int?,
        dueDay: Int?
    ): Unit = withWorkspace {
        val digits =
            lastFour.filter(Char::isDigit)
        require(digits.length == 4) {
            "Informe os 4 últimos dígitos."
        }

        val local = dao.cardById(cardId) ?: error("Cartão não encontrado")
        val updated = local.copy(bankName=bankName.trim(), brand=brand.trim(), lastFour=digits,
            nickname=nickname?.trim()?.takeIf { it.isNotBlank() }, creditLimit=creditLimit, closingDay=closingDay, dueDay=dueDay)
        dao.upsertCard(updated)
        if (WorkspaceOperation.current().userId > 0 && cardId > 0) {
            replaceLatestOperation("CARD", "UPDATE", cardId, gson.toJson(CardUpdatePayload(
                updated.bankName, updated.brand, updated.lastFour, updated.nickname, updated.creditLimit, updated.closingDay, updated.dueDay)))
        } else if (WorkspaceOperation.current().userId > 0) scheduleOfflineSync()
    }

    suspend fun updateAccountDetails(
        accountId: Int,
        institutionName: String,
        accountName: String?,
        maskedAccount: String?
    ): Unit = withWorkspace {
        val current =
            dao.accountById(accountId)
                ?: error("Conta não encontrada.")

        val updated = current.copy(
            institutionName = institutionName.trim(),
            accountName = accountName?.trim()?.takeIf { it.isNotBlank() },
            maskedAccount = maskedAccount?.trim()?.takeIf { it.isNotBlank() })
        dao.upsertAccount(updated)
        if (WorkspaceOperation.current().userId > 0 && accountId > 0) {
            replaceLatestOperation("ACCOUNT", "UPDATE", accountId, gson.toJson(AccountUpdatePayload(
                updated.institutionName, updated.accountName, updated.maskedAccount)))
        } else if (WorkspaceOperation.current().userId > 0) scheduleOfflineSync()
    }

    suspend fun deleteCard(
        cardId: Int
    ): Unit = withWorkspace {
        // Excluir o cartao preserva as compras: remove somente o vinculo.
        dao.observeTransactions(WorkspaceOperation.current().userId, WorkspaceOperation.current().workspaceId)
            .first().filter { it.cardId == cardId }.forEach {
                dao.upsertTransaction(it.copy(cardId = null, source = if (it.source == "manual") "card_purchase" else it.source))
            }
        if (cardId < 0) {
            dao.deleteCardById(cardId)
            dao.deleteOperationsForEntity(
                "CARD",
                cardId
            )
        } else {
            dao.archiveCard(cardId)

            replaceLatestOperation(
                entityType = "CARD",
                action = "DELETE",
                entityId = cardId,
                payload = null
            )
        }
    }

    suspend fun categories():
        List<CategoryDto> = withWorkspace {

        runCatching {
            api.categories()
        }.getOrElse {
            emptyList()
        }
    }

    suspend fun budgets():
        List<BudgetDto> = withWorkspace {

        runCatching {
            api.budgets()
        }.getOrElse {
            emptyList()
        }
    }

    suspend fun goals():
        List<GoalDto> = withWorkspace {

        runCatching {
            api.goals()
        }.getOrElse {
            emptyList()
        }
    }

    suspend fun createGoal(
        name: String,
        targetAmount: Double,
        currentAmount: Double,
        targetDate: String?
    ): GoalDto = withWorkspace {
        val local =
            GoalEntity(
                id = nextLocalId(),
                name = name.trim(),
                targetAmount =
                    targetAmount,
                currentAmount =
                    currentAmount,
                targetDate =
                    targetDate,
                syncState =
                    "PENDING_CREATE"
            )

        dao.upsertGoal(local)
        runCatching { scheduleOfflineSync() }

        return@withWorkspace GoalDto(
            id = local.id,
            name = local.name,
            targetAmount =
                local.targetAmount,
            currentAmount =
                local.currentAmount,
            targetDate =
                local.targetDate
        )
    }

    suspend fun updateGoal(
        goalId: Int,
        name: String,
        targetAmount: Double,
        currentAmount: Double,
        targetDate: String?
    ): GoalDto = withWorkspace {
        val current =
            dao.goalById(goalId)
                ?: GoalEntity(
                    id = goalId,
                    name = name,
                    targetAmount =
                        targetAmount,
                    currentAmount =
                        currentAmount,
                    targetDate =
                        targetDate
                )

        val updated =
            current.copy(
                name = name.trim(),
                targetAmount =
                    targetAmount,
                currentAmount =
                    currentAmount,
                targetDate =
                    targetDate,
                syncState =
                    if (goalId > 0) {
                        "PENDING_UPDATE"
                    } else {
                        current.syncState
                    }
            )

        dao.upsertGoal(updated)

        if (goalId > 0) {
            replaceLatestOperation(
                entityType = "GOAL",
                action = "UPDATE",
                entityId = goalId,
                payload = gson.toJson(
                    GoalUpdatePayload(
                        name =
                            updated.name,
                        targetAmount =
                            updated.targetAmount,
                        currentAmount =
                            updated.currentAmount,
                        targetDate =
                            updated.targetDate
                    )
                )
            )
        } else {
            scheduleOfflineSync()
        }

        return@withWorkspace GoalDto(
            id = updated.id,
            name = updated.name,
            targetAmount =
                updated.targetAmount,
            currentAmount =
                updated.currentAmount,
            targetDate =
                updated.targetDate
        )
    }

    suspend fun addGoalContribution(
        goalId: Int,
        amount: Double
    ): Unit = withWorkspace {
        require(amount > 0.0) {
            "Informe um valor maior que zero."
        }

        val goal =
            dao.goalById(goalId)
                ?: error("Meta não encontrada.")

        val now =
            java.time.OffsetDateTime.now()
                .toString()

        val localContribution =
            GoalContributionEntity(
                id = nextLocalId(),
                goalId = goalId,
                amount = amount,
                createdAt = now,
                clientKey =
                    "goal:${UUID.randomUUID()}",
                syncState = "PENDING_CREATE"
            )

        dao.upsertGoalContribution(
            localContribution
        )

        dao.upsertGoal(
            goal.copy(
                currentAmount =
                    goal.currentAmount + amount
            )
        )

        scheduleOfflineSync()
    }

    suspend fun deleteGoalContribution(
        contributionId: Int
    ): Unit = withWorkspace {
        val contribution =
            dao.goalContributionById(
                contributionId
            )
                ?: return@withWorkspace

        val goal =
            dao.goalById(
                contribution.goalId
            )

        dao.deleteGoalContributionById(
            contributionId
        )

        if (goal != null) {
            dao.upsertGoal(
                goal.copy(
                    currentAmount =
                        (
                            goal.currentAmount -
                                contribution.amount
                            ).coerceAtLeast(0.0)
                )
            )
        }

        if (contributionId < 0) {
            /*
             * O aporte ainda não chegou ao servidor.
             * Removemos apenas o registro local pendente.
             */
            dao.deleteOperationsForEntity(
                "GOAL_CONTRIBUTION",
                contributionId
            )
            scheduleOfflineSync()
        } else {
            replaceLatestOperation(
                entityType =
                    "GOAL_CONTRIBUTION",
                action = "DELETE",
                entityId =
                    contributionId,
                payload =
                    gson.toJson(
                        GoalContributionDeletePayload(
                            goalId =
                                contribution.goalId
                        )
                    )
            )
        }
    }

    suspend fun deleteGoal(
        goalId: Int
    ): Unit = withWorkspace {
        dao.deleteGoalContributionsByGoal(
            goalId
        )

        dao.deleteGoalById(
            goalId
        )

        if (goalId < 0) {
            dao.deleteOperationsForEntity(
                "GOAL",
                goalId
            )
        } else {
            dao.deleteOperationsForEntityAction(
                "GOAL",
                goalId,
                "UPDATE"
            )

            replaceLatestOperation(
                entityType = "GOAL",
                action = "DELETE",
                entityId = goalId,
                payload = null
            )
        }
    }

    suspend fun setBudget(
        categoryId: Int,
        amount: Double
    ): BudgetDto = withWorkspace {
        validateReferences(categoryId = categoryId)
        val current =
            dao.budgetByCategoryId(
                categoryId
            )

        val local =
            BudgetEntity(
                categoryId =
                    categoryId,
                serverId =
                    current?.serverId,
                amount = amount,
                syncState =
                    "PENDING_SET"
            )

        dao.upsertBudget(local)
        runCatching { scheduleOfflineSync() }

        return@withWorkspace BudgetDto(
            id =
                local.serverId
                    ?: categoryId,
            categoryId =
                categoryId,
            amount = amount
        )
    }

    suspend fun deleteBudget(
        categoryId: Int
    ): Unit = withWorkspace {
        dao.deleteBudgetByCategoryId(
            categoryId
        )

        if (categoryId > 0) {
            replaceLatestOperation(
                entityType = "BUDGET",
                action = "DELETE",
                entityId =
                    categoryId,
                payload = null
            )
        }
    }

    suspend fun createCategory(
        name: String,
        icon: String? = null
    ): CategoryDto = withWorkspace {
        val trimmed =
            name.trim()

        require(
            trimmed.isNotBlank()
        ) {
            "Informe o nome da categoria."
        }

        require(
            !categoryNameExists(trimmed)
        ) {
            "Essa categoria já existe."
        }

        val local =
            CategoryEntity(
                id = nextLocalId(),
                name = trimmed,
                icon = icon,
                syncState =
                    "PENDING_CREATE"
            )

        dao.upsertCategory(
            local
        )

        runCatching { scheduleOfflineSync() }

        return@withWorkspace CategoryDto(
            id = local.id,
            name = local.name,
            icon = local.icon
        )
    }

    suspend fun updateCategory(
        categoryId: Int,
        name: String,
        icon: String? = null
    ): CategoryDto = withWorkspace {
        val trimmed =
            name.trim()

        require(
            trimmed.isNotBlank()
        ) {
            "Informe o nome da categoria."
        }

        require(
            !categoryNameExists(
                name = trimmed,
                ignoreCategoryId =
                    categoryId
            )
        ) {
            "Essa categoria já existe."
        }

        val current =
            dao.categoryById(
                categoryId
            )
                ?: CategoryEntity(
                    id = categoryId,
                    name = trimmed,
                    icon = null
                )

        val updated =
            current.copy(
                name = trimmed,
                icon = icon ?: current.icon,
                syncState =
                    if (categoryId > 0) {
                        "PENDING_UPDATE"
                    } else {
                        current.syncState
                    }
            )

        dao.upsertCategory(
            updated
        )

        if (categoryId > 0) {
            replaceLatestOperation(
                entityType =
                    "CATEGORY",
                action = "UPDATE",
                entityId =
                    categoryId,
                payload =
                    gson.toJson(
                        CategoryUpdatePayload(
                            name = trimmed,
                            icon = updated.icon
                        )
                    )
            )
        } else {
            scheduleOfflineSync()
        }

        return@withWorkspace CategoryDto(
            id = updated.id,
            name = updated.name,
            icon = updated.icon
        )
    }

    suspend fun deleteCategory(
        categoryId: Int
    ): Unit = withWorkspace {
        dao.clearCategoryFromTransactions(
            categoryId
        )

        dao.deleteBudgetByCategoryId(
            categoryId
        )

        dao.deleteCategoryById(
            categoryId
        )

        if (categoryId < 0) {
            dao.deleteOperationsForEntity(
                "CATEGORY",
                categoryId
            )
        } else {
            dao.deleteOperationsForEntityAction(
                "CATEGORY",
                categoryId,
                "UPDATE"
            )

            replaceLatestOperation(
                entityType =
                    "CATEGORY",
                action = "DELETE",
                entityId =
                    categoryId,
                payload = null
            )
        }
    }

    suspend fun updateManualTransaction(
        transactionId: Int,
        accountId: Int?,
        categoryId: Int?,
        description: String,
        amount: Double,
        type: String,
        date: String
    ): Unit = withWorkspace {
        validateReferences(accountId, categoryId)
        val current =
            dao.transactionById(
                transactionId
            ) ?: return@withWorkspace

        val updated =
            current.copy(
                accountId = accountId,
                categoryId = categoryId,
                description =
                    description,
                amount = amount,
                transactionType =
                    type,
                date = date,
                syncState =
                    if (transactionId > 0) {
                        "PENDING_UPDATE"
                    } else {
                        current.syncState
                    }
            )

        dao.upsertTransaction(
            updated
        )

        if (transactionId > 0) {
            replaceLatestOperation(
                entityType =
                    "TRANSACTION",
                action = "UPDATE",
                entityId =
                    transactionId,
                payload =
                    gson.toJson(
                        TransactionUpdatePayload(
                            accountId =
                                updated.accountId,
                            categoryId =
                                updated.categoryId,
                            date =
                                updated.date,
                            description =
                                updated.description,
                            amount =
                                updated.amount,
                            transactionType =
                                updated.transactionType,
                            status =
                                updated.status
                        )
                    )
            )
        } else {
            scheduleOfflineSync()
        }
    }

    suspend fun updateTransactionCategory(
        transactionId: Int,
        categoryId: Int?
    ): Int = withWorkspace {
        validateReferences(categoryId = categoryId)
        val current =
            dao.transactionById(
                transactionId
            ) ?: return@withWorkspace 0

        val related =
            current.installmentGroup
                ?.takeIf {
                    (current.installmentTotal ?: 1) > 1
                }
                ?.let { group ->
                    dao.transactionsByInstallmentGroup(
                        group
                    )
                }
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: listOf(current)

        related.forEach { transaction ->
            dao.upsertTransaction(
                transaction.copy(
                    categoryId = categoryId,
                    syncState =
                        if (transaction.id > 0) {
                            "PENDING_UPDATE"
                        } else {
                            transaction.syncState
                        }
                )
            )

            if (transaction.id > 0) {
                dao.deleteOperationsForEntityAction(
                    "TRANSACTION",
                    transaction.id,
                    "CATEGORY_UPDATE"
                )

                replaceLatestOperation(
                    entityType = "TRANSACTION",
                    action = "CATEGORY_UPDATE",
                    entityId = transaction.id,
                    payload =
                        gson.toJson(
                            TransactionCategoryUpdatePayload(
                                categoryId = categoryId
                            )
                        )
                )
            }
        }

        scheduleOfflineSync()
        return@withWorkspace related.size
    }

    suspend fun disconnectAccount(
        accountId: Int
    ): Unit = withWorkspace {
        api.disconnectAccount(
            accountId
        )
        refresh()
    }

    suspend fun reconnectAccount(
        accountId: Int
    ): Unit = withWorkspace {
        api.reconnectAccount(
            accountId
        )
        refresh()
    }

    suspend fun requestAccountDeleteCode(
        accountId: Int
    ): Unit = withWorkspace {
        api.requestAccountDeleteCode(
            accountId
        )
    }

    suspend fun confirmAccountDelete(
        accountId: Int,
        code: String
    ): Unit = withWorkspace {
        api.confirmAccountDelete(
            accountId,
            AccountDeleteConfirmRequest(
                code = code
            )
        )

        refresh()
    }

    suspend fun deleteTransactionsBulk(
        transactions: List<TransactionEntity>
    ): Int = withWorkspace {
        require(transactions.all {
            it.userId == WorkspaceOperation.current().userId && it.workspaceId == WorkspaceOperation.current().workspaceId
        }) { "A seleção pertence a outro workspace" }

        if (transactions.isEmpty()) {
            return@withWorkspace 0
        }

        val expanded =
            linkedMapOf<Int, TransactionEntity>()

        transactions.forEach {
                transaction ->
            val related =
                transaction.installmentGroup
                    ?.takeIf {
                        (transaction.installmentTotal ?: 1) > 1
                    }
                    ?.let { group ->
                        dao.transactionsByInstallmentGroup(
                            group
                        )
                    }
                    ?.takeIf {
                        it.isNotEmpty()
                    }
                    ?: listOf(transaction)

            related.forEach {
                    item ->
                expanded[item.id] = item
            }
        }

        expanded.values.forEach {
                item ->
            deletePendingAttachmentsForTransaction(item.id)
            dao.deleteTransactionById(
                item.id
            )

            if (item.id < 0) {
                dao.deleteOperationsForEntity(
                    "TRANSACTION",
                    item.id
                )
            } else {
                dao.deleteOperationsForEntity(
                    "TRANSACTION",
                    item.id
                )

                replaceLatestOperation(
                    entityType =
                        "TRANSACTION",
                    action = "DELETE",
                    entityId = item.id,
                    payload = null
                )
            }
        }

        scheduleOfflineSync()
        return@withWorkspace expanded.size
    }

    suspend fun updateTransactionsCategoryBulk(
        transactions: List<TransactionEntity>,
        categoryId: Int?
    ): Int = withWorkspace {
        validateReferences(categoryId = categoryId)
        require(transactions.all {
            it.userId == WorkspaceOperation.current().userId && it.workspaceId == WorkspaceOperation.current().workspaceId
        }) { "A seleção pertence a outro workspace" }

        if (transactions.isEmpty()) {
            return@withWorkspace 0
        }

        val expanded =
            linkedMapOf<Int, TransactionEntity>()

        transactions.forEach {
                transaction ->
            val related =
                transaction.installmentGroup
                    ?.takeIf {
                        (transaction.installmentTotal ?: 1) > 1
                    }
                    ?.let { group ->
                        dao.transactionsByInstallmentGroup(
                            group
                        )
                    }
                    ?.takeIf {
                        it.isNotEmpty()
                    }
                    ?: listOf(transaction)

            related.forEach {
                    item ->
                expanded[item.id] = item
            }
        }

        expanded.values.forEach {
                transaction ->
            dao.upsertTransaction(
                transaction.copy(
                    categoryId =
                        categoryId,
                    syncState =
                        if (
                            transaction.id > 0
                        ) {
                            "PENDING_UPDATE"
                        } else {
                            transaction.syncState
                        }
                )
            )

            if (transaction.id > 0) {
                dao.deleteOperationsForEntityAction(
                    "TRANSACTION",
                    transaction.id,
                    "CATEGORY_UPDATE"
                )

                replaceLatestOperation(
                    entityType =
                        "TRANSACTION",
                    action =
                        "CATEGORY_UPDATE",
                    entityId =
                        transaction.id,
                    payload =
                        gson.toJson(
                            TransactionCategoryUpdatePayload(
                                categoryId =
                                    categoryId
                            )
                        )
                )
            }
        }

        scheduleOfflineSync()
        return@withWorkspace expanded.size
    }

    suspend fun updateTransactionsAccountBulk(
        transactions: List<TransactionEntity>,
        accountId: Int?
    ): Int = withWorkspace {
        validateReferences(accountId = accountId)
        require(transactions.all {
            it.userId == WorkspaceOperation.current().userId && it.workspaceId == WorkspaceOperation.current().workspaceId
        }) { "A seleção pertence a outro workspace" }

        if (transactions.isEmpty()) return@withWorkspace 0

        val expanded = linkedMapOf<Int, TransactionEntity>()
        transactions.forEach { transaction ->
            val related = transaction.installmentGroup
                ?.takeIf { (transaction.installmentTotal ?: 1) > 1 }
                ?.let { dao.transactionsByInstallmentGroup(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(transaction)
            related.forEach { expanded[it.id] = it }
        }

        expanded.values.forEach { transaction ->
            val updated = transaction.copy(
                accountId = accountId,
                syncState = if (transaction.id > 0) "PENDING_UPDATE" else transaction.syncState
            )
            dao.upsertTransaction(updated)

            if (transaction.id > 0) {
                dao.deleteOperationsForEntityAction("TRANSACTION", transaction.id, "CATEGORY_UPDATE")
                replaceLatestOperation(
                    entityType = "TRANSACTION",
                    action = "UPDATE",
                    entityId = transaction.id,
                    payload = gson.toJson(
                        TransactionUpdatePayload(
                            accountId = updated.accountId,
                            categoryId = updated.categoryId,
                            date = updated.date,
                            description = updated.description,
                            amount = updated.amount,
                            transactionType = updated.transactionType,
                            status = updated.status
                        )
                    )
                )
            }
        }

        scheduleOfflineSync()
        return@withWorkspace expanded.size
    }

    suspend fun deleteTransaction(
        transaction: TransactionEntity
    ): Int = withWorkspace {
        require(transaction.userId == WorkspaceOperation.current().userId && transaction.workspaceId == WorkspaceOperation.current().workspaceId)

        val related =
            transaction.installmentGroup
                ?.takeIf {
                    (transaction.installmentTotal ?: 1) > 1
                }
                ?.let {
                    dao.transactionsByInstallmentGroup(it)
                }
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: listOf(transaction)

        related.forEach { item ->
            deletePendingAttachmentsForTransaction(item.id)
            dao.deleteTransactionById(item.id)

            if (item.id < 0) {
                dao.deleteOperationsForEntity(
                    "TRANSACTION",
                    item.id
                )
            } else {
                dao.deleteOperationsForEntityAction(
                    "TRANSACTION",
                    item.id,
                    "UPDATE"
                )

                replaceLatestOperation(
                    entityType = "TRANSACTION",
                    action = "DELETE",
                    entityId = item.id,
                    payload = null
                )
            }
        }

        scheduleOfflineSync()
        return@withWorkspace related.size
    }

    suspend fun requestDeleteAllUserDataCode(): Unit = withWorkspace {
        api.requestDeleteAllUserDataCode()
    }

    suspend fun confirmDeleteAllUserData(
        code: String,
        options: com.financeapp.mobile.data.remote.UserDataDeleteOptionsRequest
    ): Unit = withWorkspace {
        /*
         * Primeiro o servidor confirma a exclusão. Só depois zeramos o
         * aparelho, para não deixar uma fila offline recriar os dados.
         */
        api.confirmDeleteAllUserData(
            UserDataDeleteConfirmRequest(
                code = code,
                options = options
            )
        )

        WorkManager
            .getInstance(context)
            .cancelUniqueWork(
                OfflineSyncWorker.UNIQUE_WORK_NAME + "-${WorkspaceOperation.current().userId}-${WorkspaceOperation.current().workspaceId}"
            )

        clearLocal()
        // Nao aguardamos refresh de rede aqui. A exclusao ja foi confirmada
        // pelo servidor; esperar uma segunda resposta podia manter o dialogo
        // preso em "Carregando" mesmo depois dos dados terem sido apagados.
        // A recomposicao do cache restante e feita em segundo plano pelo VM.

        /*
         * Arquivos de relatórios/exportações também são dados financeiros.
         */
        runCatching {
            context.cacheDir
                .resolve("reports")
                .deleteRecursively()
        }

        /*
         * Notifica o mecanismo de backup do Android de que o estado mudou.
         * As regras de backup deste patch excluem finance.db, de modo que
         * transações antigas não podem voltar por Auto Backup.
         */
        runCatching {
            BackupManager(context)
                .dataChanged()
        }
    }

    suspend fun clearLocal(): Unit = withWorkspace {
        dao.clearTransactions()
        dao.clearAccounts()
        dao.clearCategories()
        dao.clearCards()
        dao.clearGoals()
        dao.clearBudgets()
        dao.clearGoalContributions()
        dao.clearPendingOperations()
    }


    data class FinanceBackupPayload(
        val format: String = "financeapp-backup-v1",
        val createdAt: Long = System.currentTimeMillis(),
        val accounts: List<AccountEntity>,
        val transactions: List<TransactionEntity>,
        val categories: List<CategoryEntity>,
        val cards: List<CreditCardEntity>,
        val goals: List<GoalEntity>,
        val budgets: List<BudgetEntity>,
        val contributions: List<GoalContributionEntity>,
        val debtPrefs: Map<String, Any?> = emptyMap(),
        val pendingOperations: List<PendingSyncOperationEntity> = emptyList()
    )

    data class WorkspaceFinanceBackupPayload(
        val workspace: WorkspaceEntity,
        val accounts: List<AccountEntity>,
        val transactions: List<TransactionEntity>,
        val categories: List<CategoryEntity>,
        val cards: List<CreditCardEntity>,
        val goals: List<GoalEntity>,
        val budgets: List<BudgetEntity>,
        val contributions: List<GoalContributionEntity>,
        val debtPrefs: Map<String, Any?> = emptyMap()
    )

    data class FinanceBackupV3(
        val format: String = "financeapp-backup-v3",
        val createdAt: Long = System.currentTimeMillis(),
        val workspaces: List<WorkspaceFinanceBackupPayload>
    )
    suspend fun exportBackupJson(): String = withWorkspace {
        val scope = WorkspaceOperation.current()
        val debtPrefsAll = context.getSharedPreferences("financeapp_debts", Context.MODE_PRIVATE).all
        val localWorkspaces = dao.workspacesForUser(scope.userId)
            .filter { it.archivedAt == null }
            .ifEmpty {
                listOf(
                    WorkspaceEntity(
                        userId = scope.userId,
                        id = scope.workspaceId,
                        name = "Meu espaço",
                        kind = "personal",
                        isDefault = true,
                        archivedAt = null
                    )
                )
            }

        val payloads = localWorkspaces.map { ws ->
            WorkspaceFinanceBackupPayload(
                workspace = ws,
                accounts = dao.backupAccounts(scope.userId, ws.id),
                transactions = dao.backupTransactions(scope.userId, ws.id),
                categories = dao.backupCategories(scope.userId, ws.id),
                cards = dao.backupCards(scope.userId, ws.id),
                goals = dao.backupGoals(scope.userId, ws.id),
                budgets = dao.backupBudgets(scope.userId, ws.id),
                contributions = dao.backupGoalContributions(scope.userId, ws.id),
                debtPrefs = debtPrefsAll
                    .filterKeys { it.endsWith("_${ws.id}") }
                    .mapValues { it.value }
            )
        }
        Gson().toJson(FinanceBackupV3(workspaces = payloads))
    }

    suspend fun restoreBackupJson(raw: String, mode: String = "replace"): Int = withWorkspace {
        val root = com.google.gson.JsonParser.parseString(raw).asJsonObject
        val format = root.get("format")?.asString.orEmpty()
        val currentScope = WorkspaceOperation.current()
        val normalizedMode = mode.lowercase(Locale.ROOT)
        require(normalizedMode == "merge" || normalizedMode == "replace") { "Modo de restauração inválido" }

        if (format == "financeapp-backup-v1") {
            val payload = Gson().fromJson(raw, FinanceBackupPayload::class.java)
            if (normalizedMode == "replace") {
                database.withTransaction {
                    dao.clearPendingOperations()
                    dao.clearTransactions()
                    dao.clearAccountsForRestore()
                    dao.clearCategories()
                    dao.clearCards()
                    dao.clearGoals()
                    dao.clearBudgets()
                    dao.clearGoalContributions()
                    dao.upsertAccounts(payload.accounts.map { it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) })
                    dao.upsertCategories(payload.categories.map { it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) })
                    dao.upsertCards(payload.cards.map { it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) })
                    dao.upsertTransactions(payload.transactions.map { it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) })
                    dao.upsertGoals(payload.goals.map { it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) })
                    dao.upsertBudgets(payload.budgets.map { it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) })
                    dao.upsertGoalContributions(payload.contributions.map { it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) })
                }
            } else {
                database.withTransaction {
                    val accounts = dao.backupAccounts().associateBy { it.id }.toMutableMap()
                    payload.accounts.forEach { if (!accounts.containsKey(it.id)) accounts[it.id] = it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) }
                    dao.upsertAccounts(accounts.values.toList())
                    val categories = dao.backupCategories().associateBy { it.id }.toMutableMap()
                    payload.categories.forEach { if (!categories.containsKey(it.id)) categories[it.id] = it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) }
                    dao.upsertCategories(categories.values.toList())
                    val cards = dao.backupCards().associateBy { it.id }.toMutableMap()
                    payload.cards.forEach { if (!cards.containsKey(it.id)) cards[it.id] = it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) }
                    dao.upsertCards(cards.values.toList())
                    val txs = dao.backupTransactions().associateBy { it.id }.toMutableMap()
                    payload.transactions.forEach { if (!txs.containsKey(it.id)) txs[it.id] = it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) }
                    dao.upsertTransactions(txs.values.toList())
                    val goals = dao.backupGoals().associateBy { it.id }.toMutableMap()
                    payload.goals.forEach { if (!goals.containsKey(it.id)) goals[it.id] = it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) }
                    dao.upsertGoals(goals.values.toList())
                    val budgets = dao.backupBudgets().associateBy { it.categoryId }.toMutableMap()
                    payload.budgets.forEach { if (!budgets.containsKey(it.categoryId)) budgets[it.categoryId] = it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) }
                    dao.upsertBudgets(budgets.values.toList())
                    val contributions = dao.backupGoalContributions().associateBy { it.id }.toMutableMap()
                    payload.contributions.forEach { if (!contributions.containsKey(it.id)) contributions[it.id] = it.copy(userId=currentScope.userId,workspaceId=currentScope.workspaceId) }
                    dao.upsertGoalContributions(contributions.values.toList())
                }
            }
            val editor = context.getSharedPreferences("financeapp_debts", Context.MODE_PRIVATE).edit()
            payload.debtPrefs.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Collection<*> -> editor.putStringSet(key, value.mapNotNull { it?.toString() }.toSet())
                }
            }
            editor.apply()
            return@withWorkspace payload.transactions.size + payload.accounts.size + payload.categories.size + payload.cards.size + payload.goals.size + payload.budgets.size + payload.contributions.size
        }

        require(format == "financeapp-backup-v3") { "Arquivo de backup incompatível" }
        val backup = Gson().fromJson(raw, FinanceBackupV3::class.java)
        require(backup.workspaces.isNotEmpty()) { "O backup não contém workspaces" }
        val backupIds = backup.workspaces.map { it.workspace.id }.toSet()
        val existingWorkspaces = dao.workspacesForUser(currentScope.userId)
        val debtPrefs = context.getSharedPreferences("financeapp_debts", Context.MODE_PRIVATE)

        if (normalizedMode == "replace") {
            database.withTransaction {
                existingWorkspaces.forEach { ws ->
                    dao.clearPendingOperations(currentScope.userId, ws.id)
                    dao.clearTransactions(currentScope.userId, ws.id)
                    dao.clearAccountsForRestore(currentScope.userId, ws.id)
                    dao.clearCategories(currentScope.userId, ws.id)
                    dao.clearCards(currentScope.userId, ws.id)
                    dao.clearGoals(currentScope.userId, ws.id)
                    dao.clearBudgets(currentScope.userId, ws.id)
                    dao.clearGoalContributions(currentScope.userId, ws.id)
                    if (ws.id !in backupIds) dao.deleteWorkspace(currentScope.userId, ws.id)
                }
                dao.upsertWorkspaces(backup.workspaces.map { it.workspace.copy(userId = currentScope.userId, archivedAt = null) })
            }
            val clean = debtPrefs.edit()
            debtPrefs.all.keys.filter { key -> existingWorkspaces.any { key.endsWith("_${it.id}") } }.forEach { clean.remove(it) }
            clean.apply()
        } else {
            dao.upsertWorkspaces(backup.workspaces.map { it.workspace.copy(userId = currentScope.userId, archivedAt = null) })
        }

        var restored = 0
        for (wsBackup in backup.workspaces) {
            val wsId = wsBackup.workspace.id
            database.withTransaction {
                if (normalizedMode == "replace") {
                    dao.clearPendingOperations(currentScope.userId, wsId)
                    dao.clearTransactions(currentScope.userId, wsId)
                    dao.clearAccountsForRestore(currentScope.userId, wsId)
                    dao.clearCategories(currentScope.userId, wsId)
                    dao.clearCards(currentScope.userId, wsId)
                    dao.clearGoals(currentScope.userId, wsId)
                    dao.clearBudgets(currentScope.userId, wsId)
                    dao.clearGoalContributions(currentScope.userId, wsId)
                    dao.upsertAccounts(wsBackup.accounts.map { it.copy(userId=currentScope.userId,workspaceId=wsId) })
                    dao.upsertCategories(wsBackup.categories.map { it.copy(userId=currentScope.userId,workspaceId=wsId) })
                    dao.upsertCards(wsBackup.cards.map { it.copy(userId=currentScope.userId,workspaceId=wsId) })
                    dao.upsertTransactions(wsBackup.transactions.map { it.copy(userId=currentScope.userId,workspaceId=wsId) })
                    dao.upsertGoals(wsBackup.goals.map { it.copy(userId=currentScope.userId,workspaceId=wsId) })
                    dao.upsertBudgets(wsBackup.budgets.map { it.copy(userId=currentScope.userId,workspaceId=wsId) })
                    dao.upsertGoalContributions(wsBackup.contributions.map { it.copy(userId=currentScope.userId,workspaceId=wsId) })
                } else {
                    val accounts = dao.backupAccounts(currentScope.userId, wsId).associateBy { it.id }.toMutableMap()
                    wsBackup.accounts.forEach { if (!accounts.containsKey(it.id)) accounts[it.id] = it.copy(userId=currentScope.userId,workspaceId=wsId) }
                    dao.upsertAccounts(accounts.values.toList())
                    val categories = dao.backupCategories(currentScope.userId, wsId).associateBy { it.id }.toMutableMap()
                    wsBackup.categories.forEach { if (!categories.containsKey(it.id)) categories[it.id] = it.copy(userId=currentScope.userId,workspaceId=wsId) }
                    dao.upsertCategories(categories.values.toList())
                    val cards = dao.backupCards(currentScope.userId, wsId).associateBy { it.id }.toMutableMap()
                    wsBackup.cards.forEach { if (!cards.containsKey(it.id)) cards[it.id] = it.copy(userId=currentScope.userId,workspaceId=wsId) }
                    dao.upsertCards(cards.values.toList())
                    val txs = dao.backupTransactions(currentScope.userId, wsId).associateBy { it.id }.toMutableMap()
                    wsBackup.transactions.forEach { if (!txs.containsKey(it.id)) txs[it.id] = it.copy(userId=currentScope.userId,workspaceId=wsId) }
                    dao.upsertTransactions(txs.values.toList())
                    val goals = dao.backupGoals(currentScope.userId, wsId).associateBy { it.id }.toMutableMap()
                    wsBackup.goals.forEach { if (!goals.containsKey(it.id)) goals[it.id] = it.copy(userId=currentScope.userId,workspaceId=wsId) }
                    dao.upsertGoals(goals.values.toList())
                    val budgets = dao.backupBudgets(currentScope.userId, wsId).associateBy { it.categoryId }.toMutableMap()
                    wsBackup.budgets.forEach { if (!budgets.containsKey(it.categoryId)) budgets[it.categoryId] = it.copy(userId=currentScope.userId,workspaceId=wsId) }
                    dao.upsertBudgets(budgets.values.toList())
                    val contributions = dao.backupGoalContributions(currentScope.userId, wsId).associateBy { it.id }.toMutableMap()
                    wsBackup.contributions.forEach { if (!contributions.containsKey(it.id)) contributions[it.id] = it.copy(userId=currentScope.userId,workspaceId=wsId) }
                    dao.upsertGoalContributions(contributions.values.toList())
                }
            }

            val editor = debtPrefs.edit()
            wsBackup.debtPrefs.forEach { (key, value) ->
                if (normalizedMode == "merge" && debtPrefs.contains(key)) return@forEach
                when (value) {
                    is String -> editor.putString(key, value)
                    is Collection<*> -> editor.putStringSet(key, value.mapNotNull { it?.toString() }.toSet())
                }
            }
            editor.apply()
            restored += wsBackup.transactions.size + wsBackup.accounts.size + wsBackup.categories.size + wsBackup.cards.size + wsBackup.goals.size + wsBackup.budgets.size + wsBackup.contributions.size
        }

        if (currentScope.workspaceId !in backupIds) {
            val preferred = backup.workspaces.firstOrNull { it.workspace.isDefault } ?: backup.workspaces.first()
            session.activateWorkspace(WorkspaceScope(currentScope.userId, preferred.workspace.id))
        }
        restored
    }

    private fun forecastCacheKey(): String = WorkspaceOperation.current().let { "${it.userId}:${it.workspaceId}" }

    suspend fun forecastState(): Map<String, Any?> = withWorkspace {
        if (!financialCloudSyncEnabled) return@withWorkspace mapOf("payload" to ForecastCache.read(context, forecastCacheKey()))
        if (WorkspaceOperation.current().userId == 0) return@withWorkspace mapOf("payload" to ForecastCache.read(context, forecastCacheKey()))
        ForecastCache.sync(context, forecastCacheKey(), {
            try { api.forecastStateSync() }
            catch (e: retrofit2.HttpException) {
                if (e.code() != 404) throw e
                try { api.forecastState() }
                catch (legacy: retrofit2.HttpException) {
                    if (legacy.code() == 404) error("Backend sem rota de sincronização de cenários/planos. Atualize e reinicie o backend.")
                    throw legacy
                }
            }
        }, {
            val request = com.financeapp.mobile.data.remote.ForecastStateRequest(it)
            try { api.saveForecastStateSync(request) }
            catch (e: retrofit2.HttpException) {
                if (e.code() != 404) throw e
                try { api.saveForecastState(request) }
                catch (legacy: retrofit2.HttpException) {
                    if (legacy.code() == 404) error("Backend sem rota de sincronização de cenários/planos. Atualize e reinicie o backend.")
                    throw legacy
                }
            }
        })
    }

    suspend fun saveForecastState(payload: Map<String, Any?>): Map<String, Any?> = withWorkspace {
        ForecastCache.update(context, forecastCacheKey(), payload)
        mapOf("payload" to ForecastCache.read(context, forecastCacheKey()))
    }

    suspend fun askFinancialAi(
        request: com.financeapp.mobile.data.remote.FinancialAiRequest
    ): com.financeapp.mobile.data.remote.FinancialAiResponse = withWorkspace {
        api.askFinancialAi(request)
    }

    private fun pendingAttachmentDir(): File {
        val scope = WorkspaceOperation.current()
        val safeWorkspace = scope.workspaceId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(context.filesDir, "pending_transaction_attachments/${scope.userId}/$safeWorkspace").apply { mkdirs() }
    }

    private fun pendingAttachmentFile(storedName: String): File = File(pendingAttachmentDir(), storedName)

    private suspend fun pendingAttachmentRows(transactionId: Int): List<Pair<PendingSyncOperationEntity, PendingAttachmentCreatePayload>> =
        dao.pendingOperations()
            .asSequence()
            .filter { it.entityType == "ATTACHMENT" && it.action == "CREATE" && it.entityId == transactionId }
            .mapNotNull { op ->
                runCatching { gson.fromJson(op.payload, PendingAttachmentCreatePayload::class.java) }
                    .getOrNull()?.let { op to it }
            }
            .toList()

    private suspend fun deletePendingAttachmentsForTransaction(transactionId: Int) {
        dao.pendingOperations()
            .filter { it.entityType == "ATTACHMENT" && it.entityId == transactionId }
            .forEach { op ->
                if (op.action == "CREATE") {
                    runCatching { gson.fromJson(op.payload, PendingAttachmentCreatePayload::class.java) }
                        .getOrNull()
                        ?.let { pendingAttachmentFile(it.storedName).delete() }
                }
                dao.deleteOperation(op.id)
            }
    }

    private fun pendingAttachmentDto(transactionId: Int, payload: PendingAttachmentCreatePayload) =
        TransactionAttachmentDto(
            id = payload.localAttachmentId,
            transactionId = transactionId,
            originalName = payload.fileName,
            contentType = payload.contentType,
            sizeBytes = payload.sizeBytes,
            createdAt = payload.createdAt
        )

    suspend fun transactionAttachments(transactionId: Int): List<TransactionAttachmentDto> = withWorkspace {
        val local = pendingAttachmentRows(transactionId).map { pendingAttachmentDto(transactionId, it.second) }
        val scope = WorkspaceOperation.current()
        if (transactionId <= 0 || scope.userId == 0) return@withWorkspace local

        val pendingDeleteIds = dao.pendingOperations()
            .asSequence()
            .filter { it.entityType == "ATTACHMENT" && it.action == "DELETE" && it.entityId == transactionId }
            .mapNotNull { runCatching { gson.fromJson(it.payload, PendingAttachmentDeletePayload::class.java).attachmentId }.getOrNull() }
            .toSet()

        val remote = runCatching { api.transactionAttachments(transactionId) }
            .getOrElse { return@withWorkspace local }
            .filter { it.id !in pendingDeleteIds }
        remote + local
    }

    suspend fun uploadTransactionAttachment(
        transactionId: Int,
        fileName: String,
        contentType: String,
        bytes: ByteArray
    ): TransactionAttachmentDto = withWorkspace {
        require(bytes.isNotEmpty()) { "O comprovante esta vazio." }
        require(bytes.size <= 15 * 1024 * 1024) { "O comprovante deve ter no maximo 15 MB." }

        // Primeiro persistimos no aparelho. Assim uma queda de rede/app nunca faz o
        // comprovante desaparecer antes de ele existir na nuvem.
        val rawHash = UUID.randomUUID().hashCode()
        val positiveHash = if (rawHash == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(rawHash)
        val localAttachmentId = -positiveHash.coerceAtLeast(1)
        val storedName = "${UUID.randomUUID()}.pending"
        val file = pendingAttachmentFile(storedName)
        FileOutputStream(file, false).use { out ->
            out.write(bytes)
            out.flush()
            runCatching { out.fd.sync() }
        }
        require(file.length() == bytes.size.toLong()) { "Nao foi possivel salvar o comprovante no aparelho." }

        val payload = PendingAttachmentCreatePayload(
            localAttachmentId = localAttachmentId,
            fileName = fileName,
            contentType = contentType.ifBlank { "application/octet-stream" },
            storedName = storedName,
            sizeBytes = bytes.size.toLong(),
            createdAt = OffsetDateTime.now().toString()
        )
        dao.enqueueOperation(PendingSyncOperationEntity(
            entityType = "ATTACHMENT",
            action = "CREATE",
            entityId = transactionId,
            payload = gson.toJson(payload)
        ))

        // Se ja existe ID remoto, tentamos enviar agora. Falha de rede nao e erro:
        // a copia local permanece na fila e o WorkManager envia quando reconectar.
        if (transactionId > 0 && WorkspaceOperation.current().userId > 0) {
            val uploaded = runCatching {
                val body = bytes.toRequestBody(payload.contentType.toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", fileName, body)
                api.uploadTransactionAttachment(transactionId, part)
            }.getOrNull()
            if (uploaded != null) {
                pendingAttachmentRows(transactionId)
                    .firstOrNull { it.second.localAttachmentId == localAttachmentId }
                    ?.first
                    ?.let { dao.deleteOperation(it.id) }
                file.delete()
                return@withWorkspace uploaded
            }
        }

        runCatching { scheduleOfflineSync() }
        pendingAttachmentDto(transactionId, payload)
    }

    suspend fun downloadTransactionAttachment(transactionId: Int, attachmentId: Int): ByteArray =
        withWorkspace {
            if (attachmentId < 0) {
                val payload = pendingAttachmentRows(transactionId)
                    .firstOrNull { it.second.localAttachmentId == attachmentId }
                    ?.second ?: error("Comprovante local nao encontrado.")
                val file = pendingAttachmentFile(payload.storedName)
                require(file.exists()) { "Arquivo local do comprovante nao encontrado." }
                return@withWorkspace file.readBytes()
            }

            val response = api.downloadTransactionAttachment(transactionId, attachmentId)
            if (!response.isSuccessful) {
                val detail = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()
                throw IllegalStateException(
                    "Falha ao baixar comprovante (HTTP ${response.code()})" +
                        detail.takeIf { it.isNotBlank() }?.let { ": ${it.take(300)}" }.orEmpty()
                )
            }
            val body = response.body() ?: error("Servidor retornou o comprovante sem conteudo.")
            val responseType = body.contentType()?.toString().orEmpty().lowercase()
            if (responseType.contains("application/json") || responseType.contains("text/html")) {
                val raw = runCatching { body.string() }.getOrNull().orEmpty()
                error("Servidor retornou ${responseType.ifBlank { "conteudo invalido" }} em vez do arquivo: ${raw.take(300)}")
            }
            val bytes = body.bytes()
            require(bytes.isNotEmpty()) { "O comprovante recebido do servidor esta vazio." }
            bytes
        }

    suspend fun deleteTransactionAttachment(transactionId: Int, attachmentId: Int): Unit = withWorkspace {
        if (attachmentId < 0) {
            val local = pendingAttachmentRows(transactionId)
                .firstOrNull { it.second.localAttachmentId == attachmentId }
                ?: return@withWorkspace
            pendingAttachmentFile(local.second.storedName).delete()
            dao.deleteOperation(local.first.id)
            return@withWorkspace
        }

        try {
            api.deleteTransactionAttachment(transactionId, attachmentId)
        } catch (e: HttpException) {
            if (e.code() == 404) return@withWorkspace
            dao.enqueueOperation(PendingSyncOperationEntity(
                entityType = "ATTACHMENT",
                action = "DELETE",
                entityId = transactionId,
                payload = gson.toJson(PendingAttachmentDeletePayload(attachmentId))
            ))
            runCatching { scheduleOfflineSync() }
        } catch (_: Exception) {
            dao.enqueueOperation(PendingSyncOperationEntity(
                entityType = "ATTACHMENT",
                action = "DELETE",
                entityId = transactionId,
                payload = gson.toJson(PendingAttachmentDeletePayload(attachmentId))
            ))
            runCatching { scheduleOfflineSync() }
        }
    }

}
