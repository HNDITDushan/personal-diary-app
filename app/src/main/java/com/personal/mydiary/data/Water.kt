package com.personal.mydiary.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** One drink: [amountMl] of water at [time] ("HH:mm") on epoch day [date]. */
@Serializable
@Entity(tableName = "water", indices = [Index("date")])
data class WaterLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val time: String = "",
    val amountMl: Int,
    val uid: String = newUid(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface WaterDao {
    @Query("SELECT * FROM water WHERE date = :date ORDER BY time DESC, id DESC")
    fun observeOn(date: Long): Flow<List<WaterLog>>

    @Query("SELECT * FROM water WHERE date BETWEEN :from AND :to")
    fun observeBetween(from: Long, to: Long): Flow<List<WaterLog>>

    @Query("SELECT COALESCE(SUM(amountMl), 0) FROM water WHERE date = :date")
    suspend fun totalOn(date: Long): Int

    @Query("SELECT * FROM water WHERE date = :date ORDER BY time DESC, id DESC LIMIT 1")
    suspend fun lastOn(date: Long): WaterLog?

    @Upsert suspend fun upsert(item: WaterLog)
    @Delete suspend fun delete(item: WaterLog)
    @Query("DELETE FROM water WHERE uid = :uid") suspend fun deleteByUid(uid: String)
    @Query("SELECT * FROM water") suspend fun getAll(): List<WaterLog>
    @Insert suspend fun insertAll(items: List<WaterLog>)
    @Query("DELETE FROM water") suspend fun clear()
}

suspend fun WaterDao.save(item: WaterLog) = upsert(item.copy(updatedAt = System.currentTimeMillis()))

object Hydration {
    const val DEFAULT_GOAL_ML = 2000

    /** Total drunk on each epoch day that has any drinks. */
    fun dailyTotals(logs: List<WaterLog>): Map<Long, Int> =
        logs.groupBy { it.date }.mapValues { (_, day) -> day.sumOf { it.amountMl } }

    /**
     * Days in a row the goal was reached, counting back from [today]. Today only breaks the run
     * once it is over, so a streak still shows in the morning before the first glass.
     */
    fun streak(totals: Map<Long, Int>, goalMl: Int, today: Long): Int {
        var day = if ((totals[today] ?: 0) >= goalMl) today else today - 1
        var count = 0
        while ((totals[day] ?: 0) >= goalMl) { count++; day-- }
        return count
    }

    /** About 35 ml per kg of body weight, rounded to 50 ml and kept within sensible limits. */
    fun suggestedGoalMl(weightKg: Double): Int =
        ((weightKg * 35 / 50).roundToInt() * 50).coerceIn(1500, 4000)

    /** How many of [glassMl] are still needed; 0 once the goal is met. */
    fun glassesLeft(totalMl: Int, goalMl: Int, glassMl: Int): Int =
        if (totalMl >= goalMl || glassMl <= 0) 0 else (goalMl - totalMl + glassMl - 1) / glassMl
}

/** The daily goal, the three quick-add sizes and reminder times, kept on this phone. */
class WaterSettings(context: Context) {
    private val prefs = context.getSharedPreferences("water", Context.MODE_PRIVATE)

    var goalMl: Int
        get() = prefs.getInt("goal", Hydration.DEFAULT_GOAL_ML)
        set(v) = prefs.edit().putInt("goal", v).apply()

    /** Glass, bottle and cup sizes for the quick-add buttons. */
    var sizes: List<Int>
        get() = prefs.getString("sizes", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 }
            ?: listOf(250, 500, 150)
        set(v) = prefs.edit().putString("sizes", v.joinToString(",")).apply()

    val glassMl get() = sizes.first()

    var remindersOn: Boolean
        get() = prefs.getBoolean("remind", false)
        set(v) = prefs.edit().putBoolean("remind", v).apply()

    /** Minutes between reminders. */
    var intervalMinutes: Int
        get() = prefs.getInt("interval", 120)
        set(v) = prefs.edit().putInt("interval", v).apply()

    /** Reminders only come between these hours (24-hour clock, end exclusive). */
    var startHour: Int
        get() = prefs.getInt("start", 8)
        set(v) = prefs.edit().putInt("start", v).apply()

    var endHour: Int
        get() = prefs.getInt("end", 22)
        set(v) = prefs.edit().putInt("end", v).apply()
}

/** "1,250 ml" */
fun formatMl(ml: Int): String = "%,d ml".format(ml)
