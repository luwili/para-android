package app.para.schedule.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ScheduleEngineTest {
    private val monday = LocalDate.of(2026, 10, 19)
    private val lesson = Lesson(id = "law", subject = "Уголовное право", start = LocalTime.of(8, 0), end = LocalTime.of(9, 20))
    private val schedule = Schedule(
        profile = GroupProfile("ТЕСТ-101", "Asia/Yekaterinburg", monday, monday.plusMonths(4)),
        lessons = listOf(lesson), configured = true,
    )

    @Test fun `week one is anchored to semester Monday`() {
        assertEquals(1, ScheduleEngine.weekNumber(schedule, monday.plusDays(6)))
        assertEquals(2, ScheduleEngine.weekNumber(schedule, monday.plusWeeks(1)))
        assertEquals(0, ScheduleEngine.weekNumber(schedule, monday.minusDays(1)))
    }

    @Test fun `odd and even weeks resolve independently`() {
        val s = schedule.copy(lessons = listOf(lesson.copy(repeat = Repeat.ODD), lesson.copy(id = "even", repeat = Repeat.EVEN)))
        assertEquals("law", ScheduleEngine.lessonsOn(s, monday).single().id)
        assertEquals("even", ScheduleEngine.lessonsOn(s, monday.plusWeeks(1)).single().id)
    }

    @Test fun `empty override is a cancelled day`() {
        val s = schedule.copy(overrides = mapOf(monday to emptyList()))
        assertTrue(ScheduleEngine.lessonsOn(s, monday).isEmpty())
        assertEquals(1, ScheduleEngine.lessonsOn(s, monday.plusWeeks(1)).size)
    }

    @Test fun `override replaces the whole day and sorts by time`() {
        val later = lesson.copy(id = "later", start = LocalTime.of(10, 0), end = LocalTime.of(11, 0))
        val s = schedule.copy(overrides = mapOf(monday to listOf(later, lesson)))
        assertEquals(listOf("law", "later"), ScheduleEngine.lessonsOn(s, monday).map { it.id })
    }

    @Test fun `weekly template is limited by study period`() {
        assertTrue(ScheduleEngine.lessonsOn(schedule, monday.minusWeeks(1)).isEmpty())
        assertTrue(ScheduleEngine.lessonsOn(schedule, monday.plusYears(1)).isEmpty())
    }

    @Test fun `date override can exist outside study period`() {
        val date = monday.minusWeeks(2)
        val s = schedule.copy(overrides = mapOf(date to listOf(lesson)))
        assertEquals(1, ScheduleEngine.lessonsOn(s, date).size)
    }

    @Test fun `Sunday switches to actual next Monday and its even week`() {
        val s = schedule.copy(lessons = listOf(lesson.copy(repeat = Repeat.EVEN)))
        val now = ZonedDateTime.of(monday.plusDays(6), LocalTime.NOON, ZoneId.of(s.profile.timezone))
        val target = ScheduleEngine.widgetDate(s, now)
        assertEquals(monday.plusWeeks(1), target)
        assertEquals(1, ScheduleEngine.lessonsOn(s, target).size)
        assertFalse(ScheduleEngine.isCurrent(lesson, target, now))
    }

    @Test fun `Sunday classes are not hidden`() {
        val s = schedule.copy(lessons = listOf(lesson.copy(dayOfWeek = 7)))
        val now = ZonedDateTime.of(monday.plusDays(6), LocalTime.NOON, ZoneId.of(s.profile.timezone))
        assertEquals(monday.plusDays(6), ScheduleEngine.widgetDate(s, now))
    }

    @Test fun `current interval includes start and excludes end`() {
        val zone = ZoneId.of(schedule.profile.timezone)
        assertTrue(ScheduleEngine.isCurrent(lesson, monday, ZonedDateTime.of(monday, lesson.start, zone)))
        assertFalse(ScheduleEngine.isCurrent(lesson, monday, ZonedDateTime.of(monday, lesson.end, zone)))
        assertFalse(ScheduleEngine.isCurrent(lesson, monday.plusDays(1), ZonedDateTime.of(monday, lesson.start, zone)))
    }

    @Test fun `Ufa clock differs from Moscow regardless of phone timezone`() {
        val instant = Instant.parse("2026-10-19T03:30:00Z")
        val ufa = instant.atZone(ZoneId.of("Asia/Yekaterinburg"))
        val moscow = instant.atZone(ZoneId.of("Europe/Moscow"))
        assertTrue(ScheduleEngine.isCurrent(lesson, monday, ufa))
        assertFalse(ScheduleEngine.isCurrent(lesson, monday, moscow))
    }

    @Test fun `legal abbreviation matching uses specific before general`() {
        assertEquals("УПП", ScheduleEngine.abbreviation("Уголовно-процессуальное право"))
        assertEquals("ГПП", ScheduleEngine.abbreviation("Гражданский процесс"))
        assertEquals("ОФГ", ScheduleEngine.abbreviation("Основы финансовой грамотности"))
        assertEquals("ЛА", ScheduleEngine.abbreviation("Линейная алгебра"))
        assertEquals("—", ScheduleEngine.abbreviation(""))
        assertEquals("каб. 310", ScheduleEngine.cleanRoom("каб. 310 (Достоевского 131)"))
    }

    @Test fun `date edit does not modify original template`() {
        val changed = ScheduleEdits.save(schedule, monday, lesson.copy(subject = "Новая дисциплина"), true)
        assertEquals("Новая дисциплина", ScheduleEngine.lessonsOn(changed, monday).single().subject)
        assertEquals(lesson.subject, ScheduleEngine.lessonsOn(changed, monday.plusWeeks(1)).single().subject)
        assertTrue(schedule.overrides.isEmpty())
    }

    @Test fun `date deletion keeps future repetitions`() {
        val changed = ScheduleEdits.delete(schedule, monday, lesson.id, true)
        assertTrue(ScheduleEngine.lessonsOn(changed, monday).isEmpty())
        assertEquals(1, ScheduleEngine.lessonsOn(changed, monday.plusWeeks(1)).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `overlapping template lessons are rejected`() {
        ScheduleEdits.save(schedule, monday, lesson.copy(id = "collision"), false)
    }

    @Test fun `different parity can share the same time`() {
        val odd = schedule.copy(lessons = listOf(lesson.copy(repeat = Repeat.ODD)))
        val result = ScheduleEdits.save(odd, monday, lesson.copy(id = "even", repeat = Repeat.EVEN), false)
        assertEquals(2, result.lessons.size)
    }

    @Test fun `back to back lessons do not overlap`() {
        val next = lesson.copy(id = "next", start = lesson.end, end = LocalTime.of(10, 30))
        assertFalse(ScheduleEngine.overlaps(lesson, next))
        assertEquals(2, ScheduleEdits.save(schedule, monday, next, false).lessons.size)
    }

    @Test fun `template changes preserve dated exceptions`() {
        val cancelled = schedule.copy(overrides = mapOf(monday to emptyList()))
        val result = ScheduleEdits.save(cancelled, monday, lesson.copy(subject = "Другое"), false)
        assertTrue(ScheduleEngine.lessonsOn(result, monday).isEmpty())
        assertEquals("Другое", ScheduleEngine.lessonsOn(result, monday.plusWeeks(1)).single().subject)
    }
}
