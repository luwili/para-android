package app.para.schedule

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.para.schedule.ai.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in only: -e networkAssets true. Run on a disposable emulator, not personal data. */
@RunWith(AndroidJUnit4::class)
class AiDownloadTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun optIn() { Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("networkAssets") == "true") }

    @Test fun systemDownloadVerifiesAndInstallsOcr() = runBlocking {
        optIn()
        val store = AiAssetStore(context)
        val previous = store.mobile
        store.mobile = true
        try {
            store.delete("ocr-eng")
            store.start("ocr-eng")
            withTimeout(150_000) {
                while (!store.ready("ocr-eng")) {
                    val state = store.snapshot().first { it.asset.id == "ocr-eng" }
                    check(state.error == null) { state.error!! }
                    delay(300)
                }
            }
            store.verifyPrivate("ocr-eng")
            assertEquals(AiAssets.find("ocr-eng").bytes, store.file("ocr-eng").length())
        } finally { store.mobile = previous }
    }

    @Test fun huggingFaceModelRedirectWorksAndDownloadCanBeCancelled() = runBlocking {
        optIn()
        val store = AiAssetStore(context)
        val model = ModelCatalog.models.first()
        val installed = store.file(model.id)
        val backup = File(installed.parentFile, "${installed.name}.qa-backup")
        val existed = installed.exists()
        if (existed) check(installed.renameTo(backup))
        val previous = store.mobile
        store.mobile = true
        try {
            store.cancel(model.id)
            store.start(model.id)
            withTimeout(150_000) {
                while (true) {
                    val state = store.snapshot().first { it.asset.id == model.id }
                    check(state.error == null) { state.error!! }
                    if (state.received >= 64 * 1024 || state.phase == "verify" || state.ready) break
                    delay(300)
                }
            }
            store.cancel(model.id)
            assertEquals(-1L, store.downloadId(model.id))
        } finally {
            store.cancel(model.id)
            if (existed) check(backup.renameTo(installed))
            store.mobile = previous
        }
        if (existed) store.verifyPrivate(model.id)
    }
}
