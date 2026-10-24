package app.para.schedule.ai

import app.para.schedule.data.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import java.util.UUID

data class DraftLesson(
    val id: String = UUID.randomUUID().toString(),
    val subject: String = "", val start: String = "", val end: String = "",
    val day: Int? = null, val date: String = "", val weeks: String = "",
    val repeat: Repeat = Repeat.EVERY, val room: String = "", val teacher: String = "", val kind: String = "",
    val evidenceIssues: List<String> = emptyList(), val verifiedByUser: Boolean = false,
)

data class ImportDraft(
    val lessons: List<DraftLesson>, val daysOff: List<String> = emptyList(),
    val suggestedWeeks: Int? = null, val warnings: List<String> = emptyList(),
    val daysOffConfirmed: Boolean = false,
)

enum class UndatedPolicy(val label: String) {
    ASK("Уточнить повторение"), ALL("Повторять все выбранные недели"), FIRST("Только первая неделя")
}
enum class ApplyMode(val label: String) {
    DATES("Заменить только указанные даты"), APPEND("Добавить без пересечений"),
    PERIOD("Заменить весь выбранный период"), ALL("Заменить всё расписание")
}

data class ImportOptions(
    val group: String, val timezone: String, val firstMonday: LocalDate, val weekCount: Int,
    val undatedPolicy: UndatedPolicy = UndatedPolicy.ASK, val applyMode: ApplyMode = ApplyMode.DATES,
) {
    val end: LocalDate get() = firstMonday.plusWeeks(weekCount.toLong()).minusDays(1)
}

data class DraftProblem(val rowId: String?, val message: String)
data class ImportPlan(
    val days: Map<LocalDate, List<Lesson>>, val problems: List<DraftProblem>, val warnings: List<String>,
) {
    val lessonCount get() = days.values.sumOf { it.size }
}

object WeekSelection {
    fun parse(value: String, maximum: Int): Set<Int> {
        require(maximum in 1..104) { "Выберите от 1 до 104 недель" }
        val text = value.trim().replace('–', '-').replace('—', '-').replace(" ", "")
        require(text.isNotBlank()) { "Не указаны недели" }
        val result = linkedSetOf<Int>()
        for (part in text.split(',')) {
            require(Regex("\\d{1,3}(-\\d{1,3})?").matches(part)) { "Недели: например 1-4,6,8" }
            val bounds = part.split('-').map(String::toInt)
            val first = bounds.first()
            val last = bounds.last()
            require(first in 1..maximum && last in first..maximum) { "Недели должны входить в период 1–$maximum" }
            result.addAll(first..last)
        }
        return result
    }
}

object DraftCompiler {
    private val fullDate = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)

    fun resolveDate(value: String, options: ImportOptions): LocalDate {
        val text = value.trim()
        val direct = runCatching { LocalDate.parse(text) }.getOrNull()
            ?: runCatching { LocalDate.parse(text, fullDate) }.getOrNull()
        if (direct != null) {
            require(direct >= options.firstMonday && direct <= options.end) { "Дата $text вне выбранного периода" }
            return direct
        }
        require(Regex("\\d{2}\\.\\d{2}\\.?").matches(text)) { "Дата: ГГГГ-ММ-ДД, ДД.ММ.ГГГГ или ДД.ММ" }
        val (day, month) = text.trimEnd('.').split('.').map(String::toInt)
        val candidates = (options.firstMonday.year..options.end.year).mapNotNull { year ->
            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.takeIf { it >= options.firstMonday && it <= options.end }
        }
        require(candidates.size == 1) { "Для $text нужно уточнить год или границы периода" }
        return candidates.single()
    }

    fun compile(draft: ImportDraft, options: ImportOptions): ImportPlan {
        val problems = mutableListOf<DraftProblem>()
        val days = sortedMapOf<LocalDate, MutableList<Lesson>>()
        val warnings = draft.warnings.toMutableList()
        if (options.weekCount !in 1..104 || options.firstMonday.dayOfWeek.value != 1) {
            return ImportPlan(emptyMap(), listOf(DraftProblem(null, "Нужен понедельник первой недели и период 1–104 недели")), warnings)
        }
        if (draft.lessons.size > 500) return ImportPlan(emptyMap(), listOf(DraftProblem(null, "Слишком много строк: разбейте документ на части")), warnings)
        if (draft.daysOff.isNotEmpty() && !draft.daysOffConfirmed) problems += DraftProblem(null, "Отдельно проверьте и подтвердите свободные даты: они могут отменить существующие пары")
        for (row in draft.lessons) {
            val errors = mutableListOf<String>()
            if (!row.verifiedByUser) errors.addAll(row.evidenceIssues)
            if (row.subject.isBlank() || row.subject.length > 160) errors += "Проверьте название предмета (1–160 символов)"
            if (row.room.length > 160 || row.teacher.length > 160 || row.kind.length > 40) errors += "Слишком длинное описание"
            val start = time(row.start)
            val end = time(row.end)
            if (start == null || end == null) errors += "Укажите время начала и конца: ЧЧ:ММ"
            else if (end <= start) errors += "Конец должен быть позже начала"
            val dates = runCatching {
                if (row.date.isNotBlank()) {
                    val date = resolveDate(row.date, options)
                    require(row.day == null || row.day == date.dayOfWeek.value) { "Дата и день недели не совпадают" }
                    listOf(date)
                } else {
                    require(row.day in 1..7) { "Укажите день недели или конкретную дату" }
                    val weeks = when {
                        row.weeks.isNotBlank() -> WeekSelection.parse(row.weeks, options.weekCount)
                        row.repeat != Repeat.EVERY -> (1..options.weekCount).toSet()
                        options.weekCount == 1 || options.undatedPolicy == UndatedPolicy.FIRST -> setOf(1)
                        options.undatedPolicy == UndatedPolicy.ALL -> (1..options.weekCount).toSet()
                        else -> error("Выберите, как повторять занятия без номеров недель")
                    }.filter { row.repeat == Repeat.EVERY || (row.repeat == Repeat.ODD && it % 2 != 0) || (row.repeat == Repeat.EVEN && it % 2 == 0) }
                    require(weeks.isNotEmpty()) { "В выбранных неделях нет занятий с этой чётностью" }
                    weeks.map { options.firstMonday.plusWeeks((it - 1).toLong()).plusDays((row.day!! - 1).toLong()) }
                }
            }.onFailure { errors += it.message ?: "Проверьте даты и недели" }.getOrDefault(emptyList())
            if (errors.isNotEmpty()) {
                problems += errors.distinct().map { DraftProblem(row.id, it) }
                continue
            }
            for (date in dates) {
                val lesson = Lesson(id = "${row.id}-$date", subject = row.subject.trim(), start = start!!, end = end!!,
                    room = row.room.trim(), teacher = row.teacher.trim(), kind = row.kind.ifBlank { "Занятие" }, dayOfWeek = date.dayOfWeek.value,
                    color = (draft.lessons.indexOf(row) % 4))
                val list = days.getOrPut(date) { mutableListOf() }
                if (list.any { sameLesson(it, lesson) }) { warnings += "Повторяющиеся одинаковые занятия объединены"; continue }
                if (list.any { ScheduleEngine.overlaps(it, lesson) }) problems += DraftProblem(row.id, "$date: пересечение времени с другой парой")
                list += lesson
            }
        }
        for (dateText in draft.daysOff.distinct()) {
            runCatching {
                val date = resolveDate(dateText, options)
                require(days[date].isNullOrEmpty()) { "$date: одновременно указаны пары и свободный день" }
                days[date] = mutableListOf()
            }.onFailure { problems += DraftProblem(null, it.message ?: "Проверьте свободные дни") }
        }
        if (days.values.sumOf { it.size } > 8000) problems += DraftProblem(null, "Слишком большой период: сократите число недель")
        if (days.isEmpty() && problems.isEmpty()) problems += DraftProblem(null, "Не найдено занятий или явно свободных дней")
        return ImportPlan(days.mapValues { it.value.sortedBy(Lesson::start) }, problems.distinct(), warnings.distinct())
    }

    fun apply(existing: Schedule, plan: ImportPlan, options: ImportOptions): Schedule {
        require(plan.problems.isEmpty()) { "Сначала исправьте ошибки черновика" }
        require(options.group.trim().length in 1..80) { "Введите название группы" }
        java.time.ZoneId.of(options.timezone)
        require(!existing.configured || existing.profile.name == options.group.trim() || options.applyMode == ApplyMode.ALL) {
            "Для другой группы выберите «Заменить всё расписание»"
        }
        val replace = options.applyMode == ApplyMode.ALL || !existing.configured
        val base = if (replace) Schedule(profile = existing.profile.copy(name = options.group.trim(), timezone = options.timezone,
            semesterStart = options.firstMonday, semesterEnd = options.end), configured = true) else existing
        val changes = base.overrides.toMutableMap()
        if (options.applyMode == ApplyMode.PERIOD) {
            repeat(options.weekCount * 7) { changes[options.firstMonday.plusDays(it.toLong())] = emptyList() }
        }
        for ((date, incoming) in plan.days) {
            if (options.applyMode == ApplyMode.APPEND) {
                require(incoming.isNotEmpty()) { "$date: свободный день нельзя добавить поверх существующего расписания" }
                val old = ScheduleEngine.lessonsOn(base, date)
                val additions = incoming.filterNot { next -> old.any { sameLesson(it, next) } }
                require(additions.none { next -> old.any { ScheduleEngine.overlaps(it, next) } }) { "$date: пересечение с существующим расписанием" }
                changes[date] = (old + additions).sortedBy(Lesson::start)
            } else changes[date] = incoming
        }
        val next = base.copy(profile = base.profile.copy(name = options.group.trim(), timezone = options.timezone), overrides = changes,
            configured = true, demo = false)
        // Reuse persistence constraints; no partial replacement if a draft is oversized or invalid.
        ScheduleJson.decode(ScheduleJson.encode(next))
        return next
    }

    fun time(value: String): LocalTime? {
        val match = Regex("(\\d{1,2})[:.](\\d{2})").matchEntire(value.trim()) ?: return null
        return runCatching { LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()) }.getOrNull()
    }
    private fun sameLesson(a: Lesson, b: Lesson) = a.subject.equals(b.subject, true) && a.start == b.start && a.end == b.end && a.room == b.room && a.teacher == b.teacher && a.kind == b.kind
}
