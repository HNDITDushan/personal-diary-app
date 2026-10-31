package com.personal.mydiary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StatementParserTest {
    private val today = LocalDate.of(2026, 10, 31)

    @Test
    fun fullDatesWithCreditMarkersAndSummary() {
        val r = StatementParser.parse(
            """
            ABC BANK CREDIT CARD STATEMENT
            Card Number 4567 12XX XXXX 8901
            Statement Date : 25/10/2026
            Payment Due Date : 15/11/2026
            Previous Balance 50,000.00
            Total Outstanding 54,150.00
            Minimum Payment 2,707.50
            28/09/2026 PAYMENT RECEIVED - THANK YOU 20,000.00 CR
            02/10/2026 KEELLS SUPER COLOMBO 03 12,500.50
            05/10/2026 CEYPETCO FILLING STATION 8,000.00
            10/10/2026 DIALOG RECHARGE 1,500.00
            12/10/2026 DARAZ REFUND 1,000.50 CR
            25/10/2026 INTEREST CHARGES 2,650.00
            25/10/2026 LATE PAYMENT FEE 500.00
            """.trimIndent(), today,
        )
        assertEquals(LocalDate.of(2026, 10, 25), r.statementDate)
        assertEquals(LocalDate.of(2026, 11, 15), r.dueDate)
        assertEquals(5_000_000L, r.previousBalance)
        assertEquals(5_415_000L, r.closingBalance)
        assertEquals(270_750L, r.minimumDue)
        assertEquals(
            listOf(TxnKind.CARD_PAYMENT, TxnKind.EXPENSE, TxnKind.EXPENSE, TxnKind.EXPENSE, TxnKind.REFUND, TxnKind.INTEREST, TxnKind.FEE),
            r.lines.map { it.kind },
        )
        assertEquals(1_250_050L, r.lines[1].amount)
        assertEquals("Food & groceries", r.lines[1].category)
        assertEquals("Fuel", r.lines[2].category)
        assertEquals("Bills & utilities", r.lines[3].category)
        assertEquals(0L, r.difference)
    }

    @Test
    fun totalsAddUpWhenEveryLineIsRead() {
        val r = StatementParser.parse(
            """
            Statement Date 25/10/2026
            Previous Balance 10,000.00
            Closing Balance 12,500.00
            01/10/2026 SHOP ONE 5,000.00
            02/10/2026 PAYMENT THANK YOU 3,000.00 CR
            03/10/2026 ANNUAL FEE 500.00
            """.trimIndent(), today,
        )
        assertEquals(0L, r.difference)
    }

    @Test
    fun missingLineShowsAsADifference() {
        val r = StatementParser.parse(
            """
            Previous Balance 10,000.00
            Closing Balance 15,000.00
            01/10/2026 SHOP ONE 3,000.00
            """.trimIndent(), today,
        )
        assertEquals(200_000L, r.difference)
    }

    @Test
    fun twoDatesWithoutYearAcrossNewYear() {
        val r = StatementParser.parse(
            """
            Statement Date: 10 Jan 2027
            28/12 29/12 ODEL COLOMBO 7 4,200.00
            05/01 06/01 UBER TRIP 650.00
            """.trimIndent(), today,
        )
        assertEquals(LocalDate.of(2026, 12, 28), r.lines[0].date)
        assertEquals(LocalDate.of(2027, 1, 5), r.lines[1].date)
        assertEquals("ODEL COLOMBO 7", r.lines[0].description)
        assertEquals("Transport", r.lines[1].category)
    }

    @Test
    fun monthNamesAndTwoDigitYears() {
        val r = StatementParser.parse("05 Oct 26 ARPICO KANDY Rs. 3,300.00\n7-Oct-2026 NETFLIX.COM 2,990.00", today)
        assertEquals(LocalDate.of(2026, 10, 5), r.lines[0].date)
        assertEquals("ARPICO KANDY", r.lines[0].description)
        assertEquals(330_000L, r.lines[0].amount)
        assertEquals(LocalDate.of(2026, 10, 7), r.lines[1].date)
    }

    @Test
    fun foreignCurrencyUsesTheLocalAmount() {
        val r = StatementParser.parse(
            "03/10/2026 AMAZON.COM USD 25.00 7,750.00\n04/10/2026 BOOKING.COM 100.00 EUR 33,000.00", today,
        )
        assertEquals(775_000L, r.lines[0].amount)
        assertEquals("AMAZON.COM", r.lines[0].description)
        assertEquals(3_300_000L, r.lines[1].amount)
        assertEquals("BOOKING.COM", r.lines[1].description)
    }

    @Test
    fun runningBalanceColumnGivesDirection() {
        val r = StatementParser.parse(
            """
            Opening Balance 10,000.00
            01/10/2026 SOME SHOP 2,000.00 12,000.00
            02/10/2026 CASH DEPOSIT BRANCH 5,000.00 7,000.00
            03/10/2026 RETURNED GOODS 1,000.00 6,000.00
            """.trimIndent(), today,
        )
        assertEquals(listOf(TxnKind.EXPENSE, TxnKind.CARD_PAYMENT, TxnKind.REFUND), r.lines.map { it.kind })
        assertEquals(listOf(200_000L, 500_000L, 100_000L), r.lines.map { it.amount })
    }

    @Test
    fun skipsSummaryRowsAndNonTransactions() {
        val r = StatementParser.parse(
            """
            25/10/2026 Statement Date 25/10/2026
            25/09/2026 PREVIOUS BALANCE 50,000.00
            01/10/2026 BALANCE TRANSFER FEE 1,000.00
            Page 1 of 3
            TOTAL 51,000.00
            No date here 400.00
            12/10/2026 SHOP WITH NO AMOUNT
            """.trimIndent(), today,
        )
        assertEquals(listOf("BALANCE TRANSFER FEE"), r.lines.map { it.description })
        assertEquals(TxnKind.FEE, r.lines[0].kind)
    }

    @Test
    fun masksCardNumbersAndKeepsOrdinaryWordsOutOfFees() {
        val r = StatementParser.parse(
            "01/10/2026 PAYMENT FROM 4567 1234 5678 8901 5,000.00 CR\n02/10/2026 PRIVATE HOSPITAL 900.00\n03/10/2026 TAXI SERVICE 400.00",
            today,
        )
        assertEquals("PAYMENT FROM ****", r.lines[0].description)
        assertEquals(TxnKind.EXPENSE, r.lines[1].kind)
        assertEquals("Health", r.lines[1].category)
        assertEquals(TxnKind.EXPENSE, r.lines[2].kind)
    }

    @Test
    fun differenceIsNullWithoutBothBalances() {
        assertNull(StatementParser.parse("01/10/2026 SHOP 5.00", today).difference)
    }
}
