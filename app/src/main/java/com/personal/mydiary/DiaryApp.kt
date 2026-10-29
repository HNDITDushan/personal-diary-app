package com.personal.mydiary

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.personal.mydiary.data.AppDatabase
import com.personal.mydiary.work.DailyWorker
import com.personal.mydiary.work.Notifications
import com.personal.mydiary.work.SheetSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DiaryApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.get(this) }

    /** Outlives screens so a save is never cancelled by navigating away. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun launch(block: suspend AppDatabase.() -> Unit) {
        scope.launch {
            db.block()
            SheetSyncWorker.syncSoon(this@DiaryApp)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        DailyWorker.schedule(this)
        SheetSyncWorker.schedule(this)
        SheetSyncWorker.syncSoon(this, delaySeconds = 5)
    }
}

@Composable
fun diaryApp(): DiaryApp = LocalContext.current.applicationContext as DiaryApp
