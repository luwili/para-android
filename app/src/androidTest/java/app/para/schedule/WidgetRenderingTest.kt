package app.para.schedule

import android.graphics.Bitmap
import android.graphics.Canvas
import android.appwidget.AppWidgetHostView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.para.schedule.data.*
import app.para.schedule.widget.ScheduleWidgetProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class WidgetRenderingTest {
    private val date = LocalDate.of(2026, 10, 19)
    private val schedule = Schedule(
        GroupProfile("ТЕСТ-101", "Asia/Yekaterinburg", date, date.plusMonths(3)),
        (0..3).map { index -> Lesson(id = "lesson-$index", subject = listOf("Уголовное право", "Финансовое право", "Международное право", "Гражданский процесс")[index],
            start = LocalTime.of(8 + index * 2, 0), end = LocalTime.of(9 + index * 2, 20), room = "каб. ${310 + index}", color = index) },
        configured = true,
    )
    private val now = ZonedDateTime.of(date, LocalTime.of(8, 30), ZoneId.of("Asia/Yekaterinburg"))

    @Test fun layoutsInflateAndContainVisibleLessonsAtEverySize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            listOf(110 to 70, 110 to 150, 260 to 70, 260 to 120, 300 to 300).forEach { (width, height) ->
                val remote = ScheduleWidgetProvider.createViews(context, schedule, width, height, now = now)
                val parent = AppWidgetHostView(context)
                val view = remote.apply(context, parent)
                parent.addView(view)
                val density = context.resources.displayMetrics.density
                val w = (width * density).toInt()
                val h = (height * density).toInt()
                parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                parent.layout(0, 0, w, h)
                val lessons = view.findViewById<android.view.ViewGroup>(R.id.widget_lessons)
                val first = if (lessons is ListView) {
                    assertTrue("No collection rows at ${width}x$height", lessons.adapter.count > 0)
                    lessons.getChildAt(0) ?: lessons.adapter.getView(0, null, lessons).also {
                        it.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
                    }
                } else {
                    assertTrue("No lesson rendered at ${width}x$height", lessons.childCount > 0)
                    lessons.getChildAt(0)
                }
                val subject = first.findViewById<TextView>(R.id.lesson_subject)
                assertTrue("Subject collapsed at ${width}x$height", subject.measuredHeight > 0)
                assertTrue(subject.text.toString().isNotBlank())
                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_empty).visibility)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                parent.draw(Canvas(bitmap))
                File(context.filesDir, "widget-${width}x$height.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    @Test fun emptyDayHidesLessonsAndShowsExplicitMessage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val remote = ScheduleWidgetProvider.createViews(context, schedule.copy(lessons = emptyList()), 260, 120, now = now)
            val view = remote.apply(context, FrameLayout(context))
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_empty).visibility)
            assertEquals(View.GONE, view.findViewById<View>(R.id.widget_lessons).visibility)
            assertTrue(view.findViewById<TextView>(R.id.widget_empty).text.contains("пар нет"))
        }
    }
}
