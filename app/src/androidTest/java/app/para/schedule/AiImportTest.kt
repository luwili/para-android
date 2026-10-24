package app.para.schedule

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import app.para.schedule.ai.*
import app.para.schedule.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AiImportUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val monday = LocalDate.of(2026, 10, 19)
    private fun waitForDraft(timeout: Long) {
        lateinit var ai: AiImportViewModel
        compose.runOnIdle { ai = ViewModelProvider(compose.activity)[AiImportViewModel::class.java] }
        compose.waitUntil(timeout) { ai.state.value.draft != null || (!ai.state.value.busy && ai.state.value.error != null) }
        assertNotNull("AI state error=${ai.state.value.error}, status=${ai.state.value.status}", ai.state.value.draft)
        compose.waitForIdle()
        compose.onNodeWithText("Проверь расписание").assertIsDisplayed()
    }
    private fun scroll(text: String) { compose.onNodeWithTag("ai-import-list").performScrollToNode(hasText(text)) }
    private fun modelChoice(text: String) {
        compose.onNodeWithTag("model-list").performScrollToNode(hasText(text, substring = true))
        compose.onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag("model-list"))).performClick()
    }
    private fun setup() {
        val repository = ScheduleRepository.get(compose.activity)
        compose.waitUntil(10_000) { repository.state.value.ready }
        runBlocking { repository.update { Schedule(profile = GroupProfile("ТЕСТ-101", "Asia/Yekaterinburg", monday), configured = true) }.getOrThrow() }
        compose.waitForIdle()
        compose.onNodeWithText("Импорт", useUnmergedTree = true).performClick()
    }
    @Test fun manualModelChoiceAndAutoArePersisted() {
        setup()
        compose.onNodeWithContentDescription("Выбрать модель").performClick()
        modelChoice("Сбалансированная")
        compose.waitUntil { AiAssetStore(compose.activity).selection == "qwen3-17-q4" }
        modelChoice("Автоматически")
        compose.waitUntil { AiAssetStore(compose.activity).selection == "auto" }
    }
    @Test fun reviewedTextImportBuildsTwoWeeksAndCanBeRestored() {
        setup()
        scroll("Исходный текст расписания")
        compose.onNodeWithText("Исходный текст расписания").performTextInput("Недели 1-2\nПонедельник\n08:30–10:00 Математика")
        scroll("Разобрать простые строки без ИИ")
        compose.onNodeWithText("Разобрать простые строки без ИИ").performClick()
        waitForDraft(10_000)
        compose.onNodeWithTag("ai-import-list").performScrollToNode(isToggleable())
        compose.onNode(isToggleable()).performClick()
        scroll("Сохранить в расписание")
        compose.onNodeWithText("Сохранить в расписание").performClick()
        compose.onNodeWithText("Подтвердить").performClick()
        val repository = ScheduleRepository.get(compose.activity)
        compose.waitUntil(10_000) { repository.state.value.schedule.overrides.size == 2 }
        assertEquals("Математика", ScheduleEngine.lessonsOn(repository.state.value.schedule, monday).single().subject)
        assertEquals("Математика", ScheduleEngine.lessonsOn(repository.state.value.schedule, monday.plusWeeks(1)).single().subject)
        assertTrue(repository.hasImportBackup())
        compose.onNodeWithText("Импорт", useUnmergedTree = true).performClick()
        scroll("Вернуть расписание до последнего автоимпорта")
        compose.onNodeWithText("Вернуть расписание до последнего автоимпорта").performClick()
        compose.onNodeWithText("Подтвердить").performClick()
        compose.waitUntil(10_000) { repository.state.value.schedule.overrides.isEmpty() }
    }
    @Test fun localModelCreatesDraftButDoesNotSaveWithoutReview() {
        Assume.assumeTrue(AiAssetStore(compose.activity).ready(ModelCatalog.models.first().id))
        setup()
        compose.onNodeWithContentDescription("Выбрать модель").performClick()
        modelChoice("Экономичная")
        compose.onNodeWithContentDescription("Закрыть выбор модели").performClick()
        scroll("Исходный текст расписания")
        compose.onNodeWithText("Исходный текст расписания").performTextInput("Недели 1-2\nПонедельник\n08:30–10:00 Математика")
        scroll("Собрать черновик локальной моделью")
        compose.onNodeWithText("Собрать черновик локальной моделью").performClick()
        waitForDraft(150_000)
        assertTrue(ScheduleRepository.get(compose.activity).state.value.schedule.overrides.isEmpty())
        scroll("Сохранить в расписание")
        compose.onNodeWithText("Сохранить в расписание").assertIsNotEnabled()
    }
}

@RunWith(AndroidJUnit4::class)
class LocalAiIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun file(suffix: String) = File(File(context.cacheDir, "imports").apply { mkdirs() }, "${UUID.randomUUID()}.$suffix")
    private fun zip(file: File, entries: Map<String, String>) { ZipOutputStream(file.outputStream()).use { out -> entries.forEach { (name, text) -> out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry() } } }

    @Test fun docxAndSpreadsheetAreReadInLocalProcess() = runBlocking {
        val docx = file("docx")
        val xlsx = file("xlsx")
        try {
            zip(docx, mapOf("word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Понедельник</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>08:30–10:00</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Математика</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>"""))
            zip(xlsx, mapOf(
                "xl/styles.xml" to """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cellXfs><xf numFmtId="0"/><xf numFmtId="20"/></cellXfs></styleSheet>""",
                "xl/sharedStrings.xml" to """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Математика</t></si></sst>""",
                "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row><c r="A1" s="1"><v>0.3541666666666667</v></c><c r="B1" t="s"><v>0</v></c></row></sheetData></worksheet>""",
            ))
            LocalAiSession(context) {}.use { session ->
                session.connect()
                val text = session.extract(docx, "docx")
                assertTrue(text.contains("Понедельник") && text.contains("Математика") && text.contains("08:30"))
                val spreadsheet = session.extract(xlsx, "xlsx")
                assertTrue(spreadsheet, spreadsheet.contains("08:30") && spreadsheet.contains("Математика"))
            }
        } finally { docx.delete(); xlsx.delete() }
    }

    @Test fun xmlExternalEntitiesAreRejectedWithoutChangingSchedule() = runBlocking {
        val source = file("docx")
        zip(source, mapOf("word/document.xml" to """<!DOCTYPE x [<!ENTITY external SYSTEM "file:///data/data/app.para.schedule/files/schedule.json">]><x>&external;</x>"""))
        try {
            LocalAiSession(context) {}.use { session ->
                session.connect()
                assertTrue(runCatching { session.extract(source, "docx") }.isFailure)
            }
        } finally { source.delete() }
    }

    @Test fun russianPhotoIsRecognizedOfflineWhenOcrIsInstalled() = runBlocking {
        Assume.assumeTrue("Provision OCR packages for the integration test", AiAssetStore(context).ocrReady())
        val source = file("png")
        val bitmap = Bitmap.createBitmap(1600, 700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }
        canvas.drawText("Понедельник", 60f, 140f, paint)
        canvas.drawText("08:30 - 10:00", 60f, 270f, paint)
        canvas.drawText("Математика", 60f, 400f, paint)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            LocalAiSession(context) {}.use { session -> session.connect(); val text = session.extract(source, "image"); assertTrue(text, text.contains("Математика", true)); assertTrue(text, text.contains("08:30")) }
        } finally { source.delete() }
    }

    @Test fun pdfIsReadLocally() = runBlocking {
        val source = file("pdf")
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawText("08:30 - 10:00 Mathematics", 40f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f })
        pdf.finishPage(page)
        source.outputStream().use(pdf::writeTo)
        pdf.close()
        try {
            LocalAiSession(context) {}.use { session -> session.connect(); val text = session.extract(source, "pdf"); assertTrue(text, text.contains("08:30")); assertTrue(text, text.contains("Mathematics", true)) }
        } finally { source.delete() }
    }

    @Test fun qwenRunsLocallyWithConstrainedJsonAndBuildsWeeks() = runBlocking {
        val store = AiAssetStore(context)
        val model = ModelCatalog.models.first()
        Assume.assumeTrue("Provision verified development GGUF for this test", store.ready(model.id))
        store.verifyPrivate(model.id)
        val source = "Недели 1-2\nПонедельник\n08:30–10:00 Математика"
        LocalAiSession(context) { android.util.Log.d("ParaAI-smoke", it) }.use { session ->
            session.connect()
            val output = session.generate(model, DraftJson.prompt(source, "ТЕСТ-101")).getString("text")!!
            val draft = DraftJson.decode(output, source)
            assertEquals(output, 1, draft.lessons.size)
            val row = draft.lessons.single()
            assertEquals(output, "Математика", row.subject)
            assertEquals(output, 1, row.day)
            assertEquals(output, LocalTime.of(8, 30), DraftCompiler.time(row.start))
            assertEquals(output, LocalTime.of(10, 0), DraftCompiler.time(row.end))
            val reviewed = draft.copy(lessons = draft.lessons.map { it.copy(verifiedByUser = true) })
            val plan = DraftCompiler.compile(reviewed, ImportOptions("ТЕСТ-101", "Asia/Yekaterinburg", LocalDate.of(2026, 10, 19), 2, UndatedPolicy.ALL))
            assertTrue(plan.problems.toString(), plan.problems.isEmpty())
            assertEquals(2, plan.lessonCount)
            File(context.filesDir, "ai-integration-output.json").writeText(output)
        }
    }
}
