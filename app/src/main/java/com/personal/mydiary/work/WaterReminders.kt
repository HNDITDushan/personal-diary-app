package com.personal.mydiary.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personal.mydiary.R
import com.personal.mydiary.data.AppDatabase
import com.personal.mydiary.data.Hydration
import com.personal.mydiary.data.WaterLog
import com.personal.mydiary.data.WaterSettings
import com.personal.mydiary.data.formatMl
import com.personal.mydiary.data.save
import com.personal.mydiary.util.storedToTime
import com.personal.mydiary.util.toStored
import com.personal.mydiary.util.today
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object WaterReminders {
    private const val WORK = "water-reminder"
    const val NOTIFICATION_ID = 5

    /** Starts or stops the reminders to match the current settings. */
    fun apply(context: Context) {
        val settings = WaterSettings(context)
        val wm = WorkManager.getInstance(context)
        if (!settings.remindersOn) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<WaterReminderWorker>(settings.intervalMinutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
            .setInitialDelay(settings.intervalMinutes.toLong(), TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun drinkAction(context: Context, glassMl: Int): NotificationCompat.Action {
        val intent = Intent(context, WaterLogReceiver::class.java).putExtra(WaterLogReceiver.EXTRA_ML, glassMl)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action(R.drawable.ic_notification, "💧 I drank ${formatMl(glassMl)}", pending)
    }
}

/** Nudges only within waking hours, while behind on the goal, and when nothing was drunk recently. */
class WaterReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = WaterSettings(applicationContext)
        val now = LocalTime.now()
        if (!settings.remindersOn || now.hour < settings.startHour || now.hour >= settings.endHour) return Result.success()
        val water = AppDatabase.get(applicationContext).water()
        val day = today().toEpochDay()
        val total = water.totalOn(day)
        if (total >= settings.goalMl) return Result.success()
        val last = water.lastOn(day)?.time?.storedToTime()
        if (last != null && !last.isAfter(now) && Duration.between(last, now).toMinutes() < settings.intervalMinutes - 15) {
            return Result.success()
        }
        val left = Hydration.glassesLeft(total, settings.goalMl, settings.glassMl)
        Notifications.show(
            applicationContext, WaterReminders.NOTIFICATION_ID, "💧 Time for some water",
            "${formatMl(total)} of ${formatMl(settings.goalMl)} so far · about $left more glass${if (left == 1) "" else "es"} to go",
            listOf(WaterReminders.drinkAction(applicationContext, settings.glassMl)),
        )
        return Result.success()
    }
}

/** The notification's "I drank" button: logs the glass without opening the app. */
class WaterLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ml = intent.getIntExtra(EXTRA_ML, 0).takeIf { it > 0 } ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val water = AppDatabase.get(context).water()
                val day = today().toEpochDay()
                water.save(WaterLog(date = day, time = LocalTime.now().toStored(), amountMl = ml))
                val total = water.totalOn(day)
                val goal = WaterSettings(context).goalMl
                NotificationManagerCompat.from(context).cancel(WaterReminders.NOTIFICATION_ID)
                if (total >= goal) {
                    Notifications.show(context, WaterReminders.NOTIFICATION_ID, "🎉 Water goal reached!", "${formatMl(total)} today. Well done!")
                }
                SheetSyncWorker.syncSoon(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ML = "ml"
    }
}
