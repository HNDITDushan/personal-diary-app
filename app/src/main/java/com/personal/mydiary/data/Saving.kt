package com.personal.mydiary.data

/*
 * App code saves through these instead of the DAOs' upsert so every change is stamped with
 * updatedAt, which the Google Sheet sync uses to tell which side changed a record.
 */

private fun now() = System.currentTimeMillis()

suspend fun TaskDao.save(item: TaskItem) = upsert(item.copy(updatedAt = now()))
suspend fun AppointmentDao.save(item: Appointment): Long = upsert(item.copy(updatedAt = now()))
suspend fun EntryDao.save(item: DiaryEntry) = upsert(item.copy(updatedAt = now()))
suspend fun OccasionDao.save(item: Occasion) = upsert(item.copy(updatedAt = now()))
suspend fun PlaceDao.save(item: Place) = upsert(item.copy(updatedAt = now()))
suspend fun MoodDao.save(item: DayMood) = upsert(item.copy(updatedAt = now()))
