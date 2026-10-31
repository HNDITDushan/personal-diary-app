package com.personal.mydiary.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY archived ASC, type ASC, name ASC")
    fun observeAll(): Flow<List<Account>>

    @Upsert suspend fun upsert(item: Account): Long
    @Delete suspend fun delete(item: Account)
    @Query("SELECT * FROM accounts") suspend fun getAll(): List<Account>
    @Insert suspend fun insertAll(items: List<Account>)
    @Query("DELETE FROM accounts") suspend fun clear()
}

/** An earlier description with the category it was last filed under. */
data class TxnSuggestion(val description: String, val category: String)

@Dao
interface MoneyTxnDao {
    @Query("SELECT * FROM money_txns ORDER BY date DESC, time DESC, id DESC")
    fun observeAll(): Flow<List<MoneyTxn>>

    @Query(
        "SELECT description, category, MAX(date) AS latest FROM money_txns WHERE description != '' AND kind = :kind " +
            "GROUP BY description COLLATE NOCASE ORDER BY COUNT(*) DESC, latest DESC LIMIT 100"
    )
    fun observeSuggestions(kind: String): Flow<List<TxnSuggestion>>

    /** Category of the latest entry per description, so imports reuse how you filed a shop before. */
    @Query(
        "SELECT description, category, MAX(date) AS latest FROM money_txns WHERE category != '' AND description != '' " +
            "GROUP BY description COLLATE NOCASE"
    )
    suspend fun knownCategories(): List<TxnSuggestion>

    @Query("SELECT dedupeKey FROM money_txns WHERE accountId = :accountId AND dedupeKey IS NOT NULL")
    suspend fun importedKeys(accountId: Long): List<String>

    @Query("SELECT COUNT(*) FROM money_txns WHERE accountId = :accountId OR counterAccountId = :accountId")
    suspend fun countForAccount(accountId: Long): Int

    @Query("DELETE FROM money_txns WHERE statementId = :statementId")
    suspend fun deleteForStatement(statementId: Long)

    @Upsert suspend fun upsert(item: MoneyTxn)
    @Delete suspend fun delete(item: MoneyTxn)
    @Query("SELECT * FROM money_txns") suspend fun getAll(): List<MoneyTxn>
    @Insert suspend fun insertAll(items: List<MoneyTxn>)
    @Query("DELETE FROM money_txns") suspend fun clear()
}

@Dao
interface StatementDao {
    @Query("SELECT * FROM card_statements ORDER BY statementDate DESC, importedAt DESC")
    fun observeAll(): Flow<List<CardStatement>>

    @Insert suspend fun insert(item: CardStatement): Long
    @Delete suspend fun delete(item: CardStatement)
    @Query("SELECT * FROM card_statements") suspend fun getAll(): List<CardStatement>
    @Insert suspend fun insertAll(items: List<CardStatement>)
    @Query("DELETE FROM card_statements") suspend fun clear()
}
