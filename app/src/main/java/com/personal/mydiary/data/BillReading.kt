package com.personal.mydiary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** A bill as read from an e-bill or photo, before it's checked and saved. */
data class ParsedBill(
    val date: LocalDate? = null,
    val time: String = "",
    val store: String = "",
    val branch: String = "",
    val lines: List<ParsedLine> = emptyList(),
    val gross: Long? = null,
    val discount: Long = 0,
    val net: Long? = null,
    val payment: String = "",
    val dedupeKey: String? = null,
)

data class ParsedLine(
    val code: String = "",
    val name: String,
    val unitPrice: Long,
    val qty: Double = 1.0,
    val amount: Long,
    val weighed: Boolean = false,
)

/** "1,290.00" -> 129000 cents; null when it isn't a figure. */
fun parseCents(text: String): Long? =
    text.trim().replace(",", "").takeIf { it.matches(Regex("-?\\d+(\\.\\d{1,3})?")) }
        ?.let { BigDecimal(it).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong() }

/** Keells e-bills: the link from the SMS or e-mail opens an HTML copy of the receipt. */
object KeellsEBill {
    private val linkPattern = Regex("https://[\\w.-]*keellssuper\\.com/\\S+", RegexOption.IGNORE_CASE)

    /** The e-bill link in a pasted or shared message, or null. */
    fun findLink(text: String): String? = linkPattern.find(text)?.value?.trimEnd('.', ',', ')')

    /** Downloads the e-bill page; the short link redirects to the full one. */
    suspend fun download(link: String): String = withContext(Dispatchers.IO) {
        var url = link
        repeat(5) {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) MyDiary")
            try {
                when (conn.responseCode) {
                    in 300..399 -> {
                        val next = conn.getHeaderField("Location") ?: error("The e-bill link didn't lead anywhere")
                        url = URI(url).resolve(next).toString()
                        check(url.startsWith("https://")) { "The e-bill link isn't secure" }
                    }
                    200 -> return@withContext conn.inputStream.use { it.readBytes().decodeToString() }
                    else -> error("The e-bill couldn't be opened (error ${conn.responseCode}). The link may have expired.")
                }
            } finally {
                conn.disconnect()
            }
        }
        error("The e-bill link redirected too many times")
    }

    private val billDate: DateTimeFormatter = DateTimeFormatterBuilder().parseCaseInsensitive()
        .appendPattern("d-MMM-yyyy").toFormatter(Locale.ENGLISH)

    private fun clean(html: String) = html.replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&nbsp;", " ").replace("&#160;", " ").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()

    fun parse(html: String): ParsedBill {
        // The page keeps sample rows inside comments; they must not be read as items.
        val page = html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val cell = "<td[^>]*id=['\"]%s['\"][^>]*>(.*?)</td>"
        val row = Regex(listOf("itm", "prz", "qty", "amt").joinToString("\\s*") { cell.format(it) }, RegexOption.DOT_MATCHES_ALL)
        val lines = row.findAll(page).mapNotNull { m ->
            val (item, price, qtyText, amount) = m.destructured.toList().map(::clean)
            val code = item.substringBefore(":", "").trim()
            val name = if (code.isEmpty()) item else item.substringAfter(":").trim()
            val qty = qtyText.replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
            ParsedLine(
                code = code,
                name = name,
                unitPrice = parseCents(price) ?: return@mapNotNull null,
                qty = qty,
                amount = parseCents(amount) ?: return@mapNotNull null,
                weighed = qtyText.substringAfter('.', "").length >= 3,
            )
        }.toList()
        if (lines.isEmpty()) throw IllegalArgumentException("No items were found on this e-bill")

        val header = Regex("<div class=['\"]title[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(page)?.groupValues?.get(1).orEmpty()
        val headerLines = Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(header).map { clean(it.groupValues[1]) }.toList()
        val stamp = Regex("(\\d{1,2}-[A-Za-z]{3}-\\d{4})\\s+(\\d{1,2}:\\d{2})").find(header)
        val receipt = Regex("R:\\s*(\\d+)").find(header)?.groupValues?.get(1)
        val storeCode = Regex("Store Code:\\s*(\\w+)").find(header)?.groupValues?.get(1)
        val branch = headerLines.firstOrNull()?.substringAfterLast(",")?.trim().orEmpty()

        val totals = Regex("<td[^>]*colspan=['\"]6['\"][^>]*>(.*?)</td>\\s*<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
            .findAll(page).map { clean(it.groupValues[1]) to parseCents(clean(it.groupValues[2])) }.toList()
        fun total(label: String) = totals.firstOrNull { it.first.equals(label, true) }?.second
        val netAt = totals.indexOfFirst { it.first.equals("Net Amount", true) }
        // The lines between the net amount and the savings say how it was paid.
        val payment = if (netAt < 0) "" else totals.drop(netAt + 1)
            .takeWhile { !it.first.contains("saving", true) && !it.first.contains("|") }
            .joinToString(", ") { it.first }

        val date = stamp?.let { runCatching { LocalDate.parse(it.groupValues[1], billDate) }.getOrNull() }
        return ParsedBill(
            date = date,
            time = stamp?.groupValues?.get(2)?.padStart(5, '0').orEmpty(),
            store = "Keells",
            branch = ShoppingPrices.displayName(branch),
            lines = lines,
            gross = total("Gross Amount"),
            discount = total("Promotion Discount") ?: 0,
            net = total("Net Amount"),
            payment = payment,
            dedupeKey = if (receipt != null) "keells:$storeCode:$receipt:$date" else null,
        )
    }
}

/**
 * Reads items from the text of a photographed bill. Text recognition gives rows of words left to right;
 * an item row ends in its figures (price, quantity, amount) and the name is the words before them, or the
 * row above when the shop prints names and figures on separate lines. Always checked by hand before saving.
 */
object ReceiptText {
    private val stopWords = listOf("GROSS", "SUB TOTAL", "SUBTOTAL", "NET AMOUNT", "TOTAL", "BALANCE", "CASH", "CHANGE", "DISCOUNT", "SAVINGS")
    private val headerWords = listOf("ITEM", "QTY", "PRICE", "AMOUNT", "DESCRIPTION")
    private val stores = listOf("KEELLS", "CARGILLS", "FOOD CITY", "ARPICO", "GLOMARK", "SPAR", "LAUGFS", "LANKA SATHOSA", "SATHOSA")

    /** Letters a photo often reads in place of digits. */
    private val digitLookalikes = mapOf('O' to '0', 'o' to '0', 'Q' to '0', 'D' to '0', 'G' to '0', 'I' to '1', 'l' to '1', '|' to '1', 'i' to '1', 'S' to '5', 'B' to '8', 'Z' to '2', '/' to '7')
    private val figure = Regex("-?[\\d,]+(\\.\\d{1,3})?")
    /** "100G", "1KG", "500ML": part of a name, not a figure. */
    private val sizeWord = Regex("\\d+(G|KG|ML|L|S|PCS)", RegexOption.IGNORE_CASE)

    /** A token as a figure, with look-alike letters turned into digits; null when it isn't one. */
    private fun asFigure(token: String): String? {
        val t = token.trim('(', ')', '[', ']', '\'', '"')
        if (t.isEmpty() || t.matches(sizeWord)) return null
        if (t.matches(figure)) return t
        val hasDigit = t.any(Char::isDigit)
        if (!hasDigit && !(t.length == 1 && t[0] in "GOoQD")) return null
        if (hasDigit && t.count { it.isLetter() } * 2 > t.length) return null
        val mapped = t.map { digitLookalikes[it] ?: it }.joinToString("")
        return mapped.takeIf { it.matches(figure) }
    }

    private fun figuresAtEnd(row: String): Pair<String, List<String>> {
        // Photos sometimes read "1,600.00" as "1,600. 00".
        val joined = row.trim().replace(Regex("(?<=\\d)\\s*([.,])\\s+(?=\\d)|(?<=\\d)\\s+([.,])(?=\\d)"), "$1$2")
        val tokens = joined.split(Regex("\\s+"))
        val figures = mutableListOf<String>()
        var i = tokens.size
        while (i > 0) {
            val f = asFigure(tokens[i - 1]) ?: break
            figures.add(0, f)
            i--
        }
        return tokens.take(i).joinToString(" ") to figures
    }

    private fun near(a: Long, b: Long) = kotlin.math.abs(a - b) <= maxOf(100L, b / 50)

    /** Works out price, quantity and amount from the figures at the end of a row. */
    fun lineFrom(name: String, figures: List<String>): ParsedLine? {
        val decimals = figures.filter { it.contains('.') }
        if (decimals.isNotEmpty()) {
            val amountText = figures.last()
            val amount = parseCents(amountText) ?: return null
            val rest = figures.dropLast(1)
            // Try each earlier figure as the price and the other as the quantity.
            for (p in rest.indices) {
                val price = parseCents(rest[p])?.takeIf { it >= 100 } ?: continue
                val qtyText = rest.filterIndexed { i, _ -> i != p }.lastOrNull() ?: "1"
                val qty = qtyText.replace(",", "").toDoubleOrNull() ?: continue
                // Bills round price × quantity to the cent or ten cents, so the match must be close.
                if (qty > 0.0 && kotlin.math.abs(Math.round(price * qty) - amount) <= maxOf(6L, amount / 500)) {
                    return ParsedLine(name = name, unitPrice = price, qty = qty, amount = amount, weighed = qtyText.substringAfter('.', "").length >= 3)
                }
            }
            if (rest.isEmpty() || rest.size == 1 && rest[0] == amountText) return ParsedLine(name = name, unitPrice = amount, amount = amount)
        }
        return if (figures.size >= 2) fromDigits(figures)?.copy(name = name) else null
    }

    /**
     * Faint bills lose their decimal points: "400 0.555 222.0" reads as "400 0 555 222 0". Tries every way of
     * splitting the digits into price, quantity and amount and placing their points, and keeps the reading where
     * price × quantity = amount that best fits the gaps in the text.
     */
    fun fromDigits(figures: List<String>): ParsedLine? {
        val digits = StringBuilder()
        val gaps = mutableSetOf<Int>()
        val points = mutableSetOf<Int>()
        for (f in figures) {
            if (digits.isNotEmpty()) gaps += digits.length
            for (c in f) when {
                c.isDigit() -> digits.append(c)
                c == '.' -> points += digits.length
            }
        }
        val d = digits.toString()
        val n = d.length
        if (n < 3 || n > 16) return null
        val breaks = gaps + points
        fun valueOf(from: Int, to: Int, point: Int?): Double? {
            val whole = d.substring(from, point ?: to)
            if (whole.isEmpty() || whole.length > 1 && whole[0] == '0') return null
            val frac = if (point == null) "" else d.substring(point, to)
            return "$whole.${frac.ifEmpty { "0" }}".toDouble()
        }
        // Where a point could go in d[from, to): nowhere, or before any digit after the first.
        fun pointsIn(from: Int, to: Int) = listOf<Int?>(null) + (from + 1 until to)
        // A point the text showed but this reading ignores, or a split or point where the text had no gap, costs a little.
        fun cost(pos: Int?) = if (pos == null || pos in breaks) 0 else 1
        fun ignored(from: Int, to: Int, used: Int?) = points.count { it in (from + 1) until to && it != used } * 2

        var best: ParsedLine? = null
        var bestCost = Int.MAX_VALUE
        for (s1 in 1 until n - 1) for (s2 in s1 + 1 until n) {
            val splitCost = cost(s1) + cost(s2)
            if (splitCost >= bestCost) continue
            for (pp in pointsIn(0, s1)) {
                val price = valueOf(0, s1, pp)?.takeIf { it >= 1 } ?: continue
                for (qp in pointsIn(s1, s2)) {
                    val qty = valueOf(s1, s2, qp) ?: continue
                    if (qty <= 0 || qty > 100) continue
                    for (ap in pointsIn(s2, n)) {
                        val amount = valueOf(s2, n, ap)?.takeIf { it >= 1 } ?: continue
                        if (kotlin.math.abs(price * qty - amount) > maxOf(0.06, amount * 0.002)) continue
                        val total = splitCost + cost(pp) + cost(qp) + cost(ap) +
                            ignored(0, s1, pp) + ignored(s1, s2, qp) + ignored(s2, n, ap)
                        if (total < bestCost) {
                            bestCost = total
                            val qtyDecimals = if (qp == null) 0 else s2 - qp
                            best = ParsedLine(
                                name = "", unitPrice = Math.round(price * 100), qty = qty,
                                amount = Math.round(amount * 100), weighed = qtyDecimals >= 3,
                            )
                        }
                    }
                }
            }
        }
        return best
    }

    /** Whether a row of figures, however its points were lost, is this amount: used to spot the bill's total. */
    private fun readsAs(figures: List<String>, cents: Long): Boolean {
        val d = figures.joinToString("") { f -> f.filter(Char::isDigit) }
        if (d.isEmpty() || d.length > 12) return false
        return (0..minOf(2, d.length - 1)).any { k -> near(d.toLong() * 100 / pow10(k), cents) }
    }

    private fun pow10(k: Int): Long = (1..k).fold(1L) { acc, _ -> acc * 10 }

    /** Leading line number and product code: "3 941070 TUNA FISH" -> ("941070", "TUNA FISH"). */
    fun splitCode(name: String): Pair<String, String> {
        val m = Regex("^(?:\\d{1,3}\\s+)?([A-Z]?\\d{4,})[:\\s]+(.+)$").find(name.trim())
        if (m != null) return m.groupValues[1] to m.groupValues[2].trim()
        // No code: just drop a leading line number, "4 BREAD SLICED" -> "BREAD SLICED".
        return "" to name.trim().removePrefix("-").replace(Regex("^\\d{1,3}\\s+(?=\\D)"), "").trim()
    }

    /** Words on Sinhala bills that start the totals: total, amount paid, balance. */
    private val sinhalaStops = listOf("එකතුව", "මුදල", "ශේෂය", "ඉතිරි")

    fun isSinhala(c: Char) = c in '\u0D80'..'\u0DFF'

    /** A row that is only an item's figures (price, quantity, amount), with its name printed on the line above. */
    fun isFiguresOnly(row: String): Boolean {
        if (isDateOrTime(row)) return false
        val (text, figures) = figuresAtEnd(row)
        return figures.size >= 2 && text.none(Char::isLetter) && lineFrom("", figures) != null
    }

    fun parse(rows: List<String>): ParsedBill {
        val lines = mutableListOf<ParsedLine>()
        var pendingName = ""
        for (raw in rows) {
            val row = raw.trim()
            if (row.isEmpty()) continue
            val upper = row.uppercase()
            if (headerWords.count { it in upper } >= 2) { pendingName = ""; continue }
            // A date or time on its own line isn't an item, however its figures could be split up.
            if (isDateOrTime(row)) continue
            // The totals come after the items.
            if (lines.isNotEmpty() && (stopWords.any { upper.startsWith(it) } || sinhalaStops.any { it in row })) break
            val (text, figures) = figuresAtEnd(row)
            val name = text.trim().takeIf { it.any(Char::isLetter) }
            // Figures alone that match the items so far are the bill's total, even when its label can't be read.
            if (lines.isNotEmpty() && name == null && figures.isNotEmpty() && figures.size <= 2) {
                val sum = lines.sumOf { l -> l.amount }
                if (figures.size == 1 && parseCents(figures[0])?.let { near(it, sum) } == true || readsAs(figures, sum)) break
            }
            val line = if (figures.isNotEmpty()) lineFrom("", figures) else null
            when {
                line != null && name != null -> { lines += named(line, name); pendingName = "" }
                // Figures under a name, or figures alone whose name couldn't be read: kept, to be named by hand.
                line != null && (pendingName.isNotEmpty() || figures.size >= 2) -> { lines += named(line, pendingName); pendingName = "" }
                name != null && figures.isEmpty() -> pendingName = name
                else -> {}
            }
        }
        val all = rows.joinToString("\n")
        val store = stores.firstOrNull { it in all.uppercase() }?.let(ShoppingPrices::displayName).orEmpty()
        return ParsedBill(date = findDate(all), time = findTime(all), store = store, lines = lines)
    }

    private fun isDateOrTime(row: String) =
        row.trim().matches(Regex("[\\d\\s./:-]+")) && (findDate(row) != null || findTime(row).isNotEmpty())

    /** "10:15", or a till's "09.10.20" (with seconds), as "HH:mm". */
    fun findTime(text: String): String {
        val m = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b").find(text)
            ?: Regex("\\b([01]?\\d|2[0-3])[.:]([0-5]\\d)[.:][0-5]\\d\\b").find(text)
            ?: return ""
        return m.groupValues[1].padStart(2, '0') + ":" + m.groupValues[2]
    }

    private fun named(line: ParsedLine, text: String): ParsedLine {
        val (code, name) = splitCode(text)
        return line.copy(code = code, name = name)
    }

    private val datePatterns = listOf("d-MMM-yyyy", "d MMM yyyy", "d/M/yyyy", "d-M-yyyy", "yyyy-M-d", "yyyy/M/d", "yyyy.M.d", "yyyy M d", "d.M.yyyy", "d/M/yy")
        .map { DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH) }

    fun findDate(raw: String): LocalDate? {
        // Photos often read a hyphen as a dash.
        val text = raw.replace('–', '-').replace('—', '-')
        val candidates = Regex("\\b\\d{1,4}[-/. ](?:\\d{1,2}|[A-Za-z]{3})[-/. ]\\d{2,4}\\b").findAll(text).map { it.value }
        for (c in candidates) for (f in datePatterns) {
            val d = runCatching { LocalDate.parse(c, f) }.getOrNull()
            if (d != null && d.year in 2000..2100) return d
        }
        return null
    }
}
