package com.personal.mydiary.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.roundToLong

/** Money you owe, repaid in monthly installments. Amounts are whole cents. */
@Serializable
@Entity(tableName = "loans")
data class Loan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lender: String = "",
    /** What was still to repay when the loan was added to the app. */
    val openingBalance: Long,
    /** The amount first borrowed, when known; shows overall progress. */
    val originalAmount: Long? = null,
    val annualRate: Double = 0.0,
    /** Flat-rate loans charge interest on the original amount every month instead of the reducing balance. */
    val flatRate: Boolean = false,
    val installment: Long = 0,
    /** Epoch day of the first installment due after the loan was added; later ones follow monthly. */
    val firstDueDate: Long,
    val closed: Boolean = false,
    val uid: String = newUid(),
    val updatedAt: Long = System.currentTimeMillis(),
)

private fun Loan.payments(txns: List<MoneyTxn>) = txns.filter { it.loanId == id && it.kind == TxnKind.LOAN_PAYMENT }

/** What is still owed, after the principal part of every payment. */
fun Loan.balance(txns: List<MoneyTxn>): Long =
    (openingBalance - payments(txns).sumOf { it.amount - it.interestPart }).coerceAtLeast(0)

fun Loan.interestPaid(txns: List<MoneyTxn>): Long = payments(txns).sumOf { it.interestPart }

/** One month's interest when [balance] is owed. */
fun Loan.monthlyInterest(balance: Long): Long =
    monthlyInterest(balance, annualRate, flatRate, originalAmount ?: openingBalance)

private fun monthlyInterest(balance: Long, annualRate: Double, flat: Boolean, original: Long): Long {
    if (balance <= 0) return 0
    val base = if (flat) original else balance
    return (base * annualRate / 1200).roundToLong()
}

/**
 * The next installment's due date. Payments are counted by amount, so two part-payments make
 * one installment and a large payment covers several. A 1% allowance absorbs small rounding.
 */
fun Loan.nextDue(txns: List<MoneyTxn>): LocalDate? {
    if (installment <= 0 || closed || balance(txns) <= 0) return null
    val covered = (payments(txns).sumOf { it.amount } + installment / 100) / installment
    return LocalDate.ofEpochDay(firstDueDate).plusMonths(covered)
}

data class LoanProjection(val months: Int, val totalInterest: Long)

/** Months and interest left if the installment stays the same; null when it never repays the loan. */
fun Loan.project(balance: Long): LoanProjection? {
    var left = balance
    var interest = 0L
    var months = 0
    while (left > 0) {
        val due = monthlyInterest(left)
        val principal = installment - due
        if (principal <= 0 || months >= 1200) return null
        left -= principal
        interest += due
        months++
    }
    return LoanProjection(months, interest)
}

/** The monthly installment that clears [balance] in [months]. */
fun suggestedInstallment(balance: Long, annualRate: Double, months: Int, flat: Boolean, original: Long?): Long {
    if (balance <= 0 || months <= 0) return 0
    if (flat) return (balance.toDouble() / months).roundToLong() + monthlyInterest(balance, annualRate, true, original ?: balance)
    val r = annualRate / 1200
    if (r <= 0.0) return (balance.toDouble() / months).roundToLong()
    return (balance * r / (1 - (1 + r).pow(-months))).roundToLong()
}

data class ScheduleRow(val month: Int, val payment: Long, val interest: Long, val principal: Long, val balance: Long)

/**
 * Month-by-month repayment of [amount] at a fixed [installment]. With [months] given, that month's
 * payment clears whatever is left, so rounding can't add an extra month or leave a remainder.
 * Empty when the installment can't repay the loan.
 */
fun repaymentSchedule(amount: Long, annualRate: Double, flat: Boolean, installment: Long, months: Int? = null): List<ScheduleRow> {
    val rows = mutableListOf<ScheduleRow>()
    var left = amount
    while (left > 0) {
        val interest = monthlyInterest(left, annualRate, flat, amount)
        if (installment <= interest || rows.size >= MAX_SCHEDULE_MONTHS) return emptyList()
        val principal = if (rows.size + 1 == months) left else minOf(left, installment - interest)
        left -= principal
        rows += ScheduleRow(rows.size + 1, principal + interest, interest, principal, left)
    }
    return rows
}

private const val MAX_SCHEDULE_MONTHS = 1200
