package com.personal.mydiary.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.mydiary.data.DiaryEntry
import com.personal.mydiary.data.EntryType
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.DiaryScaffold
import com.personal.mydiary.ui.EmptyState
import com.personal.mydiary.ui.EntryEditor
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.ui.SectionTitle
import com.personal.mydiary.ui.label
import com.personal.mydiary.util.dayFormat
import com.personal.mydiary.util.epochDayToDate
import com.personal.mydiary.util.storedToTime
import com.personal.mydiary.util.timeFormat
import com.personal.mydiary.util.today
import java.time.YearMonth

private val diaryTabs = listOf(
    EntryType.ACTIVITY to "Activities",
    EntryType.MEMORY to "Memories",
    EntryType.GOOD_DEED to "Good deeds",
)

@Composable
fun DiaryScreen() {
    val app = diaryApp()
    // Tab 0 is the timeline of everything; the others list one kind of entry.
    var tab by rememberSaveable { mutableStateOf(0) }
    val type = diaryTabs[(tab - 1).coerceAtLeast(0)].first
    val entries by remember(type) { app.db.entries().observeByType(type.name) }.collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DiaryEntry?>(null) }

    DiaryScaffold(
        title = "Diary",
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add ${type.label.lowercase()}") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                (listOf("Timeline") + diaryTabs.map { it.second }).forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            if (tab == 0) {
                TimelineView()
            } else if (entries.isEmpty()) {
                val (icon, text) = when (type) {
                    EntryType.ACTIVITY -> Icons.Outlined.DirectionsRun to "Record what you did today."
                    EntryType.MEMORY -> Icons.Outlined.AutoAwesome to "Save moments you want to remember."
                    EntryType.GOOD_DEED -> Icons.Outlined.Favorite to "Note the ways you helped others. At month end you'll see them all."
                }
                EmptyState(icon, text)
            } else {
                val grouped = entries.groupBy { it.date }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (type == EntryType.GOOD_DEED) {
                        item { GoodDeedSummary(entries) }
                    }
                    grouped.forEach { (date, dayEntries) ->
                        item(key = "h$date") { SectionTitle(date.epochDayToDate().format(dayFormat)) }
                        items(dayEntries, key = { it.id }) { e ->
                            CompactRow(
                                emoji = entryEmoji(e.type),
                                headline = e.title,
                                supporting = listOf(e.details, e.people.takeIf { it.isNotBlank() }?.let { "👥 $it" }.orEmpty())
                                    .filter { it.isNotBlank() }.joinToString("\n"),
                                trailing = e.time.storedToTime()?.format(timeFormat).orEmpty(),
                                onClick = { editing = e },
                            )
                        }
                    }
                }
            }
        }
    }

    if (adding) EntryEditor(type, null, { adding = false })
    editing?.let { EntryEditor(it.type, it, { editing = null }) }
}

@Composable
private fun GoodDeedSummary(entries: List<DiaryEntry>) {
    val month = YearMonth.from(today())
    val thisMonth = entries.count { YearMonth.from(it.date.epochDayToDate()) == month }
    InfoCard {
        Text("💛 $thisMonth good deed${if (thisMonth == 1) "" else "s"} this month", style = MaterialTheme.typography.titleMedium)
        Text(
            "${entries.size} in total. Every bit of help counts!",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
