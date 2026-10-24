package app.para.schedule.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.para.schedule.R
import app.para.schedule.data.*
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Android 8–11 fallback only. Small widgets never use a collection adapter. */
class ScheduleWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext, intent)

    private class Factory(private val context: Context, intent: Intent) : RemoteViewsFactory {
        private var schedule = Schedule()
        private var now = ScheduleEngine.now(schedule)
        private var lessons = emptyList<Lesson>()
        private val requestedDate = intent.getStringExtra(ScheduleWidgetProvider.EXTRA_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        private var date = requestedDate ?: now.toLocalDate()

        override fun onCreate() { onDataSetChanged() }
        override fun onDataSetChanged() {
            schedule = ScheduleRepository.readForWidget(context)
            now = ScheduleEngine.now(schedule)
            date = requestedDate ?: ScheduleEngine.widgetDate(schedule, now)
            lessons = ScheduleEngine.lessonsOn(schedule, date).take(12)
        }
        override fun getCount() = lessons.size
        override fun getViewAt(position: Int): RemoteViews? = lessons.getOrNull(position)?.let { WidgetRows.full(context, it, date, now) }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
        override fun onDestroy() { lessons = emptyList() }
    }
}

internal object WidgetRows {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    fun full(context: Context, lesson: Lesson, date: LocalDate, now: ZonedDateTime): RemoteViews {
        val active = ScheduleEngine.isCurrent(lesson, date, now)
        return RemoteViews(context.packageName, R.layout.widget_full_item).apply {
            setInt(R.id.lesson_root, "setBackgroundResource", if (active) R.drawable.widget_item_active else R.drawable.widget_item_background)
            setTextViewText(R.id.lesson_time, "${lesson.start.format(timeFormat)}–${lesson.end.format(timeFormat)}${if (active) " · СЕЙЧАС" else ""}")
            setTextViewText(R.id.lesson_subject, lesson.subject)
            setTextViewText(R.id.lesson_detail, listOf(lesson.room, lesson.teacher, lesson.kind).filter { it.isNotBlank() }.joinToString(" · "))
            setOnClickFillInIntent(R.id.lesson_root, Intent())
        }
    }
}
