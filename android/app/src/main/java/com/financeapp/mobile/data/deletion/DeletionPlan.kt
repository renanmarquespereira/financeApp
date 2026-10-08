package com.financeapp.mobile.data.deletion

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.UUID

/** Immutable, testable plan: building or previewing it NEVER writes to the database. */
data class DeletionChoice(
    val workspaceIds: Set<String> = emptySet(),
    val deleteWorkspaces: Boolean = false,
    val allWorkspaces: Boolean = false,
    val allFinancial: Boolean = false,
    val accountIds: Set<Int> = emptySet(),
    val cardIds: Set<Int> = emptySet(),
    val categoryIds: Set<Int> = emptySet(),
    val transactions: Boolean = false,
    val transactionMode: String = "all", // all | cards | selected
    val transactionIds: Set<Int> = emptySet(),
    val fromDate: String = "",
    val toDate: String = "",
    val forecasts: Boolean = false,
    val planning: Boolean = false,
    val debts: Boolean = false
)

data class LocalRow(val values: Map<String, String?>) {
    operator fun get(key: String): String? = values[key]
    fun int(key: String): Int? = values[key]?.toIntOrNull()
}
data class LocalPreference(val file: String, val key: String, val kind: String, val value: String)
data class DeletionSnapshot(
    val userId: Int,
    val activeWorkspaceId: String,
    val workspaces: List<LocalRow>,
    val tables: Map<String, List<LocalRow>>,
    val preferences: List<LocalPreference>,
    val userKeys: Set<String>
)
data class DeletionSql(val sql: String, val args: List<String>)
data class DeletionPlan(
    val snapshot: DeletionSnapshot,
    val choice: DeletionChoice,
    val scopeIds: Set<String>,
    val statements: List<DeletionSql>,
    val preferenceWrites: List<LocalPreference>,
    val attachmentFiles: List<String>,
    val counts: Map<String, Int>,
    val basisHash: String,
    val planHash: String,
    val nextWorkspaceId: String?,
    val createdAt: Long = System.currentTimeMillis()
) {
    val total: Int get() = counts.values.sum()
    val operation: String get() = when {
        choice.deleteWorkspaces -> "workspaces"
        choice.allFinancial -> "all_financial"
        else -> "selected"
    }
}

object DeletionPlanner {
    val tableNames = listOf("accounts", "credit_cards_local", "transactions", "categories",
        "budgets_local", "goals_local", "goal_contributions_local", "pending_sync_operations")
    val countLabels = linkedMapOf("transactions" to "Transa\u00e7\u00f5es", "accounts" to "Contas banc\u00e1rias",
        "cards" to "Cart\u00f5es de cr\u00e9dito", "categories" to "Categorias", "budgets" to "Limites de categorias",
        "goals" to "Metas", "contributions" to "Aportes em metas", "scenarios" to "Cen\u00e1rios",
        "plans" to "Planos salvos", "debts" to "D\u00edvidas", "workspaces" to "Workspaces")
    private val gson = Gson()
    fun sha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun canonical(row: LocalRow) = row.values.toSortedMap().entries.joinToString("") {
        "${it.key.length}:${it.key}:${it.value?.length ?: -1}:${it.value.orEmpty()};"
    }
    fun snapshotHash(s: DeletionSnapshot): String = sha(buildString {
        append("owner=${s.userId};active=${s.activeWorkspaceId};")
        s.workspaces.map(::canonical).sorted().forEach { append(it).append('\n') }
        s.tables.toSortedMap().forEach { (name, rows) ->
            append(name).append('\n'); rows.map(::canonical).sorted().forEach { append(it).append('\n') }
        }
        s.preferences.sortedWith(compareBy({ it.file }, { it.key })).forEach { append(gson.toJson(it)).append('\n') }
    })
    fun build(s: DeletionSnapshot, c: DeletionChoice): DeletionPlan {
        require(s.workspaces.all { it.int("userId") == s.userId }) { "Workspace de outra conta." }
        val known = s.workspaces.mapNotNull { it["id"] }.toSet()
        val scopes = when {
            c.allFinancial && c.allWorkspaces -> known
            c.workspaceIds.isNotEmpty() -> c.workspaceIds
            else -> setOf(s.activeWorkspaceId)
        }
        require(scopes.isNotEmpty() && known.containsAll(scopes)) { "Selecione um workspace v\u00e1lido." }
        require(c.allFinancial || c.deleteWorkspaces || scopes.size == 1) { "Use um workspace por exclus\u00e3o seletiva." }
        require(c.transactionMode in setOf("all", "cards", "selected"))
        require(c.fromDate.isEmpty() || c.fromDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        require(c.toDate.isEmpty() || c.toDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        require(c.fromDate.isEmpty() || c.toDate.isEmpty() || c.fromDate <= c.toDate) { "Per\u00edodo invertido." }
        // Validate the owner even when a caller supplies a constructed snapshot.
        require(s.tables.values.flatten().all { it.int("userId") == s.userId }) { "Dados de outra conta." }
        val all = c.allFinancial || c.deleteWorkspaces
        val ops = mutableListOf<DeletionSql>()
        val prefs = mutableListOf<LocalPreference>()
        val files = mutableListOf<String>()
        val counts = countLabels.keys.associateWith { 0 }.toMutableMap()
        fun count(k: String, n: Int) { counts[k] = counts.getValue(k) + n }
        for (wid in scopes.sorted()) {
            fun rows(table: String) = s.tables[table].orEmpty().filter { it["workspaceId"] == wid }
            val accounts = rows("accounts").filter { all || it.int("id") in c.accountIds }
            val cards = rows("credit_cards_local").filter { all || it.int("id") in c.cardIds }
            val categories = rows("categories").filter { all || it.int("id") in c.categoryIds }
            if (!all) {
                require(accounts.mapNotNull { it.int("id") }.toSet() == c.accountIds) { "Uma conta selecionada n\u00e3o existe mais." }
                require(cards.mapNotNull { it.int("id") }.toSet() == c.cardIds) { "Um cart\u00e3o selecionado n\u00e3o existe mais." }
                require(categories.mapNotNull { it.int("id") }.toSet() == c.categoryIds) { "Uma categoria selecionada n\u00e3o existe mais." }
            }
            val accountIds = accounts.mapNotNull { it.int("id") }.toSet()
            val cardIds = cards.mapNotNull { it.int("id") }.toSet()
            val catIds = categories.mapNotNull { it.int("id") }.toSet()
            val txs = rows("transactions").filter { row ->
                val date = row["date"].orEmpty().take(10)
                val filtered = c.transactions && (c.fromDate.isEmpty() || date >= c.fromDate) &&
                    (c.toDate.isEmpty() || date <= c.toDate) && when (c.transactionMode) {
                        "cards" -> (row.int("cardId") != null || row["source"] == "card_purchase") && row["source"] != "card_payment"
                        "selected" -> row.int("id") in c.transactionIds
                        else -> true
                    }
                all || filtered || row.int("accountId") in accountIds || row.int("cardId") in cardIds
            }
            val txIds = txs.mapNotNull { it.int("id") }.toSet()
            val goals = rows("goals_local").filter { all || c.planning }
            val goalIds = goals.mapNotNull { it.int("id") }.toSet()
            val budgets = rows("budgets_local").filter { all || c.planning || it.int("categoryId") in catIds }
            val budgetIds = budgets.mapNotNull { it.int("categoryId") }.toSet()
            val contributions = rows("goal_contributions_local").filter { all || it.int("goalId") in goalIds }
            val contributionIds = contributions.mapNotNull { it.int("id") }.toSet()
            val relink = rows("transactions").filter { it.int("id") !in txIds && it.int("categoryId") in catIds }
            val relinkIds = relink.mapNotNull { it.int("id") }.toSet()
            val pending = rows("pending_sync_operations").filter { row -> all || when(row["entityType"]) {
                "TRANSACTION" -> row.int("entityId") in (txIds + relinkIds)
                "ATTACHMENT" -> row.int("entityId") in txIds
                "ACCOUNT" -> row.int("entityId") in accountIds
                "CARD", "CREDIT_CARD" -> row.int("entityId") in cardIds
                "CATEGORY" -> row.int("entityId") in catIds
                "BUDGET" -> row.int("entityId") in budgetIds
                "GOAL" -> row.int("entityId") in goalIds
                "GOAL_CONTRIBUTION" -> row.int("entityId") in contributionIds
                else -> false
            } }
            for (row in pending.filter { it["entityType"] == "ATTACHMENT" && it["action"] == "CREATE" }) {
                val payload = runCatching { JsonParser.parseString(row["payload"] ?: "{}").asJsonObject }.getOrNull()
                val stored = payload?.get("storedName")?.takeUnless { it.isJsonNull }?.asString
                if (stored != null && stored !in setOf(".", "..") && stored.matches(Regex("[A-Za-z0-9._-]+"))) {
                    val safe = wid.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    files += "pending_transaction_attachments/${s.userId}/$safe/$stored"
                }
            }
            fun remove(table: String, list: List<LocalRow>, key: String = "id") {
                list.forEach { r -> ops += DeletionSql("DELETE FROM $table WHERE userId = ? AND workspaceId = ? AND $key = ?",
                    listOf(s.userId.toString(), wid, requireNotNull(r[key]))) }
            }
            remove("pending_sync_operations", pending)
            remove("goal_contributions_local", contributions)
            remove("transactions", txs)
            remove("budgets_local", budgets, "categoryId")
            relink.forEach { r -> ops += DeletionSql("UPDATE transactions SET categoryId = NULL WHERE userId = ? AND workspaceId = ? AND id = ?",
                listOf(s.userId.toString(), wid, requireNotNull(r["id"]))) }
            remove("accounts", accounts); remove("credit_cards_local", cards)
            remove("categories", categories); remove("goals_local", goals)
            count("transactions", txs.size); count("accounts", accounts.size); count("cards", cards.size)
            count("categories", categories.size); count("budgets", budgets.size); count("goals", goals.size)
            count("contributions", contributions.size)
            prefs += planPreferences(s, wid, all || c.forecasts, all || c.planning, all || c.debts,
                accountIds, cardIds, catIds, counts)
        }
        var nextWorkspace: String? = null
        if (c.deleteWorkspaces) {
            scopes.sorted().forEach { wid -> ops += DeletionSql("DELETE FROM workspaces_local WHERE userId = ? AND id = ?", listOf(s.userId.toString(), wid)) }
            count("workspaces", scopes.size)
            val activeRemaining = s.workspaces.filter { it["id"] !in scopes && it["archivedAt"] == null }
            val survivor = activeRemaining.firstOrNull { it["isDefault"] == "1" } ?: activeRemaining.firstOrNull()
            if (survivor == null) {
                nextWorkspace = "local-" + UUID.randomUUID().toString()
                ops += DeletionSql("UPDATE workspaces_local SET isDefault = 0 WHERE userId = ?", listOf(s.userId.toString()))
                ops += DeletionSql("INSERT INTO workspaces_local (userId, id, name, kind, isDefault, archivedAt) VALUES (?, ?, ?, ?, 1, NULL)",
                    listOf(s.userId.toString(), nextWorkspace, "Principal", "personal"))
            } else {
                if (s.activeWorkspaceId in scopes) nextWorkspace = survivor["id"]
                if (s.workspaces.any { it["id"] in scopes && it["isDefault"] == "1" }) {
                    ops += DeletionSql("UPDATE workspaces_local SET isDefault = 1 WHERE userId = ? AND id = ?",
                        listOf(s.userId.toString(), requireNotNull(survivor["id"])))
                }
            }
        }
        val basis = snapshotHash(s)
        val writes = prefs.distinctBy { it.file to it.key }
        val hash = sha(basis + gson.toJson(c) + gson.toJson(ops) + gson.toJson(writes) + UUID.randomUUID())
        return DeletionPlan(s, c, scopes, ops, writes, files.distinct(), counts, basis, hash, nextWorkspace)
    }

    private fun planPreferences(s: DeletionSnapshot, wid: String, forecast: Boolean, planning: Boolean, debts: Boolean,
        accountIds: Set<Int>, cardIds: Set<Int>, catIds: Set<Int>, counts: MutableMap<String, Int>): List<LocalPreference> {
        val out = linkedMapOf<Pair<String, String>, LocalPreference>()
        val name = s.workspaces.first { it["id"] == wid }["name"].orEmpty()
        val uniqueName = s.workspaces.count { it["name"] == name } == 1
        val scenarioSuffixes = setOf(wid) + if(uniqueName) setOf(name) else emptySet()
        fun get(file: String, key: String): LocalPreference? = s.preferences.firstOrNull { it.file == file && it.key == key }
        fun json(p: LocalPreference?): JsonElement = runCatching { JsonParser.parseString(p?.value ?: "[]") }.getOrDefault(JsonArray())
        fun array(e: JsonElement): JsonArray = if (e.isJsonArray) e.asJsonArray else JsonArray()
        fun ids(e: JsonElement): Set<String> = array(e).mapNotNull { el ->
            val raw = runCatching { if (el.isJsonObject) el.asJsonObject.get("id")?.asString else el.asString }.getOrNull()
            // Gson caches can serialize Long IDs as 1.77E12; tombstones must use the same integral ID.
            if(raw == null) null else runCatching { raw.toBigDecimal().toBigIntegerExact().toString() }.getOrDefault(raw)
        }.toSet()
        fun write(file: String, key: String, value: JsonElement, kind: String = "string") {
            val next = LocalPreference(file, key, kind, gson.toJson(value))
            if (get(file, key) != next) out[file to key] = next
        }
        // Remove bank/category references from preserved scenarios/debt payments.
        fun detach(e: JsonElement): JsonElement {
            if (e.isJsonArray) { e.asJsonArray.forEach { detach(it) }; return e }
            if (!e.isJsonObject) return e
            val obj = e.asJsonObject
            obj.entrySet().toList().forEach { (key, v) ->
                val id = runCatching { v.asInt }.getOrNull()
                val remove = when (key) {
                    "accountId", "account_id" -> id in accountIds
                    "cardId", "card_id" -> id in cardIds
                    "categoryId", "category_id" -> id in catIds
                    else -> false
                }
                if (remove) obj.add(key, JsonNull.INSTANCE) else detach(v)
            }
            return e
        }
        val cacheKey = "${s.userId}:$wid"
        val cache = runCatching { json(get("forecast_sync_v3", cacheKey)).asJsonObject }.getOrDefault(JsonObject())
        val scenarioIds = ids(cache.get("scenarios") ?: JsonArray()).toMutableSet()
        val planIds = ids(cache.get("aiPlans") ?: JsonArray()).toMutableSet()
        val debtIds = ids(cache.get("debts") ?: JsonArray()).toMutableSet()
        for (uk in s.userKeys) for (suffix in scenarioSuffixes) {
            scenarioIds += ids(json(get("forecast_scenarios", "forecast_scenarios_${uk}_$suffix")))
        }
        for (uk in s.userKeys) {
            planIds += ids(json(get("ai_plans", "plans_${uk}_workspace_$wid")))
            if(uniqueName) planIds += ids(json(get("ai_plans", "plans_${uk}_$name")))
            debtIds += ids(json(get("financeapp_debts", "financeapp_debts_v1_${uk}_$wid")))
        }
        fun emptyCollection(collection: String, deleted: String, removed: Set<String>) {
            val allDeleted = ids(cache.get(deleted) ?: JsonArray()) + removed
            cache.add(collection, JsonArray()); cache.add(deleted, gson.toJsonTree(allDeleted.sorted()))
        }
        if (forecast) { counts["scenarios"] = counts.getValue("scenarios") + scenarioIds.size; emptyCollection("scenarios", "deletedScenarioIds", scenarioIds) }
        if (planning) { counts["plans"] = counts.getValue("plans") + planIds.size; emptyCollection("aiPlans", "deletedAiPlanIds", planIds) }
        if (debts) { counts["debts"] = counts.getValue("debts") + debtIds.size; emptyCollection("debts", "deletedDebtIds", debtIds) }
        if (forecast || planning || debts || get("forecast_sync_v3", cacheKey) != null) write("forecast_sync_v3", cacheKey, detach(cache))
        for (uk in s.userKeys) {
            for (suffix in scenarioSuffixes) {
                val k = "forecast_scenarios_${uk}_$suffix"
                val old = get("forecast_scenarios", k)
                if (forecast) {
                    write("forecast_scenarios", k, JsonArray())
                    val dk = "forecast_deleted_scenarios_${uk}_$suffix"
                    write("forecast_scenarios", dk, gson.toJsonTree((ids(json(get("forecast_scenarios", dk))) + scenarioIds).sorted()), "set")
                } else if (old != null) write(old.file, old.key, detach(json(old)))
            }
            for (suffix in setOf("${uk}_workspace_$wid") + if(uniqueName) setOf("${uk}_$name") else emptySet()) {
                val k = "plans_$suffix"; val old = get("ai_plans", k)
                if (planning) {
                    write("ai_plans", k, JsonArray())
                    val dk = "deleted_$suffix"
                    write("ai_plans", dk, gson.toJsonTree((ids(json(get("ai_plans", dk))) + planIds).sorted()), "set")
                } else if (old != null) write(old.file, old.key, detach(json(old)))
            }
            val dk = "financeapp_debts_v1_${uk}_$wid"; val old = get("financeapp_debts", dk)
            if (debts) {
                write("financeapp_debts", dk, JsonArray())
                val deleted = "financeapp_deleted_debts_v1_${uk}_$wid"
                write("financeapp_debts", deleted, gson.toJsonTree((ids(json(get("financeapp_debts", deleted))) + debtIds).sorted()), "set")
            } else if (old != null) write(old.file, old.key, detach(json(old)))
        }
        if(uniqueName) for(uk in s.userKeys) {
            val rulesFile = "financeapp_category_rules_${uk}_$name"
            s.preferences.filter { it.file == rulesFile }.forEach { p ->
                if(p.value.toIntOrNull() in catIds) out[p.file to p.key] = p.copy(kind = "remove", value = "")
            }
        }
        return out.values.toList()
    }
}
