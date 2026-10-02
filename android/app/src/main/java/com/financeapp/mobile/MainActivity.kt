package com.financeapp.mobile

import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.financeapp.mobile.ui.auth.AuthScreen
import com.financeapp.mobile.ui.auth.AuthViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import com.financeapp.mobile.ui.home.WorkspaceViewModel
import com.financeapp.mobile.ui.home.WorkspaceManagerDialog
import com.financeapp.mobile.ui.home.HomeScreen
import com.financeapp.mobile.ui.home.HomeViewModel
import com.financeapp.mobile.ui.theme.FinanceTheme
import com.financeapp.mobile.ui.legal.LegalAcceptanceStore
import com.financeapp.mobile.ui.legal.LegalConsentDialog
import com.financeapp.mobile.ui.onboarding.OnboardingScreen
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private var appUnlocked by mutableStateOf(true)
    private var appLockEnabledState by mutableStateOf(false)
    private var promptShowing = false

    private fun lockPrefs() = getSharedPreferences("financeapp_security", MODE_PRIVATE)
    private fun isAppLockEnabled(): Boolean = lockPrefs().getBoolean("app_lock_enabled", false)
    private fun setAppLockEnabled(enabled: Boolean) {
        // Atualiza a UI no mesmo toque; persistencia ocorre sem bloquear a tela.
        appLockEnabledState = enabled
        lockPrefs().edit().putBoolean("app_lock_enabled", enabled).apply()
        if (!enabled) appUnlocked = true
        else requestAppUnlock()
    }

    private fun requestAppUnlock() {
        if (!isAppLockEnabled() || promptShowing) return
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            lockPrefs().edit().putBoolean("app_lock_enabled", false).apply()
            appUnlocked = true
            return
        }
        promptShowing = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptShowing = false
                appUnlocked = true
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                promptShowing = false
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquear FinanceApp")
                .setSubtitle("Confirme sua identidade para acessar seus dados financeiros")
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }

    override fun onResume() {
        super.onResume()
        if (isAppLockEnabled() && !appUnlocked) requestAppUnlock()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && isAppLockEnabled()) appUnlocked = false
    }

    private val credentialManager by lazy {
        CredentialManager.create(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Em um novo processo, um app protegido começa bloqueado.
        appLockEnabledState = isAppLockEnabled()
        appUnlocked = !appLockEnabledState

        setContent {
            var themeMode by remember { mutableStateOf("SYSTEM") }
            FinanceTheme(themeMode = themeMode) {
                val onboardingPrefs = remember { getSharedPreferences("financeapp_onboarding", MODE_PRIVATE) }
                var showOnboarding by remember {
                    mutableStateOf(!onboardingPrefs.getBoolean("v1_done", false))
                }

                if (showOnboarding) {
                    OnboardingScreen(
                        onFinish = {
                            onboardingPrefs.edit().putBoolean("v1_done", true).apply()
                            showOnboarding = false
                        },
                        onSkip = {
                            onboardingPrefs.edit().putBoolean("v1_done", true).apply()
                            showOnboarding = false
                        }
                    )
                } else {
                var loggedIn by remember { mutableStateOf(false) }
                var authStartRegister by remember { mutableStateOf(false) }
                var legalAcceptedForThisVersion by remember { mutableStateOf(LegalAcceptanceStore.acceptedCurrent(this@MainActivity)) }

                val authViewModel: AuthViewModel = hiltViewModel()
                val authState by authViewModel.state.collectAsState()

                LaunchedEffect(authState.authenticated) {
                    loggedIn = authState.authenticated
                }

                var showRecovery by remember { mutableStateOf(false) }
                val recoveryState by authViewModel.recovery.collectAsState()
                if (showRecovery && !loggedIn) {
                    com.financeapp.mobile.ui.auth.PasswordRecoveryDialog(recoveryState,
                        onDismiss = { showRecovery = false; authViewModel.clearRecovery() },
                        onRequest = authViewModel::requestPasswordCode,
                        onConfirm = authViewModel::confirmPassword)
                }
                if (loggedIn && !legalAcceptedForThisVersion) {
                    LegalConsentDialog(
                        onAccept = { LegalAcceptanceStore.record(this@MainActivity); legalAcceptedForThisVersion = true },
                        onDismiss = { }
                    )
                }
                if (!loggedIn) {
                    AuthScreen(
                        state = authState,
                        initialRegisterMode = authStartRegister,
                        onLogin = authViewModel::login,
                        onGuest = authViewModel::enterGuest,
                        onForgotPassword = { authViewModel.clearRecovery(); showRecovery = true },
                        onRegister = authViewModel::register,
                        onGoogleSignIn = {
                            launchGoogleSignIn(authViewModel)
                        },
                        onBiometricSuccess = {
                            authViewModel.biometricLogin()
                        },
                        onResendRegistrationLink =
                            authViewModel::resendRegistrationLink,
                        onCloseEmailVerificationNotice =
                            authViewModel::closeEmailVerificationNotice
                    )
                } else {
                    val workspaceViewModel: WorkspaceViewModel = hiltViewModel()
                    val activeWorkspace by workspaceViewModel.active.collectAsState()
                    val workspaces by workspaceViewModel.workspaces.collectAsState()
                    val workspaceState by workspaceViewModel.state.collectAsState()
                    var showWorkspaces by remember { mutableStateOf(false) }
                    LaunchedEffect(activeWorkspace?.userId) { workspaceViewModel.refresh() }
                    var showGuestTransfer by remember { mutableStateOf(false) }
                    LaunchedEffect(activeWorkspace?.userId) {
                        showGuestTransfer = activeWorkspace?.userId?.let { it > 0 } == true && workspaceViewModel.guestTransferPending()
                    }
                    if (showGuestTransfer) {
                        com.financeapp.mobile.ui.home.GuestTransferDialog(workspaceState,
                            onDismiss={showGuestTransfer=false},
                            onTransfer={workspaceViewModel.transferGuest { showGuestTransfer=false }})
                    }
                    val workspaceName = workspaces.firstOrNull { it.id == activeWorkspace?.workspaceId }?.name ?: "Workspace"

                    if (activeWorkspace != null) {
                    key(activeWorkspace) {
                    val activity = this@MainActivity
                    val homeOwner = remember {
                        object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                            override val viewModelStore = ViewModelStore()
                            override val defaultViewModelProviderFactory = activity.defaultViewModelProviderFactory
                            override val defaultViewModelCreationExtras = activity.defaultViewModelCreationExtras
                        }
                    }
                    DisposableEffect(homeOwner) { onDispose { homeOwner.viewModelStore.clear() } }
                    val homeViewModel: HomeViewModel = hiltViewModel(homeOwner)
                    val homeState by homeViewModel.state.collectAsState()
                    val accounts by homeViewModel.accounts.collectAsState()
                    val transactions by homeViewModel.transactions.collectAsState()
                    val categories by homeViewModel.categories.collectAsState()
                    val cards by homeViewModel.cards.collectAsState()
                    val budgets by homeViewModel.budgets.collectAsState()
                    val goals by homeViewModel.goals.collectAsState()
                    val goalContributions by
                        homeViewModel.goalContributions.collectAsState()

                    LaunchedEffect(authState.authenticated) {
                        if (authState.authenticated) {
                            homeViewModel.restoreAfterLogin()
                        }
                    }

                    HomeScreen(
                        workspaceId = activeWorkspace?.workspaceId ?: "guest-local",
                        workspaceName = if (activeWorkspace?.userId == 0) "Visitante" else workspaceName,
                        guest = activeWorkspace?.userId == 0,
                        guestFrozen = workspaceViewModel.guestTransferFrozen(),
                        onGuestRegister = { authStartRegister = true; authViewModel.leaveGuestForRegistration() },
                        onGuestLogin = { authStartRegister = false; authViewModel.leaveGuestForRegistration() },
                        onCreateGuestAccount = homeViewModel::createGuestAccount,
                        onCreateManualAccount = homeViewModel::createManualAccount,
                        onDeleteGuestAccount = homeViewModel::deleteGuestAccount,
                        onManageWorkspaces = { workspaceViewModel.clearError(); showWorkspaces = true; workspaceViewModel.refresh() },
                        onUpdateProfilePhoto = homeViewModel::updateProfilePhoto,
                        onLoadPersonalProfile = homeViewModel::currentUserProfile,
                        onUpdatePersonalProfile = homeViewModel::updatePersonalProfile,
                        onUseGoogleProfilePhoto = homeViewModel::useGoogleProfilePhoto,
                        onThemeModeChange = { themeMode = it },
                        onShowOnboarding = { showOnboarding = true },
                        appLockEnabled = appLockEnabledState,
                        onAppLockChange = { enabled -> setAppLockEnabled(enabled) },
                        state = if (activeWorkspace?.userId == 0) homeState.copy(pendingSyncCount=0,offlineSyncing=false) else homeState,
                        workspaces = workspaces,
                        accounts = accounts,
                        transactions = transactions,
                        categories = categories,
                        cards = cards,
                        budgets = budgets,
                        goals = goals,
                        goalContributions =
                            goalContributions,
                        onRefresh = homeViewModel::refresh,
                        onConnectBank = homeViewModel::connectBank,
                        onDismissBankConnection =
                            homeViewModel::dismissBankConnection,
                        onCompleteBankConnection =
                            homeViewModel::completeBankConnection,
                        onDismissOpenFinanceProfile =
                            homeViewModel::dismissOpenFinanceProfile,
                        onSaveOpenFinanceProfile =
                            homeViewModel::saveOpenFinanceProfile,
                        onSync = homeViewModel::syncNow,
                        onExportBackup = homeViewModel::exportBackupJson,
                        onRestoreBackup = homeViewModel::restoreBackupJson,
                        onCreateManual = homeViewModel::createManual,
                        onCreateCard = homeViewModel::createCard,
                        onPayCardInvoice = homeViewModel::payCardInvoice,
                        onDeleteCard = homeViewModel::deleteCard,
                        onUpdateCardDetails =
                            homeViewModel::updateCardDetails,
                        onUpdateAccountDetails =
                            homeViewModel::updateAccountDetails,
                        onCreateCategory = homeViewModel::createCategory,
                        onUpdateCategory = homeViewModel::updateCategory,
                        onDeleteCategory = homeViewModel::deleteCategory,
                        onSetBudget = homeViewModel::setBudget,
                        onDeleteBudget = homeViewModel::deleteBudget,
                        onCreateGoal = homeViewModel::createGoal,
                        onUpdateGoal = homeViewModel::updateGoal,
                        onAddGoalContribution =
                            homeViewModel::addGoalContribution,
                        onDeleteGoalContribution =
                            homeViewModel::deleteGoalContribution,
                        onDeleteGoal = homeViewModel::deleteGoal,
                        onUpdateManualTransaction = homeViewModel::updateManualTransaction,
                        onUpdateTransactionCategory = homeViewModel::updateTransactionCategory,
                        onListTransactionAttachments = homeViewModel::transactionAttachments,
                        onUploadTransactionAttachment = homeViewModel::uploadTransactionAttachment,
                        onDownloadTransactionAttachment = homeViewModel::downloadTransactionAttachment,
                        onDeleteTransactionAttachment = homeViewModel::deleteTransactionAttachment,
                        onDeleteTransaction = { transaction ->
                            homeViewModel.deleteTransaction(transaction)
                        },
                        onDeleteTransactionsBulk =
                            homeViewModel::deleteTransactionsBulk,
                        onUpdateTransactionsCategoryBulk =
                            homeViewModel::updateTransactionsCategoryBulk,
                        onUpdateTransactionsAccountBulk =
                            homeViewModel::updateTransactionsAccountBulk,
                        onDisconnectAccount = homeViewModel::disconnectAccount,
                        onReconnectAccount = homeViewModel::reconnectAccount,
                        onRequestAccountDeleteCode =
                            homeViewModel::requestAccountDeleteCode,
                        onConfirmAccountDelete =
                            homeViewModel::confirmAccountDelete,
                        onRequestDeleteAllUserDataCode =
                            homeViewModel::requestDeleteAllUserDataCode,
                        onConfirmDeleteAllUserData = { code, options, done ->
                            homeViewModel.confirmDeleteAllUserData(code, options) {
                                if (!options.deleteAccount) {
                                    workspaceViewModel.refresh()
                                    if (activeWorkspace?.workspaceId in options.workspaceIds) {
                                        workspaces.firstOrNull { it.archivedAt == null && it.id !in options.workspaceIds }?.id?.let { fallbackId ->
                                            workspaceViewModel.select(fallbackId) { done() }
                                        } ?: done()
                                    } else done()
                                } else done()
                            }
                        },
                        onLogout = {
                            homeViewModel.logout {
                                authViewModel.resetAfterLogout()
                                authStartRegister = false
                                loggedIn = false
                            }
                        },
                        onAskFinancialAi = homeViewModel::askFinancialAi,
                        onLoadForecastState = homeViewModel::forecastState,
                        onSaveForecastState = homeViewModel::saveForecastState,
                        onClearMessage = homeViewModel::clearMessage
                    )
                    }
                    }
                    if (showWorkspaces) WorkspaceManagerDialog(
                        workspaces = workspaces, activeId = activeWorkspace?.workspaceId, state = workspaceState,
                        onDismiss = { showWorkspaces = false }, onRefresh = workspaceViewModel::refresh,
                        onClearError = workspaceViewModel::clearError, onSelect = workspaceViewModel::select,
                        onCreate = workspaceViewModel::create, onEdit = workspaceViewModel::edit,
                        onArchive = workspaceViewModel::archive, onRestore = workspaceViewModel::restore,
                        onGuestTransfer = if (workspaceViewModel.guestTransferPending()) ({ showWorkspaces=false; showGuestTransfer=true }) else null,
                        onDeleteCode = workspaceViewModel::requestDelete, onConfirmDelete = workspaceViewModel::confirmDelete,
                        onDeleteCodeBatch = workspaceViewModel::requestDeleteBatch, onConfirmDeleteBatch = workspaceViewModel::confirmDeleteBatch
                    )
                }
                }
            }
        }
    }

    private fun launchGoogleSignIn(
        viewModel: AuthViewModel
    ) {
        lifecycleScope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(
                    serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
                ).build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build()

                val result = credentialManager.getCredential(
                    context = this@MainActivity,
                    request = request
                )

                val credential = result.credential

                if (
                    credential is CustomCredential &&
                    credential.type ==
                    GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleCredential =
                        GoogleIdTokenCredential.createFrom(credential.data)

                    viewModel.googleLogin(googleCredential.idToken)
                }
            } catch (e: Exception) {
                viewModel.setError(
                    "Não foi possível entrar com Google: " +
                        (e.message ?: e::class.java.simpleName)
                )
            }
        }
    }
}
