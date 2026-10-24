package app.para.schedule

import android.app.Application
import app.para.schedule.data.ScheduleRepository
import app.para.schedule.widget.WidgetRefreshWorker

class ParaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else runCatching {
            java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000')
        }.getOrDefault(packageName)
        if (process != packageName) return
        ScheduleRepository.get(this)
        WidgetRefreshWorker.ensureScheduled(this)
    }
}
