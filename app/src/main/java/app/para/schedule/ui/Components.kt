package app.para.schedule.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import app.para.schedule.data.*
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
val shortDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", russian)

@Composable
fun BrandHeader(group: String, onGroup: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text("пара", fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        Spacer(Modifier.width(12.dp))
        Surface(onClick = onGroup, modifier = Modifier.weight(1f), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(group, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(17.dp))
            }
        }
    }
}

@Composable
fun OrbitArt(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.primaryContainer
    Canvas(modifier) {
        val center = Offset(size.width * .53f, size.height * .52f)
        val radius = size.minDimension * .31f
        drawCircle(ink.copy(alpha = .08f), radius * 1.6f, center)
        drawCircle(ink.copy(alpha = .23f), radius * 1.28f, center, style = Stroke(1.5.dp.toPx()))
        drawCircle(ink.copy(alpha = .45f), radius, center, style = Stroke(1.5.dp.toPx()))
        drawCircle(paper, radius * .69f, center)
        drawCircle(ink, 6.dp.toPx(), Offset(center.x + radius * 1.07f, center.y - radius * .66f))
        drawCircle(ink.copy(alpha = .5f), 4.dp.toPx(), Offset(center.x - radius * .7f, center.y + radius * .69f))
        drawLine(ink, Offset(center.x - radius * .25f, center.y), Offset(center.x - radius * .02f, center.y + radius * .24f), 3.dp.toPx(), StrokeCap.Round)
        drawLine(ink, Offset(center.x - radius * .02f, center.y + radius * .24f), Offset(center.x + radius * .34f, center.y - radius * .23f), 3.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
fun SectionTitle(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun SmallBadge(text: String, active: Boolean = false) {
    Box(Modifier.clip(CircleShape).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
        .padding(horizontal = 9.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun LessonCard(lesson: Lesson, index: Int, date: LocalDate, now: ZonedDateTime, onClick: () -> Unit) {
    val current = ScheduleEngine.isCurrent(lesson, date, now)
    val past = date < now.toLocalDate() || (date == now.toLocalDate() && now.toLocalTime() >= lesson.end)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!stacked) Column(Modifier.width(55.dp).padding(top = 15.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(lesson.start.format(clockFormat), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(lesson.end.format(clockFormat), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.width(2.dp).height(26.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
        Surface(onClick = onClick, modifier = Modifier.weight(1f), shape = RoundedCornerShape(23.dp),
            color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = if (current) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .45f)) else null) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (stacked) Text("${lesson.start.format(clockFormat)} — ${lesson.end.format(clockFormat)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(31.dp).clip(RoundedCornerShape(10.dp)).background(lessonTint(lesson.color)), contentAlignment = Alignment.Center) {
                        Icon(if (lesson.kind == "Лекция") Icons.AutoMirrored.Outlined.MenuBook else Icons.Outlined.EditNote, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Text("${index + 1} ПАРА · ${lesson.kind.uppercase(russian)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (current) SmallBadge("СЕЙЧАС", true)
                    else if (past) Icon(Icons.Outlined.Check, "Завершена", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(lesson.subject, style = MaterialTheme.typography.titleMedium)
                if (lesson.room.isNotBlank() || lesson.teacher.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Outlined.LocationOn, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(listOf(lesson.room, lesson.teacher).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
    }
}

@Composable
fun EmptyDay(onAdd: () -> Unit, outsidePeriod: Boolean = false) {
    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(27.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.WbSunny, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.tertiary)
            Text(if (outsidePeriod) "Вне учебного периода" else "Можно выдохнуть", style = MaterialTheme.typography.titleLarge)
            Text(if (outsidePeriod) "Шаблон здесь не действует. Можно добавить занятие на эту дату." else "На этот день пар нет. Время для себя — тоже часть расписания.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAdd) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text("Добавить занятие") }
        }
    }
}

@Composable
fun InfoCard(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}
