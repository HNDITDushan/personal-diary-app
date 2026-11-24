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

    private fun figuresAtEnd(row: String): Pair<String, List<String>> {
        // Photos sometimes read "1,600.00" as "1,600. 00".
        val joined = row.trim().replace(Regex("(?<=\\d)\\s*([.,])\\s+(?=\\d)|(?<=\\d)\\s+([.,])(?=\\d)"), "$1$2")
        val tokens = joined.split(Regex("\\s+"))
        var i = tokens.size
        while (i > 0 && tokens[i - 1].matches(Regex("-?[\\d,]+(\\.\\d{1,3})?"))) i--
        return tokens.take(i).joinToString(" ") to tokens.drop(i)
    }

    private fun near(a: Long, b: Long) = kotlin.math.abs(a - b) <= maxOf(100L, b / 50)

    /** Works out price, quantity and amount from the figures at the end of a row. */
    fun lineFrom(name: String, figures: List<String>): ParsedLine? {
        val decimals = figures.filter { it.contains('.') }
        if (decimals.isEmpty()) return null
        val amountText = figures.last()
        val amount = parseCents(amountText) ?: return null
        val rest = figures.dropLast(1)
        // Try each earlier figure as the price and the other as the quantity.
        for (p in rest.indices) {
            val price = parseCents(rest[p]) ?: continue
            val qtyText = rest.filterIndexed { i, _ -> i != p }.lastOrNull() ?: "1"
            val qty = qtyText.replace(",", "").toDoubleOrNull() ?: continue
            if (qty != 0.0 && near((price * qty).toLong(), amount)) {
                return ParsedLine(name = name, unitPrice = price, qty = qty, amount = amount, weighed = qtyText.substringAfter('.', "").length >= 3)
            }
        }
        return if (rest.isEmpty() || rest.size == 1 && rest[0] == amountText) ParsedLine(name = name, unitPrice = amount, amount = amount) else null
    }

    /** Leading line number and product code: "3 941070 TUNA FISH" -> ("941070", "TUNA FISH"). */
    fun splitCode(name: String): Pair<String, String> {
        val m = Regex("^(?:\\d{1,3}\\s+)?([A-Z]?\\d{4,})[:\\s]+(.+)$").find(name.trim())
        if (m != null) return m.groupValues[1] to m.groupValues[2].trim()
        // No code: just drop a leading line number, "4 BREAD SLICED" -> "BREAD SLICED".
        return "" to name.trim().removePrefix("-").replace(Regex("^\\d{1,3}\\s+(?=\\D)"), "").trim()
    }

    fun parse(rows: List<String>): ParsedBill {
        val lines = mutableListOf<ParsedLine>()
        var pendingName = ""
        var started = false
        for (raw in rows) {
            val row = raw.trim()
            if (row.isEmpty()) continue
            val upper = row.uppercase()
            if (headerWords.count { it in upper } >= 2) { started = true; pendingName = ""; continue }
            // The totals come after the items.
            if (lines.isNotEmpty() && stopWords.any { upper.startsWith(it) }) break
            val (text, figures) = figuresAtEnd(row)
            val name = text.trim().takeIf { it.any(Char::isLetter) }
            val line = if (figures.size >= 1) lineFrom("", figures) else null
            when {
                line != null && name != null -> { lines += named(line, name); pendingName = "" }
                line != null && pendingName.isNotEmpty() -> { lines += named(line, pendingName); pendingName = "" }
                name != null && figures.isEmpty() -> pendingName = if (started || lines.isNotEmpty()) name else pendingName
                else -> {}
            }
        }
        val all = rows.joinToString("\n")
        val store = stores.firstOrNull { it in all.uppercase() }?.let(ShoppingPrices::displayName).orEmpty()
        return ParsedBill(date = findDate(all), time = Regex("\\b([01]?\\d|2[0-3]):[0-5]\\d\\b").find(all)?.value?.padStart(5, '0').orEmpty(), store = store, lines = lines)
    }

    private fun named(line: ParsedLine, text: String): ParsedLine {
        val (code, name) = splitCode(text)
        return line.copy(code = code, name = name)
    }

    private val datePatterns = listOf("d-MMM-yyyy", "d MMM yyyy", "d/M/yyyy", "d-M-yyyy", "yyyy-M-d", "yyyy/M/d", "d.M.yyyy", "d/M/yy")
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
