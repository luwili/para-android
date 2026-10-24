package app.para.schedule.ai

const val MIB = 1024L * 1024
const val GIB = 1024L * MIB

data class ModelSpec(
    val id: String, val title: String, val parameters: String, val bytes: Long,
    val sha256: String, val url: String, val minimumRam: Long, val workingMemory: Long,
    val contextTokens: Int = 4096,
)

/** Pinned public GGUF files. No user input can change a download URL or checksum. */
object ModelCatalog {
    val models = listOf(
        ModelSpec("qwen3-06-q4", "Экономичная", "Qwen3 · 0,6B · Q4_K_M", 484220320,
            "9acfc1e001311f34b4252001b626f2e466d592a42065f66571bff3790d4e1b14",
            "https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF/resolve/60b85c0e3d8fe0f6474f406922a26d12aca4550d/Qwen_Qwen3-0.6B-Q4_K_M.gguf",
            2 * GIB, 1200 * MIB),
        ModelSpec("qwen3-17-q4", "Сбалансированная", "Qwen3 · 1,7B · Q4_K_M", 1282439584,
            "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb",
            "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/dcb19155b962dbb6389f4691a982043a8e651022/Qwen_Qwen3-1.7B-Q4_K_M.gguf",
            5 * GIB, 2500 * MIB),
        ModelSpec("qwen3-4-q4", "Расширенная", "Qwen3 · 4B · Q4_K_M", 2497280256,
            "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
            "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/bc640142c66e1fdd12af0bd68f40445458f3869b/Qwen3-4B-Q4_K_M.gguf",
            8 * GIB, 4200 * MIB),
    )
    fun find(id: String): ModelSpec = models.firstOrNull { it.id == id } ?: error("Неизвестная модель")
}

data class DeviceProfile(
    val ram: Long, val availableRam: Long, val freeStorage: Long,
    val supports64Bit: Boolean, val lowRam: Boolean, val cpuCount: Int,
)

object ModelPolicy {
    fun storageNeeded(model: ModelSpec): Long = model.bytes * 2 + 256 * MIB // staged download + verified private copy
    fun canRun(model: ModelSpec, device: DeviceProfile): Boolean = device.supports64Bit &&
        !device.lowRam && device.availableRam >= model.workingMemory
    fun recommend(device: DeviceProfile, installed: Set<String> = emptySet()): ModelSpec? =
        ModelCatalog.models.lastOrNull {
            canRun(it, device) && device.ram >= it.minimumRam && (it.id in installed || device.freeStorage >= storageNeeded(it))
        }
    fun reason(model: ModelSpec, device: DeviceProfile): String? = when {
        !device.supports64Bit -> "Для локальной LLM нужен Android с 64-битной архитектурой arm64 или x86_64. OCR и ручной импорт остаются доступны."
        device.lowRam -> "Устройство сообщает режим low-RAM. Локальная LLM отключена, чтобы не закрывать приложение из-за нехватки памяти."
        device.availableRam < model.workingMemory -> "Сейчас недостаточно свободной RAM. Закройте тяжёлые приложения или выберите более лёгкую модель."
        else -> null
    }
}
