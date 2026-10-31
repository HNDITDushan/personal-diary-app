package com.personal.mydiary.util

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

private val moneyPattern = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))

/** "Rs 1,234.50" from whole cents; negative amounts get a leading minus. */
fun formatMoney(cents: Long): String =
    (if (cents < 0) "−" else "") + "Rs " + moneyPattern.format(abs(cents) / 100.0)

/** Plain figure for editing in a text field. */
fun moneyInput(cents: Long): String = BigDecimal(cents).movePointLeft(2).stripTrailingZeros().toPlainString()

/** Whole cents from typed text; null when it isn't a number. */
fun parseMoney(text: String): Long? = runCatching {
    BigDecimal(text.replace(",", "").replace(" ", "")).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
}.getOrNull()
