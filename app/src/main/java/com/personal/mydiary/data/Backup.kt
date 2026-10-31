package com.personal.mydiary.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.personal.mydiary.work.AppointmentReminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class BackupData(
    val version: Int = 1,
    val exportedAt: Long,
    val tasks: List<TaskItem> = emptyList(),
    val appointments: List<Appointment> = emptyList(),
    val entries: List<DiaryEntry> = emptyList(),
    val occasions: List<Occasion> = emptyList(),
    val places: List<Place> = emptyList(),
    val moods: List<DayMood> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val moneyTxns: List<MoneyTxn> = emptyList(),
    val statements: List<CardStatement> = emptyList(),
) {
    val itemCount get() = tasks.size + appointments.size + entries.size + occasions.size + places.size + moods.size +
        accounts.size + moneyTxns.size + statements.size
}

/**
 * Backs up everything to a single JSON file. The file location is chosen with the system
 * file picker, so it can be saved on the phone, an SD card, or straight into Google Drive.
 */
object Backup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val PREFS = "backup"
    private const val LAST_BACKUP = "last_backup"

    fun suggestedFileName(): String =
        "MyDiary-backup-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))}.json"

    suspend fun export(context: Context, db: AppDatabase, uri: Uri): Int = withContext(Dispatchers.IO) {
        val data = BackupData(
            exportedAt = System.currentTimeMillis(),
            tasks = db.tasks().getAll(),
            appointments = db.appointments().getAll(),
            entries = db.entries().getAll(),
            occasions = db.occasions().getAll(),
            places = db.places().getAll(),
            moods = db.moods().getAll(),
            accounts = db.accounts().getAll(),
            moneyTxns = db.moneyTxns().getAll(),
            statements = db.statements().getAll(),
        )
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the file")
        out.use { it.write(json.encodeToString(BackupData.serializer(), data).toByteArray()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(LAST_BACKUP, data.exportedAt).apply()
        data.itemCount
    }

    /** Replaces all current data with the backup's contents. */
    suspend fun restore(context: Context, db: AppDatabase, uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("Could not open the file")
        val data = runCatching { json.decodeFromString(BackupData.serializer(), text) }
            .getOrElse { throw IllegalArgumentException("This is not a My Diary backup file") }
        db.withTransaction {
            db.tasks().clear(); db.tasks().insertAll(data.tasks)
            db.appointments().clear(); db.appointments().insertAll(data.appointments)
            db.entries().clear(); db.entries().insertAll(data.entries)
            db.occasions().clear(); db.occasions().insertAll(data.occasions)
            db.places().clear(); db.places().insertAll(data.places)
            db.moods().clear(); db.moods().insertAll(data.moods)
            db.accounts().clear(); db.accounts().insertAll(data.accounts)
            db.moneyTxns().clear(); db.moneyTxns().insertAll(data.moneyTxns)
            db.statements().clear(); db.statements().insertAll(data.statements)
        }
        db.appointments().upcoming(System.currentTimeMillis()).forEach { AppointmentReminders.schedule(context, it) }
        ensureCashAccount(db)
        // Restored records are older than the last sheet sync; without a reset they would look deleted from the sheet.
        SheetSettings(context).resetSyncState()
        data.itemCount
    }

    fun lastBackupMillis(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_BACKUP, 0L).takeIf { it > 0 }
}
