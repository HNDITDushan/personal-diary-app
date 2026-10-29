package com.personal.mydiary.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personal.mydiary.data.AppDatabase
import com.personal.mydiary.data.SheetSettings
import com.personal.mydiary.data.SheetSync
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Background Google Sheet sync: hourly, and shortly after anything changes in the app. */
class SheetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SheetSettings(applicationContext)
        if (!settings.isConfigured || !settings.autoSync) return Result.success()
        return try {
            val result = SheetSync.sync(applicationContext, AppDatabase.get(applicationContext))
            settings.lastMessage = "${stamp()} · $result"
            Result.success()
        } catch (e: Exception) {
            settings.lastMessage = "${stamp()} · Sync failed: ${e.message}"
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val PERIODIC = "sheet-sync-hourly"
        private const val SOON = "sheet-sync-soon"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun stamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM, h:mm a"))

        /** Starts or stops the hourly sync to match the settings. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val settings = SheetSettings(context)
            if (settings.isConfigured && settings.autoSync) {
                val request = PeriodicWorkRequestBuilder<SheetSyncWorker>(1, TimeUnit.HOURS).setConstraints(network).build()
                wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                wm.cancelUniqueWork(PERIODIC)
            }
        }

        /** Syncs about a minute from now; repeated calls push the time back so a burst of edits syncs once. */
        fun syncSoon(context: Context, delaySeconds: Long = 60) {
            val settings = SheetSettings(context)
            if (!settings.isConfigured || !settings.autoSync) return
            val request = OneTimeWorkRequestBuilder<SheetSyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
