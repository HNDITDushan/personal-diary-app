package com.personal.mydiary.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.mydiary.data.DayMood
import com.personal.mydiary.data.Mood
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.theme.color
import com.personal.mydiary.ui.theme.emoji
import com.personal.mydiary.ui.theme.label
import com.personal.mydiary.util.dayFormat
import com.personal.mydiary.util.timeFormat
import com.personal.mydiary.util.today
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Screen frame used inside the app's bottom-bar scaffold, which already applies system insets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
            )
        },
        floatingActionButton = floatingActionButton,
        contentWindowInsets = WindowInsets(0),
        content = content,
    )
}

@Composable
fun EditorDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean = true,
    onDelete: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        },
        confirmButton = { TextButton(onClick = onSave, enabled = saveEnabled) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun TextInput(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate?, onChange: (LocalDate?) -> Unit, clearable: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(date?.format(dayFormat) ?: label)
        }
        if (clearable && date != null) TextButton(onClick = { onChange(null) }) { Text("Clear") }
    }
    if (open) {
        // The Material date picker works in UTC midnight millis.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: today()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, time: LocalTime?, onChange: (LocalTime?) -> Unit, clearable: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(time?.format(timeFormat) ?: label)
        }
        if (clearable && time != null) TextButton(onClick = { onChange(null) }) { Text("Clear") }
    }
    if (open) {
        val initial = time ?: LocalTime.now()
        val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = { onChange(LocalTime.of(state.hour, state.minute)); open = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun EmptyState(icon: ImageVector, text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.padding(6.dp))
            Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Three smiley buttons; tapping the selected one again clears the rating. */
@Composable
fun MoodPicker(selected: Mood?, onSelect: (Mood?) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Mood.entries.forEach { mood ->
            val isSelected = mood == selected
            Surface(
                onClick = { onSelect(if (isSelected) null else mood) },
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) mood.color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) mood.color else MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(mood.emoji, fontSize = 36.sp)
                    Text(
                        mood.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) mood.color else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Rate any day (today or earlier); picking a smiley saves and closes, tapping the current one clears it. */
@Composable
fun MoodDialog(date: LocalDate, onDismiss: () -> Unit) {
    val app = diaryApp()
    val day = date.toEpochDay()
    val current by remember(day) { app.db.moods().observe(day) }.collectAsState(initial = null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (date == today()) "How was today?" else "How was ${date.format(dayFormat)}?") },
        text = {
            MoodPicker(current?.mood) { selected ->
                app.launch {
                    if (selected == null) moods().deleteOn(day)
                    else moods().upsert(DayMood(day, selected, current?.note.orEmpty()))
                }
                onDismiss()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Date picker that only allows today and earlier. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PastDatePickerDialog(onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val todayUtc = today().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = todayUtc - 86_400_000L,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
            override fun isSelectableYear(year: Int) = year <= today().year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("Next") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}
