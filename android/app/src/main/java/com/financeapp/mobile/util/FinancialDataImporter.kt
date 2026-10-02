package com.financeapp.mobile.util

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ImportedFinancialRow(
    val description: String,
    val amount: Double,
    val date: String,
    val type: String,
    val account: String? = null,
    val category: String? = null,
    val card: String? = null,
)

object FinancialDataImporter {
    private var lastDetectedMapping: Map<String, String> = emptyMap()

    /** Campos reconhecidos no último arquivo analisado (destino -> cabeçalho de origem). */
    fun detectedMapping(): Map<String, String> = lastDetectedMapping

    private val dateAliases = listOf("data","date","dt_mov","data_movimento")
    private val descAliases = listOf("descricao","descrição","description","historico","histórico","memo")
    private val amountAliases = listOf("valor","amount","vl_transacao","vl_transação")
    private val typeAliases = listOf("tipo","type","entrada_saida","entrada/saida","natureza")
    private val accountAliases = listOf("conta","banco","account","bank")
    private val categoryAliases = listOf("categoria","category")
    private val cardAliases = listOf("cartao","cartão","card")

    fun parse(name: String, bytes: ByteArray): List<ImportedFinancialRow> {
        lastDetectedMapping = emptyMap()
        return when(name.substringAfterLast('.', "").lowercase()) {
        "json" -> parseJson(bytes.toString(Charsets.UTF_8))
        "xml" -> parseXml(bytes)
        "xlsx" -> parseXlsx(bytes)
        "csv", "txt" -> parseDelimited(bytes.toString(Charsets.UTF_8))
        else -> error("Formato não suportado. Use XLSX, CSV, JSON, TXT ou XML.")
        }
    }

    private fun norm(v: String) = java.text.Normalizer.normalize(v.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    private fun value(map: Map<String,String>, aliases: List<String>): String? {
        val wanted = aliases.map(::norm).toSet()
        return map.entries.firstOrNull { norm(it.key) in wanted }?.value?.trim()?.takeIf { it.isNotEmpty() }
    }
    private fun money(raw: String): Double {
        var s = raw.trim().replace("R$", "").replace(" ", "")
        if (s.contains(',') && s.contains('.')) s = s.replace(".", "").replace(',', '.')
        else if (s.contains(',')) s = s.replace(',', '.')
        return s.toDoubleOrNull() ?: error("Valor inválido: $raw")
    }
    private fun isoDate(raw: String?): String {
        if (raw.isNullOrBlank()) return java.time.LocalDate.now().toString()
        val s = raw.trim()
        runCatching { return java.time.LocalDate.parse(s.take(10)).toString() }
        val patterns = listOf("dd/MM/yyyy","d/M/yyyy","dd-MM-yyyy","d-M-yyyy")
        for (p in patterns) runCatching { return java.time.LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern(p)).toString() }
        return java.time.LocalDate.now().toString()
    }
    private fun row(map: Map<String,String>): ImportedFinancialRow? {
        if (lastDetectedMapping.isEmpty()) {
            fun sourceFor(aliases: List<String>): String? {
                val wanted = aliases.map(::norm).toSet()
                return map.keys.firstOrNull { norm(it) in wanted }
            }
            lastDetectedMapping = linkedMapOf<String, String>().apply {
                sourceFor(dateAliases)?.let { put("data", it) }
                sourceFor(descAliases)?.let { put("descricao", it) }
                sourceFor(amountAliases)?.let { put("valor", it) }
                sourceFor(typeAliases)?.let { put("tipo", it) }
                sourceFor(accountAliases)?.let { put("conta", it) }
                sourceFor(categoryAliases)?.let { put("categoria", it) }
                sourceFor(cardAliases)?.let { put("cartao", it) }
            }
        }
        val desc = value(map, descAliases) ?: return null
        val rawAmount = value(map, amountAliases) ?: return null
        val parsed = money(rawAmount)
        val rawType = norm(value(map, typeAliases) ?: "")
        val type = when {
            rawType.contains("entrada") || rawType.contains("receita") || rawType.contains("income") || rawType.contains("credit") || rawType.contains("credito") -> "credit"
            rawType.contains("saida") || rawType.contains("despesa") || rawType.contains("expense") || rawType.contains("debit") || rawType.contains("debito") -> "debit"
            parsed < 0 -> "debit"
            else -> "credit"
        }
        return ImportedFinancialRow(desc, kotlin.math.abs(parsed), isoDate(value(map,dateAliases)), type,
            value(map,accountAliases), value(map,categoryAliases), value(map,cardAliases))
    }

    private fun parseDelimited(text: String): List<ImportedFinancialRow> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val delimiter = listOf(';',',','\t','|').maxBy { d -> lines.first().count { it == d } }
        fun split(line:String):List<String> { val out=mutableListOf<String>(); val b=StringBuilder(); var q=false; for(c in line){ if(c=='"') q=!q else if(c==delimiter&&!q){out+=b.toString();b.clear()} else b.append(c)}; out+=b.toString(); return out }
        val headers = split(lines.first())
        return lines.drop(1).mapNotNull { l -> val cells=split(l); row(headers.mapIndexed { i,h -> h to (cells.getOrNull(i)?:"") }.toMap()) }
    }
    private fun parseJson(text:String):List<ImportedFinancialRow>{
        val root=text.trim(); val arr=if(root.startsWith("[")) JSONArray(root) else JSONObject(root).optJSONArray("transactions") ?: JSONObject(root).optJSONArray("transacoes") ?: error("JSON sem lista de transações")
        return (0 until arr.length()).mapNotNull { i -> val o=arr.optJSONObject(i)?:return@mapNotNull null; row(o.keys().asSequence().associateWith { o.opt(it)?.toString()?:"" }) }
    }
    private fun parseXml(bytes:ByteArray):List<ImportedFinancialRow>{
        val doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(bytes)); val nodes=listOf("transaction","transacao","row","item").asSequence().map { doc.getElementsByTagName(it) }.firstOrNull { it.length>0 } ?: return emptyList()
        return (0 until nodes.length).mapNotNull { i -> val e=nodes.item(i) as? Element ?: return@mapNotNull null; val m=mutableMapOf<String,String>(); val children=e.childNodes; for(j in 0 until children.length){ val n=children.item(j); if(n.nodeType==org.w3c.dom.Node.ELEMENT_NODE)m[n.nodeName]=n.textContent }; row(m) }
    }
    private fun parseXlsx(bytes:ByteArray):List<ImportedFinancialRow>{
        val entries=mutableMapOf<String,ByteArray>(); ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while(true){ val e=z.nextEntry?:break; entries[e.name]=z.readBytes() } }
        val shared=entries["xl/sharedStrings.xml"]?.let { b -> val d=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(b)); val n=d.getElementsByTagName("si"); (0 until n.length).map { n.item(it).textContent } } ?: emptyList()
        val sheet=entries["xl/worksheets/sheet1.xml"] ?: error("Planilha sem primeira aba")
        val d=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(sheet)); val rows=d.getElementsByTagName("row"); val matrix=mutableListOf<List<String>>()
        for(i in 0 until rows.length){ val r=rows.item(i) as Element; val cells=r.getElementsByTagName("c"); val vals=mutableListOf<String>(); for(j in 0 until cells.length){ val c=cells.item(j) as Element; val ref=c.getAttribute("r"); val col=ref.takeWhile{it.isLetter()}.fold(0){a,ch->a*26+(ch.uppercaseChar()-'A'+1)}-1; while(vals.size<=col)vals.add(""); val v=c.getElementsByTagName("v").item(0)?.textContent?:""; vals[col]=if(c.getAttribute("t")=="s") shared.getOrNull(v.toIntOrNull()?:-1)?:"" else v }; matrix+=vals }
        if(matrix.size<2)return emptyList(); val h=matrix.first(); return matrix.drop(1).mapNotNull { cells -> row(h.mapIndexed { i,k -> k to (cells.getOrNull(i)?:"") }.toMap()) }
    }
}
