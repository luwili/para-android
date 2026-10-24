package app.para.schedule.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScheduleJsonTest {
    private val date = LocalDate.of(2026, 10, 19)
    private val lesson = Lesson(id = "json", subject = "Линейная алгебра", start = LocalTime.of(8, 30), end = LocalTime.of(10, 0), room = "310", repeat = Repeat.ODD)
    private val schedule = Schedule(GroupProfile("Группа 101", "Asia/Yekaterinburg", date, date.plusMonths(5)), listOf(lesson),
        mapOf(date.plusDays(1) to emptyList(), date.plusDays(2) to listOf(lesson.copy(id = "override"))), configured = true)

    @Test fun `full roundtrip includes cancelled days and parity`() {
        assertEquals(schedule, ScheduleJson.decode(ScheduleJson.encode(schedule)))
    }

    @Test fun `unknown fields are ignored for forward compatibility`() {
        val root = JSONObject(ScheduleJson.encode(schedule)).put("futureField", "ignored")
        assertEquals(schedule, ScheduleJson.decode(root.toString()))
    }

    @Test(expected = IllegalArgumentException::class) fun `unsupported schema version is rejected`() {
        ScheduleJson.decode(JSONObject(ScheduleJson.encode(schedule)).put("version", 99).toString())
    }

    @Test(expected = IllegalArgumentException::class) fun `foreign schedule format is rejected`() {
        ScheduleJson.decode(JSONObject(ScheduleJson.encode(schedule)).put("format", "other").toString())
    }

    @Test fun `bad timezone is rejected`() {
        assertTrue(runCatching { ScheduleJson.decode(ScheduleJson.encode(schedule.copy(profile = schedule.profile.copy(timezone = "Not/AZone")))) }.isFailure)
    }

    @Test(expected = IllegalArgumentException::class) fun `inverted times are rejected`() {
        ScheduleJson.decode(ScheduleJson.encode(schedule.copy(lessons = listOf(lesson.copy(end = LocalTime.of(7, 0))))))
    }

    @Test(expected = IllegalArgumentException::class) fun `duplicate IDs are rejected`() {
        ScheduleJson.decode(ScheduleJson.encode(schedule.copy(lessons = listOf(lesson, lesson))))
    }

    @Test(expected = IllegalArgumentException::class) fun `oversize input is rejected before parsing`() {
        ScheduleJson.decode("x".repeat(ScheduleJson.MAX_BYTES + 1))
    }

    @Test(expected = IllegalArgumentException::class) fun `empty subjects are rejected`() {
        ScheduleJson.decode(ScheduleJson.encode(schedule.copy(lessons = listOf(lesson.copy(subject = "   ")))))
    }

    @Test(expected = IllegalArgumentException::class) fun `period cannot start on Tuesday`() {
        ScheduleJson.decode(ScheduleJson.encode(schedule.copy(profile = schedule.profile.copy(semesterStart = date.plusDays(1)))))
    }

    @Test fun `corrupt json fails without touching original schedule`() {
        assertTrue(runCatching { ScheduleJson.decode("{broken") }.isFailure)
        assertEquals(1, schedule.lessons.size)
    }
}
