package com.financeapp.mobile.data.repository

import com.financeapp.mobile.data.remote.EmailVerificationResendRequest
import com.financeapp.mobile.data.remote.OpenFinanceProfileUpdateRequest
import com.financeapp.mobile.data.remote.UserDto
import com.financeapp.mobile.data.remote.FinanceApi
import com.financeapp.mobile.data.remote.GoogleLoginRequest
import com.financeapp.mobile.data.remote.LoginRequest
import com.financeapp.mobile.data.remote.RefreshRequest
import com.financeapp.mobile.data.remote.RegisterRequest
import com.financeapp.mobile.util.SessionManager
import java.io.IOException
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: FinanceApi,
    private val session: SessionManager
) {
    suspend fun forgotPassword(email: String): String =
        api.forgotPassword(com.financeapp.mobile.data.remote.PasswordResetRequest(email.trim().lowercase())).message

    suspend fun resetPassword(email: String, code: String, password: String): String {
        val response = api.resetPassword(com.financeapp.mobile.data.remote.PasswordResetConfirm(email.trim().lowercase(), code, password))
        session.clear()
        return response.message
    }

    fun isGuest() = session.isGuest()
    fun enterGuest() = session.startGuest()
    fun requestGuestTransfer() = session.requestGuestTransfer()
    suspend fun login(
        email: String,
        password: String
    ) {
        val normalizedEmail =
            email.trim().lowercase()

        try {
            val token =
                api.login(
                    LoginRequest(
                        normalizedEmail,
                        password
                    )
                )

            session.save(
                token.accessToken,
                token.refreshToken
            )

            session.saveOfflinePasswordVerifier(
                normalizedEmail,
                password
            )
        } catch (e: IOException) {
            /*
             * Sem internet/timeout: só permite entrada se este
             * mesmo usuário já foi validado online neste aparelho.
             */
            if (
                session.canOfflinePasswordLogin(
                    normalizedEmail,
                    password
                )
            ) {
                return
            }

            throw IllegalStateException(
                "Sem internet. Faça pelo menos um login online " +
                    "neste aparelho antes de usar o acesso offline."
            )
        }
    }

    suspend fun googleLogin(idToken: String) {
        val token = api.googleLogin(GoogleLoginRequest(idToken))
        session.save(token.accessToken, token.refreshToken)
    }

    suspend fun quickLoginWithRefreshToken() {
        val refresh =
            session.refreshToken()
                ?: error(
                    "Sessão anterior não encontrada."
                )

        try {
            val token =
                api.refreshToken(
                    RefreshRequest(refresh)
                )

            session.saveRefreshedIfCurrent(refresh, token.accessToken, token.refreshToken)
        } catch (e: IOException) {
            /*
             * A biometria já autenticou a pessoa localmente.
             * Se o aparelho possui uma sessão previamente validada,
             * liberamos o acesso offline.
             */
            if (session.hasOfflineProfile()) {
                return
            }

            throw e
        }
    }

    suspend fun register(
        name: String,
        cpf: String,
        email: String,
        password: String,
        birthDate: String,
        sex: String
    ): String {
        val normalizedEmail =
            email.trim().lowercase()

        val result =
            api.register(
                RegisterRequest(
                    email =
                        normalizedEmail,
                    name = name.trim(),
                    cpf =
                        cpf.filter(
                            Char::isDigit
                        ),
                    password = password,
                    birthDate = birthDate,
                    sex = sex
                )
            )

        return result.email
    }

    suspend fun resendRegistrationLink(
        email: String
    ) {
        api.resendVerification(
            EmailVerificationResendRequest(
                email =
                    email.trim().lowercase()
            )
        )
    }

    suspend fun refreshSessionForSync() {
        val refresh =
            session.refreshToken()
                ?: return

        val token =
            api.refreshToken(
                RefreshRequest(refresh)
            )

        session.saveRefreshedIfCurrent(refresh, token.accessToken, token.refreshToken)
    }

    suspend fun updateOpenFinanceProfile(
        name: String,
        cpf: String
    ): UserDto =
        api.updateOpenFinanceProfile(
            OpenFinanceProfileUpdateRequest(
                name = name.trim(),
                cpf =
                    cpf.filter(Char::isDigit)
            )
        )

    suspend fun currentUser() = api.me()

    fun isLoggedIn(): Boolean = session.isLoggedIn()
    fun hasPersistedSession(): Boolean = session.hasPersistedSession()
    fun canQuickLogin(): Boolean = session.canQuickLogin()

    /**
     * Renova a credencial sem bloquear a entrada no app. Falha de rede e
     * timeout preservam a sessão local; 401/403 significam sessão revogada.
     */
    suspend fun refreshPersistedSession() {
        val refresh = session.refreshToken() ?: return
        val token = api.refreshToken(RefreshRequest(refresh))
        session.saveRefreshedIfCurrent(refresh, token.accessToken, token.refreshToken)
    }

    fun lockForNextOpen() = session.clearAccessOnly()

    fun logout() = session.clear()
}
