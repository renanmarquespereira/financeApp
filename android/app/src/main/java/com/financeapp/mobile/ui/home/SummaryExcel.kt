package com.financeapp.mobile.ui.home

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.financeapp.mobile.data.local.TransactionEntity
import com.financeapp.mobile.data.remote.CategoryDto
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

fun createAndShareFinancialSummaryExcel(
    context: Context,
    transactions: List<TransactionEntity>,
    categories: List<CategoryDto>,
    periodTitle: String
) {
    try {
        if (transactions.isEmpty()) {
            Toast.makeText(
                context,
                "Não há dados para exportar.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val file =
            buildXlsxFile(
                context = context,
                transactions = transactions,
                categories = categories,
                periodTitle = periodTitle
            )

        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

        val share =
            Intent(
                Intent.ACTION_SEND
            ).apply {
                type =
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

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
                share,
                "Compartilhar Excel"
            )
        )
    } catch (error: Exception) {
        Toast.makeText(
            context,
            "Não foi possível gerar a planilha Excel.",
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun buildXlsxFile(
    context: Context,
    transactions: List<TransactionEntity>,
    categories: List<CategoryDto>,
    periodTitle: String
): File {
    val locale =
        Locale("pt", "BR")

    val income =
        transactions
            .filter {
                it.amount > 0.0
            }
            .sumOf {
                it.amount
            }

    val expense =
        transactions
            .filter {
                it.amount < 0.0
            }
            .sumOf {
                abs(it.amount)
            }

    val balance =
        income - expense

    val byCategory =
        transactions
            .filter {
                it.amount < 0.0
            }
            .groupBy {
                it.categoryId
            }
            .map {
                    item ->
                val name =
                    categories.firstOrNull {
                        it.id ==
                            item.key
                    }?.name
                        ?: "Sem categoria"

                name to
                    item.value.sumOf {
                        abs(it.amount)
                    }
            }
            .sortedByDescending {
                it.second
            }

    val safeName =
        periodTitle
            .lowercase(locale)
            .replace(
                Regex("[^a-z0-9]+"),
                "_"
            )
            .trim('_')
            .ifBlank {
                "periodo"
            }

    val reports =
        File(
            context.cacheDir,
            "reports"
        ).apply {
            mkdirs()
        }

    val file =
        File(
            reports,
            "resumo_financeiro_$safeName.xlsx"
        )

    if (file.exists()) {
        file.delete()
    }

    ZipOutputStream(
        file.outputStream()
            .buffered()
    ).use {
            zip ->

        fun entry(
            path: String,
            content: String
        ) {
            zip.putNextEntry(
                ZipEntry(path)
            )

            zip.write(
                content
                    .trimIndent()
                    .toByteArray(
                        Charsets.UTF_8
                    )
            )

            zip.closeEntry()
        }

        entry(
            "[Content_Types].xml",
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types
                xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default
                    Extension="rels"
                    ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default
                    Extension="xml"
                    ContentType="application/xml"/>
                <Override
                    PartName="/xl/workbook.xml"
                    ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override
                    PartName="/xl/styles.xml"
                    ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                <Override
                    PartName="/xl/worksheets/sheet1.xml"
                    ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override
                    PartName="/xl/worksheets/sheet2.xml"
                    ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            </Types>
            """
        )

        entry(
            "_rels/.rels",
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships
                xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship
                    Id="rId1"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                    Target="xl/workbook.xml"/>
            </Relationships>
            """
        )

        entry(
            "xl/workbook.xml",
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook
                xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets>
                    <sheet
                        name="Resumo"
                        sheetId="1"
                        r:id="rId1"/>
                    <sheet
                        name="Transações"
                        sheetId="2"
                        r:id="rId2"/>
                </sheets>
            </workbook>
            """
        )

        entry(
            "xl/_rels/workbook.xml.rels",
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships
                xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship
                    Id="rId1"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                    Target="worksheets/sheet1.xml"/>
                <Relationship
                    Id="rId2"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                    Target="worksheets/sheet2.xml"/>
                <Relationship
                    Id="rId3"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
                    Target="styles.xml"/>
            </Relationships>
            """
        )

        entry(
            "xl/styles.xml",
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet
                xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">

                <numFmts count="1">
                    <numFmt
                        numFmtId="164"
                        formatCode="&quot;R$&quot; #,##0.00;[Red]-&quot;R$&quot; #,##0.00"/>
                </numFmts>

                <fonts count="3">
                    <font>
                        <sz val="11"/>
                        <name val="Calibri"/>
                    </font>

                    <font>
                        <b/>
                        <color rgb="FFFFFFFF"/>
                        <sz val="11"/>
                        <name val="Calibri"/>
                    </font>

                    <font>
                        <b/>
                        <sz val="15"/>
                        <name val="Calibri"/>
                    </font>
                </fonts>

                <fills count="3">
                    <fill>
                        <patternFill patternType="none"/>
                    </fill>

                    <fill>
                        <patternFill patternType="gray125"/>
                    </fill>

                    <fill>
                        <patternFill patternType="solid">
                            <fgColor rgb="FF2358A6"/>
                            <bgColor indexed="64"/>
                        </patternFill>
                    </fill>
                </fills>

                <borders count="1">
                    <border>
                        <left/>
                        <right/>
                        <top/>
                        <bottom/>
                        <diagonal/>
                    </border>
                </borders>

                <cellStyleXfs count="1">
                    <xf
                        numFmtId="0"
                        fontId="0"
                        fillId="0"
                        borderId="0"/>
                </cellStyleXfs>

                <cellXfs count="4">
                    <xf
                        numFmtId="0"
                        fontId="0"
                        fillId="0"
                        borderId="0"
                        xfId="0"/>

                    <xf
                        numFmtId="0"
                        fontId="1"
                        fillId="2"
                        borderId="0"
                        xfId="0"
                        applyFont="1"
                        applyFill="1"/>

                    <xf
                        numFmtId="0"
                        fontId="2"
                        fillId="0"
                        borderId="0"
                        xfId="0"
                        applyFont="1"/>

                    <xf
                        numFmtId="164"
                        fontId="0"
                        fillId="0"
                        borderId="0"
                        xfId="0"
                        applyNumberFormat="1"/>
                </cellXfs>

                <cellStyles count="1">
                    <cellStyle
                        name="Normal"
                        xfId="0"
                        builtinId="0"/>
                </cellStyles>
            </styleSheet>
            """
        )

        entry(
            "xl/worksheets/sheet1.xml",
            buildSummarySheetXml(
                periodTitle = periodTitle,
                income = income,
                expense = expense,
                balance = balance,
                transactionCount =
                    transactions.size,
                byCategory = byCategory
            )
        )

        entry(
            "xl/worksheets/sheet2.xml",
            buildTransactionsSheetXml(
                transactions =
                    transactions,
                categories =
                    categories
            )
        )
    }

    return file
}

private fun buildSummarySheetXml(
    periodTitle: String,
    income: Double,
    expense: Double,
    balance: Double,
    transactionCount: Int,
    byCategory: List<Pair<String, Double>>
): String {
    var row = 1

    val rows =
        buildString {
            append(
                xlsxRow(
                    row++,
                    listOf(
                        xlsxTextCell(
                            "A1",
                            "Resumo financeiro",
                            style = 2
                        )
                    )
                )
            )

            append(
                xlsxRow(
                    row++,
                    listOf(
                        xlsxTextCell(
                            "A2",
                            periodTitle
                        )
                    )
                )
            )

            row++

            append(
                xlsxRow(
                    row,
                    listOf(
                        xlsxTextCell(
                            "A$row",
                            "Indicador",
                            style = 1
                        ),
                        xlsxTextCell(
                            "B$row",
                            "Valor",
                            style = 1
                        )
                    )
                )
            )
            row++

            append(
                xlsxRow(
                    row,
                    listOf(
                        xlsxTextCell(
                            "A$row",
                            "Entradas"
                        ),
                        xlsxNumberCell(
                            "B$row",
                            income,
                            style = 3
                        )
                    )
                )
            )
            row++

            append(
                xlsxRow(
                    row,
                    listOf(
                        xlsxTextCell(
                            "A$row",
                            "Saídas"
                        ),
                        xlsxNumberCell(
                            "B$row",
                            expense,
                            style = 3
                        )
                    )
                )
            )
            row++

            append(
                xlsxRow(
                    row,
                    listOf(
                        xlsxTextCell(
                            "A$row",
                            "Saldo"
                        ),
                        xlsxNumberCell(
                            "B$row",
                            balance,
                            style = 3
                        )
                    )
                )
            )
            row++

            append(
                xlsxRow(
                    row,
                    listOf(
                        xlsxTextCell(
                            "A$row",
                            "Quantidade de transações"
                        ),
                        xlsxNumberCell(
                            "B$row",
                            transactionCount.toDouble()
                        )
                    )
                )
            )

            row += 2

            append(
                xlsxRow(
                    row,
                    listOf(
                        xlsxTextCell(
                            "A$row",
                            "Gastos por categoria",
                            style = 1
                        ),
                        xlsxTextCell(
                            "B$row",
                            "Valor",
                            style = 1
                        )
                    )
                )
            )
            row++

            if (byCategory.isEmpty()) {
                append(
                    xlsxRow(
                        row,
                        listOf(
                            xlsxTextCell(
                                "A$row",
                                "Nenhuma saída no período"
                            )
                        )
                    )
                )
            } else {
                byCategory.forEach {
                        item ->
                    append(
                        xlsxRow(
                            row,
                            listOf(
                                xlsxTextCell(
                                    "A$row",
                                    item.first
                                ),
                                xlsxNumberCell(
                                    "B$row",
                                    item.second,
                                    style = 3
                                )
                            )
                        )
                    )
                    row++
                }
            }
        }

    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet
            xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <sheetViews>
                <sheetView workbookViewId="0">
                    <pane
                        ySplit="1"
                        topLeftCell="A2"
                        activePane="bottomLeft"
                        state="frozen"/>
                </sheetView>
            </sheetViews>

            <cols>
                <col min="1" max="1" width="32" customWidth="1"/>
                <col min="2" max="2" width="18" customWidth="1"/>
            </cols>

            <sheetData>
                $rows
            </sheetData>
        </worksheet>
    """.trimIndent()
}

private fun buildTransactionsSheetXml(
    transactions: List<TransactionEntity>,
    categories: List<CategoryDto>
): String {
    var row = 1

    val rows =
        buildString {
            append(
                xlsxRow(
                    row,
                    listOf(
                        xlsxTextCell(
                            "A$row",
                            "Data",
                            style = 1
                        ),
                        xlsxTextCell(
                            "B$row",
                            "Descrição",
                            style = 1
                        ),
                        xlsxTextCell(
                            "C$row",
                            "Tipo",
                            style = 1
                        ),
                        xlsxTextCell(
                            "D$row",
                            "Categoria",
                            style = 1
                        ),
                        xlsxTextCell(
                            "E$row",
                            "Valor",
                            style = 1
                        ),
                        xlsxTextCell(
                            "F$row",
                            "Parcela",
                            style = 1
                        ),
                        xlsxTextCell(
                            "G$row",
                            "Origem",
                            style = 1
                        )
                    )
                )
            )

            row++

            transactions
                .sortedByDescending {
                    it.date
                }
                .forEach {
                        tx ->
                    val category =
                        categories.firstOrNull {
                            it.id ==
                                tx.categoryId
                        }?.name
                            ?: "Sem categoria"

                    val installment =
                        if (
                            tx.installmentNumber !=
                            null &&
                            tx.installmentTotal !=
                            null
                        ) {
                            "${tx.installmentNumber}/${tx.installmentTotal}"
                        } else {
                            ""
                        }

                    val source =
                        if (
                            tx.source ==
                            "open_finance"
                        ) {
                            "Automática"
                        } else {
                            "Manual"
                        }

                    append(
                        xlsxRow(
                            row,
                            listOf(
                                xlsxTextCell(
                                    "A$row",
                                    formatExcelDate(
                                        tx.date
                                    )
                                ),
                                xlsxTextCell(
                                    "B$row",
                                    tx.description
                                ),
                                xlsxTextCell(
                                    "C$row",
                                    if (
                                        tx.amount <
                                        0.0
                                    ) {
                                        "Saída"
                                    } else {
                                        "Entrada"
                                    }
                                ),
                                xlsxTextCell(
                                    "D$row",
                                    category
                                ),
                                xlsxNumberCell(
                                    "E$row",
                                    tx.amount,
                                    style = 3
                                ),
                                xlsxTextCell(
                                    "F$row",
                                    installment
                                ),
                                xlsxTextCell(
                                    "G$row",
                                    source
                                )
                            )
                        )
                    )

                    row++
                }
        }

    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet
            xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <sheetViews>
                <sheetView workbookViewId="0">
                    <pane
                        ySplit="1"
                        topLeftCell="A2"
                        activePane="bottomLeft"
                        state="frozen"/>
                </sheetView>
            </sheetViews>

            <cols>
                <col min="1" max="1" width="19" customWidth="1"/>
                <col min="2" max="2" width="38" customWidth="1"/>
                <col min="3" max="3" width="13" customWidth="1"/>
                <col min="4" max="4" width="22" customWidth="1"/>
                <col min="5" max="5" width="16" customWidth="1"/>
                <col min="6" max="6" width="11" customWidth="1"/>
                <col min="7" max="7" width="15" customWidth="1"/>
            </cols>

            <sheetData>
                $rows
            </sheetData>
        </worksheet>
    """.trimIndent()
}

private fun xlsxRow(
    row: Int,
    cells: List<String>
): String =
    """<row r="$row">${cells.joinToString("")}</row>"""

private fun xlsxTextCell(
    reference: String,
    value: String,
    style: Int = 0
): String {
    val styleValue =
        if (style > 0) {
            """ s="$style""""
        } else {
            ""
        }

    return """
        <c r="$reference"$styleValue t="inlineStr">
            <is>
                <t xml:space="preserve">${excelXmlEscape(value)}</t>
            </is>
        </c>
    """.trimIndent()
}

private fun xlsxNumberCell(
    reference: String,
    value: Double,
    style: Int = 0
): String {
    val styleValue =
        if (style > 0) {
            """ s="$style""""
        } else {
            ""
        }

    return """
        <c r="$reference"$styleValue>
            <v>$value</v>
        </c>
    """.trimIndent()
}

private fun excelXmlEscape(
    value: String
): String =
    value
        .replace(
            "&",
            "&amp;"
        )
        .replace(
            "<",
            "&lt;"
        )
        .replace(
            ">",
            "&gt;"
        )
        .replace(
            "\"",
            "&quot;"
        )
        .replace(
            "'",
            "&apos;"
        )

private fun formatExcelDate(
    value: String
): String =
    runCatching {
        OffsetDateTime
            .parse(value)
            .format(
                DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy HH:mm"
                )
            )
    }.getOrElse {
        value
            .replace(
                "T",
                " "
            )
            .take(16)
    }
