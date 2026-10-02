package com.financeapp.mobile.ui.home

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Padrao unico: o estado do campo guarda apenas centavos/digitos; a formatacao e somente visual. */
internal object BrlMoney {
    private val formatter: NumberFormat
        get() = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))

    fun digits(raw: String, maxDigits: Int = 15): String =
        raw.filter(Char::isDigit).take(maxDigits).trimStart('0')

    fun fromDouble(value: Double?): String = value?.takeIf { it > 0.0 }?.let {
        BigDecimal.valueOf(it).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString()
    }.orEmpty()

    fun formatDigits(raw: String): String {
        val d = digits(raw)
        if (d.isBlank()) return ""
        return formatter.format(BigDecimal(d).movePointLeft(2))
    }

    fun toDouble(raw: String): Double = digits(raw).toLongOrNull()?.div(100.0) ?: 0.0
}

/**
 * Evita o bug de cursor no meio do valor formatado. O TextField edita somente os digitos
 * (centavos) e esta transformacao desenha R$, pontos e virgula sem alterar o texto editavel.
 */
internal object BrlMoneyVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = BrlMoney.digits(text.text)
        if (raw.isEmpty()) return TransformedText(AnnotatedString(""), OffsetMapping.Identity)
        val formatted = BrlMoney.formatDigits(raw)

        // Campo monetario e append-only durante digitacao: qualquer selecao/cursor visual
        // volta para o fim. Isso impede o IME de inserir o proximo digito dentro do prefixo R$.
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = formatted.length
            override fun transformedToOriginal(offset: Int): Int = raw.length
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}
