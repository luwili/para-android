package app.para.schedule.ai

import android.app.Service
import android.content.*
import android.os.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Separate, same-UID process: weights stay private, native OOM does not kill the editor. */
class LocalAiService : Service() {
    private val executor get() = engineExecutor
    private val busy = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var loaded = false
    @Volatile private var activeRequest = 0
    private var modelId: String? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            CANCEL -> if (busy.get() && message.arg1 == activeRequest) {
                cancelled.set(true)
                if (loaded) NativeBridge.cancel()
                // Also interrupts native PDF/OCR routines that cannot poll a coroutine cancellation.
                Process.killProcess(Process.myPid())
            }
            RUN -> {
                val reply = message.replyTo ?: return@Handler true
                val request = message.arg1
                val input = Bundle(message.data)
                if (!busy.compareAndSet(false, true)) {
                    send(reply, ERROR, request, Bundle().apply { putString("error", "Модель уже занята другим разбором") })
                    return@Handler true
                }
                activeRequest = request
                cancelled.set(false)
                executor.execute {
                    val result = Bundle()
                    var success = true
                    try {
                        val status: (String) -> Unit = { text -> send(reply, PROGRESS, request, Bundle().apply { putString("status", text) }) }
                        if (input.getString("operation") == "extract") {
                            val path = File(input.getString("path") ?: error("Не указан файл")).canonicalFile
                            require(path.parentFile == File(cacheDir, "imports").canonicalFile) { "Недопустимый путь исходника" }
                            DocumentExtractor(this, { cancelled.get() }, status).use {
                                result.putString("text", it.extract(path, input.getString("kind") ?: ""))
                            }
                        } else {
                            val model = ModelCatalog.find(input.getString("model") ?: "")
                            val store = AiAssetStore(this)
                            require(store.ready(model.id)) { "Модель ещё не установлена" }
                            if (modelId != model.id) {
                                ModelPolicy.reason(model, DeviceInspector.inspect(this))?.let { error(it) }
                                status("Загружаем ${model.parameters} в память")
                                NativeBridge.load(store.file(model.id).absolutePath, model.contextTokens, DeviceInspector.inspect(this).cpuCount.coerceIn(1, 4))
                                loaded = true
                                modelId = model.id
                            }
                            var tokens = 0
                            val begin = SystemClock.elapsedRealtime()
                            val bytes = NativeBridge.generate((input.getString("prompt") ?: "").toByteArray(Charsets.UTF_8),
                                DraftJson.grammar.toByteArray(Charsets.UTF_8), 1536, object : NativeProgress {
                                    override fun onProgress(count: Int) { tokens = count; status("Генерация: $count токенов") }
                                })
                            result.putString("text", String(bytes, Charsets.UTF_8))
                            result.putInt("tokens", tokens)
                            result.putLong("millis", SystemClock.elapsedRealtime() - begin)
                        }
                    } catch (e: Throwable) {
                        success = false
                        result.putString("error", friendly(e.message))
                    }
                    busy.set(false)
                    send(reply, if (success) RESULT else ERROR, request, result)
                }
            }
        }
        true
    })
    override fun onBind(intent: Intent) = messenger.binder
    override fun onDestroy() {
        cancelled.set(true)
        if (loaded) NativeBridge.cancel()
        executor.execute { if (loaded) NativeBridge.unload(); loaded = false }
        super.onDestroy()
    }

    private fun send(reply: Messenger, what: Int, request: Int, data: Bundle) {
        runCatching { reply.send(Message.obtain(null, what).apply { arg1 = request; this.data = data }) }
            .onFailure { cancelled.set(true); if (loaded) NativeBridge.cancel() }
    }
    companion object {
        private val engineExecutor = Executors.newSingleThreadExecutor()
        const val RUN = 1; const val CANCEL = 2; const val PROGRESS = 10; const val RESULT = 11; const val ERROR = 12
        fun friendly(error: String?): String = when (error) {
            "CONTEXT_TOO_LONG", "INPUT_LIMIT" -> "Для модели слишком много текста в одной части. Сократите исходник или выберите более крупную модель."
            "OUTPUT_LIMIT" -> "Модель не закончила JSON. Разделите расписание на меньшие части и повторите разбор."
            "MODEL_LOAD_FAILED", "CONTEXT_MEMORY_FAILED", "DECODE_FAILED" -> "Не удалось запустить модель. Освободите RAM или выберите более лёгкую."
            "GENERATION_TIMEOUT" -> "Разбор занял больше 5 минут. Выберите более лёгкую модель или сократите текст."
            "CANCELLED" -> "Разбор отменён"
            else -> error?.take(240) ?: "Локальный процесс остановлен. Попробуйте более лёгкую модель."
        }
    }
}

class LocalAiSession(private val context: Context, private val progress: (String) -> Unit) : AutoCloseable {
    private val connected = CompletableDeferred<Messenger>()
    private var pending: CompletableDeferred<Bundle>? = null
    private var request = 0
    private var bound = false
    private var remote: Messenger? = null
    private val response = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.arg1 == request) when (message.what) {
            LocalAiService.PROGRESS -> progress(message.data.getString("status") ?: "Обработка")
            LocalAiService.RESULT -> pending?.complete(message.data)
            LocalAiService.ERROR -> pending?.completeExceptionally(IllegalStateException(message.data.getString("error")))
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { if (service != null) connected.complete(Messenger(service).also { remote = it }) }
        override fun onServiceDisconnected(name: ComponentName?) {
            val e = IllegalStateException("Локальный процесс остановлен системой. Попробуйте более лёгкую модель; расписание не изменено.")
            remote = null
            connected.completeExceptionally(e); pending?.completeExceptionally(e)
        }
        override fun onNullBinding(name: ComponentName?) { connected.completeExceptionally(IllegalStateException("Движок недоступен")) }
    }

    suspend fun connect() {
        withContext(Dispatchers.Main) {
            bound = context.bindService(Intent(context, LocalAiService::class.java), connection, Context.BIND_AUTO_CREATE)
            require(bound) { "Не удалось открыть локальный движок" }
        }
        try { withTimeout(15_000) { connected.await() } }
        catch (e: TimeoutCancellationException) { throw IllegalStateException("Локальный движок не ответил. Попробуйте открыть приложение заново.", e) }
    }

    suspend fun extract(file: File, kind: String): String = execute(Bundle().apply {
        putString("operation", "extract"); putString("path", file.absolutePath); putString("kind", kind)
    }).getString("text") ?: error("Пустой ответ распознавания")

    suspend fun generate(model: ModelSpec, prompt: String): Bundle = execute(Bundle().apply {
        putString("operation", "generate"); putString("model", model.id); putString("prompt", prompt)
    })

    private suspend fun execute(data: Bundle): Bundle {
        val wait = CompletableDeferred<Bundle>()
        try {
            val service = connected.await()
            withContext(Dispatchers.Main) {
                request = Random.nextInt(1, Int.MAX_VALUE)
                pending = wait
                service.send(Message.obtain(null, LocalAiService.RUN).apply { arg1 = request; this.data = data; replyTo = response })
            }
            return withTimeout(6 * 60_000L) { wait.await() }
        } catch (e: TimeoutCancellationException) {
            cancel()
            throw IllegalStateException("Локальный разбор не завершился за 6 минут. Сократите документ или выберите более лёгкую модель.", e)
        } catch (e: CancellationException) {
            cancel()
            throw e
        } finally { withContext(NonCancellable + Dispatchers.Main) { if (pending === wait) pending = null } }
    }

    fun cancel() {
        runCatching { remote?.send(Message.obtain(null, LocalAiService.CANCEL).apply { arg1 = request }) }
    }
    override fun close() { if (bound) { context.unbindService(connection); bound = false } }
}
