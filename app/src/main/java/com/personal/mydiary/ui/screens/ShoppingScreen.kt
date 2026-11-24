package com.personal.mydiary.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.room.withTransaction
import com.personal.mydiary.ShareInbox
import com.personal.mydiary.data.BillPhoto
import com.personal.mydiary.data.BillSource
import com.personal.mydiary.data.KeellsEBill
import com.personal.mydiary.data.MoneyTxn
import com.personal.mydiary.data.ParsedBill
import com.personal.mydiary.data.PriceChange
import com.personal.mydiary.data.Purchase
import com.personal.mydiary.data.ShoppingBill
import com.personal.mydiary.data.ShoppingItem
import com.personal.mydiary.data.ShoppingPrices
import com.personal.mydiary.data.TxnKind
import com.personal.mydiary.data.defaultAccountAmong
import com.personal.mydiary.data.save
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.DateField
import com.personal.mydiary.ui.DiaryScaffold
import com.personal.mydiary.ui.EditorDialog
import com.personal.mydiary.ui.EmptyState
import com.personal.mydiary.ui.HeroCard
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.ui.SectionTitle
import com.personal.mydiary.ui.TextInput
import com.personal.mydiary.ui.TimeField
import com.personal.mydiary.util.dayFormat
import com.personal.mydiary.util.epochDayToDate
import com.personal.mydiary.util.formatMoney
import com.personal.mydiary.util.moneyInput
import com.personal.mydiary.util.parseMoney
import com.personal.mydiary.util.storedToTime
import com.personal.mydiary.util.timeFormat
import com.personal.mydiary.util.toStored
import com.personal.mydiary.util.today
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val shortDay = DateTimeFormatter.ofPattern("d MMM")

/** A price rise costs more, so it wears the error colour; a drop wears the "good" colour. Both carry an arrow and words too. */
@Composable
private fun changeColor(diff: Long) = if (diff > 0) MaterialTheme.colorScheme.error else Color(0xFF2E9D57)

private fun changeText(c: PriceChange) =
    (if (c.diff > 0) "▲ up " else "▼ down ") + formatMoney(abs(c.diff)) + " (${abs(c.percent)}%)"

private fun unitPriceText(price: Long, weighed: Boolean) = formatMoney(price) + if (weighed) " /kg" else ""

private fun qtyText(qty: Double, weighed: Boolean) =
    if (weighed) "%.3f kg".format(qty) else if (qty == qty.toLong().toDouble()) "× ${qty.toLong()}" else "× $qty"

/** A bill being checked before it's saved: what was read, or an existing bill being edited. */
private data class Draft(val bill: ShoppingBill, val lines: List<ShoppingItem>, val isNew: Boolean, val note: String = "")

private fun draftFrom(parsed: ParsedBill, source: BillSource, url: String = "", note: String = ""): Draft {
    val bill = ShoppingBill(
        date = (parsed.date ?: today()).toEpochDay(), time = parsed.time, store = parsed.store, branch = parsed.branch,
        gross = parsed.gross ?: parsed.lines.sumOf { it.amount }, discount = parsed.discount,
        net = parsed.net ?: parsed.lines.sumOf { it.amount }, payment = parsed.payment, source = source, url = url,
        dedupeKey = parsed.dedupeKey,
    )
    val lines = parsed.lines.filterNot { ShoppingPrices.isNoise(it.unitPrice, it.qty) }.map {
        ShoppingItem(billUid = bill.uid, code = it.code, name = ShoppingPrices.displayName(it.name), itemKey = "", unitPrice = it.unitPrice, qty = it.qty, amount = it.amount, weighed = it.weighed)
    }
    return Draft(bill, lines, isNew = true, note = note)
}

@Composable
fun ShoppingScreen(onBack: () -> Unit) {
    val app = diaryApp()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bills by remember { app.db.shopping().observeBills() }.collectAsState(initial = null)
    val purchases by remember { app.db.shopping().observePurchases() }.collectAsState(initial = emptyList())
    val history = remember(purchases) { ShoppingPrices.history(purchases) }

    var tab by rememberSaveable { mutableStateOf(0) }
    var choosing by remember { mutableStateOf(false) }
    var pastingLink by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var openBill by remember { mutableStateOf<ShoppingBill?>(null) }
    var openItem by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    fun importLink(text: String) {
        val link = KeellsEBill.findLink(text)
        if (link == null) {
            problem = "No Keells e-bill link was found. Paste the whole message, or the link starting with https://digibill.keellssuper.com/"
            return
        }
        reading = "Opening the e-bill…"
        scope.launch {
            try {
                val parsed = KeellsEBill.parse(KeellsEBill.download(link))
                val existing = parsed.dedupeKey?.let { app.db.shopping().billWithKey(it) }
                if (existing != null) {
                    openBill = existing
                    snackbar.showSnackbar("This bill is already saved")
                } else {
                    draft = draftFrom(parsed, BillSource.EBILL, url = link)
                }
            } catch (e: Exception) {
                problem = e.message ?: "The e-bill couldn't be read. Check you're online and try again."
            } finally {
                reading = null
            }
        }
    }

    fun importPhoto(uri: Uri) {
        reading = "Reading the bill…"
        scope.launch {
            try {
                val parsed = BillPhoto.read(context, uri)
                draft = draftFrom(
                    parsed, BillSource.PHOTO,
                    note = if (parsed.lines.isEmpty()) "No items could be read from the photo. Add them below, or try a sharper, straighter photo."
                    else "Read ${parsed.lines.size} items from the photo. Check the names and prices before saving.",
                )
            } catch (e: Exception) {
                problem = "The photo couldn't be read: ${e.message}"
            } finally {
                reading = null
            }
        }
    }

    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        photoUri?.takeIf { ok }?.let { importPhoto(Uri.parse(it)) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::importPhoto) }

    // Something shared to the app from another one: an e-bill message or a bill photo.
    val shared = ShareInbox.pending
    LaunchedEffect(shared) {
        when (val s = ShareInbox.take()) {
            is ShareInbox.Shared.Text -> importLink(s.text)
            is ShareInbox.Shared.Image -> importPhoto(s.uri)
            null -> {}
        }
    }

    DiaryScaffold(
        title = "Shopping",
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { choosing = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add bill") })
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Bills", "Prices").forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            val list = bills
            when {
                list == null -> {}
                list.isEmpty() -> FirstUse { choosing = true }
                tab == 0 -> BillsTab(list, history, onOpen = { openBill = it }, onItem = { openItem = it }, onSeeAll = { tab = 1 })
                else -> PricesTab(history) { openItem = it }
            }
        }
    }

    if (choosing) {
        AddBillSheet(
            onDismiss = { choosing = false },
            onLink = { choosing = false; pastingLink = true },
            onCamera = {
                choosing = false
                val uri = BillPhoto.newPhotoUri(context)
                photoUri = uri.toString()
                camera.launch(uri)
            },
            onGallery = { choosing = false; gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onManual = { choosing = false; draft = draftFrom(ParsedBill(), BillSource.MANUAL) },
        )
    }
    if (pastingLink) LinkDialog(onDismiss = { pastingLink = false }) { pastingLink = false; importLink(it) }
    reading?.let { ReadingDialog(it) }
    problem?.let {
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text("Couldn't add the bill") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text("OK") } },
        )
    }
    draft?.let { d -> BillReview(d, onDismiss = { draft = null }, onSaved = { saved -> draft = null; openBill = saved }) }
    openBill?.let { b ->
        val current = bills?.firstOrNull { it.uid == b.uid } ?: b
        BillDetail(current, history, onDismiss = { openBill = null }, onItem = { openItem = it }, onEdit = { lines ->
            draft = Draft(current, lines, isNew = false)
        })
    }
    openItem?.let { key -> history[key]?.let { ItemDetail(it, onDismiss = { openItem = null }) } ?: run { openItem = null } }
}

@Composable
private fun FirstUse(onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EmptyState(Icons.Outlined.ShoppingCart, "Add your shopping bills and see how the prices of the things you buy go up or down.")
        InfoCard {
            Text("Ways to add a bill", style = MaterialTheme.typography.titleSmall)
            Text("🔗  Paste a Keells e-bill message or link", style = MaterialTheme.typography.bodyMedium)
            Text("📤  Or share the e-bill SMS to My Diary from your messages app", style = MaterialTheme.typography.bodyMedium)
            Text("📷  Take a photo of a paper bill", style = MaterialTheme.typography.bodyMedium)
            Text("✏️  Type the items in yourself", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add your first bill") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBillSheet(onDismiss: () -> Unit, onLink: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit, onManual: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add a bill", style = MaterialTheme.typography.titleLarge)
            CompactRow("Paste e-bill link", "From the Keells SMS or e-mail", emoji = "🔗", onClick = onLink)
            CompactRow("Take a photo", "Of a paper bill, flat and in good light", emoji = "📷", onClick = onCamera)
            CompactRow("Choose a photo", "A bill photo or screenshot you already have", emoji = "🖼️", onClick = onGallery)
            CompactRow("Type it in", "Add the items yourself", emoji = "✏️", onClick = onManual)
        }
    }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onRead: (String) -> Unit) {
    val context = LocalContext.current
    // Start with the copied text when it holds an e-bill link, so it's usually just one tap.
    var text by remember {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val copied = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        mutableStateOf(if (KeellsEBill.findLink(copied) != null) copied else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste e-bill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste the e-bill message or just its link.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://digibill.keellssuper.com/…") })
                Text("The bill is opened from Keells' site, so you need to be online.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onRead(text) }, enabled = text.isNotBlank()) { Text("Read bill") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReadingDialog(message: String) {
    Dialog(onDismissRequest = {}) {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Text(message)
            }
        }
    }
}

@Composable
private fun BillsTab(bills: List<ShoppingBill>, history: Map<String, List<Purchase>>, onOpen: (ShoppingBill) -> Unit, onItem: (String) -> Unit, onSeeAll: () -> Unit) {
    val month = YearMonth.from(today())
    val thisMonth = bills.filter { YearMonth.from(it.date.epochDayToDate()) == month }
    val changes = remember(history) { ShoppingPrices.latestChanges(history) }
    val perBill = remember(history) {
        history.values.flatMap { list -> list.mapNotNull { p -> ShoppingPrices.changeFor(p, list) } }.groupBy { it.current.billUid }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            HeroCard {
                Text("This month", style = MaterialTheme.typography.labelLarge)
                Text(formatMoney(thisMonth.sumOf { it.net }), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                val saved = thisMonth.sumOf { it.discount }
                Text(
                    listOfNotNull(
                        if (thisMonth.size == 1) "1 bill" else "${thisMonth.size} bills",
                        "saved ${formatMoney(saved)} in offers".takeIf { saved > 0 },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (changes.isNotEmpty()) {
            item {
                SectionTitle("Latest price changes")
                InfoCard {
                    changes.take(5).forEach { c -> ChangeLine(c) { onItem(c.current.itemKey) } }
                    if (changes.size > 5) {
                        TextButton(onClick = onSeeAll) { Text("See all ${changes.size} changes") }
                    }
                }
            }
        }
        item { SectionTitle("Bills") }
        items(bills, key = { it.uid }) { b ->
            val moved = perBill[b.uid].orEmpty()
            val up = moved.count { it.diff > 0 }
            val down = moved.count { it.diff < 0 }
            CompactRow(
                emoji = when (b.source) { BillSource.EBILL -> "🧾"; BillSource.PHOTO -> "📷"; BillSource.MANUAL -> "✏️" },
                headline = b.title,
                supporting = listOfNotNull(
                    b.date.epochDayToDate().format(dayFormat),
                    "$up up".takeIf { up > 0 }?.let { "▲ $it" },
                    "$down down".takeIf { down > 0 }?.let { "▼ $it" },
                ).joinToString(" · "),
                trailing = formatMoney(b.net),
                onClick = { onOpen(b) },
            )
        }
    }
}

/** An item's name, and how its price moved since it was last bought. */
@Composable
private fun ChangeLine(c: PriceChange, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.current.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(changeText(c), style = MaterialTheme.typography.labelLarge, color = changeColor(c.diff), fontWeight = FontWeight.SemiBold)
        }
        Text(
            "${unitPriceText(c.previous.unitPrice, c.previous.weighed)} → ${unitPriceText(c.current.unitPrice, c.current.weighed)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PricesTab(history: Map<String, List<Purchase>>, onItem: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var changedOnly by rememberSaveable { mutableStateOf(false) }
    val rows = remember(history, query, changedOnly) {
        history.values.map { list -> list.last() to ShoppingPrices.changeFor(list.last(), list) }
            .filter { (p, c) -> (query.isBlank() || p.name.contains(query.trim(), true)) && (!changedOnly || c != null) }
            .sortedWith(compareByDescending<Pair<Purchase, PriceChange?>> { it.second?.let { c -> abs(c.percent) } ?: -1 }.thenBy { it.first.name })
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search items, e.g. carrot") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = !changedOnly, onClick = { changedOnly = false }, label = { Text("All items") }) }
                item { FilterChip(selected = changedOnly, onClick = { changedOnly = true }, label = { Text("Price changed") }) }
            }
        }
        if (rows.isEmpty()) item { Text("Nothing matches.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(rows, key = { it.first.itemKey }) { (p, c) ->
            val times = history[p.itemKey]?.size ?: 1
            InfoCard(Modifier.clickable { onItem(p.itemKey) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${unitPriceText(p.unitPrice, p.weighed)} · ${p.date.epochDayToDate().format(shortDay)} · bought ${if (times == 1) "once" else "$times times"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (c != null) {
                        Text(changeText(c), style = MaterialTheme.typography.labelLarge, color = changeColor(c.diff), fontWeight = FontWeight.SemiBold)
                    } else if (times > 1) {
                        Text("No change", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** A full-screen page over the current one, with a back arrow and optional actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreen(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            modifier = Modifier.imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = actions,
                )
            },
            content = content,
        )
    }
}

@Composable
private fun BillDetail(bill: ShoppingBill, history: Map<String, List<Purchase>>, onDismiss: () -> Unit, onItem: (String) -> Unit, onEdit: (List<ShoppingItem>) -> Unit) {
    val app = diaryApp()
    val items by remember(bill.uid) { app.db.shopping().observeItems(bill.uid) }.collectAsState(initial = emptyList())
    var deleting by remember { mutableStateOf(false) }
    val changes = items.associate { item ->
        val list = history[item.itemKey].orEmpty()
        item.uid to list.firstOrNull { it.itemUid == item.uid }?.let { ShoppingPrices.changeFor(it, list) }
    }
    val up = changes.values.filterNotNull().filter { it.diff > 0 }
    val down = changes.values.filterNotNull().filter { it.diff < 0 }

    FullScreen(bill.title, onDismiss, actions = {
        IconButton(onClick = { onEdit(items) }) { Icon(Icons.Outlined.Edit, "Edit bill") }
        IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.Delete, "Delete bill") }
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                HeroCard {
                    Text(formatMoney(bill.net), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(
                            bill.date.epochDayToDate().format(dayFormat),
                            bill.time.storedToTime()?.format(timeFormat),
                        ).joinToString(" · "),
                    )
                    if (bill.payment.isNotBlank()) Text("Paid by ${bill.payment}", style = MaterialTheme.typography.bodySmall)
                    if (bill.discount > 0) Text("Offers saved ${formatMoney(bill.discount)} off ${formatMoney(bill.gross)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (up.isNotEmpty() || down.isNotEmpty()) {
                item {
                    InfoCard {
                        Text("Compared with last time", style = MaterialTheme.typography.titleSmall)
                        if (up.isNotEmpty()) Text("▲ ${up.size} ${if (up.size == 1) "item" else "items"} cost more", color = changeColor(1))
                        if (down.isNotEmpty()) Text("▼ ${down.size} ${if (down.size == 1) "item" else "items"} cost less", color = changeColor(-1))
                    }
                }
            }
            item { SectionTitle(if (items.size == 1) "1 item" else "${items.size} items") }
            items(items, key = { it.uid }) { item ->
                val c = changes[item.uid]
                InfoCard(Modifier.clickable { onItem(item.itemKey) }) {
                    Row {
                        Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(formatMoney(item.amount), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        "${unitPriceText(item.unitPrice, item.weighed)} ${qtyText(item.qty, item.weighed)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (c != null) {
                        Text(
                            "${changeText(c)} since ${c.previous.date.epochDayToDate().format(shortDay)}",
                            style = MaterialTheme.typography.labelMedium, color = changeColor(c.diff), fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this bill?") },
            text = { Text("Its items are removed from your price history." + if (bill.moneyTxnUid != null) " Its Money entry is deleted too." else "") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    app.launch {
                        withTransaction {
                            shopping().deleteBillAndItems(bill.uid)
                            bill.moneyTxnUid?.let { uid -> moneyTxns().getAll().firstOrNull { it.uid == uid }?.let { moneyTxns().delete(it) } }
                        }
                    }
                    onDismiss()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ItemDetail(purchases: List<Purchase>, onDismiss: () -> Unit) {
    val latest = purchases.last()
    val weighed = latest.weighed
    val prices = purchases.map { it.unitPrice }
    FullScreen(latest.name, onDismiss) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                HeroCard {
                    Text("Now ${unitPriceText(latest.unitPrice, weighed)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    ShoppingPrices.changeFor(latest, purchases)?.let {
                        Text("${changeText(it)} since ${it.previous.date.epochDayToDate().format(shortDay)}", fontWeight = FontWeight.SemiBold)
                    }
                    val first = purchases.first()
                    if (first !== latest && first.unitPrice != latest.unitPrice) {
                        val diff = latest.unitPrice - first.unitPrice
                        Text(
                            "${if (diff > 0) "Up" else "Down"} ${formatMoney(abs(diff))} since you first bought it on ${first.date.epochDayToDate().format(shortDay)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (purchases.size > 1) {
                item {
                    SectionTitle("Price over time")
                    InfoCard { PriceChart(purchases) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniStat("Lowest", unitPriceText(prices.min(), weighed), Modifier.weight(1f))
                    MiniStat("Highest", unitPriceText(prices.max(), weighed), Modifier.weight(1f))
                    MiniStat("Bought", if (purchases.size == 1) "once" else "${purchases.size} times", Modifier.weight(1f))
                }
            }
            item { SectionTitle("Each time you bought it") }
            items(purchases.reversed(), key = { it.itemUid }) { p ->
                val c = ShoppingPrices.changeFor(p, purchases)
                CompactRow(
                    headline = unitPriceText(p.unitPrice, p.weighed),
                    supporting = listOf(p.date.epochDayToDate().format(dayFormat), p.branch.ifBlank { p.store }, qtyText(p.qty, p.weighed))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    trailing = c?.let { (if (it.diff > 0) "▲ " else "▼ ") + formatMoney(abs(it.diff)) }.orEmpty(),
                    trailingColor = c?.let { changeColor(it.diff) },
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    InfoCard(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** One item's price at each purchase: a single line, so one colour; the lowest and highest are labelled. */
@Composable
private fun PriceChart(purchases: List<Purchase>) {
    val line = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val grid = MaterialTheme.colorScheme.outlineVariant
    val prices = purchases.map { it.unitPrice }
    val lo = prices.min()
    val hi = prices.max()
    val span = (hi - lo).coerceAtLeast(1)
    Column {
        Row {
            Text(formatMoney(hi), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 8.dp)) {
            val stepX = if (prices.size > 1) size.width / (prices.size - 1) else 0f
            fun y(p: Long) = if (hi == lo) size.height / 2 else size.height - (p - lo).toFloat() / span * size.height
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            val path = Path()
            prices.forEachIndexed { i, p -> if (i == 0) path.moveTo(0f, y(p)) else path.lineTo(i * stepX, y(p)) }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
            prices.forEachIndexed { i, p ->
                drawCircle(surface, radius = 6.dp.toPx(), center = Offset(i * stepX, y(p)))
                drawCircle(line, radius = 4.dp.toPx(), center = Offset(i * stepX, y(p)))
            }
        }
        Row {
            Text(formatMoney(lo), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row {
            Text(purchases.first().date.epochDayToDate().format(shortDay), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(purchases.last().date.epochDayToDate().format(shortDay), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Check and correct a bill before saving it; also used to edit a saved bill. */
@Composable
private fun BillReview(draft: Draft, onDismiss: () -> Unit, onSaved: (ShoppingBill) -> Unit) {
    val app = diaryApp()
    val accounts by remember { app.db.accounts().observeAll() }.collectAsState(initial = emptyList())
    val b = draft.bill
    var store by rememberSaveable { mutableStateOf(b.store) }
    var branch by rememberSaveable { mutableStateOf(b.branch) }
    var date by remember { mutableStateOf(b.date.epochDayToDate()) }
    var time by remember { mutableStateOf(b.time.storedToTime()) }
    val lines = remember { mutableStateListOf<ShoppingItem>().apply { addAll(draft.lines) } }
    val itemsTotal = lines.sumOf { it.amount }
    // The bill's own total is kept when it was read from the bill; otherwise it follows the items.
    var netText by remember { mutableStateOf(moneyInput(if (draft.isNew && b.source != BillSource.EBILL) itemsTotal else b.net)) }
    var netEdited by remember { mutableStateOf(b.source == BillSource.EBILL || !draft.isNew) }
    var editingLine by remember { mutableStateOf<Int?>(null) }
    var addToMoney by rememberSaveable { mutableStateOf(b.moneyTxnUid != null) }
    var accountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var linkedTxn by remember { mutableStateOf<MoneyTxn?>(null) }
    LaunchedEffect(b.moneyTxnUid) {
        linkedTxn = b.moneyTxnUid?.let { uid -> app.db.moneyTxns().getAll().firstOrNull { it.uid == uid } }
        linkedTxn?.let { accountId = it.accountId }
    }
    val net = if (netEdited) parseMoney(netText) else itemsTotal
    val active = accounts.filter { !it.archived }
    val account = active.firstOrNull { it.id == accountId } ?: defaultAccountAmong(active)

    FullScreen(if (draft.isNew) "Check the bill" else "Edit bill", onDismiss, actions = {
        TextButton(
            enabled = lines.isNotEmpty() && net != null && (!addToMoney || account != null),
            onClick = {
                val bill = b.copy(
                    store = store.trim(), branch = branch.trim(), date = date.toEpochDay(), time = time?.toStored().orEmpty(),
                    net = net ?: itemsTotal, gross = if (b.source == BillSource.EBILL) b.gross else (net ?: itemsTotal) + b.discount,
                )
                app.launch {
                    val known = shopping().knownItems()
                    val keyed = lines.map { line ->
                        val key = ShoppingPrices.keyFor(line.code, line.name, known)
                        // A typed-in item keeps the name it already has, so "tuna fish" shows as "Tuna Fish".
                        val name = known.firstOrNull { it.itemKey == key && line.code.isBlank() }?.name ?: line.name
                        line.copy(itemKey = key, name = name)
                    }
                    withTransaction {
                        var saved = bill
                        val txn = linkedTxn
                        if (addToMoney && account != null) {
                            val entry = (txn ?: MoneyTxn(kind = TxnKind.EXPENSE, date = 0, amount = 0, accountId = 0, category = "Food & groceries")).copy(
                                date = bill.date, time = bill.time, amount = bill.net, accountId = account.id,
                                description = bill.title,
                            )
                            moneyTxns().save(entry)
                            saved = saved.copy(moneyTxnUid = entry.uid)
                        } else {
                            txn?.let { moneyTxns().delete(it) }
                            saved = saved.copy(moneyTxnUid = null)
                        }
                        shopping().saveBill(saved, keyed)
                    }
                }
                onSaved(bill)
            },
        ) { Text("Save") }
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (draft.note.isNotBlank()) item { InfoCard { Text(draft.note, style = MaterialTheme.typography.bodyMedium) } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { TextInput(store, { store = it }, "Shop") }
                        Box(Modifier.weight(1f)) { TextInput(branch, { branch = it }, "Branch") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { DateField("Date", date, { it?.let { d -> date = d } }) }
                        Box(Modifier.weight(1f)) { TimeField("Time", time, { time = it }) }
                    }
                }
            }
            item { SectionTitle("Items (${lines.size}) · ${formatMoney(itemsTotal)}") }
            if (lines.isEmpty()) item { Text("No items yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(lines, key = { _, it -> it.uid }) { i, line ->
                CompactRow(
                    headline = line.name,
                    supporting = "${unitPriceText(line.unitPrice, line.weighed)} ${qtyText(line.qty, line.weighed)}",
                    trailing = formatMoney(line.amount),
                    onClick = { editingLine = i },
                )
            }
            item {
                OutlinedButton(onClick = { editingLine = -1 }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add item")
                }
            }
            item {
                InfoCard {
                    OutlinedTextField(
                        value = if (netEdited) netText else moneyInput(itemsTotal),
                        onValueChange = { netText = it; netEdited = true },
                        label = { Text("Total paid") }, prefix = { Text("Rs ") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (b.discount > 0) Text("After ${formatMoney(b.discount)} of offers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                InfoCard {
                    Row(Modifier.clickable { addToMoney = !addToMoney }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Also add to Money", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "As a Food & groceries expense. Leave this off if the card statement will bring it in.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = addToMoney, onCheckedChange = { addToMoney = it })
                    }
                    if (addToMoney) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            // The default account comes first, so the chosen one is in view.
                            items(active.sortedByDescending { it.isDefault }, key = { it.id }) { a ->
                                FilterChip(selected = account?.id == a.id, onClick = { accountId = a.id }, label = { Text(a.displayName()) })
                            }
                        }
                    }
                }
            }
        }
    }

    editingLine?.let { i ->
        LineEditor(
            initial = lines.getOrNull(i),
            onDismiss = { editingLine = null },
            onDelete = if (i >= 0) ({ lines.removeAt(i); editingLine = null }) else null,
            onSave = { line -> if (i >= 0) lines[i] = line else lines += line; editingLine = null },
        )
    }
}

@Composable
private fun LineEditor(initial: ShoppingItem?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (ShoppingItem) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var price by rememberSaveable { mutableStateOf(initial?.unitPrice?.let(::moneyInput).orEmpty()) }
    var qty by rememberSaveable { mutableStateOf(initial?.qty?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "1") }
    var weighed by rememberSaveable { mutableStateOf(initial?.weighed ?: false) }
    val cents = parseMoney(price)
    val q = qty.toDoubleOrNull()
    val amount = if (cents != null && q != null) Math.round(cents * q) else null

    EditorDialog(
        title = if (initial == null) "Add item" else "Edit item",
        onDismiss = onDismiss,
        saveEnabled = name.isNotBlank() && cents != null && cents > 0 && q != null && q > 0,
        onDelete = onDelete,
        onSave = {
            val keepAmount = initial != null && initial.unitPrice == cents && initial.qty == q
            onSave(
                (initial ?: ShoppingItem(billUid = "", name = "", itemKey = "", unitPrice = 0, amount = 0)).copy(
                    name = name.trim(), unitPrice = cents ?: 0, qty = q ?: 1.0, weighed = weighed,
                    // A bill's own amount can differ by a cent from price × weight; keep it unless the figures changed.
                    amount = if (keepAmount) initial!!.amount else amount ?: 0,
                    code = if (initial != null && ShoppingPrices.normalize(initial.name) != ShoppingPrices.normalize(name)) "" else initial?.code.orEmpty(),
                ),
            )
        },
    ) {
        TextInput(name, { name = it }, "Item, e.g. Carrot")
        Row(Modifier.clickable { weighed = !weighed }, verticalAlignment = Alignment.CenterVertically) {
            Text("Sold by weight (price per kg)", Modifier.weight(1f))
            Switch(checked = weighed, onCheckedChange = { weighed = it })
        }
        OutlinedTextField(
            value = price, onValueChange = { price = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (weighed) "Price per kg" else "Price each") }, prefix = { Text("Rs ") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        OutlinedTextField(
            value = qty, onValueChange = { qty = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (weighed) "Weight (kg)" else "How many") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        amount?.let { Text("Amount ${formatMoney(it)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
        if (initial?.code?.isNotBlank() == true) {
            Text("Shop code ${initial.code}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
