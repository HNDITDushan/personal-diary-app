package com.personal.mydiary.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HydrationTest {
    private fun log(day: Long, ml: Int) = WaterLog(date = day, amountMl = ml)

    @Test
    fun totalsAddUpEachDay() {
        val totals = Hydration.dailyTotals(listOf(log(10, 250), log(10, 500), log(11, 150)))
        assertEquals(mapOf(10L to 750, 11L to 150), totals)
    }

    @Test
    fun streakCountsDaysInARowThatReachedTheGoal() {
        val totals = mapOf(7L to 2000, 8L to 2100, 9L to 2500, 10L to 2000)
        assertEquals(4, Hydration.streak(totals, 2000, today = 10))
    }

    /** In the morning before the first glass, yesterday's run still counts. */
    @Test
    fun todayDoesNotBreakTheStreakYet() {
        val totals = mapOf(8L to 2000, 9L to 2000, 10L to 500)
        assertEquals(2, Hydration.streak(totals, 2000, today = 10))
    }

    @Test
    fun aMissedDayEndsTheStreak() {
        val totals = mapOf(7L to 2000, 8L to 1200, 9L to 2000)
        assertEquals(1, Hydration.streak(totals, 2000, today = 10))
        assertEquals(0, Hydration.streak(mapOf(8L to 2000), 2000, today = 10))
    }

    @Test
    fun suggestedGoalFollowsWeightWithinLimits() {
        assertEquals(2450, Hydration.suggestedGoalMl(70.0))
        assertEquals(1500, Hydration.suggestedGoalMl(30.0))
        assertEquals(4000, Hydration.suggestedGoalMl(150.0))
    }

    @Test
    fun glassesLeftRoundsUp() {
        assertEquals(4, Hydration.glassesLeft(1100, 2000, 250))
        assertEquals(0, Hydration.glassesLeft(2000, 2000, 250))
    }
}
