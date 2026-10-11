package com.financeapp.mobile.ui.home

import android.content.Context
import com.financeapp.mobile.data.local.TransactionEntity
import java.security.MessageDigest
import kotlin.math.roundToLong

/** A local review flag, scoped by user and Workspace through the transaction key.
 * It does not modify financial data or sync payloads. */
internal object VoiceReviewMarker {
    private fun prefs(context: Context) = context.getSharedPreferences("financeapp_voice_review_v1", Context.MODE_PRIVATE)
    private fun signature(workspace: String, description: String, amount: Double, date: String, accountId: Int?, cardId: Int?): String {
        val raw = listOf(workspace, description.trim(), (amount * 100).roundToLong().toString(), date.take(10), accountId?.toString().orEmpty(), cardId?.toString().orEmpty()).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return "review_" + hash.joinToString("") { "%02x".format(it) }
    }
    fun mark(context: Context, workspace: String, description: String, amount: Double, date: String, accountId: Int?, cardId: Int?) {
        prefs(context).edit().putBoolean(signature(workspace, description, amount, date, accountId, cardId), true).apply()
    }
    fun markCreated(context: Context, workspace: String, description: String, totalAmount: Double, date: String, accountId: Int?, cardId: Int?, installments: Int) {
        val count = installments.coerceIn(1, 360)
        val totalCents = kotlin.math.abs(totalAmount * 100).roundToLong()
        val base = totalCents / count
        val remainder = totalCents % count
        val dateBase = runCatching { java.time.LocalDate.parse(date.take(10)) }.getOrNull()
        for (index in 0 until count) {
            val cents = base + if (index < remainder) 1 else 0
            val partAmount = (if (totalAmount < 0.0) -1 else 1) * cents / 100.0
            val partDate = dateBase?.plusMonths(index.toLong())?.toString() ?: date.take(10)
            mark(context, workspace, description, partAmount, partDate, accountId, cardId)
        }
    }
    fun pending(context: Context, tx: TransactionEntity): Boolean = prefs(context).getBoolean(signature(tx.workspaceId, tx.description, tx.amount, tx.date, tx.accountId, tx.cardId), false)
    fun reviewed(context: Context, tx: TransactionEntity) {
        prefs(context).edit().remove(signature(tx.workspaceId, tx.description, tx.amount, tx.date, tx.accountId, tx.cardId)).apply()
    }
}
