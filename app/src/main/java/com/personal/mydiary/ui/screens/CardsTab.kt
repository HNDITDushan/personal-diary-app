package com.personal.mydiary.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.personal.mydiary.data.Account
import com.personal.mydiary.data.AccountType
import com.personal.mydiary.data.CardStatement
import com.personal.mydiary.data.MoneyTxn
import com.personal.mydiary.data.ReviewLine
import com.personal.mydiary.data.StatementImport
import com.personal.mydiary.data.StatementParser
import com.personal.mydiary.data.StatementPasswords
import com.personal.mydiary.data.TxnKind
import com.personal.mydiary.data.defaultCategories
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.ChoiceChips
import com.personal.mydiary.ui.EditorDialog
import com.personal.mydiary.ui.EmptyState
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.ui.SectionTitle
import com.personal.mydiary.ui.SuggestionRow
import com.personal.mydiary.ui.TextInput
import com.personal.mydiary.util.dayFormat
import com.personal.mydiary.util.epochDayToDate
import com.personal.mydiary.util.formatMoney
import com.personal.mydiary.util.shortDayFormat
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.Year
import java.time.ZoneId

/** A statement read successfully and waiting for the user to check it. */
private class PendingReview(val card: Account, val parsed: StatementParser.Result, val fileName: String, val lines: List<ReviewLine>)

/** What the password prompt is waiting on. */
private class PasswordRequest(val card: Account, val uri: Uri, val wrongAttempt: Boolean)

@Composable
fun CardsTab(accounts: List<Account>, txns: List<MoneyTxn>, statements: List<CardStatement>) {
    val app = diaryApp()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val passwords = remember { StatementPasswords(context) }
    val cards = accounts.filter { it.type == AccountType.CREDIT_CARD && !it.archived }

    var pickingFor by remember { mutableStateOf<Account?>(null) }
    var passwordRequest by remember { mutableStateOf<PasswordRequest?>(null) }
    var review by remember { mutableStateOf<PendingReview?>(null) }
    var unreadable by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CardStatement?>(null) }

    fun open(card: Account, uri: Uri, password: String, remember: Boolean) {
        busy = true
        scope.launch {
            try {
                val text = StatementImport.readText(context, uri, password)
                // Only a password that opened the file is worth keeping.
                if (remember && password.isNotEmpty()) passwords.set(card.uid, password)
                val parsed = StatementParser.parse(text)
                if (parsed.lines.isEmpty()) {
                    unreadable = text
                } else {
                    review = PendingReview(card, parsed, StatementImport.fileName(context, uri), StatementImport.prepareReview(app.db, card, parsed))
                }
            } catch (e: StatementImport.WrongPassword) {
                passwordRequest = PasswordRequest(card, uri, wrongAttempt = password.isNotEmpty())
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't read this PDF: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val card = pickingFor
        if (uri != null && card != null) open(card, uri, passwords.get(card.uid).orEmpty(), remember = false)
    }

    if (cards.isEmpty()) {
        EmptyState(Icons.Outlined.CreditCard, "Add your credit cards under the Accounts tab, then upload their statements here.")
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Reading statement…")
                    }
                }
            }
            items(cards, key = { it.id }) { card ->
                CardPanel(
                    card = card,
                    txns = txns.filter { it.accountId == card.id || it.counterAccountId == card.id },
                    statements = statements.filter { it.accountId == card.id },
                    uploadEnabled = !busy,
                    onUpload = {
                        pickingFor = card
                        picker.launch(arrayOf("application/pdf"))
                    },
                    onStatement = { removing = it },
                )
            }
        }
    }

    passwordRequest?.let { request ->
        PasswordDialog(
            request = request,
            onDismiss = { passwordRequest = null },
            onSubmit = { password, keep ->
                passwordRequest = null
                open(request.card, request.uri, password, keep)
            },
        )
    }

    review?.let { pending ->
        ReviewDialog(pending, onDismiss = { review = null }) { lines ->
            review = null
            scope.launch {
                val added = StatementImport.save(app.db, pending.card, pending.parsed, pending.fileName, lines)
                Toast.makeText(context, "Added $added entries to ${pending.card.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    unreadable?.let { text ->
        AlertDialog(
            onDismissRequest = { unreadable = null },
            title = { Text("No transactions found") },
            text = {
                Text(
                    if (text.isBlank()) "This PDF has no readable text; it is probably a scanned image, which this app can't read."
                    else "The PDF opened, but its layout wasn't recognised. Nothing was added. The app can be taught this layout: " +
                        "copy the text with long numbers hidden, and check it before sharing."
                )
            },
            confirmButton = { TextButton(onClick = { unreadable = null }) { Text("Close") } },
            dismissButton = {
                if (text.isNotBlank()) {
                    TextButton(onClick = {
                        val masked = text.replace(Regex("""\d[\d \-]{7,}\d"""), "#####")
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Statement layout", masked))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy text") }
                }
            },
        )
    }

    removing?.let { statement ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this statement?") },
            text = { Text("All entries added from it are removed too, so you can upload it again.") },
            confirmButton = {
                TextButton(onClick = {
                    app.launch {
                        moneyTxns().deleteForStatement(statement.id)
                        statements().delete(statement)
                    }
                    removing = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CardPanel(
    card: Account, txns: List<MoneyTxn>, statements: List<CardStatement>, uploadEnabled: Boolean,
    onUpload: () -> Unit, onStatement: (CardStatement) -> Unit,
) {
    val thisYear = Year.now().value
    val yearTxns = txns.filter { it.accountId == card.id && it.date.epochDayToDate().year == thisYear }
    fun sum(vararg kinds: TxnKind) = yearTxns.filter { it.kind in kinds }.sumOf { it.amount }
    val owed = -card.balance(txns)

    InfoCard {
        Text(card.displayName(), style = MaterialTheme.typography.titleMedium)
        Text("Owed ${formatMoney(owed)}", fontWeight = FontWeight.Bold)
        card.creditLimit?.takeIf { it > 0 }?.let { limit ->
            Text(
                "${owed * 100 / limit}% of ${formatMoney(limit)} limit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        Text("In $thisYear", style = MaterialTheme.typography.labelLarge)
        PanelFigure("Spending", sum(TxnKind.EXPENSE) - sum(TxnKind.REFUND))
        PanelFigure("Payments made", sum(TxnKind.CARD_PAYMENT))
        PanelFigure("Interest", sum(TxnKind.INTEREST))
        PanelFigure("Fees", sum(TxnKind.FEE))
        Spacer(Modifier.size(8.dp))
        FilledTonalButton(onClick = onUpload, enabled = uploadEnabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Upload statement (PDF)")
        }
        if (statements.isNotEmpty()) {
            SectionTitle("Statements")
            statements.forEach { s ->
                val date = s.statementDate?.epochDayToDate()?.format(dayFormat)
                    ?: Instant.ofEpochMilli(s.importedAt).atZone(ZoneId.systemDefault()).toLocalDate().format(dayFormat)
                val count = txns.count { it.statementId == s.id }
                Column(Modifier.fillMaxWidth().clickable { onStatement(s) }.padding(vertical = 6.dp)) {
                    Text(date, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            "$count entries",
                            s.closingBalance?.let { "Balance ${formatMoney(it)}" },
                            s.dueDate?.let { "Due ${it.epochDayToDate().format(shortDayFormat)}" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelFigure(label: String, value: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(formatMoney(value), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PasswordDialog(request: PasswordRequest, onDismiss: () -> Unit, onSubmit: (String, Boolean) -> Unit) {
    var password by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Statement password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${request.card.name}'s statement is locked. Enter its PDF password.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = request.wrongAttempt,
                    supportingText = if (request.wrongAttempt) { { Text("That password didn't open it. Try again.") } } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keep, onCheckedChange = { keep = it })
                    Text("Remember for this card (kept encrypted on this phone)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password, keep) }, enabled = password.isNotEmpty()) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Full-screen check of everything read from a statement; nothing is saved until Add is tapped. */
@Composable
private fun ReviewDialog(pending: PendingReview, onDismiss: () -> Unit, onImport: (List<ReviewLine>) -> Unit) {
    val lines = remember { mutableStateListOf<ReviewLine>().apply { addAll(pending.lines) } }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    val parsed = pending.parsed
    val chosen = lines.filter { it.selected }
    fun sum(vararg kinds: TxnKind) = chosen.filter { it.line.kind in kinds }.sumOf { it.line.amount }
    val duplicates = lines.count { it.duplicate }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text("Check ${pending.card.name} statement", style = MaterialTheme.typography.titleLarge)
                        Text(
                            listOfNotNull(
                                parsed.statementDate?.let { "Statement ${it.format(dayFormat)}" },
                                parsed.dueDate?.let { "Due ${it.format(dayFormat)}" },
                                parsed.minimumDue?.let { "Minimum ${formatMoney(it)}" },
                            ).joinToString("\n"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        InfoCard {
                            PanelFigure("Spending", sum(TxnKind.EXPENSE) - sum(TxnKind.REFUND))
                            PanelFigure("Payments", sum(TxnKind.CARD_PAYMENT))
                            PanelFigure("Interest", sum(TxnKind.INTEREST))
                            PanelFigure("Fees", sum(TxnKind.FEE))
                            parsed.closingBalance?.let { PanelFigure("Statement balance", it) }
                            Spacer(Modifier.size(4.dp))
                            ReconcileNote(parsed)
                            if (duplicates > 0) {
                                Text(
                                    "$duplicates already added before, so left unticked.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    itemsIndexed(lines) { i, item ->
                        Row(Modifier.fillMaxWidth().clickable { editingIndex = i }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = item.selected, onCheckedChange = { lines[i] = item.copy(selected = it) })
                            Column(Modifier.weight(1f)) {
                                Text(item.line.description, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOf(item.line.date.format(shortDayFormat), item.line.kind.label, item.line.category)
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(formatMoney(item.line.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { onImport(lines.toList()) }, enabled = chosen.isNotEmpty(), modifier = Modifier.weight(2f)) {
                        Text("Add ${chosen.size} entries")
                    }
                }
            }
        }
    }

    editingIndex?.let { i ->
        val item = lines[i]
        var kind by remember(i) { mutableStateOf(item.line.kind) }
        var category by remember(i) { mutableStateOf(item.line.category) }
        EditorDialog(
            title = item.line.description,
            onDismiss = { editingIndex = null },
            onSave = {
                lines[i] = item.copy(line = item.line.copy(kind = kind, category = if (kind == TxnKind.EXPENSE) category.trim() else ""))
                editingIndex = null
            },
        ) {
            ChoiceChips(listOf(TxnKind.EXPENSE, TxnKind.CARD_PAYMENT, TxnKind.INTEREST, TxnKind.FEE, TxnKind.REFUND), kind, { it.label }) { kind = it }
            if (kind == TxnKind.EXPENSE) {
                TextInput(category, { category = it }, "Category")
                SuggestionRow(category, defaultCategories) { category = it }
            }
        }
    }
}

/** Says whether the lines add up to the statement's own balance, the one check that nothing was missed. */
@Composable
private fun ReconcileNote(parsed: StatementParser.Result) {
    val difference = parsed.difference
    val (text, isProblem) = when {
        difference == null -> "The statement's balances weren't found, so please compare the totals with your PDF." to false
        difference == 0L -> "✓ These lines add up exactly to the statement's balance." to false
        else -> "⚠ These lines differ from the statement's balance by ${formatMoney(kotlin.math.abs(difference))}. Some lines may be missing or marked wrongly." to true
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
