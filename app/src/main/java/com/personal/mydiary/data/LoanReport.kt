package com.personal.mydiary.data

import java.io.OutputStream
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A worked-out loan from the calculator, ready to save as a file. */
class LoanReport(
    val amount: Long,
    val annualRate: Double,
    val flat: Boolean,
    val installment: Long,
    val rows: List<ScheduleRow>,
    /** Total interest under the other interest type, for comparison. */
    val otherTypeInterest: Long?,
    val createdOn: LocalDate = LocalDate.now(),
) {
    val totalInterest get() = rows.sumOf { it.interest }
    val totalPaid get() = rows.sumOf { it.payment }
    val typeName get() = if (flat) "Flat" else "Reducing balance"
    val otherTypeName get() = if (flat) "Reducing balance" else "Flat"
    val rateText: String get() = BigDecimal.valueOf(annualRate).stripTrailingZeros().toPlainString() + "%"

    /** Label and already-formatted value of each summary line, in display order. */
    fun summaryLines(): List<Pair<String, String>> = listOfNotNull(
        "Loan amount" to money(amount),
        "Interest rate" to "$rateText a year",
        "Interest type" to typeName,
        "Monthly installment" to money(installment),
        "Months" to rows.size.toString(),
        "Total interest" to money(totalInterest),
        "Total repayment" to money(totalPaid),
        otherTypeInterest?.let { "Total interest if $otherTypeName" to money(it) },
    )

    /** Tab name in the Google Sheet: one tab per saved calculation, so earlier ones are kept. */
    fun sheetTabName(at: LocalDateTime = LocalDateTime.now()): String =
        "Loan calc " + at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm"))

    /** The schedule on the left and the summary beside it, so both are visible at the top of the tab. */
    fun sheetRows(): List<Map<String, String>> {
        val summary = summaryLines()
        return List(maxOf(rows.size + 1, summary.size)) { i ->
            val cells = mutableMapOf<String, String>()
            val row = rows.getOrNull(i)
            if (row != null) {
                cells["Month"] = row.month.toString()
                cells["Payment (Rs)"] = plain(row.payment)
                cells["Principal (Rs)"] = plain(row.principal)
                cells["Interest (Rs)"] = plain(row.interest)
                cells["Left to pay (Rs)"] = plain(row.balance)
            } else if (i == rows.size) {
                cells["Month"] = "Total"
                cells["Payment (Rs)"] = plain(totalPaid)
                cells["Principal (Rs)"] = plain(rows.sumOf { it.principal })
                cells["Interest (Rs)"] = plain(totalInterest)
            }
            summary.getOrNull(i)?.let { (label, value) ->
                cells["Summary"] = label
                cells["Details"] = value
            }
            cells
        }
    }

    private fun plain(cents: Long) = BigDecimal(cents).movePointLeft(2).toPlainString()

    companion object {
        /** Column order in the Google Sheet tab; the blank-named column keeps the two blocks apart. */
        val SHEET_COLUMNS = listOf("Month", "Payment (Rs)", "Principal (Rs)", "Interest (Rs)", "Left to pay (Rs)", " ", "Summary", "Details")
        const val NOTE = "An estimate with a fixed rate and equal monthly payments. Your bank's figures may differ slightly."
        private val moneyPattern = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))

        fun money(cents: Long): String = "Rs " + moneyPattern.format(BigDecimal(cents).movePointLeft(2))
    }
}

/**
 * Writes a real .xlsx file. That format is a zip of XML parts, and this needs only a few of them,
 * so it doesn't take a large spreadsheet library. Amounts are numbers and totals are formulas,
 * so the sheet can be worked with in Excel or Google Sheets.
 */
object LoanXlsx {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    // Indexes into cellXfs in stylesXml.
    private const val STYLE_TEXT = 0
    private const val STYLE_TITLE = 1
    private const val STYLE_BOLD = 2
    private const val STYLE_MONEY = 3
    private const val STYLE_HEADER = 4
    private const val STYLE_TOTAL_MONEY = 5
    private const val STYLE_PERCENT = 6
    private const val STYLE_NOTE = 7

    fun write(report: LoanReport, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun part(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
            part("[Content_Types].xml", CONTENT_TYPES)
            part("_rels/.rels", ROOT_RELS)
            part("xl/workbook.xml", WORKBOOK)
            part("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            part("xl/styles.xml", STYLES)
            part("xl/worksheets/sheet1.xml", sheetXml(report))
        }
    }

    private class Sheet {
        val rows = StringBuilder()
        var row = 0

        fun addRow(vararg cells: String) {
            row++
            rows.append("<row r=\"$row\">")
            cells.forEachIndexed { i, cell -> rows.append(cell.replace(COLUMN, "${'A' + i}$row")) }
            rows.append("</row>")
        }

        fun skipRow() { row++ }
    }

    private const val COLUMN = "@CELL@"

    private fun text(value: String, style: Int = STYLE_TEXT) =
        """<c r="$COLUMN" s="$style" t="inlineStr"><is><t xml:space="preserve">${escape(value)}</t></is></c>"""

    private fun number(value: String, style: Int) = """<c r="$COLUMN" s="$style"><v>$value</v></c>"""

    private fun formula(formula: String, cached: String, style: Int) =
        """<c r="$COLUMN" s="$style"><f>$formula</f><v>$cached</v></c>"""

    private fun amount(cents: Long) = BigDecimal(cents).movePointLeft(2).toPlainString()

    private fun escape(value: String) =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun sheetXml(r: LoanReport): String {
        val s = Sheet()
        s.addRow(text("Loan calculation", STYLE_TITLE))
        s.addRow(text("Created ${r.createdOn}"))
        s.skipRow()
        s.addRow(text("Loan amount (Rs)", STYLE_BOLD), number(amount(r.amount), STYLE_MONEY))
        s.addRow(text("Interest rate a year", STYLE_BOLD), number(BigDecimal.valueOf(r.annualRate).movePointLeft(2).toPlainString(), STYLE_PERCENT))
        s.addRow(text("Interest type", STYLE_BOLD), text(r.typeName))
        s.addRow(text("Monthly installment (Rs)", STYLE_BOLD), number(amount(r.installment), STYLE_MONEY))
        s.addRow(text("Months", STYLE_BOLD), number(r.rows.size.toString(), STYLE_TEXT))
        val interestSummaryRow = s.row + 1
        // Filled in below once the table's position is known.
        val first = interestSummaryRow + 4 + if (r.otherTypeInterest != null) 1 else 0
        val last = first + r.rows.size - 1
        s.addRow(text("Total interest (Rs)", STYLE_BOLD), formula("SUM(D$first:D$last)", amount(r.totalInterest), STYLE_MONEY))
        s.addRow(text("Total repayment (Rs)", STYLE_BOLD), formula("SUM(B$first:B$last)", amount(r.totalPaid), STYLE_MONEY))
        r.otherTypeInterest?.let {
            s.addRow(text("Total interest if ${r.otherTypeName} (Rs)", STYLE_BOLD), number(amount(it), STYLE_MONEY))
        }
        s.skipRow()
        s.addRow(
            text("Month", STYLE_HEADER), text("Payment (Rs)", STYLE_HEADER), text("Principal (Rs)", STYLE_HEADER),
            text("Interest (Rs)", STYLE_HEADER), text("Left to pay (Rs)", STYLE_HEADER),
        )
        r.rows.forEach { row ->
            s.addRow(
                number(row.month.toString(), STYLE_TEXT), number(amount(row.payment), STYLE_MONEY), number(amount(row.principal), STYLE_MONEY),
                number(amount(row.interest), STYLE_MONEY), number(amount(row.balance), STYLE_MONEY),
            )
        }
        s.addRow(
            text("Total", STYLE_BOLD),
            formula("SUM(B$first:B$last)", amount(r.totalPaid), STYLE_TOTAL_MONEY),
            formula("SUM(C$first:C$last)", amount(r.rows.sumOf { it.principal }), STYLE_TOTAL_MONEY),
            formula("SUM(D$first:D$last)", amount(r.totalInterest), STYLE_TOTAL_MONEY),
        )
        s.skipRow()
        s.addRow(text(LoanReport.NOTE, STYLE_NOTE))
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="1" max="1" width="34" customWidth="1"/><col min="2" max="5" width="18" customWidth="1"/></cols><sheetData>${s.rows}</sheetData></worksheet>"""
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private const val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Loan schedule" sheetId="1" r:id="rId1"/></sheets></workbook>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="0.00%"/></numFmts><fonts count="5"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="16"/><color rgb="FF5A3A9E"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font><font><i/><sz val="10"/><color rgb="FF666666"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF5A3A9E"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="8"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="0" fontId="3" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="4" fontId="2" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="0" fontId="4" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
