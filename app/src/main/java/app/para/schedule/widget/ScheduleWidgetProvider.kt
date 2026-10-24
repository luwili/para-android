package app.para.schedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import app.para.schedule.MainActivity
import app.para.schedule.R
import app.para.schedule.data.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class ScheduleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
        WidgetRefreshWorker.ensureScheduled(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, manager, id)
    }

    override fun onDisabled(context: Context) { WidgetRefreshWorker.ensureScheduled(context) }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(ACTION_REFRESH, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            val pending = goAsync()
            executor.execute {
                try { updateAll(context); WidgetRefreshWorker.ensureScheduled(context) } finally { pending.finish() }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "app.para.schedule.REFRESH_WIDGET"
        const val EXTRA_DATE = "schedule_date"
        private val executor = Executors.newSingleThreadExecutor()

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java)).forEach { render(context, manager, it) }
        }

        @Suppress("DEPRECATION") // Legacy collection refresh is required only on API 26–30.
        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val schedule = ScheduleRepository.readForWidget(context)
            val options = manager.getAppWidgetOptions(id)
            val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val width = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 260)
            val height = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
            manager.updateAppWidget(id, createViews(context, schedule, width, height, id))
            if (android.os.Build.VERSION.SDK_INT < 31) manager.notifyAppWidgetViewDataChanged(id, R.id.widget_lessons)
        }

        @Suppress("DEPRECATION") // RemoteViewsService is the API 26–30 collection fallback.
        internal fun createViews(context: Context, schedule: Schedule, width: Int, height: Int,
            id: Int = 0, now: java.time.ZonedDateTime = ScheduleEngine.now(schedule)): RemoteViews {
            val date = ScheduleEngine.widgetDate(schedule, now)
            val lessons = ScheduleEngine.lessonsOn(schedule, date)
            val tiny = height < 110
            val compact = width < 170 && !tiny
            val columns = tiny || (!compact && height < 170)
            val layout = when { tiny -> R.layout.widget_tiny; compact -> R.layout.widget_compact; columns -> R.layout.widget_columns; else -> R.layout.widget_full }
            val views = RemoteViews(context.packageName, layout)
            val title = if (!schedule.configured) "пара · твоя группа" else
                "${if (schedule.demo) "ПРИМЕР · " else ""}${schedule.profile.name}${if (compact || tiny) "" else " · ${date.format(DateTimeFormatter.ofPattern("d MMM", russian))}"}"
            views.setTextViewText(R.id.widget_title, title)
            val capacity = when {
                compact -> ((height - 60) / 43).coerceIn(1, 6)
                columns -> if (width < 170) 2 else 4
                else -> 12
            }
            val focus = if ((compact || columns) && date == now.toLocalDate() && lessons.size > capacity) {
                lessons.indexOfFirst { it.end > now.toLocalTime() }.coerceAtLeast(0)
            } else 0
            val visible = lessons.drop(focus).take(capacity)
            val full = !compact && !columns
            if (full) {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1)
                    visible.forEachIndexed { index, lesson ->
                        items.addItem(index.toLong(), WidgetRows.full(context, lesson, date, now))
                    }
                    views.setRemoteAdapter(R.id.widget_lessons, items.build())
                } else {
                    val service = Intent(context, ScheduleWidgetService::class.java)
                        .putExtra(EXTRA_DATE, date.toString())
                        .setData(android.net.Uri.parse("para://widget/$id/$date"))
                    views.setRemoteAdapter(R.id.widget_lessons, service)
                }
            } else {
                views.removeAllViews(R.id.widget_lessons)
                visible.forEach { lesson ->
                    val active = ScheduleEngine.isCurrent(lesson, date, now)
                    val row = RemoteViews(context.packageName, if (compact) R.layout.widget_compact_item else R.layout.widget_column_item)
                    row.setInt(R.id.lesson_root, "setBackgroundResource", if (active) R.drawable.widget_item_active else R.drawable.widget_item_background)
                    val abbr = ScheduleEngine.abbreviation(lesson.subject)
                    row.setTextViewText(R.id.lesson_subject, if (compact) "${lesson.start.format(timeFormat)}  $abbr${if (active) " · СЕЙЧАС" else ""}" else abbr)
                    if (!compact) row.setTextViewText(R.id.lesson_time, if (tiny) lesson.start.format(timeFormat)
                        else "${lesson.start.format(timeFormat)}\n–${lesson.end.format(timeFormat)}")
                    row.setTextViewText(R.id.lesson_detail, ScheduleEngine.cleanRoom(lesson.room).ifBlank { lesson.kind })
                    if (tiny) {
                        row.setViewVisibility(R.id.lesson_detail, View.GONE)
                        row.setViewPadding(R.id.lesson_root, 3, 0, 3, 0)
                        row.setTextViewTextSize(R.id.lesson_subject, android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    } else if (columns) {
                        val pad = (4 * context.resources.displayMetrics.density).toInt()
                        row.setViewPadding(R.id.lesson_root, pad, 0, pad, 0)
                    }
                    views.addView(R.id.widget_lessons, row)
                }
            }
            val empty = lessons.isEmpty()
            views.setViewVisibility(R.id.widget_empty, if (empty) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_empty, if (!schedule.configured) "Нажми и настрой свою группу" else "${if (date == now.toLocalDate()) "Сегодня" else "Завтра"} пар нет ☀")
            views.setViewVisibility(R.id.widget_lessons, if (empty) View.GONE else View.VISIBLE)
            val dayLabel = if (date == now.toLocalDate()) "Сегодня" else "Завтра · понедельник"
            val weekNumber = ScheduleEngine.weekNumber(schedule, date)
            val weekLabel = if (date < schedule.profile.semesterStart || date > schedule.profile.semesterEnd) "Вне учебного периода" else "$weekNumber неделя"
            if (!compact && !columns) views.setTextViewText(R.id.widget_subtitle, "$dayLabel · $weekLabel")
            views.setTextViewText(R.id.widget_footer, if (lessons.size > visible.size) "Ещё ${lessons.size - visible.size} · все пары в приложении" else
                if (compact) date.format(DateTimeFormatter.ofPattern("EEE, d MMM", russian)) else "$dayLabel · ${now.format(timeFormat)}")
            if (columns && !tiny) lessons.firstOrNull { ScheduleEngine.isCurrent(it, date, now) }?.let {
                val remainder = if (lessons.size > visible.size) "ещё ${lessons.size - visible.size}" else "до ${it.end.format(timeFormat)}"
                views.setTextViewText(R.id.widget_footer, "СЕЙЧАС · ${ScheduleEngine.abbreviation(it.subject)} · $remainder")
            }
            val open = Intent(context, MainActivity::class.java).putExtra(EXTRA_DATE, date.toString())
                .setData(android.net.Uri.parse("para://day/$date"))
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            if (full) {
                val mutableFlag = if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                views.setPendingIntentTemplate(R.id.widget_lessons, PendingIntent.getActivity(context, id + 100_000, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag))
            }
            val refresh = Intent(context, ScheduleWidgetProvider::class.java).setAction(ACTION_REFRESH)
            views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, id, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return views
        }

        private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    }
}
