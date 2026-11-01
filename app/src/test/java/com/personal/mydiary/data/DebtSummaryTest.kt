package com.personal.mydiary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebtSummaryTest {
    private val visa = Account(id = 1, name = "Visa", type = AccountType.CREDIT_CARD, openingBalance = -50_000_00, creditLimit = 100_000_00)
    private val master = Account(id = 2, name = "Master", type = AccountType.CREDIT_CARD, openingBalance = -20_000_00)
    private val bank = Account(id = 3, name = "Bank", type = AccountType.BANK, openingBalance = 9_000_00)
    private val car = Loan(id = 9, name = "Car", openingBalance = 300_000_00, firstDueDate = 0)

    private fun txn(kind: TxnKind, amount: Long, account: Long, loan: Long? = null, interest: Long = 0) =
        MoneyTxn(kind = kind, date = 0, amount = amount, accountId = account, loanId = loan, interestPart = interest)

    @Test
    fun totalToPayAddsCardsAndLoans() {
        val s = DebtSummary(listOf(visa, master, bank), listOf(car), emptyList())
        assertEquals(70_000_00L, s.cardsToPay)
        assertEquals(300_000_00L, s.loansToPay)
        assertEquals(370_000_00L, s.totalToPay)
    }

    @Test
    fun entriesChangeWhatIsOwedAndAvailable() {
        val s = DebtSummary(
            listOf(visa), listOf(car),
            listOf(
                txn(TxnKind.EXPENSE, 10_000_00, 1), txn(TxnKind.CARD_PAYMENT, 5_000_00, 1), txn(TxnKind.INTEREST, 1_000_00, 1),
                txn(TxnKind.LOAN_PAYMENT, 10_000_00, 3, loan = 9, interest = 3_000_00),
            ),
        )
        assertEquals(56_000_00L, s.cardsToPay)
        assertEquals(44_000_00L, s.availableCredit)
        // Only the principal part of a loan payment reduces the loan.
        assertEquals(293_000_00L, s.loansToPay)
    }

    @Test
    fun cardsWithoutALimitAreFlaggedNotCounted() {
        val s = DebtSummary(listOf(visa, master), emptyList(), emptyList())
        assertEquals(50_000_00L, s.availableCredit)
        assertEquals(1, s.cardsMissingLimit)
        assertNull(s.cards.first { it.account.id == 2L }.available)
    }

    @Test
    fun overTheLimitShowsNoAvailableCredit() {
        val s = DebtSummary(listOf(visa), emptyList(), listOf(txn(TxnKind.EXPENSE, 60_000_00, 1)))
        assertEquals(0L, s.availableCredit)
        assertEquals(10_000_00L, s.cards.single().overLimit)
    }

    @Test
    fun anOverpaidCardAddsCreditButDoesNotReduceOtherDebts() {
        val s = DebtSummary(listOf(visa, master), emptyList(), listOf(txn(TxnKind.CARD_PAYMENT, 55_000_00, 1)))
        assertEquals(20_000_00L, s.totalToPay)
        assertEquals(105_000_00L, s.cards.first { it.account.id == 1L }.available)
    }

    @Test
    fun closedAndPaidOffLoansAreLeftOut() {
        val s = DebtSummary(emptyList(), listOf(car.copy(closed = true), car.copy(id = 8, openingBalance = 0)), emptyList())
        assertEquals(0L, s.totalToPay)
        assertEquals(0, s.openLoans.size)
    }

    @Test
    fun aHiddenCardStillCountsWhileMoneyIsOwed() {
        val hiddenOwed = visa.copy(archived = true)
        val hiddenClear = master.copy(archived = true, openingBalance = 0)
        val s = DebtSummary(listOf(hiddenOwed, hiddenClear), emptyList(), emptyList())
        assertEquals(50_000_00L, s.cardsToPay)
        assertEquals(1, s.cards.size)
        assertEquals(0L, s.availableCredit)
    }
}
