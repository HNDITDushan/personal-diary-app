package com.personal.mydiary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LoansTest {
    private val due = LocalDate.of(2026, 1, 31).toEpochDay()
    private val loan = Loan(id = 1, name = "Car", openingBalance = 100_000_00, annualRate = 12.0, installment = 10_000_00, firstDueDate = due)

    private fun pay(amount: Long, interest: Long) =
        MoneyTxn(kind = TxnKind.LOAN_PAYMENT, date = 0, amount = amount, accountId = 1, loanId = 1, interestPart = interest)

    @Test
    fun reducingBalanceInterestFollowsTheBalance() {
        assertEquals(1_000_00L, loan.monthlyInterest(100_000_00))
        assertEquals(500_00L, loan.monthlyInterest(50_000_00))
        assertEquals(0L, loan.monthlyInterest(0))
    }

    @Test
    fun flatInterestStaysOnTheOriginalAmount() {
        val flat = loan.copy(flatRate = true, originalAmount = 200_000_00)
        assertEquals(2_000_00L, flat.monthlyInterest(50_000_00))
    }

    @Test
    fun onlyPrincipalReducesTheBalance() {
        val txns = listOf(pay(10_000_00, 1_000_00), pay(10_000_00, 910_00))
        assertEquals(81_910_00L, loan.balance(txns))
        assertEquals(1_910_00L, loan.interestPaid(txns))
    }

    @Test
    fun otherLoansAndEntriesAreIgnored() {
        val txns = listOf(
            pay(10_000_00, 0).copy(loanId = 2),
            MoneyTxn(kind = TxnKind.LOAN_RECEIVED, date = 0, amount = 50_000_00, accountId = 1, loanId = 1),
        )
        assertEquals(100_000_00L, loan.balance(txns))
    }

    @Test
    fun nextDueCountsPaymentsByAmountAndKeepsMonthEnd() {
        assertEquals(LocalDate.of(2026, 1, 31), loan.nextDue(emptyList()))
        assertEquals(LocalDate.of(2026, 2, 28), loan.nextDue(listOf(pay(10_000_00, 0))))
        // Two part-payments make one installment; a slightly short payment still counts.
        assertEquals(LocalDate.of(2026, 2, 28), loan.nextDue(listOf(pay(5_000_00, 0), pay(5_000_00, 0))))
        assertEquals(LocalDate.of(2026, 2, 28), loan.nextDue(listOf(pay(9_950_00, 0))))
        assertEquals(LocalDate.of(2026, 1, 31), loan.nextDue(listOf(pay(5_000_00, 0))))
    }

    @Test
    fun noNextDueOnceClosedOrPaidOff() {
        assertNull(loan.copy(closed = true).nextDue(emptyList()))
        assertNull(loan.nextDue(listOf(pay(100_000_00, 0))))
    }

    @Test
    fun suggestedInstallmentClearsTheLoanOnTime() {
        val installment = suggestedInstallment(100_000_00, 12.0, 12, false, null)
        assertEquals(8_884_88L, installment)
        val projection = loan.copy(installment = installment + 1).project(100_000_00)!!
        assertEquals(12, projection.months)
        assertTrue(projection.totalInterest in 6_600_00L..6_700_00L)
    }

    @Test
    fun suggestedInstallmentForZeroAndFlatRates() {
        assertEquals(10_000_00L, suggestedInstallment(120_000_00, 0.0, 12, false, null))
        assertEquals(11_000_00L, suggestedInstallment(120_000_00, 10.0, 12, true, 120_000_00))
    }

    @Test
    fun installmentTooSmallNeverRepays() {
        assertNull(loan.copy(installment = 1_000_00).project(100_000_00))
        assertNull(loan.copy(installment = 0).project(100_000_00))
    }

    @Test
    fun loanEntriesMoveAccountBalances() {
        assertEquals(-500L, pay(500, 100).effectOn(1))
        assertEquals(700L, MoneyTxn(kind = TxnKind.LOAN_RECEIVED, date = 0, amount = 700, accountId = 1).effectOn(1))
    }
}
