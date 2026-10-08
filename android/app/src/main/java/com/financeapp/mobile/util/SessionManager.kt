package com.financeapp.mobile.util

import android.content.Context
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs =
        context.getSharedPreferences(
            "finance_session",
            Context.MODE_PRIVATE
        )

    private fun tokenUserId(token: String?): Int? = runCatching {
        val payload = token?.split('.')?.getOrNull(1) ?: return@runCatching null
        JSONObject(String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE))).getString("sub").toInt()
    }.getOrNull()

    fun isGuest(): Boolean = prefs.getBoolean("guest_active", false)
    fun guestTransferPending(): Boolean = prefs.getBoolean("guest_transfer_pending", false)
    fun requestGuestTransfer() { prefs.edit().putBoolean("guest_transfer_pending", true).commit(); clear() }
    fun finishGuestTransfer() { prefs.edit().remove("guest_transfer_pending").commit() }
    @Synchronized fun startGuest() {
        clear()
        prefs.edit().putBoolean("guest_active", true).commit()
        scopeState.value = storedScope()
    }
    private fun standaloneOwnerId(): Int? =
        if (prefs.contains("standalone_owner_id")) prefs.getInt("standalone_owner_id", 0) else null

    @Synchronized
    fun enableStandaloneMode() {
        val owner =
            standaloneOwnerId()
                ?: (if (isGuest()) 0 else tokenUserId(accessToken()) ?: tokenUserId(refreshToken()) ?: 0)

        prefs.edit()
            .putInt("standalone_owner_id", owner)
            .putBoolean("standalone_mode", true)
            .apply()

        scopeState.value = storedScope()
    }

    fun localUserId(): Int? =
        standaloneOwnerId()
            ?: if (isGuest()) 0 else tokenUserId(accessToken()) ?: tokenUserId(refreshToken())

    private fun storedScope(): WorkspaceScope? = localUserId()?.let { id ->
        WorkspaceScope(id, prefs.getString("workspace_$id", null) ?: "default-$id")
    }

    private val scopeState = MutableStateFlow(storedScope())
    val workspaceScope = scopeState.asStateFlow()

    fun requireWorkspace(): WorkspaceScope = storedScope()
        ?: error("Entre na sua conta antes de acessar os dados financeiros")

    @Synchronized
    fun activateWorkspace(scope: WorkspaceScope) {
        check(localUserId() == scope.userId) { "A conta mudou. Entre novamente." }
        check(prefs.edit().putString("workspace_${scope.userId}", scope.workspaceId).commit()) {
            "Não foi possível salvar o workspace selecionado"
        }
        scopeState.value = scope
    }

    @Synchronized
    fun captureOperation(requested: WorkspaceScope? = null): WorkspaceOperation {
        val current = requireWorkspace()
        val selected = requested ?: current
        check(selected.userId == current.userId) { "A sessão mudou; a fila foi preservada" }
        return WorkspaceOperation(selected, accessToken())
    }

    @Synchronized
    fun saveRefreshedIfCurrent(previousRefresh: String, access: String, refresh: String) {
        if (refreshToken() == previousRefresh) save(access, refresh)
    }

    fun accessToken(): String? =
        prefs.getString(
            "access_token",
            null
        )

    fun refreshToken(): String? =
        prefs.getString(
            "refresh_token",
            null
        )

    fun hasPersistedSession(): Boolean =
        isGuest() || !refreshToken().isNullOrBlank() || !accessToken().isNullOrBlank()

    fun hasCachedProfilePhoto(): Boolean = prefs.contains("profile_photo_cache")

    fun cachedProfilePhoto(): String? =
        prefs.getString("profile_photo_cache", null)?.takeUnless { it == "__NONE__" }

    fun cacheProfilePhoto(value: String?) {
        prefs.edit().putString("profile_photo_cache", value ?: "__NONE__").apply()
    }

    @Synchronized
    fun save(
        accessToken: String,
        refreshToken: String
    ) {
        if (localUserId() != tokenUserId(accessToken)) {
            prefs.edit().remove("offline_email").remove("offline_password_salt")
                .remove("offline_password_hash").remove("profile_photo_cache").apply()
        }
        prefs.edit()
            .remove("guest_active")
            .putString(
                "access_token",
                accessToken
            )
            .putString(
                "refresh_token",
                refreshToken
            )
            .putBoolean(
                "quick_login_enabled",
                true
            )
            .putBoolean(
                "offline_profile_enabled",
                true
            )
            .apply()
        scopeState.value = storedScope()
    }

    /**
     * Salva somente um verificador PBKDF2 da senha.
     * A senha original nunca é persistida no aparelho.
     */
    fun saveOfflinePasswordVerifier(
        email: String,
        password: String
    ) {
        val normalizedEmail =
            email.trim().lowercase()

        val salt =
            ByteArray(16).also {
                SecureRandom().nextBytes(it)
            }

        val hash =
            derivePasswordHash(
                password = password,
                salt = salt
            )

        prefs.edit()
            .putString(
                "offline_email",
                normalizedEmail
            )
            .putString(
                "offline_password_salt",
                Base64.getEncoder()
                    .encodeToString(salt)
            )
            .putString(
                "offline_password_hash",
                Base64.getEncoder()
                    .encodeToString(hash)
            )
            .putBoolean(
                "offline_profile_enabled",
                true
            )
            .apply()
    }

    fun canOfflinePasswordLogin(
        email: String,
        password: String
    ): Boolean {
        if (!hasOfflineProfile()) {
            return false
        }

        val normalizedEmail =
            email.trim().lowercase()

        val storedEmail =
            prefs.getString(
                "offline_email",
                null
            ) ?: return false

        if (storedEmail != normalizedEmail) {
            return false
        }

        val saltText =
            prefs.getString(
                "offline_password_salt",
                null
            ) ?: return false

        val hashText =
            prefs.getString(
                "offline_password_hash",
                null
            ) ?: return false

        return runCatching {
            val salt =
                Base64.getDecoder()
                    .decode(saltText)

            val expected =
                Base64.getDecoder()
                    .decode(hashText)

            val actual =
                derivePasswordHash(
                    password = password,
                    salt = salt
                )

            constantTimeEquals(
                expected,
                actual
            )
        }.getOrDefault(false)
    }

    fun hasOfflineProfile(): Boolean =
        prefs.getBoolean(
            "offline_profile_enabled",
            false
        ) &&
            !refreshToken().isNullOrBlank()

    @Synchronized
    fun clear() {
        val remembered = prefs.all.filterKeys { it.startsWith("workspace_") || it == "guest_transfer_pending" || it == "standalone_owner_id" || it == "standalone_mode" }
        val editor = prefs.edit().clear()
        remembered.forEach { (key, value) -> when (value) { is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key,value); is Int -> editor.putInt(key,value) } }
        editor.apply()
        scopeState.value = null
    }

    fun clearAccessOnly() {
        prefs.edit()
            .remove("access_token")
            .apply()
    }

    fun isLoggedIn(): Boolean =
        (isGuest() || !accessToken().isNullOrBlank())

    fun canQuickLogin(): Boolean =
        prefs.getBoolean(
            "quick_login_enabled",
            false
        ) &&
            hasOfflineProfile()

    private fun derivePasswordHash(
        password: String,
        salt: ByteArray
    ): ByteArray {
        val spec =
            PBEKeySpec(
                password.toCharArray(),
                salt,
                120_000,
                256
            )

        return try {
            SecretKeyFactory
                .getInstance(
                    "PBKDF2WithHmacSHA256"
                )
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun constantTimeEquals(
        first: ByteArray,
        second: ByteArray
    ): Boolean {
        if (first.size != second.size) {
            return false
        }

        var diff = 0

        for (index in first.indices) {
            diff =
                diff or
                    (
                        first[index].toInt() xor
                            second[index].toInt()
                        )
        }

        return diff == 0
    }
}
