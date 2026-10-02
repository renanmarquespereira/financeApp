package com.financeapp.mobile.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeapp.mobile.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val authenticated: Boolean = false,
    val biometricQuickLoginAvailable: Boolean = false,
    val offlineAccess: Boolean = false,
    val awaitingEmailVerification: Boolean = false,
    val verificationEmail: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: AuthRepository
) : ViewModel() {

    private fun userFacingError(
        error: Exception,
        fallback: String
    ): String {
        if (error is retrofit2.HttpException) {
            val raw =
                runCatching {
                    error.response()
                        ?.errorBody()
                        ?.string()
                }.getOrNull()

            if (!raw.isNullOrBlank()) {
                val detail =
                    runCatching {
                        org.json.JSONObject(raw)
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


    private val _state = MutableStateFlow(
        AuthUiState(
            // Offline-first: uma sessão previamente validada abre o app
            // imediatamente. A renovação do token ocorre em segundo plano.
            authenticated = repository.hasPersistedSession(),
            biometricQuickLoginAvailable = false,
            offlineAccess = repository.hasPersistedSession() && !repository.isLoggedIn()
        )
    )
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        if (repository.hasPersistedSession() && !repository.isGuest()) {
            viewModelScope.launch {
                try {
                    repository.refreshPersistedSession()
                    _state.value = _state.value.copy(offlineAccess = false)
                } catch (e: retrofit2.HttpException) {
                    if (e.code() == 401 || e.code() == 403) {
                        repository.logout()
                        _state.value = AuthUiState(authenticated = false)
                    }
                    // Outros erros HTTP não expulsam o usuário do cache local.
                } catch (_: java.io.IOException) {
                    _state.value = _state.value.copy(offlineAccess = true)
                } catch (_: Exception) {
                    // Falha temporária de renovação não bloqueia a abertura local.
                }
            }
        }
    }

    private val recoveryMutable = MutableStateFlow(PasswordRecoveryState())
    val recovery = recoveryMutable.asStateFlow()
    fun clearRecovery() { if (!recoveryMutable.value.busy) recoveryMutable.value = PasswordRecoveryState() }
    fun requestPasswordCode(email: String) = recoveryAction {
        val message = repository.forgotPassword(email)
        recoveryMutable.value = PasswordRecoveryState(sent = true, message = message)
    }
    fun confirmPassword(email: String, code: String, password: String) = recoveryAction {
        val message = repository.resetPassword(email, code, password)
        _state.value = AuthUiState()
        recoveryMutable.value = PasswordRecoveryState(complete = true, message = message)
    }
    private fun recoveryAction(block: suspend () -> Unit) {
        if (recoveryMutable.value.busy) return
        recoveryMutable.value = recoveryMutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { recoveryMutable.value = recoveryMutable.value.copy(error = userFacingError(e, "Não foi possível concluir. Tente novamente.")) }
            finally { recoveryMutable.value = recoveryMutable.value.copy(busy = false) }
        }
    }

    fun enterGuest() {
        repository.enterGuest()
        _state.value = AuthUiState(authenticated = true)
    }
    fun leaveGuestForRegistration() {
        repository.requestGuestTransfer()
        _state.value = AuthUiState()
    }
    fun login(email: String, password: String) = runAction {
        repository.login(email.trim(), password)
    }

    fun register(
        name: String,
        cpf: String,
        email: String,
        password: String,
        birthDate: String,
        sex: String
    ) {
        viewModelScope.launch {
            _state.value =
                _state.value.copy(
                    loading = true,
                    error = null
                )

            try {
                repository.register(
                    name = name.trim(),
                    cpf = cpf,
                    email = email.trim(),
                    password = password,
                    birthDate = birthDate,
                    sex = sex
                )

                // Beta/test mode: cadastro já é efetivado no servidor.
                // Faz o primeiro login imediatamente, sem etapa de e-mail.
                repository.login(
                    email.trim(),
                    password
                )

                _state.value = AuthUiState(
                    authenticated = true,
                    biometricQuickLoginAvailable = true
                )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        loading = false,
                        error = userFacingError(
                            e,
                            "Não foi possível iniciar o cadastro."
                        )
                    )
            }
        }
    }

    fun resendRegistrationLink() {
        val email =
            _state.value.verificationEmail
                ?: return

        viewModelScope.launch {
            _state.value =
                _state.value.copy(
                    loading = true,
                    error = null
                )

            try {
                repository
                    .resendRegistrationLink(
                        email
                    )

                _state.value =
                    _state.value.copy(
                        loading = false
                    )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        loading = false,
                        error = userFacingError(
                            e,
                            "Não foi possível reenviar o e-mail de confirmação."
                        )
                    )
            }
        }
    }

    fun closeEmailVerificationNotice() {
        _state.value =
            _state.value.copy(
                loading = false,
                error = null,
                awaitingEmailVerification =
                    false,
                verificationEmail = null
            )
    }

    fun googleLogin(idToken: String) = runAction {
        repository.googleLogin(idToken)
    }

    fun setError(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    fun biometricLogin() = runAction {
        repository.quickLoginWithRefreshToken()
    }

    fun resetAfterLogout() {
        _state.value = AuthUiState(
            authenticated = false,
            biometricQuickLoginAvailable = false
        )
    }

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)

            try {
                block()
                _state.value = AuthUiState(
                    authenticated = true,
                    biometricQuickLoginAvailable = true
                )
            } catch (e: Exception) {
                _state.value =
                    AuthUiState(
                        authenticated = false,
                        biometricQuickLoginAvailable =
                            repository.canQuickLogin(),
                        error =
                            userFacingError(
                                e,
                                "Falha ao autenticar"
                            )
                    )
            }
        }
    }
}

data class PasswordRecoveryState(val busy: Boolean = false, val sent: Boolean = false, val complete: Boolean = false, val message: String? = null, val error: String? = null)
