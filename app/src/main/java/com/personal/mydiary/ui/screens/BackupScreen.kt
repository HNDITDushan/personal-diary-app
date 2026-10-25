package com.personal.mydiary.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.personal.mydiary.data.Backup
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.DiaryScaffold
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.util.dayFormat
import com.personal.mydiary.util.millisToDateTime
import com.personal.mydiary.util.timeFormat
import kotlinx.coroutines.launch

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val app = diaryApp()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var lastBackup by remember { mutableStateOf(Backup.lastBackupMillis(context)) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }

    fun run(action: suspend () -> String) {
        busy = true
        status = null
        scope.launch {
            status = runCatching { action() }.getOrElse { "⚠️ ${it.message ?: "Something went wrong"}" }
            busy = false
        }
    }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run {
            val n = Backup.export(context, app.db, uri)
            lastBackup = Backup.lastBackupMillis(context)
            "✅ Backed up $n items."
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestore = uri
    }

    DiaryScaffold(title = "Backup & restore", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard {
                Text("Back up your diary", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Saves everything (tasks, diary, places, moods, special days) into one file. " +
                        "In the next screen pick where to save it: choose \"Drive\" from the menu to keep it " +
                        "in your Google Drive, or a folder such as Downloads to keep it on the phone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Last backup: " + (lastBackup?.millisToDateTime()?.let { "${it.toLocalDate().format(dayFormat)}, ${it.format(timeFormat)}" } ?: "never"),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = { createFile.launch(Backup.suggestedFileName()) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Back up now")
                }
            }

            InfoCard {
                Text("Restore from a backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pick a My Diary backup file from your phone or Google Drive. " +
                        "This replaces everything currently in the app.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { openFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restore…")
                }
            }

            if (busy) CircularProgressIndicator()
            status?.let { Text(it, style = MaterialTheme.typography.titleSmall) }

            InfoCard {
                Text("Automatic Google backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Android also backs up this app's data to your Google account automatically when " +
                        "\"Backup by Google One\" is on in your phone settings, and restores it when you set up a new phone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Replace all data?") },
            text = { Text("Everything currently in My Diary will be replaced by the contents of this backup.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    run { "✅ Restored ${Backup.restore(context, app.db, uri)} items." }
                }) { Text("Restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }
}
