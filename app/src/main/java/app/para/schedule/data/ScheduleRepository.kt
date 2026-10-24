package app.para.schedule.data

import android.content.Context
import android.util.AtomicFile
import app.para.schedule.widget.ScheduleWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class StoreState(val schedule: Schedule = Schedule(), val ready: Boolean = false, val error: String? = null)

class ScheduleRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val file = AtomicFile(File(context.filesDir, "schedule.json"))
    private val importBackup = AtomicFile(File(context.filesDir, "last-ai-import-backup.json"))
    private val mutableState = MutableStateFlow(StoreState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            mutex.withLock {
                try {
                    val schedule = synchronized(storageLock) {
                        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                            ScheduleJson.decode(file.openRead().bufferedReader().use { it.readText() })
                        } else Schedule()
                    }
                    mutableState.value = StoreState(schedule, ready = true)
                } catch (_: Exception) {
                    // Never silently overwrite damaged user data. Preserve it for recovery.
                    runCatching { file.baseFile.copyTo(File(context.filesDir, "schedule-recovery-${System.currentTimeMillis()}.json")) }
                    mutableState.value = StoreState(ready = true, error = "Не удалось прочитать расписание. Сохранена резервная копия; можно восстановить JSON через настройки.")
                }
            }
        }
    }

    suspend fun update(transform: (Schedule) -> Schedule): Result<Unit> = write(false, transform)
    suspend fun applyImport(transform: (Schedule) -> Schedule): Result<Unit> = write(true, transform)
    fun hasImportBackup() = importBackup.baseFile.exists() || File(importBackup.baseFile.path + ".bak").exists()
    fun readImportBackup(): Schedule = synchronized(storageLock) {
        ScheduleJson.decode(importBackup.openRead().bufferedReader().use { it.readText() })
    }

    private suspend fun write(backup: Boolean, transform: (Schedule) -> Schedule): Result<Unit> = mutex.withLock {
        runCatching {
            val next = transform(mutableState.value.schedule)
            val text = ScheduleJson.encode(next)
            ScheduleJson.decode(text) // The same constraints apply to the editor and file import.
            synchronized(storageLock) {
                if (backup) {
                    val saved = importBackup.startWrite()
                    try {
                        saved.write(ScheduleJson.encode(mutableState.value.schedule).toByteArray(Charsets.UTF_8))
                        importBackup.finishWrite(saved)
                    } catch (e: Exception) { importBackup.failWrite(saved); throw e }
                }
                val output = file.startWrite()
                try {
                    output.write(text.toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (e: Exception) {
                    file.failWrite(output)
                    throw e
                }
            }
            mutableState.value = StoreState(next, ready = true)
            runCatching { ScheduleWidgetProvider.updateAll(context) }
                .onFailure { android.util.Log.w("Para", "Schedule saved; launcher widget update deferred", it) }
            Unit
        }
    }

    companion object {
        private val storageLock = Any()
        @Suppress("StaticFieldLeak") // Constructor is private; get() retains only applicationContext.
        @Volatile private var instance: ScheduleRepository? = null
        fun get(context: Context): ScheduleRepository = instance ?: synchronized(this) {
            instance ?: ScheduleRepository(context.applicationContext).also { instance = it }
        }

        // A receiver may run before the app's asynchronous repository has loaded.
        fun readForWidget(context: Context): Schedule = synchronized(storageLock) {
            runCatching {
                val file = AtomicFile(File(context.filesDir, "schedule.json"))
                ScheduleJson.decode(file.openRead().bufferedReader().use { it.readText() })
            }.getOrDefault(Schedule())
        }
    }
}
