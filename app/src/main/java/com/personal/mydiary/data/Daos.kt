package com.personal.mydiary.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY done ASC, CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END, dueDate ASC, createdAt DESC")
    fun observeAll(): Flow<List<TaskItem>>

    /** Earlier titles, most used first, for suggestions. */
    @Query("SELECT title FROM tasks GROUP BY title COLLATE NOCASE ORDER BY COUNT(*) DESC, MAX(createdAt) DESC LIMIT 100")
    fun observeTitles(): Flow<List<String>>

    @Query("SELECT * FROM tasks WHERE done = 0 AND dueDate IS NOT NULL AND dueDate <= :day ORDER BY dueDate")
    fun observeDueBy(day: Long): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks WHERE done = 0 AND dueDate = :day")
    suspend fun dueOn(day: Long): List<TaskItem>

    @Query("SELECT COUNT(*) FROM tasks WHERE done = 1 AND completedAt BETWEEN :fromMillis AND :toMillis")
    fun observeCompletedBetween(fromMillis: Long, toMillis: Long): Flow<Int>

    @Upsert suspend fun upsert(item: TaskItem)
    @Delete suspend fun delete(item: TaskItem)
    @Query("SELECT * FROM tasks") suspend fun getAll(): List<TaskItem>
    @Insert suspend fun insertAll(items: List<TaskItem>)
    @Query("DELETE FROM tasks") suspend fun clear()
}

@Dao
interface AppointmentDao {
    @Query("SELECT * FROM appointments ORDER BY startAt DESC")
    fun observeAll(): Flow<List<Appointment>>

    @Query("SELECT * FROM appointments WHERE startAt >= :fromMillis ORDER BY startAt ASC")
    fun observeFrom(fromMillis: Long): Flow<List<Appointment>>

    @Query("SELECT * FROM appointments WHERE startAt < :beforeMillis ORDER BY startAt DESC LIMIT 50")
    fun observeBefore(beforeMillis: Long): Flow<List<Appointment>>

    @Query("SELECT * FROM appointments WHERE startAt BETWEEN :fromMillis AND :toMillis ORDER BY startAt ASC")
    fun observeBetween(fromMillis: Long, toMillis: Long): Flow<List<Appointment>>

    @Query("SELECT * FROM appointments WHERE startAt BETWEEN :fromMillis AND :toMillis ORDER BY startAt ASC")
    suspend fun between(fromMillis: Long, toMillis: Long): List<Appointment>

    @Query("SELECT * FROM appointments WHERE startAt > :nowMillis")
    suspend fun upcoming(nowMillis: Long): List<Appointment>

    /** Returns the new row id on insert, or -1 when an existing row was updated. */
    @Upsert suspend fun upsert(item: Appointment): Long
    @Delete suspend fun delete(item: Appointment)
    @Query("SELECT * FROM appointments") suspend fun getAll(): List<Appointment>
    @Insert suspend fun insertAll(items: List<Appointment>)
    @Query("DELETE FROM appointments") suspend fun clear()
}

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries ORDER BY date DESC, time DESC, id DESC")
    fun observeAll(): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM entries WHERE type = :type ORDER BY date DESC, time DESC, id DESC")
    fun observeByType(type: String): Flow<List<DiaryEntry>>

    /** Earlier titles of one entry type, most used first, for suggestions. */
    @Query("SELECT title FROM entries WHERE type = :type GROUP BY title COLLATE NOCASE ORDER BY COUNT(*) DESC, MAX(date) DESC LIMIT 100")
    fun observeTitles(type: String): Flow<List<String>>

    @Query("SELECT people FROM entries WHERE people != '' GROUP BY people COLLATE NOCASE ORDER BY COUNT(*) DESC, MAX(date) DESC LIMIT 50")
    fun observePeople(): Flow<List<String>>

    @Query("SELECT * FROM entries WHERE date = :date ORDER BY time ASC, id ASC")
    fun observeOnDate(date: Long): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM entries WHERE type = :type AND date BETWEEN :from AND :to ORDER BY date ASC, time ASC")
    fun observeTypeBetween(type: String, from: Long, to: Long): Flow<List<DiaryEntry>>

    @Upsert suspend fun upsert(item: DiaryEntry)
    @Delete suspend fun delete(item: DiaryEntry)
    @Query("SELECT * FROM entries") suspend fun getAll(): List<DiaryEntry>
    @Insert suspend fun insertAll(items: List<DiaryEntry>)
    @Query("DELETE FROM entries") suspend fun clear()
}

@Dao
interface OccasionDao {
    @Query("SELECT * FROM occasions")
    fun observeAll(): Flow<List<Occasion>>

    @Query("SELECT * FROM occasions WHERE contactKey = :key LIMIT 1")
    suspend fun byContactKey(key: String): Occasion?

    @Upsert suspend fun upsert(item: Occasion)
    @Delete suspend fun delete(item: Occasion)
    @Query("SELECT * FROM occasions") suspend fun getAll(): List<Occasion>
    @Insert suspend fun insertAll(items: List<Occasion>)
    @Query("DELETE FROM occasions") suspend fun clear()
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY date DESC, id DESC")
    fun observeAll(): Flow<List<Place>>

    /** The latest visit to each differently named place, for suggestions. */
    @Query("SELECT * FROM places WHERE id IN (SELECT MAX(id) FROM places GROUP BY name COLLATE NOCASE) ORDER BY date DESC LIMIT 100")
    fun observeDistinct(): Flow<List<Place>>

    @Query("SELECT * FROM places WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    fun observeBetween(from: Long, to: Long): Flow<List<Place>>

    @Upsert suspend fun upsert(item: Place)
    @Delete suspend fun delete(item: Place)
    @Query("SELECT * FROM places") suspend fun getAll(): List<Place>
    @Insert suspend fun insertAll(items: List<Place>)
    @Query("DELETE FROM places") suspend fun clear()
}

@Dao
interface MoodDao {
    @Query("SELECT * FROM moods ORDER BY date DESC")
    fun observeAll(): Flow<List<DayMood>>

    @Query("SELECT * FROM moods WHERE date = :date")
    fun observe(date: Long): Flow<DayMood?>

    @Query("SELECT * FROM moods WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    fun observeBetween(from: Long, to: Long): Flow<List<DayMood>>

    @Upsert suspend fun upsert(item: DayMood)
    @Query("DELETE FROM moods WHERE date = :date") suspend fun deleteOn(date: Long)
    @Query("SELECT * FROM moods") suspend fun getAll(): List<DayMood>
    @Insert suspend fun insertAll(items: List<DayMood>)
    @Query("DELETE FROM moods") suspend fun clear()
}
