package app.para.schedule.ai

import app.para.schedule.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ImportLogicTest {
    private val monday = LocalDate.of(2026, 10, 19)
    private val options = ImportOptions("ТЕСТ-101", "Asia/Yekaterinburg", monday, 4, UndatedPolicy.ALL)
    private val row = DraftLesson(subject = "Математика", start = "08:30", end = "10:00", day = 1)

    @Test fun `week ranges are bounded and deduplicated`() {
        assertEquals(setOf(1, 2, 3, 4, 6, 8), WeekSelection.parse("1–4, 3, 6,8", 8))
        for (bad in listOf("0", "9", "4-1", "1..3", "1,", "1-999")) assertTrue(runCatching { WeekSelection.parse(bad, 8) }.isFailure)
    }
    @Test fun `all weeks generate actual dates without LLM date arithmetic`() {
        val plan = DraftCompiler.compile(ImportDraft(listOf(row)), options)
        assertTrue(plan.problems.toString(), plan.problems.isEmpty())
        assertEquals((0L..3).map { monday.plusWeeks(it) }, plan.days.keys.toList())
    }
    @Test fun `unspecified recurrence requires a user choice`() {
        val plan = DraftCompiler.compile(ImportDraft(listOf(row)), options.copy(undatedPolicy = UndatedPolicy.ASK))
        assertTrue(plan.problems.isNotEmpty())
        assertEquals(1, DraftCompiler.compile(ImportDraft(listOf(row)), options.copy(undatedPolicy = UndatedPolicy.FIRST)).lessonCount)
    }
    @Test fun `numbered weeks and parity intersect`() {
        val plan = DraftCompiler.compile(ImportDraft(listOf(row.copy(weeks = "1-4", repeat = Repeat.EVEN))), options)
        assertEquals(listOf(monday.plusWeeks(1), monday.plusWeeks(3)), plan.days.keys.toList())
        assertTrue(DraftCompiler.compile(ImportDraft(listOf(row.copy(weeks = "1,3", repeat = Repeat.EVEN))), options).problems.isNotEmpty())
    }
    @Test fun `concrete date is never repeated`() {
        val plan = DraftCompiler.compile(ImportDraft(listOf(row.copy(date = "2026-11-02"))), options)
        assertEquals(setOf(LocalDate.of(2026, 11, 2)), plan.days.keys)
    }
    @Test fun `dates outside period and wrong weekdays are rejected`() {
        assertTrue(DraftCompiler.compile(ImportDraft(listOf(row.copy(date = "2027-01-01"))), options).problems.isNotEmpty())
        assertTrue(DraftCompiler.compile(ImportDraft(listOf(row.copy(date = "2026-10-20"))), options).problems.isNotEmpty())
    }
    @Test fun `partial date resolves only when year is unique`() {
        val winter = options.copy(firstMonday = LocalDate.of(2026, 12, 7), weekCount = 8)
        assertEquals(LocalDate.of(2027, 1, 2), DraftCompiler.resolveDate("02.01", winter))
        assertTrue(runCatching { DraftCompiler.resolveDate("02.11", options.copy(weekCount = 104)) }.isFailure)
    }
    @Test fun `explicit free day stays an explicit empty override`() {
        val plan = DraftCompiler.compile(ImportDraft(emptyList(), listOf("2026-10-20"), daysOffConfirmed = true), options)
        assertTrue(plan.problems.isEmpty())
        assertEquals(emptyList<Lesson>(), plan.days[LocalDate.of(2026, 10, 20)])
    }
    @Test fun `free day cannot erase a conflicting lesson silently`() {
        val plan = DraftCompiler.compile(ImportDraft(listOf(row), listOf("2026-10-19"), daysOffConfirmed = true), options)
        assertTrue(plan.problems.isNotEmpty())
    }
    @Test fun `overlaps are errors and exact duplicate rows are merged`() {
        assertTrue(DraftCompiler.compile(ImportDraft(listOf(row, row.copy(subject = "Другая дисциплина"))), options).problems.isNotEmpty())
        val duplicate = DraftCompiler.compile(ImportDraft(listOf(row, row.copy(id = "another"))), options)
        assertTrue(duplicate.problems.isEmpty())
        assertEquals(4, duplicate.lessonCount)
        assertTrue(duplicate.warnings.isNotEmpty())
    }
    @Test fun `verification cannot bypass invalid times`() {
        val bad = row.copy(start = "", verifiedByUser = true, evidenceIssues = listOf("Догадка"))
        assertTrue(DraftCompiler.compile(ImportDraft(listOf(bad)), options).problems.isNotEmpty())
    }
    @Test fun `unsupported model guesses require row confirmation`() {
        val guess = row.copy(evidenceIssues = listOf("Название не подтверждено"))
        assertTrue(DraftCompiler.compile(ImportDraft(listOf(guess)), options).problems.isNotEmpty())
        assertTrue(DraftCompiler.compile(ImportDraft(listOf(guess.copy(verifiedByUser = true))), options).problems.isEmpty())
    }
    @Test fun `replace dates preserves other dates and weekly template`() {
        val old = Lesson(subject = "Старый предмет", start = LocalTime.NOON, end = LocalTime.of(13, 0), dayOfWeek = 2)
        val existing = Schedule(GroupProfile("ТЕСТ-101", "Asia/Yekaterinburg", monday, monday.plusMonths(4)), listOf(old), configured = true)
        val plan = DraftCompiler.compile(ImportDraft(listOf(row.copy(weeks = "1"))), options)
        val result = DraftCompiler.apply(existing, plan, options)
        assertEquals(listOf(old), result.lessons)
        assertEquals("Старый предмет", ScheduleEngine.lessonsOn(result, monday.plusDays(1)).single().subject)
        assertEquals("Математика", ScheduleEngine.lessonsOn(result, monday).single().subject)
    }
    @Test fun `replace period explicitly clears unspecified days`() {
        val plan = DraftCompiler.compile(ImportDraft(listOf(row.copy(weeks = "1"))), options)
        val result = DraftCompiler.apply(Schedule(), plan, options.copy(applyMode = ApplyMode.PERIOD))
        assertEquals(28, result.overrides.size)
        assertTrue(result.overrides[monday.plusDays(1)]!!.isEmpty())
    }
    @Test fun `append rejects existing conflicts and different groups need explicit replacement`() {
        val original = Schedule(GroupProfile("ТЕСТ-101", "Asia/Yekaterinburg", monday), listOf(Lesson(subject = "Существующий", start = LocalTime.of(8, 0), end = LocalTime.of(9, 0))), configured = true)
        val plan = DraftCompiler.compile(ImportDraft(listOf(row)), options)
        assertTrue(runCatching { DraftCompiler.apply(original, plan, options.copy(applyMode = ApplyMode.APPEND)) }.isFailure)
        assertTrue(runCatching { DraftCompiler.apply(original, plan, options.copy(group = "Другая")) }.isFailure)
        assertEquals("Другая", DraftCompiler.apply(original, plan, options.copy(group = "Другая", applyMode = ApplyMode.ALL)).profile.name)
    }
    @Test fun `unknown kind is neutral rather than invented seminar`() {
        val plan = DraftCompiler.compile(ImportDraft(listOf(row.copy(kind = ""))), options)
        assertEquals("Занятие", plan.days.values.first().first().kind)
    }
    @Test fun `model cannot cancel dates without separate user confirmation`() {
        val plan = DraftCompiler.compile(ImportDraft(emptyList(), listOf("2026-10-20")), options)
        assertTrue(plan.problems.any { "подтвердите" in it.message })
    }
}
