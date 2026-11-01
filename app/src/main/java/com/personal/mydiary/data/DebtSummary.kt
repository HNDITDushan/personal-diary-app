package com.personal.mydiary.data

/** One credit card's position. [owed] is negative if the card has been overpaid. */
class CardDebt(val account: Account, val owed: Long) {
    /** Limit minus what's owed, never below zero; null when no limit has been entered. */
    val available: Long? get() = account.creditLimit?.let { (it - owed).coerceAtLeast(0) }

    /** How far past the limit the card is, or zero. */
    val overLimit: Long get() = account.creditLimit?.let { (owed - it).coerceAtLeast(0) } ?: 0
}

/** Everything still to be repaid, and the credit still available to use. */
class DebtSummary(accounts: List<Account>, loans: List<Loan>, txns: List<MoneyTxn>) {
    val cards: List<CardDebt> = accounts.filter { it.type == AccountType.CREDIT_CARD }
        .map { card -> CardDebt(card, -(card.openingBalance + txns.sumOf { it.effectOn(card.id) })) }
        // A hidden card still counts while money is owed on it.
        .filter { !it.account.archived || it.owed != 0L }

    val openLoans: List<Pair<Loan, Long>> = loans.filter { !it.closed }.map { it to it.balance(txns) }.filter { it.second > 0 }

    /** An overpaid card is money held by the bank, so it doesn't reduce what's owed elsewhere. */
    val cardsToPay: Long = cards.sumOf { it.owed.coerceAtLeast(0) }
    val loansToPay: Long = openLoans.sumOf { it.second }
    val totalToPay: Long = cardsToPay + loansToPay

    val availableCredit: Long = cards.filter { !it.account.archived }.sumOf { it.available ?: 0 }
    val cardsMissingLimit: Int = cards.count { !it.account.archived && it.account.creditLimit == null }
}
