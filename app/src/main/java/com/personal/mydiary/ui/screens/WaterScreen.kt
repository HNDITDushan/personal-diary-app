package com.personal.mydiary.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.personal.mydiary.data.Hydration
import com.personal.mydiary.data.WaterLog
import com.personal.mydiary.data.WaterSettings
import com.personal.mydiary.data.formatMl
import com.personal.mydiary.data.save
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.DiaryScaffold
import com.personal.mydiary.ui.EditorDialog
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.ui.SectionTitle
import com.personal.mydiary.ui.TimeField
import com.personal.mydiary.util.dayFormat
import com.personal.mydiary.util.storedToTime
import com.personal.mydiary.util.timeFormat
import com.personal.mydiary.util.toStored
import com.personal.mydiary.util.today
import com.personal.mydiary.work.WaterReminders
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

val WaterBlue = Color(0xFF1E88E5)
private val WaterLight = Color(0xFF4FC3F7)
private val WaterDeep = Color(0xFF1565C0)

/** Emoji for each quick-add slot: glass, bottle, cup. */
private val sizeEmoji = listOf("🥛", "🧴", "☕")
private val sizeName = listOf("Glass", "Bottle", "Cup")

private val barDay = DateTimeFormatter.ofPattern("EEEEE")

/** "1.8 L" for chart labels, "750" below a litre. */
private fun shortMl(ml: Int) = if (ml >= 1000) "%.1f L".format(ml / 1000.0) else "$ml"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaterScreen(onBack: () -> Unit) {
    val app = diaryApp()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { WaterSettings(context) }
    // Bumped after the settings dialog saves, so the figures below re-read the preferences.
    var settingsVersion by remember { mutableIntStateOf(0) }
    val goal = remember(settingsVersion) { settings.goalMl }
    val sizes = remember(settingsVersion) { settings.sizes }

    val todayDate = remember { today() }
    var day by remember { mutableStateOf(todayDate) }
    val isToday = day == todayDate
    val logs by remember(day) { app.db.water().observeOn(day.toEpochDay()) }.collectAsState(initial = emptyList())
    val recent by remember { app.db.water().observeBetween(todayDate.toEpochDay() - 60, todayDate.toEpochDay()) }
        .collectAsState(initial = emptyList())
    val totals = remember(recent) { Hydration.dailyTotals(recent) }
    val total = logs.sumOf { it.amountMl }
    val streak = Hydration.streak(totals, goal, todayDate.toEpochDay())

    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var customAmount by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WaterLog?>(null) }

    fun add(ml: Int) {
        val log = WaterLog(date = day.toEpochDay(), time = if (isToday) LocalTime.now().toStored() else "", amountMl = ml)
        app.launch { water().save(log) }
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val reached = total < goal && total + ml >= goal
            val result = snackbar.showSnackbar(
                if (reached) "🎉 Goal reached! Added ${formatMl(ml)}" else "Added ${formatMl(ml)}",
                actionLabel = "Undo", duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) app.launch { water().deleteByUid(log.uid) }
        }
    }

    DiaryScaffold(
        title = "Water",
        onBack = onBack,
        actions = { IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "Goal and reminders") } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { day = day.minusDays(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day") }
                    Text(
                        if (isToday) "Today" else if (day == todayDate.minusDays(1)) "Yesterday" else day.format(dayFormat),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { day = day.plusDays(1) }, enabled = !isToday) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day")
                    }
                }
            }

            item {
                InfoCard {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        WaterRing(total, goal)
                        Spacer(Modifier.height(12.dp))
                        val left = Hydration.glassesLeft(total, goal, sizes.first())
                        Text(
                            when {
                                total >= goal -> "🎉 Goal reached. Well done!"
                                total == 0 -> if (isToday) "Let's start with a glass of water" else "Nothing logged this day"
                                else -> "${formatMl(goal - total)} to go · about $left more glass${if (left == 1) "" else "es"}"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (total >= goal) WaterDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (streak > 0 || logs.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (streak > 0) WaterPill("🔥 $streak-day streak")
                                if (logs.isNotEmpty()) WaterPill("💧 ${logs.size} drink${if (logs.size == 1) "" else "s"}")
                            }
                        }
                    }
                }
            }

            item {
                SectionTitle(if (isToday) "I just drank…" else "Add to ${day.format(dayFormat)}")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sizes.forEachIndexed { i, ml ->
                        DrinkTile(sizeEmoji[i], sizeName[i], formatMl(ml), Modifier.weight(1f)) { add(ml) }
                    }
                    DrinkTile("✏️", "Other", "amount", Modifier.weight(1f)) { customAmount = true }
                }
            }

            item { WeekCard(todayDate, day, totals, goal) { day = it } }

            item { SectionTitle(if (isToday) "Today's drinks" else "Drinks") }
            if (logs.isEmpty()) {
                item {
                    Text(
                        "No water logged yet. Tap a glass above each time you drink.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(logs, key = { it.id }) { log ->
                val index = sizes.indexOf(log.amountMl)
                CompactRow(
                    emoji = if (index >= 0) sizeEmoji[index] else "💧",
                    headline = formatMl(log.amountMl),
                    supporting = if (index >= 0) sizeName[index] else "",
                    trailing = log.time.storedToTime()?.format(timeFormat).orEmpty(),
                    trailingColor = WaterBlue,
                    onClick = { editing = log },
                )
            }
        }
    }

    if (customAmount) CustomAmountDialog(onDismiss = { customAmount = false }) { add(it); customAmount = false }
    editing?.let { log -> DrinkEditor(log) { editing = null } }
    if (showSettings) WaterSettingsDialog(settings) { showSettings = false; settingsVersion++ }
}

/** The day's progress as a ring that fills with water. */
@Composable
private fun WaterRing(total: Int, goal: Int) {
    val target = if (goal > 0) (total.toFloat() / goal).coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(target, tween(600), label = "water")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (progress > 0f) {
                drawArc(
                    Brush.sweepGradient(listOf(WaterLight, WaterBlue, WaterDeep, WaterLight)),
                    -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("💧", fontSize = 28.sp)
            Text(
                "%,d".format(total),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                color = WaterDeep,
            )
            Text("of ${formatMl(goal)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${if (goal > 0) total * 100 / goal else 0}%",
                style = MaterialTheme.typography.labelLarge,
                color = WaterBlue,
            )
        }
    }
}

@Composable
private fun WaterPill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = WaterDeep,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(WaterBlue.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun DrinkTile(emoji: String, label: String, amount: String, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = WaterBlue.copy(alpha = 0.10f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 30.sp)
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(amount, style = MaterialTheme.typography.labelSmall, color = WaterDeep)
        }
    }
}

/** The last seven days as bars against the goal; tapping a bar opens that day. */
@Composable
private fun WeekCard(todayDate: LocalDate, selected: LocalDate, totals: Map<Long, Int>, goal: Int, onPick: (LocalDate) -> Unit) {
    val days = (6 downTo 0).map { todayDate.minusDays(it.toLong()) }
    val amounts = days.map { totals[it.toEpochDay()] ?: 0 }
    val met = amounts.count { it >= goal }
    val logged = amounts.filter { it > 0 }
    InfoCard {
        Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
        Text(
            if (logged.isEmpty()) "Your week will show here as you log water."
            else "Goal reached on $met of 7 days · average ${formatMl(logged.sum() / logged.size)} on days you logged",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("- - -  goal ${formatMl(goal)}", style = MaterialTheme.typography.labelSmall, color = WaterDeep)
        Spacer(Modifier.height(8.dp))
        val top = (maxOf(goal, amounts.maxOrNull() ?: 0) * 1.1f).toInt().coerceAtLeast(1)
        val goalLine = WaterDeep.copy(alpha = 0.5f)
        Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEachIndexed { i, d ->
                val ml = amounts[i]
                val isSelected = d == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable { onPick(d) }
                        .background(if (isSelected) WaterBlue.copy(alpha = 0.08f) else Color.Transparent),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (ml > 0) shortMl(ml) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Box(
                        Modifier.weight(1f).fillMaxWidth()
                            .drawBehind {
                                // Each column draws its piece of the dashed goal line; together they run across the chart.
                                val y = size.height * (1f - goal.toFloat() / top)
                                drawLine(goalLine, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                            }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight((ml.toFloat() / top).coerceIn(0.03f, 1f))
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (ml >= goal) WaterBlue else WaterLight.copy(alpha = if (ml > 0) 0.6f else 0.25f)),
                        )
                    }
                    Text(
                        d.format(barDay) + if (ml >= goal) "✓" else "",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else null,
                        color = if (isSelected) WaterDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MlField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        suffix = { Text("ml") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomAmountDialog(onDismiss: () -> Unit, onAdd: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val ml = text.toIntOrNull()?.takeIf { it in 1..5000 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How much did you drink?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MlField(text, { text = it }, "Amount")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 200, 300, 750, 1000).forEach { preset ->
                        SuggestionChip(onClick = { onAdd(preset) }, label = { Text(formatMl(preset)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { ml?.let(onAdd) }, enabled = ml != null) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DrinkEditor(log: WaterLog, onDismiss: () -> Unit) {
    val app = diaryApp()
    var text by remember { mutableStateOf(log.amountMl.toString()) }
    var time by remember { mutableStateOf(log.time.storedToTime()) }
    val ml = text.toIntOrNull()?.takeIf { it in 1..5000 }
    EditorDialog(
        title = "Edit drink",
        onDismiss = onDismiss,
        saveEnabled = ml != null,
        onSave = {
            app.launch { water().save(log.copy(amountMl = ml!!, time = time?.toStored().orEmpty())) }
            onDismiss()
        },
        onDelete = { app.launch { water().delete(log) }; onDismiss() },
    ) {
        MlField(text, { text = it }, "Amount")
        TimeField("No time", time, { time = it }, clearable = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaterSettingsDialog(settings: WaterSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var goal by remember { mutableIntStateOf(settings.goalMl) }
    var weight by remember { mutableStateOf("") }
    val sizeText = remember { settings.sizes.map { mutableStateOf(it.toString()) } }
    var remind by remember { mutableStateOf(settings.remindersOn) }
    var interval by remember { mutableIntStateOf(settings.intervalMinutes) }
    var start by remember { mutableStateOf(LocalTime.of(settings.startHour, 0)) }
    var end by remember { mutableStateOf(LocalTime.of(settings.endHour % 24, 0)) }
    val sizes = sizeText.map { it.value.toIntOrNull()?.takeIf { ml -> ml in 10..3000 } }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) remind = false
    }

    EditorDialog(
        title = "Water settings",
        onDismiss = onDismiss,
        saveEnabled = sizes.all { it != null },
        onSave = {
            settings.goalMl = goal
            settings.sizes = sizes.map { it!! }
            settings.remindersOn = remind
            settings.intervalMinutes = interval
            settings.startHour = start.hour
            settings.endHour = if (end.hour <= start.hour) 24 else end.hour
            WaterReminders.apply(context)
            onDismiss()
        },
    ) {
        Text("Daily goal", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { goal = (goal - 250).coerceAtLeast(500) }) { Text("−") }
            Text(
                formatMl(goal),
                style = MaterialTheme.typography.headlineSmall,
                color = WaterDeep,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = { goal = (goal + 250).coerceAtMost(6000) }) { Text("+") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = weight,
                onValueChange = { v -> weight = v.filter { it.isDigit() || it == '.' }.take(5) },
                label = { Text("Your weight") },
                suffix = { Text("kg") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            val suggested = weight.toDoubleOrNull()?.takeIf { it in 20.0..250.0 }?.let { Hydration.suggestedGoalMl(it) }
            TextButton(onClick = { suggested?.let { goal = it } }, enabled = suggested != null) {
                Text(suggested?.let { "Use ${formatMl(it)}" } ?: "Suggest")
            }
        }
        Text(
            "A common guide is about 35 ml for each kg you weigh. Drink more on hot days or when you exercise.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Quick add buttons", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sizeText.forEachIndexed { i, state ->
                MlField(state.value, { state.value = it }, "${sizeEmoji[i]} ${sizeName[i]}", Modifier.weight(1f))
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remind me to drink", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Only while you're behind, and not right after a drink",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = remind, onCheckedChange = { on ->
                remind = on
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
        }
        if (remind) {
            Text("Every", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60 to "1 h", 90 to "1½ h", 120 to "2 h", 180 to "3 h").forEach { (minutes, label) ->
                    FilterChip(selected = interval == minutes, onClick = { interval = minutes }, label = { Text(label) })
                }
            }
            Text("Between", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { TimeField("From", start, { it?.let { t -> start = t.withMinute(0) } }, prefix = "From ") }
                Box(Modifier.weight(1f)) { TimeField("Until", end, { it?.let { t -> end = t.withMinute(0) } }, prefix = "Until ") }
            }
        }
    }
}

/** Today's water on the Today screen: progress and a one-tap glass. */
@Composable
fun WaterTodayCard(onOpen: () -> Unit) {
    val app = diaryApp()
    val context = LocalContext.current
    val settings = remember { WaterSettings(context) }
    val day = remember { today().toEpochDay() }
    val logs by remember { app.db.water().observeOn(day) }.collectAsState(initial = emptyList())
    val total = logs.sumOf { it.amountMl }
    val goal = settings.goalMl
    val glass = settings.glassMl
    val progress by animateFloatAsState(if (goal > 0) (total.toFloat() / goal).coerceIn(0f, 1f) else 0f, tween(600), label = "water")
    InfoCard(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("💧", fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Water", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (total >= goal) "🎉 ${formatMl(total)} · goal reached" else "${formatMl(total)} of ${formatMl(goal)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = {
                    app.launch { water().save(WaterLog(date = day, time = LocalTime.now().toStored(), amountMl = glass)) }
                },
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = WaterBlue.copy(alpha = 0.14f), contentColor = WaterDeep),
            ) { Text("🥛 +$glass ml") }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(
                Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(5.dp))
                    .background(Brush.horizontalGradient(listOf(WaterLight, WaterBlue))),
            )
        }
    }
}
