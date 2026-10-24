package app.para.schedule

import android.graphics.Bitmap
import android.graphics.Canvas
import android.appwidget.AppWidgetHostView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.content.res.Configuration
import android.view.ViewGroup
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

    @Test fun allCardBordersAndPrimaryTextStayInsideAtLargeFontsAndBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val stress = schedule.copy(lessons = schedule.lessons.mapIndexed { index, lesson ->
            lesson.copy(subject = if (index == 0) "Уголовно-процессуальное право и специальная профессиональная подготовка" else lesson.subject,
                room = "каб. 402 (Достоевского 131, учебный корпус университета)", teacher = "Преподаватель с очень длинным именем и фамилией")
        })
        instrumentation.runOnMainSync {
            for (fontScale in listOf(1f, 1.3f, 1.8f, 2f)) for (night in listOf(false, true)) {
                val config = Configuration(base.resources.configuration).apply {
                    this.fontScale = fontScale
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = base.createConfigurationContext(config)
                for ((width, height) in listOf(110 to 70, 110 to 150, 170 to 170, 260 to 70, 260 to 110, 260 to 120, 260 to 170, 260 to 210, 300 to 300, 600 to 300)) {
                    val label = "${width}x$height font=$fontScale night=$night"
                    val host = AppWidgetHostView(context)
                    val view = ScheduleWidgetProvider.createViews(context, stress, width, height, now = now).apply(context, host)
                    host.addView(view)
                    val density = context.resources.displayMetrics.density
                    val w = (width * density).toInt()
                    val h = (height * density).toInt()
                    host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    host.layout(0, 0, w, h)
                    val body = view.findViewById<LinearLayout>(R.id.widget_lessons)
                    assertTrue("Empty body: $label", body.childCount > 0)
                    for (index in 0 until body.childCount) {
                        val card = body.getChildAt(index) as ViewGroup
                        assertTrue("Top border cropped: $label", card.top >= body.paddingTop)
                        assertTrue("Bottom border cropped: $label", card.bottom <= body.height - body.paddingBottom)
                        assertTrue("Side border cropped: $label", card.left >= body.paddingLeft && card.right <= body.width - body.paddingRight)
                        for (child in 0 until card.childCount) {
                            val text = card.getChildAt(child) as? TextView ?: continue
                            if (text.visibility != View.VISIBLE) continue
                            assertTrue("Text above frame: $label ${text.text}", text.top >= card.paddingTop)
                            assertTrue("Text below frame: $label ${text.text}", text.bottom <= card.height - card.paddingBottom)
                            assertTrue("Text vertically clipped: $label ${text.text}", text.layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                        }
                    }
                    if ((width == 300 && height == 300) || (width == 260 && height == 120)) {
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        host.draw(Canvas(bitmap))
                        File(base.filesDir, "adaptive-widget-${width}x$height-f$fontScale-${if (night) "dark" else "light"}.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    @Test fun roomIsRemovedBeforePrimaryTextWhenSpaceRunsOut() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val host = AppWidgetHostView(context)
            val longSubject = schedule.copy(lessons = schedule.lessons.map { it.copy(subject = "Уголовно-процессуальное право и специальная профессиональная подготовка") })
            val view = ScheduleWidgetProvider.createViews(context, longSubject, 260, 170, now = now).apply(context, host)
            val body = view.findViewById<LinearLayout>(R.id.widget_lessons)
            assertEquals(View.GONE, body.getChildAt(0).findViewById<View>(R.id.lesson_detail).visibility)
            assertEquals(View.VISIBLE, body.getChildAt(0).findViewById<View>(R.id.lesson_subject).visibility)
        }
    }
}
