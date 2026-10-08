package com.financeapp.mobile.data.deletion

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.financeapp.mobile.BuildConfig
import com.financeapp.mobile.data.local.AppDatabase
import com.financeapp.mobile.data.local.PendingSyncOperationEntity
import com.financeapp.mobile.util.SessionManager
import com.financeapp.mobile.util.WorkspaceScope
import com.google.gson.Gson
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val SECURITY_PREFS = "finance_data_deletion_security"
private const val CLEANUP = "LOCAL_DELETE_CLEANUP"

data class DeletionEmailSettings(val email: String, val emailVerified: Boolean, val serviceUrl: String)
data class DeletionChallenge(val id: String, val hash: String, val email: String, val url: String,
    val expiresAt: Long, val resendAt: Long)
private data class DeletionCleanup(val preferences: List<LocalPreference>, val files: List<String>,
    val nextWorkspaceId: String?, val userId: Int, val receiptId: String)

@Singleton
class LocalDataDeletionRepository @Inject constructor(
    private val database: AppDatabase,
    private val session: SessionManager,
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val mutex = Mutex()
    private val prefs: SharedPreferences get() = context.getSharedPreferences(SECURITY_PREFS, Context.MODE_PRIVATE)
    // Deliberately separate from the finance API interceptor, which blocks ALL guest requests.
    // This client can reach only the email authorization endpoints, not financial sync.
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()

    fun emailSettings(): DeletionEmailSettings {
        val old = context.getSharedPreferences("finance_session", Context.MODE_PRIVATE).getString("offline_email", "").orEmpty()
        return DeletionEmailSettings(prefs.getString("email", old).orEmpty(), prefs.getBoolean("verified", false),
            prefs.getString("service_url", BuildConfig.API_BASE_URL).orEmpty())
    }
    @Synchronized private fun deviceSecret(): String {
        prefs.getString("device_secret", null)?.let { return it }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        check(prefs.edit().putString("device_secret", secret).commit()) { "N\u00e3o foi poss\u00edvel salvar a configura\u00e7\u00e3o de seguran\u00e7a." }
        return secret
    }
    fun normalizeUrl(raw: String): String {
        val value = raw.trim().trimEnd('/') + "/"
        val u = runCatching { URI(value) }.getOrElse { error("Endere\u00e7o do backend inv\u00e1lido.") }
        require(u.host != null && u.userInfo == null && u.query == null && u.fragment == null) { "Informe a URL do backend, sem senha ou par\u00e2metros." }
        val host = u.host.lowercase()
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        val ipv4 = octets.size == 4 && octets.all { it in 0..255 } && host.matches(Regex("[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+"))
        val privateHost = host == "localhost" || (ipv4 && (octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) || (octets[0] == 172 && octets[1] in 16..31)))
        require(u.scheme == "https" || (BuildConfig.DEBUG && u.scheme == "http" && privateHost)) {
            "Use HTTPS para confirmar por e-mail. HTTP local s\u00f3 \u00e9 permitido na compila\u00e7\u00e3o de teste."
        }
        return value
    }
    private suspend fun post(url: String, path: String, data: Any): com.google.gson.JsonObject = withContext(Dispatchers.IO) {
        val body = gson.toJson(data).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url + "user-data/local-deletion/" + path).post(body).build()
        val result = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                if (!response.isSuccessful) {
                    val detail = json?.get("detail")?.takeIf { it.isJsonPrimitive }?.asString
                    error(when(response.code) {
                        404 -> "O backend ainda n\u00e3o tem este patch. Atualize e reinicie o backend. Nenhum dado foi apagado."
                        401, 403 -> detail ?: "Confirma\u00e7\u00e3o n\u00e3o autorizada."
                        429 -> detail ?: "Limite de tentativas atingido. Aguarde antes de reenviar."
                        503 -> detail ?: "Envio de e-mail indispon\u00edvel. Verifique o SMTP no backend."
                        else -> detail ?: "O servidor recusou a confirma\u00e7\u00e3o (HTTP ${response.code}). Nenhum dado foi apagado."
                    })
                }
                json ?: error("Resposta de confirma\u00e7\u00e3o inv\u00e1lida. Nenhum dado foi apagado.")
            }
        } catch (e: java.io.IOException) {
            error("N\u00e3o foi poss\u00edvel acessar o backend de e-mail. Confira a conex\u00e3o e o endere\u00e7o em Configurar confirma\u00e7\u00e3o. Nenhum dado foi apagado.")
        }
        currentCoroutineContext().ensureActive()
        result
    }
    suspend fun snapshot(userKey: String = "local", frozenUserKeys: Set<String>? = null): DeletionSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverLocked()
            database.withTransaction { readSnapshot(userKey, frozenUserKeys) }
        }
    }
    private fun query(sql: String, args: Array<Any>): List<LocalRow> =
        database.openHelper.readableDatabase.query(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) add(LocalRow(c.columnNames.mapIndexed { i, name -> name to if(c.isNull(i)) null else c.getString(i) }.toMap()))
            }
        }
    private fun readSnapshot(userKey: String, frozenUserKeys: Set<String>? = null): DeletionSnapshot {
        val current = session.requireWorkspace()
        val workspaces = query("SELECT * FROM workspaces_local WHERE userId = ? ORDER BY id", arrayOf(current.userId))
        val tables = DeletionPlanner.tableNames.associateWith { table ->
            query("SELECT * FROM $table WHERE userId = ?" + if(table == "pending_sync_operations") " AND entityType != '$CLEANUP'" else "", arrayOf(current.userId))
        }
        val cachedEmail = context.getSharedPreferences("finance_session", Context.MODE_PRIVATE).getString("offline_email", null)
        val userKeys = frozenUserKeys ?: setOfNotNull("local", userKey.takeIf { it.isNotBlank() }, cachedEmail?.takeIf { it.isNotBlank() })
        val collected = mutableListOf<LocalPreference>()
        fun add(file: String, key: String) {
            val value = context.getSharedPreferences(file, Context.MODE_PRIVATE).all[key] ?: return
            when(value) {
                is String -> collected += LocalPreference(file, key, "string", value)
                is Int -> collected += LocalPreference(file, key, "int", value.toString())
                is Set<*> -> collected += LocalPreference(file, key, "set", gson.toJson(value.map { it.toString() }.sorted()))
            }
        }
        for (ws in workspaces) {
            val wid = requireNotNull(ws["id"])
            add("forecast_sync_v3", "${current.userId}:$wid")
            for (uk in userKeys) {
                if(workspaces.count { it["name"] == ws["name"] } == 1) {
                    val rulesFile = "financeapp_category_rules_${uk}_${ws["name"]}"
                    if('/' !in rulesFile && '\\' !in rulesFile)
                        context.getSharedPreferences(rulesFile, Context.MODE_PRIVATE).all.keys.forEach { add(rulesFile, it) }
                }
                for (suffix in setOf(wid, ws["name"].orEmpty())) {
                    add("forecast_scenarios", "forecast_scenarios_${uk}_$suffix")
                    add("forecast_scenarios", "forecast_deleted_scenarios_${uk}_$suffix")
                }
                for (suffix in setOf("${uk}_workspace_$wid", "${uk}_${ws["name"]}")) {
                    add("ai_plans", "plans_$suffix"); add("ai_plans", "deleted_$suffix")
                }
                add("financeapp_debts", "financeapp_debts_v1_${uk}_$wid")
                add("financeapp_debts", "financeapp_deleted_debts_v1_${uk}_$wid")
            }
        }
        return DeletionSnapshot(current.userId, current.workspaceId, workspaces, tables,
            collected.distinctBy { it.file to it.key }, userKeys)
    }
    suspend fun requestCode(plan: DeletionPlan, email: String, serviceUrl: String): DeletionChallenge {
        require(plan.total > 0) { "Nenhum registro encontrado para a sele\u00e7\u00e3o." }
        val address = email.trim().lowercase()
        require(android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) { "Informe um e-mail v\u00e1lido." }
        val settings = emailSettings()
        require(!settings.emailVerified || address == settings.email.trim().lowercase()) { "Use o e-mail j\u00e1 verificado neste aparelho." }
        val url = normalizeUrl(serviceUrl)
        require(!settings.emailVerified || url == normalizeUrl(settings.serviceUrl)) { "Use o backend de confirma\u00e7\u00e3o j\u00e1 verificado neste aparelho." }
        val fresh = snapshot(frozenUserKeys = plan.snapshot.userKeys)
        require(DeletionPlanner.snapshotHash(fresh) == plan.basisHash) { "Os dados mudaram. Volte e gere outra pr\u00e9via antes de enviar o c\u00f3digo." }
        val json = post(url, "code", mapOf("device_secret" to deviceSecret(), "email" to address,
            "plan_hash" to plan.planHash, "operation" to plan.operation, "counts" to plan.counts))
        require(json.get("plan_hash")?.asString == plan.planHash) { "O c\u00f3digo recebido n\u00e3o corresponde \u00e0 sele\u00e7\u00e3o." }
        val id = json.get("challenge_id")?.asString ?: error("Confirma\u00e7\u00e3o sem identificador.")
        require(id.matches(Regex("[a-f0-9-]{36}")))
        check(prefs.edit().putString("email", address).putString("service_url", url).commit())
        val now = android.os.SystemClock.elapsedRealtime()
        return DeletionChallenge(id, plan.planHash, address, url, now + 300_000L, now + 60_000L)
    }
    suspend fun confirm(plan: DeletionPlan, challenge: DeletionChallenge, code: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(code.matches(Regex("[0-9]{4}"))) { "Digite os quatro d\u00edgitos." }
            require(challenge.hash == plan.planHash) { "Sele\u00e7\u00e3o alterada. Solicite outro c\u00f3digo." }
            require(android.os.SystemClock.elapsedRealtime() < challenge.expiresAt) { "C\u00f3digo expirado. Reenvie para continuar." }
            val current = session.requireWorkspace()
            require(current.userId == plan.snapshot.userId && current.workspaceId == plan.snapshot.activeWorkspaceId) { "O workspace mudou. Cancele e gere outra pr\u00e9via." }
            val response = post(challenge.url, "confirm", mapOf("device_secret" to deviceSecret(),
                "challenge_id" to challenge.id, "plan_hash" to plan.planHash, "code" to code))
            require(response.get("authorized")?.asBoolean == true && response.get("challenge_id")?.asString == challenge.id && response.get("plan_hash")?.asString == plan.planHash) {
                "O servidor n\u00e3o autorizou esta exclus\u00e3o. Nenhum dado foi apagado."
            }
            check(prefs.edit().putString("email", challenge.email).putBoolean("verified", true).commit())
            currentCoroutineContext().ensureActive()
            // The OTP has now been consumed by the server; do not ever cache/reuse an authorization.
            database.withTransaction {
                val fresh = readSnapshot("local", plan.snapshot.userKeys)
                require(DeletionPlanner.snapshotHash(fresh) == plan.basisHash) { "Os dados mudaram durante a confirma\u00e7\u00e3o. Nenhum dado foi apagado. Gere uma nova pr\u00e9via." }
                val db = database.openHelper.writableDatabase
                plan.statements.forEach { command -> db.execSQL(command.sql, command.args.toTypedArray()) }
                val cleanup = DeletionCleanup(plan.preferenceWrites, plan.attachmentFiles, plan.nextWorkspaceId,
                    plan.snapshot.userId, challenge.id)
                // Transactional outbox: after a process interruption, cleanup is completed before Home opens.
                database.financeDao().enqueueOperation(PendingSyncOperationEntity(entityType = CLEANUP, action = "APPLY", entityId = 0,
                    payload = gson.toJson(cleanup), userId = plan.snapshot.userId, workspaceId = plan.snapshot.activeWorkspaceId))
            }
            withContext(NonCancellable) { recoverLocked() }
        }
    }
    suspend fun recover() = withContext(Dispatchers.IO) { mutex.withLock { recoverLocked() } }
    private suspend fun recoverLocked() {
        val jobs = query("SELECT * FROM pending_sync_operations WHERE entityType = ? ORDER BY id", arrayOf(CLEANUP))
        for (job in jobs) {
            val cleanup = gson.fromJson(requireNotNull(job["payload"]), DeletionCleanup::class.java)
            require(cleanup.userId == job.int("userId"))
            for ((file, values) in cleanup.preferences.groupBy { it.file }) {
                require(file in setOf("forecast_sync_v3", "forecast_scenarios", "ai_plans", "financeapp_debts") || file.startsWith("financeapp_category_rules_"))
                val editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
                for (p in values) when(p.kind) {
                    "set" -> editor.putStringSet(p.key, JsonParser.parseString(p.value).asJsonArray.map { it.asString }.toSet())
                    "string" -> editor.putString(p.key, p.value)
                    "remove" -> editor.remove(p.key)
                    else -> error("Registro de limpeza inv\u00e1lido.")
                }
                check(editor.commit()) { "A exclus\u00e3o foi autorizada, mas a limpeza precisa ser retomada ao reabrir o aplicativo." }
            }
            for (path in cleanup.files) {
                val file = File(context.filesDir, path)
                val allowed = File(context.filesDir, "pending_transaction_attachments/${cleanup.userId}").canonicalPath + File.separator
                require(file.canonicalPath.startsWith(allowed)) { "Caminho de anexo inv\u00e1lido." }
                check(!file.exists() || file.delete()) { "N\u00e3o foi poss\u00edvel remover um anexo local. Reabra o aplicativo para concluir." }
            }
            cleanup.nextWorkspaceId?.let { wid ->
                if(session.localUserId() == cleanup.userId) session.activateWorkspace(WorkspaceScope(cleanup.userId, wid))
            }
            database.openHelper.writableDatabase.execSQL("DELETE FROM pending_sync_operations WHERE id = ? AND entityType = ? AND userId = ?",
                arrayOf<Any>(requireNotNull(job["id"]), CLEANUP, cleanup.userId))
        }
        if(jobs.isNotEmpty()) database.invalidationTracker.refreshVersionsAsync()
    }
}
