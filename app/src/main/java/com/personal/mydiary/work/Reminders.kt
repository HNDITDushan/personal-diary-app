package com.personal.mydiary.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.personal.mydiary.MainActivity
import com.personal.mydiary.R
import com.personal.mydiary.data.AppDatabase
import com.personal.mydiary.data.Appointment
import com.personal.mydiary.data.OccasionType
import com.personal.mydiary.util.endMillis
import com.personal.mydiary.util.millisToDateTime
import com.personal.mydiary.util.nextOccurrence
import com.personal.mydiary.util.startMillis
import com.personal.mydiary.util.timeFormat
import com.personal.mydiary.util.today
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object Notifications {
    private const val CHANNEL = "diary_reminders"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Appointments, birthdays, tasks and monthly report"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}

/** Posts one notification described by its input data. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Notifications.show(
            applicationContext,
            inputData.getInt(KEY_ID, 0),
            inputData.getString(KEY_TITLE).orEmpty(),
            inputData.getString(KEY_TEXT).orEmpty(),
        )
        return Result.success()
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_TITLE = "title"
        const val KEY_TEXT = "text"
    }
}

object AppointmentReminders {
    private fun workName(id: Long) = "appointment-$id"

    fun schedule(context: Context, appointment: Appointment) {
        val wm = WorkManager.getInstance(context)
        val remindAt = appointment.startAt - appointment.remindMinutesBefore * 60_000L
        val delay = remindAt - System.currentTimeMillis()
        if (appointment.remindMinutesBefore <= 0 || delay <= 0) {
            wm.cancelUniqueWork(workName(appointment.id))
            return
        }
        val text = buildString {
            append("At ").append(appointment.startAt.millisToDateTime().format(timeFormat))
            if (appointment.location.isNotBlank()) append(" · ").append(appointment.location)
        }
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    ReminderWorker.KEY_ID to (100_000 + appointment.id).toInt(),
                    ReminderWorker.KEY_TITLE to appointment.title,
                    ReminderWorker.KEY_TEXT to text,
                )
            )
            .build()
        wm.enqueueUniqueWork(workName(appointment.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }
}

/** Runs each morning: today's special days, tasks due, appointments, and month-end report nudge. */
class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val day = today()

        val occasions = db.occasions().getAll().filter { nextOccurrence(it.month, it.day, day) == day }
        if (occasions.isNotEmpty()) {
            val text = occasions.joinToString("\n") { o ->
                val years = o.year?.let { day.year - it }
                when (o.type) {
                    OccasionType.BIRTHDAY -> "🎂 ${o.name}'s birthday" + (years?.let { " (turns $it)" } ?: "")
                    OccasionType.ANNIVERSARY -> "💞 ${o.name} anniversary" + (years?.let { " ($it years)" } ?: "")
                    OccasionType.SPECIAL -> "⭐ ${o.name}"
                }
            }
            Notifications.show(applicationContext, 1, "Special day today", text)
        }

        val tasks = db.tasks().dueOn(day.toEpochDay())
        val appointments = db.appointments().between(day.startMillis(), day.endMillis())
        if (tasks.isNotEmpty() || appointments.isNotEmpty()) {
            val parts = listOfNotNull(
                tasks.size.takeIf { it > 0 }?.let { "$it task(s) due" },
                appointments.size.takeIf { it > 0 }?.let { "$it appointment(s)" },
            )
            Notifications.show(applicationContext, 2, "Your day", parts.joinToString(" · "))
        }

        if (day == day.withDayOfMonth(day.lengthOfMonth())) {
            Notifications.show(
                applicationContext, 3, "Your month in review",
                "Rate today, then open the monthly report to see how happy you were and the good you did.",
            )
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(8, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("daily-summary", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
