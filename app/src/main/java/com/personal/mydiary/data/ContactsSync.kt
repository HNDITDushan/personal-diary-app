package com.personal.mydiary.data

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports birthdays and anniversaries from the phone's contacts. Contacts from the Google
 * account are synced to the phone by Android, so this picks up Google Contacts birthdays.
 */
object ContactsSync {
    data class Result(val added: Int, val updated: Int)

    suspend fun sync(context: Context, db: AppDatabase): Result = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            ContactsContract.Data.LOOKUP_KEY,
            ContactsContract.Data.DISPLAY_NAME,
            Event.START_DATE,
            Event.TYPE,
            Event.LABEL,
        )
        var added = 0
        var updated = 0
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(Event.CONTENT_ITEM_TYPE),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val lookup = c.getString(0) ?: continue
                val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val date = parseDate(c.getString(2) ?: continue) ?: continue
                val eventType = c.getInt(3)
                val label = c.getString(4).orEmpty()
                val type = when (eventType) {
                    Event.TYPE_BIRTHDAY -> OccasionType.BIRTHDAY
                    Event.TYPE_ANNIVERSARY -> OccasionType.ANNIVERSARY
                    else -> OccasionType.SPECIAL
                }
                val key = "$lookup|$eventType|$label"
                val existing = db.occasions().byContactKey(key)
                val displayName = if (type == OccasionType.SPECIAL && label.isNotBlank()) "$name – $label" else name
                val occasion = (existing ?: Occasion(name = "", type = type, month = 1, day = 1, contactKey = key))
                    .copy(name = displayName, type = type, month = date.first, day = date.second, year = date.third)
                if (existing == null) added++ else if (existing != occasion) updated++
                if (existing != occasion) db.occasions().save(occasion)
            }
        }
        Result(added, updated)
    }

    private val fullDate = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val noYear = Regex("""^--(\d{1,2})-?(\d{1,2})""")
    private val compact = Regex("""^(\d{4})(\d{2})(\d{2})$""")

    /** Contact event dates come as "yyyy-MM-dd", "--MM-dd" (no year) or "yyyyMMdd". */
    private fun parseDate(raw: String): Triple<Int, Int, Int?>? {
        val s = raw.trim()
        val (y, m, d) = (fullDate.find(s) ?: compact.find(s))?.destructured
            ?.let { (y, m, d) -> Triple(y.toInt(), m.toInt(), d.toInt()) }
            ?: noYear.find(s)?.destructured?.let { (m, d) -> Triple(0, m.toInt(), d.toInt()) }
            ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        // Google uses placeholder years such as 1604 when the year is unknown.
        return Triple(m, d, y.takeIf { it >= 1900 })
    }
}
