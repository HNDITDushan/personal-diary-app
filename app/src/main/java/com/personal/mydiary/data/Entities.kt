package com.personal.mydiary.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class Priority { LOW, NORMAL, HIGH }

/** Kinds of diary entries recorded against a day. */
enum class EntryType { ACTIVITY, MEMORY, GOOD_DEED }

enum class Mood { GOOD, FAIR, BAD }

enum class OccasionType { BIRTHDAY, ANNIVERSARY, SPECIAL }

/** Dates are stored as epoch days (LocalDate.toEpochDay) and instants as epoch millis. */
@Serializable
@Entity(tableName = "tasks")
data class TaskItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val dueDate: Long? = null,
    val priority: Priority = Priority.NORMAL,
    val done: Boolean = false,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "appointments")
data class Appointment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startAt: Long,
    val location: String = "",
    val notes: String = "",
    /** Minutes before [startAt] to remind; 0 disables the reminder. */
    val remindMinutesBefore: Int = 30,
)

@Serializable
@Entity(tableName = "entries", indices = [Index("date"), Index("type")])
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: EntryType,
    val date: Long,
    /** "HH:mm", or empty when no time was given. */
    val time: String = "",
    val title: String,
    val details: String = "",
    val people: String = "",
)

/** A yearly recurring day: birthday, anniversary or other special day. */
@Serializable
@Entity(tableName = "occasions", indices = [Index(value = ["contactKey"], unique = true)])
data class Occasion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: OccasionType,
    val month: Int,
    val day: Int,
    val year: Int? = null,
    val notes: String = "",
    /** Set for entries imported from the phone's (Google) contacts. */
    val contactKey: String? = null,
)

@Serializable
@Entity(tableName = "places", indices = [Index("date")])
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val date: Long,
    val notes: String = "",
)

@Serializable
@Entity(tableName = "moods")
data class DayMood(
    @PrimaryKey val date: Long,
    val mood: Mood,
    val note: String = "",
)
