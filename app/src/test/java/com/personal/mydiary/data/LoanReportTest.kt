package com.personal.mydiary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

class LoanReportTest {
    private val installment = suggestedInstallment(100_000_00, 12.0, 12, false, null)
    private val report = LoanReport(
        100_000_00, 12.0, false, installment, repaymentSchedule(100_000_00, 12.0, false, installment, 12),
        otherTypeInterest = 12_000_00, createdOn = LocalDate.of(2026, 10, 31),
    )

    private fun xlsxParts(r: LoanReport): Map<String, String> {
        val out = ByteArrayOutputStream()
        LoanXlsx.write(r, out)
        val parts = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { parts[it.name] = zip.readBytes().decodeToString() }
        }
        return parts
    }

    @Test
    fun summaryHasTheFiguresOnScreen() {
        val lines = report.summaryLines().toMap()
        assertEquals("Rs 8,884.88", lines["Monthly installment"])
        assertEquals("12", lines["Months"])
        assertEquals("12% a year", lines["Interest rate"])
        assertEquals("Rs 12,000.00", lines["Total interest if Flat"])
    }

    @Test
    fun sheetRowsHoldTheScheduleAndSummaryTogether() {
        val rows = report.sheetRows()
        assertEquals(13, rows.size)
        assertEquals("1", rows[0]["Month"])
        assertEquals("8884.88", rows[0]["Payment (Rs)"])
        assertEquals("Loan amount", rows[0]["Summary"])
        assertEquals("Rs 100,000.00", rows[0]["Details"])
        assertEquals("Total", rows[12]["Month"])
        assertEquals("100000.00", rows[12]["Principal (Rs)"])
    }

    @Test
    fun aShortLoanStillKeepsEverySummaryLine() {
        val shortRows = repaymentSchedule(100_00, 0.0, false, 100_00, 1)
        val rows = LoanReport(100_00, 0.0, false, 100_00, shortRows, null).sheetRows()
        assertEquals(LoanReport(100_00, 0.0, false, 100_00, shortRows, null).summaryLines().size, rows.count { "Summary" in it })
        assertEquals("Total", rows[1]["Month"])
    }

    @Test
    fun tabNamesAreUniqueToTheMinuteAndAllowedBySheets() {
        val name = report.sheetTabName(java.time.LocalDateTime.of(2026, 10, 31, 15, 4))
        assertEquals("Loan calc 2026-10-31 15.04", name)
    }

    @Test
    fun workbookHasEveryRequiredPart() {
        val parts = xlsxParts(report)
        listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml")
            .forEach { assertTrue(it, it in parts) }
    }

    @Test
    fun scheduleCellsAreNumbersAndTotalsPointAtTheTable() {
        val sheet = xlsxParts(report).getValue("xl/worksheets/sheet1.xml")
        assertTrue("<v>8884.88</v>" in sheet)
        // 12 rows starting after the summary (with comparison line) and heading: 14 to 25.
        assertTrue("SUM(D14:D25)" in sheet)
        assertTrue("SUM(B14:B25)" in sheet)
        assertTrue("""<c r="A14" s="0"><v>1</v></c>""" in sheet)
    }

    @Test
    fun totalsLineUpWithoutTheComparisonLineToo() {
        val sheet = xlsxParts(LoanReport(report.amount, 12.0, false, installment, report.rows, null)).getValue("xl/worksheets/sheet1.xml")
        assertTrue("SUM(D13:D24)" in sheet)
    }
}
