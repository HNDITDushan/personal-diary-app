package com.personal.mydiary.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.mydiary.data.save
import com.personal.mydiary.Routes
import com.personal.mydiary.data.DayMood
import com.personal.mydiary.data.DiaryEntry
import com.personal.mydiary.data.EntryType
import com.personal.mydiary.data.TaskItem
import com.personal.mydiary.data.TxnKind
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.EntryEditor
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.ui.MoodDialog
import com.personal.mydiary.ui.MoodPicker
import com.personal.mydiary.ui.PastDatePickerDialog
import com.personal.mydiary.ui.PlaceEditor
import com.personal.mydiary.ui.SectionTitle
import com.personal.mydiary.ui.TaskEditor
import com.personal.mydiary.ui.label
import com.personal.mydiary.util.daysFromToday
import com.personal.mydiary.util.endMillis
import com.personal.mydiary.util.epochDayToDate
import com.personal.mydiary.util.longDayFormat
import com.personal.mydiary.util.millisToDateTime
import com.personal.mydiary.util.nextOccurrence
import com.personal.mydiary.util.relativeDay
import com.personal.mydiary.util.startMillis
import com.personal.mydiary.util.storedToTime
import com.personal.mydiary.util.timeFormat
import com.personal.mydiary.util.today
import java.time.LocalDate
import java.time.LocalTime

private sealed interface QuickAdd {
    data object Task : QuickAdd
    data class Entry(val type: EntryType) : QuickAdd
    data object Place : QuickAdd
    data object Expense : QuickAdd
}

@Composable
fun TodayScreen(onOpen: (String) -> Unit) {
    val app = diaryApp()
    val db = app.db
    val day = remember { today() }
    val dayNum = day.toEpochDay()

    val mood by remember { db.moods().observe(dayNum) }.collectAsState(initial = null)
    val dueTasks by remember { db.tasks().observeDueBy(dayNum) }.collectAsState(initial = emptyList())
    val appointments by remember { db.appointments().observeBetween(day.startMillis(), day.endMillis()) }
        .collectAsState(initial = emptyList())
    val occasions by remember { db.occasions().observeAll() }.collectAsState(initial = emptyList())
    val entries by remember { db.entries().observeOnDate(dayNum) }.collectAsState(initial = emptyList())
    val recentMoods by remember { db.moods().observeBetween(dayNum - 7, dayNum - 1) }.collectAsState(initial = emptyList())
    val unrated = remember(recentMoods) {
        val rated = recentMoods.map { it.date }.toSet()
        (1L..7L).map { day.minusDays(it) }.filter { it.toEpochDay() !in rated }
    }
    var pickPastDay by remember { mutableStateOf(false) }
    var rateDay by remember { mutableStateOf<LocalDate?>(null) }

    val upcoming = remember(occasions) {
        occasions.map { it to nextOccurrence(it.month, it.day, day) }
            .filter { daysFromToday(it.second) <= 14 }
            .sortedBy { it.second }
    }

    var quickAdd by remember { mutableStateOf<QuickAdd?>(null) }
    var editingTask by remember { mutableStateOf<TaskItem?>(null) }
    var editingEntry by remember { mutableStateOf<DiaryEntry?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(greeting(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(day.format(longDayFormat), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            InfoCard {
                Text("How was your day?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(6.dp))
                MoodPicker(mood?.mood) { selected ->
                    app.launch {
                        if (selected == null) moods().deleteOn(dayNum) else moods().save(DayMood(dayNum, selected, mood?.note.orEmpty()))
                    }
                }
                if (unrated.isNotEmpty()) {
                    Spacer(Modifier.padding(4.dp))
                    Text("Not rated yet", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(unrated, key = { it.toEpochDay() }) { d ->
                            SuggestionChip(onClick = { rateDay = d }, label = { Text(d.format(chipDayFormat)) })
                        }
                    }
                }
                TextButton(onClick = { pickPastDay = true }) { Text("Rate another day…") }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { QuickChip("Task", Icons.Outlined.CheckCircle) { quickAdd = QuickAdd.Task } }
                item { QuickChip("Activity", Icons.Outlined.DirectionsRun) { quickAdd = QuickAdd.Entry(EntryType.ACTIVITY) } }
                item { QuickChip("Good deed", Icons.Outlined.Favorite) { quickAdd = QuickAdd.Entry(EntryType.GOOD_DEED) } }
                item { QuickChip("Memory", Icons.Outlined.AutoAwesome) { quickAdd = QuickAdd.Entry(EntryType.MEMORY) } }
                item { QuickChip("Place", Icons.Outlined.Place) { quickAdd = QuickAdd.Place } }
                item { QuickChip("Income / expense", Icons.Outlined.AccountBalanceWallet) { quickAdd = QuickAdd.Expense } }
            }
        }

        if (upcoming.isNotEmpty()) {
            item { SectionTitle("Coming up") }
            items(upcoming, key = { "o" + it.first.id }) { (o, date) ->
                val years = o.year?.let { date.year - it }
                val detail = o.type.label + (years?.let { y -> if (y > 0) " · $y" + if (o.type.name == "BIRTHDAY") " years old" else " years" else "" } ?: "")
                CompactRow(
                    headline = "${occasionEmoji(o.type)} ${o.name}",
                    supporting = detail,
                    trailing = relativeDay(date),
                    highlight = date == day,
                    onClick = { onOpen(Routes.EVENTS) },
                )
            }
        }

        item {
            Row {
                SectionTitle("Tasks", Modifier.weight(1f))
                TextButton(onClick = { onOpen(Routes.TASKS) }) { Text("All tasks") }
            }
        }
        if (dueTasks.isEmpty()) {
            item { Text("Nothing due. Enjoy your day!", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(dueTasks, key = { "t" + it.id }) { task ->
            val overdue = (task.dueDate ?: dayNum) < dayNum
            ListItem(
                modifier = Modifier.clickable { editingTask = task },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Checkbox(checked = false, onCheckedChange = {
                        app.launch { tasks().save(task.copy(done = true, completedAt = System.currentTimeMillis())) }
                    })
                },
                headlineContent = { Text(task.title) },
                supportingContent = if (overdue) {
                    { Text("Overdue · ${relativeDay(task.dueDate!!.epochDayToDate())}", color = MaterialTheme.colorScheme.error) }
                } else null,
            )
        }

        if (appointments.isNotEmpty()) {
            item { SectionTitle("Appointments today") }
            items(appointments, key = { "a" + it.id }) { a ->
                CompactRow(
                    headline = a.title,
                    supporting = a.location,
                    trailing = a.startAt.millisToDateTime().format(timeFormat),
                    onClick = { onOpen(Routes.EVENTS) },
                )
            }
        }

        item { SectionTitle("Today's diary") }
        if (entries.isEmpty()) {
            item {
                Text(
                    "Nothing written yet. Use the buttons above to record what you did.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(entries, key = { "e" + it.id }) { e ->
            CompactRow(
                headline = "${entryEmoji(e.type)} ${e.title}",
                supporting = listOf(e.details, e.people.takeIf { it.isNotBlank() }?.let { "👥 $it" }.orEmpty())
                    .filter { it.isNotBlank() }.joinToString("\n"),
                trailing = e.time.storedToTime()?.format(timeFormat).orEmpty(),
                onClick = { editingEntry = e },
            )
        }
    }

    when (val q = quickAdd) {
        QuickAdd.Task -> TaskEditor(null) { quickAdd = null }
        is QuickAdd.Entry -> EntryEditor(q.type, null, { quickAdd = null })
        QuickAdd.Place -> PlaceEditor(null) { quickAdd = null }
        QuickAdd.Expense -> TxnEditor(null, TxnKind.EXPENSE) { quickAdd = null }
        null -> {}
    }
    editingTask?.let { TaskEditor(it) { editingTask = null } }
    if (pickPastDay) PastDatePickerDialog(onPick = { pickPastDay = false; rateDay = it }, onDismiss = { pickPastDay = false })
    rateDay?.let { MoodDialog(it) { rateDay = null } }
    editingEntry?.let { EntryEditor(it.type, it, { editingEntry = null }) }
}

private val chipDayFormat = java.time.format.DateTimeFormatter.ofPattern("EEE d")

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun QuickChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text("+ $text") }, leadingIcon = { Icon(icon, null) })
}

@Composable
fun CompactRow(
    headline: String,
    supporting: String = "",
    trailing: String = "",
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    InfoCard(modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.titleSmall)
                if (supporting.isNotBlank()) {
                    Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trailing.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (highlight) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    fontWeight = if (highlight) FontWeight.Bold else null,
                )
            }
        }
    }
}

fun entryEmoji(type: EntryType) = when (type) {
    EntryType.ACTIVITY -> "📝"
    EntryType.MEMORY -> "✨"
    EntryType.GOOD_DEED -> "💛"
}

fun occasionEmoji(type: com.personal.mydiary.data.OccasionType) = when (type) {
    com.personal.mydiary.data.OccasionType.BIRTHDAY -> "🎂"
    com.personal.mydiary.data.OccasionType.ANNIVERSARY -> "💞"
    com.personal.mydiary.data.OccasionType.SPECIAL -> "⭐"
}
