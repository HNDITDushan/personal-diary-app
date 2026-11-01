package com.personal.mydiary.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** How accounts, money entries and loans look as rows in the Google Sheet. */
internal object SheetMoney {
    private val kindNames = mapOf(
        TxnKind.EXPENSE to "Expense", TxnKind.INCOME to "Income", TxnKind.TRANSFER to "Transfer",
        TxnKind.CARD_PAYMENT to "Card payment", TxnKind.INTEREST to "Interest", TxnKind.FEE to "Fee",
        TxnKind.REFUND to "Refund", TxnKind.LOAN_PAYMENT to "Loan payment", TxnKind.LOAN_RECEIVED to "Loan received",
    )
    private val accountTypeNames = mapOf(
        AccountType.CASH to "Cash", AccountType.BANK to "Bank account", AccountType.CREDIT_CARD to "Credit card",
    )

    val kindChoices = kindNames.values.toList()
    val accountTypeChoices = accountTypeNames.values.toList()
    val loanTypeChoices = listOf("Reducing balance", "Flat")
    val yesNoChoices = listOf("TRUE", "FALSE")

    fun amountText(cents: Long): String = BigDecimal(cents).movePointLeft(2).toPlainString()

    /** A plain decimal, with commas allowed; never rounds silently past the cent. */
    fun parseAmount(text: String): Long? = runCatching {
        BigDecimal(text.trim().replace(",", "")).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
    }.getOrNull()

    /**
     * How an account is named in the Money tab. A bank account and a card can share a name, so cards
     * say so; without this, "Com Bank" could mean either.
     */
    fun accountLabel(account: Account): String =
        if (account.type == AccountType.CREDIT_CARD) "${account.name} (Card)" else account.name

    fun findAccount(text: String, accounts: List<Account>): Account? {
        val t = text.trim()
        return accounts.firstOrNull { accountLabel(it) == t }
            ?: accounts.firstOrNull { accountLabel(it).equals(t, ignoreCase = true) }
            // A plain name works too when only one account has it.
            ?: accounts.filter { it.name.equals(t, ignoreCase = true) }.singleOrNull()
    }

    // ---- Money entries ----

    val entryColumns = listOf("Date", "Time", "Type", "Amount", "Account", "Other account", "Category", "Description", "Interest part", "Loan")

    fun entryToRow(t: MoneyTxn, accounts: List<Account>, loans: List<Loan>): Map<String, String> = mapOf(
        "Date" to LocalDate.ofEpochDay(t.date).toString(),
        "Time" to t.time,
        "Type" to kindNames.getValue(t.kind),
        "Amount" to amountText(t.amount),
        "Account" to accounts.firstOrNull { it.id == t.accountId }?.let(::accountLabel).orEmpty(),
        "Other account" to t.counterAccountId?.let { id -> accounts.firstOrNull { it.id == id } }?.let(::accountLabel).orEmpty(),
        "Category" to t.category,
        "Description" to t.description,
        "Interest part" to if (t.kind == TxnKind.LOAN_PAYMENT) amountText(t.interestPart) else "",
        "Loan" to t.loanId?.let { id -> loans.firstOrNull { it.id == id } }?.name.orEmpty(),
        "uid" to t.uid,
    )

    /** Null when the row can't be understood, such as an unknown account; the row then stays as typed. */
    fun parseEntry(
        row: Map<String, String>, existing: MoneyTxn?, key: String, now: Long, today: LocalDate,
        accounts: List<Account>, loans: List<Loan>,
    ): MoneyTxn? {
        fun cell(name: String) = row[name]?.trim().orEmpty()
        val amount = parseAmount(cell("Amount"))?.takeIf { it > 0 } ?: return null
        val typeText = cell("Type")
        val kind = if (typeText.isEmpty()) TxnKind.EXPENSE else SheetSync.parseChoice(typeText, kindNames) ?: return null
        val dateText = cell("Date")
        val date = if (dateText.isEmpty()) today else SheetSync.parseDate(dateText) ?: return null

        val accountText = cell("Account")
        val account = (if (accountText.isEmpty()) accounts.firstOrNull { it.type == AccountType.CASH && !it.archived }
        else findAccount(accountText, accounts)) ?: return null

        val otherText = cell("Other account")
        val other = if (otherText.isEmpty()) null else findAccount(otherText, accounts) ?: return null
        if (kind == TxnKind.TRANSFER && (other == null || other.id == account.id)) return null

        val loanText = cell("Loan")
        val loan = if (loanText.isEmpty()) null else loans.firstOrNull { it.name.equals(loanText, ignoreCase = true) } ?: return null
        if (kind == TxnKind.LOAN_PAYMENT && loan == null) return null

        val interestText = cell("Interest part")
        val interest = if (interestText.isEmpty() || kind != TxnKind.LOAN_PAYMENT) 0L else parseAmount(interestText) ?: return null
        if (interest !in 0..amount) return null

        return (existing ?: MoneyTxn(kind = kind, date = 0, amount = 0, accountId = 0, uid = key)).copy(
            kind = kind, date = date.toEpochDay(), time = SheetSync.parseTimeText(cell("Time")), amount = amount,
            accountId = account.id, counterAccountId = other?.id, category = cell("Category"), description = cell("Description"),
            loanId = loan?.id, interestPart = interest, updatedAt = now,
        )
    }

    // ---- Accounts ----

    const val BALANCE_COLUMN = "Balance now (owed on cards)"
    val accountColumns = listOf("Name", "Type", "Opening balance (owed on cards)", BALANCE_COLUMN, "Last 4 digits", "Credit limit", "Not in total", "No longer used")

    fun accountToRow(a: Account): Map<String, String> = mapOf(
        "Name" to a.name,
        "Type" to accountTypeNames.getValue(a.type),
        "Opening balance (owed on cards)" to amountText(if (a.type == AccountType.CREDIT_CARD) -a.openingBalance else a.openingBalance),
        "Last 4 digits" to a.last4,
        "Credit limit" to a.creditLimit?.let(::amountText).orEmpty(),
        "Not in total" to if (a.excludeFromTotal) "TRUE" else "FALSE",
        "No longer used" to if (a.archived) "TRUE" else "FALSE",
        "uid" to a.uid,
    )

    fun parseAccount(row: Map<String, String>, existing: Account?, key: String, now: Long): Account? {
        fun cell(name: String) = row[name]?.trim().orEmpty()
        val name = cell("Name").ifEmpty { return null }
        val typeText = cell("Type")
        val type = if (typeText.isEmpty()) existing?.type ?: AccountType.CASH else SheetSync.parseChoice(typeText, accountTypeNames) ?: return null
        val openingText = cell("Opening balance (owed on cards)")
        val opening = if (openingText.isEmpty()) 0L else parseAmount(openingText) ?: return null
        val limitText = cell("Credit limit")
        val limit = if (limitText.isEmpty()) null else parseAmount(limitText) ?: return null
        return (existing ?: Account(name = name, type = type, uid = key)).copy(
            name = name, type = type, openingBalance = if (type == AccountType.CREDIT_CARD) -opening else opening,
            last4 = cell("Last 4 digits").filter(Char::isDigit).takeLast(4),
            creditLimit = if (type == AccountType.CREDIT_CARD) limit else null,
            excludeFromTotal = type != AccountType.CREDIT_CARD && SheetSync.parseBool(cell("Not in total")),
            archived = SheetSync.parseBool(cell("No longer used")), updatedAt = now,
        )
    }

    // ---- Loans ----

    const val OWED_COLUMN = "Owed now"
    val loanColumns = listOf("Name", "Lender", "Owed when added", OWED_COLUMN, "First borrowed", "Rate a year (%)", "Interest type", "Installment", "First due", "Closed")

    fun loanToRow(l: Loan): Map<String, String> = mapOf(
        "Name" to l.name,
        "Lender" to l.lender,
        "Owed when added" to amountText(l.openingBalance),
        "First borrowed" to l.originalAmount?.let(::amountText).orEmpty(),
        "Rate a year (%)" to BigDecimal.valueOf(l.annualRate).stripTrailingZeros().toPlainString(),
        "Interest type" to if (l.flatRate) "Flat" else "Reducing balance",
        "Installment" to amountText(l.installment),
        "First due" to LocalDate.ofEpochDay(l.firstDueDate).toString(),
        "Closed" to if (l.closed) "TRUE" else "FALSE",
        "uid" to l.uid,
    )

    fun parseLoan(row: Map<String, String>, existing: Loan?, key: String, now: Long): Loan? {
        fun cell(name: String) = row[name]?.trim().orEmpty()
        val name = cell("Name").ifEmpty { return null }
        val owed = parseAmount(cell("Owed when added"))?.takeIf { it >= 0 } ?: return null
        val rate = cell("Rate a year (%)").removeSuffix("%").trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() } ?: return null
        if (rate !in 0.0..100.0) return null
        val borrowedText = cell("First borrowed")
        val borrowed = if (borrowedText.isEmpty()) null else parseAmount(borrowedText) ?: return null
        val installmentText = cell("Installment")
        val installment = if (installmentText.isEmpty()) 0L else parseAmount(installmentText)?.takeIf { it >= 0 } ?: return null
        val due = SheetSync.parseDate(cell("First due"))?.toEpochDay() ?: existing?.firstDueDate ?: return null
        return (existing ?: Loan(name = name, openingBalance = owed, firstDueDate = due, uid = key)).copy(
            name = name, lender = cell("Lender"), openingBalance = owed, originalAmount = borrowed, annualRate = rate,
            flatRate = cell("Interest type").equals("Flat", ignoreCase = true), installment = installment,
            firstDueDate = due, closed = SheetSync.parseBool(cell("Closed")), updatedAt = now,
        )
    }
}
