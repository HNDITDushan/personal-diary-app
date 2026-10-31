package com.personal.mydiary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TaskItem::class, Appointment::class, DiaryEntry::class, Occasion::class, Place::class, DayMood::class,
        Account::class, MoneyTxn::class, CardStatement::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun appointments(): AppointmentDao
    abstract fun entries(): EntryDao
    abstract fun occasions(): OccasionDao
    abstract fun places(): PlaceDao
    abstract fun moods(): MoodDao
    abstract fun accounts(): AccountDao
    abstract fun moneyTxns(): MoneyTxnDao
    abstract fun statements(): StatementDao

    companion object {
        const val NAME = "diary.db"

        /** Adds the Google Sheet sync columns; existing rows get a random uid. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                listOf("tasks", "appointments", "entries", "occasions", "places").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE $table SET uid = lower(hex(randomblob(16)))")
                }
                listOf("tasks", "appointments", "entries", "occasions", "places", "moods").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE $table SET updatedAt = $now")
                }
            }
        }

        /** Clears placeholder birth years (e.g. 1904) that early contact imports stored as real years. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE occasions SET year = NULL, updatedAt = ${System.currentTimeMillis()} " +
                        "WHERE contactKey IS NOT NULL AND year < ${ContactsSync.MIN_REAL_YEAR}"
                )
            }
        }

        /** Adds accounts, money entries and credit card statements. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `openingBalance` INTEGER NOT NULL, `last4` TEXT NOT NULL, `creditLimit` INTEGER, `archived` INTEGER NOT NULL, `uid` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `money_txns` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `date` INTEGER NOT NULL, `time` TEXT NOT NULL, `amount` INTEGER NOT NULL, `accountId` INTEGER NOT NULL, `counterAccountId` INTEGER, `category` TEXT NOT NULL, `description` TEXT NOT NULL, `statementId` INTEGER, `dedupeKey` TEXT, `uid` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_txns_date` ON `money_txns` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_txns_accountId` ON `money_txns` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_txns_statementId` ON `money_txns` (`statementId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `card_statements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `statementDate` INTEGER, `dueDate` INTEGER, `closingBalance` INTEGER, `minimumDue` INTEGER, `importedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_statements_accountId` ON `card_statements` (`accountId`)")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
