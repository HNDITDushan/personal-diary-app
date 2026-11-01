package com.personal.mydiary.data

class MergeStats {
    var added = 0
    var updated = 0
    var deleted = 0
}

/** How one kind of record is read from and written to its sheet tab. */
internal interface MergeRules<T : Any> {
    fun key(item: T): String
    fun updatedAt(item: T): Long
    fun toRow(item: T): Map<String, String>
    fun rowKey(row: Map<String, String>): String?

    /** The record a row describes, keeping app-only fields from [existing]; null when the row is unusable. */
    fun fromRow(row: Map<String, String>, existing: T?, key: String, now: Long): T?
    suspend fun store(item: T)
    suspend fun remove(item: T)

    /** False for records that must survive their row being deleted, such as an account with entries. */
    suspend fun canRemove(item: T): Boolean = true
}

/**
 * Merges one tab with the app's records and returns the rows to write back.
 *
 * A record missing from the sheet normally means it was deleted there, but a tab that doesn't exist
 * yet or has no rows says nothing about that: it may be new, or cleared by accident. Those cases
 * never delete anything, and everything in the app is written back instead.
 */
internal suspend fun <T : Any> mergeSheetRows(
    rules: MergeRules<T>,
    local: List<T>,
    sheetRows: List<Map<String, String>>,
    tabExists: Boolean,
    lastServer: Long,
    lastLocal: Long,
    now: Long,
    stats: MergeStats,
): List<Map<String, String>> {
    val byKey = local.associateBy(rules::key)
    val seen = mutableSetOf<String>()
    val out = mutableListOf<Map<String, String>>()

    for (row in sheetRows) {
        val key = rules.rowKey(row)
        if (key != null && !seen.add(key)) continue // duplicate row in the sheet
        val rowUpdated = row["updatedAt"]?.toDoubleOrNull()?.toLong() ?: 0L
        val sheetChanged = rowUpdated == 0L || rowUpdated > lastServer
        val existing = key?.let(byKey::get)

        if (existing != null) {
            val localChanged = rules.updatedAt(existing) > lastLocal
            val parsed = if (sheetChanged && !localChanged) rules.fromRow(row, existing, key, now) else null
            when {
                sheetChanged && !localChanged && parsed == null -> out += row // unreadable edit: keep it as typed
                parsed != null && rules.toRow(parsed) != rules.toRow(existing) -> {
                    rules.store(parsed); stats.updated++; out += rules.toRow(parsed)
                }
                else -> out += rules.toRow(existing)
            }
        } else {
            val parsed = rules.fromRow(row, null, key ?: newUid(), now)
            when {
                parsed == null -> out += row // keep rows the app can't read so nothing typed is lost
                key != null && !sheetChanged -> {} // was synced before and has since been deleted in the app
                else -> {
                    rules.store(parsed); stats.added++; seen += rules.key(parsed); out += rules.toRow(parsed)
                }
            }
        }
    }

    val mayDelete = tabExists && sheetRows.isNotEmpty() && lastLocal > 0
    for ((key, item) in byKey) {
        if (key in seen) continue
        if (mayDelete && rules.updatedAt(item) <= lastLocal && rules.canRemove(item)) {
            rules.remove(item); stats.deleted++ // deleted from the sheet
        } else {
            out += rules.toRow(item)
        }
    }
    return out
}
