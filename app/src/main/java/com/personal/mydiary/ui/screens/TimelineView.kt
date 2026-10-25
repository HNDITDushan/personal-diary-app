package com.personal.mydiary.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.mydiary.data.Appointment
import com.personal.mydiary.data.DiaryEntry
import com.personal.mydiary.data.EntryType
import com.personal.mydiary.data.Mood
import com.personal.mydiary.data.Place
import com.personal.mydiary.data.TaskItem
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.AppointmentEditor
import com.personal.mydiary.ui.EmptyState
import com.personal.mydiary.ui.EntryEditor
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.ui.MoodDialog
import com.personal.mydiary.ui.PlaceEditor
import com.personal.mydiary.ui.TaskEditor
import com.personal.mydiary.ui.theme.color
import com.personal.mydiary.ui.theme.emoji
import com.personal.mydiary.ui.theme.label
import com.personal.mydiary.util.dayFormat
import com.personal.mydiary.util.epochDayToDate
import com.personal.mydiary.util.millisToDateTime
import com.personal.mydiary.util.relativeDay
import com.personal.mydiary.util.storedToTime
import com.personal.mydiary.util.timeFormat
import com.personal.mydiary.util.today
import java.time.LocalDate
import java.time.LocalTime

private enum class TimelineFilter(val label: String, val itemLabel: String) {
    ALL("All", ""), ACTIVITY("Activities", "Activity"), MEMORY("Memories", "Memory"),
    GOOD_DEED("Good deeds", "Good deed"), PLACE("Places", "Place visited"),
    TASK("Tasks done", "Task completed"), APPOINTMENT("Appointments", "Appointment"),
}

/** One row on the timeline; [source] is the record opened for editing on tap. */
private data class TimelineItem(
    val key: String,
    val filter: TimelineFilter,
    val date: LocalDate,
    val time: LocalTime?,
    val emoji: String,
    val color: Color,
    val title: String,
    val subtitle: String,
    val source: Any,
)

private val activityColor = Color(0xFF5C6BC0)
private val memoryColor = Color(0xFFAB47BC)
private val deedColor = Color(0xFFE0457B)
private val placeColor = Color(0xFF26A69A)
private val taskColor = Color(0xFF43A047)
private val appointmentColor = Color(0xFFFFA726)

private fun DiaryEntry.toItem(): TimelineItem {
    val (filter, color) = when (type) {
        EntryType.ACTIVITY -> TimelineFilter.ACTIVITY to activityColor
        EntryType.MEMORY -> TimelineFilter.MEMORY to memoryColor
        EntryType.GOOD_DEED -> TimelineFilter.GOOD_DEED to deedColor
    }
    val who = people.takeIf { it.isNotBlank() }?.let { if (type == EntryType.GOOD_DEED) "Helped: $it" else "👥 $it" }
    return TimelineItem(
        "e$id", filter, date.epochDayToDate(), time.storedToTime(), entryEmoji(type), color, title,
        listOfNotNull(details.takeIf { it.isNotBlank() }, who).joinToString("\n"), this,
    )
}

private fun Place.toItem() = TimelineItem(
    "p$id", TimelineFilter.PLACE, date.epochDayToDate(), null, "📍", placeColor, name,
    listOf(address, notes).filter { it.isNotBlank() }.joinToString("\n"), this,
)

private fun TaskItem.toItem(): TimelineItem {
    val at = completedAt!!.millisToDateTime()
    return TimelineItem("t$id", TimelineFilter.TASK, at.toLocalDate(), at.toLocalTime(), "✅", taskColor, title, notes, this)
}

private fun Appointment.toItem(): TimelineItem {
    val at = startAt.millisToDateTime()
    return TimelineItem(
        "a$id", TimelineFilter.APPOINTMENT, at.toLocalDate(), at.toLocalTime(), "📅", appointmentColor, title,
        listOf(location, notes).filter { it.isNotBlank() }.joinToString("\n"), this,
    )
}

@Composable
fun TimelineView() {
    val db = diaryApp().db
    val entries by remember { db.entries().observeAll() }.collectAsState(initial = emptyList())
    val places by remember { db.places().observeAll() }.collectAsState(initial = emptyList())
    val tasks by remember { db.tasks().observeAll() }.collectAsState(initial = emptyList())
    val appointments by remember { db.appointments().observeAll() }.collectAsState(initial = emptyList())
    val moods by remember { db.moods().observeAll() }.collectAsState(initial = emptyList())

    var filter by rememberSaveable { mutableStateOf(TimelineFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Any?>(null) }
    var rateDay by remember { mutableStateOf<LocalDate?>(null) }

    val allItems = remember(entries, places, tasks, appointments) {
        entries.map { it.toItem() } +
            places.map { it.toItem() } +
            tasks.filter { it.done && it.completedAt != null }.map { it.toItem() } +
            appointments.map { it.toItem() }
    }
    val moodByDay = remember(moods) { moods.associate { it.date.epochDayToDate() to it.mood } }

    val days: List<Pair<LocalDate, List<TimelineItem>>> = remember(allItems, moodByDay, filter, query) {
        val q = query.trim()
        val shown = allItems.filter { item ->
            (filter == TimelineFilter.ALL || item.filter == filter) &&
                (q.isEmpty() || item.title.contains(q, ignoreCase = true) || item.subtitle.contains(q, ignoreCase = true))
        }
        val byDay = shown.groupBy { it.date }.toMutableMap()
        // A rated day with nothing else recorded still gets its own entry on the full timeline.
        if (filter == TimelineFilter.ALL && q.isEmpty()) moodByDay.keys.forEach { byDay.getOrPut(it) { emptyList() } }
        byDay.entries
            .sortedByDescending { it.key }
            .map { (day, items) -> day to items.sortedWith(compareByDescending<TimelineItem> { it.time ?: LocalTime.MIN }) }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search your diary") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(TimelineFilter.entries) { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
            }
        }

        if (days.isEmpty()) {
            EmptyState(
                Icons.Outlined.Timeline,
                if (query.isBlank() && filter == TimelineFilter.ALL) "Your timeline fills up as you record activities, memories, good deeds, places and tasks."
                else "Nothing matches.",
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            ) {
                days.forEach { (day, items) ->
                    item(key = "d$day") { DayHeader(day, moodByDay[day]) { rateDay = day } }
                    items(items, key = { it.key }) { item ->
                        TimelineRow(item, isLast = item == items.last()) { editing = item.source }
                    }
                }
            }
        }
    }

    when (val e = editing) {
        is DiaryEntry -> EntryEditor(e.type, e, { editing = null })
        is Place -> PlaceEditor(e) { editing = null }
        is TaskItem -> TaskEditor(e) { editing = null }
        is Appointment -> AppointmentEditor(e) { editing = null }
    }
    rateDay?.let { MoodDialog(it) { rateDay = null } }
}

@Composable
private fun DayHeader(day: LocalDate, mood: Mood?, onRate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !day.isAfter(today()), onClick = onRate).padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(day.format(dayFormat), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val relative = relativeDay(day)
            if (relative != day.format(dayFormat)) {
                Text(relative, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (mood != null) {
            Box(
                Modifier.background(mood.color.copy(alpha = 0.18f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("${mood.emoji} ${mood.label}", style = MaterialTheme.typography.labelLarge, color = mood.color) }
        } else if (!day.isAfter(today())) {
            Text("Rate day", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Time on the left, a coloured dot on a vertical rail, then the content card. */
@Composable
private fun TimelineRow(item: TimelineItem, isLast: Boolean, onClick: () -> Unit) {
    val rail = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(
            item.time?.format(timeFormat).orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp).padding(top = 14.dp),
        )
        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier.width(2.dp)
                    .fillMaxHeight(if (isLast) 0.3f else 1f)
                    .background(rail),
            )
            Box(
                Modifier.padding(top = 14.dp).size(12.dp).background(item.color, CircleShape),
            )
        }
        InfoCard(Modifier.weight(1f).padding(start = 4.dp, bottom = 8.dp).clickable(onClick = onClick)) {
            Text("${item.emoji} ${item.title}", style = MaterialTheme.typography.titleSmall)
            Text(item.filter.itemLabel, style = MaterialTheme.typography.labelSmall, color = item.color)
            if (item.subtitle.isNotBlank()) {
                Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
