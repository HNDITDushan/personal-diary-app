package com.personal.mydiary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TaskItem::class, Appointment::class, DiaryEntry::class, Occasion::class, Place::class, DayMood::class],
    version = 1,
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

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .build()
                .also { instance = it }
        }
    }
}
