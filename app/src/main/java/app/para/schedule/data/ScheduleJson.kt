package app.para.schedule.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object ScheduleJson {
    const val MAX_BYTES = 2 * 1024 * 1024

    fun encode(schedule: Schedule): String = JSONObject().apply {
        put("format", "para-schedule")
        put("version", 1)
        put("configured", schedule.configured)
        put("demo", schedule.demo)
        put("profile", JSONObject().apply {
            put("name", schedule.profile.name)
            put("timezone", schedule.profile.timezone)
            put("semesterStart", schedule.profile.semesterStart.toString())
            put("semesterEnd", schedule.profile.semesterEnd.toString())
            put("theme", schedule.profile.theme)
            put("dynamicColors", schedule.profile.dynamicColors)
        })
        put("lessons", lessonArray(schedule.lessons))
        put("overrides", JSONObject().apply {
            schedule.overrides.toSortedMap().forEach { (date, lessons) -> put(date.toString(), lessonArray(lessons)) }
        })
    }.toString(2)

    fun decode(text: String): Schedule {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Файл больше 2 МБ" }
        val root = JSONObject(text)
        require(root.getString("format") == "para-schedule") { "Это не файл расписания Пары" }
        require(root.getInt("version") == 1) { "Версия файла пока не поддерживается" }
        val p = root.getJSONObject("profile")
        val name = p.getString("name").trim()
        require(name.length in 1..80) { "Название группы должно содержать 1–80 символов" }
        val zone = p.getString("timezone")
        ZoneId.of(zone)
        val start = LocalDate.parse(p.getString("semesterStart"))
        val end = LocalDate.parse(p.getString("semesterEnd"))
        require(start.dayOfWeek.value == 1) { "Первая неделя должна начинаться в понедельник" }
        require(end >= start && end <= start.plusYears(2)) { "Период должен быть от 1 дня до 2 лет" }
        val theme = p.optString("theme", "system")
        require(theme in listOf("system", "light", "dark")) { "Неизвестная тема" }
        val templates = parseLessons(root.getJSONArray("lessons"))
        val overridesObject = root.getJSONObject("overrides")
        require(overridesObject.length() <= 1500) { "Слишком много исключений по датам" }
        var total = templates.size
        val overrides = overridesObject.keys().asSequence().associate { key ->
            val date = LocalDate.parse(key)
            val lessons = parseLessons(overridesObject.getJSONArray(key))
            total += lessons.size
            require(total <= 12000) { "Слишком много занятий" }
            date to lessons
        }
        return Schedule(
            GroupProfile(name, zone, start, end, theme, p.optBoolean("dynamicColors", true)),
            templates, overrides, root.optBoolean("configured", true), root.optBoolean("demo", false),
        )
    }

    private fun lessonArray(lessons: List<Lesson>) = JSONArray().apply {
        lessons.forEach { lesson -> put(JSONObject().apply {
            put("id", lesson.id)
            put("subject", lesson.subject)
            put("start", lesson.start.toString())
            put("end", lesson.end.toString())
            put("room", lesson.room)
            put("teacher", lesson.teacher)
            put("kind", lesson.kind)
            put("dayOfWeek", lesson.dayOfWeek)
            put("repeat", lesson.repeat.name)
            put("color", lesson.color)
        }) }
    }

    private fun parseLessons(array: JSONArray): List<Lesson> {
        require(array.length() <= 1000) { "Слишком много занятий в одном списке" }
        val lessons = (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            val id = o.getString("id")
            val subject = o.getString("subject").trim()
            val start = LocalTime.parse(o.getString("start"))
            val end = LocalTime.parse(o.getString("end"))
            val day = o.getInt("dayOfWeek")
            require(id.length in 1..100 && subject.length in 1..160) { "Некорректное название или ID занятия" }
            require(end > start && start.second == 0 && end.second == 0 && start.nano == 0 && end.nano == 0) { "Проверьте время занятия" }
            require(day in 1..7) { "Некорректный день недели" }
            val room = o.optString("room", "")
            val teacher = o.optString("teacher", "")
            val kind = o.optString("kind", "Семинар")
            require(room.length <= 160 && teacher.length <= 160 && kind.length <= 40) { "Слишком длинное описание занятия" }
            Lesson(id, subject, start, end, room, teacher, kind, day,
                Repeat.valueOf(o.optString("repeat", "EVERY")), o.optInt("color", 0).coerceIn(0, 3))
        }
        require(lessons.map { it.id }.distinct().size == lessons.size) { "Повторяющиеся ID занятий" }
        return lessons
    }
}
