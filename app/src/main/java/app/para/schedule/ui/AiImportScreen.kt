package app.para.schedule.ui

import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.para.schedule.ai.*
import app.para.schedule.data.*
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiImportScreen(onBack: () -> Unit, onApplied: () -> Unit, model: AiImportViewModel = viewModel()) {
    val ui by model.state.collectAsStateWithLifecycle()
    val packages by model.packages.collectAsStateWithLifecycle()
    var modelsOpen by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DraftLesson?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var reviewed by rememberSaveable { mutableStateOf(false) }
    var byWeek by rememberSaveable { mutableStateOf(false) }
    var week by rememberSaveable { mutableIntStateOf(1) }
    var weekText by remember(ui.options.weekCount) { mutableStateOf(ui.options.weekCount.toString()) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri?.let(android.net.Uri::parse)
        if (ok && uri != null) model.importFile(uri)
        else if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importFile) }
    BackHandler { if (ui.busy) confirm = "cancel" else if (ui.draft != null) model.backToSource() else onBack() }
    LaunchedEffect(ui.draft, ui.options) { reviewed = false; week = week.coerceIn(1, ui.options.weekCount) }
    LaunchedEffect(Unit) { model.refreshDevice() }
    LaunchedEffect(modelsOpen) { if (modelsOpen) model.refreshDevice() }
    LaunchedEffect(ui.busy, ui.draft != null) {
        keyboard?.hide()
        focus.clearFocus()
        listState.scrollToItem(0)
    }
    DisposableEffect(ui.busy) {
        val window = (context as? android.app.Activity)?.window
        if (ui.busy) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val plan = ui.plan
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("ai-import-list"), state = listState, contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (ui.busy) confirm = "cancel" else if (ui.draft != null) model.backToSource() else onBack() }) { Icon(Icons.Outlined.ChevronLeft, "Назад") }
                Text(if (ui.draft == null) "Умный импорт" else "Проверь расписание", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            }
            Text("Только на устройстве · без отправки фото и документов на сервер", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            val selected = if (ui.selection == "auto") ModelPolicy.recommend(ui.device, packages.filter { it.ready }.map { it.asset.id }.toSet()) else ModelCatalog.find(ui.selection)
            OutlinedCard(onClick = { modelsOpen = true }, enabled = !ui.busy, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Memory, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(if (ui.selection == "auto") "Авто: ${selected?.title ?: "LLM сейчас недоступна"}" else selected!!.title, style = MaterialTheme.typography.titleMedium)
                        Text(selected?.parameters ?: "Остаются OCR и разбор строк без ИИ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.Tune, "Выбрать модель")
                }
            }
        }
        ui.error?.let { message -> item {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) { Text(message, color = MaterialTheme.colorScheme.onErrorContainer); TextButton(onClick = model::clearError) { Text("Понятно") } }
            }
        } }
        if (ui.busy) item {
            InfoCard(Icons.Outlined.AutoAwesome, "Локальная обработка", "${ui.status}\nОставайтесь в приложении. Пока идёт разбор, текущее расписание не меняется.") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = model::cancel) { Text("Остановить") }
            }
        }
        if (ui.draft == null) {
            item {
                OutlinedTextField(ui.options.group, { model.options(ui.options.copy(group = it.take(80))) }, enabled = !ui.busy,
                    label = { Text("Группа") }, supportingText = { Text("Если в таблице несколько групп, модель должна выбрать именно эту") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(ui.options.timezone, { model.options(ui.options.copy(timezone = it)) }, enabled = !ui.busy,
                    label = { Text("Часовой пояс IANA") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true)
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf", "text/*", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }, enabled = !ui.busy) {
                        Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Фото / документ")
                    }
                    OutlinedButton(onClick = {
                        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
                        val file = File(dir, "photo-${UUID.randomUUID()}.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        cameraUri = uri.toString()
                        runCatching { photo.launch(uri) }.onFailure { model.notice("Камера недоступна. Выберите готовое фото через «Фото / документ».") }
                    }, enabled = !ui.busy) { Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Камера") }
                }
                Text("PDF до 12 страниц · DOCX · XLSX до 8 листов · CSV/TXT · фото. Файл до 20 МБ. Для фото и сканов нужен OCR-пакет в разделе моделей.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(ui.source, model::source, enabled = !ui.busy, label = { Text("Исходный текст расписания") },
                    placeholder = { Text("Недели 1-3\nПонедельник\n08:30–10:00 Математика, каб. 310") },
                    minLines = 7, maxLines = 16, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    supportingText = { Text("${ui.sourceName} · ${ui.source.length}/24000. После OCR проверьте порядок строк, даты и группу.") })
            }
            item {
                Button(onClick = {
                    val selected = if (ui.selection == "auto") ModelPolicy.recommend(ui.device, packages.filter { it.ready }.map { it.asset.id }.toSet()) else ModelCatalog.find(ui.selection)
                    if (selected != null && !model.isModelReady(selected.id)) modelsOpen = true else model.analyze(true)
                }, enabled = !ui.busy && ui.source.isNotBlank() && ui.source.length <= 24000 && ui.options.group.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) { Text("Собрать черновик локальной моделью") }
                OutlinedButton(onClick = { model.analyze(false) }, enabled = !ui.busy && ui.source.isNotBlank() && ui.source.length <= 24000 && ui.options.group.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp), shape = RoundedCornerShape(18.dp)) { Text("Разобрать простые строки без ИИ") }
                Text("ИИ не гарантирует правильность. Следующий экран — проверка и выбор периода, а не автоматическая запись в календарь.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            }
            if (ui.hasBackup) item { TextButton(onClick = { confirm = "restore" }, enabled = !ui.busy) { Text("Вернуть расписание до последнего автоимпорта") } }
        } else {
            item {
                Text("${ui.options.group} · ${ui.options.timezone}", style = MaterialTheme.typography.titleMedium)
                Text("Найдено строк: ${ui.draft!!.lessons.size}. В календаре: ${plan?.lessonCount ?: 0} пар, ${plan?.days?.size ?: 0} дат.", style = MaterialTheme.typography.bodyMedium)
            }
            item {
                DateField("Понедельник первой недели импортируемого периода", ui.options.firstMonday) { model.options(ui.options.copy(firstMonday = monday(it))) }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(weekText, { value -> weekText = value.take(3); value.toIntOrNull()?.takeIf { it in 1..104 }?.let { model.options(ui.options.copy(weekCount = it)) } },
                    label = { Text("Количество недель · 1–104") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = weekText.toIntOrNull() !in 1..104, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    supportingText = { Text("До ${ui.options.end.format(shortDateFormat)} ${ui.options.end.year}. Это недели периода, а не число пар. Предложение модели нужно проверить.") })
            }
            if (ui.draft!!.lessons.any { it.date.isBlank() && it.weeks.isBlank() && it.repeat == Repeat.EVERY } && ui.options.weekCount > 1) item {
                Text("Есть занятия без даты и номеров недель. Как их повторять?", style = MaterialTheme.typography.titleMedium)
                UndatedPolicy.entries.forEach { value -> ChoiceRow(value.label, ui.options.undatedPolicy == value) { model.options(ui.options.copy(undatedPolicy = value)) } }
            }
            item {
                Text("Как применить", style = MaterialTheme.typography.titleMedium)
                ApplyMode.entries.forEach { mode -> ChoiceRow(mode.label, ui.options.applyMode == mode) { model.options(ui.options.copy(applyMode = mode)) } }
                if (ui.options.applyMode == ApplyMode.DATES) Text("Каждая указанная дата заменяется целиком, включая ранее сохранённые пары этого дня. Для неполного фрагмента выберите «Добавить без пересечений».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ui.options.applyMode in listOf(ApplyMode.PERIOD, ApplyMode.ALL)) Text("Этот режим удаляет старые занятия выбранного периода или всего расписания. Перед применением сохраняется одна резервная копия.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            item {
                var dates by rememberSaveable { mutableStateOf(ui.draft!!.daysOff.joinToString(", ")) }
                OutlinedTextField(dates, { dates = it; model.daysOff(it) }, label = { Text("Явно свободные даты, через запятую") },
                    placeholder = { Text("04.11, 2026-12-31") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                if (ui.draft!!.daysOff.isNotEmpty() && !ui.draft!!.daysOffConfirmed) TextButton(onClick = model::confirmDaysOff) { Text("Я проверил все отмены и свободные даты") }
            }
            if (!plan?.warnings.isNullOrEmpty()) item {
                InfoCard(Icons.Outlined.Info, "Нужно проверить", plan!!.warnings.take(12).joinToString("\n"))
            }
            plan?.problems?.filter { it.rowId == null }?.forEach { problem -> item { Text(problem.message, color = MaterialTheme.colorScheme.error) } }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!byWeek, { byWeek = false }, label = { Text("Исходные строки") })
                    FilterChip(byWeek, { byWeek = true }, label = { Text("По неделям") })
                }
            }
            if (byWeek) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { week-- }, enabled = week > 1) { Icon(Icons.Outlined.ChevronLeft, "Предыдущая неделя") }
                        Text("$week из ${ui.options.weekCount} · ${ui.options.firstMonday.plusWeeks((week - 1).toLong()).format(shortDateFormat)}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { week++ }, enabled = week < ui.options.weekCount) { Icon(Icons.Outlined.ChevronRight, "Следующая неделя") }
                    }
                }
                items((0..6).map { ui.options.firstMonday.plusWeeks((week - 1).toLong()).plusDays(it.toLong()) }) { date ->
                    val lessons = plan?.days?.get(date)
                    InfoCard(Icons.Outlined.CalendarMonth, date.format(DateTimeFormatter.ofPattern("EEEE, d MMM", russian)),
                        when { lessons == null -> "В исходнике не указано"; lessons.isEmpty() -> "Явно свободный день"; else -> lessons.joinToString("\n") { "${it.start.format(clockFormat)}–${it.end.format(clockFormat)} · ${it.subject}" } })
                }
            } else items(ui.draft!!.lessons, key = { it.id }) { row ->
                val problems = plan?.problems?.filter { it.rowId == row.id }?.map { it.message }.orEmpty()
                Surface(onClick = { editing = row }, shape = RoundedCornerShape(20.dp), color = if (problems.isEmpty()) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.subject.ifBlank { "Предмет не определён" }, style = MaterialTheme.typography.titleMedium)
                        Text("${row.start.ifBlank { "?" }}–${row.end.ifBlank { "?" }} · ${row.date.ifBlank { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").getOrNull((row.day ?: 0) - 1) ?: "Нужен день" }}", style = MaterialTheme.typography.bodyMedium)
                        Text("${row.repeat.label}${if (row.weeks.isNotBlank()) " · недели ${row.weeks}" else ""}", style = MaterialTheme.typography.bodySmall)
                        if (row.room.isNotBlank()) Text(row.room, style = MaterialTheme.typography.bodySmall)
                        problems.take(4).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Text("Нажми, чтобы исправить", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (!row.verifiedByUser && row.evidenceIssues.isNotEmpty()) TextButton(onClick = { model.edit(row) }) { Text("Я сверил данные этой строки") }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(reviewed, { reviewed = it })
                    Text("Я проверил даты, недели, время, полноту и принадлежность своей группе", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                Text("Ошибок: ${plan?.problems?.size ?: 0}. Перед заменой автоматически сохраняется состояние до импорта.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { confirm = "apply" }, enabled = !ui.busy && reviewed && weekText.toIntOrNull() in 1..104 && plan != null && plan.problems.isEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) { Text("Сохранить в расписание") }
            }
        }
    }
    if (modelsOpen) ModelChooserSheet(ui, packages, model, onDismiss = { modelsOpen = false })
    editing?.let { row -> DraftRowEditor(row, onDismiss = { editing = null }, onSave = model::edit, onDelete = { model.remove(row.id) }) }
    confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text(when (action) { "apply" -> "Применить черновик?"; "restore" -> "Восстановить копию?"; else -> "Остановить разбор?" }) },
        text = { Text(when (action) {
            "apply" -> "${ui.options.applyMode.label}. Период ${ui.options.firstMonday} — ${ui.options.end}. Будет ${plan?.lessonCount ?: 0} пар. Это действие изменяет локальное расписание."
            "restore" -> "Вернётся состояние перед последним AI-импортом. Последующие изменения будут потеряны."
            else -> "Обработка остановится. Текущее расписание не изменено."
        }) }, confirmButton = { TextButton(onClick = { confirm = null; when (action) { "apply" -> model.apply(onApplied); "restore" -> model.restore(onApplied); else -> model.cancel() } }) { Text("Подтвердить") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } }) }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick); TextButton(onClick, modifier = Modifier.weight(1f)) { Text(label, modifier = Modifier.fillMaxWidth()) } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelChooserSheet(ui: AiImportState, packages: List<AssetStatus>, model: AiImportViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    var delete by remember { mutableStateOf<String?>(null) }
    var license by remember { mutableStateOf<String?>(null) }
    val recommended = ModelPolicy.recommend(ui.device, packages.filter { it.ready }.map { it.asset.id }.toSet())
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxWidth().testTag("model-list"), contentPadding = PaddingValues(24.dp, 0.dp, 24.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Модель под твоё устройство", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Закрыть выбор модели") }
                }
                Text("${Build.MANUFACTURER} ${Build.MODEL}\nRAM ${size(ui.device.ram)}, свободно ${size(ui.device.availableRam)}\nХранилище: свободно ${size(ui.device.freeStorage)} · CPU ${ui.device.cpuCount} ядер\n${if (ui.device.supports64Bit) "64-битный CPU-движок" else "LLM недоступна: нужен 64-битный Android"}", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = model::refreshDevice) { Text("Обновить оценку") }
                ChoiceRow("Автоматически · ${recommended?.parameters ?: "нет безопасного варианта сейчас"}", ui.selection == "auto") { model.selectModel("auto") }
                if (ui.selection == "auto" && recommended != null && packages.none { it.asset.id == recommended.id && (it.ready || it.active || it.phase == "failed") }) {
                    Button(onClick = { model.download(recommended.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Скачать рекомендованную · ${size(recommended.bytes)}")
                    }
                }
                Text("Авто выбирает по RAM, доступной памяти, месту и архитектуре. Скачивание начинается только по кнопке. Скорость и качество на конкретном телефоне не гарантированы.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Разрешить мобильную сеть для новых загрузок", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(ui.mobile, model::setMobile)
                }
            }
            items(ModelCatalog.models, key = { it.id }) { spec ->
                val status = packages.firstOrNull { it.asset.id == spec.id }
                Surface(shape = RoundedCornerShape(22.dp), color = if (ui.selection == spec.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        ChoiceRow("${spec.title}${if (recommended?.id == spec.id) " · рекомендация" else ""}", ui.selection == spec.id) { model.selectModel(spec.id) }
                        Text(spec.parameters, style = MaterialTheme.typography.titleMedium)
                        Text("Файл ${size(spec.bytes)} · свободная RAM для запуска от ${size(spec.workingMemory)}\nДля установки нужно около ${size(ModelPolicy.storageNeeded(spec))} свободного места (временная и проверенная копии).", style = MaterialTheme.typography.bodySmall)
                        if (ui.device.ram < spec.minimumRam) Text("Меньше рекомендованной RAM. Ручной выбор возможен, но запуск потребует достаточно свободной памяти.", style = MaterialTheme.typography.bodySmall)
                        ModelPolicy.reason(spec, ui.device)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        val speed = model.speed(spec.id)
                        if (speed > 0) Text("На этом устройстве: примерно ${"%.1f".format(speed)} токенов/с с учётом входного текста", style = MaterialTheme.typography.bodySmall)
                        AssetProgress(status)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (status?.ready == true) OutlinedButton(onClick = { delete = spec.id }) { Text("Удалить пакет") }
                            else if (status != null && status.phase in listOf("download", "paused", "verify", "failed")) TextButton(onClick = { model.cancelDownload(spec.id) }) { Text("Отменить / сбросить") }
                            else Button(onClick = { model.download(spec.id) }, enabled = ui.device.supports64Bit && !ui.device.lowRam && ui.device.freeStorage >= ModelPolicy.storageNeeded(spec)) { Text("Скачать ${size(spec.bytes)}") }
                        }
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(spec.url.substringBefore("/resolve/")))) } }) { Text("Источник и лицензия модели") }
                    }
                }
            }
            item {
                InfoCard(Icons.Outlined.DocumentScanner, "OCR для фото и сканов", "Tesseract · русский + английский. ${size(AiAssets.ocr.sumOf { it.bytes })}. Нужен для фото и PDF без текстового слоя; на Android до 15 также для обычных PDF. Всё распознаётся локально.") {
                    AiAssets.ocr.forEach { asset -> Text(asset.title, style = MaterialTheme.typography.labelMedium); AssetProgress(packages.firstOrNull { it.asset.id == asset.id }) }
                    if (AiAssets.ocr.all { asset -> packages.any { it.asset.id == asset.id && it.ready } }) Text("OCR готов")
                    else Button(onClick = model::downloadOcr) { Text("Скачать OCR-пакет") }
                    AiAssets.ocr.filter { asset -> packages.any { it.asset.id == asset.id && (it.active || it.phase == "failed") } }.forEach { asset ->
                        TextButton(onClick = { model.cancelDownload(asset.id) }) { Text("Отменить ${asset.title}") }
                    }
                    if (AiAssets.ocr.any { asset -> packages.any { it.asset.id == asset.id && it.ready } }) TextButton(onClick = { delete = "ocr-rus" }) { Text("Удалить OCR-пакет") }
                }
            }
            item { Text("После загрузки проверяется SHA-256, затем пакет переносится в закрытое хранилище приложения. Документы в эти запросы не попадают. Локальный CPU-движок работает без облака; GPU/NPU автоматически не обещаются.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("NOTICE.txt" to "Компоненты", "Apache-2.0.txt" to "Apache 2.0", "llama-MIT.txt" to "MIT", "Leptonica.txt" to "Leptonica", "libpng.txt" to "libpng", "libjpeg-README.txt" to "JPEG").forEach { (path, label) ->
                        TextButton(onClick = { license = path }) { Text(label) }
                    }
                }
            }
        }
    }
    delete?.let { id -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Удалить локальный пакет?") }, text = { Text("Расписание не удалится. Для следующего распознавания пакет потребуется скачать заново.") },
        confirmButton = { TextButton(onClick = { delete = null; if (id.startsWith("ocr-")) AiAssets.ocr.forEach { model.deletePackage(it.id) } else model.deletePackage(id) }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Отмена") } }) }
    license?.let { path ->
        val text = remember(path) { context.assets.open("licenses/$path").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { license = null }, title = { Text(path) },
            text = { Text(text, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { license = null }) { Text("Закрыть") } })
    }
}

@Composable
private fun AssetProgress(status: AssetStatus?) {
    when {
        status?.ready == true -> Text("Установлено · SHA-256 проверен", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        status?.phase == "verify" -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Проверяем SHA-256 и сохраняем пакет", style = MaterialTheme.typography.bodySmall) }
        status?.active == true -> {
            LinearProgressIndicator(progress = { (status.received.toDouble() / status.asset.bytes).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(if (status.phase == "paused") "Ожидание сети / Wi-Fi" else "Скачано ${(status.received * 100 / status.asset.bytes).coerceIn(0, 100)}%", style = MaterialTheme.typography.bodySmall)
        }
    }
    status?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DraftRowEditor(row: DraftLesson, onDismiss: () -> Unit, onSave: (DraftLesson) -> Unit, onDelete: () -> Unit) {
    var subject by rememberSaveable { mutableStateOf(row.subject) }
    var start by rememberSaveable { mutableStateOf(row.start) }
    var end by rememberSaveable { mutableStateOf(row.end) }
    var date by rememberSaveable { mutableStateOf(row.date) }
    var day by rememberSaveable { mutableIntStateOf(row.day ?: 0) }
    var weeks by rememberSaveable { mutableStateOf(row.weeks) }
    var repeat by rememberSaveable { mutableStateOf(row.repeat.name) }
    var room by rememberSaveable { mutableStateOf(row.room) }
    var teacher by rememberSaveable { mutableStateOf(row.teacher) }
    var kind by rememberSaveable { mutableStateOf(row.kind) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp, 0.dp, 24.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Исправить строку", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(subject, { subject = it.take(160) }, label = { Text("Предмет") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(start, { start = it.take(8) }, label = { Text("Начало") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(end, { end = it.take(8) }, label = { Text("Конец") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            OutlinedTextField(date, { date = it.take(10) }, label = { Text("Дата · необязательно") }, supportingText = { Text("ГГГГ-ММ-ДД или ДД.ММ. Для повторения оставьте пустой.") }, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("?", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEachIndexed { index, label -> FilterChip(day == index, { day = index }, label = { Text(label) }) } }
            OutlinedTextField(weeks, { weeks = it.take(100) }, label = { Text("Номера недель · например 1-4,6") }, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { Repeat.entries.forEach { value -> FilterChip(repeat == value.name, { repeat = value.name }, label = { Text(value.label) }) } }
            OutlinedTextField(room, { room = it.take(160) }, label = { Text("Кабинет") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(teacher, { teacher = it.take(160) }, label = { Text("Преподаватель") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(kind, { kind = it.take(40) }, label = { Text("Тип занятия") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onSave(row.copy(subject = subject.trim(), start = start.trim(), end = end.trim(), date = date.trim(), day = day.takeIf { it in 1..7 }, weeks = weeks.trim(), repeat = Repeat.valueOf(repeat), room = room.trim(), teacher = teacher.trim(), kind = kind.trim(), verifiedByUser = true)); onDismiss() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) { Text("Подтвердить исправления") }
            TextButton(onClick = { onDelete(); onDismiss() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Удалить строку из черновика") }
        }
    }
}
