package com.personal.mydiary.data

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.abs

/**
 * Pulls transactions and summary figures out of a credit card statement's text.
 *
 * Banks lay statements out differently, so this looks for the shape they share: a line that
 * starts with a date and ends with an amount. Nothing is saved from here; the user reviews
 * the result first, and [Result.difference] flags statements that didn't read cleanly.
 */
object StatementParser {
    data class Line(
        val date: LocalDate,
        val description: String,
        val amount: Long,
        val kind: TxnKind,
        val category: String,
    )

    data class Result(
        val lines: List<Line>,
        val statementDate: LocalDate?,
        val dueDate: LocalDate?,
        val closingBalance: Long?,
        val previousBalance: Long?,
        val minimumDue: Long?,
    ) {
        /** Closing balance minus what the lines add up to; null when the statement doesn't give both balances. */
        val difference: Long?
            get() {
                val closing = closingBalance ?: return null
                val previous = previousBalance ?: return null
                val moved = lines.sumOf {
                    when (it.kind) {
                        TxnKind.CARD_PAYMENT, TxnKind.REFUND, TxnKind.INCOME -> -it.amount
                        else -> it.amount
                    }
                }
                return closing - previous - moved
            }
    }

    private const val MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
    private const val DATE =
        """(?:\d{4}-\d{2}-\d{2}|\d{1,2}[/.\-]\d{1,2}(?:[/.\-](?:\d{4}|\d{2}))?(?!\d)|\d{1,2}[ \-/]?(?:$MONTHS)[a-z]*(?:[ \-/,]+(?:\d{4}|\d{2})(?![\d.,]))?)"""
    private val leadingDate = Regex("""^\s*($DATE)(?:\s+($DATE))?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val anyDate = Regex(DATE, RegexOption.IGNORE_CASE)
    private val isoParts = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val numericParts = Regex("""^(\d{1,2})[/.\-](\d{1,2})(?:[/.\-](\d{2,4}))?$""")
    private val namedParts = Regex("""^(\d{1,2})[ \-/]?($MONTHS)[a-z]*(?:[ \-/,]+(\d{2,4}))?$""", RegexOption.IGNORE_CASE)

    private val amountPattern = Regex(
        """(?<![\d.,])(\(?)(-?)((?:\d{1,3}(?:,\d{3})+|\d+)\.\d{2})(\)?)(-?)(?![\d,])(?:\s*(CR|DR|C|D|\+)\b|\s*(\+))?""",
        RegexOption.IGNORE_CASE,
    )
    private val foreignCurrency = Regex("""\b(USD|EUR|GBP|AUD|CAD|SGD|INR|AED|JPY|CHF|MYR|THB|NZD|QAR|SAR|HKD|CNY)\b""", RegexOption.IGNORE_CASE)
    private val localCurrency = Regex("""(?:\b(?:LKR|Rs)\.?)\s*$""", RegexOption.IGNORE_CASE)
    private val cardNumber = Regex("""\b\d{4}[ \-]?[\dXx*]{2,4}[ \-]?[\dXx*]{2,4}[ \-]?\d{4}\b""")

    private val notATransaction = Regex(
        """\b(BALANCE(?!\s+TRANSFER)|STATEMENT|MINIMUM|DUE DATE|CREDIT LIMIT|AVAILABLE|B/F|C/F|BROUGHT FORWARD|CARRIED FORWARD|SUB.?TOTAL)\b|^TOTAL\b""",
        RegexOption.IGNORE_CASE,
    )
    private val paymentWords = Regex("""\b(PAYMENT|PYMT|PMT|THANK YOU|RECEIVED|CASH DEPOSIT)\b""", RegexOption.IGNORE_CASE)
    private val interestWords = Regex("""\b(INTEREST|FINANCE CHARGES?)\b""", RegexOption.IGNORE_CASE)
    private val feeWords = Regex(
        """\b(FEES?|CHARGES?|LATE|OVER\s?LIMIT|STAMP DUTY|ANNUAL|SSCL|VAT|GOVT|GOVERNMENT|TAX|LEVY)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val closingLabel = Regex(
        """(closing|new|current|statement)\s+balance|(total\s+)?outstanding(\s+balance)?|total\s+(amount\s+)?(due|payable)|balance\s+due""",
        RegexOption.IGNORE_CASE,
    )
    private val previousLabel = Regex("""(previous|opening|last\s+statement|last\s+month)('?s)?\s+balance|balance\s+(b/f|brought\s+forward)|brought\s+forward""", RegexOption.IGNORE_CASE)
    private val minimumLabel = Regex("""minimum\s+(payment|amount|due|payable)|min\.?\s+(payment|amount|due)""", RegexOption.IGNORE_CASE)
    private val statementDateLabel = Regex("""statement\s+date|date\s+of\s+statement""", RegexOption.IGNORE_CASE)
    private val dueDateLabel = Regex("""(payment\s+)?due\s+date|pay\s+by""", RegexOption.IGNORE_CASE)

    fun parse(text: String, today: LocalDate = LocalDate.now()): Result {
        val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val statementDate = findDate(rows, statementDateLabel, today)
        val dueDate = findDate(rows, dueDateLabel, statementDate ?: today, allowFuture = true)
        val previous = findAmount(rows, previousLabel)
        val anchor = statementDate ?: today

        var runningBalance = previous
        val lines = rows.mapNotNull { row ->
            val parsed = parseRow(row, anchor, runningBalance) ?: return@mapNotNull null
            parsed.second?.let { runningBalance = it }
            parsed.first
        }
        return Result(
            lines = lines,
            statementDate = statementDate,
            dueDate = dueDate,
            closingBalance = findAmount(rows, closingLabel, exclude = Regex("""minimum|previous|opening|last\s+(statement|month)|date""", RegexOption.IGNORE_CASE)),
            previousBalance = previous,
            minimumDue = findAmount(rows, minimumLabel),
        )
    }

    private class Amount(val value: Long, val credit: Boolean?, val range: IntRange)

    private fun toAmount(match: MatchResult): Amount {
        val cents = BigDecimal(match.groupValues[3].replace(",", "")).movePointRight(2).toLong()
        val marker = (match.groupValues[6] + match.groupValues[7]).uppercase()
        val credit = when {
            marker in setOf("CR", "C", "+") -> true
            marker in setOf("DR", "D") -> false
            match.groupValues[1] == "(" || match.groupValues[2] == "-" || match.groupValues[5] == "-" -> true
            else -> null
        }
        return Amount(cents, credit, match.range)
    }

    /** The transaction on this row plus any running balance shown beside it. */
    private fun parseRow(row: String, anchor: LocalDate, previousBalance: Long?): Pair<Line, Long?>? {
        val match = leadingDate.find(row) ?: return null
        val date = toDate(match.groupValues[1], anchor) ?: return null
        val rest = match.groupValues[3]

        val found = amountPattern.findAll(rest).map(::toAmount).toList()
        if (found.isEmpty() || found.last().range.last < rest.trimEnd().length - 1) return null

        // Only amounts at the very end of the row belong to the columns; numbers in a shop's name don't.
        val trailing = mutableListOf(found.last())
        var foreign = false
        for (i in found.size - 2 downTo 0) {
            val gap = rest.substring(found[i].range.last + 1, trailing.first().range.first).trim()
            val isCurrency = foreignCurrency.matches(gap)
            if (gap.isNotEmpty() && !isCurrency) break
            if (isCurrency) foreign = true
            trailing.add(0, found[i])
        }

        var description = rest.substring(0, trailing.first().range.first).trim()
        localCurrency.find(description)?.let { description = description.removeRange(it.range).trim() }
        foreignCurrency.findAll(description).lastOrNull()?.takeIf { it.range.last == description.lastIndex }?.let {
            foreign = true
            description = description.removeRange(it.range).trim()
        }
        description = cardNumber.replace(description, "****").replace(Regex("""\s+"""), " ").trim(' ', '-', ':')
        if (description.isEmpty() || notATransaction.containsMatchIn(description)) return null

        val amount: Amount
        var balance: Long? = null
        var credit: Boolean?
        if (trailing.size == 1 || foreign) {
            // With a foreign amount first, the last figure is the charge in local currency.
            amount = trailing.last()
            credit = amount.credit
        } else {
            // Debit and/or credit columns followed by a running balance.
            val columns = trailing.dropLast(1)
            amount = columns.firstOrNull { it.value != 0L } ?: return null
            balance = trailing.last().value
            credit = amount.credit
            if (credit == null && previousBalance != null) {
                if (abs(previousBalance - amount.value - balance) <= 1) credit = true
                else if (abs(previousBalance + amount.value - balance) <= 1) credit = false
            }
        }
        if (amount.value == 0L) return null

        // "LATE PAYMENT FEE" mentions a payment but is a charge.
        val isPayment = credit != false && paymentWords.containsMatchIn(description) &&
            !feeWords.containsMatchIn(description) && !interestWords.containsMatchIn(description)
        val kind = when {
            isPayment -> TxnKind.CARD_PAYMENT
            credit == true -> TxnKind.REFUND
            interestWords.containsMatchIn(description) -> TxnKind.INTEREST
            feeWords.containsMatchIn(description) -> TxnKind.FEE
            else -> TxnKind.EXPENSE
        }
        val category = if (kind == TxnKind.EXPENSE) guessCategory(description) else ""
        return Line(date, description, amount.value, kind, category) to balance
    }

    private fun toDate(text: String, anchor: LocalDate, allowFuture: Boolean = false): LocalDate? {
        val t = text.trim()
        val (day, month, yearText) = isoParts.find(t)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        } ?: numericParts.find(t)?.let { m ->
            var d = m.groupValues[1].toInt()
            var mo = m.groupValues[2].toInt()
            if (mo > 12 && d <= 12) d = mo.also { mo = d } // month written first
            Triple(d, mo, m.groupValues[3])
        } ?: namedParts.find(t)?.let { m ->
            val mo = MONTHS.split("|").indexOf(m.groupValues[2].take(3).lowercase()) + 1
            Triple(m.groupValues[1].toInt(), mo, m.groupValues[3])
        } ?: return null
        if (month !in 1..12 || day !in 1..31) return null

        val year = when (yearText.length) {
            4 -> yearText.toInt()
            2 -> 2000 + yearText.toInt()
            else -> anchor.year
        }
        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        // A date with no year that lands after the statement belongs to the previous year.
        return if (yearText.isEmpty() && !allowFuture && date.isAfter(anchor)) date.minusYears(1) else date
    }

    private fun findDate(rows: List<String>, label: Regex, anchor: LocalDate, allowFuture: Boolean = false): LocalDate? {
        rows.forEachIndexed { i, row ->
            val match = label.find(row) ?: return@forEachIndexed
            val after = row.substring(match.range.last + 1)
            (anyDate.find(after) ?: rows.getOrNull(i + 1)?.let(anyDate::find))?.let { found ->
                toDate(found.value, anchor, allowFuture)?.let { return it }
            }
        }
        return null
    }

    private fun findAmount(rows: List<String>, label: Regex, exclude: Regex? = null): Long? {
        rows.forEachIndexed { i, row ->
            val match = label.find(row) ?: return@forEachIndexed
            if (exclude != null && exclude.containsMatchIn(row)) return@forEachIndexed
            val after = row.substring(match.range.last + 1)
            (amountPattern.find(after) ?: rows.getOrNull(i + 1)?.let(amountPattern::find))?.let { found ->
                val amount = toAmount(found)
                return if (amount.credit == true) -amount.value else amount.value
            }
        }
        return null
    }
}
