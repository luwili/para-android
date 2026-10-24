package app.para.schedule.ai

import androidx.annotation.Keep

@Keep
interface NativeProgress { fun onProgress(tokens: Int) }

@Keep
object NativeBridge {
    init { System.loadLibrary("para_ai") }
    external fun load(path: String, contextTokens: Int, threads: Int)
    external fun generate(prompt: ByteArray, grammar: ByteArray, maxTokens: Int, progress: NativeProgress): ByteArray
    external fun cancel()
    external fun unload()
}
