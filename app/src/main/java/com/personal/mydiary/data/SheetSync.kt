package com.personal.mydiary.data

import android.content.Context
import com.personal.mydiary.work.AppointmentReminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** Connection details and sync bookkeeping, kept in shared preferences. */
class SheetSettings(context: Context) {
    private val prefs = context.getSharedPreferences("sheet_sync", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString("url", "").orEmpty()
        set(value) {
            if (value != url) prefs.edit().putString("url", value).remove(LAST_SERVER).remove(LAST_LOCAL).remove("sheet_url").apply()
        }

    /** Secret shared with the Apps Script; generated once per install. */
    val token: String
        get() = prefs.getString("token", null) ?: generateToken().also { prefs.edit().putString("token", it).apply() }

    var autoSync: Boolean
        get() = prefs.getBoolean("auto", true)
        set(value) = prefs.edit().putBoolean("auto", value).apply()

    val isConfigured get() = url.startsWith("https://")

    /** Server clock (sheet side) and phone clock at the end of the last successful sync. */
    var lastServer: Long
        get() = prefs.getLong(LAST_SERVER, 0)
        set(value) = prefs.edit().putLong(LAST_SERVER, value).apply()
    var lastLocal: Long
        get() = prefs.getLong(LAST_LOCAL, 0)
        set(value) = prefs.edit().putLong(LAST_LOCAL, value).apply()

    var lastMessage: String
        get() = prefs.getString("message", "").orEmpty()
        set(value) = prefs.edit().putString("message", value).apply()
    var sheetUrl: String
        get() = prefs.getString("sheet_url", "").orEmpty()
        set(value) = prefs.edit().putString("sheet_url", value).apply()

    /** Makes the next sync merge everything both ways without treating anything as deleted. */
    fun resetSyncState() = prefs.edit().remove(LAST_SERVER).remove(LAST_LOCAL).apply()

    private fun generateToken(): String {
        val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return (1..24).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private companion object {
        const val LAST_SERVER = "last_server"
        const val LAST_LOCAL = "last_local"
    }
}

/**
 * Two-way sync with a Google Sheet through the Apps Script web app in res/raw/sheet_script.js.
 *
 * Each sync downloads every tab, merges row by row with the database, then writes the merged
 * rows back. A row counts as changed in the sheet when its updatedAt (stamped by the script)
 * is newer than the last sync; a record counts as changed in the app when its updatedAt is
 * newer than the last sync on the phone's clock. If both changed, the app's version wins.
 */
object SheetSync {
    data class Result(val added: Int, val updated: Int, val deleted: Int) {
        override fun toString() =
            if (added + updated + deleted == 0) "Everything is up to date"
            else listOfNotNull(
                added.takeIf { it > 0 }?.let { "$it new from sheet" },
                updated.takeIf { it > 0 }?.let { "$it updated" },
                deleted.takeIf { it > 0 }?.let { "$it deleted" },
            ).joinToString(", ")
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sync(context: Context, db: AppDatabase): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            val settings = SheetSettings(context)
            check(settings.isConfigured) { "Add your Google Sheet web app link first" }

            val pulled = parse(request(settings.url + (if ('?' in settings.url) "&" else "?") + "token=" +
                URLEncoder.encode(settings.token, "UTF-8"), null))
            pulled["url"]?.jsonPrimitive?.content?.let { settings.sheetUrl = it }
            val sheetTabs = pulled["tabs"]?.jsonObject ?: JsonObject(emptyMap())

            val stats = Stats()
            val now = System.currentTimeMillis()
            val outgoing = tables.associate { table ->
                val rows = (sheetTabs[table.tab] as? JsonArray).orEmpty().map { row ->
                    row.jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }
                }
                table.tab to (table.columns to table.merge(context, db, rows, settings.lastServer, settings.lastLocal, now, stats))
            }
            val mergedAt = System.currentTimeMillis()

            val body = buildJsonObject {
                put("token", settings.token)
                putJsonObject("tabs") {
                    outgoing.forEach { (tab, pair) ->
                        val (columns, rows) = pair
                        putJsonObject(tab) {
                            putJsonArray("columns") {
                                columns.forEach { c ->
                                    add(buildJsonObject {
                                        put("name", c.name)
                                        c.choices?.let { choices -> put("choices", buildJsonArray { choices.forEach { add(JsonPrimitive(it)) } }) }
                                    })
                                }
                            }
                            putJsonArray("rows") {
                                rows.forEach { row -> add(JsonObject(row.mapValues { JsonPrimitive(it.value) })) }
                            }
                        }
                    }
                }
            }
            val pushed = parse(request(settings.url, body.toString()))
            settings.lastServer = pushed["now"]?.jsonPrimitive?.content?.toLongOrNull() ?: error("Unexpected reply from the sheet")
            settings.lastLocal = mergedAt

            db.appointments().upcoming(System.currentTimeMillis()).forEach { AppointmentReminders.schedule(context, it) }
            Result(stats.added, stats.updated, stats.deleted)
        }
    }

    /** Checks the link and secret key without changing anything; returns the sheet's name. */
    suspend fun test(context: Context): String = withContext(Dispatchers.IO) {
        val settings = SheetSettings(context)
        val reply = parse(request(settings.url + (if ('?' in settings.url) "&" else "?") + "token=" +
            URLEncoder.encode(settings.token, "UTF-8"), null))
        reply["url"]?.jsonPrimitive?.content?.let { settings.sheetUrl = it }
        reply["name"]?.jsonPrimitive?.content.orEmpty()
    }

    private fun parse(text: String): JsonObject {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            error("The link didn't return sheet data. Check it is the web app URL and access is set to \"Anyone\".")
        }
        obj["error"]?.jsonPrimitive?.content?.let { error(it) }
        return obj
    }

    /** Apps Script answers through a redirect to googleusercontent.com, which must be fetched with GET. */
    private fun request(url: String, body: String?): String {
        var target = URL(url)
        var payload = body
        repeat(5) {
            val c = target.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.requestMethod = if (payload != null) "POST" else "GET"
                payload?.let { p ->
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(p.toByteArray()) }
                }
                val code = c.responseCode
                if (code in 300..399) {
                    target = URL(target, c.getHeaderField("Location"))
                    payload = null
                    return@repeat
                }
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code >= 400) error("Google returned error $code. Check the web app link.")
                return text
            } finally {
                c.disconnect()
            }
        }
        error("Too many redirects from Google")
    }

    private class Stats { var added = 0; var updated = 0; var deleted = 0 }

    private class Column(val name: String, val choices: List<String>? = null)

    private abstract class Table<T : Any>(val tab: String, columns: List<Column>) {
        /** Data columns followed by the bookkeeping columns the script and sync rely on. */
        open val columns: List<Column> = columns + Column("uid") + Column("updatedAt")

        abstract suspend fun all(db: AppDatabase): List<T>
        abstract fun key(item: T): String
        abstract fun updatedAt(item: T): Long
        abstract fun toRow(item: T): Map<String, String>

        /** The record a sheet row describes, keeping app-only fields from [existing]; null when the row is unusable. */
        abstract fun fromRow(row: Map<String, String>, existing: T?, key: String, now: Long): T?
        abstract suspend fun store(db: AppDatabase, item: T)
        abstract suspend fun remove(context: Context, db: AppDatabase, item: T)

        open fun rowKey(row: Map<String, String>): String? = row["uid"]?.trim()?.takeIf { it.isNotEmpty() }

        suspend fun merge(
            context: Context, db: AppDatabase, sheetRows: List<Map<String, String>>,
            lastServer: Long, lastLocal: Long, now: Long, stats: Stats,
        ): List<Map<String, String>> {
            val local = all(db).associateBy(::key)
            val seen = mutableSetOf<String>()
            val out = mutableListOf<Map<String, String>>()

            for (row in sheetRows) {
                val key = rowKey(row)
                if (key != null && !seen.add(key)) continue // duplicate row in the sheet
                val rowUpdated = row["updatedAt"]?.toDoubleOrNull()?.toLong() ?: 0L
                val sheetChanged = rowUpdated == 0L || rowUpdated > lastServer
                val existing = key?.let(local::get)

                if (existing != null) {
                    val localChanged = updatedAt(existing) > lastLocal
                    val parsed = if (sheetChanged && !localChanged) fromRow(row, existing, key, now) else null
                    when {
                        sheetChanged && !localChanged && parsed == null -> out += row // unreadable edit: keep it as typed
                        parsed != null && toRow(parsed) != toRow(existing) -> {
                            store(db, parsed); stats.updated++; out += toRow(parsed)
                        }
                        else -> out += toRow(existing)
                    }
                } else {
                    val parsed = fromRow(row, null, key ?: newUid(), now)
                    when {
                        parsed == null -> out += row // keep rows the app can't read so nothing typed is lost
                        key != null && !sheetChanged -> {} // was synced before and has since been deleted in the app
                        else -> {
                            store(db, parsed); stats.added++; seen += key(parsed); out += toRow(parsed)
                        }
                    }
                }
            }

            for ((key, item) in local) {
                if (key in seen) continue
                if (lastLocal > 0 && updatedAt(item) <= lastLocal) {
                    remove(context, db, item); stats.deleted++ // deleted from the sheet
                } else {
                    out += toRow(item)
                }
            }
            return out
        }
    }

    // ---- Tabs ----

    private val priorities = mapOf(Priority.LOW to "Low", Priority.NORMAL to "Normal", Priority.HIGH to "High")
    private val moodNames = mapOf(
        Mood.GOOD to "Good", Mood.FAIR to "Fair", Mood.BAD to "Bad",
        Mood.ANGRY to "Angry", Mood.TIRED to "Tired", Mood.BUSY to "Busy",
    )
    private val occasionNames = mapOf(
        OccasionType.BIRTHDAY to "Birthday", OccasionType.ANNIVERSARY to "Anniversary", OccasionType.SPECIAL to "Special day",
    )

    private object Tasks : Table<TaskItem>(
        "Tasks",
        listOf(Column("Title"), Column("Notes"), Column("Due date"), Column("Priority", priorities.values.toList()),
            Column("Done", listOf("TRUE", "FALSE")), Column("Completed")),
    ) {
        override suspend fun all(db: AppDatabase) = db.tasks().getAll()
        override fun key(item: TaskItem) = item.uid
        override fun updatedAt(item: TaskItem) = item.updatedAt
        override fun toRow(item: TaskItem) = mapOf(
            "Title" to item.title, "Notes" to item.notes,
            "Due date" to item.dueDate?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty(),
            "Priority" to priorities.getValue(item.priority), "Done" to if (item.done) "TRUE" else "FALSE",
            "Completed" to item.completedAt?.let(::formatDateTime).orEmpty(), "uid" to item.uid,
        )
        override fun fromRow(row: Map<String, String>, existing: TaskItem?, key: String, now: Long): TaskItem? {
            val title = row.text("Title").ifEmpty { return null }
            val done = parseBool(row.text("Done"))
            val completed = parseDateTime(row.text("Completed"))?.let(::toMillis)
                ?: existing?.completedAt?.takeIf { done } ?: if (done) now else null
            return (existing ?: TaskItem(title = title, uid = key, createdAt = now)).copy(
                title = title, notes = row.text("Notes"), dueDate = parseDate(row.text("Due date"))?.toEpochDay(),
                priority = parseChoice(row.text("Priority"), priorities) ?: Priority.NORMAL,
                done = done, completedAt = if (done) completed else null, updatedAt = now,
            )
        }
        override suspend fun store(db: AppDatabase, item: TaskItem) = db.tasks().upsert(item)
        override suspend fun remove(context: Context, db: AppDatabase, item: TaskItem) = db.tasks().delete(item)
    }

    private object Appointments : Table<Appointment>(
        "Appointments",
        listOf(Column("Title"), Column("Date"), Column("Time"), Column("Location"), Column("Notes"), Column("Remind (minutes before)")),
    ) {
        override suspend fun all(db: AppDatabase) = db.appointments().getAll()
        override fun key(item: Appointment) = item.uid
        override fun updatedAt(item: Appointment) = item.updatedAt
        override fun toRow(item: Appointment): Map<String, String> {
            val at = Instant.ofEpochMilli(item.startAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
            return mapOf(
                "Title" to item.title, "Date" to at.toLocalDate().toString(), "Time" to at.toLocalTime().format(hm),
                "Location" to item.location, "Notes" to item.notes,
                "Remind (minutes before)" to item.remindMinutesBefore.toString(), "uid" to item.uid,
            )
        }
        override fun fromRow(row: Map<String, String>, existing: Appointment?, key: String, now: Long): Appointment? {
            val title = row.text("Title").ifEmpty { return null }
            val date = parseDate(row.text("Date")) ?: return null
            val time = parseTime(row.text("Time")) ?: LocalTime.of(9, 0)
            return (existing ?: Appointment(title = title, startAt = 0, uid = key)).copy(
                title = title, startAt = toMillis(date.atTime(time)), location = row.text("Location"),
                notes = row.text("Notes"),
                remindMinutesBefore = row.text("Remind (minutes before)").toDoubleOrNull()?.toInt() ?: 30,
                updatedAt = now,
            )
        }
        override suspend fun store(db: AppDatabase, item: Appointment) { db.appointments().upsert(item) }
        override suspend fun remove(context: Context, db: AppDatabase, item: Appointment) {
            db.appointments().delete(item)
            AppointmentReminders.cancel(context, item.id)
        }
    }

    private class Entries(tab: String, private val type: EntryType, private val peopleColumn: String) : Table<DiaryEntry>(
        tab, listOf(Column("Date"), Column("Time"), Column("Title"), Column("Details"), Column(peopleColumn)),
    ) {
        override suspend fun all(db: AppDatabase) = db.entries().getAll().filter { it.type == type }
        override fun key(item: DiaryEntry) = item.uid
        override fun updatedAt(item: DiaryEntry) = item.updatedAt
        override fun toRow(item: DiaryEntry) = mapOf(
            "Date" to LocalDate.ofEpochDay(item.date).toString(), "Time" to item.time, "Title" to item.title,
            "Details" to item.details, peopleColumn to item.people, "uid" to item.uid,
        )
        override fun fromRow(row: Map<String, String>, existing: DiaryEntry?, key: String, now: Long): DiaryEntry? {
            val title = row.text("Title").ifEmpty { return null }
            val dateText = row.text("Date")
            val date = if (dateText.isEmpty()) LocalDate.now() else parseDate(dateText) ?: return null
            return (existing ?: DiaryEntry(type = type, date = 0, title = title, uid = key)).copy(
                date = date.toEpochDay(), time = parseTime(row.text("Time"))?.format(hm).orEmpty(), title = title,
                details = row.text("Details"), people = row.text(peopleColumn), updatedAt = now,
            )
        }
        override suspend fun store(db: AppDatabase, item: DiaryEntry) = db.entries().upsert(item)
        override suspend fun remove(context: Context, db: AppDatabase, item: DiaryEntry) = db.entries().delete(item)
    }

    private object SpecialDays : Table<Occasion>(
        "Special days",
        listOf(Column("Name"), Column("Type", occasionNames.values.toList()), Column("Day"), Column("Month"),
            Column("Year"), Column("Notes")),
    ) {
        override suspend fun all(db: AppDatabase) = db.occasions().getAll()
        override fun key(item: Occasion) = item.uid
        override fun updatedAt(item: Occasion) = item.updatedAt
        override fun toRow(item: Occasion) = mapOf(
            "Name" to item.name, "Type" to occasionNames.getValue(item.type), "Day" to item.day.toString(),
            "Month" to item.month.toString(), "Year" to item.year?.toString().orEmpty(), "Notes" to item.notes,
            "uid" to item.uid,
        )
        override fun fromRow(row: Map<String, String>, existing: Occasion?, key: String, now: Long): Occasion? {
            val name = row.text("Name").ifEmpty { return null }
            val month = parseMonth(row.text("Month")) ?: return null
            val day = row.text("Day").toDoubleOrNull()?.toInt()?.takeIf { it in 1..31 } ?: return null
            val type = parseChoice(row.text("Type"), occasionNames)
                ?: if (row.text("Type").lowercase().startsWith("anniv")) OccasionType.ANNIVERSARY
                else if (row.text("Type").isBlank() || row.text("Type").lowercase().startsWith("birth")) OccasionType.BIRTHDAY
                else OccasionType.SPECIAL
            return (existing ?: Occasion(name = name, type = type, month = month, day = day, uid = key)).copy(
                name = name, type = type, month = month, day = day,
                year = row.text("Year").toDoubleOrNull()?.toInt()?.takeIf { it in 1800..2200 },
                notes = row.text("Notes"), updatedAt = now,
            )
        }
        override suspend fun store(db: AppDatabase, item: Occasion) = db.occasions().upsert(item)
        override suspend fun remove(context: Context, db: AppDatabase, item: Occasion) = db.occasions().delete(item)
    }

    private object Places : Table<Place>(
        "Places",
        listOf(Column("Date"), Column("Name"), Column("Address"), Column("Latitude"), Column("Longitude"), Column("Notes")),
    ) {
        override suspend fun all(db: AppDatabase) = db.places().getAll()
        override fun key(item: Place) = item.uid
        override fun updatedAt(item: Place) = item.updatedAt
        override fun toRow(item: Place) = mapOf(
            "Date" to LocalDate.ofEpochDay(item.date).toString(), "Name" to item.name, "Address" to item.address,
            "Latitude" to item.latitude?.toString().orEmpty(), "Longitude" to item.longitude?.toString().orEmpty(),
            "Notes" to item.notes, "uid" to item.uid,
        )
        override fun fromRow(row: Map<String, String>, existing: Place?, key: String, now: Long): Place? {
            val name = row.text("Name").ifEmpty { return null }
            val dateText = row.text("Date")
            val date = if (dateText.isEmpty()) LocalDate.now() else parseDate(dateText) ?: return null
            return (existing ?: Place(name = name, date = 0, uid = key)).copy(
                name = name, date = date.toEpochDay(), address = row.text("Address"),
                latitude = row.text("Latitude").toDoubleOrNull(), longitude = row.text("Longitude").toDoubleOrNull(),
                notes = row.text("Notes"), updatedAt = now,
            )
        }
        override suspend fun store(db: AppDatabase, item: Place) = db.places().upsert(item)
        override suspend fun remove(context: Context, db: AppDatabase, item: Place) = db.places().delete(item)
    }

    /** Moods are identified by their date, one row per day. */
    private object Moods : Table<DayMood>(
        "Moods", listOf(Column("Date"), Column("Mood", moodNames.values.toList()), Column("Note")),
    ) {
        override val columns = listOf(Column("Date"), Column("Mood", moodNames.values.toList()), Column("Note"), Column("updatedAt"))
        override fun rowKey(row: Map<String, String>) = parseDate(row.text("Date"))?.toString()
        override suspend fun all(db: AppDatabase) = db.moods().getAll()
        override fun key(item: DayMood) = LocalDate.ofEpochDay(item.date).toString()
        override fun updatedAt(item: DayMood) = item.updatedAt
        override fun toRow(item: DayMood) = mapOf(
            "Date" to key(item), "Mood" to moodNames.getValue(item.mood), "Note" to item.note,
        )
        override fun fromRow(row: Map<String, String>, existing: DayMood?, key: String, now: Long): DayMood? {
            val date = parseDate(row.text("Date")) ?: return null
            val mood = parseMood(row.text("Mood")) ?: return null
            return DayMood(date.toEpochDay(), mood, row.text("Note"), now)
        }
        override suspend fun store(db: AppDatabase, item: DayMood) = db.moods().upsert(item)
        override suspend fun remove(context: Context, db: AppDatabase, item: DayMood) = db.moods().deleteOn(item.date)
    }

    private val tables: List<Table<*>> = listOf(
        Tasks, Appointments,
        Entries("Activities", EntryType.ACTIVITY, "With"),
        Entries("Memories", EntryType.MEMORY, "People"),
        Entries("Good deeds", EntryType.GOOD_DEED, "Who I helped"),
        SpecialDays, Places, Moods,
    )

    // ---- Parsing what people type into the sheet ----

    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dayFirst = listOf("d/M/uuuu", "d-M-uuuu", "d.M.uuuu").map { DateTimeFormatter.ofPattern(it) }
    private val timeFormats = listOf("H:mm", "H:mm:ss", "h:mm a", "h:mma", "h a", "ha").map {
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH)
    }

    private fun Map<String, String>.text(column: String) = this[column]?.trim().orEmpty()

    private fun toMillis(dt: LocalDateTime) = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun formatDateTime(millis: Long) =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime().format(dateTimeFormat)

    private fun parseDate(s: String): LocalDate? {
        if (s.isEmpty()) return null
        runCatching { return LocalDate.parse(s.take(10)) }
        dayFirst.forEach { f -> runCatching { return LocalDate.parse(s.substringBefore(' '), f) } }
        return null
    }

    private fun parseTime(s: String): LocalTime? {
        if (s.isEmpty()) return null
        timeFormats.forEach { f -> runCatching { return LocalTime.parse(s.uppercase(), f) } }
        return null
    }

    private fun parseDateTime(s: String): LocalDateTime? {
        if (s.isEmpty()) return null
        runCatching { return LocalDateTime.parse(s, dateTimeFormat) }
        val date = parseDate(s) ?: return null
        return date.atTime(parseTime(s.substringAfter(' ', "")) ?: LocalTime.MIDNIGHT)
    }

    private fun parseBool(s: String) = s.lowercase() in setOf("true", "yes", "y", "1", "done", "x", "✓", "✔", "✅")

    private fun <E> parseChoice(s: String, names: Map<E, String>): E? =
        names.entries.firstOrNull { it.value.equals(s, ignoreCase = true) }?.key

    private fun parseMood(s: String): Mood? = parseChoice(s, moodNames) ?: when (s.lowercase()) {
        "😊", "🙂", "😀", "happy", "great" -> Mood.GOOD
        "😐", "ok", "okay", "normal", "average" -> Mood.FAIR
        "😞", "🙁", "😢", "sad", "unhappy" -> Mood.BAD
        "😠", "😡", "😤", "mad", "angry" -> Mood.ANGRY
        "😴", "🥱", "sleepy", "exhausted" -> Mood.TIRED
        "🤯", "💼", "hectic" -> Mood.BUSY
        else -> null
    }

    private fun parseMonth(s: String): Int? =
        s.toDoubleOrNull()?.toInt()?.takeIf { it in 1..12 }
            ?: Month.entries.firstOrNull { s.length >= 3 && it.name.startsWith(s.uppercase().take(3)) }?.value
}
