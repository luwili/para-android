package app.para.schedule

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.para.schedule.data.*
import app.para.schedule.ui.*
import app.para.schedule.widget.ScheduleWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val date = intent.getStringExtra(ScheduleWidgetProvider.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        setContent { ParaApp(initialDate = date) }
    }
}

private enum class Page(val label: String, val icon: ImageVector) {
    DAY("Расписание", Icons.Outlined.ViewAgenda), MONTH("Месяц", Icons.Outlined.CalendarMonth),
    WIDGET("Виджет", Icons.Outlined.Widgets), IMPORT("Импорт", Icons.Outlined.AutoAwesome), SETTINGS("Настройки", Icons.Outlined.Tune),
}

@Composable
fun ParaApp(initialDate: LocalDate? = null, model: ParaViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val schedule = state.schedule
    val pendingImport by model.pendingImport.collectAsStateWithLifecycle()
    var pageName by rememberSaveable { mutableStateOf(Page.DAY.name) }
    var selectedDate by rememberSaveable { mutableStateOf((initialDate ?: ScheduleEngine.now(schedule).toLocalDate()).toString()) }
    val date = LocalDate.parse(selectedDate)
    var now by remember(schedule.profile.timezone) { mutableStateOf(ScheduleEngine.now(schedule)) }
    var profileEditor by rememberSaveable { mutableStateOf(false) }
    var lessonEditor by remember { mutableStateOf(false) }
    var editingLesson by remember { mutableStateOf<Lesson?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::readImport) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::export) }
    LaunchedEffect(Unit) { model.messages.collect { snackbars.showSnackbar(it) } }
    LaunchedEffect(state.ready, schedule.profile.timezone) {
        if (state.ready && initialDate == null) selectedDate = ScheduleEngine.now(schedule).toLocalDate().toString()
    }
    LaunchedEffect(schedule.profile.timezone) {
        while (true) { now = ScheduleEngine.now(schedule); delay(30_000) }
    }
    val onImport = { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
    val onAdd = { editingLesson = null; lessonEditor = true }
    val onEdit: (Lesson) -> Unit = { editingLesson = it; lessonEditor = true }
    ParaTheme(schedule.profile) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val fullNavLabels = maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.3f
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbars) },
            topBar = {
                if (state.ready && schedule.configured) Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.widthIn(max = 960.dp)) {
                    BrandHeader(schedule.profile.name) { profileEditor = true }
                    }
                }
            },
            bottomBar = {
                if (state.ready && schedule.configured && !wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    Page.entries.forEach { page -> NavigationBarItem(selected = pageName == page.name, onClick = { pageName = page.name },
                        icon = { Icon(page.icon, page.label) }, label = { Text(page.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, alwaysShowLabel = fullNavLabels,
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer)) }
                }
            },
            floatingActionButton = {
                if (state.ready && schedule.configured && pageName in listOf(Page.DAY.name, Page.MONTH.name)) {
                    FloatingActionButton(onClick = onAdd, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Icon(Icons.Outlined.Add, "Добавить занятие")
                    }
                }
            }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide && state.ready && schedule.configured) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Page.entries.forEach { page -> NavigationRailItem(selected = pageName == page.name, onClick = { pageName = page.name },
                        icon = { Icon(page.icon, page.label) }, label = { Text(page.label, maxLines = 1) }) }
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
                when {
                    !state.ready -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    !schedule.configured && pageName != Page.IMPORT.name -> WelcomeScreen(onCreate = { profileEditor = true }, onDemo = { model.update("Открыт демонстрационный пример") { ScheduleEngine.example() } }, onImport = onImport,
                        onAiImport = { pageName = Page.IMPORT.name })
                    else -> when (Page.valueOf(pageName)) {
                        Page.DAY -> ScheduleScreen(schedule, date, now, { selectedDate = it.toString() }, onAdd, onEdit,
                            { confirm = "dayOff" }, { confirm = "restore" })
                        Page.MONTH -> MonthScreen(schedule, date, now, { selectedDate = it.toString() }, onAdd, onEdit,
                            { confirm = "dayOff" }, { confirm = "restore" })
                        Page.WIDGET -> WidgetScreen(schedule, onPin = {
                            val manager = AppWidgetManager.getInstance(context)
                            if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(context, ScheduleWidgetProvider::class.java), null, null)
                            else scope.launch { snackbars.showSnackbar("Добавь вручную: рабочий стол → Виджеты → Пара") }
                        }, onRefresh = {
                            scope.launch {
                                withContext(Dispatchers.IO) { ScheduleWidgetProvider.updateAll(context) }
                                snackbars.showSnackbar("Виджеты обновлены")
                            }
                        })
                        Page.SETTINGS -> SettingsScreen(schedule, { profileEditor = true },
                            { theme -> model.update { it.copy(profile = it.profile.copy(theme = theme)) } },
                            { dynamic -> model.update { it.copy(profile = it.profile.copy(dynamicColors = dynamic)) } },
                            onImport, { exporter.launch("para-${schedule.profile.name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")}.json") }, { confirm = "reset" }, { pageName = Page.IMPORT.name })
                        Page.IMPORT -> AiImportScreen(onBack = { pageName = Page.DAY.name }, onApplied = { pageName = Page.DAY.name })
                    }
                }
            }
            }
            }
        }
        }
        if (profileEditor) ProfileEditor(schedule.profile, onboarding = !schedule.configured,
            onDismiss = { profileEditor = false }, onSave = { profile -> model.update("Группа настроена") { it.copy(profile = profile, configured = true) } })
        if (lessonEditor) LessonEditor(schedule, date, editingLesson, onDismiss = { lessonEditor = false },
            onSave = { lesson, dateOnly, done -> model.saveLesson(date, lesson, dateOnly, done) },
            onDelete = { dateOnly -> editingLesson?.let { lesson -> model.update("Занятие удалено") { ScheduleEdits.delete(it, date, lesson.id, dateOnly) } } })
        pendingImport?.let { imported ->
            AlertDialog(onDismissRequest = model::dismissImport, icon = { Icon(Icons.Outlined.FileOpen, null) },
                title = { Text("Загрузить расписание?") }, text = {
                    Text("${imported.profile.name}\n${imported.profile.timezone}\n${imported.lessons.size} занятий в шаблоне · ${imported.overrides.size} исключений по датам\n\nТекущее расписание будет заменено. При необходимости сначала сохрани его через «Экспорт».${if (imported.demo) "\n\nФайл содержит демонстрационные данные." else ""}")
                }, confirmButton = { TextButton(onClick = model::confirmImport) { Text("Заменить и загрузить") } },
                dismissButton = { TextButton(onClick = model::dismissImport) { Text("Отмена") } })
        }
        confirm?.let { action ->
            AlertDialog(onDismissRequest = { confirm = null }, title = { Text(when (action) { "reset" -> "Начать заново?"; "restore" -> "Вернуть шаблон?"; else -> "Отменить пары?" }) },
                text = { Text(when (action) {
                    "reset" -> "Текущее расписание будет удалено. Сначала сохрани JSON через «Экспорт», если хочешь оставить копию."
                    "restore" -> "Индивидуальные изменения на ${date.format(shortDateFormat)} исчезнут. Вернётся расписание из недельного шаблона."
                    else -> "${date.format(shortDateFormat)} станет свободным днём. Недельный шаблон и другие даты не изменятся."
                }) }, confirmButton = { TextButton(onClick = {
                    when (action) {
                        "reset" -> { model.update("Можно создать новое расписание") { Schedule() }; pageName = Page.DAY.name }
                        "restore" -> model.update("Недельный шаблон восстановлен") { it.copy(overrides = it.overrides - date) }
                        else -> model.update("Пары на эту дату отменены") { it.copy(overrides = it.overrides + (date to emptyList())) }
                    }
                    confirm = null
                }) { Text("Подтвердить") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } })
        }
        state.error?.let { error ->
            LaunchedEffect(error) { snackbars.showSnackbar(error, duration = SnackbarDuration.Long) }
        }
    }
}
