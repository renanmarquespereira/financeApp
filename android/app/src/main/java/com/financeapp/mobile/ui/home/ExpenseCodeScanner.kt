package com.financeapp.mobile.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.time.LocalDate
import java.util.Locale

internal data class ExpenseCodeDraft(
    val description: String,
    val amount: Double? = null,
    val dueDate: LocalDate? = null,
    val rawCode: String,
    val kind: String
)

internal object ExpenseCodeParser {
    private val bankNames = mapOf(
        "001" to "Banco do Brasil",
        "033" to "Santander",
        "077" to "Banco Inter",
        "104" to "Caixa",
        "237" to "Bradesco",
        "260" to "Nubank",
        "341" to "Itaú",
        "422" to "Safra",
        "756" to "Sicoob"
    )

    fun parse(rawValue: String): ExpenseCodeDraft? {
        val raw = rawValue.trim()
        if (raw.isBlank()) return null
        parsePix(raw)?.let { return it }
        val digits = raw.filter(Char::isDigit)
        if (digits.length == 44 && digits.firstOrNull() != '8') return parseBankBarcode(digits, raw)
        if (digits.length == 47) return parseBankTypedLine(digits, raw)
        if (digits.length == 44 || digits.length == 48) {
            return ExpenseCodeDraft("Conta / boleto lido", rawCode = raw, kind = "boleto")
        }
        return ExpenseCodeDraft("Despesa lida por código", rawCode = raw, kind = "codigo")
    }

    private fun parseBankBarcode(digits: String, raw: String): ExpenseCodeDraft {
        val bankCode = digits.take(3)
        val amount = digits.substring(9, 19).toLongOrNull()?.div(100.0)?.takeIf { it > 0.0 }
        val factor = digits.substring(5, 9).toIntOrNull()
        val bank = bankNames[bankCode]
        return ExpenseCodeDraft(
            description = if (bank != null) "Boleto • $bank" else "Boleto bancário",
            amount = amount,
            dueDate = factorToDate(factor),
            rawCode = raw,
            kind = "boleto"
        )
    }

    private fun parseBankTypedLine(digits: String, raw: String): ExpenseCodeDraft {
        val bankCode = digits.take(3)
        val factor = digits.substring(33, 37).toIntOrNull()
        val amount = digits.substring(37, 47).toLongOrNull()?.div(100.0)?.takeIf { it > 0.0 }
        val bank = bankNames[bankCode]
        return ExpenseCodeDraft(
            description = if (bank != null) "Boleto • $bank" else "Boleto bancário",
            amount = amount,
            dueDate = factorToDate(factor),
            rawCode = raw,
            kind = "boleto"
        )
    }

    private fun factorToDate(factor: Int?): LocalDate? {
        if (factor == null || factor <= 0) return null
        return if (factor >= 1000) LocalDate.of(2022, 5, 29).plusDays(factor.toLong())
        else LocalDate.of(1997, 10, 7).plusDays(factor.toLong())
    }

    private fun parsePix(raw: String): ExpenseCodeDraft? {
        if (!raw.startsWith("000201") || !raw.uppercase(Locale.ROOT).contains("BR.GOV.BCB.PIX")) return null
        val fields = readTlv(raw)
        val merchant = fields["59"]?.trim()?.takeIf { it.isNotBlank() }
        val city = fields["60"]?.trim()?.takeIf { it.isNotBlank() }
        val amount = fields["54"]?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0.0 }
        val label = buildString {
            append("Pix")
            if (merchant != null) append(" • ").append(merchant)
            else if (city != null) append(" • ").append(city)
        }
        return ExpenseCodeDraft(label, amount, rawCode = raw, kind = "pix")
    }

    private fun readTlv(value: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var i = 0
        while (i + 4 <= value.length) {
            val id = value.substring(i, i + 2)
            val len = value.substring(i + 2, i + 4).toIntOrNull() ?: break
            val start = i + 4
            val end = start + len
            if (end > value.length) break
            out[id] = value.substring(start, end)
            i = end
        }
        return out
    }
}

@Composable
internal fun rememberExpenseCodeScanner(
    onResult: (ExpenseCodeDraft) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    val currentResult by rememberUpdatedState(onResult)
    val currentError by rememberUpdatedState(onError)
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents.orEmpty()
        if (raw.isBlank()) return@rememberLauncherForActivityResult
        val draft = ExpenseCodeParser.parse(raw)
        if (draft != null) currentResult(draft)
        else currentError("Não foi possível interpretar o código lido.")
    }
    val options = remember {
        ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
            setPrompt("Aponte para o código de barras ou QR Code")
            setBeepEnabled(false)
            setOrientationLocked(false)
            setBarcodeImageEnabled(false)
        }
    }
    return remember(launcher, options) { { runCatching { launcher.launch(options) }.onFailure { currentError("Não foi possível abrir a câmera para leitura.") } } }
}
