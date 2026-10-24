package app.para.schedule.data

import java.time.LocalDate

object ScheduleEdits {
    fun save(schedule: Schedule, date: LocalDate, lesson: Lesson, dateOnly: Boolean): Schedule {
        if (dateOnly) {
            val day = ScheduleEngine.lessonsOn(schedule, date).filterNot { it.id == lesson.id }
            require(day.none { ScheduleEngine.overlaps(it, lesson) }) { "В это время уже есть занятие" }
            return schedule.copy(overrides = schedule.overrides + (date to (day + lesson).sortedBy { it.start }))
        }
        val others = schedule.lessons.filterNot { it.id == lesson.id }
        require(others.none {
            it.dayOfWeek == lesson.dayOfWeek &&
                (it.repeat == Repeat.EVERY || lesson.repeat == Repeat.EVERY || it.repeat == lesson.repeat) &&
                ScheduleEngine.overlaps(it, lesson)
        }) { "В шаблоне уже есть занятие в это время" }
        return schedule.copy(lessons = others + lesson)
    }

    fun delete(schedule: Schedule, date: LocalDate, id: String, dateOnly: Boolean): Schedule =
        if (dateOnly) schedule.copy(overrides = schedule.overrides +
            (date to ScheduleEngine.lessonsOn(schedule, date).filterNot { it.id == id }))
        else schedule.copy(lessons = schedule.lessons.filterNot { it.id == id })
}
