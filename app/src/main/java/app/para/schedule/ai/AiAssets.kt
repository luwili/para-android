package app.para.schedule.ai

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class AiAsset(val id: String, val title: String, val bytes: Long, val sha256: String, val url: String, val relativePath: String)
object AiAssets {
    val ocr = listOf(
        AiAsset("ocr-rus", "OCR · русский", 3861738, "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225",
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/rus.traineddata", "ocr/tessdata/rus.traineddata"),
        AiAsset("ocr-eng", "OCR · английский", 4113088, "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/eng.traineddata", "ocr/tessdata/eng.traineddata"),
    )
    val all: List<AiAsset> = ModelCatalog.models.map { AiAsset(it.id, it.parameters, it.bytes, it.sha256, it.url, "models/${it.id}.gguf") } + ocr
    fun find(id: String) = all.firstOrNull { it.id == id } ?: error("Неизвестный пакет")
}

object DeviceInspector {
    fun inspect(context: Context): DeviceProfile {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return DeviceProfile(memory.totalMem, memory.availMem, context.filesDir.usableSpace,
            Process.is64Bit() && Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" },
            manager.isLowRamDevice, Runtime.getRuntime().availableProcessors())
    }
}

data class AssetStatus(val asset: AiAsset, val ready: Boolean, val phase: String, val received: Long = 0, val error: String? = null) {
    val active get() = phase in listOf("download", "verify", "paused")
}

class AiAssetStore(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("local-ai", Context.MODE_PRIVATE)
    private val downloads get() = context.getSystemService(DownloadManager::class.java)
    var selection: String
        get() = preferences.getString("selection", "auto").let { if (it == "auto" || ModelCatalog.models.any { m -> m.id == it }) it!! else "auto" }
        set(value) { require(value == "auto" || ModelCatalog.models.any { it.id == value }); preferences.edit().putString("selection", value).apply() }
    var mobile: Boolean
        get() = preferences.getBoolean("mobile", false)
        set(value) { preferences.edit().putBoolean("mobile", value).apply() }
    fun file(id: String) = File(context.filesDir, AiAssets.find(id).relativePath)
    fun ready(id: String): Boolean = file(id).let { it.isFile && it.length() == AiAssets.find(id).bytes }
    fun ocrReady() = AiAssets.ocr.all { ready(it.id) }
    fun downloadId(id: String) = preferences.getLong("download.$id", -1)
    fun speed(id: String) = preferences.getFloat("speed.$id", 0f)
    fun recordSpeed(id: String, tokens: Int, millis: Long) {
        if (millis > 0 && tokens > 0) preferences.edit().putFloat("speed.$id", tokens * 1000f / millis).apply()
    }

    fun snapshot(): List<AssetStatus> = AiAssets.all.map { asset ->
        if (ready(asset.id)) return@map AssetStatus(asset, true, "ready")
        val id = downloadId(asset.id)
        if (id < 0) return@map AssetStatus(asset, false, "missing", error = preferences.getString("error.${asset.id}", null))
        if (preferences.getBoolean("verify.${asset.id}", false)) return@map AssetStatus(asset, false, "verify")
        downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> { enqueueVerification(asset.id); AssetStatus(asset, false, "verify") }
                DownloadManager.STATUS_FAILED -> AssetStatus(asset, false, "failed", error = "Загрузка не завершена. Отмените её и попробуйте ещё раз.")
                else -> AssetStatus(asset, false,
                    if (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_PAUSED) "paused" else "download",
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)))
            }
        } ?: AssetStatus(asset, false, "failed", error = "Загрузка удалена из системы. Можно начать заново.")
    }

    fun start(id: String) = synchronized(lock) {
        val asset = AiAssets.find(id)
        if (ready(id)) return@synchronized
        require(downloadId(id) < 0) { "Сначала отмените предыдущую загрузку этого пакета" }
        if (ModelCatalog.models.any { it.id == id }) require(DeviceInspector.inspect(context).supports64Bit) { "Локальная LLM требует 64-битного Android" }
        if (ModelCatalog.models.any { it.id == id }) require(ModelCatalog.models.none { it.id != id && downloadId(it.id) >= 0 }) {
            "Сначала завершите или отмените загрузку другой модели"
        }
        require(context.filesDir.usableSpace >= asset.bytes * 2 + 64 * MIB) { "Недостаточно места: во время проверки нужны две копии файла" }
        require(context.getExternalFilesDir("ai-downloads") != null) { "Каталог загрузок недоступен" }
        stage(id).delete()
        val request = DownloadManager.Request(Uri.parse(asset.url))
            .setTitle("Пара · ${asset.title}")
            .setDescription("Локальный пакет. Документы никуда не отправляются.")
            .setAllowedOverMetered(mobile).setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, "ai-downloads", "$id.download")
        val requestId = downloads.enqueue(request)
        preferences.edit().putLong("download.$id", requestId).remove("error.$id").putBoolean("verify.$id", false).commit()
    }

    fun cancel(id: String) = synchronized(lock) {
        AiAssets.find(id)
        val requestId = downloadId(id)
        preferences.edit().remove("download.$id").remove("verify.$id").remove("error.$id").commit()
        WorkManager.getInstance(context).cancelUniqueWork("ai-verify-$id")
        if (requestId >= 0) downloads.remove(requestId)
        stage(id).delete()
    }

    fun delete(id: String) { cancel(id); require(!file(id).exists() || file(id).delete()) { "Не удалось удалить пакет" } }
    private fun stage(id: String) = File(context.getExternalFilesDir("ai-downloads"), "$id.download")

    fun enqueueVerification(id: String) {
        AiAssets.find(id)
        if (downloadId(id) < 0 || ready(id)) return
        val complete = downloads.query(DownloadManager.Query().setFilterById(downloadId(id))).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        }
        if (!complete) return
        preferences.edit().putBoolean("verify.$id", true).apply()
        val work = OneTimeWorkRequestBuilder<VerifyAiAssetWorker>().setInputData(workDataOf("asset" to id)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("ai-verify-$id", ExistingWorkPolicy.KEEP, work)
    }

    fun verifyAndInstall(id: String, stopped: () -> Boolean) {
        val asset = AiAssets.find(id)
        val requestId = downloadId(id)
        require(requestId >= 0) { "Загрузка отменена" }
        downloads.query(DownloadManager.Query().setFilterById(requestId)).use { cursor ->
            require(cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) { "Файл ещё не скачан" }
        }
        val target = file(id)
        target.parentFile!!.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            stage(id).inputStream().use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    check(!stopped() && downloadId(id) == requestId) { "Загрузка отменена" }
                    val n = input.read(buffer)
                    if (n < 0) break
                    count += n
                    require(count <= asset.bytes) { "Некорректный размер файла" }
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            } }
            require(count == asset.bytes && digest.digest().hex() == asset.sha256) { "SHA-256 не совпал: пакет не установлен" }
            synchronized(lock) {
                check(!stopped() && downloadId(id) == requestId) { "Загрузка отменена" }
                check(temporary.renameTo(target)) { "Не удалось сохранить проверенный пакет" }
                preferences.edit().remove("download.$id").remove("verify.$id").remove("error.$id").commit()
                downloads.remove(requestId)
                stage(id).delete()
            }
        } catch (e: Exception) {
            temporary.delete()
            if (downloadId(id) == requestId) {
                preferences.edit().remove("download.$id").remove("verify.$id").putString("error.$id", e.message ?: "Ошибка проверки").commit()
                downloads.remove(requestId)
                stage(id).delete()
            }
            throw e
        }
    }

    fun verifyPrivate(id: String, stopped: () -> Boolean = { false }) {
        val asset = AiAssets.find(id)
        require(ready(id)) { "Сначала скачайте пакет ${asset.title}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file(id).inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                check(!stopped()) { "Отменено" }
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        require(digest.digest().hex() == asset.sha256) { "Пакет повреждён. Удалите его и скачайте заново." }
    }

    companion object { private val lock = Any() }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

class VerifyAiAssetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("asset") ?: return@withContext Result.failure()
        runCatching { AiAssetStore(applicationContext).verifyAndInstall(id) { isStopped } }
            .fold({ Result.success() }, { Result.failure() })
    }
}

class AiDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0) return
        val store = AiAssetStore(context)
        // Broadcast data never chooses a file or URL. Only this app's recorded downloads are accepted.
        AiAssets.all.filter { store.downloadId(it.id) == id }.forEach { store.enqueueVerification(it.id) }
    }
}
