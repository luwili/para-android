package app.para.schedule.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.AddToHomeScreen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import app.para.schedule.data.*
import java.time.format.DateTimeFormatter

@Composable
fun WelcomeScreen(onCreate: () -> Unit, onDemo: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Text("пара", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            SmallBadge("ДЛЯ СТУДЕНТОВ")
        }
        OrbitArt(Modifier.fillMaxWidth().height(180.dp))
        Text("Учёба —\nв своём ритме.", style = MaterialTheme.typography.displaySmall)
        Text("Твоя группа. Твоё расписание.\nВсе пары и свободные дни — в одном спокойном месте.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
                FeatureLine(Icons.Outlined.DateRange, "Неделя и месяц", "Шаблоны, чётность и изменения по датам")
                FeatureLine(Icons.Outlined.Widgets, "Виджет под твой экран", "От компактной строки до полного списка")
                FeatureLine(Icons.Outlined.Lock, "Без аккаунта и интернета", "Расписание хранится только на устройстве")
            }
        }
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
            Text("Создать своё расписание", modifier = Modifier.weight(1f)); Spacer(Modifier.width(10.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
        }
        OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) { Text("Сначала посмотреть пример") }
        TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("У меня уже есть JSON-файл", modifier = Modifier.weight(1f)) }
        Text("Никаких подписок. Просто твой учебный день.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun FeatureLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun WidgetScreen(schedule: Schedule, onPin: () -> Unit, onRefresh: () -> Unit) {
    val now = ScheduleEngine.now(schedule)
    val date = ScheduleEngine.widgetDate(schedule, now)
    val lessons = ScheduleEngine.lessonsOn(schedule, date)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp, 8.dp, 24.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("БЛИЖЕ, ЧЕМ ПРИЛОЖЕНИЕ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text("Важное —\nна главном экране", style = MaterialTheme.typography.headlineLarge)
        Text("Расписание видно сразу. Без поиска приложения и лишних касаний.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(schedule.profile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.Refresh, null, tint = MaterialTheme.colorScheme.primary)
                }
                Text("${date.format(shortDateFormat)} · пример внешнего вида", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (lessons.isEmpty()) {
                    Text("В этот день пар нет ☀", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 25.dp))
                }
                lessons.take(3).forEach { lesson ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .7f)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${lesson.start.format(clockFormat)} — ${lesson.end.format(clockFormat)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(lesson.subject, style = MaterialTheme.typography.titleMedium)
                            if (lesson.room.isNotBlank()) Text(lesson.room, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("2 × 2 · строки", "4 × 2 · колонки", "4 × 4 · карточки").forEach { label ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
            }
        }
        Button(onClick = onPin, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.AutoMirrored.Outlined.AddToHomeScreen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Добавить виджет", modifier = Modifier.weight(1f))
        }
        InfoCard(Icons.Outlined.Tune, "Подстраивается под тебя", "Удерживай виджет на рабочем столе и измени размер. На Android 12+ цвета берутся из системной палитры; на более ранних версиях — светлая или тёмная палитра Пары.")
        InfoCard(Icons.Outlined.Schedule, "Обновления без лишнего расхода", "После правок виджет обновляется сразу. В фоне — примерно раз в 15–30 минут: Android может задерживать обновления в режиме экономии. Для точного статуса сейчас нажми ↻.") {
            TextButton(onClick = onRefresh) { Text("Обновить все виджеты") }
        }
        Text("Если кнопка добавления не работает, удерживай пустое место на рабочем столе → Виджеты → Пара.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SettingsScreen(schedule: Schedule, onProfile: () -> Unit, onTheme: (String) -> Unit,
    onDynamic: (Boolean) -> Unit, onImport: () -> Unit, onExport: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp, 8.dp, 24.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("ВСЁ НА СВОИХ МЕСТАХ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text("Твоё пространство", style = MaterialTheme.typography.headlineLarge)
        InfoCard(Icons.Outlined.Groups, schedule.profile.name,
            "${schedule.profile.timezone}\n${schedule.profile.semesterStart.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} — ${schedule.profile.semesterEnd.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}") {
            TextButton(onClick = onProfile) { Text("Изменить группу и период") }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Внешний вид", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "Система", "light" to "Светлая", "dark" to "Тёмная").forEach { (key, title) ->
                        FilterChip(schedule.profile.theme == key, { onTheme(key) }, label = { Text(title) })
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Цвета обоев", style = MaterialTheme.typography.bodyLarge)
                        Text("Material You в приложении", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(schedule.profile.dynamicColors, onCheckedChange = onDynamic)
                }
            }
        }
        InfoCard(Icons.Outlined.SwapHoriz, "Одно расписание на всю группу", "Заполни расписание один раз и сохрани JSON. Одногруппники смогут открыть тот же файл в Паре. Это обмен копиями, не облачная синхронизация.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImport) { Icon(Icons.Outlined.FileOpen, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Импорт") }
                Button(onClick = onExport) { Icon(Icons.Outlined.SaveAlt, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Экспорт") }
            }
        }
        InfoCard(Icons.Outlined.AutoAwesome, "А если просто прислать фото?", "Распознавание фото, PDF, таблиц и текста — следующий этап. Сейчас работает ручной редактор и JSON. Перед любым будущим автоимпортом можно будет проверить даты и пары: ИИ не должен молча менять расписание.")
        if (schedule.demo) InfoCard(Icons.Outlined.Science, "Сейчас открыт пример", "Это демонстрационные занятия ПОНБ-УП-304С, а не данные университета. Можно удалить пример и создать своё расписание.") {
            TextButton(onClick = onReset) { Text("Начать с чистого листа") }
        } else TextButton(onClick = onReset, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Создать новое расписание")
        }
        Text("Пара · ${app.para.schedule.BuildConfig.VERSION_NAME}\nСделано для спокойных учебных дней.\nБез рекламы, аккаунтов и отправки данных на сервер.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
