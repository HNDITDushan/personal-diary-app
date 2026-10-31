package com.personal.mydiary.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.personal.mydiary.Routes
import com.personal.mydiary.data.Place
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.DiaryScaffold
import com.personal.mydiary.ui.EmptyState
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.ui.PlaceEditor
import com.personal.mydiary.util.LocationHelper
import com.personal.mydiary.util.dayFormat
import com.personal.mydiary.util.epochDayToDate

@Composable
fun MoreScreen(onOpen: (String) -> Unit) {
    DiaryScaffold(title = "More") { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MoreItem("🎂", "Events", "Appointments, birthdays and special days") { onOpen(Routes.EVENTS) }
            MoreItem("🧮", "Loan calculator", "Work out installments, interest and months") { onOpen(Routes.LOAN_CALCULATOR) }
            MoreItem("📊", "Monthly report", "Were you happy? Good deeds and stats") { onOpen(Routes.REPORT) }
            MoreItem("📍", "Places I visited", "Save places with your location") { onOpen(Routes.PLACES) }
            MoreItem("📗", "Google Sheet", "View and add entries from a Google Sheet") { onOpen(Routes.SHEET) }
            MoreItem("☁️", "Backup & restore", "Phone storage or Google Drive") { onOpen(Routes.BACKUP) }
        }
    }
}

@Composable
private fun MoreItem(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
    CompactRow(headline = title, supporting = subtitle, emoji = emoji, onClick = onClick)
}

@Composable
fun PlacesScreen(onBack: () -> Unit) {
    val app = diaryApp()
    val context = LocalContext.current
    val places by remember { app.db.places().observeAll() }.collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Place?>(null) }

    DiaryScaffold(
        title = "Places I visited",
        onBack = onBack,
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add place") } },
    ) { padding ->
        if (places.isEmpty()) {
            Column(Modifier.padding(padding)) {
                EmptyState(Icons.Outlined.Map, "No places yet. Tap + and use your current location to save where you are.")
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(places, key = { it.id }) { p ->
                    InfoCard(Modifier.clickable { editing = p }) {
                        ListItem(
                            headlineContent = { Text("📍 ${p.name}") },
                            supportingContent = {
                                Text(listOf(p.date.epochDayToDate().format(dayFormat), p.address, p.notes)
                                    .filter { it.isNotBlank() }.joinToString("\n"))
                            },
                            trailingContent = {
                                IconButton(onClick = { LocationHelper.openInMaps(context, p.name, p.latitude, p.longitude) }) {
                                    Icon(Icons.Outlined.Map, "Open in Maps")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (adding) PlaceEditor(null) { adding = false }
    editing?.let { PlaceEditor(it) { editing = null } }
}
