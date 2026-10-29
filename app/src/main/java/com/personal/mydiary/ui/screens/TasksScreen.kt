package com.personal.mydiary.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.personal.mydiary.data.save
import com.personal.mydiary.data.Priority
import com.personal.mydiary.data.TaskItem
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.DiaryScaffold
import com.personal.mydiary.ui.EmptyState
import com.personal.mydiary.ui.TaskEditor
import com.personal.mydiary.util.epochDayToDate
import com.personal.mydiary.util.relativeDay
import com.personal.mydiary.util.today

private enum class TaskFilter(val label: String) { OPEN("To do"), DONE("Done"), ALL("All") }

@Composable
fun TasksScreen() {
    val app = diaryApp()
    val all by remember { app.db.tasks().observeAll() }.collectAsState(initial = emptyList())
    var filter by rememberSaveable { mutableStateOf(TaskFilter.OPEN) }
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    val todayNum = today().toEpochDay()

    val shown = when (filter) {
        TaskFilter.OPEN -> all.filter { !it.done }
        TaskFilter.DONE -> all.filter { it.done }.sortedByDescending { it.completedAt }
        TaskFilter.ALL -> all
    }

    DiaryScaffold(
        title = "Tasks",
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add task") }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskFilter.entries.forEach { f ->
                    val count = when (f) {
                        TaskFilter.OPEN -> all.count { !it.done }
                        TaskFilter.DONE -> all.count { it.done }
                        TaskFilter.ALL -> all.size
                    }
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} ($count)") })
                }
            }
            if (shown.isEmpty()) {
                EmptyState(Icons.Outlined.TaskAlt, if (filter == TaskFilter.DONE) "No finished tasks yet." else "No tasks. Tap + to add one.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(shown, key = { it.id }) { task ->
                        val overdue = !task.done && task.dueDate != null && task.dueDate < todayNum
                        ListItem(
                            modifier = Modifier.clickable { editing = task },
                            leadingContent = {
                                Checkbox(checked = task.done, onCheckedChange = { checked ->
                                    app.launch {
                                        tasks().save(task.copy(done = checked, completedAt = if (checked) System.currentTimeMillis() else null))
                                    }
                                })
                            },
                            headlineContent = {
                                Text(
                                    (if (task.priority == Priority.HIGH) "❗ " else "") + task.title,
                                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                                    color = if (task.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            supportingContent = {
                                val parts = listOfNotNull(
                                    task.dueDate?.let { (if (overdue) "Overdue · " else "Due ") + relativeDay(it.epochDayToDate()) },
                                    task.notes.takeIf { it.isNotBlank() },
                                )
                                if (parts.isNotEmpty()) {
                                    Text(
                                        parts.joinToString(" · "),
                                        maxLines = 2,
                                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (adding) TaskEditor(null) { adding = false }
    editing?.let { TaskEditor(it) { editing = null } }
}
