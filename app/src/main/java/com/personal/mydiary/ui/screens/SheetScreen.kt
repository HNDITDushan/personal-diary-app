package com.personal.mydiary.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.personal.mydiary.R
import com.personal.mydiary.data.SheetSettings
import com.personal.mydiary.data.SheetSync
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.DiaryScaffold
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.work.SheetSyncWorker
import kotlinx.coroutines.launch

private fun scriptText(context: Context, token: String) =
    context.resources.openRawResource(R.raw.sheet_script).bufferedReader().use { it.readText() }
        .replace("__TOKEN__", token)

@Composable
fun SheetScreen(onBack: () -> Unit) {
    val app = diaryApp()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SheetSettings(context) }

    var url by remember { mutableStateOf(settings.url) }
    var connected by remember { mutableStateOf(settings.isConfigured) }
    var autoSync by remember { mutableStateOf(settings.autoSync) }
    var sheetUrl by remember { mutableStateOf(settings.sheetUrl) }
    var status by remember { mutableStateOf(settings.lastMessage) }
    var busy by remember { mutableStateOf(false) }

    fun syncNow() {
        busy = true
        scope.launch {
            status = runCatching { SheetSync.sync(context, app.db) }
                .fold({ "${SheetSyncWorker.stamp()} · $it" }, { "${SheetSyncWorker.stamp()} · Sync failed: ${it.message}" })
            settings.lastMessage = status
            sheetUrl = settings.sheetUrl
            busy = false
        }
    }

    fun connect() {
        val link = url.trim()
        if (!link.startsWith("https://script.google.com/")) {
            Toast.makeText(context, "Paste the Web app URL; it starts with https://script.google.com/", Toast.LENGTH_LONG).show()
            return
        }
        settings.url = link
        busy = true
        scope.launch {
            val name = runCatching { SheetSync.test(context) }
            busy = false
            name.onSuccess {
                connected = true
                SheetSyncWorker.schedule(context)
                Toast.makeText(context, "Connected to \"$it\"", Toast.LENGTH_SHORT).show()
                syncNow()
            }.onFailure {
                settings.url = ""
                status = "Couldn't connect: ${it.message}"
            }
        }
    }

    DiaryScaffold(title = "Google Sheet", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (connected) {
                InfoCard {
                    Text("✅ Linked to your Google Sheet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Everything in the app is copied to the sheet, and rows you add or change in the sheet come into the app.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status.isNotBlank()) {
                        Spacer(Modifier.size(8.dp))
                        Text("Last sync: $status", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = ::syncNow, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "Syncing…" else "Sync now")
                    }
                    if (sheetUrl.isNotBlank()) {
                        OutlinedButton(
                            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sheetUrl))) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open the sheet")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sync automatically")
                            Text(
                                "About a minute after each change, and every hour",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoSync, onCheckedChange = {
                            autoSync = it
                            settings.autoSync = it
                            SheetSyncWorker.schedule(context)
                        })
                    }
                    TextButton(onClick = {
                        settings.url = ""
                        url = ""
                        connected = false
                        status = ""
                        SheetSyncWorker.schedule(context)
                    }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
                }
            }

            InfoCard {
                Text(if (connected) "Set-up steps" else "Link a Google Sheet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Easiest on a computer. Takes about 5 minutes, once.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Step(1, "Create a new, empty Google Sheet (type sheets.new in the browser). Give it a name, e.g. My Diary.")
                Step(2, "In the sheet open Extensions → Apps Script. Delete the code that is there and paste the My Diary script. Use the buttons below to copy it, or send it to yourself.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("My Diary script", scriptText(context, settings.token)))
                        Toast.makeText(context, "Script copied", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy script")
                    }
                    FilledTonalButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "My Diary – Google Sheet script")
                            .putExtra(Intent.EXTRA_TEXT, scriptText(context, settings.token))
                        context.startActivity(Intent.createChooser(send, "Send script to…"))
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Send to computer")
                    }
                }
                Step(3, "Click 💾 Save. Then Deploy → New deployment → ⚙ Select type → Web app. Set \"Execute as\" to Me and \"Who has access\" to Anyone, then click Deploy.")
                Step(4, "Google asks you to authorise: choose your account, click Advanced → Go to project (unsafe) → Allow. (It's your own script, so this is safe.)")
                Step(5, "Copy the Web app URL that Google shows and paste it here:")
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Web app URL") },
                    placeholder = { Text("https://script.google.com/macros/s/…/exec") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = ::connect, enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (connected) "Save link" else "Connect")
                }
                if (!connected && status.isNotBlank()) {
                    Text(status, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.size(8.dp))
                Text("Secret key in your script", style = MaterialTheme.typography.labelLarge)
                Text(settings.token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }

            InfoCard {
                Text("Adding entries in the sheet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "After the first sync the sheet has a tab for each part of the diary: Tasks, Appointments, " +
                        "Activities, Memories, Good deeds, Special days, Places and Moods. Add a row at the bottom of a tab " +
                        "and it appears in the app on the next sync. Dates: 2026-10-29 or 29/10/2026. Times: 16:30 or 4:30 PM. " +
                        "Leave the grey uid and updatedAt columns empty; they fill in by themselves. " +
                        "Deleting a row in the sheet deletes it from the app too.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("$number.", fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
