package app.para.schedule.ai

/** Use explicit numeric headings when a small model drops week metadata. Ambiguous blocks stay unresolved. */
object SourceWeekHints {
    data class Hints(val numberedBlocks: List<Set<Int>>, val count: Int?)
    private val heading = Regex("(?iu)(?<![а-яё])(?:неделя|недели|неделях|недель|нед\\.)\\s*:?\\s*(\\d{1,3}(?:\\s*[-–—]\\s*\\d{1,3})?(?:\\s*,\\s*\\d{1,3}(?:\\s*[-–—]\\s*\\d{1,3})?)*)")

    fun read(source: String): Hints {
        val blocks = mutableListOf<Set<Int>>()
        val counts = mutableListOf<Int>()
        for (line in source.lines()) {
            for (match in heading.findAll(line)) {
                val values = runCatching { WeekSelection.parse(match.groupValues[1], 104) }.getOrNull() ?: continue
                val prefix = line.substring(0, match.range.first)
                if (values.size == 1 && Regex("(?iu)количество|всего|длительность|период").containsMatchIn(prefix)) counts += values.single()
                else blocks += values
            }
            Regex("(?iu)(?:всего|на|период|семестр)\\s*:?\\s*(\\d{1,3})\\s*(?:учебных\\s+)?недел[ьи]").findAll(line).forEach {
                it.groupValues[1].toIntOrNull()?.takeIf { n -> n in 1..104 }?.let(counts::add)
            }
            Regex("(?iu)(?<!\\d)(\\d{1,3})(?:-?я)?\\s+неделя(?![а-яё])").findAll(line).forEach {
                it.groupValues[1].toIntOrNull()?.takeIf { n -> n in 1..104 }?.let { n -> blocks += setOf(n) }
            }
        }
        val distinct = blocks.distinct()
        return Hints(distinct, (counts + distinct.flatMap { it }).maxOrNull())
    }

    fun enrich(draft: ImportDraft, source: String): ImportDraft {
        val hints = read(source)
        val unique = hints.numberedBlocks.singleOrNull()
        val rows = draft.lessons.map { row ->
            if (row.date.isNotBlank() || row.weeks.isNotBlank()) row
            else if (unique != null) row.copy(weeks = compact(unique))
            else if (hints.numberedBlocks.size > 1) row.copy(evidenceIssues = row.evidenceIssues + "В исходнике несколько блоков недель. Уточните, к каким относится эта строка.")
            else row
        }
        return draft.copy(lessons = rows, suggestedWeeks = hints.count ?: draft.suggestedWeeks)
    }

    private fun compact(weeks: Set<Int>): String {
        val sorted = weeks.sorted()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var end = start
        for (week in sorted.drop(1)) {
            if (week == end + 1) end = week else {
                parts += if (start == end) "$start" else "$start-$end"
                start = week; end = week
            }
        }
        parts += if (start == end) "$start" else "$start-$end"
        return parts.joinToString(",")
    }
}
