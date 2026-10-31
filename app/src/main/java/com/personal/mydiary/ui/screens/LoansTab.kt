package com.personal.mydiary.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.personal.mydiary.data.Account
import com.personal.mydiary.data.AccountType
import com.personal.mydiary.data.Loan
import com.personal.mydiary.data.MoneyTxn
import com.personal.mydiary.data.TxnKind
import com.personal.mydiary.data.balance
import com.personal.mydiary.data.interestPaid
import com.personal.mydiary.data.monthlyInterest
import com.personal.mydiary.data.nextDue
import com.personal.mydiary.data.project
import com.personal.mydiary.data.save
import com.personal.mydiary.data.suggestedInstallment
import com.personal.mydiary.diaryApp
import com.personal.mydiary.ui.ChoiceChips
import com.personal.mydiary.ui.DateField
import com.personal.mydiary.ui.EditorDialog
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.ui.SectionTitle
import com.personal.mydiary.ui.TextInput
import com.personal.mydiary.util.dayFormat
import com.personal.mydiary.util.daysFromToday
import com.personal.mydiary.util.epochDayToDate
import com.personal.mydiary.util.formatMoney
import com.personal.mydiary.util.moneyInput
import com.personal.mydiary.util.monthFormat
import com.personal.mydiary.util.parseMoney
import com.personal.mydiary.util.relativeDay
import com.personal.mydiary.util.today
import java.time.YearMonth

@Composable
fun LoansTab(accounts: List<Account>, txns: List<MoneyTxn>, loans: List<Loan>) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Loan?>(null) }
    var paying by remember { mutableStateOf<Loan?>(null) }
    var editingPayment by remember { mutableStateOf<MoneyTxn?>(null) }
    val active = loans.filter { !it.closed }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (active.isNotEmpty()) {
            item {
                InfoCard {
                    Text("Total owed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatMoney(active.sumOf { it.balance(txns) }), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Installments ${formatMoney(active.filter { it.balance(txns) > 0 }.sumOf { it.installment })} a month",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(loans, key = { it.id }) { loan ->
            LoanPanel(
                loan, txns, accounts,
                onEdit = { editing = loan },
                onPay = { paying = loan },
                onPayment = { editingPayment = it },
            )
        }
        item {
            FilledTonalButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add loan") }
        }
    }

    if (adding) LoanEditor(null, accounts, txns) { adding = false }
    editing?.let { LoanEditor(it, accounts, txns) { editing = null } }
    paying?.let { LoanPaymentEditor(it, null, accounts, txns) { paying = null } }
    editingPayment?.let { TxnEditor(it, it.kind) { editingPayment = null } }
}

@Composable
private fun LoanPanel(
    loan: Loan, txns: List<MoneyTxn>, accounts: List<Account>,
    onEdit: () -> Unit, onPay: () -> Unit, onPayment: (MoneyTxn) -> Unit,
) {
    val balance = loan.balance(txns)
    val due = loan.nextDue(txns)
    val projection = if (balance > 0) loan.project(balance) else null
    val payments = txns.filter { it.loanId == loan.id && it.kind == TxnKind.LOAN_PAYMENT }
    var showPayments by remember { mutableStateOf(false) }

    InfoCard {
        Column(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
            Text(loan.name + if (loan.closed) " (closed)" else "", style = MaterialTheme.typography.titleMedium)
            val rate = if (loan.annualRate > 0) "${loan.annualRate}% ${if (loan.flatRate) "flat" else "reducing"}" else null
            listOfNotNull(loan.lender.takeIf { it.isNotBlank() }, rate).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (balance > 0) "Owed ${formatMoney(balance)}" else "🎉 Paid off",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        loan.originalAmount?.takeIf { it > 0 }?.let { original ->
            val paid = ((original - balance).toFloat() / original).coerceIn(0f, 1f)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { paid }, modifier = Modifier.fillMaxWidth())
            Text(
                "${(paid * 100).toInt()}% of ${formatMoney(original)} repaid",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (loan.installment > 0) LoanFigure("Installment", formatMoney(loan.installment))
        LoanFigure("Interest paid so far", formatMoney(loan.interestPaid(txns)))
        if (projection != null) {
            val end = YearMonth.from(due ?: today()).plusMonths(projection.months - 1L)
            LoanFigure("Months left", "${projection.months} (to ${end.format(monthFormat)})")
            LoanFigure("Interest still to pay", formatMoney(projection.totalInterest))
        } else if (balance > 0 && loan.installment > 0) {
            Text(
                "This installment doesn't cover the monthly interest, so the loan would never be repaid. Check the rate and installment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        due?.let {
            val late = daysFromToday(it) < 0
            Text(
                if (late) "⚠ Installment was due ${it.format(dayFormat)}" else "Next due: ${relativeDay(it)}",
                fontWeight = FontWeight.SemiBold,
                color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (balance > 0 && !loan.closed) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onPay, modifier = Modifier.fillMaxWidth()) { Text("Record payment") }
        }
        if (payments.isNotEmpty()) {
            TextButton(onClick = { showPayments = !showPayments }) {
                Text((if (showPayments) "Hide" else "Show") + " ${payments.size} payment" + if (payments.size == 1) "" else "s")
            }
        }
        if (showPayments) {
            val names = accounts.associate { it.id to it.name }
            payments.forEach { p ->
                Column(Modifier.fillMaxWidth().clickable { onPayment(p) }.padding(vertical = 6.dp)) {
                    Row {
                        Text(p.date.epochDayToDate().format(dayFormat), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(formatMoney(p.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        listOfNotNull(
                            "Principal ${formatMoney(p.amount - p.interestPart)}", "Interest ${formatMoney(p.interestPart)}", names[p.accountId],
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
private fun LoanFigure(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoanEditor(initial: Loan?, accounts: List<Account>, txns: List<MoneyTxn>, onDismiss: () -> Unit) {
    val app = diaryApp()
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var lender by rememberSaveable { mutableStateOf(initial?.lender.orEmpty()) }
    var balance by rememberSaveable { mutableStateOf(initial?.openingBalance?.let(::moneyInput).orEmpty()) }
    var original by rememberSaveable { mutableStateOf(initial?.originalAmount?.let(::moneyInput).orEmpty()) }
    var rate by rememberSaveable { mutableStateOf(initial?.annualRate?.takeIf { it > 0 }?.toString().orEmpty()) }
    var flat by remember { mutableStateOf(initial?.flatRate ?: false) }
    var installment by rememberSaveable { mutableStateOf(initial?.installment?.takeIf { it > 0 }?.let(::moneyInput).orEmpty()) }
    var months by rememberSaveable { mutableStateOf("") }
    var due by remember { mutableStateOf(initial?.firstDueDate?.epochDayToDate() ?: today().plusMonths(1)) }
    var closed by remember { mutableStateOf(initial?.closed ?: false) }
    var receivedInto by remember { mutableStateOf<Account?>(null) }
    val hasPayments = initial != null && txns.any { it.loanId == initial.id && it.kind == TxnKind.LOAN_PAYMENT }

    val balanceCents = parseMoney(balance)
    val originalCents = if (original.isBlank()) null else parseMoney(original)
    val rateValue = if (rate.isBlank()) 0.0 else rate.toDoubleOrNull()
    val installmentCents = if (installment.isBlank()) 0L else parseMoney(installment)
    val suggestion = months.toIntOrNull()?.takeIf { it > 0 }?.let {
        suggestedInstallment(balanceCents ?: 0, rateValue ?: 0.0, it, flat, originalCents)
    }?.takeIf { it > 0 }
    val valid = name.isNotBlank() && balanceCents != null && balanceCents > 0 && rateValue != null && rateValue >= 0 &&
        installmentCents != null && installmentCents >= 0 && (original.isBlank() || originalCents != null)

    EditorDialog(
        title = if (initial == null) "New loan" else "Edit loan",
        onDismiss = onDismiss,
        saveEnabled = valid,
        onSave = {
            val item = (initial ?: Loan(name = "", openingBalance = 0, firstDueDate = 0)).copy(
                name = name.trim(), lender = lender.trim(), openingBalance = balanceCents ?: 0, originalAmount = originalCents,
                annualRate = rateValue ?: 0.0, flatRate = flat, installment = installmentCents ?: 0,
                firstDueDate = due.toEpochDay(), closed = closed,
            )
            val account = receivedInto
            app.launch {
                val newId = loans().save(item)
                if (initial == null && account != null) {
                    moneyTxns().save(
                        MoneyTxn(
                            kind = TxnKind.LOAN_RECEIVED, date = today().toEpochDay(), amount = originalCents ?: item.openingBalance,
                            accountId = account.id, loanId = newId, description = "${item.name} received", category = "Loan",
                        )
                    )
                }
            }
            onDismiss()
        },
        onDelete = initial?.let {
            {
                app.launch {
                    if (moneyTxns().countForLoan(it.id) > 0) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context, "This loan has entries. Tick \"Closed\" to hide it from your totals instead.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        loans().delete(it)
                    }
                }
                onDismiss()
            }
        },
    ) {
        TextInput(name, { name = it }, "Loan name, e.g. Car loan")
        TextInput(lender, { lender = it }, "Bank or lender (optional)")
        AmountInput(balance, { balance = it }, if (hasPayments) "Amount owed when this loan was added" else "Amount still to pay now")
        AmountInput(original, { original = it }, "Amount first borrowed (optional)")
        OutlinedTextField(
            value = rate,
            onValueChange = { rate = it },
            label = { Text("Interest rate a year") },
            suffix = { Text("%") },
            singleLine = true,
            isError = rateValue == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Interest type", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(listOf(false, true), flat, { if (it) "Flat" else "Reducing balance" }) { flat = it }
        AmountInput(installment, { installment = it }, "Monthly installment")
        OutlinedTextField(
            value = months,
            onValueChange = { months = it.filter(Char::isDigit).take(4) },
            label = { Text("Months left, to work out the installment (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        suggestion?.let {
            TextButton(onClick = { installment = moneyInput(it) }) { Text("Use ${formatMoney(it)} a month") }
        }
        Text(if (hasPayments) "First installment due" else "Next installment due", style = MaterialTheme.typography.labelLarge)
        DateField("Due date", due, { if (it != null) due = it })
        if (initial == null) {
            Text("Money received into (only for a new loan)", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(accounts.filter { !it.archived && it.type != AccountType.CREDIT_CARD }, receivedInto, { it?.name.orEmpty() }) { picked ->
                receivedInto = if (picked?.id == receivedInto?.id) null else picked
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = closed, onCheckedChange = { closed = it })
                Text("Closed (hide from totals and reminders)")
            }
        }
    }
}

/** Opens the payment editor for an existing entry, e.g. from the Timeline, once its loan has loaded. */
@Composable
fun LoanPaymentEditorFor(initial: MoneyTxn, onDismiss: () -> Unit) {
    val db = diaryApp().db
    val loans by remember { db.loans().observeAll() }.collectAsState(initial = null)
    val accounts by remember { db.accounts().observeAll() }.collectAsState(initial = null)
    val txns by remember { db.moneyTxns().observeAll() }.collectAsState(initial = null)
    val loan = loans?.firstOrNull { it.id == initial.loanId } ?: return
    LoanPaymentEditor(loan, initial, accounts ?: return, txns ?: return, onDismiss)
}

@Composable
private fun LoanPaymentEditor(loan: Loan, initial: MoneyTxn?, accounts: List<Account>, txns: List<MoneyTxn>, onDismiss: () -> Unit) {
    val app = diaryApp()
    // What's owed without this payment, so editing one doesn't count it twice.
    val owedBefore = remember { loan.balance(txns.filter { it.id != initial?.id }) }
    val suggestedInterest = remember { loan.monthlyInterest(owedBefore) }
    val suggestedAmount = remember {
        if (loan.installment > 0) minOf(loan.installment, owedBefore + suggestedInterest) else owedBefore + suggestedInterest
    }
    var amount by rememberSaveable { mutableStateOf(moneyInput(initial?.amount ?: suggestedAmount)) }
    var interest by rememberSaveable { mutableStateOf(moneyInput(initial?.interestPart ?: suggestedInterest)) }
    var date by remember { mutableStateOf(initial?.date?.epochDayToDate() ?: today()) }
    val lastAccount = remember { txns.firstOrNull { it.loanId == loan.id && it.kind == TxnKind.LOAN_PAYMENT }?.accountId }
    var accountId by remember { mutableStateOf(initial?.accountId ?: lastAccount) }

    val choices = accounts.filter { (!it.archived && it.type != AccountType.CREDIT_CARD) || it.id == initial?.accountId }
    val account = choices.firstOrNull { it.id == accountId } ?: choices.firstOrNull()
    val amountCents = parseMoney(amount)
    val interestCents = if (interest.isBlank()) 0L else parseMoney(interest)
    val valid = amountCents != null && amountCents > 0 && interestCents != null && interestCents in 0..amountCents && account != null

    EditorDialog(
        title = "${loan.name} payment",
        onDismiss = onDismiss,
        saveEnabled = valid,
        onSave = {
            val item = (initial ?: MoneyTxn(kind = TxnKind.LOAN_PAYMENT, date = 0, amount = 0, accountId = 0, loanId = loan.id)).copy(
                amount = amountCents ?: 0, interestPart = interestCents ?: 0, date = date.toEpochDay(), accountId = account?.id ?: 0,
                description = initial?.description ?: "${loan.name} payment", category = "Loan",
            )
            app.launch { moneyTxns().save(item) }
            onDismiss()
        },
        onDelete = initial?.let { { app.launch { moneyTxns().delete(it) }; onDismiss() } },
    ) {
        AmountInput(amount, { amount = it }, "Amount paid")
        AmountInput(interest, { interest = it }, "Interest part")
        Text(
            "The interest is an estimate. Change it to match your bank's figure if you have it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (valid) {
            val principal = (amountCents ?: 0) - (interestCents ?: 0)
            Text("Principal ${formatMoney(principal)} · Owed after ${formatMoney((owedBefore - principal).coerceAtLeast(0))}", fontWeight = FontWeight.SemiBold)
        }
        Text("Paid from", style = MaterialTheme.typography.labelLarge)
        if (account != null) ChoiceChips(choices, account, { it.name }) { accountId = it.id }
        DateField("Date", date, { if (it != null) date = it })
    }
}
