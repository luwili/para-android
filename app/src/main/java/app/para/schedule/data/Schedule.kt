package app.para.schedule.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID

enum class Repeat(val label: String) {
    EVERY("Каждую неделю"), ODD("Нечётные недели"), EVEN("Чётные недели")
}

data class Lesson(
    val id: String = UUID.randomUUID().toString(),
    val subject: String,
    val start: LocalTime,
    val end: LocalTime,
    val room: String = "",
    val teacher: String = "",
    val kind: String = "Семинар",
    val dayOfWeek: Int = 1,
    val repeat: Repeat = Repeat.EVERY,
    val color: Int = 0,
)

data class GroupProfile(
    val name: String = "Моя группа",
    val timezone: String = ZoneId.systemDefault().id,
    val semesterStart: LocalDate = monday(LocalDate.now()),
    val semesterEnd: LocalDate = semesterStart.plusMonths(6),
    val theme: String = "system",
    val dynamicColors: Boolean = false,
)

data class Schedule(
    val profile: GroupProfile = GroupProfile(),
    val lessons: List<Lesson> = emptyList(),
    // A present, empty list means an explicitly cancelled day, not a missing template.
    val overrides: Map<LocalDate, List<Lesson>> = emptyMap(),
    val configured: Boolean = false,
    val demo: Boolean = false,
)

fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
val russian: Locale = Locale.forLanguageTag("ru-RU")

object ScheduleEngine {
    fun weekNumber(schedule: Schedule, date: LocalDate): Int =
        Math.floorDiv(ChronoUnit.DAYS.between(monday(schedule.profile.semesterStart), monday(date)), 7L).toInt() + 1

    fun lessonsOn(schedule: Schedule, date: LocalDate): List<Lesson> {
        schedule.overrides[date]?.let { return it.sortedBy(Lesson::start) }
        if (date < schedule.profile.semesterStart || date > schedule.profile.semesterEnd) return emptyList()
        val odd = weekNumber(schedule, date) % 2 != 0
        return schedule.lessons.filter {
            it.dayOfWeek == date.dayOfWeek.value &&
                (it.repeat == Repeat.EVERY || (it.repeat == Repeat.ODD && odd) || (it.repeat == Repeat.EVEN && !odd))
        }.sortedBy(Lesson::start)
    }

    // End is exclusive: back-to-back lessons cannot both be current.
    fun isCurrent(lesson: Lesson, date: LocalDate, now: ZonedDateTime): Boolean =
        date == now.toLocalDate() && !now.toLocalTime().isBefore(lesson.start) && now.toLocalTime().isBefore(lesson.end)

    fun now(schedule: Schedule): ZonedDateTime = ZonedDateTime.now(ZoneId.of(schedule.profile.timezone))

    fun widgetDate(schedule: Schedule, now: ZonedDateTime): LocalDate {
        val today = now.toLocalDate()
        // Respect real Sunday classes and explicit cancellations of the following Monday.
        return if (today.dayOfWeek == DayOfWeek.SUNDAY && lessonsOn(schedule, today).isEmpty()) today.plusDays(1) else today
    }

    fun overlaps(a: Lesson, b: Lesson): Boolean = a.start < b.end && b.start < a.end

    fun abbreviation(subject: String): String {
        if (subject.isBlank()) return "—"
        val lower = subject.lowercase(russian)
        return when {
            "уголовно-процесс" in lower || "уголовный процесс" in lower -> "УПП"
            "уголовн" in lower -> "УП"
            "гражданско-процесс" in lower || "гражданский процесс" in lower -> "ГПП"
            "гражданск" in lower -> "ГП"
            "административн" in lower && "процесс" in lower -> "АСП"
            "финансов" in lower && "грамотн" in lower -> "ОФГ"
            "финансов" in lower -> "ФП"
            "экологическ" in lower -> "ЭП"
            "международн" in lower -> "МП"
            "специальная профессиональная" in lower -> "СПП"
            "физическ" in lower || lower == "офп" -> "ОФП"
            "информационн" in lower -> "ИТ"
            "экономические основы" in lower -> "ЭОБ"
            else -> subject.split(Regex("[\\s-]+")).filter { it.isNotBlank() }
                .let { words -> if (words.size == 1) words.first().take(4) else words.take(4).joinToString("") { it.take(1) } }
                .uppercase(russian)
        }
    }

    fun cleanRoom(room: String): String = room.replace(Regex("\\s*\\([^)]*\\)"), "").trim().ifBlank { room.trim() }

    fun example(): Schedule {
        val start = monday(LocalDate.now(ZoneId.of("Asia/Yekaterinburg")))
        val subjects = listOf("Уголовное право", "Гражданский процесс", "Финансовое право", "Международное право", "Специальная профессиональная подготовка", "Информационные технологии")
        val times = listOf("08:30" to "10:00", "10:15" to "11:45", "12:15" to "13:45", "14:00" to "15:30")
        return Schedule(
            profile = GroupProfile("ПОНБ-УП-304С", "Asia/Yekaterinburg", start, start.plusMonths(6)),
            lessons = (1..6).flatMap { day ->
                (0 until if (day == 6) 2 else 3).map { index ->
                    Lesson(subject = subjects[(day + index - 1) % subjects.size],
                        start = LocalTime.parse(times[index].first), end = LocalTime.parse(times[index].second),
                        room = "${310 + day * 2 + index}", kind = if (index == 0) "Лекция" else "Семинар",
                        dayOfWeek = day, color = (day + index - 1) % 4)
                }
            }, configured = true, demo = true,
        )
    }
}
