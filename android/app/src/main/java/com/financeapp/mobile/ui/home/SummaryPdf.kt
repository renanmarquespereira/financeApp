package com.financeapp.mobile.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.core.content.FileProvider
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.CategoryDto
import com.financeapp.mobile.data.remote.CreditCardDto
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

fun createAndShareFinancialSummaryPdf(
    context: Context,
    transactions: List<TransactionEntity>,
    categories: List<CategoryDto>,
    cards: List<CreditCardDto>,
    periodTitle: String,
    annualMonths: List<Triple<Int, Double, Double>> = emptyList(),
    openingBalance: Double = 0.0
) {
    try {
        createAndShareFinancialSummaryPdfInternal(
            context = context,
            transactions = transactions,
            categories = categories,
            cards = cards,
            periodTitle = periodTitle,
            annualMonths = annualMonths,
            openingBalance = openingBalance
        )
    } catch (error: Exception) {
        Toast.makeText(
            context,
            "Não foi possível gerar ou compartilhar o arquivo.",
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun createAndShareFinancialSummaryPdfInternal(
    context: Context,
    transactions: List<TransactionEntity>,
    categories: List<CategoryDto>,
    cards: List<CreditCardDto>,
    periodTitle: String,
    annualMonths: List<Triple<Int, Double, Double>> = emptyList(),
    openingBalance: Double = 0.0
) {
    val locale = Locale("pt", "BR")
    val currency = NumberFormat.getCurrencyInstance(locale)
    val annualReport = annualMonths.isNotEmpty() || periodTitle.trim().startsWith("Ano", ignoreCase = true)
    val monthNames = listOf("Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho", "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro")

    // O relatório segue a mesma regra do saldo consolidado:
    // compras no cartão pertencem à fatura e não são fluxo de caixa;
    // somente o pagamento da fatura afeta as despesas da conta.
    val cashFlowTransactions = transactions.filter { tx ->
        tx.source != "card_purchase" && !(tx.cardId != null && tx.source != "card_payment")
    }

    val income = cashFlowTransactions
        .filter { it.amount > 0 }
        .sumOf { it.amount }

    val commonExpenseTransactions =
        cashFlowTransactions.filter {
            it.amount < 0 && it.source != "card_payment"
        }

    val cardExpenseTransactions =
        cashFlowTransactions.filter {
            it.amount < 0 && it.source == "card_payment"
        }

    // Compras no cartão aparecem no relatório em uma seção informativa própria.
    // Elas não entram novamente nas despesas da conta nem no saldo do período.
    val cardPurchaseTransactions =
        transactions.filter { tx ->
            tx.amount < 0 &&
                tx.source != "card_payment" &&
                (tx.source == "card_purchase" || tx.cardId != null || tx.installmentGroup != null)
        }

    val commonExpenses =
        commonExpenseTransactions
            .sumOf { abs(it.amount) }

    val cardExpenses =
        cardExpenseTransactions
            .sumOf { abs(it.amount) }

    val cardExpenseTotals =
        cardExpenseTransactions
            .groupBy {
                it.cardId
            }
            .mapNotNull { entry ->
                val card =
                    cards.firstOrNull {
                        it.id == entry.key
                    }

                if (card == null) {
                    null
                } else {
                    card to
                        entry.value.sumOf {
                            abs(it.amount)
                        }
                }
            }
            .sortedByDescending {
                it.second
            }

    val expenses =
        commonExpenses + cardExpenses

    val balance = openingBalance + income - expenses

    val categoryExpenses = cashFlowTransactions
        .filter { it.amount < 0 }
        .groupBy { it.categoryId }
        .map { entry ->
            val name = categories.firstOrNull {
                it.id == entry.key
            }?.name ?: "Sem categoria"

            name to entry.value.sumOf {
                abs(it.amount)
            }
        }
        .sortedByDescending { it.second }

    val document = PdfDocument()

    val pageWidth = 595
    val pageHeight = 842
    val margin = 38f
    val contentWidth = pageWidth - margin * 2
    val bottomLimit = pageHeight - 45f

    val primary = Color.rgb(35, 88, 166)
    val primaryLight = Color.rgb(235, 242, 252)
    val green = Color.rgb(23, 138, 58)
    val red = Color.rgb(198, 40, 40)
    val dark = Color.rgb(35, 39, 47)
    val muted = Color.rgb(100, 108, 120)
    val border = Color.rgb(220, 224, 230)
    val white = Color.WHITE
    val chartBackground = Color.rgb(241, 243, 246)

    fun makePaint(
        size: Float,
        color: Int = dark,
        bold: Boolean = false
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) {
            Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
        } else {
            Typeface.DEFAULT
        }
    }

    val titlePaint = makePaint(26f, white, true)
    val subtitlePaint = makePaint(
        12f,
        Color.rgb(224, 234, 250)
    )
    val headingPaint = makePaint(16f, dark, true)
    val bodyPaint = makePaint(10.5f, dark)
    val smallPaint = makePaint(8.5f, muted)

    var pageNumber = 0
    var page: PdfDocument.Page? = null
    var y = margin

    fun canvas() = requireNotNull(page).canvas

    fun startPage(includeHeader: Boolean = true) {
        pageNumber += 1

        val info = PdfDocument.PageInfo.Builder(
            pageWidth,
            pageHeight,
            pageNumber
        ).create()

        page = document.startPage(info)
        y = margin

        if (includeHeader) {
            canvas().drawText(
                "Finance App",
                margin,
                y,
                makePaint(13f, primary, true)
            )

            canvas().drawText(
                "Resumo financeiro • $periodTitle",
                margin,
                y + 18f,
                smallPaint
            )

            y += 38f
        }
    }

    fun finishPage() {
        page?.let {
            document.finishPage(it)
        }
        page = null
    }

    fun ensure(height: Float) {
        if (
            page == null ||
            y + height > bottomLimit
        ) {
            if (page != null) {
                finishPage()
            }
            startPage()
        }
    }

    fun roundedCard(
        top: Float,
        height: Float,
        fill: Int = white
    ) {
        val rect = RectF(
            margin,
            top,
            pageWidth - margin,
            top + height
        )

        canvas().drawRoundRect(
            rect,
            12f,
            12f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fill
                style = Paint.Style.FILL
            }
        )

        canvas().drawRoundRect(
            rect,
            12f,
            12f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = border
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
        )
    }

    fun sectionTitle(title: String) {
        ensure(34f)
        canvas().drawText(
            title,
            margin,
            y + 16f,
            headingPaint
        )
        y += 30f
    }

    startPage(includeHeader = false)

    canvas().drawRect(
        RectF(
            0f,
            0f,
            pageWidth.toFloat(),
            155f
        ),
        Paint().apply {
            color = primary
        }
    )

    canvas().drawText(
        "Finance App",
        margin,
        62f,
        titlePaint
    )

    canvas().drawText(
        "Relatório financeiro",
        margin,
        91f,
        makePaint(18f, white, true)
    )

    canvas().drawText(
        periodTitle,
        margin,
        116f,
        subtitlePaint
    )

    canvas().drawText(
        "Fluxo de caixa: compras no cartão aparecem na fatura; só o pagamento afeta as despesas.",
        margin,
        139f,
        makePaint(8.5f, Color.rgb(224, 234, 250))
    )

    y = 182f

    val gap = 10f
    val cardWidth =
        (contentWidth - gap) / 2f
    val cardHeight = 72f

    fun metricCard(
        left: Float,
        top: Float,
        title: String,
        value: String,
        valueColor: Int
    ) {
        val rect = RectF(
            left,
            top,
            left + cardWidth,
            top + cardHeight
        )

        canvas().drawRoundRect(
            rect,
            12f,
            12f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = white
            }
        )

        canvas().drawRoundRect(
            rect,
            12f,
            12f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = border
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
        )

        canvas().drawText(
            title,
            left + 13f,
            top + 23f,
            smallPaint
        )

        canvas().drawText(
            value,
            left + 13f,
            top + 50f,
            makePaint(14f, valueColor, true)
        )
    }

    metricCard(
        margin,
        y,
        "Entradas",
        currency.format(income),
        green
    )

    metricCard(
        margin + cardWidth + gap,
        y,
        "Despesas (saídas)",
        currency.format(expenses),
        red
    )

    y += cardHeight + gap

    val balanceRect = RectF(margin, y, pageWidth - margin, y + cardHeight)
    canvas().drawRoundRect(balanceRect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white })
    canvas().drawRoundRect(balanceRect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = border
        style = Paint.Style.STROKE
        strokeWidth = 1f
    })
    canvas().drawText("SALDO", margin + 13f, y + 23f, smallPaint)
    canvas().drawText(
        currency.format(balance),
        margin + 13f,
        y + 50f,
        makePaint(14f, if (balance >= 0) green else red, true)
    )

    y += cardHeight + 10f

    if (!annualReport) {
        canvas().drawText(
            "Saldo anterior: ${currency.format(openingBalance)} • Saldo final: ${currency.format(balance)}",
            margin,
            y + 10f,
            smallPaint
        )
        y += 28f
    } else {
        y += 18f
    }

    sectionTitle("Entradas x Saídas")

    ensure(135f)
    roundedCard(
        y,
        125f,
        primaryLight
    )

    val graphLeft = margin + 24f
    val graphTop = y + 28f
    val graphWidth = contentWidth - 48f
    val maxMovement = max(
        max(income, expenses),
        1.0
    )

    fun horizontalBar(
        label: String,
        value: Double,
        top: Float,
        color: Int
    ) {
        canvas().drawText(
            label,
            graphLeft,
            top,
            smallPaint
        )

        val bg = RectF(
            graphLeft + 68f,
            top - 10f,
            graphLeft + graphWidth,
            top + 2f
        )

        canvas().drawRoundRect(
            bg,
            6f,
            6f,
            Paint().apply {
                this.color = chartBackground
            }
        )

        val ratio = (
            value / maxMovement
            ).coerceIn(0.0, 1.0).toFloat()

        val bar = RectF(
            bg.left,
            bg.top,
            bg.left + bg.width() * ratio,
            bg.bottom
        )

        canvas().drawRoundRect(
            bar,
            6f,
            6f,
            Paint().apply {
                this.color = color
            }
        )

        canvas().drawText(
            currency.format(value),
            graphLeft + 68f,
            top + 18f,
            smallPaint
        )
    }

    horizontalBar(
        "Entradas",
        income,
        graphTop,
        green
    )

    horizontalBar(
        "Saídas",
        expenses,
        graphTop + 58f,
        red
    )

    y += 142f

    sectionTitle("Gastos por categoria")

    if (categoryExpenses.isEmpty()) {
        ensure(60f)
        roundedCard(y, 50f)

        canvas().drawText(
            "Nenhuma saída registrada no período.",
            margin + 14f,
            y + 29f,
            bodyPaint
        )

        y += 66f
    } else {
        val topCategories = categoryExpenses.take(8)
        val maxCategory = max(
            topCategories.maxOfOrNull {
                it.second
            } ?: 0.0,
            1.0
        )

        topCategories.forEach { item ->
            ensure(58f)

            val top = y
            val amount = item.second
            val share =
                if (expenses > 0.0) {
                    amount / expenses * 100.0
                } else {
                    0.0
                }

            canvas().drawText(
                item.first.take(34),
                margin,
                top + 13f,
                bodyPaint
            )

            canvas().drawText(
                currency.format(amount),
                pageWidth - margin - 95f,
                top + 13f,
                makePaint(9.5f, dark, true)
            )

            val barTop = top + 25f

            val bg = RectF(
                margin,
                barTop,
                pageWidth - margin,
                barTop + 10f
            )

            canvas().drawRoundRect(
                bg,
                5f,
                5f,
                Paint().apply {
                    color = chartBackground
                }
            )

            val ratio = (
                amount / maxCategory
                ).coerceIn(0.0, 1.0).toFloat()

            val fg = RectF(
                bg.left,
                bg.top,
                bg.left + bg.width() * ratio,
                bg.bottom
            )

            canvas().drawRoundRect(
                fg,
                5f,
                5f,
                Paint().apply {
                    color = primary
                }
            )

            canvas().drawText(
                "${String.format(locale, "%.1f", share)}% das saídas",
                margin,
                barTop + 27f,
                smallPaint
            )

            y += 57f
        }
    }

    y += 10f

    fun transactionSection(
        title: String,
        items: List<TransactionEntity>,
        emptyText: String
    ) {
        sectionTitle(title)

        if (items.isEmpty()) {
            ensure(42f)
            canvas().drawText(
                emptyText,
                margin,
                y + 16f,
                bodyPaint
            )
            y += 34f
            return
        }

        val sortedItems = items.sortedBy { parseSummaryPdfDate(it.date) }
        var currentMonth: Int? = null
        sortedItems.forEach { tx ->
                val txMonth = parseSummaryPdfDate(tx.date)?.monthValue
                if (annualReport && txMonth != null && txMonth != currentMonth) {
                    ensure(34f)
                    currentMonth = txMonth
                    canvas().drawText(
                        monthNames[txMonth - 1],
                        margin,
                        y + 16f,
                        makePaint(11f, primary, true)
                    )
                    y += 28f
                }
                ensure(52f)

                val categoryName =
                    categories.firstOrNull {
                        it.id == tx.categoryId
                    }?.name ?: "Sem categoria"

                val date =
                    parseSummaryPdfDate(tx.date)
                        ?.let {
                            "%02d/%02d/%04d".format(
                                it.dayOfMonth,
                                it.monthValue,
                                it.year
                            )
                        }
                        ?: tx.date.take(10)

                val installment =
                    if (
                        tx.installmentNumber != null &&
                        tx.installmentTotal != null
                    ) {
                        " • ${tx.installmentNumber}/${tx.installmentTotal}"
                    } else {
                        ""
                    }

                canvas().drawText(
                    tx.description.take(42),
                    margin,
                    y + 13f,
                    makePaint(10f, dark, true)
                )

                val cardLabel =
                    tx.cardId?.let {
                            cardId ->
                        cards.firstOrNull {
                            it.id == cardId
                        }?.let {
                            " • cartão •••• ${it.lastFour}"
                        }
                    }.orEmpty()

                canvas().drawText(
                    "$date • $categoryName$installment$cardLabel",
                    margin,
                    y + 29f,
                    smallPaint
                )

                canvas().drawText(
                    currency.format(tx.amount),
                    pageWidth - margin - 95f,
                    y + 20f,
                    makePaint(
                        10f,
                        if (tx.amount >= 0) green else red,
                        true
                    )
                )

                canvas().drawLine(
                    margin,
                    y + 42f,
                    pageWidth - margin,
                    y + 42f,
                    Paint().apply {
                        color = border
                        strokeWidth = 1f
                    }
                )

                y += 50f
            }

        y += 8f
    }

    transactionSection(
        title = "Entradas",
        items = cashFlowTransactions.filter { it.amount > 0 },
        emptyText = "Nenhuma entrada registrada no período."
    )

    transactionSection(
        title = "Despesas (saídas)",
        items = cashFlowTransactions.filter { it.amount < 0 },
        emptyText = "Nenhuma despesa registrada no período."
    )

    sectionTitle("Despesas com cartão")

    val purchasesByCard = cardPurchaseTransactions
        .groupBy { it.cardId }
        .toList()
        .sortedByDescending { (_, items) -> items.sumOf { abs(it.amount) } }

    if (purchasesByCard.isEmpty()) {
        ensure(52f)
        roundedCard(y, 42f)
        canvas().drawText(
            "Nenhuma compra com cartão no período.",
            margin + 14f,
            y + 25f,
            bodyPaint
        )
        y += 56f
    } else {
        purchasesByCard.forEach { (cardId, items) ->
            val card = cards.firstOrNull { it.id == cardId }
            val cardName = if (card != null) {
                "${card.bankName} •••• ${card.lastFour}"
            } else {
                "Cartão não identificado"
            }
            val cardTotal = items.sumOf { abs(it.amount) }

            ensure(58f)
            roundedCard(y, 48f)
            canvas().drawText(
                cardName, margin + 14f, y + 18f,
                makePaint(11f, dark, true)
            )
            canvas().drawText(
                "Total de compras: ${currency.format(cardTotal)}",
                margin + 14f, y + 35f,
                makePaint(9f, red, true)
            )
            y += 56f

            items.sortedBy { parseSummaryPdfDate(it.date) }.forEach { tx ->
                ensure(48f)
                val categoryName = categories.firstOrNull { it.id == tx.categoryId }?.name ?: "Sem categoria"
                val date = formatSummaryPdfCardDateTime(tx.date)
                val installment = if (tx.installmentNumber != null && tx.installmentTotal != null) {
                    " • Parcela ${tx.installmentNumber}/${tx.installmentTotal}"
                } else ""

                canvas().drawText(
                    tx.description.take(42), margin + 10f, y + 13f,
                    makePaint(9.5f, dark, true)
                )
                canvas().drawText(
                    "$date • $categoryName$installment",
                    margin + 10f, y + 29f, smallPaint
                )
                canvas().drawText(
                    currency.format(abs(tx.amount)),
                    pageWidth - margin - 95f, y + 20f,
                    makePaint(9.5f, red, true)
                )
                canvas().drawLine(
                    margin + 10f, y + 40f, pageWidth - margin, y + 40f,
                    Paint().apply { color = border; strokeWidth = 1f }
                )
                y += 46f
            }
            y += 10f
        }
    }

    y += 8f


    finishPage()

    val reportsDir = File(
        context.cacheDir,
        "reports"
    ).apply {
        mkdirs()
    }

    val safeTitle =
        periodTitle
            .lowercase(locale)
            .replace(
                Regex("[^a-z0-9]+"),
                "_"
            )
            .trim('_')

    val file = File(
        reportsDir,
        "resumo_financeiro_$safeTitle.pdf"
    )

    FileOutputStream(file).use {
        document.writeTo(it)
    }

    document.close()

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val intent = Intent(
        Intent.ACTION_SEND
    ).apply {
        type = "application/pdf"

        putExtra(
            Intent.EXTRA_STREAM,
            uri
        )

        putExtra(
            Intent.EXTRA_SUBJECT,
            "Resumo financeiro - $periodTitle"
        )

        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    context.startActivity(
        Intent.createChooser(
            intent,
            "Compartilhar resumo financeiro"
        )
    )
}

private fun parseSummaryPdfDate(
    raw: String
): LocalDate? =
    runCatching {
        LocalDate.parse(raw.take(10))
    }.getOrNull()

private fun formatSummaryPdfCardDateTime(raw: String): String {
    val date = parseSummaryPdfDate(raw)?.let {
        "%02d/%02d/%04d".format(it.dayOfMonth, it.monthValue, it.year)
    } ?: raw.take(10)
    val time = Regex("[T ](\\d{2}):(\\d{2})").find(raw)
    return if (time != null) "$date ${time.groupValues[1]}:${time.groupValues[2]}" else date
}
