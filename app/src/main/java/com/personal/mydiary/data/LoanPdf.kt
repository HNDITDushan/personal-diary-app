package com.personal.mydiary.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** Draws a [LoanReport] as an A4 PDF with Android's built-in PDF writer. */
object LoanPdf {
    const val MIME = "application/pdf"

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val ROW_HEIGHT = 18f
    private const val BOTTOM = PAGE_HEIGHT - 60f
    private val BRAND = Color.rgb(0x5A, 0x3A, 0x9E)
    private val BAND = Color.rgb(0xF3, 0xEF, 0xFA)
    private val MUTED = Color.rgb(0x66, 0x66, 0x66)

    /** Right edges of the amount columns; Month is left-aligned at the margin. */
    private val COLUMNS = listOf("Payment" to 240f, "Principal" to 340f, "Interest" to 440f, "Left to pay" to PAGE_WIDTH - MARGIN - 6)

    private fun paint(size: Float, color: Int = Color.BLACK, bold: Boolean = false, right: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
    }

    /** Plain figure for table cells; the column headings say Rs. */
    private fun figure(cents: Long) = LoanReport.money(cents).removePrefix("Rs ")

    fun write(report: LoanReport, out: OutputStream) {
        val pdf = PdfDocument()
        try {
            val tableTopFirst = 112f + report.summaryLines().size * 20f
            // The last slot on the last page holds the totals row.
            val firstCapacity = ((BOTTOM - tableTopFirst - ROW_HEIGHT) / ROW_HEIGHT).toInt()
            val otherCapacity = ((BOTTOM - MARGIN - ROW_HEIGHT) / ROW_HEIGHT).toInt()
            val rowsAndTotal = report.rows.size + 1
            val pageCount = 1 + if (rowsAndTotal <= firstCapacity) 0 else (rowsAndTotal - firstCapacity + otherCapacity - 1) / otherCapacity

            var next = 0
            for (pageNumber in 1..pageCount) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                val canvas = page.canvas
                var y = MARGIN

                if (pageNumber == 1) {
                    canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 84f, Paint().apply { color = BRAND })
                    canvas.drawText("Loan calculation", MARGIN, 46f, paint(22f, Color.WHITE, bold = true))
                    canvas.drawText("Created ${report.createdOn}", MARGIN, 68f, paint(11f, Color.WHITE))
                    y = 112f
                    report.summaryLines().forEach { (label, value) ->
                        canvas.drawText(label, MARGIN, y, paint(12f, MUTED))
                        canvas.drawText(value, PAGE_WIDTH - MARGIN, y, paint(12f, bold = true, right = true))
                        y += 20f
                    }
                    y = tableTopFirst
                }

                canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + ROW_HEIGHT, Paint().apply { color = BRAND })
                canvas.drawText("Month", MARGIN + 6, y + 13, paint(10f, Color.WHITE, bold = true))
                COLUMNS.forEach { (name, x) -> canvas.drawText("$name (Rs)", x, y + 13, paint(10f, Color.WHITE, bold = true, right = true)) }
                y += ROW_HEIGHT

                val capacity = if (pageNumber == 1) firstCapacity else otherCapacity
                val pageRows = report.rows.subList(next, minOf(next + capacity, report.rows.size))
                pageRows.forEachIndexed { i, row ->
                    if (i % 2 == 1) canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + ROW_HEIGHT, Paint().apply { color = BAND })
                    canvas.drawText(row.month.toString(), MARGIN + 6, y + 13, paint(10f))
                    listOf(row.payment, row.principal, row.interest, row.balance).forEachIndexed { c, cents ->
                        canvas.drawText(figure(cents), COLUMNS[c].second, y + 13, paint(10f, right = true))
                    }
                    y += ROW_HEIGHT
                }
                next += pageRows.size

                if (pageNumber == pageCount) {
                    canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, paint(1f, BRAND))
                    canvas.drawText("Total", MARGIN + 6, y + 13, paint(10f, bold = true))
                    listOf(report.totalPaid, report.rows.sumOf { it.principal }, report.totalInterest).forEachIndexed { c, cents ->
                        canvas.drawText(figure(cents), COLUMNS[c].second, y + 13, paint(10f, bold = true, right = true))
                    }
                }

                canvas.drawText(LoanReport.NOTE, MARGIN, PAGE_HEIGHT - 34f, paint(8f, MUTED))
                canvas.drawText("Page $pageNumber of $pageCount", PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 34f, paint(8f, MUTED, right = true))
                pdf.finishPage(page)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }
}
