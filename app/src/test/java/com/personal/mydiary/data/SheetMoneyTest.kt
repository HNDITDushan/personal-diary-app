package com.personal.mydiary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SheetMoneyTest {
    private val today = LocalDate.of(2026, 10, 31)
    private val cash = Account(id = 1, name = "Wallet", type = AccountType.CASH)
    private val bank = Account(id = 2, name = "Town Bank", type = AccountType.BANK)
    private val card = Account(id = 3, name = "Town Bank", type = AccountType.CREDIT_CARD, openingBalance = -50_000_00)
    private val accounts = listOf(cash, bank, card)
    private val loan = Loan(id = 9, name = "Car loan", openingBalance = 100_000_00, firstDueDate = 20_000)

    private fun parse(row: Map<String, String>, existing: MoneyTxn? = null) =
        SheetMoney.parseEntry(row, existing, "key", 5, today, accounts, listOf(loan))

    @Test
    fun aBankAndCardWithTheSameNameStayApart() {
        assertEquals("Town Bank (Card)", SheetMoney.accountLabel(card))
        assertEquals(bank, SheetMoney.findAccount("Town Bank", accounts))
        assertEquals(card, SheetMoney.findAccount("town bank (card)", accounts))
    }

    @Test
    fun aMinimalTypedRowBecomesACashExpenseToday() {
        val t = parse(mapOf("Amount" to "1,250.50", "Description" to "Lunch"))!!
        assertEquals(TxnKind.EXPENSE, t.kind)
        assertEquals(125_050L, t.amount)
        assertEquals(cash.id, t.accountId)
        assertEquals(today.toEpochDay(), t.date)
        assertEquals("key", t.uid)
    }

    @Test
    fun rowsThatCannotBeUnderstoodAreRejected() {
        assertNull(parse(mapOf("Amount" to "abc")))
        assertNull(parse(mapOf("Amount" to "-5")))
        assertNull(parse(mapOf("Amount" to "5", "Type" to "Mystery")))
        assertNull(parse(mapOf("Amount" to "5", "Account" to "Unknown")))
        assertNull(parse(mapOf("Amount" to "5", "Date" to "not a date")))
        assertNull(parse(mapOf("Amount" to "5", "Type" to "Transfer")))
        assertNull(parse(mapOf("Amount" to "5", "Type" to "Transfer", "Account" to "Wallet", "Other account" to "Wallet")))
        assertNull(parse(mapOf("Amount" to "5", "Type" to "Loan payment")))
        assertNull(parse(mapOf("Amount" to "5", "Type" to "Loan payment", "Loan" to "Car loan", "Interest part" to "9")))
    }

    @Test
    fun anEditKeepsStatementLinks() {
        val imported = MoneyTxn(id = 7, kind = TxnKind.EXPENSE, date = 1, amount = 100, accountId = 3, statementId = 4, dedupeKey = "k", uid = "u")
        val t = parse(mapOf("Amount" to "2", "Account" to "Town Bank (Card)", "Type" to "Expense"), imported)!!
        assertEquals(7L, t.id)
        assertEquals(4L, t.statementId)
        assertEquals("k", t.dedupeKey)
        assertEquals(200L, t.amount)
    }

    @Test
    fun anEntrySurvivesARoundTrip() {
        val t = MoneyTxn(
            id = 7, kind = TxnKind.LOAN_PAYMENT, date = today.toEpochDay(), time = "16:30", amount = 8_884_88, accountId = 2,
            category = "Loan", description = "Car loan payment", loanId = 9, interestPart = 1_000_00, uid = "u", updatedAt = 1,
        )
        val row = SheetMoney.entryToRow(t, accounts, listOf(loan))
        assertEquals(t.copy(updatedAt = 5), parse(row, t))
        // A number comes back from Sheets without trailing zeros, and must still read the same.
        assertEquals(888_488L, parse(row + ("Amount" to "8884.88"), t)!!.amount)
        assertEquals(row, SheetMoney.entryToRow(parse(row + ("Interest part" to "1000"), t)!!, accounts, listOf(loan)))
    }

    @Test
    fun aCardsOpeningBalanceReadsAsAmountOwed() {
        val row = SheetMoney.accountToRow(card)
        assertEquals("50000.00", row["Opening balance (owed on cards)"])
        assertEquals(card.copy(updatedAt = 5), SheetMoney.parseAccount(row, card, card.uid, 5))
    }

    @Test
    fun excludedAccountSurvivesARoundTrip() {
        val a = cash.copy(excludeFromTotal = true)
        val row = SheetMoney.accountToRow(a)
        assertEquals("TRUE", row["Not in total"])
        assertEquals(a.copy(updatedAt = 5), SheetMoney.parseAccount(row, a, a.uid, 5))
    }

    @Test
    fun aLoanSurvivesARoundTrip() {
        val l = loan.copy(lender = "Town Bank", annualRate = 14.5, flatRate = true, installment = 10_000_00, originalAmount = 200_000_00)
        val row = SheetMoney.loanToRow(l)
        assertEquals(l.copy(updatedAt = 5), SheetMoney.parseLoan(row, l, l.uid, 5))
    }

    @Test
    fun aLoanNeedsANameAmountAndDueDate() {
        assertNull(SheetMoney.parseLoan(mapOf("Owed when added" to "5", "First due" to "2026-11-01"), null, "k", 5))
        assertNull(SheetMoney.parseLoan(mapOf("Name" to "X", "First due" to "2026-11-01"), null, "k", 5))
        assertNull(SheetMoney.parseLoan(mapOf("Name" to "X", "Owed when added" to "5"), null, "k", 5))
    }
}
