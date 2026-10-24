package app.para.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.para.schedule.data.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonEditor(schedule: Schedule, date: LocalDate, initial: Lesson?, onDismiss: () -> Unit,
    onSave: (Lesson, Boolean, (Boolean) -> Unit) -> Unit, onDelete: (Boolean) -> Unit) {
    val template = initial != null && schedule.lessons.any { it.id == initial.id } && !schedule.overrides.containsKey(date)
    var subject by rememberSaveable { mutableStateOf(initial?.subject ?: "") }
    var start by rememberSaveable { mutableStateOf(initial?.start?.format(clockFormat) ?: "08:30") }
    var end by rememberSaveable { mutableStateOf(initial?.end?.format(clockFormat) ?: "10:00") }
    var room by rememberSaveable { mutableStateOf(initial?.room ?: "") }
    var teacher by rememberSaveable { mutableStateOf(initial?.teacher ?: "") }
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: "Семинар") }
    var color by rememberSaveable { mutableIntStateOf(initial?.color ?: 0) }
    val outside = date < schedule.profile.semesterStart || date > schedule.profile.semesterEnd
    var scope by rememberSaveable { mutableStateOf(if (schedule.overrides.containsKey(date) || outside) "DATE" else initial?.repeat?.name ?: "EVERY") }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val id = rememberSaveable { initial?.id ?: UUID.randomUUID().toString() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (initial == null) "Новая пара" else "Редактор занятия", style = MaterialTheme.typography.headlineMedium)
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", russian)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(subject, { subject = it.take(160) }, label = { Text("Предмет") }, placeholder = { Text("Например, уголовное право") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), maxLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(start, { start = it.take(5) }, label = { Text("Начало") }, placeholder = { Text("08:30") },
                    modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                OutlinedTextField(end, { end = it.take(5) }, label = { Text("Конец") }, placeholder = { Text("10:00") },
                    modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            }
            Text("Время в поясе ${schedule.profile.timezone} · формат ЧЧ:ММ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Лекция", "Семинар", "Практика", "Лабораторная", "Экзамен", "Другое").forEach { label ->
                    FilterChip(kind == label, onClick = { kind = label }, label = { Text(label) })
                }
            }
            OutlinedTextField(room, { room = it.take(160) }, label = { Text("Аудитория / ссылка") }, leadingIcon = { Icon(Icons.Outlined.LocationOn, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
            OutlinedTextField(teacher, { teacher = it.take(160) }, label = { Text("Преподаватель · необязательно") }, leadingIcon = { Icon(Icons.Outlined.PersonOutline, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            Text("Повторение", style = MaterialTheme.typography.titleMedium)
            Column {
                (listOf("DATE" to "Только ${date.format(shortDateFormat)}") + Repeat.entries.map { it.name to it.label }).forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { scope = value }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(scope == value, onClick = { scope = value })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text(if (scope == "DATE") "Изменится только выбранная дата. Остальные недели останутся как были." else
                "Изменится шаблон для ${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, russian)} на весь учебный период. Уже созданные исключения по датам не меняются.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (scope != "DATE" && outside) Text("Эта дата вне учебного периода: шаблон на ней не отобразится. Выбери «Только дата» или измени период в настройках.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Цвет карточки", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                repeat(4) { index ->
                    Box(Modifier.size(34.dp).clip(CircleShape).background(lessonTint(index)).clickable { color = index }, contentAlignment = Alignment.Center) {
                        if (color == index) Icon(Icons.Outlined.Check, "Выбран цвет ${index + 1}", Modifier.size(18.dp))
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = {
                val parsedStart = parseTime(start)
                val parsedEnd = parseTime(end)
                when {
                    subject.isBlank() -> error = "Напиши название предмета"
                    parsedStart == null || parsedEnd == null -> error = "Введи время в формате 08:30"
                    parsedEnd <= parsedStart -> error = "Конец занятия должен быть позже начала"
                    else -> {
                        error = null
                        val lesson = Lesson(id, subject.trim(), parsedStart, parsedEnd, room.trim(), teacher.trim(), kind, date.dayOfWeek.value,
                            if (scope == "DATE") Repeat.EVERY else Repeat.valueOf(scope), color)
                        // Show collision feedback inline before the asynchronous write.
                        runCatching { ScheduleEdits.save(schedule, date, lesson, scope == "DATE") }
                            .onSuccess {
                                saving = true
                                onSave(lesson, scope == "DATE") { success -> saving = false; if (success) onDismiss() }
                            }.onFailure { error = it.message }
                    }
                }
            }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) { Text("Сохранить занятие") }
            if (initial != null) TextButton(onClick = { deleting = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Удалить занятие")
            }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Удалить занятие?") },
        text = { Text(if (scope == "DATE") "Оно исчезнет только ${date.format(shortDateFormat)}." else if (!template) "Будет удалено из недельного шаблона, если оно там есть. Исключения по датам останутся." else "Оно исчезнет из недельного шаблона. Исключения по датам останутся.") },
        confirmButton = { TextButton(onClick = { onDelete(scope == "DATE"); deleting = false; onDismiss() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } })
}

private fun parseTime(value: String): LocalTime? = if (Regex("\\d{2}:\\d{2}").matches(value)) runCatching { LocalTime.parse(value) }.getOrNull() else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditor(initial: GroupProfile, onboarding: Boolean = false, onDismiss: () -> Unit, onSave: (GroupProfile) -> Unit) {
    var name by rememberSaveable { mutableStateOf(if (onboarding) "" else initial.name) }
    var timezone by rememberSaveable { mutableStateOf(initial.timezone) }
    var start by rememberSaveable { mutableStateOf(initial.semesterStart.toString()) }
    var end by rememberSaveable { mutableStateOf(initial.semesterEnd.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(if (onboarding) "Давай знакомиться" else "Твоя группа", style = MaterialTheme.typography.headlineMedium)
            Text("Один раз настрой основу — и расписание будет работать даже без интернета.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Название группы") }, placeholder = { Text("Например, ПОНБ-УП-304С") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            OutlinedTextField(timezone, { timezone = it }, label = { Text("Часовой пояс (IANA)") }, supportingText = { Text("Не зависит от часового пояса телефона") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Europe/Moscow" to "Москва", "Asia/Yekaterinburg" to "Уфа / Екатеринбург", "Asia/Novosibirsk" to "Новосибирск", ZoneId.systemDefault().id to "Как на телефоне").distinctBy { it.first }.forEach { (zone, label) ->
                    FilterChip(timezone == zone, { timezone = zone }, label = { Text(label) })
                }
            }
            DateField("Понедельник первой учебной недели", LocalDate.parse(start)) { start = monday(it).toString() }
            DateField("Последний день учебного периода", LocalDate.parse(end)) { end = it.toString() }
            Text("От первой недели считаются чётность и номер недели. Недельный шаблон действует только внутри этого периода; разовые занятия можно добавить на любую дату.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                val from = LocalDate.parse(start)
                val to = LocalDate.parse(end)
                when {
                    name.isBlank() -> error = "Напиши название группы"
                    runCatching { ZoneId.of(timezone.trim()) }.isFailure -> error = "Часовой пояс не найден. Например: Asia/Yekaterinburg"
                    to < from || to > from.plusYears(2) -> error = "Период должен быть от 1 дня до 2 лет"
                    else -> { onSave(initial.copy(name = name.trim(), timezone = timezone.trim(), semesterStart = from, semesterEnd = to)); onDismiss() }
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) { Text(if (onboarding) "Создать расписание" else "Сохранить настройки") }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DateField(label: String, date: LocalDate, onDate: (LocalDate) -> Unit) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(onClick = { showPicker = true }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", russian)), style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (showPicker) {
        val compact = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.3f
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            initialDisplayMode = if (compact) DisplayMode.Input else DisplayMode.Picker,
        )
        DatePickerDialog(onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { onDate(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()) }
                showPicker = false
            }, enabled = state.selectedDateMillis != null) { Text("Выбрать") } },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Отмена") } }) {
            DatePicker(state, modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    }
}
