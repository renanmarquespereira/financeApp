package com.financeapp.mobile.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.financeapp.mobile.MainActivity
import com.financeapp.mobile.R
import com.financeapp.mobile.data.local.FinanceDao
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.local.CreditCardEntity
import com.financeapp.mobile.util.SessionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal data class FinanceNotificationPreferences(
    val payables: Boolean = true,
    val invoices: Boolean = true,
    val budgets: Boolean = true,
    val monthlySummary: Boolean = false,
)

internal object FinanceNotificationSettings {
    private const val PREFS = "financeapp_notifications"
    fun read(context: Context): FinanceNotificationPreferences {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return FinanceNotificationPreferences(
            payables = p.getBoolean("payables", true),
            invoices = p.getBoolean("invoices", true),
            budgets = p.getBoolean("budgets", true),
            monthlySummary = p.getBoolean("monthly_summary", false),
        )
    }
    fun save(context: Context, value: FinanceNotificationPreferences) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("payables", value.payables)
            .putBoolean("invoices", value.invoices)
            .putBoolean("budgets", value.budgets)
            .putBoolean("monthly_summary", value.monthlySummary)
            .apply()
        FinanceNotificationScheduler.schedule(context, runNow = true)
    }
    fun markSent(context: Context, key: String): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val full = "sent_$key"
        if (p.getBoolean(full, false)) return false
        p.edit().putBoolean(full, true).apply()
        return true
    }
}

internal object FinanceNotificationScheduler {
    fun schedule(context: Context, runNow: Boolean = false) {
        val now = LocalDateTime.now()
        var target = LocalDateTime.of(now.toLocalDate(), LocalTime.of(8, 0))
        if (!target.isAfter(now)) target = target.plusDays(1)
        val delayMinutes = Duration.between(now, target).toMinutes().coerceAtLeast(1)
        val periodic = PeriodicWorkRequestBuilder<FinanceNotificationWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "financeapp-financial-notifications", ExistingPeriodicWorkPolicy.UPDATE, periodic
        )
        if (runNow) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "financeapp-financial-notifications-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FinanceNotificationWorker>().build()
            )
        }
    }
}

private const val NOTIFICATION_CHANNEL_ID = "finance_reminders_v2"

private data class PayableMeta(val reminderDays: Int, val amount: Double, val description: String)
private data class InvoiceReminder(
    val card: CreditCardEntity,
    val invoiceEnd: LocalDate,
    val dueDate: LocalDate,
    val total: Double,
    val remaining: Double,
)

private fun closingForMonth(month: YearMonth, closingDay: Int?): LocalDate {
    val close = (closingDay ?: 31).coerceIn(1, 31)
    return month.atDay(close.coerceAtMost(month.lengthOfMonth()))
}

private fun invoiceEndForDate(date: LocalDate, closingDay: Int?): LocalDate {
    val close = closingForMonth(YearMonth.from(date), closingDay)
    return if (!date.isAfter(close)) close else closingForMonth(YearMonth.from(date).plusMonths(1), closingDay)
}

private fun invoiceDueDate(end: LocalDate, dueDay: Int?): LocalDate? {
    val due = dueDay ?: return null
    var month = YearMonth.from(end)
    if (due <= end.dayOfMonth) month = month.plusMonths(1)
    return month.atDay(due.coerceAtMost(month.lengthOfMonth()))
}

private fun transactionDate(tx: TransactionEntity): LocalDate? =
    runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull()

private fun realInvoices(
    cards: List<CreditCardEntity>,
    transactions: List<TransactionEntity>,
): List<InvoiceReminder> {
    val result = mutableListOf<InvoiceReminder>()
    cards.filter { it.active && it.dueDay != null }.forEach { card ->
        val movements = transactions.filter { it.cardId == card.id && it.source != "card_payment" }
        val grouped = movements.mapNotNull { tx ->
            val date = transactionDate(tx) ?: return@mapNotNull null
            invoiceEndForDate(date, card.closingDay) to tx
        }.groupBy({ it.first }, { it.second })

        grouped.forEach { (invoiceEnd, invoiceTransactions) ->
            val total = (-invoiceTransactions.sumOf { it.amount }).coerceAtLeast(0.0)
            if (total <= 0.005) return@forEach
            val paid = transactions.asSequence()
                .filter { it.cardId == card.id && it.source == "card_payment" && it.purchaseDate?.take(10) == invoiceEnd.toString() }
                .sumOf { abs(it.amount) }
            val remaining = (total - paid).coerceAtLeast(0.0)
            if (remaining <= 0.005) return@forEach
            val due = invoiceDueDate(invoiceEnd, card.dueDay) ?: return@forEach
            result += InvoiceReminder(card, invoiceEnd, due, total, remaining)
        }
    }
    return result
}

@HiltWorker
class FinanceNotificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dao: FinanceDao,
    private val session: SessionManager,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val scope = runCatching { session.requireWorkspace() }.getOrNull() ?: return Result.success()
        val settings = FinanceNotificationSettings.read(applicationContext)
        val transactions = dao.backupTransactions(scope.userId, scope.workspaceId)
        val cards = dao.backupCards(scope.userId, scope.workspaceId)
        val categories = dao.backupCategories(scope.userId, scope.workspaceId)
        val budgets = dao.backupBudgets(scope.userId, scope.workspaceId)
        val today = LocalDate.now()
        createChannel()

        if (settings.payables) transactions.forEach { tx ->
            val meta = decodePayable(tx.description) ?: return@forEach
            val due = runCatching { LocalDate.parse(tx.date.take(10)) }.getOrNull() ?: return@forEach
            val days = Duration.between(today.atStartOfDay(), due.atStartOfDay()).toDays().toInt()
            if (days == meta.reminderDays || days == 0) {
                val suffix = if (days == 0) "vence hoje" else "vence em $days dias"
                notifyOnce("payable:${tx.id}:$due:$days", "Conta a pagar", "${meta.description} $suffix • ${money(meta.amount)}")
            }
        }

        if (settings.invoices) {
            val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            // Apenas faturas reais (com movimentação no cartão e saldo em aberto) geram notificação.
            realInvoices(cards, transactions).forEach { invoice ->
                val days = ChronoUnit.DAYS.between(today, invoice.dueDate).toInt()
                val stage = when {
                    days < 0 -> "overdue"
                    days == 0 -> "due"
                    days in 1..5 -> "upcoming"
                    else -> null
                } ?: return@forEach

                val label = invoice.card.nickname?.takeIf { it.isNotBlank() }
                    ?: "${invoice.card.bankName} •••• ${invoice.card.lastFour}"
                val title: String
                val message: String
                when (stage) {
                    "overdue" -> {
                        title = "Fatura vencida"
                        message = "$label venceu em ${invoice.dueDate.format(dateFormat)} • Em aberto ${money(invoice.remaining)}"
                    }
                    "due" -> {
                        title = "Fatura vence hoje"
                        message = "$label • Em aberto ${money(invoice.remaining)}"
                    }
                    else -> {
                        title = "Fatura próxima do vencimento"
                        message = "$label vence em $days dia(s) • ${money(invoice.remaining)}"
                    }
                }
                // Um aviso ao entrar na janela de 5 dias, outro no vencimento e outro se ficar vencida.
                notifyOnce("invoice:${invoice.card.id}:${invoice.invoiceEnd}:$stage", title, message)
            }
        }

        if (settings.budgets) {
            val month = YearMonth.from(today)
            budgets.filter { it.amount > 0.0 }.forEach { budget ->
                val spent = transactions.asSequence().filter { tx ->
                    tx.categoryId == budget.categoryId && tx.amount < 0 &&
                        runCatching { YearMonth.from(LocalDate.parse(tx.date.take(10))) }.getOrNull() == month
                }.sumOf { abs(it.amount) }
                val ratio = spent / budget.amount
                val threshold = when { ratio >= 1.0 -> 100; ratio >= .8 -> 80; else -> null } ?: return@forEach
                val category = categories.firstOrNull { it.id == budget.categoryId }?.name ?: "Categoria"
                notifyOnce(
                    "budget:${budget.categoryId}:$month:$threshold",
                    if (threshold == 100) "Limite atingido" else "Atenção ao limite",
                    "$category está em ${(ratio * 100).toInt()}% • ${money(spent)} de ${money(budget.amount)}"
                )
            }
        }

        if (settings.monthlySummary && today.dayOfMonth == 1) {
            val previous = YearMonth.from(today).minusMonths(1)
            val values = transactions.filter { tx ->
                runCatching { YearMonth.from(LocalDate.parse(tx.date.take(10))) }.getOrNull() == previous && decodePayable(tx.description) == null
            }
            val income = values.filter { it.amount > 0 }.sumOf { it.amount }
            val expense = values.filter { it.amount < 0 }.sumOf { abs(it.amount) }
            notifyOnce("summary:$previous", "Resumo mensal", "Entradas ${money(income)} • Saídas ${money(expense)}")
        }
        return Result.success()
    }

    private fun decodePayable(value: String): PayableMeta? {
        val prefix = "__PAYABLE_V1__|"
        if (!value.startsWith(prefix)) return null
        val parts = value.removePrefix(prefix).split('|', limit = 3)
        if (parts.size != 3) return null
        val reminder = parts[0].toIntOrNull() ?: return null
        val cents = parts[1].toLongOrNull() ?: return null
        val description = runCatching { String(Base64.decode(parts[2], Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()?.trim().orEmpty()
        if (description.isBlank()) return null
        return PayableMeta(reminder.coerceIn(1, 30), cents / 100.0, description)
    }

    private fun money(value: Double): String = "R$ " + String.format(java.util.Locale("pt", "BR"), "%,.2f", value)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL_ID, "Lembretes financeiros", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Contas, faturas, categorias e resumos do FinanceApp"
                    enableVibration(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun notifyOnce(key: String, title: String, message: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!FinanceNotificationSettings.markSent(applicationContext, key)) return
        val pending = PendingIntent.getActivity(
            applicationContext,
            key.hashCode(),
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(android.app.Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(key.hashCode(), notification)
    }
}
