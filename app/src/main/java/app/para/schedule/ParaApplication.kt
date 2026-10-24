package app.para.schedule

import android.app.Application
import app.para.schedule.data.ScheduleRepository
import app.para.schedule.widget.WidgetRefreshWorker

class ParaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ScheduleRepository.get(this)
        WidgetRefreshWorker.ensureScheduled(this)
    }
}
