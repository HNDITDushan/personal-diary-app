package com.personal.mydiary.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.personal.mydiary.data.ScheduleRow
import com.personal.mydiary.data.repaymentSchedule
import com.personal.mydiary.data.suggestedInstallment
import com.personal.mydiary.ui.ChoiceChips
import com.personal.mydiary.ui.DiaryScaffold
import com.personal.mydiary.ui.HeroCard
import com.personal.mydiary.ui.InfoCard
import com.personal.mydiary.ui.SectionTitle
import com.personal.mydiary.util.formatMoney
import com.personal.mydiary.util.parseMoney

private const val MAX_MONTHS = 600

/** Works out a loan on paper; nothing here is saved or changes your real loans. */
@Composable
fun LoanCalculatorScreen(onBack: () -> Unit) {
    var findMonths by rememberSaveable { mutableStateOf(false) }
    var amount by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("") }
    var flat by rememberSaveable { mutableStateOf(false) }
    var months by rememberSaveable { mutableStateOf("") }
    var installmentText by rememberSaveable { mutableStateOf("") }
    var showSchedule by rememberSaveable { mutableStateOf(false) }

    val amountCents = parseMoney(amount)?.takeIf { it > 0 }
    val rateValue = (if (rate.isBlank()) 0.0 else rate.toDoubleOrNull())?.takeIf { it in 0.0..100.0 }
    val monthCount = months.toIntOrNull()?.takeIf { it in 1..MAX_MONTHS }

    fun installmentFor(isFlat: Boolean): Long? = when {
        amountCents == null || rateValue == null -> null
        findMonths -> parseMoney(installmentText)?.takeIf { it > 0 }
        else -> monthCount?.let { suggestedInstallment(amountCents, rateValue, it, isFlat, amountCents) }
    }
    fun scheduleFor(isFlat: Boolean): List<ScheduleRow> {
        val installment = installmentFor(isFlat) ?: return emptyList()
        return repaymentSchedule(amountCents ?: return emptyList(), rateValue ?: 0.0, isFlat, installment, monthCount.takeIf { !findMonths })
    }

    val installment = installmentFor(flat)
    val schedule = scheduleFor(flat)
    val other = scheduleFor(!flat)

    DiaryScaffold(title = "Loan calculator", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                InfoCard {
                    Text("What do you want to find?", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(listOf(false, true), findMonths, { if (it) "Months to repay" else "Monthly installment" }) { findMonths = it }
                    Spacer(Modifier.height(8.dp))
                    AmountInput(amount, { amount = it }, "Loan amount")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rate,
                        onValueChange = { rate = it },
                        label = { Text("Interest rate a year") },
                        suffix = { Text("%") },
                        singleLine = true,
                        isError = rateValue == null,
                        supportingText = if (rateValue == null) { { Text("Enter a rate from 0 to 100") } } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    if (findMonths) {
                        AmountInput(installmentText, { installmentText = it }, "What you can pay a month")
                    } else {
                        OutlinedTextField(
                            value = months,
                            onValueChange = { months = it.filter(Char::isDigit).take(3) },
                            label = { Text("Number of months") },
                            singleLine = true,
                            isError = months.isNotEmpty() && monthCount == null,
                            supportingText = if (months.isNotEmpty() && monthCount == null) { { Text("Enter 1 to $MAX_MONTHS months") } } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Interest type", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(listOf(false, true), flat, { if (it) "Flat" else "Reducing balance" }) { flat = it }
                }
            }

            if (schedule.isNotEmpty() && installment != null) {
                val totalInterest = schedule.sumOf { it.interest }
                val totalPaid = schedule.sumOf { it.payment }
                item {
                    HeroCard {
                        Text("Monthly installment", style = MaterialTheme.typography.labelLarge)
                        Text(formatMoney(installment), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(12.dp))
                        ResultLine("Months", "${schedule.size}" + yearsLabel(schedule.size))
                        ResultLine("Total interest", formatMoney(totalInterest))
                        ResultLine("Total repayment", formatMoney(totalPaid))
                        if (findMonths && schedule.last().payment != installment) {
                            ResultLine("Last payment", formatMoney(schedule.last().payment))
                        }
                    }
                }
                if (other.isNotEmpty()) {
                    item {
                        val otherInterest = other.sumOf { it.interest }
                        InfoCard {
                            Text("Compare the two interest types", style = MaterialTheme.typography.titleSmall)
                            ResultLine("This one (${typeName(flat)})", formatMoney(totalInterest))
                            ResultLine(typeName(!flat).replaceFirstChar { it.uppercase() }, formatMoney(otherInterest))
                            Text(
                                "Total interest at the same ${rate.ifBlank { "0" }}% rate. A flat rate charges interest on the full amount every month, so it costs more than the same reducing rate.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                item {
                    TextButton(onClick = { showSchedule = !showSchedule }) {
                        Text((if (showSchedule) "Hide" else "Show") + " month-by-month schedule")
                    }
                }
                if (showSchedule) {
                    item { SectionTitle("Schedule") }
                    items(schedule, key = { it.month }) { row -> ScheduleLine(row) }
                }
                item {
                    Text(
                        "An estimate with a fixed rate and equal monthly payments. Your bank's figures may differ slightly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (findMonths && amountCents != null && rateValue != null && installment != null) {
                item {
                    InfoCard {
                        Text(
                            "This payment doesn't cover the monthly interest, so the loan would never be repaid. Try a larger amount.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun typeName(flat: Boolean) = if (flat) "flat" else "reducing balance"

private fun yearsLabel(months: Int): String {
    if (months < 12) return ""
    val years = months / 12
    val rest = months % 12
    return " (" + (if (years == 1) "1 year" else "$years years") + (if (rest > 0) " $rest mo" else "") + ")"
}

@Composable
private fun ResultLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ScheduleLine(row: ScheduleRow) {
    InfoCard {
        Row(Modifier.fillMaxWidth()) {
            Text("Month ${row.month}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(formatMoney(row.payment), style = MaterialTheme.typography.titleSmall)
        }
        Text(
            "Principal ${formatMoney(row.principal)} · Interest ${formatMoney(row.interest)}\nLeft to pay ${formatMoney(row.balance)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
