package app.para.schedule.ai

/** Deliberately limited fallback, not a replacement for document understanding. */
object SimpleScheduleParser {
    fun parse(text: String): ImportDraft {
        val days = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
        val short = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
        val time = Regex("(\\d{1,2}[:.]\\d{2})\\s*[-–—]\\s*(\\d{1,2}[:.]\\d{2})\\s+(.+)")
        val week = Regex("(?iu)(?<![а-яё])недел(?:и|я|ях)?\\s*:?\\s*(\\d[\\d, –—-]*)")
        var day: Int? = null
        var date = ""
        var weeks = ""
        val rows = mutableListOf<DraftLesson>()
        val ignored = mutableListOf<String>()
        for (line in text.lines().filter(String::isNotBlank)) {
            val lower = line.lowercase().trim()
            val dayIndex = days.indices.firstOrNull { Regex("(?<![а-я])(?:${days[it]}|${short[it]})(?![а-я])").containsMatchIn(lower) }
            if (dayIndex != null) day = dayIndex + 1
            Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{2}\\.\\d{2}(?:\\.\\d{4})?\\b").find(line.substringBefore(':'))?.let { date = it.value }
            val weekMatch = week.find(line)
            if (weekMatch != null) weeks = weekMatch.groupValues[1].trim()
            val match = time.find(line)
            if (match != null) {
                var subject = match.groupValues[3].replace(week, "").trim(' ', ',', ';', '.')
                val roomMatch = Regex("(?iu)(?:кабинет|аудитория|каб\\.?|ауд\\.?)\\s*[\\p{L}\\d-]+").find(subject)
                val room = roomMatch?.value ?: ""
                if (roomMatch != null) subject = subject.substring(0, roomMatch.range.first).trim(' ', ',', ';', '.')
                rows += DraftLesson(subject = subject, start = match.groupValues[1], end = match.groupValues[2], day = day, date = date, weeks = weeks, room = room)
            } else if (dayIndex == null && weekMatch == null && !Regex("^\\d{4}-|^\\d{2}\\.\\d{2}").containsMatchIn(line)) ignored += line.take(120)
        }
        require(rows.isNotEmpty()) { "Нужны строки вида «08:30–10:00 Математика» под заголовком дня недели. Либо используйте локальную модель." }
        return ImportDraft(rows, warnings = if (ignored.isEmpty()) emptyList() else listOf("Без ИИ не разобраны строки: ${ignored.take(6).joinToString(" / ")}. Проверьте полноту расписания."))
    }
}
