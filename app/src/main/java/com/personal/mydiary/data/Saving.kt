package com.personal.mydiary.data

/*
 * App code saves through these instead of the DAOs' upsert so every change is stamped with
 * updatedAt, which the Google Sheet sync uses to tell which side changed a record.
 */

private fun now() = System.currentTimeMillis()

/** Every install has a Cash account to record money against. */
suspend fun ensureCashAccount(db: AppDatabase) {
    val accounts = db.accounts()
    if (accounts.getAll().none { it.type == AccountType.CASH }) {
        accounts.save(Account(name = "Cash", type = AccountType.CASH))
    }
}

suspend fun TaskDao.save(item: TaskItem) = upsert(item.copy(updatedAt = now()))
suspend fun AppointmentDao.save(item: Appointment): Long = upsert(item.copy(updatedAt = now()))
suspend fun EntryDao.save(item: DiaryEntry) = upsert(item.copy(updatedAt = now()))
suspend fun OccasionDao.save(item: Occasion) = upsert(item.copy(updatedAt = now()))
suspend fun PlaceDao.save(item: Place) = upsert(item.copy(updatedAt = now()))
suspend fun MoodDao.save(item: DayMood) = upsert(item.copy(updatedAt = now()))
suspend fun AccountDao.save(item: Account): Long = upsert(item.copy(updatedAt = now()))
suspend fun MoneyTxnDao.save(item: MoneyTxn) = upsert(item.copy(updatedAt = now()))
suspend fun LoanDao.save(item: Loan): Long = upsert(item.copy(updatedAt = now()))
