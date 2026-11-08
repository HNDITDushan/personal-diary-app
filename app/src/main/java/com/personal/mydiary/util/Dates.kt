package com.personal.mydiary.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
val longDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
val shortDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val storedTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun today(): LocalDate = LocalDate.now()

fun Long.epochDayToDate(): LocalDate = LocalDate.ofEpochDay(this)

fun Long.millisToDateTime(): LocalDateTime =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDateTime()

fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun LocalDate.startMillis(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun LocalDate.endMillis(): Long = plusDays(1).startMillis() - 1

fun YearMonth.firstEpochDay(): Long = atDay(1).toEpochDay()
fun YearMonth.lastEpochDay(): Long = atEndOfMonth().toEpochDay()

fun LocalTime.toStored(): String = format(storedTime)
fun String.storedToTime(): LocalTime? = if (isBlank()) null else runCatching { LocalTime.parse(this, storedTime) }.getOrNull()

/** "9:00 AM", or "9:00 AM – 11:30 AM" when an end time is stored too. */
fun timeRangeLabel(start: String, end: String): String {
    val from = start.storedToTime()?.format(timeFormat) ?: return ""
    val to = end.storedToTime()?.format(timeFormat) ?: return from
    return "$from – $to"
}

/** "2 h 30 min" between two stored times; an end before the start runs past midnight. */
fun durationLabel(start: String, end: String): String {
    val from = start.storedToTime() ?: return ""
    val to = end.storedToTime() ?: return ""
    val minutes = Math.floorMod(java.time.Duration.between(from, to).toMinutes(), 24 * 60L)
    return listOfNotNull((minutes / 60).takeIf { it > 0 }?.let { "$it h" }, (minutes % 60).takeIf { it > 0 }?.let { "$it min" })
        .joinToString(" ")
}

/** Date of a yearly occasion falling on or after [from]; Feb 29 falls back to Feb 28 in non-leap years. */
fun nextOccurrence(month: Int, day: Int, from: LocalDate = today()): LocalDate {
    fun inYear(year: Int): LocalDate {
        val ym = YearMonth.of(year, month)
        val d = if (month == 2 && day == 29 && !Year.isLeap(year.toLong())) 28 else day.coerceAtMost(ym.lengthOfMonth())
        return ym.atDay(d)
    }
    val thisYear = inYear(from.year)
    return if (thisYear.isBefore(from)) inYear(from.year + 1) else thisYear
}

fun daysFromToday(date: LocalDate): Long = ChronoUnit.DAYS.between(today(), date)

fun relativeDay(date: LocalDate): String = when (val n = daysFromToday(date)) {
    0L -> "Today"
    1L -> "Tomorrow"
    -1L -> "Yesterday"
    in 2L..30L -> "In $n days"
    else -> date.format(dayFormat)
}
