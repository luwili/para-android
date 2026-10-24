package app.para.schedule.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import app.para.schedule.data.Lesson
import app.para.schedule.data.ScheduleEngine
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

internal enum class WidgetMode { TINY, COLUMNS, COMPACT, FULL }

internal data class WidgetRowFit(
    val subjectPx: Float, val timePx: Float, val detailPx: Float,
    val subjectLines: Int, val timeLines: Int, val detail: String,
)

/** Pixel budgets match the XML exactly. Text uses real scaled font metrics, not sp == dp. */
internal class WidgetSizing(private val context: Context, width: Int, height: Int, lessonCount: Int) {
    private val metrics = context.resources.displayMetrics
    private val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
    val mode = when {
        height < 110 -> WidgetMode.TINY
        width < 170 * min(scale, 1.5f) -> WidgetMode.COMPACT
        height < 170 * min(scale, 1.5f) -> WidgetMode.COLUMNS
        else -> WidgetMode.FULL
    }
    val headerHeight = when (mode) { WidgetMode.TINY -> 20; WidgetMode.COLUMNS -> 24; WidgetMode.COMPACT -> 30; WidgetMode.FULL -> 32 }
    val showHeader = mode != WidgetMode.TINY || dp(height - 36) >= line(sp(9)) + line(sp(15)) + dp(2)
    private val outerPadding = when (mode) { WidgetMode.TINY -> 8; WidgetMode.COLUMNS -> 8; WidgetMode.COMPACT -> 10; WidgetMode.FULL -> 12 }
    private val footerSp = if (mode == WidgetMode.FULL) 10 else 9
    private val chrome = dp(outerPadding * 2 + if (showHeader) headerHeight else 0) +
        (if (mode == WidgetMode.TINY) 0f else line(sp(footerSp)) + dp(if (mode == WidgetMode.FULL) 6 else if (mode == WidgetMode.COLUMNS) 3 else 0)) +
        (if (mode == WidgetMode.FULL) line(sp(12)) + dp(6) else 0f)
    val contentHeight = (dp(height) - chrome - dp(2)).coerceAtLeast(dp(1))
    val capacity = when (mode) {
        WidgetMode.TINY, WidgetMode.COLUMNS -> floor(dp(width - 24) / (sp(18) * 2.8f + dp(8))).toInt().coerceIn(1, 4)
        WidgetMode.COMPACT -> floor(contentHeight / (line(sp(12)) + dp(20))).toInt().coerceIn(1, 6)
        WidgetMode.FULL -> floor(contentHeight / (line(sp(11)) + line(sp(14)) + dp(29))).toInt().coerceIn(1, 8)
    }.coerceAtMost(lessonCount.coerceAtLeast(1))
    private val tileHeight = if (mode == WidgetMode.FULL || mode == WidgetMode.COMPACT) contentHeight / capacity else contentHeight
    private val textWidth = when (mode) {
        WidgetMode.FULL -> dp(width - 44)
        WidgetMode.COMPACT -> dp(width - 32)
        else -> dp(width - 24) / capacity - dp(12)
    }.coerceAtLeast(dp(8))

    fun dp(value: Int): Float = value * metrics.density
    fun sp(value: Int): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value.toFloat(), metrics)
    private fun paint(size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    private fun line(size: Float): Float = paint(size).fontMetrics.let { ceil(it.descent - it.ascent) + 1 }
    private fun lines(text: String, size: Float): Int = StaticLayout.Builder.obtain(text, 0, text.length, paint(size, true), textWidth.toInt().coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(2).build().lineCount.coerceIn(1, 2)

    fun fit(lesson: Lesson): WidgetRowFit {
        var subject = sp(when (mode) { WidgetMode.FULL -> 14; WidgetMode.COMPACT -> 12; WidgetMode.TINY -> 15; WidgetMode.COLUMNS -> 18 })
        var time = sp(if (mode == WidgetMode.FULL) 11 else 9)
        val detail = sp(if (mode == WidgetMode.COMPACT) 9 else 10)
        val padding = dp(when (mode) { WidgetMode.FULL -> 26; WidgetMode.COMPACT -> 20; else -> 2 })
        val hasTime = mode != WidgetMode.COMPACT
        // Extremely low widgets cannot grow vertically. Keep their primary text inside the tile.
        repeat(12) {
            val required = line(subject) + (if (hasTime) line(time) else 0f) + padding
            if (required > tileHeight) {
                val ratio = ((tileHeight - padding).coerceAtLeast(dp(8)) / (required - padding)).coerceIn(.5f, .98f)
                subject *= ratio
                time *= ratio
            }
        }
        var remaining = tileHeight - padding - line(subject) - if (hasTime) line(time) else 0f
        val wantedSubjectLines = if (mode == WidgetMode.FULL) lines(lesson.subject, subject) else 1
        val subjectLines = if (wantedSubjectLines > 1 && remaining >= line(subject)) {
            remaining -= line(subject); 2
        } else 1
        val timeLines = if (mode == WidgetMode.COLUMNS && remaining >= line(time)) { remaining -= line(time); 2 } else 1
        val candidates = if (mode == WidgetMode.FULL) listOf(
            listOf(lesson.room, lesson.teacher, lesson.kind).filter(String::isNotBlank).joinToString(" · "),
            listOf(lesson.room, lesson.kind).filter(String::isNotBlank).joinToString(" · "),
            lesson.room,
        ) else listOf(ScheduleEngine.cleanRoom(lesson.room))
        val primaryFits = subjectLines >= wantedSubjectLines && (mode != WidgetMode.COLUMNS || timeLines == 2)
        val detailText = if (mode != WidgetMode.TINY && primaryFits && remaining >= line(detail) + dp(3)) {
            candidates.firstOrNull { it.isNotBlank() && paint(detail).measureText(it) <= textWidth } ?: ""
        } else ""
        return WidgetRowFit(subject, time, detail, subjectLines, timeLines, detailText)
    }
}
