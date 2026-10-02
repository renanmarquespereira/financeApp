package com.financeapp.mobile.ui.home

import com.financeapp.mobile.data.local.TransactionEntity
import java.text.Normalizer
import java.time.LocalDate
import kotlin.math.abs

data class RecurrencePattern(
    val key: String,
    val description: String,
    val amount: Double,
    val isIncome: Boolean,
    val dayOfMonth: Int,
    val lastDate: LocalDate,
    val occurrences: Int
)

private fun recurrenceDate(raw: String): LocalDate? = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
private fun recurrenceKey(raw: String): String = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ")
    .replace(Regex("\\b\\d{1,4}\\b"), " ").replace(Regex("\\s+"), " ").trim()

internal fun detectRecurringPatterns(transactions: List<TransactionEntity>): List<RecurrencePattern> {
    val cash = transactions.filter { it.source != "card_purchase" && !(it.cardId != null && it.source != "card_payment") }
    return cash.groupBy { recurrenceKey(it.description) }.mapNotNull { (key, rows) ->
        if (key.length < 3 || rows.size < 3) return@mapNotNull null
        val dated = rows.mapNotNull { tx -> recurrenceDate(tx.date)?.let { it to tx } }.sortedBy { it.first }
        if (dated.size < 3) return@mapNotNull null
        val sameSign = dated.all { it.second.amount >= 0 } || dated.all { it.second.amount < 0 }
        if (!sameSign) return@mapNotNull null
        val gaps = dated.zipWithNext { a,b -> java.time.temporal.ChronoUnit.DAYS.between(a.first,b.first) }
        val monthly = gaps.count { it in 20..40 } >= (gaps.size * 0.6)
        if (!monthly) return@mapNotNull null
        val amounts = dated.map { abs(it.second.amount) }.sorted()
        val median = amounts[amounts.size/2]
        if (median <= 0) return@mapNotNull null
        val stable = amounts.count { abs(it-median)/median <= .15 } >= (amounts.size * .7)
        if (!stable) return@mapNotNull null
        val last = dated.last()
        RecurrencePattern(key, last.second.description, median, last.second.amount >= 0, last.first.dayOfMonth, last.first, dated.size)
    }.sortedByDescending { it.amount }
}

internal fun projectedRecurrences(patterns: List<RecurrencePattern>, from: LocalDate, to: LocalDate): List<Triple<LocalDate,String,Double>> =
    patterns.flatMap { p ->
        val out=mutableListOf<Triple<LocalDate,String,Double>>()
        var month=java.time.YearMonth.from(maxOf(from,p.lastDate)).plusMonths(1)
        while (!month.atDay(1).isAfter(to)) {
            val d=month.atDay(p.dayOfMonth.coerceAtMost(month.lengthOfMonth()))
            if (d.isAfter(from) && !d.isAfter(to)) out += Triple(d,p.description,if(p.isIncome) p.amount else -p.amount)
            month=month.plusMonths(1)
        }
        out
    }

internal fun recurrenceDecision(context: android.content.Context, userKey: String, workspace: String, key: String): String? =
    context.getSharedPreferences("financeapp_recurrences_${userKey}_${workspace}", android.content.Context.MODE_PRIVATE).getString(key, null)

internal fun saveRecurrenceDecision(context: android.content.Context, userKey: String, workspace: String, key: String, decision: String) {
    context.getSharedPreferences("financeapp_recurrences_${userKey}_${workspace}", android.content.Context.MODE_PRIVATE).edit().putString(key, decision).apply()
}
