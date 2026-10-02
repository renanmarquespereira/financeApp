package com.financeapp.mobile.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable workspace cache. Network replies are merged with the latest local state. */
internal object ForecastCache {
    private val gson = Gson()
    private val type = object : TypeToken<Map<String, Any?>>() {}.type
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val collections = mapOf("scenarios" to "deletedScenarioIds", "aiPlans" to "deletedAiPlanIds")
    private fun id(value: Any?): String = if (value is Number) value.toLong().toString() else value.toString().removeSuffix(".0")
    @Synchronized fun read(context: Context, key: String): Map<String, Any?> {
        val raw = context.getSharedPreferences("forecast_sync_v3", Context.MODE_PRIVATE).getString(key, null) ?: return emptyMap()
        return gson.fromJson(raw, type)
    }
    private fun merge(local: Map<String, Any?>, remote: Map<String, Any?>): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        for ((collection, tombstones) in collections) {
            val deleted = listOf(local, remote).flatMap { (it[tombstones] as? List<*>) ?: emptyList<Any>() }.map(::id).toSet()
            val items = linkedMapOf<String, Any?>()
            for (source in listOf(local, remote)) for (item in (source[collection] as? List<*>) ?: emptyList<Any>()) {
                val row = item as? Map<*, *> ?: continue
                val rowId = row["id"] ?: continue
                if (id(rowId) !in deleted) items[id(rowId)] = item
            }
            out[collection] = items.values.toList()
            out[tombstones] = deleted.toList()
        }
        return out
    }
    @Synchronized fun update(context: Context, key: String, delta: Map<String, Any?>): Map<String, Any?> {
        val next = merge(read(context, key), delta)
        check(context.getSharedPreferences("forecast_sync_v3", Context.MODE_PRIVATE).edit().putString(key, gson.toJson(next)).commit()) { "Não foi possível salvar no aparelho" }
        return next
    }
    suspend fun sync(context: Context, key: String, load: suspend () -> Map<String, Any?>,
                     save: suspend (Map<String, Any?>) -> Map<String, Any?>): Map<String, Any?> =
        locks.getOrPut(key) { Mutex() }.withLock {
            val response = load()
            check((response["sync_version"] as? Number)?.toInt() == 2) { "Atualize o backend para sincronizar cenários e planos com segurança." }
            @Suppress("UNCHECKED_CAST")
            var remote = response["payload"] as? Map<String, Any?> ?: error("Resposta de cenários inválida")
            val local = read(context, key)
            val delta = mutableMapOf<String, Any?>()
            for ((collection, tombstones) in collections) {
                val known = ((remote[collection] as? List<*>) ?: emptyList<Any>()).mapNotNull { (it as? Map<*, *>)?.get("id")?.let(::id) }.toSet()
                val removed = ((remote[tombstones] as? List<*>) ?: emptyList<Any>()).map(::id).toSet()
                val added = ((local[collection] as? List<*>) ?: emptyList<Any>()).filter { val k = (it as? Map<*, *>)?.get("id")?.let(::id); k != null && k !in known && k !in removed }
                val deleted = ((local[tombstones] as? List<*>) ?: emptyList<Any>()).filter { id(it) !in removed }
                if (added.isNotEmpty()) delta[collection] = added
                if (deleted.isNotEmpty()) delta[tombstones] = deleted
            }
            if (delta.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                val saved = save(delta)["payload"] as? Map<String, Any?> ?: error("Servidor não confirmou o salvamento")
                remote = saved
            }
            mapOf("payload" to update(context, key, remote), "sync_version" to 2)
        }
}
