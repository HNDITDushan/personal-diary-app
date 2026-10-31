package com.personal.mydiary.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.mydiary.data.DayMood
import com.personal.mydiary.data.EntryType
import com.personal.mydiary.data.Mood
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.DiaryScaffold
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.ui.MoodDialog
import com.personal.mydiary.ui.SectionTitle
import com.personal.mydiary.ui.theme.color
import com.personal.mydiary.ui.theme.emoji
import com.personal.mydiary.ui.theme.label
import com.personal.mydiary.util.endMillis
import com.personal.mydiary.util.epochDayToDate
import com.personal.mydiary.util.firstEpochDay
import com.personal.mydiary.util.lastEpochDay
import com.personal.mydiary.util.monthFormat
import com.personal.mydiary.util.shortDayFormat
import com.personal.mydiary.util.startMillis
import com.personal.mydiary.util.today
import kotlin.math.roundToInt
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Happiness score 0–100: the average of each rated day's [Mood.points]. */
private fun happinessScore(moods: List<DayMood>): Int? {
    if (moods.isEmpty()) return null
    return (moods.sumOf { it.mood.points }.toDouble() / moods.size).roundToInt()
}

private fun verdict(score: Int): Pair<Mood, String> = when {
    score >= 70 -> Mood.GOOD to "You were happy this month"
    score >= 45 -> Mood.FAIR to "A mixed month — ups and downs"
    else -> Mood.BAD to "You were mostly unhappy this month"
}

@Composable
fun ReportScreen(onBack: () -> Unit) {
    val db = diaryApp().db
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today()).toString()) }
    val month = YearMonth.parse(monthText)
    val from = month.firstEpochDay()
    val to = month.lastEpochDay()

    val moods by remember(month) { db.moods().observeBetween(from, to) }.collectAsState(initial = emptyList())
    val deeds by remember(month) { db.entries().observeTypeBetween(EntryType.GOOD_DEED.name, from, to) }.collectAsState(initial = emptyList())
    val activities by remember(month) { db.entries().observeTypeBetween(EntryType.ACTIVITY.name, from, to) }.collectAsState(initial = emptyList())
    val memories by remember(month) { db.entries().observeTypeBetween(EntryType.MEMORY.name, from, to) }.collectAsState(initial = emptyList())
    val places by remember(month) { db.places().observeBetween(from, to) }.collectAsState(initial = emptyList())
    val tasksDone by remember(month) {
        db.tasks().observeCompletedBetween(month.atDay(1).startMillis(), month.atEndOfMonth().endMillis())
    }.collectAsState(initial = 0)

    var rateDay by remember { mutableStateOf<java.time.LocalDate?>(null) }
    val score = happinessScore(moods)
    val isFutureMonth = month > YearMonth.from(today())

    DiaryScaffold(title = "Monthly report", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { monthText = month.minusMonths(1).toString() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month")
                    }
                    Text(
                        month.format(monthFormat),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { monthText = month.plusMonths(1).toString() }, enabled = !isFutureMonth) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
                    }
                }
            }

            item {
                InfoCard {
                    if (score == null) {
                        Text("No days rated yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Rate each day with a smiley on the Today screen to see how happy your month was.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val (mood, text) = verdict(score)
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(mood.emoji, fontSize = 64.sp)
                            Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                            Text("Happiness score $score / 100", color = mood.color, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { score / 100f },
                                color = mood.color,
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            )
                            Spacer(Modifier.height(12.dp))
                            Mood.entries.chunked(3).forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    row.forEach { m ->
                                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(m.emoji, fontSize = 24.sp)
                                            Text("${moods.count { it.mood == m }} ${m.label.lowercase()}")
                                        }
                                    }
                                }
                            }
                            Text(
                                "${moods.size} of ${month.lengthOfMonth()} days rated",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                SectionTitle("Mood calendar")
                MoodCalendar(month, moods.associate { it.date to it.mood }) { rateDay = it }
                Text(
                    "Tap a day to rate it or change its rating.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item {
                SectionTitle("This month in numbers")
                InfoCard {
                    StatLine("💛", "Good deeds", deeds.size)
                    StatLine("✅", "Tasks completed", tasksDone)
                    StatLine("📝", "Activities recorded", activities.size)
                    StatLine("✨", "Memories saved", memories.size)
                    StatLine("📍", "Places visited", places.size)
                }
            }

            item {
                SectionTitle("How I helped others")
                if (deeds.isEmpty()) {
                    Text("No good deeds recorded this month.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(deeds, key = { it.id }) { d ->
                CompactRow(
                    emoji = "💛",
                    headline = d.title,
                    supporting = listOf(d.people.takeIf { it.isNotBlank() }?.let { "Helped: $it" }.orEmpty(), d.details)
                        .filter { it.isNotBlank() }.joinToString("\n"),
                    trailing = d.date.epochDayToDate().format(shortDayFormat),
                )
            }
        }
    }
    rateDay?.let { MoodDialog(it) { rateDay = null } }
}

@Composable
private fun StatLine(emoji: String, label: String, value: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("$emoji  $label", Modifier.weight(1f))
        Text("$value", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MoodCalendar(month: YearMonth, moods: Map<Long, Mood>, onDayClick: (java.time.LocalDate) -> Unit) {
    val weekDays = DayOfWeek.entries
    val leading = month.atDay(1).dayOfWeek.value - 1 // Monday first
    val cells = List(leading) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val todayDate = today()
    InfoCard {
        Row(Modifier.fillMaxWidth()) {
            weekDays.forEach {
                Text(
                    it.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { i ->
                    val date = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            val mood = moods[date.toEpochDay()]
                            Box(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                                    .background(mood?.color?.copy(alpha = 0.25f) ?: MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable(enabled = !date.isAfter(todayDate)) { onDayClick(date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (mood != null) Text(mood.emoji, fontSize = 18.sp)
                                else Text(
                                    "${date.dayOfMonth}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (date == todayDate) FontWeight.Bold else null,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
