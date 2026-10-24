package app.para.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.para.schedule.data.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun ScheduleScreen(
    schedule: Schedule, date: LocalDate, now: ZonedDateTime,
    onDate: (LocalDate) -> Unit, onAdd: () -> Unit, onEdit: (Lesson) -> Unit,
    onDayOff: () -> Unit, onRestore: () -> Unit,
) {
    val lessons = ScheduleEngine.lessonsOn(schedule, date)
    val week = monday(date)
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("УЧЁБА В ТВОЁМ РИТМЕ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text("Всё по плану.", style = MaterialTheme.typography.headlineLarge)
                }
                IconButton(onClick = { onDate(now.toLocalDate()) }, modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface)) {
                    Icon(Icons.Outlined.Today, "Перейти к сегодня")
                }
            }
        }
        item { DayHero(schedule, date, now, lessons) }
        if (schedule.demo) item {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(16.dp)) {
                Text("Демо-расписание · не данные вуза. Начать своё можно в настройках.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onDate(date.minusWeeks(1)) }) { Icon(Icons.Outlined.ChevronLeft, "Предыдущая неделя") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${week.format(DateTimeFormatter.ofPattern("d MMM", russian))} — ${week.plusDays(6).format(DateTimeFormatter.ofPattern("d MMM", russian))}", style = MaterialTheme.typography.titleMedium)
                    val number = ScheduleEngine.weekNumber(schedule, date)
                    Text(if (date < schedule.profile.semesterStart || date > schedule.profile.semesterEnd) "Вне учебного периода" else "$number неделя · ${if (number % 2 == 0) "чётная" else "нечётная"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onDate(date.plusWeeks(1)) }) { Icon(Icons.Outlined.ChevronRight, "Следующая неделя") }
            }
            Spacer(Modifier.height(8.dp))
            WeekStrip(schedule, week, date, now.toLocalDate(), onDate)
        }
        item {
            SectionTitle(if (date == now.toLocalDate()) "Сегодня" else date.format(shortDateFormat)) {
                DayActions(schedule.overrides.containsKey(date), lessons.isNotEmpty(), onDayOff, onRestore)
            }
        }
        if (schedule.overrides.containsKey(date)) item {
            Text("Исключение на эту дату · недельный шаблон не изменён", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (lessons.isEmpty()) item { EmptyDay(onAdd, date < schedule.profile.semesterStart || date > schedule.profile.semesterEnd) }
        itemsIndexed(lessons, key = { _, lesson -> lesson.id }) { index, lesson -> LessonCard(lesson, index, date, now) { onEdit(lesson) } }
        if (lessons.isNotEmpty()) item {
            Text("Нажми на занятие, чтобы изменить его", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 60.dp))
        }
    }
}

@Composable
private fun DayHero(schedule: Schedule, date: LocalDate, now: ZonedDateTime, lessons: List<Lesson>) {
    val today = date == now.toLocalDate()
    val current = lessons.firstOrNull { ScheduleEngine.isCurrent(it, date, now) }
    val next = if (today) lessons.firstOrNull { it.start > now.toLocalTime() } else null
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.fillMaxWidth().heightIn(min = 125.dp)) {
            OrbitArt(Modifier.align(Alignment.CenterEnd).size(112.dp))
            Column(Modifier.padding(18.dp).fillMaxWidth(.72f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", russian)).replaceFirstChar { it.titlecase(russian) },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(when {
                    current != null -> "Сейчас на паре"
                    next != null -> "Следующая в ${next.start.format(clockFormat)}"
                    today && lessons.isNotEmpty() -> "На сегодня всё"
                    lessons.isEmpty() -> "День без пар"
                    else -> "${lessons.size} ${pairWord(lessons.size)} в плане"
                }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(when {
                    current != null -> "${current.subject} · до ${current.end.format(clockFormat)}"
                    next != null -> "Через ${ChronoUnit.MINUTES.between(now.toLocalTime(), next.start).coerceAtLeast(1)} мин · ${next.subject}"
                    lessons.isNotEmpty() -> "${lessons.first().start.format(clockFormat)} — ${lessons.last().end.format(clockFormat)} · ${schedule.profile.timezone.substringAfterLast('/').replace('_', ' ')}"
                    else -> "Оставь место для своих планов"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun pairWord(number: Int) = if (number % 100 in 11..14) "пар" else when (number % 10) { 1 -> "пара"; 2, 3, 4 -> "пары"; else -> "пар" }

@Composable
private fun WeekStrip(schedule: Schedule, week: LocalDate, selected: LocalDate, today: LocalDate, onDate: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        val labels = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")
        repeat(7) { index ->
            val date = week.plusDays(index.toLong())
            val active = selected == date
            val foreground = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                .clickable { onDate(date) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(labels[index], style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .7f))
                Text(date.dayOfMonth.toString(), fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = foreground)
                Box(Modifier.size(4.dp).background(if (date == today || ScheduleEngine.lessonsOn(schedule, date).isNotEmpty()) foreground else androidx.compose.ui.graphics.Color.Transparent, CircleShape))
            }
        }
    }
}

@Composable
fun DayActions(hasOverride: Boolean, hasLessons: Boolean, onDayOff: () -> Unit, onRestore: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.MoreHoriz, "Действия с днём") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (hasLessons) DropdownMenuItem(text = { Text("Отменить пары на эту дату") }, onClick = { expanded = false; onDayOff() }, leadingIcon = { Icon(Icons.Outlined.EventBusy, null) })
            if (hasOverride) DropdownMenuItem(text = { Text("Вернуть недельный шаблон") }, onClick = { expanded = false; onRestore() }, leadingIcon = { Icon(Icons.Outlined.Restore, null) })
            if (!hasLessons && !hasOverride) DropdownMenuItem(text = { Text("Пока нет занятий") }, onClick = { expanded = false }, enabled = false)
        }
    }
}

@Composable
fun MonthScreen(schedule: Schedule, date: LocalDate, now: ZonedDateTime, onDate: (LocalDate) -> Unit,
    onAdd: () -> Unit, onEdit: (Lesson) -> Unit, onDayOff: () -> Unit, onRestore: () -> Unit) {
    var monthString by rememberSaveable { mutableStateOf(YearMonth.from(date).toString()) }
    val month = YearMonth.parse(monthString)
    val lessons = ScheduleEngine.lessonsOn(schedule, date)
    LaunchedEffect(date) { monthString = YearMonth.from(date).toString() }
    LazyColumn(contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("БОЛЬШЕ ЯСНОСТИ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text("Месяц в фокусе", style = MaterialTheme.typography.headlineLarge)
        }
        item {
            Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { monthString = month.minusMonths(1).toString() }) { Icon(Icons.Outlined.ChevronLeft, "Предыдущий месяц") }
                        Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", russian)).replaceFirstChar { it.titlecase(russian) },
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { monthString = month.plusMonths(1).toString() }) { Icon(Icons.Outlined.ChevronRight, "Следующий месяц") }
                    }
                    Row {
                        listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС").forEach {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    val start = monday(month.atDay(1))
                    val rows = ((month.atDay(1).dayOfWeek.value - 1 + month.lengthOfMonth()) + 6) / 7
                    repeat(rows) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            repeat(7) { column ->
                                val day = start.plusDays((row * 7 + column).toLong())
                                val inMonth = YearMonth.from(day) == month
                                val active = date == day
                                val count = ScheduleEngine.lessonsOn(schedule, day).size
                                Column(Modifier.weight(1f).heightIn(min = 45.dp).clip(RoundedCornerShape(13.dp))
                                    .background(if (active) MaterialTheme.colorScheme.primary else if (day == now.toLocalDate()) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable { onDate(day) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val ink = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = ink.copy(alpha = if (inMonth) 1f else .3f))
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        repeat(count.coerceAtMost(3).coerceAtLeast(1)) {
                                            Box(Modifier.size(3.dp).background(if (count > 0) ink.copy(alpha = if (inMonth) .65f else .2f) else androidx.compose.ui.graphics.Color.Transparent, CircleShape))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text("Точки — учебные дни. Нажми на дату, чтобы изменить её.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(6.dp))
                }
            }
        }
        item {
            SectionTitle(date.format(shortDateFormat)) { DayActions(schedule.overrides.containsKey(date), lessons.isNotEmpty(), onDayOff, onRestore) }
            if (schedule.overrides.containsKey(date)) Text("Особое расписание на эту дату", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (lessons.isEmpty()) item { EmptyDay(onAdd, date < schedule.profile.semesterStart || date > schedule.profile.semesterEnd) }
        itemsIndexed(lessons, key = { _, lesson -> lesson.id }) { index, lesson -> LessonCard(lesson, index, date, now) { onEdit(lesson) } }
    }
}
