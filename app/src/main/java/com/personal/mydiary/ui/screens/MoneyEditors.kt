package com.personal.mydiary.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.personal.mydiary.data.Account
import com.personal.mydiary.data.AccountType
import com.personal.mydiary.data.MoneyTxn
import com.personal.mydiary.data.StatementPasswords
import com.personal.mydiary.data.TxnKind
import com.personal.mydiary.data.defaultCategories
import com.personal.mydiary.data.incomeCategories
import com.personal.mydiary.data.save
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.ChoiceChips
import com.personal.mydiary.ui.DateField
import com.personal.mydiary.ui.EditorDialog
import com.personal.mydiary.ui.SuggestionRow
import com.personal.mydiary.ui.TextInput
import com.personal.mydiary.ui.TimeField
import com.personal.mydiary.util.epochDayToDate
import com.personal.mydiary.util.formatMoney
import com.personal.mydiary.util.moneyInput
import com.personal.mydiary.util.parseMoney
import com.personal.mydiary.util.storedToTime
import com.personal.mydiary.util.toStored
import com.personal.mydiary.util.today
import java.time.LocalTime

val TxnKind.label: String
    get() = when (this) {
        TxnKind.INCOME -> "Income"
        TxnKind.EXPENSE -> "Expense"
        TxnKind.TRANSFER -> "Transfer"
        TxnKind.CARD_PAYMENT -> "Card payment"
        TxnKind.INTEREST -> "Interest"
        TxnKind.FEE -> "Fee"
        TxnKind.REFUND -> "Refund"
        TxnKind.LOAN_PAYMENT -> "Loan payment"
        TxnKind.LOAN_RECEIVED -> "Loan received"
    }

val TxnKind.emoji: String
    get() = when (this) {
        TxnKind.INCOME -> "💰"
        TxnKind.EXPENSE -> "💸"
        TxnKind.TRANSFER -> "🔁"
        TxnKind.CARD_PAYMENT -> "💳"
        TxnKind.INTEREST -> "📈"
        TxnKind.FEE -> "🧾"
        TxnKind.REFUND -> "↩️"
        TxnKind.LOAN_PAYMENT -> "🏦"
        TxnKind.LOAN_RECEIVED -> "🏦"
    }

val AccountType.emoji: String
    get() = when (this) {
        AccountType.CASH -> "💵"
        AccountType.BANK -> "🏦"
        AccountType.CREDIT_CARD -> "💳"
    }

val AccountType.label: String
    get() = when (this) {
        AccountType.CASH -> "Cash"
        AccountType.BANK -> "Bank account"
        AccountType.CREDIT_CARD -> "Credit card"
    }

/** Amount with + or − for money gained or spent; moves between your own accounts have no sign. */
fun MoneyTxn.signedAmount(): String = when (kind) {
    TxnKind.INCOME, TxnKind.REFUND, TxnKind.LOAN_RECEIVED -> "+" + formatMoney(amount)
    TxnKind.EXPENSE, TxnKind.INTEREST, TxnKind.FEE, TxnKind.LOAN_PAYMENT -> "−" + formatMoney(amount)
    TxnKind.TRANSFER, TxnKind.CARD_PAYMENT -> formatMoney(amount)
}

@Composable
fun AmountInput(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        prefix = { Text("Rs ") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun TxnEditor(initial: MoneyTxn?, defaultKind: TxnKind, onDismiss: () -> Unit) {
    if (initial?.kind == TxnKind.LOAN_PAYMENT) {
        LoanPaymentEditorFor(initial, onDismiss)
        return
    }
    val app = diaryApp()
    val allAccounts by remember { app.db.accounts().observeAll() }.collectAsState(initial = emptyList())
    val accounts = allAccounts.filter { !it.archived || it.id == initial?.accountId || it.id == initial?.counterAccountId }

    var kind by remember { mutableStateOf(initial?.kind ?: defaultKind) }
    var amount by rememberSaveable { mutableStateOf(initial?.amount?.let(::moneyInput).orEmpty()) }
    var description by rememberSaveable { mutableStateOf(initial?.description.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(initial?.category.orEmpty()) }
    var date by remember { mutableStateOf(initial?.date?.epochDayToDate() ?: today()) }
    var time by remember {
        mutableStateOf(initial?.time?.storedToTime() ?: if (initial == null) LocalTime.now().withSecond(0).withNano(0) else null)
    }
    var accountId by remember { mutableStateOf(initial?.accountId) }
    var counterId by remember { mutableStateOf(initial?.counterAccountId) }

    // A card payment goes onto a card; everything else starts from an account you pay out of.
    val cards = accounts.filter { it.type == AccountType.CREDIT_CARD }
    val mainChoices = if (kind == TxnKind.CARD_PAYMENT) cards else if (kind == TxnKind.TRANSFER) accounts.filter { it.type != AccountType.CREDIT_CARD } else accounts
    val selectedAccount = mainChoices.firstOrNull { it.id == accountId } ?: mainChoices.firstOrNull()
    val counterChoices = when (kind) {
        TxnKind.TRANSFER, TxnKind.CARD_PAYMENT -> accounts.filter { it.type != AccountType.CREDIT_CARD && it.id != selectedAccount?.id }
        else -> emptyList()
    }
    val selectedCounter = counterChoices.firstOrNull { it.id == counterId }
    val needsCounter = kind == TxnKind.TRANSFER
    val cents = parseMoney(amount)

    val suggestions by remember(kind) { app.db.moneyTxns().observeSuggestions(kind.name) }.collectAsState(initial = emptyList())
    val kinds = (listOf(TxnKind.EXPENSE, TxnKind.INCOME, TxnKind.TRANSFER, TxnKind.CARD_PAYMENT, TxnKind.INTEREST, TxnKind.FEE, TxnKind.REFUND) + listOfNotNull(initial?.kind)).distinct()

    EditorDialog(
        title = if (initial == null) "New entry" else "Edit entry",
        onDismiss = onDismiss,
        saveEnabled = cents != null && cents > 0 && selectedAccount != null && (!needsCounter || selectedCounter != null),
        onSave = {
            val item = (initial ?: MoneyTxn(kind = kind, date = 0, amount = 0, accountId = 0)).copy(
                kind = kind, amount = cents ?: 0, date = date.toEpochDay(), time = time?.toStored().orEmpty(),
                accountId = selectedAccount?.id ?: 0, counterAccountId = selectedCounter?.id,
                description = description.trim(), category = category.trim(),
            )
            app.launch { moneyTxns().save(item) }
            onDismiss()
        },
        onDelete = initial?.let { { app.launch { moneyTxns().delete(it) }; onDismiss() } },
    ) {
        ChoiceChips(kinds, kind, { it.label }) { kind = it }
        AmountInput(amount, { amount = it }, "Amount")
        TextInput(description, { description = it }, "Description")
        SuggestionRow(description, suggestions.map { it.description }) { picked ->
            description = picked
            suggestions.firstOrNull { it.description == picked }?.let { if (category.isBlank()) category = it.category }
        }
        if (kind == TxnKind.EXPENSE || kind == TxnKind.INCOME || kind == TxnKind.REFUND) {
            TextInput(category, { category = it }, "Category")
            SuggestionRow(category, if (kind == TxnKind.INCOME) incomeCategories else defaultCategories) { category = it }
        }
        Text(
            when (kind) {
                TxnKind.TRANSFER -> "From"
                TxnKind.CARD_PAYMENT -> "Card"
                else -> "Account"
            },
            style = MaterialTheme.typography.labelLarge,
        )
        if (selectedAccount != null) {
            ChoiceChips(mainChoices, selectedAccount, { it.name }) { accountId = it.id }
        } else {
            Text("Add a credit card under Money → Accounts first.", color = MaterialTheme.colorScheme.error)
        }
        if (counterChoices.isNotEmpty()) {
            Text(if (kind == TxnKind.TRANSFER) "To" else "Paid from (tap again to leave out)", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(counterChoices, selectedCounter, { it?.name.orEmpty() }) { picked ->
                counterId = if (picked?.id == counterId) null else picked?.id
            }
        } else if (needsCounter) {
            Text("Add another account to transfer to.", color = MaterialTheme.colorScheme.error)
        }
        DateField("Date", date, { if (it != null) date = it })
        TimeField("No time", time, { time = it }, clearable = true)
    }
}

@Composable
fun AccountEditor(initial: Account?, defaultType: AccountType, onDismiss: () -> Unit) {
    val app = diaryApp()
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var type by remember { mutableStateOf(initial?.type ?: defaultType) }
    var last4 by rememberSaveable { mutableStateOf(initial?.last4.orEmpty()) }
    var archived by remember { mutableStateOf(initial?.archived ?: false) }
    var excludeFromTotal by remember { mutableStateOf(initial?.excludeFromTotal ?: false) }
    var balance by rememberSaveable {
        mutableStateOf(initial?.openingBalance?.takeIf { it != 0L }?.let { moneyInput(if (initial.type == AccountType.CREDIT_CARD) -it else it) }.orEmpty())
    }
    var limit by rememberSaveable { mutableStateOf(initial?.creditLimit?.let(::moneyInput).orEmpty()) }
    val passwords = remember { StatementPasswords(context) }
    // Kept out of saved instance state, and a saved password is never shown again.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var hasSavedPassword by remember { mutableStateOf(initial != null && passwords.has(initial.uid)) }
    val isCard = type == AccountType.CREDIT_CARD
    val balanceCents = if (balance.isBlank()) 0L else parseMoney(balance)

    EditorDialog(
        title = if (initial == null) "New account" else "Edit account",
        onDismiss = onDismiss,
        saveEnabled = name.isNotBlank() && balanceCents != null && (limit.isBlank() || parseMoney(limit) != null),
        onSave = {
            val opening = balanceCents ?: 0
            val item = (initial ?: Account(name = "", type = type)).copy(
                name = name.trim(), type = type, last4 = last4.trim().takeLast(4),
                openingBalance = if (isCard) -opening else opening,
                creditLimit = if (isCard) parseMoney(limit) else null,
                archived = archived,
                excludeFromTotal = !isCard && excludeFromTotal,
            )
            when {
                !isCard || !hasSavedPassword && password.isEmpty() -> passwords.clear(item.uid)
                password.isNotEmpty() -> passwords.set(item.uid, password)
            }
            app.launch { accounts().save(item) }
            onDismiss()
        },
        onDelete = initial?.let {
            {
                app.launch {
                    if (moneyTxns().countForAccount(it.id) > 0) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context, "This account has entries. Hide it instead with \"No longer used\".", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        accounts().delete(it)
                        StatementPasswords(context).clear(it.uid)
                    }
                }
                onDismiss()
            }
        },
    ) {
        ChoiceChips(AccountType.entries, type, { it.label }) { type = it }
        TextInput(
            name, { name = it },
            if (isCard) "Card name, e.g. HNB Visa" else if (type == AccountType.BANK) "Bank account, e.g. BOC Savings" else "Name",
        )
        TextInput(last4, { last4 = it.filter(Char::isDigit).take(4) }, "Last 4 digits (optional)")
        AmountInput(
            balance, { balance = it },
            if (isCard) "Amount owed before your first entry" else "Balance before your first entry",
        )
        if (isCard) {
            AmountInput(limit, { limit = it }, "Credit limit (optional)")
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (hasSavedPassword) "New statement password" else "Statement PDF password (optional)") },
                supportingText = { Text("Kept encrypted on this phone, and used to open this card's statements.") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    if (password.isNotEmpty()) {
                        TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasSavedPassword) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔒 Password saved", Modifier.weight(1f))
                    TextButton(onClick = { hasSavedPassword = false }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (!isCard) {
            Row(Modifier.clickable { excludeFromTotal = !excludeFromTotal }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = excludeFromTotal, onCheckedChange = { excludeFromTotal = it })
                Text("Don't count in my total (e.g. someone else's money)")
            }
        }
        if (initial != null) {
            Row(Modifier.clickable { archived = !archived }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = archived, onCheckedChange = { archived = it })
                Text("No longer used (hide from lists)")
            }
        }
    }
}
