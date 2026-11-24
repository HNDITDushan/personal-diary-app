package com.personal.mydiary.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong

/** Where a bill came from. */
enum class BillSource { EBILL, PHOTO, MANUAL }

/** One shopping trip. Amounts are whole cents, like the rest of the money data. */
@Serializable
@Entity(tableName = "shopping_bills", indices = [Index("date")])
data class ShoppingBill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    /** "HH:mm", or empty. */
    val time: String = "",
    /** The shop, e.g. "Keells". */
    val store: String = "",
    /** The branch, e.g. "Ambalangoda". */
    val branch: String = "",
    val gross: Long = 0,
    val discount: Long = 0,
    val net: Long = 0,
    /** How it was paid, as the bill says, e.g. "Credit Card-PEO". */
    val payment: String = "",
    val source: BillSource = BillSource.MANUAL,
    /** The e-bill link it was read from. */
    val url: String = "",
    /** Identifies a store receipt so the same e-bill can't be added twice. */
    val dedupeKey: String? = null,
    /** The Money expense saved with this bill, by its uid. */
    val moneyTxnUid: String? = null,
    val uid: String = newUid(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val title get() = listOf(store, branch).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Shopping" }
}

/** One line of a bill. [unitPrice] is per item, or per kg for a [weighed] item. */
@Serializable
@Entity(tableName = "shopping_items", indices = [Index("billUid"), Index("itemKey")])
data class ShoppingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billUid: String,
    /** The shop's product code, when the bill shows one. */
    val code: String = "",
    val name: String,
    /** Ties together purchases of the same product across bills; see [ShoppingPrices.keyFor]. */
    val itemKey: String,
    val unitPrice: Long,
    val qty: Double = 1.0,
    val amount: Long,
    /** Sold by weight, so [unitPrice] is per kg. */
    val weighed: Boolean = false,
    val uid: String = newUid(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A purchase of one item together with the bill it's on, for price history. */
data class Purchase(
    val itemKey: String,
    val name: String,
    val unitPrice: Long,
    val qty: Double,
    val amount: Long,
    val weighed: Boolean,
    val itemUid: String,
    val billUid: String,
    val date: Long,
    val time: String,
    val store: String,
    val branch: String,
)

@Dao
interface ShoppingDao {
    @Query("SELECT * FROM shopping_bills ORDER BY date DESC, time DESC, id DESC")
    fun observeBills(): Flow<List<ShoppingBill>>

    @Query(
        "SELECT i.itemKey, i.name, i.unitPrice, i.qty, i.amount, i.weighed, i.uid AS itemUid, b.uid AS billUid, " +
            "b.date, b.time, b.store, b.branch FROM shopping_items i JOIN shopping_bills b ON b.uid = i.billUid",
    )
    fun observePurchases(): Flow<List<Purchase>>

    @Query("SELECT * FROM shopping_items WHERE billUid = :billUid ORDER BY id")
    fun observeItems(billUid: String): Flow<List<ShoppingItem>>

    @Query("SELECT * FROM shopping_bills WHERE dedupeKey = :key LIMIT 1")
    suspend fun billWithKey(key: String): ShoppingBill?

    /** Each known item's key and name, to match new lines to what was bought before. */
    @Query("SELECT itemKey, name, code FROM shopping_items GROUP BY itemKey")
    suspend fun knownItems(): List<KnownItem>

    @Upsert suspend fun upsertBill(bill: ShoppingBill)
    @Upsert suspend fun upsertItems(items: List<ShoppingItem>)
    @Query("DELETE FROM shopping_items WHERE billUid = :billUid") suspend fun deleteItemsOf(billUid: String)
    @Query("DELETE FROM shopping_bills WHERE uid = :uid") suspend fun deleteBill(uid: String)

    /** Replaces a bill and all its lines in one go. */
    @Transaction
    suspend fun saveBill(bill: ShoppingBill, items: List<ShoppingItem>) {
        val now = System.currentTimeMillis()
        upsertBill(bill.copy(updatedAt = now))
        deleteItemsOf(bill.uid)
        upsertItems(items.map { it.copy(id = 0, billUid = bill.uid, updatedAt = now) })
    }

    @Transaction
    suspend fun deleteBillAndItems(uid: String) {
        deleteItemsOf(uid)
        deleteBill(uid)
    }

    @Query("SELECT * FROM shopping_bills") suspend fun getAllBills(): List<ShoppingBill>
    @Query("SELECT * FROM shopping_items") suspend fun getAllItems(): List<ShoppingItem>
    @Insert suspend fun insertBills(items: List<ShoppingBill>)
    @Insert suspend fun insertItems(items: List<ShoppingItem>)
    @Query("DELETE FROM shopping_bills") suspend fun clearBills()
    @Query("DELETE FROM shopping_items") suspend fun clearItems()
}

data class KnownItem(val itemKey: String, val name: String, val code: String)

/** How an item's price moved since it was last bought. */
data class PriceChange(val previous: Purchase, val current: Purchase) {
    val diff get() = current.unitPrice - previous.unitPrice
    /** Whole-number percentage, rounded. */
    val percent get() = if (previous.unitPrice == 0L) 0 else (diff * 100.0 / previous.unitPrice).roundToLong().toInt()
}

object ShoppingPrices {
    /** Upper case, punctuation removed, single spaces: "Carrot  (Local)" -> "CARROT LOCAL". Sinhala letters and signs are kept. */
    fun normalize(name: String): String =
        name.uppercase().replace(Regex("[^\\p{L}\\p{M}\\p{N}%]+"), " ").trim().replace(Regex("\\s+"), " ")

    /** How alike two names are, from 0 to 1, by the share of letters that match (edit distance). */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a).replace(" ", "")
        val y = normalize(b).replace(" ", "")
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var prev = IntArray(y.length + 1) { it }
        for (i in 1..x.length) {
            val cur = IntArray(y.length + 1)
            cur[0] = i
            for (j in 1..y.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return 1.0 - prev[y.length].toDouble() / maxOf(x.length, y.length)
    }

    /**
     * The name of something bought before that a photo's reading is close to, so a name corrected once is used
     * again: "බඛෝෞංචි" becomes "බෝංචි" after you've saved it that way. Null when nothing is close enough.
     */
    fun closestKnown(name: String, known: List<KnownItem>): String? {
        if (normalize(name).length < 2) return null
        return known.map { it.name to similarity(name, it.name) }.filter { it.second >= 0.6 }.maxByOrNull { it.second }?.first
    }

    /**
     * The key that ties purchases of one product together. A shop's product code is the surest match,
     * and a line without one (from a photo or typed in) joins an earlier purchase with the same name.
     */
    fun keyFor(code: String, name: String, known: List<KnownItem>): String {
        val norm = normalize(name)
        if (code.isNotBlank()) {
            return known.firstOrNull { it.code == code }?.itemKey ?: "#$code"
        }
        return known.firstOrNull { normalize(it.name) == norm }?.itemKey ?: norm
    }

    /** "KOTMALE YOGHURT 6S 480G" -> "Kotmale Yoghurt 6s 480g" */
    fun displayName(name: String): String =
        name.trim().lowercase().split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private val order = compareBy<Purchase>({ it.date }, { it.time }, { it.billUid })

    /** Every purchase of each item, oldest first. */
    fun history(purchases: List<Purchase>): Map<String, List<Purchase>> =
        purchases.groupBy { it.itemKey }.mapValues { (_, list) -> list.sortedWith(order) }

    /** The change from the purchase before [p] on an earlier bill, or null when it's the first time or the price is the same. */
    fun changeFor(p: Purchase, history: List<Purchase>): PriceChange? {
        val previous = history.lastOrNull { it.billUid != p.billUid && order.compare(it, p) < 0 } ?: return null
        return PriceChange(previous, p).takeIf { it.diff != 0L }
    }

    /** Each item's latest change, biggest moves first; items whose price hasn't changed are left out. */
    fun latestChanges(history: Map<String, List<Purchase>>): List<PriceChange> =
        history.values.mapNotNull { list -> list.lastOrNull()?.let { changeFor(it, list) } }
            .sortedByDescending { abs(it.percent) }

    /** Lines that are only shop paperwork, like a 1-cent bag and its refund. */
    fun isNoise(unitPrice: Long, qty: Double): Boolean = unitPrice < 100 || qty <= 0.0
}
