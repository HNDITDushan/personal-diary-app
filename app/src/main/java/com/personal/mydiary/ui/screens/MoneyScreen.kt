package com.personal.mydiary.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.personal.mydiary.data.Account
import com.personal.mydiary.data.AccountType
import com.personal.mydiary.data.CardStatement
import com.personal.mydiary.data.MoneyTxn
import com.personal.mydiary.data.TxnKind
import com.personal.mydiary.data.effectOn
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.DiaryScaffold
import com.personal.mydiary.ui.EmptyState
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.ui.SectionTitle
import com.personal.mydiary.util.dayFormat
import com.personal.mydiary.util.epochDayToDate
import com.personal.mydiary.util.formatMoney
import com.personal.mydiary.util.monthFormat
import com.personal.mydiary.util.storedToTime
import com.personal.mydiary.util.timeFormat
import com.personal.mydiary.util.today
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Account's balance now: its opening balance plus every entry's effect on it. */
fun Account.balance(txns: List<MoneyTxn>): Long = openingBalance + txns.sumOf { it.effectOn(id) }

fun Account.displayName(): String = if (last4.isBlank()) name else "$name ••$last4"

private fun MoneyTxn.month(): YearMonth = YearMonth.from(date.epochDayToDate())

/** Totals for a set of entries. Transfers and card payments are moves, so they aren't spending. */
private class Totals(txns: List<MoneyTxn>) {
    private fun sum(txns: List<MoneyTxn>, kind: TxnKind) = txns.filter { it.kind == kind }.sumOf { it.amount }
    val income = sum(txns, TxnKind.INCOME)
    val spending = sum(txns, TxnKind.EXPENSE) - sum(txns, TxnKind.REFUND)
    val interest = sum(txns, TxnKind.INTEREST)
    val fees = sum(txns, TxnKind.FEE)
    val payments = sum(txns, TxnKind.CARD_PAYMENT)
    val totalOut = spending + interest + fees
    val net = income - totalOut
}

@Composable
fun MoneyScreen() {
    val db = diaryApp().db
    val accounts by remember { db.accounts().observeAll() }.collectAsState(initial = emptyList())
    val txns by remember { db.moneyTxns().observeAll() }.collectAsState(initial = emptyList())
    val statements by remember { db.statements().observeAll() }.collectAsState(initial = emptyList<CardStatement>())

    var tab by rememberSaveable { mutableStateOf(0) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today()).toString()) }
    val month = YearMonth.parse(monthText)
    var adding by remember { mutableStateOf(false) }

    DiaryScaffold(
        title = "Money",
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add entry") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                listOf("Dashboard", "Entries", "Accounts", "Credit cards").forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> DashboardTab(month, { monthText = it.toString() }, accounts, txns)
                1 -> EntriesTab(month, { monthText = it.toString() }, accounts, txns)
                2 -> AccountsTab(accounts, txns)
                else -> CardsTab(accounts, txns, statements)
            }
        }
    }

    if (adding) TxnEditor(null, TxnKind.EXPENSE) { adding = false }
}

@Composable
private fun MonthSwitcher(month: YearMonth, onChange: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
        Text(
            month.format(monthFormat),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onChange(month.plusMonths(1)) }, enabled = month < YearMonth.from(today())) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    InfoCard(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** Income and spending colours: the first two slots of the validated categorical palette. */
@Composable
private fun incomeColor() = if (isSystemInDarkTheme()) Color(0xFF3987E5) else Color(0xFF2A78D6)

@Composable
private fun spendingColor() = if (isSystemInDarkTheme()) Color(0xFFD95926) else Color(0xFFEB6834)

@Composable
private fun DashboardTab(month: YearMonth, onMonth: (YearMonth) -> Unit, accounts: List<Account>, txns: List<MoneyTxn>) {
    val monthTxns = remember(txns, month) { txns.filter { it.month() == month } }
    val totals = remember(monthTxns) { Totals(monthTxns) }
    val cashAndBank = accounts.filter { it.type != AccountType.CREDIT_CARD }.sumOf { it.balance(txns) }
    val owed = -accounts.filter { it.type == AccountType.CREDIT_CARD }.sumOf { it.balance(txns) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Cash & bank now", formatMoney(cashAndBank), Modifier.weight(1f))
                StatTile("Credit cards owed", formatMoney(owed), Modifier.weight(1f))
            }
        }
        item { MonthSwitcher(month, onMonth) }
        item {
            InfoCard {
                Text(
                    (if (totals.net >= 0) "Saved " else "Overspent ") + formatMoney(kotlin.math.abs(totals.net)),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Income minus spending, interest and fees",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FigureLine("Income", totals.income)
                FigureLine("Spending", totals.spending)
                FigureLine("Interest", totals.interest)
                FigureLine("Fees", totals.fees)
            }
        }
        item {
            SectionTitle("Last 6 months")
            TrendChart(month, onMonth, txns)
        }
        item {
            SectionTitle("Where the money went")
            CategoryBars(monthTxns)
        }
        item {
            SectionTitle("Credit cards this month")
            CardSummary(accounts, monthTxns)
        }
        val biggest = monthTxns.filter { it.kind == TxnKind.EXPENSE }.sortedByDescending { it.amount }.take(5)
        if (biggest.isNotEmpty()) {
            item { SectionTitle("Biggest expenses") }
            items(biggest, key = { it.id }) { t ->
                CompactRow(
                    headline = t.description.ifBlank { t.category.ifBlank { t.kind.label } },
                    supporting = listOf(accounts.firstOrNull { it.id == t.accountId }?.name.orEmpty(), t.date.epochDayToDate().format(dayFormat))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    trailing = formatMoney(t.amount),
                )
            }
        }
    }
}

@Composable
private fun FigureLine(label: String, value: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f))
        Text(formatMoney(value), fontWeight = FontWeight.SemiBold)
    }
}

private val shortMonth = DateTimeFormatter.ofPattern("MMM")

/** Income beside spending for six months; tap a month to open it. */
@Composable
private fun TrendChart(selected: YearMonth, onMonth: (YearMonth) -> Unit, txns: List<MoneyTxn>) {
    val latest = maxOf(selected, YearMonth.from(today()))
    val months = (5 downTo 0).map { latest.minusMonths(it.toLong()) }
    val totals = remember(txns, latest) {
        val byMonth = txns.groupBy { it.month() }
        months.associateWith { Totals(byMonth[it].orEmpty()) }
    }
    val top = totals.values.maxOf { maxOf(it.income, it.totalOut) }.coerceAtLeast(1)
    val income = incomeColor()
    val spending = spendingColor()

    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(income)
            Text("Income", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(16.dp))
            LegendDot(spending)
            Text("Spending, interest & fees", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            months.forEach { m ->
                val t = totals.getValue(m)
                Column(
                    Modifier.weight(1f).clickable { onMonth(m) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TrendBar(t.income.toFloat() / top, income)
                        TrendBar(t.totalOut.toFloat() / top, spending)
                    }
                    Text(
                        m.format(shortMonth),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (m == selected) FontWeight.Bold else null,
                        color = if (m == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.padding(end = 6.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
}

@Composable
private fun TrendBar(fraction: Float, color: Color) {
    Box(Modifier.width(12.dp).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
        if (fraction > 0f) {
            Box(
                Modifier.width(12.dp).fillMaxHeight(fraction.coerceIn(0.01f, 1f))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(color),
            )
        }
    }
}

/** One colour: the bar length shows size, and the category sits in text beside it. */
@Composable
private fun CategoryBars(monthTxns: List<MoneyTxn>) {
    val spending = monthTxns.filter { it.kind == TxnKind.EXPENSE }
        .groupBy { it.category.ifBlank { "Uncategorised" } }
        .mapValues { (_, list) -> list.sumOf { it.amount } }
        .toList().sortedByDescending { it.second }
    // Long tails fold into one row so the chart stays readable.
    val shown = if (spending.size > 8) spending.take(7) + ("Everything else" to spending.drop(7).sumOf { it.second }) else spending
    val top = shown.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val total = shown.sumOf { it.second }.coerceAtLeast(1)
    val bar = spendingColor()

    InfoCard {
        if (shown.isEmpty()) {
            Text("No expenses this month.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        shown.forEach { (name, amount) ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${formatMoney(amount)} · ${(amount * 100 / total)}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box(
                Modifier.padding(top = 4.dp).fillMaxWidth(amount.toFloat() / top).height(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(bar),
            )
        }
    }
}

@Composable
private fun CardSummary(accounts: List<Account>, monthTxns: List<MoneyTxn>) {
    val cards = accounts.filter { it.type == AccountType.CREDIT_CARD }
    InfoCard {
        if (cards.isEmpty()) {
            Text("No credit cards yet. Add them under Accounts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        cards.forEach { card ->
            val t = Totals(monthTxns.filter { it.accountId == card.id })
            Text(card.displayName(), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            FigureLine("Spending", t.spending)
            FigureLine("Payments made", t.payments)
            FigureLine("Interest", t.interest)
            FigureLine("Fees", t.fees)
            Spacer(Modifier.height(8.dp))
        }
    }
}

private enum class EntryFilter(val label: String, val kinds: Set<TxnKind>) {
    ALL("All", TxnKind.entries.toSet()),
    SPENDING("Spending", setOf(TxnKind.EXPENSE, TxnKind.REFUND)),
    INCOME("Income", setOf(TxnKind.INCOME)),
    PAYMENTS("Payments", setOf(TxnKind.CARD_PAYMENT)),
    INTEREST("Interest & fees", setOf(TxnKind.INTEREST, TxnKind.FEE)),
    TRANSFERS("Transfers", setOf(TxnKind.TRANSFER)),
}

@Composable
private fun EntriesTab(month: YearMonth, onMonth: (YearMonth) -> Unit, accounts: List<Account>, txns: List<MoneyTxn>) {
    var filter by rememberSaveable { mutableStateOf(EntryFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var accountFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<MoneyTxn?>(null) }
    val names = accounts.associate { it.id to it.name }

    val shown = remember(txns, month, filter, query, accountFilter) {
        val q = query.trim()
        txns.filter { t ->
            // A search looks through every month.
            (q.isNotEmpty() || t.month() == month) && t.kind in filter.kinds &&
                (accountFilter == null || t.accountId == accountFilter || t.counterAccountId == accountFilter) &&
                (q.isEmpty() || t.description.contains(q, true) || t.category.contains(q, true))
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search all entries") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { MonthSwitcher(month, onMonth) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(EntryFilter.entries) { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(accounts.filter { !it.archived }) { a ->
                    FilterChip(
                        selected = accountFilter == a.id,
                        onClick = { accountFilter = if (accountFilter == a.id) null else a.id },
                        label = { Text(a.name) },
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            item { EmptyState(Icons.Outlined.ReceiptLong, "No entries here. Tap Add entry, or upload a card statement.") }
        }
        shown.groupBy { it.date }.forEach { (date, dayTxns) ->
            item(key = "d$date") { SectionTitle(date.epochDayToDate().format(dayFormat)) }
            items(dayTxns, key = { it.id }) { t ->
                val time = t.time.storedToTime()?.format(timeFormat)
                CompactRow(
                    headline = "${t.kind.emoji} ${t.description.ifBlank { t.category.ifBlank { t.kind.label } }}",
                    supporting = listOfNotNull(time, names[t.accountId], t.category.takeIf { it.isNotBlank() }).joinToString(" · "),
                    trailing = t.signedAmount(),
                    onClick = { editing = t },
                )
            }
        }
    }
    editing?.let { TxnEditor(it, it.kind) { editing = null } }
}

@Composable
private fun AccountsTab(accounts: List<Account>, txns: List<MoneyTxn>) {
    var addingType by remember { mutableStateOf<AccountType?>(null) }
    var editing by remember { mutableStateOf<Account?>(null) }
    var transferring by remember { mutableStateOf(false) }
    val active = accounts.filter { !it.archived }
    val cashAndBank = active.filter { it.type != AccountType.CREDIT_CARD }
    val cards = active.filter { it.type == AccountType.CREDIT_CARD }
    val hidden = accounts.filter { it.archived }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionTitle("Cash & bank")
            StatTile("Total", formatMoney(cashAndBank.sumOf { it.balance(txns) }))
        }
        items(cashAndBank, key = { it.id }) { a ->
            CompactRow(a.displayName(), a.type.label, formatMoney(a.balance(txns)), onClick = { editing = a })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { addingType = AccountType.BANK }, modifier = Modifier.weight(1f)) { Text("Add bank") }
                OutlinedButton(onClick = { transferring = true }, modifier = Modifier.weight(1f)) { Text("Transfer") }
            }
        }
        item {
            SectionTitle("Credit cards")
            StatTile("Total owed", formatMoney(-cards.sumOf { it.balance(txns) }))
        }
        items(cards, key = { it.id }) { a ->
            CompactRow(a.displayName(), "Owed", formatMoney(-a.balance(txns)), onClick = { editing = a })
        }
        item {
            FilledTonalButton(onClick = { addingType = AccountType.CREDIT_CARD }, modifier = Modifier.fillMaxWidth()) { Text("Add credit card") }
        }
        if (hidden.isNotEmpty()) {
            item { SectionTitle("No longer used") }
            items(hidden, key = { it.id }) { a ->
                CompactRow(a.displayName(), a.type.label, formatMoney(a.balance(txns)), onClick = { editing = a })
            }
        }
    }

    addingType?.let { AccountEditor(null, it) { addingType = null } }
    editing?.let { AccountEditor(it, it.type) { editing = null } }
    if (transferring) TxnEditor(null, TxnKind.TRANSFER) { transferring = false }
}
