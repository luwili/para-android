package app.para.schedule.ai

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.para.schedule.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class AiImportState(
    val source: String, val sourceName: String, val options: ImportOptions,
    val draft: ImportDraft? = null, val plan: ImportPlan? = null,
    val busy: Boolean = false, val status: String = "", val error: String? = null,
    val selection: String = "auto", val mobile: Boolean = false, val device: DeviceProfile,
    val hasBackup: Boolean = false,
)

class AiImportViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val repository = ScheduleRepository.get(context)
    private val assets = AiAssetStore(context)
    private fun initial(): AiImportState {
        val schedule = repository.state.value.schedule
        val p = schedule.profile
        return AiImportState("", "Вставленный текст", ImportOptions(if (schedule.configured) p.name else "", p.timezone, p.semesterStart,
            (ChronoUnit.WEEKS.between(p.semesterStart, p.semesterEnd).toInt() + 1).coerceIn(1, 104)),
            selection = assets.selection, mobile = assets.mobile, device = DeviceInspector.inspect(context), hasBackup = repository.hasImportBackup())
    }
    private val mutable = MutableStateFlow(initial())
    val state = mutable.asStateFlow()
    val packages = flow {
        while (currentCoroutineContext().isActive) { emit(withContext(Dispatchers.IO) { assets.snapshot() }); delay(1500) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var job: Job? = null

    fun refreshDevice() { mutable.update { it.copy(device = DeviceInspector.inspect(context)) } }
    fun selectModel(id: String) { assets.selection = id; mutable.update { it.copy(selection = id) }; refreshDevice() }
    fun setMobile(value: Boolean) { assets.mobile = value; mutable.update { it.copy(mobile = value) } }
    fun download(id: String) = assetAction { assets.start(id) }
    fun downloadOcr() = assetAction { AiAssets.ocr.filterNot { assets.ready(it.id) || assets.downloadId(it.id) >= 0 }.forEach { assets.start(it.id) } }
    fun cancelDownload(id: String) = assetAction { assets.cancel(id) }
    fun deletePackage(id: String) = assetAction { check(!state.value.busy) { "Сначала завершите разбор" }; assets.delete(id) }
    fun speed(id: String) = assets.speed(id)
    fun isModelReady(id: String) = assets.ready(id)
    private fun assetAction(block: () -> Unit) { viewModelScope.launch(Dispatchers.IO) { runCatching(block).onFailure { error(it) }; refreshDevice() } }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun notice(message: String) { mutable.update { it.copy(error = message) } }
    private fun error(e: Throwable) { if (e !is CancellationException) mutable.update { it.copy(error = e.message?.take(300) ?: "Не удалось выполнить действие") } }

    fun source(value: String) { if (!state.value.busy) mutable.update { it.copy(source = value.take(100000), draft = null, plan = null,
        error = if (value.length > 24000) "Лимит разбора — 24 000 символов. Сократите текст; длинная вставка не будет импортирована." else null) } }
    fun options(value: ImportOptions) {
        mutable.update { current -> current.copy(options = value, plan = current.draft?.let { preview(it, value) }) }
    }
    fun backToSource() { if (!state.value.busy) mutable.update { it.copy(draft = null, plan = null, error = null) } }
    fun cancel() {
        val running = job
        mutable.update { it.copy(status = "Останавливаем локальную обработку") }
        viewModelScope.launch { running?.cancelAndJoin(); mutable.update { it.copy(busy = false, status = "Отменено. Расписание не менялось.") } }
    }
    fun reset() { cancel(); mutable.value = initial() }

    fun importFile(uri: Uri) {
        if (state.value.busy) return
        job = viewModelScope.launch(Dispatchers.IO) {
            mutable.update { it.copy(busy = true, status = "Читаем выбранный файл", error = null, draft = null, plan = null) }
            var file: File? = null
            try {
                val resolver = context.contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.take(160) else null } ?: "document"
                val kind = DocumentExtractor.kind(name, resolver.getType(uri))
                val dir = File(context.cacheDir, "imports").apply { mkdirs() }
                val staged = File(dir, "${UUID.randomUUID()}.input")
                file = staged
                resolver.openInputStream(uri)?.use { input -> staged.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= 20 * MIB) { "Файл больше 20 МБ. Сохраните нужные страницы отдельно." }
                        output.write(buffer, 0, n)
                    }
                } } ?: error("Не удалось открыть файл")
                LocalAiSession(context) { status -> mutable.update { it.copy(status = status) } }.use { session ->
                    session.connect()
                    val text = session.extract(staged, kind)
                    mutable.update { it.copy(source = text, sourceName = name, status = "Текст извлечён. Проверьте порядок строк и свою группу.") }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { error(e) }
            finally {
                file?.delete()
                if (uri.authority == "${context.packageName}.files" && uri.path?.startsWith("/capture/") == true) runCatching { context.contentResolver.delete(uri, null, null) }
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun analyze(withModel: Boolean) {
        if (state.value.busy) return
        val snapshot = state.value
        job = viewModelScope.launch(Dispatchers.IO) {
            mutable.update { it.copy(busy = true, status = "Проверяем исходник", error = null) }
            try {
                require(snapshot.options.group.trim().length in 1..80) { "Сначала укажите группу (1–80 символов)" }
                java.time.ZoneId.of(snapshot.options.timezone.trim())
                require(snapshot.source.isNotBlank()) { "Добавьте фото, документ или текст" }
                require(snapshot.source.length <= 24000) { "Сократите исходник до 24 000 символов" }
                var draft = if (!withModel) SimpleScheduleParser.parse(snapshot.source) else {
                    val device = DeviceInspector.inspect(context)
                    val installed = ModelCatalog.models.filter { assets.ready(it.id) }.map { it.id }.toSet()
                    val model = if (snapshot.selection == "auto") ModelPolicy.recommend(device, installed)
                        ?: error("Сейчас нет подходящей модели. Проверьте память или используйте разбор строк без ИИ.") else ModelCatalog.find(snapshot.selection)
                    require(assets.ready(model.id)) { "Сначала скачайте ${model.parameters} через «Модели»" }
                    ModelPolicy.reason(model, device)?.let { error(it) }
                    if (Build.VERSION.SDK_INT >= 29) require(context.getSystemService(PowerManager::class.java).currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE) { "Телефон перегрет. Дайте ему остыть перед запуском модели." }
                    mutable.update { it.copy(status = "Проверяем SHA-256 модели", device = device) }
                    assets.verifyPrivate(model.id) { !isActive }
                    val chunks = DraftJson.chunks(snapshot.source, if (model == ModelCatalog.models.first()) 1800 else 2600)
                    val rows = mutableListOf<DraftLesson>()
                    val off = mutableListOf<String>()
                    val warnings = mutableListOf<String>()
                    var weeks: Int? = null
                    LocalAiSession(context) { status -> mutable.update { it.copy(status = status) } }.use { session ->
                        session.connect()
                        for ((index, chunk) in chunks.withIndex()) {
                            ensureActive()
                            mutable.update { it.copy(status = "Часть ${index + 1} из ${chunks.size} · ${model.title}") }
                            val result = session.generate(model, DraftJson.prompt(chunk, snapshot.options.group))
                            val parsed = DraftJson.decode(result.getString("text") ?: error("Пустой ответ"), chunk)
                            rows += parsed.lessons; off += parsed.daysOff; warnings += parsed.warnings
                            parsed.suggestedWeeks?.let { weeks = maxOf(weeks ?: 0, it) }
                            require(rows.size <= 500) { "Больше 500 строк: разделите документ на части" }
                            assets.recordSpeed(model.id, result.getInt("tokens"), result.getLong("millis"))
                        }
                    }
                    val distinct = rows.distinctBy { listOf(it.subject, it.start, it.end, it.date, it.day, it.weeks, it.repeat, it.room, it.teacher, it.kind) }
                    if (distinct.size != rows.size) warnings += "Одинаковые записи из соседних частей объединены"
                    ImportDraft(distinct, off.distinct(), weeks, warnings.distinct())
                }
                if (withModel && draft.lessons.isEmpty() && draft.daysOff.isEmpty()) {
                    runCatching { SimpleScheduleParser.parse(snapshot.source) }.onSuccess { parsed ->
                        draft = parsed.copy(warnings = parsed.warnings + "Модель не выделила пары. Использован ограниченный разбор явных строк: проверьте полноту, преподавателей и тип занятий.")
                    }
                }
                require(draft.lessons.isNotEmpty() || draft.daysOff.isNotEmpty()) { "Модель не нашла занятий. Проверьте исходный текст или выберите другую модель." }
                var options = snapshot.options.copy(group = snapshot.options.group.trim(), timezone = snapshot.options.timezone.trim())
                val numbered = draft.lessons.any { it.weeks.isNotBlank() }
                val lastExplicitWeek = draft.lessons.filter { it.weeks.isNotBlank() }.mapNotNull {
                    runCatching { WeekSelection.parse(it.weeks, 104).maxOrNull() }.getOrNull()
                }.maxOrNull()
                val suggestedCount = listOfNotNull(draft.suggestedWeeks, lastExplicitWeek).maxOrNull()
                if (!numbered && draft.lessons.isNotEmpty() && draft.lessons.all { it.date.isNotBlank() }) {
                    val dates = draft.lessons.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                    if (dates.size == draft.lessons.size) {
                        val start = monday(dates.min())
                        options = options.copy(firstMonday = start, weekCount = (ChronoUnit.WEEKS.between(start, monday(dates.max())).toInt() + 1).coerceIn(1, 104))
                    }
                } else suggestedCount?.let { options = options.copy(weekCount = it) }
                mutable.update { it.copy(draft = draft, options = options, plan = preview(draft, options), status = "Черновик готов. Проверьте даты, недели и пары.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { error(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun edit(row: DraftLesson) {
        val draft = state.value.draft ?: return
        val next = draft.copy(lessons = draft.lessons.map { if (it.id == row.id) row.copy(verifiedByUser = true) else it })
        mutable.update { it.copy(draft = next, plan = preview(next, it.options)) }
    }
    fun remove(id: String) {
        val draft = state.value.draft ?: return
        val next = draft.copy(lessons = draft.lessons.filterNot { it.id == id })
        mutable.update { it.copy(draft = next, plan = preview(next, it.options)) }
    }
    fun daysOff(value: String) {
        val draft = state.value.draft ?: return
        val next = draft.copy(daysOff = value.split(',').map(String::trim).filter(String::isNotBlank).take(728), daysOffConfirmed = false)
        mutable.update { it.copy(draft = next, plan = preview(next, it.options)) }
    }
    fun confirmDaysOff() {
        val draft = state.value.draft ?: return
        val next = draft.copy(daysOffConfirmed = true)
        mutable.update { it.copy(draft = next, plan = preview(next, it.options)) }
    }
    private fun preview(draft: ImportDraft, options: ImportOptions): ImportPlan {
        val plan = DraftCompiler.compile(draft, options)
        if (plan.problems.isNotEmpty()) return plan
        return runCatching { DraftCompiler.apply(repository.state.value.schedule, plan, options); plan }
            .getOrElse { plan.copy(problems = plan.problems + DraftProblem(null, it.message ?: "Проверьте период")) }
    }
    fun apply(done: () -> Unit) {
        val snapshot = state.value
        val plan = snapshot.plan ?: return
        if (snapshot.busy || plan.problems.isNotEmpty()) return
        job = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            val result = withContext(Dispatchers.IO) { repository.applyImport { DraftCompiler.apply(it, plan, snapshot.options) } }
            mutable.update { it.copy(busy = false, hasBackup = repository.hasImportBackup()) }
            result.onSuccess { mutable.value = initial(); done() }.onFailure { error(it) }
        }
    }
    fun restore(done: () -> Unit) {
        if (state.value.busy) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.update { repository.readImportBackup() } }
            result.onSuccess { mutable.value = initial(); done() }.onFailure { error(it) }
        }
    }
}
