package com.personal.mydiary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TaskItem::class, Appointment::class, DiaryEntry::class, Occasion::class, Place::class, DayMood::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun appointments(): AppointmentDao
    abstract fun entries(): EntryDao
    abstract fun occasions(): OccasionDao
    abstract fun places(): PlaceDao
    abstract fun moods(): MoodDao

    companion object {
        const val NAME = "diary.db"

        /** Adds the Google Sheet sync columns; existing rows get a random uid. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                listOf("tasks", "appointments", "entries", "occasions", "places").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE $table SET uid = lower(hex(randomblob(16)))")
                }
                listOf("tasks", "appointments", "entries", "occasions", "places", "moods").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE $table SET updatedAt = $now")
                }
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
