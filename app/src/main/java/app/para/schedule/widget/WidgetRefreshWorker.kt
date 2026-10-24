package app.para.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class WidgetRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        ScheduleWidgetProvider.updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        fun ensureScheduled(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val hasWidgets = manager.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java)).isNotEmpty()
            val workManager = WorkManager.getInstance(context)
            if (hasWidgets) {
                val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build()
                workManager.enqueueUniquePeriodicWork("para-widget-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
            } else workManager.cancelUniqueWork("para-widget-refresh")
        }
    }
}
