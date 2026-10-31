package com.personal.mydiary.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.personal.mydiary.data.save
import com.personal.mydiary.data.Appointment
import com.personal.mydiary.data.DiaryEntry
import com.personal.mydiary.data.EntryType
import com.personal.mydiary.data.Occasion
import com.personal.mydiary.data.OccasionType
import com.personal.mydiary.data.Place
import com.personal.mydiary.data.Priority
import com.personal.mydiary.data.TaskItem
import com.personal.mydiary.diaryApp
import com.personal.mydiary.util.LocationHelper
import com.personal.mydiary.util.epochDayToDate
import com.personal.mydiary.util.millisToDateTime
import com.personal.mydiary.util.storedToTime
import com.personal.mydiary.util.toMillis
import com.personal.mydiary.util.toStored
import com.personal.mydiary.util.today
import com.personal.mydiary.work.AppointmentReminders
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

val EntryType.label: String
    get() = when (this) {
        EntryType.ACTIVITY -> "Activity"
        EntryType.MEMORY -> "Memory"
        EntryType.GOOD_DEED -> "Good deed"
    }

val OccasionType.label: String
    get() = when (this) {
        OccasionType.BIRTHDAY -> "Birthday"
        OccasionType.ANNIVERSARY -> "Anniversary"
        OccasionType.SPECIAL -> "Special day"
    }

val Priority.label: String
    get() = when (this) {
        Priority.LOW -> "Low"
        Priority.NORMAL -> "Normal"
        Priority.HIGH -> "High"
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
fun TaskEditor(initial: TaskItem?, onDismiss: () -> Unit) {
    val app = diaryApp()
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(initial?.notes.orEmpty()) }
    var due by remember { mutableStateOf(initial?.dueDate?.epochDayToDate() ?: if (initial == null) today() else null) }
    var priority by remember { mutableStateOf(initial?.priority ?: Priority.NORMAL) }

    EditorDialog(
        title = if (initial == null) "New task" else "Edit task",
        onDismiss = onDismiss,
        saveEnabled = title.isNotBlank(),
        onSave = {
            val item = (initial ?: TaskItem(title = "")).copy(
                title = title.trim(), notes = notes.trim(), dueDate = due?.toEpochDay(), priority = priority,
            )
            app.launch { tasks().save(item) }
            onDismiss()
        },
        onDelete = initial?.let { { app.launch { tasks().delete(it) }; onDismiss() } },
    ) {
        TextInput(title, { title = it }, "What needs doing?")
        val earlier by remember { app.db.tasks().observeTitles() }.collectAsState(initial = emptyList())
        SuggestionRow(title, earlier) { title = it }
        TextInput(notes, { notes = it }, "Notes", singleLine = false)
        DateField("No due date", due, { due = it }, clearable = true)
        Text("Priority", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(Priority.entries, priority, { it.label }) { priority = it }
    }
}

@Composable
fun EntryEditor(type: EntryType, initial: DiaryEntry?, onDismiss: () -> Unit, defaultDate: LocalDate = today()) {
    val app = diaryApp()
    var date by remember { mutableStateOf(initial?.date?.epochDayToDate() ?: defaultDate) }
    var time by remember { mutableStateOf(initial?.time?.storedToTime() ?: if (initial == null) LocalTime.now().withSecond(0).withNano(0) else null) }
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var details by rememberSaveable { mutableStateOf(initial?.details.orEmpty()) }
    var people by rememberSaveable { mutableStateOf(initial?.people.orEmpty()) }

    val (titleHint, peopleHint) = when (type) {
        EntryType.ACTIVITY -> "What did you do?" to "With whom (optional)"
        EntryType.MEMORY -> "Memory" to "People in this memory (optional)"
        EntryType.GOOD_DEED -> "How did you help?" to "Who did you help?"
    }

    EditorDialog(
        title = (if (initial == null) "New " else "Edit ") + type.label.lowercase(),
        onDismiss = onDismiss,
        saveEnabled = title.isNotBlank(),
        onSave = {
            val item = (initial ?: DiaryEntry(type = type, date = 0, title = "")).copy(
                date = date.toEpochDay(), time = time?.toStored().orEmpty(),
                title = title.trim(), details = details.trim(), people = people.trim(),
            )
            app.launch { entries().save(item) }
            onDismiss()
        },
        onDelete = initial?.let { { app.launch { entries().delete(it) }; onDismiss() } },
    ) {
        TextInput(title, { title = it }, titleHint)
        val earlier by remember(type) { app.db.entries().observeTitles(type.name) }.collectAsState(initial = emptyList())
        SuggestionRow(title, earlier) { title = it }
        TextInput(details, { details = it }, "Details", singleLine = false)
        TextInput(people, { people = it }, peopleHint)
        val earlierPeople by remember { app.db.entries().observePeople() }.collectAsState(initial = emptyList())
        SuggestionRow(people, earlierPeople) { people = it }
        DateField("Date", date, { if (it != null) date = it })
        TimeField("No time", time, { time = it }, clearable = true)
    }
}

@Composable
fun PlaceEditor(initial: Place?, onDismiss: () -> Unit) {
    val app = diaryApp()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(initial?.address.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(initial?.notes.orEmpty()) }
    var date by remember { mutableStateOf(initial?.date?.epochDayToDate() ?: today()) }
    var coords by remember { mutableStateOf(initial?.latitude?.let { lat -> initial.longitude?.let { lat to it } }) }
    var locating by remember { mutableStateOf(false) }

    fun locate() {
        locating = true
        scope.launch {
            val found = runCatching { LocationHelper.current(context) }.getOrNull()
            locating = false
            if (found == null) {
                Toast.makeText(context, "Couldn't get your location. Is location turned on?", Toast.LENGTH_LONG).show()
            } else {
                coords = found.latitude to found.longitude
                if (name.isBlank()) name = found.name
                if (found.address.isNotBlank()) address = found.address
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) locate()
        else Toast.makeText(context, "Location permission is needed for this", Toast.LENGTH_SHORT).show()
    }

    EditorDialog(
        title = if (initial == null) "Add place" else "Edit place",
        onDismiss = onDismiss,
        saveEnabled = name.isNotBlank(),
        onSave = {
            val item = (initial ?: Place(name = "", date = 0)).copy(
                name = name.trim(), address = address.trim(), notes = notes.trim(), date = date.toEpochDay(),
                latitude = coords?.first, longitude = coords?.second,
            )
            app.launch { places().save(item) }
            onDismiss()
        },
        onDelete = initial?.let { { app.launch { places().delete(it) }; onDismiss() } },
    ) {
        FilledTonalButton(
            onClick = {
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            enabled = !locating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (locating) "Finding you…" else "Use my current location")
        }
        coords?.let { (lat, lng) ->
            Text("📍 %.5f, %.5f".format(lat, lng), style = MaterialTheme.typography.bodySmall)
        }
        TextInput(name, { name = it }, "Place name")
        val earlier by remember { app.db.places().observeDistinct() }.collectAsState(initial = emptyList())
        SuggestionRow(name, earlier.map { it.name }) { picked ->
            name = picked
            earlier.firstOrNull { it.name == picked }?.let { place ->
                if (address.isBlank()) address = place.address
                if (coords == null) coords = place.latitude?.let { lat -> place.longitude?.let { lat to it } }
            }
        }
        TextInput(address, { address = it }, "Address")
        TextInput(notes, { notes = it }, "Notes", singleLine = false)
        DateField("Date visited", date, { if (it != null) date = it })
    }
}

private val reminderChoices = listOf(0, 10, 30, 60, 24 * 60)
private fun reminderLabel(minutes: Int) = when (minutes) {
    0 -> "None"
    60 -> "1 hour"
    24 * 60 -> "1 day"
    else -> "$minutes min"
}

@Composable
fun AppointmentEditor(initial: Appointment?, onDismiss: () -> Unit) {
    val app = diaryApp()
    val context = LocalContext.current
    val start = initial?.startAt?.millisToDateTime()
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var location by rememberSaveable { mutableStateOf(initial?.location.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(initial?.notes.orEmpty()) }
    var date by remember { mutableStateOf(start?.toLocalDate() ?: today()) }
    var time by remember { mutableStateOf(start?.toLocalTime() ?: LocalTime.of(9, 0)) }
    var remind by remember { mutableStateOf(initial?.remindMinutesBefore ?: 30) }

    EditorDialog(
        title = if (initial == null) "New appointment" else "Edit appointment",
        onDismiss = onDismiss,
        saveEnabled = title.isNotBlank(),
        onSave = {
            val item = (initial ?: Appointment(title = "", startAt = 0)).copy(
                title = title.trim(), location = location.trim(), notes = notes.trim(),
                startAt = date.atTime(time).toMillis(), remindMinutesBefore = remind,
            )
            app.launch {
                val newId = appointments().save(item)
                AppointmentReminders.schedule(context.applicationContext, if (item.id == 0L) item.copy(id = newId) else item)
            }
            onDismiss()
        },
        onDelete = initial?.let {
            {
                app.launch { appointments().delete(it) }
                AppointmentReminders.cancel(context.applicationContext, it.id)
                onDismiss()
            }
        },
    ) {
        TextInput(title, { title = it }, "Appointment")
        DateField("Date", date, { if (it != null) date = it })
        TimeField("Time", time, { if (it != null) time = it })
        TextInput(location, { location = it }, "Where")
        TextInput(notes, { notes = it }, "Notes", singleLine = false)
        Text("Remind me before", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(reminderChoices, remind, ::reminderLabel) { remind = it }
    }
}

@Composable
fun OccasionEditor(initial: Occasion?, onDismiss: () -> Unit) {
    val app = diaryApp()
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(initial?.notes.orEmpty()) }
    var type by remember { mutableStateOf(initial?.type ?: OccasionType.BIRTHDAY) }
    var knowYear by remember { mutableStateOf(initial == null || initial.year != null) }
    var date by remember {
        // 2000 is a leap year, so Feb 29 is valid when the year is unknown.
        mutableStateOf(initial?.let { runCatching { LocalDate.of(it.year ?: 2000, it.month, it.day) }.getOrNull() } ?: today())
    }

    EditorDialog(
        title = if (initial == null) "New special day" else "Edit special day",
        onDismiss = onDismiss,
        saveEnabled = name.isNotBlank(),
        onSave = {
            val item = (initial ?: Occasion(name = "", type = type, month = 1, day = 1)).copy(
                name = name.trim(), type = type, notes = notes.trim(),
                month = date.monthValue, day = date.dayOfMonth, year = if (knowYear) date.year else null,
            )
            app.launch { occasions().save(item) }
            onDismiss()
        },
        onDelete = initial?.let { { app.launch { occasions().delete(it) }; onDismiss() } },
    ) {
        ChoiceChips(OccasionType.entries, type, { it.label }) { type = it }
        TextInput(
            name, { name = it },
            when (type) {
                OccasionType.BIRTHDAY -> "Whose birthday?"
                OccasionType.ANNIVERSARY -> "Anniversary of…"
                OccasionType.SPECIAL -> "What's special?"
            },
        )
        DateField("Date", date, { if (it != null) date = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = knowYear, onCheckedChange = { knowYear = it })
            Text("Year is known (shows age / years)")
        }
        TextInput(notes, { notes = it }, "Notes (gift ideas, etc.)", singleLine = false)
    }
}
