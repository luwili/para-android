package app.para.schedule

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.para.schedule.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class ParaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ScheduleRepository.get(application)
    val state = repository.state
    private val messagesChannel = Channel<String>(Channel.BUFFERED)
    val messages = messagesChannel.receiveAsFlow()
    private val mutableImport = MutableStateFlow<Schedule?>(null)
    val pendingImport = mutableImport.asStateFlow()

    fun update(message: String = "Сохранено", transform: (Schedule) -> Schedule) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.update(transform)
            messagesChannel.send(if (result.isSuccess) message else result.exceptionOrNull()?.message ?: "Не удалось сохранить")
        }
    }

    fun saveLesson(date: LocalDate, lesson: Lesson, dateOnly: Boolean, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.update { ScheduleEdits.save(it, date, lesson, dateOnly) } }
            done(result.isSuccess)
            messagesChannel.send(if (result.isSuccess) "Занятие сохранено" else result.exceptionOrNull()?.message ?: "Ошибка сохранения")
        }
    }

    fun readImport(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= ScheduleJson.MAX_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, ScheduleJson.MAX_BYTES + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("Не удалось открыть файл")
                require(bytes.size <= ScheduleJson.MAX_BYTES) { "Файл больше 2 МБ" }
                ScheduleJson.decode(String(bytes, Charsets.UTF_8)).copy(configured = true)
            }.onSuccess { mutableImport.value = it }
                .onFailure { messagesChannel.send("Импорт не выполнен: ${it.message ?: "неверный JSON"}") }
        }
    }

    fun dismissImport() { mutableImport.value = null }

    fun confirmImport() {
        val imported = mutableImport.value ?: return
        mutableImport.value = null
        update("Расписание импортировано") { imported }
    }

    fun export(uri: Uri) {
        // Snapshot before starting IO: the entire exported file represents one revision.
        val snapshot = state.value.schedule
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(ScheduleJson.encode(snapshot).toByteArray(Charsets.UTF_8))
                } ?: error("Не удалось открыть файл для записи")
            }
            messagesChannel.send(if (result.isSuccess) "Файл сохранён. Его можно отправить группе" else "Не удалось экспортировать: ${result.exceptionOrNull()?.message}")
        }
    }
}
