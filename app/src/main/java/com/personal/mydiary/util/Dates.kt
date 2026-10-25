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
