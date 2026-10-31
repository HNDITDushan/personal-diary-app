package com.personal.mydiary.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class AccountType { CASH, BANK, CREDIT_CARD }

/**
 * What a money entry did. Transfers and card payments only move money between your own
 * accounts, so they never count as income or spending. Likewise money received from a loan
 * isn't income, and only the interest part of a loan payment is a cost.
 */
enum class TxnKind { INCOME, EXPENSE, TRANSFER, CARD_PAYMENT, INTEREST, FEE, REFUND, LOAN_PAYMENT, LOAN_RECEIVED }

/** All amounts are whole cents; a credit card's balance is negative while money is owed on it. */
@Serializable
@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    val openingBalance: Long = 0,
    /** Last four card or account digits, only to tell accounts apart. */
    val last4: String = "",
    val creditLimit: Long? = null,
    val archived: Boolean = false,
    val uid: String = newUid(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "money_txns", indices = [Index("date"), Index("accountId"), Index("statementId"), Index("loanId")])
data class MoneyTxn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: TxnKind,
    val date: Long,
    /** "HH:mm", or empty when no time was given. */
    val time: String = "",
    /** Always positive; [kind] gives the direction. */
    val amount: Long,
    val accountId: Long,
    /** The other side of a transfer (receiving account) or card payment (paying account), when known. */
    val counterAccountId: Long? = null,
    val category: String = "",
    val description: String = "",
    val statementId: Long? = null,
    /** Identifies an imported line so the same statement can't add it twice. */
    val dedupeKey: String? = null,
    /** The loan this entry belongs to, and how much of a [TxnKind.LOAN_PAYMENT] was interest. */
    val loanId: Long? = null,
    val interestPart: Long = 0,
    val uid: String = newUid(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "card_statements", indices = [Index("accountId")])
data class CardStatement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val fileName: String = "",
    val statementDate: Long? = null,
    val dueDate: Long? = null,
    val closingBalance: Long? = null,
    val minimumDue: Long? = null,
    val importedAt: Long = System.currentTimeMillis(),
)

/** The change this entry makes to the balance of [account]. */
fun MoneyTxn.effectOn(account: Long): Long {
    var change = 0L
    if (accountId == account) {
        change += when (kind) {
            TxnKind.INCOME, TxnKind.REFUND, TxnKind.CARD_PAYMENT, TxnKind.LOAN_RECEIVED -> amount
            TxnKind.EXPENSE, TxnKind.INTEREST, TxnKind.FEE, TxnKind.TRANSFER, TxnKind.LOAN_PAYMENT -> -amount
        }
    }
    if (counterAccountId == account) {
        change += when (kind) {
            TxnKind.TRANSFER -> amount
            TxnKind.CARD_PAYMENT -> -amount
            else -> 0
        }
    }
    return change
}

val defaultCategories = listOf(
    "Food & groceries", "Fuel", "Transport", "Bills & utilities", "Shopping", "Health",
    "Education", "Travel", "Entertainment", "Insurance", "Family", "Donations", "Other",
)

val incomeCategories = listOf("Salary", "Business", "Interest earned", "Gift", "Other income")

private val categoryKeywords = listOf(
    "Food & groceries" to listOf("KEELLS", "CARGILLS", "FOOD", "SUPERMARKET", "ARPICO", "LAUGFS SUPER", "SPAR", "RESTAURANT", "CAFE", "BAKERY", "PIZZA", "KFC", "MCDONALD", "BURGER"),
    "Fuel" to listOf("FUEL", "PETROL", "CEYPETCO", "FILLING STATION", "LANKA IOC", "LIOC", "SHED"),
    "Transport" to listOf("UBER", "PICKME", "TAXI", "BUS ", "RAILWAY", "PARKING", "HIGHWAY"),
    "Bills & utilities" to listOf("DIALOG", "MOBITEL", "SLT", "HUTCH", "AIRTEL", "CEB", "LECO", "WATER BOARD", "NWSDB", "ELECTRICITY", "TELECOM", "INTERNET", "NETFLIX", "SPOTIFY", "GOOGLE", "APPLE.COM"),
    "Shopping" to listOf("DARAZ", "ODEL", "AMAZON", "ALIEXPRESS", "ABANS", "SINGER", "SOFTLOGIC", "FASHION", "NOLIMIT", "HOUSE OF FASHION", "COOL PLANET", "EBAY"),
    "Health" to listOf("PHARMACY", "HOSPITAL", "ASIRI", "NAWALOKA", "HEMAS", "DURDANS", "CLINIC", "DENTAL", "MEDICAL", "LANKA HOSPITALS"),
    "Education" to listOf("SCHOOL", "COLLEGE", "UNIVERSITY", "TUITION", "COURSERA", "UDEMY"),
    "Travel" to listOf("AIRLINE", "AIRWAYS", "HOTEL", "BOOKING.COM", "AGODA", "AIRBNB", "RESORT"),
    "Entertainment" to listOf("CINEMA", "SCOPE", "PVR", "STEAM", "PLAYSTATION"),
    "Insurance" to listOf("INSURANCE", "ASSURANCE", "AIA", "UNION ASSURANCE", "CEYLINCO"),
)

/** Best guess at a spending category from a statement description; empty when unsure. */
fun guessCategory(description: String): String {
    val text = description.uppercase()
    return categoryKeywords.firstOrNull { (_, words) -> words.any { it in text } }?.first.orEmpty()
}
