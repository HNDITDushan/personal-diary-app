package com.personal.mydiary.data

import com.personal.mydiary.util.durationLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityTimeTest {
    @Test
    fun durationBetweenTwoTimes() {
        assertEquals("2 h 30 min", durationLabel("09:00", "11:30"))
        assertEquals("45 min", durationLabel("18:15", "19:00"))
        assertEquals("1 h", durationLabel("07:00", "08:00"))
    }

    @Test
    fun anEndBeforeTheStartRunsPastMidnight() {
        assertEquals("3 h", durationLabel("22:00", "01:00"))
    }

    @Test
    fun noDurationWithoutBothTimes() {
        assertEquals("", durationLabel("09:00", ""))
        assertEquals("", durationLabel("", "10:00"))
        assertEquals("", durationLabel("09:00", "09:00"))
    }
}
