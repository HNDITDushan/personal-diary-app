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

    /** Layout used by People's Bank, with invented values: two dates and DB/CR after each amount. */
    @Test
    fun dbMarkersAndTwoDatesAreAllRead() {
        val r = StatementParser.parse(
            """
            PROCESS DATE TRANSACTION DATE DESCRIPTION FOREIGN CURRENCY AMOUNT AMOUNT (LKR)
            Opening Balance: 10,000.00 DB
            27-Oct-2026 27-Oct-2026 Debit Interest 100.50 DB
            4111********1111
            Account: 00012345
            30-Sep-2026 30-Sep-2026 Online Re-payment Received - Thank you 2,000.00 CR
            12-Oct-2026 09-Oct-2026 Some Hospital (PVT) Ltd, Galle 170.00 DB
            16-Oct-2026 13-Oct-2026 Town Food City, Galle 3,280.00 DB
            25-Oct-2026 24-Oct-2026 TOWN MPCS FILLING, GALLE 4,040.00 DB
            Closing Balance: 15,590.50 DB
            Opening Balance 0.00
            Closing Balance 0.00
            """.trimIndent(), today,
        )
        assertEquals(
            listOf(TxnKind.INTEREST, TxnKind.CARD_PAYMENT, TxnKind.EXPENSE, TxnKind.EXPENSE, TxnKind.EXPENSE),
            r.lines.map { it.kind },
        )
        assertEquals(10_050L, r.lines[0].amount)
        assertEquals(LocalDate.of(2026, 9, 30), r.lines[1].date)
        assertEquals("Health", r.lines[2].category)
        assertEquals("Food & groceries", r.lines[3].category)
        assertEquals("Fuel", r.lines[4].category)
        assertEquals(1_000_000L, r.previousBalance)
        assertEquals(1_559_050L, r.closingBalance)
        assertEquals(0L, r.difference)
        // No statement date printed where it can be read, so the newest line stands in.
        assertEquals(LocalDate.of(2026, 10, 27), r.statementDate)
    }

    @Test
    fun summaryValuesCentredAboveTheirLabelsAreFound() {
        val r = StatementParser.parse(
            """
            27-Oct-2026
            STATEMENT DATE
            LKR
            BILLING CURRENCY
            56,000.00
            TOTAL AMOUNT DUE
            2,800.00
            MINIMUM PAYMENT DUE
            17-Nov-2026
            PAYMENT DUE DATE
            100,000.00
            CREDIT LIMIT
            """.trimIndent(), today,
        )
        assertEquals(LocalDate.of(2026, 10, 27), r.statementDate)
        assertEquals(LocalDate.of(2026, 11, 17), r.dueDate)
        assertEquals(280_000L, r.minimumDue)
    }

    /** Layout used by NTB, with invented values: a period, a balance grid, and repeated amount columns. */
    private val gridStatement = """
        Statement Period: 13-Sep-2026 to 12-Oct-2026
        Credit Limit 500,000 Minimum Payment Due 5,000.00
        Total Outstanding Due 110,500.00 Total Minimum Due 5,000.00
        Payment Due Date 02-NOV-2026
        Opening Purchases Cash Interest Charges Payments Other Closing
        Balance Advances Credits Balance
        100,000.00 12,000.00 0.00 1,500.00 500.00 3,000.00 500.00 110,500.00
        Post Date Transaction Date Description Transaction Currency Amount Amount (LKR)
        13 SEP 13 SEP MONTHLY CASH BACK SAVINGS CRDIT LKR 500.00 500.00 Cr
        29 SEP 29 SEP CASH PAYMENT-FINACLE LKR 3,000.00 3,000.00 Cr
        01 OCT 01 OCT INSTALLMENT CHARGE - <6/12> LKR 500.00 500.00 Dr
        01 OCT 01 OCT SOME BANK EMI 1 INST 6 - REM BAL - 52000.1 - <6 LKR 8,000.00 8,000.00 Dr
        /12>
        09 OCT 07 OCT KEELLS SUPER - GALLE LKR 4,000.00 4,000.00 Dr
        12 OCT 12 OCT DEBIT INTEREST LKR 1,500.00 1,500.00 Dr
        Example of how interest is calculated
        01 Mar Op. Bal. 0.00 01 Apr 01 Apr Op. Bal. 50,000.00 LKR 50,000.00 15 Mar - 31 Mar 16 28% p.a 613.70
        15 Mar Transaction 50,000.00 10 Apr 08 Apr Transaction 6,000.00 LKR 50,000.00 31 Mar - 20 Apr 21 28% p.a 805.48
        31 Mar Closing Bal. 50,000.00 20 Apr 20 Apr Payment. 2,000.00 Cr LKR 48,000.00* 20 Apr - 30 Apr 11 28% p.a 405.04
        30 Apr Closing Bal. 55,824.22 Total Interest Charged 1,824.22
    """.trimIndent()

    @Test
    fun repeatedDatesKeepTheRightYearAndDescription() {
        val r = StatementParser.parse(gridStatement, today)
        assertEquals("MONTHLY CASH BACK SAVINGS CRDIT", r.lines[0].description)
        assertEquals(LocalDate.of(2026, 9, 13), r.lines[0].date)
        assertEquals(LocalDate.of(2026, 10, 9), r.lines[4].date)
        assertEquals(LocalDate.of(2026, 10, 12), r.statementDate)
        assertEquals(LocalDate.of(2026, 11, 2), r.dueDate)
    }

    @Test
    fun workedExamplesInTheSmallPrintAreNotTransactions() {
        val r = StatementParser.parse(gridStatement, today)
        assertEquals(6, r.lines.size)
    }

    @Test
    fun currencyColumnsKeepTheCreditMarker() {
        val r = StatementParser.parse(gridStatement, today)
        assertEquals(
            listOf(TxnKind.REFUND, TxnKind.CARD_PAYMENT, TxnKind.FEE, TxnKind.EXPENSE, TxnKind.EXPENSE, TxnKind.INTEREST),
            r.lines.map { it.kind },
        )
        assertEquals(50_000L, r.lines[0].amount)
    }

    @Test
    fun balanceGridIsReadAndAddsUp() {
        val r = StatementParser.parse(gridStatement, today)
        assertEquals(10_000_000L, r.previousBalance)
        assertEquals(11_050_000L, r.closingBalance)
        assertEquals(500_000L, r.minimumDue)
        assertEquals(0L, r.difference)
    }

    @Test
    fun linesOutsideTheStatementPeriodAreDropped() {
        val r = StatementParser.parse(
            "Statement Period: 01-Oct-2026 to 31-Oct-2026\n05 OCT 05 OCT REAL SHOP 100.00 Dr\n05 MAR 05 MAR OLD EXAMPLE 200.00 Dr",
            today,
        )
        assertEquals(listOf("REAL SHOP"), r.lines.map { it.description })
    }

    @Test
    fun futureDatesAreNotUsedAsTheStatementDate() {
        val r = StatementParser.parse("05 Oct 2026 SHOP 100.00\n31 Mar 2031 SAMPLE 200.00", today)
        assertEquals(LocalDate.of(2026, 10, 5), r.statementDate)
    }

    /** Layout used by Commercial Bank, with invented values, and small print that looks like transactions. */
    private val billingStatement = """
        Billing Date 30/09/26
        Total Outstanding 10,900.00
        Minimum Payment Due 500.00
        Payment Due Date 21/10/26
        Credit Limit
        250,000.00
        Opening Balance
        Processed Date Transaction Date Description LKR Amount
        OPENING BALANCE 8,000.00
        04/09 04/09 Q+ Bill-pay DIALOG AXI 0770000000 200.00
        09/09 09/09 PAYMENT RECEIVED - THANK YOU 1,000.00 CR
        14/09 11/09 TOWN MPCS FILLINGALLE LK 3,000.00
        16/09 16/09 Q+ Bill-pay LANKA ELEC 0400000000 500.00
        30/09 30/09 INTEREST CHARGE 200.00
        ** CARD - 43250000****0000 SUBTOTAL- 2,900.00
        Closing Balance
        10,900.00
        21 Jul Late Fee Charge 1,000.00 26,175.00 fYaIh 29.06.2024 Kw;gz 675.00
        31 Jul Interest Charge 30.06.2024 ,Wjp kPjp 675.00
        21 Jul jhkjf; fl;lzk; 1,000.00
    """.trimIndent()

    @Test
    fun billingDateAnchorsTheStatementAndDropsOldSmallPrint() {
        val r = StatementParser.parse(billingStatement, today)
        assertEquals(LocalDate.of(2026, 9, 30), r.statementDate)
        assertEquals(LocalDate.of(2026, 10, 21), r.dueDate)
        assertEquals(5, r.lines.size)
        assertEquals(LocalDate.of(2026, 9, 4), r.lines[0].date)
        assertEquals(TxnKind.CARD_PAYMENT, r.lines[1].kind)
        assertEquals(TxnKind.INTEREST, r.lines[4].kind)
    }

    @Test
    fun balancesOnTheirOwnLinePreferredOverNeighbouringFigures() {
        val r = StatementParser.parse(billingStatement, today)
        assertEquals(800_000L, r.previousBalance)
        assertEquals(1_090_000L, r.closingBalance)
        assertEquals(50_000L, r.minimumDue)
        assertEquals(0L, r.difference)
    }

    @Test
    fun categoriesForBillPayAndCutOffNames() {
        val r = StatementParser.parse(billingStatement, today)
        assertEquals("Bills & utilities", r.lines[0].category)
        assertEquals("Fuel", r.lines[2].category)
        assertEquals("Bills & utilities", r.lines[3].category)
    }

    @Test
    fun rowsHoldingAmountsOrDatesInTheirTextAreRejected() {
        val r = StatementParser.parse(
            "05/10/2026 Late Fee Charge 1,000.00 extra words 675.00\n06/10/2026 Interest Charge 30.06.2024 words 675.00\n07/10/2026 REAL SHOP 50.00",
            today,
        )
        assertEquals(listOf("REAL SHOP"), r.lines.map { it.description })
    }

    /** Summary values with no readable labels, side-column figures on some rows, and terms after the last page. */
    private val unlabelledStatement = """
        A CARDHOLDER Visa Gold
        NO 1/1
        SOME TOWN 30/09/26
        10,900.00
        500.00
        21/10/26
        4111 1111 **** 1111
        250,000.00
        8,000.00
        OPENING BALANCE 8,000.00
        04/09 04/09 Q+ Bill-pay DIALOG AXI 0770000000 200.00
        09/09 09/09 PAYMENT RECEIVED - THANK YOU 1,000.00 CR
        3,000.00
        14/09 11/09 SOME SHOP 1,500.00 0.00
        16/09 16/09 Q+ Bill-pay NATIONAL W 33/40/430/ 1,450.00 0.00
        18/09 15/09 OTHER SHOP 350.00 200.00
        Page 1 of 2
        SOME TOWN 30/09/26
        10,900.00
        30/09 30/09 INTEREST CHARGE 400.00
        ** CARD - 41111111****1111 SUBTOTAL- 2,000.00
        0.00
        10,900.00
        Page 2 of 2
        Pay before the due date before 6.30 p.m.
        Statement Period 01st June to 30th June 2024
        30.06.2024 Closing Balance 26,000.00
        Payment Due Date 21st July 2024 minimum Payment 1,027/-
        15.09 SAMPLE ROW IN THE TERMS 1,000.00
    """.trimIndent()

    @Test
    fun unlabelledSummaryIsReadAndTermsAreIgnored() {
        val r = StatementParser.parse(unlabelledStatement, today)
        assertEquals(LocalDate.of(2026, 9, 30), r.statementDate)
        assertEquals(LocalDate.of(2026, 10, 21), r.dueDate)
        assertEquals(800_000L, r.previousBalance)
        assertEquals(1_090_000L, r.closingBalance)
        assertEquals(50_000L, r.minimumDue)
        assertEquals(0L, r.difference)
    }

    @Test
    fun billNumbersThatLookLikeDatesStayAndSideColumnsAreIgnored() {
        val r = StatementParser.parse(unlabelledStatement, today)
        assertEquals(6, r.lines.size)
        assertEquals(145_000L, r.lines.first { "NATIONAL W" in it.description }.amount)
        assertEquals(35_000L, r.lines.first { "OTHER SHOP" in it.description }.amount)
        assertEquals("Bills & utilities", r.lines.first { "NATIONAL W" in it.description }.category)
    }

    @Test
    fun aStrayFigureIsNotAcceptedAsAnUnlabelledBalance() {
        val r = StatementParser.parse(
            "OPENING BALANCE 1,000.00\n01/10/2026 SHOP 100.00\n9,999.00\nPage 1 of 1", today,
        )
        assertNull(r.closingBalance)
        assertNull(r.difference)
    }

    @Test
    fun differenceIsNullWithoutBothBalances() {
        assertNull(StatementParser.parse("01/10/2026 SHOP 5.00", today).difference)
    }
}
