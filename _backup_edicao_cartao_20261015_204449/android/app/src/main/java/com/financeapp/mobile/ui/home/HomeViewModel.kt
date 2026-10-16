package com.financeapp.mobile.ui.home

import com.financeapp.mobile.data.remote.UserDto
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeapp.mobile.data.local.AccountEntity
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.CategoryDto
import com.financeapp.mobile.data.remote.CreditCardDto
import com.financeapp.mobile.data.remote.BudgetDto
import com.financeapp.mobile.data.remote.GoalDto
import com.financeapp.mobile.data.remote.GoalContributionDto
import com.financeapp.mobile.data.repository.AuthRepository
import com.financeapp.mobile.data.repository.FinanceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import retrofit2.HttpException
import org.json.JSONObject

data class HomeUiState(
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val connectionId: Int? = null,
    val userEmail: String? = null,
    val lastSyncAt: String? = null,
    val highlightedTransactionId: Int? = null,
    val pendingSyncCount: Int = 0,
    val offlineSyncing: Boolean = false,
    val openFinanceWidgetUrl: String? = null,
    val serverAvailable: Boolean? = null,
    val needsOpenFinanceProfile: Boolean = false,
    val openFinanceProfileName: String = "",
    val profilePhoto: String? = null,
    val hasGoogleProfilePhoto: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: FinanceRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val screenContext = com.financeapp.mobile.util.WorkspaceScreenContext.element(repository.screenWorkspace())

    private fun userFacingError(
        error: Exception,
        fallback: String
    ): String {
        if (error is HttpException) {
            val raw =
                runCatching {
                    error.response()
                        ?.errorBody()
                        ?.string()
                }.getOrNull()

            if (!raw.isNullOrBlank()) {
                val detail =
                    runCatching {
                        JSONObject(raw)
                            .optString("detail")
                    }.getOrNull()

                if (!detail.isNullOrBlank()) {
                    return detail
                }
            }
        }

        return error.message
            ?.takeIf {
                it.isNotBlank()
            }
            ?: fallback
    }


    val accounts: StateFlow<List<AccountEntity>> =
        repository.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val transactions: StateFlow<List<TransactionEntity>> =
        repository.transactions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryDto>> =
        repository.categoriesFlow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val cards: StateFlow<List<CreditCardDto>> =
        repository.cardsFlow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val budgets: StateFlow<List<BudgetDto>> =
        repository.budgetsFlow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val goals: StateFlow<List<GoalDto>> =
        repository.goalsFlow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val goalContributions:
        StateFlow<List<GoalContributionDto>> =
        repository.goalContributionsFlow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    private val _state = MutableStateFlow(
        HomeUiState(profilePhoto = repository.cachedProfilePhoto())
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()


    suspend fun forecastState(): Map<String, Any?> = kotlinx.coroutines.withContext(screenContext) { repository.forecastState() }

    suspend fun saveForecastState(payload: Map<String, Any?>): Map<String, Any?> = kotlinx.coroutines.withContext(screenContext) { repository.saveForecastState(payload) }

    fun createManualAccount(name: String, agency: String?, accountNumber: String?) = viewModelScope.launch(screenContext) {
        try { repository.createManualAccount(name, agency, accountNumber) } catch (e: Exception) { _state.value = _state.value.copy(error=e.message) }
    }
    fun createGuestAccount(name: String) = viewModelScope.launch(screenContext) {
        try { repository.createGuestAccount(name) } catch (e: Exception) { _state.value = _state.value.copy(error=e.message) }
    }
    fun deleteGuestAccount(id: Int) = viewModelScope.launch(screenContext) {
        try { repository.deleteGuestAccount(id) } catch (e: Exception) { _state.value = _state.value.copy(error=e.message) }
    }
    init {
        viewModelScope.launch(screenContext) {
            repository.pendingSyncCount.collect {
                    count ->
                _state.value =
                    _state.value.copy(
                        pendingSyncCount = 0
                    )
            }
        }

        viewModelScope.launch(screenContext) {
            repository.offlineSyncing.collect {
                    syncing ->
                _state.value =
                    _state.value.copy(
                        offlineSyncing = syncing
                    )
            }
        }

        /*
         * Internet do aparelho e disponibilidade do servidor são
         * coisas diferentes. O app confirma a API periodicamente.
         *
         * Para evitar status piscando por um timeout isolado:
         * - 1ª falha: mantém o último estado;
         * - 2ª falha consecutiva: servidor indisponível.
         * Uma resposta válida recupera o estado imediatamente.
         */
        viewModelScope.launch(screenContext) {
            var consecutiveFailures = 0
            var wasAvailable: Boolean? =
                null

            if (repository.isGuest()) return@launch
            while (true) {
                val available =
                    repository.serverIsAvailable()

                if (available) {
                    consecutiveFailures = 0

                    val shouldKickSync =
                        _state.value.pendingSyncCount > 0 &&
                            !_state.value.offlineSyncing

                    wasAvailable = true

                    _state.value =
                        _state.value.copy(
                            serverAvailable = true
                        )

                    if (shouldKickSync) {
                        repository
                            .scheduleOfflineSync(
                                force = true
                            )
                    }
                } else {
                    consecutiveFailures += 1

                    if (
                        consecutiveFailures >= 2
                    ) {
                        wasAvailable = false

                        _state.value =
                            _state.value.copy(
                                serverAvailable =
                                    false
                            )
                    }
                }

                delay(15_000)
            }
        }

        // Sem refresh completo em loop: Room atualiza a UI localmente e
        // o WorkManager cuida do envio pendente quando a internet volta.
        // Refresh remoto continua ocorrendo em login, ações explícitas e sync.
    }

    private suspend fun currentLastSync(): String? =
        runCatching { repository.syncStatus().lastFullSyncAt }.getOrNull()

    private suspend fun currentEmail(): String? =
        runCatching { authRepository.currentUser().email }.getOrNull()

    private suspend fun loadProfilePhotoWithoutWorkspaceReload(): String? {
        if (repository.hasCachedProfilePhoto()) return repository.cachedProfilePhoto()
        return runCatching {
            repository.currentUserProfile().profilePhoto.also(repository::cacheProfilePhoto)
        }.getOrElse { repository.cachedProfilePhoto() }
    }

    fun restoreAfterLogin() {
        if (repository.isGuest()) return
        /*
         * O login não deve esperar a restauração completa da nuvem.
         * Room já entrega imediatamente os últimos dados locais.
         * A atualização do servidor acontece em segundo plano.
         */
        _state.value = _state.value.copy(
            loading = false,
            error = null
        )

        viewModelScope.launch(screenContext) {
            try {
                repository.scheduleOfflineSync(
                    force = true
                )
                repository.refresh()

                val id =
                    repository.firstConnectionId()

                _state.value =
                    _state.value.copy(
                        loading = false,
                        connectionId = id,
                        userEmail =
                            currentEmail(),
                        lastSyncAt =
                            currentLastSync(),
                        profilePhoto = loadProfilePhotoWithoutWorkspaceReload(),
                        hasGoogleProfilePhoto = runCatching { repository.currentUserProfile().hasGoogleProfilePhoto }.getOrDefault(_state.value.hasGoogleProfilePhoto),
                        message = null
                    )
            } catch (e: Exception) {
                /*
                 * Se a rede estiver lenta, o usuário continua no app
                 * usando os dados locais; uma nova sincronização pode
                 * acontecer depois sem exigir outro login.
                 */
                _state.value =
                    _state.value.copy(
                        loading = false,
                        error = null
                    )
            }
        }
    }

    suspend fun exportBackupJson(): String = repository.exportBackupJson()

    suspend fun restoreBackupJson(raw: String, mode: String): Int {
        val count = repository.restoreBackupJson(raw, mode)
        _state.value = _state.value.copy(message = "Backup restaurado: $count registros", error = null)
        return count
    }

    suspend fun currentUserProfile() = repository.currentUserProfile()

    suspend fun updatePersonalProfile(name: String, cpf: String, birthDate: String?, sex: String?) =
        repository.updatePersonalProfile(name, cpf, birthDate, sex)

    fun updateProfilePhoto(profilePhoto: String?) = viewModelScope.launch(screenContext) {
        if (repository.isGuest()) return@launch
        try {
            val user = repository.updateProfilePhoto(profilePhoto)
            repository.cacheProfilePhoto(user.profilePhoto)
            _state.value = _state.value.copy(profilePhoto = user.profilePhoto, error = null)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = userFacingError(e, "Não foi possível atualizar a foto do perfil"))
        }
    }


    fun useGoogleProfilePhoto() = viewModelScope.launch(screenContext) {
        if (repository.isGuest()) return@launch
        try {
            val user = repository.useGoogleProfilePhoto()
            repository.cacheProfilePhoto(user.profilePhoto)
            _state.value = _state.value.copy(profilePhoto = user.profilePhoto, hasGoogleProfilePhoto = user.hasGoogleProfilePhoto, error = null)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = userFacingError(e, "Não foi possível usar a foto da conta Google"))
        }
    }

    fun refresh() {
        if (repository.isGuest()) return

        /*
         * Offline-first: a tela nunca deve ficar bloqueada esperando a API.
         * Os StateFlows acima ja estao ligados ao Room e exibem o cache local
         * imediatamente. O refresh remoto e apenas uma tentativa em background.
         */
        _state.value = _state.value.copy(loading = false, error = null)

        viewModelScope.launch(screenContext) {
            try {
                // Antes de baixar o snapshot do servidor, envia tudo que estiver
                // pendente no aparelho. Assim o Web e o Android passam a convergir
                // para a mesma fonte de verdade, sem depender apenas do WorkManager.
                runCatching { repository.syncPendingNow() }
                repository.refresh()
                val id = repository.firstConnectionId()
                _state.value = _state.value.copy(
                    loading = false,
                    connectionId = id,
                    userEmail = currentEmail(),
                    lastSyncAt = currentLastSync(),
                    serverAvailable = true,
                    error = null
                )
            } catch (_: Exception) {
                // Sem internet/servidor: mantem Room visivel e utilizavel.
                _state.value = _state.value.copy(
                    loading = false,
                    serverAvailable = false,
                    error = null
                )
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch(screenContext) {
            if (repository.isGuest()) return@launch
            _state.value = _state.value.copy(loading = true, error = null, message = null)
            try {
                // O botão da Central precisa aguardar uma tentativa REAL de envio.
                // Antes ele apenas agendava o WorkManager e já mostrava sucesso,
                // por isso o contador podia continuar em 10 sem explicar o motivo.
                repeat(3) { attempt ->
                    runCatching { repository.syncPendingNow() }
                    if (repository.pendingSyncCountNow() == 0) return@repeat
                    if (attempt == 1) {
                        runCatching { repository.reconcilePendingAfterSync() }
                    }
                    kotlinx.coroutines.delay(if (attempt == 0) 600L else 1_000L)
                }

                val pending = repository.pendingSyncCountNow()
                if (pending == 0) {
                    repository.refresh()
                    _state.value = _state.value.copy(
                        loading = false,
                        userEmail = currentEmail(),
                        pendingSyncCount = 0,
                        lastSyncAt = currentLastSync(),
                        serverAvailable = true,
                        error = null,
                        message = "Tudo sincronizado."
                    )
                } else {
                    // V3.25.6.3: neste ciclo de desenvolvimento o usuario autorizou
                    // descartar a fila legada que ficou presa entre versoes.
                    // Os dados locais nao sao apagados; somente o estado pendente e limpo.
                    repository.discardLegacyPendingSync()
                    val remaining = repository.pendingSyncCountNow()
                    _state.value = _state.value.copy(
                        loading = false,
                        pendingSyncCount = remaining,
                        lastSyncAt = currentLastSync(),
                        serverAvailable = true,
                        error = null,
                        message = if (remaining == 0)
                            "Pendências antigas removidas. Sincronização limpa."
                        else
                            "Limpeza concluída; $remaining alteração(ões) ainda precisam de revisão."
                    )
                }
            } catch (e: Exception) {
                val pending = runCatching { repository.pendingSyncCountNow() }.getOrDefault(_state.value.pendingSyncCount)
                _state.value = _state.value.copy(
                    loading = false,
                    pendingSyncCount = pending,
                    error = userFacingError(e, "Não foi possível sincronizar agora.")
                )
            }
        }
    }

    fun payCardInvoice(
        accountId: Int,
        cardId: Int,
        invoiceEnd: String,
        amount: Double,
        date: String,
        onSaved: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch(screenContext) {
            try {
                repository.createCardPayment(accountId, cardId, invoiceEnd, amount, date)
                _state.value = _state.value.copy(message = "Pagamento da fatura registrado.")
                onSaved(true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Não foi possível registrar o pagamento da fatura.")
                onSaved(false)
            }
        }
    }

    fun createManual(
        accountId: Int?,
        categoryId: Int?,
        description: String,
        amount: Double,
        type: String,
        date: String,
        cardId: Int? = null,
        installmentTotal: Int = 1,
        firstChargeDate: String? = null,
        onSaved: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch(screenContext) {
            _state.value = _state.value.copy(error=null)
            try {
                val createdId =
                    repository.createManual(
                        accountId,
                        categoryId,
                        description,
                        amount,
                        type,
                        date,
                        cardId,
                        installmentTotal,
                        firstChargeDate
                    )

                _state.value = _state.value.copy(
                    message = "Lançamento salvo.",
                    highlightedTransactionId = createdId
                )

                onSaved(true)
                // O destaque visual é temporário.
                delay(3_000)
                if (_state.value.highlightedTransactionId == createdId) {
                    _state.value = _state.value.copy(
                        highlightedTransactionId = null
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
                onSaved(false)
            }
        }
    }


    fun connectBank() {
        viewModelScope.launch(screenContext) {
            _state.value =
                _state.value.copy(
                    loading = true,
                    error = null
                )

            try {
                val user: UserDto =
                    authRepository.currentUser()

                val validName =
                    !user.name.isNullOrBlank() &&
                        user.name.trim()
                            .split(
                                Regex("\\s+")
                            )
                            .size >= 2

                val validCpf =
                    !user.cpf.isNullOrBlank() &&
                        user.cpf
                            .filter(
                                Char::isDigit
                            )
                            .length == 11

                if (
                    !validName ||
                    !validCpf
                ) {
                    _state.value =
                        _state.value.copy(
                            loading = false,
                            needsOpenFinanceProfile =
                                true,
                            openFinanceProfileName =
                                user.name.orEmpty()
                        )
                    return@launch
                }

                openBankWidget(
                    user.name!!,
                    user.cpf!!
                )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        loading = false,
                        error = e.message
                            ?: "Não foi possível iniciar a conexão bancária."
                    )
            }
        }
    }

    private suspend fun openBankWidget(
        name: String,
        cpf: String
    ) {
        val url =
            repository.belvoWidgetUrl(
                name = name,
                cpf = cpf
            )

        _state.value =
            _state.value.copy(
                loading = false,
                needsOpenFinanceProfile =
                    false,
                openFinanceWidgetUrl = url
            )
    }

    fun dismissOpenFinanceProfile() {
        _state.value =
            _state.value.copy(
                needsOpenFinanceProfile =
                    false
            )
    }

    fun saveOpenFinanceProfile(
        name: String,
        cpf: String
    ) {
        viewModelScope.launch(screenContext) {
            _state.value =
                _state.value.copy(
                    loading = true,
                    error = null
                )

            try {
                val user: UserDto =
                    authRepository
                        .updateOpenFinanceProfile(
                            name,
                            cpf
                        )

                openBankWidget(
                    user.name
                        ?: name,
                    user.cpf
                        ?: cpf
                )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        loading = false,
                        error =
                            userFacingError(
                                e,
                                "Não foi possível salvar seus dados."
                            )
                    )
            }
        }
    }

    fun dismissBankConnection() {
        _state.value =
            _state.value.copy(
                openFinanceWidgetUrl = null
            )
    }

    fun completeBankConnection(
        linkId: String,
        institutionName: String?
    ) {
        viewModelScope.launch(screenContext) {
            _state.value =
                _state.value.copy(
                    loading = true,
                    error = null,
                    openFinanceWidgetUrl = null
                )
            try {
                repository.registerBelvoLink(
                    linkId,
                    institutionName
                )
                delay(1_000)
                repository.refresh()
                _state.value =
                    _state.value.copy(
                        loading = false,
                        lastSyncAt = currentLastSync(),
                        message =
                            "Banco conectado com sucesso. Sincronizando dados..."
                    )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        loading = false,
                        error =
                            "Não foi possível concluir a conexão com o banco. " +
                                "O serviço da instituição pode estar temporariamente " +
                                "indisponível. Tente novamente mais tarde."
                    )
            }
        }
    }

    fun updateAccountDetails(
        accountId: Int,
        institutionName: String,
        accountName: String?,
        maskedAccount: String?
    ) {
        viewModelScope.launch(screenContext) {
            try {
                repository.updateAccountDetails(
                    accountId,
                    institutionName,
                    accountName,
                    maskedAccount
                )
                _state.value =
                    _state.value.copy(
                        message =
                            "Dados da conta atualizados."
                    )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        error = e.message
                            ?: "Não foi possível atualizar a conta."
                    )
            }
        }
    }

    fun updateCardDetails(
        cardId: Int,
        bankName: String,
        brand: String,
        lastFour: String,
        nickname: String?,
        creditLimit: Double?,
        closingDay: Int?,
        dueDay: Int?
    ) {
        viewModelScope.launch(screenContext) {
            try {
                repository.updateCardDetails(
                    cardId,
                    bankName,
                    brand,
                    lastFour,
                    nickname,
                    creditLimit,
                    closingDay,
                    dueDay
                )
                _state.value =
                    _state.value.copy(
                        message =
                            "Dados do cartão atualizados."
                    )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        error = e.message
                            ?: "Não foi possível atualizar o cartão."
                    )
            }
        }
    }

    fun createCard(
        bankName: String,
        brand: String,
        lastFour: String,
        nickname: String?,
        creditLimit: Double?,
        closingDay: Int?,
        dueDay: Int?
    ) {
        viewModelScope.launch(screenContext) {
            try {
                repository.createCard(
                    bankName,
                    brand,
                    lastFour,
                    nickname,
                    creditLimit,
                    closingDay,
                    dueDay
                )
                _state.value =
                    _state.value.copy(
                        message = "Cartão salvo."
                    )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        error = e.message
                            ?: "Não foi possível salvar o cartão."
                    )
            }
        }
    }

    fun deleteCard(
        cardId: Int
    ) {
        viewModelScope.launch(screenContext) {
            try {
                repository.deleteCard(cardId)
                _state.value =
                    _state.value.copy(
                        message = "Cartão removido."
                    )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        error = e.message
                            ?: "Não foi possível remover o cartão."
                    )
            }
        }
    }


    fun createCategory(
        name: String,
        icon: String? = null,
        onCreated: (CategoryDto) -> Unit = {}
    ) {
        viewModelScope.launch(screenContext) {
            try {
                val category = repository.createCategory(name, icon)
                onCreated(category)
                _state.value = _state.value.copy(
                    message = "Categoria salva."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Não foi possível criar a categoria."
                )
            }
        }
    }

    fun updateCategory(
        categoryId: Int,
        name: String,
        icon: String? = null
    ) {
        viewModelScope.launch(screenContext) {
            try {
                repository.updateCategory(categoryId, name, icon)
                _state.value = _state.value.copy(message = "Categoria atualizada.")
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Não foi possível atualizar a categoria."
                )
            }
        }
    }

    fun deleteCategory(categoryId: Int) {
        viewModelScope.launch(screenContext) {
            try {
                repository.deleteCategory(categoryId)
                _state.value = _state.value.copy(
                    message = "Categoria excluída. As transações foram mantidas sem categoria."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Não foi possível excluir a categoria."
                )
            }
        }
    }

    fun createGoal(
        name: String,
        targetAmount: Double,
        currentAmount: Double,
        targetDate: String?
    ) {
        viewModelScope.launch(screenContext) {
            try {
                repository.createGoal(
                    name,
                    targetAmount,
                    currentAmount,
                    targetDate
                )
                _state.value = _state.value.copy(
                    message = "Meta financeira criada."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Não foi possível criar a meta."
                )
            }
        }
    }

    fun updateGoal(
        goalId: Int,
        name: String,
        targetAmount: Double,
        currentAmount: Double,
        targetDate: String?
    ) {
        viewModelScope.launch(screenContext) {
            try {
                repository.updateGoal(
                    goalId,
                    name,
                    targetAmount,
                    currentAmount,
                    targetDate
                )
                _state.value = _state.value.copy(
                    message = "Meta financeira atualizada."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Não foi possível atualizar a meta."
                )
            }
        }
    }

    fun addGoalContribution(
        goalId: Int,
        amount: Double
    ) {
        viewModelScope.launch(screenContext) {
            try {
                repository.addGoalContribution(
                    goalId,
                    amount
                )

                _state.value =
                    _state.value.copy(
                        message =
                            "Valor adicionado à meta."
                    )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        error = e.message
                            ?: "Não foi possível adicionar o valor."
                    )
            }
        }
    }

    fun deleteGoalContribution(
        contributionId: Int
    ) {
        viewModelScope.launch(screenContext) {
            try {
                repository.deleteGoalContribution(
                    contributionId
                )

                _state.value =
                    _state.value.copy(
                        message =
                            "Valor removido da meta."
                    )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        error =
                            e.message
                                ?: "Não foi possível excluir o aporte."
                    )
            }
        }
    }

    fun deleteGoal(goalId: Int) {
        viewModelScope.launch(screenContext) {
            try {
                repository.deleteGoal(goalId)
                _state.value = _state.value.copy(
                    message = "Meta financeira excluída."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Não foi possível excluir a meta."
                )
            }
        }
    }

    fun setBudget(
        categoryId: Int,
        amount: Double
    ) {
        viewModelScope.launch(screenContext) {
            try {
                repository.setBudget(categoryId, amount)
                _state.value = _state.value.copy(
                    message = "Orçamento mensal salvo."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Não foi possível salvar o orçamento."
                )
            }
        }
    }

    fun deleteBudget(categoryId: Int) {
        viewModelScope.launch(screenContext) {
            try {
                repository.deleteBudget(categoryId)
                _state.value = _state.value.copy(
                    message = "Orçamento removido."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Não foi possível remover o orçamento."
                )
            }
        }
    }

    fun updateManualTransaction(
        transactionId: Int,
        accountId: Int?,
        categoryId: Int?,
        description: String,
        amount: Double,
        type: String,
        date: String
    ) {
        viewModelScope.launch(screenContext) {
            try {
                repository.updateManualTransaction(
                    transactionId,
                    accountId,
                    categoryId,
                    description,
                    amount,
                    type,
                    date
                )
                _state.value = _state.value.copy(
                    message = "Transação atualizada.",
                    highlightedTransactionId = transactionId
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Não foi possível atualizar a transação."
                )
            }
        }
    }

    fun updateTransactionCategory(
        transactionId: Int,
        categoryId: Int?
    ) {
        viewModelScope.launch(screenContext) {
            try {
                val updatedCount =
                    repository.updateTransactionCategory(
                        transactionId,
                        categoryId
                    )

                _state.value =
                    _state.value.copy(
                        message =
                            if (updatedCount > 1) {
                                "Categoria aplicada às $updatedCount parcelas da compra."
                            } else {
                                "Categoria atualizada."
                            }
                    )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Não foi possível atualizar a categoria."
                )
            }
        }
    }

    fun disconnectAccount(accountId: Int) {
        viewModelScope.launch(screenContext) {
            try {
                repository.disconnectAccount(accountId)
                _state.value = _state.value.copy(
                    message = "Sincronização bancária pausada. O histórico foi mantido."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Não foi possível desconectar o banco."
                )
            }
        }
    }

    fun reconnectAccount(accountId: Int) {
        viewModelScope.launch(screenContext) {
            try {
                _state.value = _state.value.copy(loading = true, error = null)
                repository.reconnectAccount(accountId)
                delay(1_000)
                repository.refresh()
                _state.value = _state.value.copy(
                    loading = false,
                    lastSyncAt = currentLastSync(),
                    message = "Banco reconectado. As atualizações foram retomadas."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Não foi possível reconectar o banco."
                )
            }
        }
    }

    fun requestAccountDeleteCode(
        accountId: Int,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch(screenContext) {
            try {
                _state.value = _state.value.copy(
                    loading = true,
                    error = null
                )
                repository.requestAccountDeleteCode(accountId)
                _state.value = _state.value.copy(
                    loading = false,
                    message = "Código enviado para seu e-mail."
                )
                onSuccess()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = userFacingError(
                        e,
                        "Não foi possível enviar o código de confirmação."
                    )
                )
            }
        }
    }

    fun confirmAccountDelete(
        accountId: Int,
        code: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch(screenContext) {
            try {
                _state.value = _state.value.copy(
                    loading = true,
                    error = null
                )
                repository.confirmAccountDelete(
                    accountId,
                    code
                )
                _state.value = _state.value.copy(
                    loading = false,
                    message = "Conta excluída. As transações foram preservadas."
                )
                onSuccess()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = userFacingError(
                        e,
                        "Não foi possível excluir a conta."
                    )
                )
            }
        }
    }

    fun deleteTransactionsBulk(
        transactions: List<TransactionEntity>
    ) {
        viewModelScope.launch(screenContext) {
            try {
                val deleted =
                    repository
                        .deleteTransactionsBulk(
                            transactions
                        )

                _state.value =
                    _state.value.copy(
                        message =
                            if (deleted == 1) {
                                "1 transação excluída."
                            } else {
                                "$deleted transações excluídas."
                            }
                    )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        error =
                            e.message
                                ?: "Não foi possível excluir as transações."
                    )
            }
        }
    }

    fun deleteLoanMovements(
        loanId: String,
        transactions: List<TransactionEntity>,
        onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch(screenContext) {
            try {
                require(loanId.isNotBlank() && transactions.isNotEmpty()) {
                    "Nao ha movimentacoes de emprestimo para excluir."
                }
                require(transactions.all { isLentMovementFor(loanId, it) }) {
                    "A selecao contem transacoes que nao pertencem ao emprestimo."
                }
                require(transactions.none {
                    it.installmentGroup != null && (it.installmentTotal ?: 1) > 1
                }) {
                    "Uma movimentacao passou a fazer parte de um parcelamento. " +
                        "Remova o parcelamento antes de exclui-la junto com o emprestimo."
                }
                val selected = transactions.distinctBy { it.id }
                val deleted = repository.deleteTransactionsBulk(selected)
                check(deleted == selected.size) {
                    "Nem todas as movimentacoes vinculadas foram excluidas. Confira o extrato."
                }
                _state.value = _state.value.copy(
                    message = if (deleted == 1) "Movimentacao de emprestimo excluida."
                        else "$deleted movimentacoes de emprestimo excluidas."
                )
                onComplete(true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Nao foi possivel excluir as movimentacoes do emprestimo."
                )
                onComplete(false)
            }
        }
    }

    fun updateLoanReceipt(
        loanId: String, transaction: TransactionEntity, accountId: Int,
        amountCents: Long, paymentDate: String, onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch(screenContext) {
            try {
                val current = transactions.value.firstOrNull { it.id == transaction.id }
                    ?: error("Recebimento nao encontrado no extrato.")
                require(isLentReceiptFor(loanId, current)) {
                    "Esse lancamento nao pertence ao emprestimo selecionado."
                }
                require(current.installmentGroup == null || (current.installmentTotal ?: 1) <= 1) {
                    "Um recebimento parcelado nao pode ser alterado por esta tela."
                }
                require(amountCents > 0L && amountCents < Long.MAX_VALUE / 2L) { "Valor invalido." }
                require(accounts.value.any { it.id == accountId }) { "Banco nao encontrado no Workspace." }
                java.time.LocalDate.parse(paymentDate)
                // Preserva a descricao e o identificador do emprestimo.
                repository.updateManualTransaction(
                    current.id, accountId, current.categoryId, current.description,
                    amountCents.toDouble() / 100.0, "credit", paymentDate
                )
                _state.value = _state.value.copy(message = "Recebimento atualizado.")
                onComplete(true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Falha ao editar recebimento.")
                onComplete(false)
            }
        }
    }

    fun deleteLoanReceipt(
        loanId: String, transaction: TransactionEntity, onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch(screenContext) {
            try {
                val current = transactions.value.firstOrNull { it.id == transaction.id }
                    ?: error("Recebimento nao encontrado no extrato.")
                require(isLentReceiptFor(loanId, current)) {
                    "Esse lancamento nao pertence ao emprestimo selecionado."
                }
                require(current.installmentGroup == null || (current.installmentTotal ?: 1) <= 1) {
                    "O recebimento esta vinculado a um grupo de parcelas."
                }
                check(repository.deleteTransaction(current) == 1) {
                    "Nao foi possivel excluir somente esse recebimento."
                }
                _state.value = _state.value.copy(message = "Recebimento excluido do extrato.")
                onComplete(true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Falha ao excluir recebimento.")
                onComplete(false)
            }
        }
    }

    fun updateTransactionsCategoryBulk(
        transactions: List<TransactionEntity>,
        categoryId: Int?
    ) {
        viewModelScope.launch(screenContext) {
            try {
                val updated =
                    repository
                        .updateTransactionsCategoryBulk(
                            transactions,
                            categoryId
                        )

                _state.value =
                    _state.value.copy(
                        message =
                            if (updated == 1) {
                                "Categoria atualizada em 1 transação."
                            } else {
                                "Categoria atualizada em $updated transações."
                            }
                    )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        error =
                            e.message
                                ?: "Não foi possível alterar a categoria das transações."
                    )
            }
        }
    }

    fun updateTransactionsAccountBulk(
        transactions: List<TransactionEntity>,
        accountId: Int?
    ) {
        viewModelScope.launch(screenContext) {
            try {
                val updated = repository.updateTransactionsAccountBulk(transactions, accountId)
                _state.value = _state.value.copy(
                    message = if (updated == 1)
                        "Banco relacionado a 1 transação."
                    else
                        "Banco relacionado a $updated transações."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Não foi possível relacionar o banco às transações."
                )
            }
        }
    }

    fun deleteTransaction(
        transaction: TransactionEntity
    ) {
        viewModelScope.launch(screenContext) {
            try {
                val deleted =
                    repository.deleteTransaction(transaction)

                _state.value = _state.value.copy(
                    message =
                        if (deleted > 1) {
                            "$deleted parcelas excluídas."
                        } else {
                            "Transação excluída."
                        }
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error =
                        e.message
                            ?: "Não foi possível excluir a transação."
                )
            }
        }
    }

    fun requestDeleteAllUserDataCode(
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch(screenContext) {
            try {
                _state.value = _state.value.copy(
                    loading = true,
                    error = null
                )

                repository.requestDeleteAllUserDataCode()

                _state.value = _state.value.copy(
                    loading = false,
                    message = "Código enviado para seu e-mail."
                )

                onSuccess()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = userFacingError(
                        e,
                        "Não foi possível enviar o código."
                    )
                )
            }
        }
    }

    fun confirmDeleteAllUserData(
        code: String,
        options: com.financeapp.mobile.data.remote.UserDataDeleteOptionsRequest,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch(screenContext) {
            try {
                _state.value = _state.value.copy(
                    loading = true,
                    error = null
                )

                repository.confirmDeleteAllUserData(code, options)

                _state.value = _state.value.copy(
                    loading = false,
                    connectionId = null,
                    lastSyncAt = null,
                    message =
                        if (options.deleteAccount) "Conta excluída." else "Os dados selecionados foram apagados."
                )

                // Fecha o fluxo de confirmacao assim que a exclusao principal
                // terminou. Atualizacoes de rede posteriores nao podem manter
                // o dialogo preso em estado de carregamento.
                onSuccess()

                if (!options.deleteAccount) {
                    viewModelScope.launch(screenContext) {
                        runCatching { repository.refresh() }
                        runCatching { repository.refreshWorkspaces() }
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = userFacingError(
                        e,
                        "Não foi possível apagar os dados."
                    )
                )
            }
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch(screenContext) {
            authRepository.logout()
            onDone()
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    suspend fun askFinancialAi(
        request: com.financeapp.mobile.data.remote.FinancialAiRequest
    ): com.financeapp.mobile.data.remote.FinancialAiResponse =
        repository.askFinancialAi(request)

    suspend fun transactionAttachments(transactionId: Int) = repository.transactionAttachments(transactionId)
    suspend fun uploadTransactionAttachment(transactionId: Int, fileName: String, contentType: String, bytes: ByteArray) =
        repository.uploadTransactionAttachment(transactionId, fileName, contentType, bytes)
    suspend fun downloadTransactionAttachment(transactionId: Int, attachmentId: Int) =
        repository.downloadTransactionAttachment(transactionId, attachmentId)
    suspend fun deleteTransactionAttachment(transactionId: Int, attachmentId: Int) =
        repository.deleteTransactionAttachment(transactionId, attachmentId)

}
