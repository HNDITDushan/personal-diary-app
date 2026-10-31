package com.personal.mydiary.ui.screens

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.personal.mydiary.data.Appointment
import com.personal.mydiary.data.ContactsSync
import com.personal.mydiary.data.Occasion
import com.personal.mydiary.data.OccasionType
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.AppointmentEditor
import com.personal.mydiary.ui.DiaryScaffold
import com.personal.mydiary.ui.EmptyState
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.ui.OccasionEditor
import com.personal.mydiary.ui.SectionTitle
import com.personal.mydiary.ui.label
import com.personal.mydiary.util.dayFormat
import com.personal.mydiary.util.millisToDateTime
import com.personal.mydiary.util.nextOccurrence
import com.personal.mydiary.util.relativeDay
import com.personal.mydiary.util.shortDayFormat
import com.personal.mydiary.util.timeFormat
import com.personal.mydiary.util.today
import kotlinx.coroutines.launch

@Composable
fun EventsScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var addAppointment by remember { mutableStateOf(false) }
    var addOccasion by remember { mutableStateOf(false) }

    DiaryScaffold(
        title = "Events",
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = { if (tab == 0) addAppointment = true else addOccasion = true }) {
                Icon(Icons.Filled.Add, "Add")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Appointments") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Special days") })
            }
            if (tab == 0) AppointmentsList() else OccasionsList()
        }
    }

    if (addAppointment) AppointmentEditor(null) { addAppointment = false }
    if (addOccasion) OccasionEditor(null) { addOccasion = false }
}

@Composable
private fun AppointmentsList() {
    val db = diaryApp().db
    val now = remember { System.currentTimeMillis() }
    val upcoming by remember { db.appointments().observeFrom(now) }.collectAsState(initial = emptyList())
    val past by remember { db.appointments().observeBefore(now) }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Appointment?>(null) }

    if (upcoming.isEmpty() && past.isEmpty()) {
        EmptyState(Icons.Outlined.EventNote, "No appointments yet. Tap + to add one, and you'll get a reminder.")
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle("Upcoming") }
            if (upcoming.isEmpty()) item { Text("Nothing scheduled.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(upcoming, key = { it.id }) { a -> AppointmentRow(a) { editing = a } }
            if (past.isNotEmpty()) {
                item { SectionTitle("Past") }
                items(past, key = { "p" + it.id }) { a -> AppointmentRow(a) { editing = a } }
            }
        }
    }
    editing?.let { AppointmentEditor(it) { editing = null } }
}

@Composable
private fun AppointmentRow(a: Appointment, onClick: () -> Unit) {
    val at = a.startAt.millisToDateTime()
    CompactRow(
        emoji = "📅",
        headline = a.title,
        supporting = listOf(at.toLocalDate().format(dayFormat), a.location).filter { it.isNotBlank() }.joinToString(" · "),
        trailing = at.format(timeFormat),
        onClick = onClick,
    )
}

@Composable
private fun OccasionsList() {
    val app = diaryApp()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val occasions by remember { app.db.occasions().observeAll() }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Occasion?>(null) }
    var syncing by remember { mutableStateOf(false) }
    val day = remember { today() }
    val sorted = remember(occasions) {
        occasions.map { it to nextOccurrence(it.month, it.day, day) }.sortedBy { it.second }
    }

    fun sync() {
        syncing = true
        scope.launch {
            val result = runCatching { ContactsSync.sync(context, app.db) }
            syncing = false
            val message = result.fold(
                { r -> if (r.added + r.updated == 0) "Already up to date" else "Added ${r.added}, updated ${r.updated} from contacts" },
                { "Sync failed: ${it.message}" },
            )
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) sync() else Toast.makeText(context, "Contacts permission is needed to import birthdays", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            InfoCard {
                Text("Birthdays from Google", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Imports birthdays and anniversaries saved in your Google contacts on this phone. Run it again any time to pick up changes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(
                    onClick = { permission.launch(Manifest.permission.READ_CONTACTS) },
                    enabled = !syncing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (syncing) "Syncing…" else "Sync from contacts")
                }
            }
        }
        if (sorted.isEmpty()) {
            item { EmptyState(Icons.Outlined.Cake, "Add birthdays, anniversaries and special days with +, or sync them from your contacts.") }
        }
        items(sorted, key = { it.first.id }) { (o, next) ->
            val years = o.year?.let { next.year - it }?.takeIf { it > 0 }
            val detail = buildString {
                append(o.type.label).append(" · ").append(next.format(shortDayFormat))
                if (years != null) {
                    append(" · ")
                    append(if (o.type == OccasionType.BIRTHDAY) "turns $years" else "$years years")
                }
                if (o.contactKey != null) append(" · from contacts")
            }
            CompactRow(
                emoji = occasionEmoji(o.type),
                headline = o.name,
                supporting = detail,
                trailing = relativeDay(next),
                highlight = next == day,
                onClick = { editing = o },
            )
        }
    }
    editing?.let { OccasionEditor(it) { editing = null } }
}
