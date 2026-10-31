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
                return closing - previous - lines.sumOf(::signedAmount)
            }
    }

    private const val PERIOD_SLACK_DAYS = 5L
    private const val CYCLE_DAYS = 40L
    private const val MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
    private const val DATE =
        """(?:\d{4}-\d{2}-\d{2}|\d{1,2}[/.\-]\d{1,2}(?:[/.\-](?:\d{4}|\d{2}))?(?!\d)|\d{1,2}[ \-/]?(?:$MONTHS)[a-z]*(?:[ \-/,]+(?:\d{4}|\d{2})(?![\d.,])(?!\s*(?:$MONTHS)))?)"""
    private val leadingDate = Regex("""^\s*($DATE)(?:\s+($DATE))?\s+(.+)$""", RegexOption.IGNORE_CASE)
    /** Not preceded by part of a number, so the end of "100,000.00" isn't taken for a date. */
    private val anyDate = Regex("""(?<![\d.,])$DATE""", RegexOption.IGNORE_CASE)
    private val isoParts = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val numericParts = Regex("""^(\d{1,2})[/.\-](\d{1,2})(?:[/.\-](\d{2,4}))?$""")
    private val namedParts = Regex("""^(\d{1,2})[ \-/]?($MONTHS)[a-z]*(?:[ \-/,]+(\d{2,4}))?$""", RegexOption.IGNORE_CASE)

    private val amountPattern = Regex(
        """(?<![\d.,])(\(?)(-?)((?:\d{1,3}(?:,\d{3})+|\d+)\.\d{2})(\)?)(-?)(?![\d,])(?:\s*(CR|DR|DB|C|D|\+)\b|\s*(\+))?""",
        RegexOption.IGNORE_CASE,
    )
    private val foreignCurrency = Regex("""\b(USD|EUR|GBP|AUD|CAD|SGD|INR|AED|JPY|CHF|MYR|THB|NZD|QAR|SAR|HKD|CNY)\b""", RegexOption.IGNORE_CASE)
    private val localCurrency = Regex("""(?:\b(?:LKR|Rs)\.?)\s*$""", RegexOption.IGNORE_CASE)
    private val cardNumber = Regex("""\b\d{4}[ \-]?[\dXx*]{2,4}[ \-]?[\dXx*]{2,4}[ \-]?\d{4}\b""")

    private val notATransaction = Regex(
        """\b(BALANCE(?!\s+TRANSFER)|STATEMENT|MINIMUM|DUE DATE|CREDIT LIMIT|AVAILABLE|B/F|C/F|BROUGHT FORWARD|CARRIED FORWARD|SUB.?TOTAL|OP\.?\s*BAL|CLOSING\s+BAL|TOTAL INTEREST)\b|^TOTAL\b|%\s*p\.?\s*a\b|\d{1,3}(?:,\d{3})+\.\d{2}|\b(?:0?[1-9]|[12]\d|3[01])[./\-](?:0?[1-9]|1[0-2])[./\-](?:\d{4}|\d{2})\b""",
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
    private val lastPage = Regex("""\bpage\s+(\d+)\s+of\s+\1\b""", RegexOption.IGNORE_CASE)
    private val hasYear = Regex("""\d{4}|[/.\-]\d{2}$""")
    private val statementPeriod = Regex("""statement\s+period\D*?($DATE)\s*(?:to|-|–)\s*($DATE)""", RegexOption.IGNORE_CASE)
    private val statementDateLabel = Regex("""statement\s+date|date\s+of\s+statement|billing\s+date""", RegexOption.IGNORE_CASE)
    private val dueDateLabel = Regex("""(payment\s+)?due\s+date|pay\s+by""", RegexOption.IGNORE_CASE)

    fun parse(text: String, today: LocalDate = LocalDate.now()): Result {
        val allRows = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // "Page 2 of 2" ends the statement; what follows is terms and worked examples.
        val rows = allRows.indexOfLast { lastPage.containsMatchIn(it) }.let { if (it >= 0) allRows.take(it + 1) else allRows }
        val header = rows.takeWhile { parseRow(it, today, null) == null }.flatMap { row ->
            anyDate.findAll(row).filter { hasYear.containsMatchIn(it.value) }.mapNotNull { toDate(it.value, today, allowFuture = true) }.toList()
        }
        val period = rows.firstNotNullOfOrNull { statementPeriod.find(it) }?.let { m ->
            val end = toDate(m.groupValues[2], today, allowFuture = true)
            val start = toDate(m.groupValues[1], end ?: today)
            if (start != null && end != null && !start.isAfter(end)) start to end else null
        }
        // Some statements print these values with no readable labels, statement date first.
        val statementDate = findDate(rows, statementDateLabel, today) ?: period?.second ?: header.firstOrNull()
        val dueDate = (findDate(rows, dueDateLabel, statementDate ?: today, allowFuture = true) ?: header.getOrNull(1))
            ?.takeIf { statementDate == null || it.isAfter(statementDate) }
        val grid = balanceGrid(rows)
        val previous = findAmount(rows, previousLabel) ?: grid?.first
        val anchor = statementDate ?: today

        var runningBalance = previous
        val lines = rows.mapNotNull { row ->
            val parsed = parseRow(row, anchor, runningBalance) ?: return@mapNotNull null
            parsed.second?.let { runningBalance = it }
            parsed.first
        }.filter { line ->
            // Small print can hold worked examples; real lines fall inside the period, give or take a few days.
            val earliest = period?.first ?: statementDate?.minusDays(CYCLE_DAYS)
            val latest = period?.second ?: statementDate
            (earliest == null || !line.date.isBefore(earliest.minusDays(PERIOD_SLACK_DAYS))) &&
                (latest == null || !line.date.isAfter(latest.plusDays(PERIOD_SLACK_DAYS)))
        }
        val labelledClosing = findAmount(rows, closingLabel, exclude = Regex("""minimum|previous|opening|last\s+(statement|month)|date""", RegexOption.IGNORE_CASE)) ?: grid?.second
        val closing = labelledClosing ?: previous?.let { printedBalance(rows, it + lines.sumOf(::signedAmount)) }
        return Result(
            lines = lines,
            // Without a readable statement date, the newest line is the closest thing to it.
            statementDate = statementDate ?: lines.filter { !it.date.isAfter(today) }.maxOfOrNull { it.date },
            dueDate = dueDate,
            closingBalance = closing,
            previousBalance = previous,
            minimumDue = findAmount(rows, minimumLabel) ?: closing?.let { minimumAfterBalance(rows, it) },
        )
    }

    private class Amount(val value: Long, val credit: Boolean?, val range: IntRange)

    private fun toAmount(match: MatchResult): Amount {
        val cents = BigDecimal(match.groupValues[3].replace(",", "")).movePointRight(2).toLong()
        val marker = (match.groupValues[6] + match.groupValues[7]).uppercase()
        val credit = when {
            marker in setOf("CR", "C", "+") -> true
            marker in setOf("DR", "DB", "D") -> false
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
        localCurrency.find(description)?.let {
            description = description.removeRange(it.range).trim()
            // A currency column means these are amount columns, not an amount and a running balance.
            foreign = true
        }
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

    /**
     * Where to look for a label's value, most trustworthy first: the same row anywhere in the
     * statement, then the row below, then above, since summary boxes can centre a value in its cell.
     */
    private val NEARBY_ROWS = listOf(0, 1, -1)

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
        NEARBY_ROWS.forEach { offset ->
            rows.forEachIndexed { i, row ->
                val match = label.find(row) ?: return@forEachIndexed
                val candidate = if (offset == 0) row.substring(match.range.last + 1) else rows.getOrNull(i + offset) ?: return@forEachIndexed
                anyDate.find(candidate)?.let { found -> toDate(found.value, anchor, allowFuture)?.let { return it } }
            }
        }
        return null
    }

    /** What a line adds to the balance owed. */
    private fun signedAmount(line: Line): Long = when (line.kind) {
        TxnKind.CARD_PAYMENT, TxnKind.REFUND, TxnKind.INCOME -> -line.amount
        else -> line.amount
    }

    /**
     * A closing balance with no readable label: accepted only when the statement prints a figure
     * exactly equal to what its own lines add up to, so a stray number can't pass as the balance.
     */
    private fun printedBalance(rows: List<String>, expected: Long): Long? =
        expected.takeIf { rows.any { row -> amountPattern.findAll(row).any { toAmount(it).value == expected } } }

    /** In an unlabelled summary the minimum payment comes right after the total, and is smaller. */
    private fun minimumAfterBalance(rows: List<String>, closing: Long): Long? {
        fun onlyAmount(row: String) = amountPattern.findAll(row).toList().singleOrNull()
            ?.takeIf { amountPattern.replace(row, "").isBlank() }?.let { toAmount(it).value }
        val i = rows.indexOfFirst { onlyAmount(it) == closing }
        return rows.getOrNull(i + 1)?.takeIf { i >= 0 }?.let(::onlyAmount)?.takeIf { it < closing }
    }

    /**
     * Opening and closing balances from a summary grid: a row of labels from Opening to Closing,
     * with a row of nothing but amounts below it, first and last being the two balances.
     */
    private fun balanceGrid(rows: List<String>): Pair<Long, Long>? {
        rows.forEachIndexed { i, row ->
            val amounts = amountPattern.findAll(row).map(::toAmount).toList()
            if (amounts.size < 3 || amountPattern.replace(row, "").isNotBlank()) return@forEachIndexed
            val above = rows.subList((i - 12).coerceAtLeast(0), i).joinToString(" ")
            if ("opening" in above.lowercase() && "closing" in above.lowercase()) return amounts.first().value to amounts.last().value
        }
        return null
    }

    private fun findAmount(rows: List<String>, label: Regex, exclude: Regex? = null): Long? {
        NEARBY_ROWS.forEach { offset ->
            rows.forEachIndexed { i, row ->
                val match = label.find(row) ?: return@forEachIndexed
                // Only what's right beside the label counts: other columns can share the row.
                val beside = row.substring((match.range.first - 12).coerceAtLeast(0), match.range.last + 1)
                if (exclude != null && exclude.containsMatchIn(beside)) return@forEachIndexed
                val candidate = if (offset == 0) row.substring(match.range.last + 1) else rows.getOrNull(i + offset) ?: return@forEachIndexed
                amountPattern.find(candidate)?.let { found ->
                    val amount = toAmount(found)
                    return if (amount.credit == true) -amount.value else amount.value
                }
            }
        }
        return null
    }
}
