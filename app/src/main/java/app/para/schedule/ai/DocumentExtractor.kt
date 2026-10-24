package app.para.schedule.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Xml
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.util.zip.ZipFile
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Used only in :local_ai; a malformed PDF/image cannot exhaust the UI process. */
class DocumentExtractor(private val context: Context, private val cancelled: () -> Boolean, private val progress: (String) -> Unit) : AutoCloseable {
    private var tess: TessBaseAPI? = null
    private fun check() { check(!cancelled()) { "Отменено" } }

    fun extract(file: File, kind: String): String {
        require(file.isFile && file.length() <= 20 * MIB) { "Файл должен быть не больше 20 МБ" }
        val result = when (kind) {
            "image" -> image(file)
            "pdf" -> pdf(file)
            "docx" -> OfficeText.docx(file)
            "xlsx" -> OfficeText.xlsx(file)
            "text" -> decodeText(file.readBytes())
            else -> error("Поддерживаются фото, PDF, DOCX, XLSX, CSV и TXT. DOC/XLS сохраните в новом формате.")
        }.trim()
        check()
        require(result.isNotBlank()) { "Текст не найден. Попробуйте более чёткое фото или другой документ." }
        require(result.length <= 24000) { "Больше 24 000 символов. Разделите документ или оставьте только свою группу." }
        return result
    }

    private fun engine(): TessBaseAPI = tess ?: TessBaseAPI().also { api ->
        val store = AiAssetStore(context)
        require(store.ocrReady()) { "Для фото и сканов сначала скачайте OCR-пакет (русский + английский) в разделе моделей" }
        AiAssets.ocr.forEach { store.verifyPrivate(it.id, cancelled) }
        check(api.init(File(context.filesDir, "ocr").absolutePath, "rus+eng", TessBaseAPI.OEM_LSTM_ONLY)) { "Не удалось открыть OCR-пакет. Скачайте его заново." }
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        api.setVariable("preserve_interword_spaces", "1")
        tess = api
    }

    private fun recognize(bitmap: Bitmap): String {
        check()
        val api = engine()
        api.setImage(bitmap)
        val text = api.getUTF8Text()
        api.clear()
        check()
        return text
    }

    private fun image(file: File): String {
        progress("Распознаём фото локально")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Не удалось прочитать изображение" }
        var sample = 1
        while (bounds.outWidth / sample > 2800 || bounds.outHeight / sample > 2800 ||
            (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 6_000_000) sample *= 2
        var bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: error("Изображение не поддерживается")
        try {
            val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(270f); postScale(-1f, 1f) }
                    8 -> setRotate(270f)
                }
            }
            if (!matrix.isIdentity) {
                val corrected = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (corrected !== bitmap) { bitmap.recycle(); bitmap = corrected }
            }
            return recognize(bitmap)
        } finally { bitmap.recycle() }
    }

    private fun pdf(file: File): String = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            require(renderer.pageCount in 1..12) { "Поддерживается до 12 страниц PDF. Сохраните нужные страницы отдельно." }
            val text = StringBuilder()
            repeat(renderer.pageCount) { index ->
                check()
                progress("PDF: страница ${index + 1} из ${renderer.pageCount}")
                renderer.openPage(index).use { page ->
                    val embedded = if (Build.VERSION.SDK_INT >= 35) page.textContents.joinToString("\n") { it.text }.trim() else ""
                    val content = if (embedded.count(Char::isLetter) >= 8) embedded else {
                        require(page.width > 0 && page.height > 0) { "Некорректный размер страницы" }
                        val scale = min(3.0, min(2800.0 / maxOf(page.width, page.height), sqrt(6_000_000.0 / (page.width.toDouble() * page.height))))
                        val bitmap = Bitmap.createBitmap(ceil(page.width * scale).toInt().coerceAtLeast(1), ceil(page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            recognize(bitmap)
                        } finally { bitmap.recycle() }
                    }
                    text.append("\nСтраница ${index + 1}\n").append(content).append('\n')
                    require(text.length <= 24000) { "В PDF больше 24 000 символов. Выберите только нужные страницы/группу." }
                }
            }
            text.toString()
        }
    }

    override fun close() { tess?.recycle(); tess = null }

    companion object {
        fun kind(name: String, mime: String?): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                mime?.startsWith("image/") == true || ext in listOf("jpg", "jpeg", "png", "webp", "heic", "bmp") -> "image"
                ext == "pdf" || mime == "application/pdf" -> "pdf"
                ext == "docx" || mime?.contains("wordprocessingml") == true -> "docx"
                ext == "xlsx" || mime?.contains("spreadsheetml") == true -> "xlsx"
                ext in listOf("txt", "csv", "tsv") || mime?.startsWith("text/") == true -> "text"
                else -> error("Нужен PDF, DOCX, XLSX, TXT, CSV или фото. Старые DOC/XLS сохраните в новом формате.")
            }
        }
        fun decodeText(bytes: ByteArray): String {
            val text = when {
                bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF")
                bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> String(bytes, Charsets.UTF_16BE).removePrefix("\uFEFF")
                else -> runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                    .getOrElse { String(bytes, charset("windows-1251")) }.removePrefix("\uFEFF")
            }
            require('\u0000' !in text) { "Это не текстовый документ" }
            return text
        }
    }
}

internal object OfficeText {
    private fun xml(zip: ZipFile, path: String): XmlPullParser {
        val entry = zip.getEntry(path) ?: error("В документе отсутствует $path")
        val out = ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(out.size() + n <= 4 * MIB) { "Слишком большой распакованный XML" }
                out.write(buffer, 0, n)
            }
        }
        val text = String(out.toByteArray(), Charsets.UTF_8)
        require(!Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "DTD и внешние сущности не разрешены" }
        return Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(text.reader()) }
    }
    private fun checkZip(zip: ZipFile) { require(zip.size() <= 1000) { "Слишком много элементов в документе" } }

    fun docx(file: File): String = ZipFile(file).use { zip ->
        checkZip(zip)
        val p = xml(zip, "word/document.xml")
        val text = StringBuilder()
        var inCell = false
        var inText = false
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) { "tc" -> inCell = true; "t" -> inText = true; "tab" -> text.append('\t'); "br" -> text.append('\n') }
                XmlPullParser.TEXT -> if (inText) text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inText = false
                    "p" -> text.append(if (inCell) ' ' else '\n')
                    "tc" -> { inCell = false; text.append('\t') }
                    "tr" -> text.append('\n')
                }
            }
            require(text.length <= 24000) { "В документе больше 24 000 символов" }
            p.next()
        }
        text.toString()
    }

    fun xlsx(file: File): String = ZipFile(file).use { zip ->
        checkZip(zip)
        val strings = mutableListOf<String>()
        if (zip.getEntry("xl/sharedStrings.xml") != null) {
            val p = xml(zip, "xl/sharedStrings.xml")
            var value = StringBuilder()
            var inText = false
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "si") value = StringBuilder()
                if (p.eventType == XmlPullParser.START_TAG && p.name == "t") inText = true
                if (p.eventType == XmlPullParser.TEXT && inText) value.append(p.text)
                if (p.eventType == XmlPullParser.END_TAG && p.name == "t") inText = false
                if (p.eventType == XmlPullParser.END_TAG && p.name == "si") strings += value.toString()
                require(strings.size <= 60000 && value.length <= 4096) { "Слишком большая таблица строк" }
                p.next()
            }
        }
        val styles = mutableListOf<Int>()
        val formats = mutableMapOf<Int, String>()
        if (zip.getEntry("xl/styles.xml") != null) {
            val p = xml(zip, "xl/styles.xml")
            var cellFormats = false
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    if (p.name == "numFmt") formats[p.getAttributeValue(null, "numFmtId").toInt()] = p.getAttributeValue(null, "formatCode")
                    if (p.name == "cellXfs") cellFormats = true
                    if (p.name == "xf" && cellFormats) styles += p.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0
                }
                if (p.eventType == XmlPullParser.END_TAG && p.name == "cellXfs") cellFormats = false
                p.next()
            }
        }
        var date1904 = false
        if (zip.getEntry("xl/workbook.xml") != null) {
            val p = xml(zip, "xl/workbook.xml")
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "workbookPr") date1904 = p.getAttributeValue(null, "date1904") in listOf("1", "true")
                p.next()
            }
        }
        val sheets = zip.entries().asSequence().filter { Regex("xl/worksheets/sheet\\d+\\.xml").matches(it.name) }.map { it.name }.sorted().toList()
        require(sheets.isNotEmpty() && sheets.size <= 8) { "Нужна книга с 1–8 листами. Сохраните нужные листы отдельно." }
        val output = StringBuilder()
        var cells = 0
        for ((sheetIndex, path) in sheets.withIndex()) {
            output.append("\nЛист ${sheetIndex + 1}\n")
            val p = xml(zip, path)
            var row = sortedMapOf<Int, String>()
            var column = 0
            var type = ""
            var style = 0
            var inValue = false
            var value = StringBuilder()
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                    "row" -> row = sortedMapOf()
                    "c" -> {
                        val letters = p.getAttributeValue(null, "r")?.takeWhile(Char::isLetter)
                        column = letters?.uppercase()?.fold(0) { n, c -> n * 26 + (c - 'A' + 1) } ?: (column + 1)
                        require(column in 1..128) { "Таблица шире 128 столбцов" }
                        type = p.getAttributeValue(null, "t") ?: ""
                        style = p.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                        value = StringBuilder()
                    }
                    "v", "t" -> inValue = true
                }
                if (p.eventType == XmlPullParser.TEXT && inValue) value.append(p.text)
                if (p.eventType == XmlPullParser.END_TAG) when (p.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        require(++cells <= 12000) { "Слишком много ячеек" }
                        val raw = value.toString()
                        row[column] = if (type == "s") strings.getOrNull(raw.toIntOrNull() ?: -1) ?: error("Некорректная ссылка на строку")
                            else excelValue(raw, styles.getOrElse(style) { 0 }, formats, date1904)
                    }
                    "row" -> {
                        if (row.isNotEmpty()) output.append((1..row.lastKey()).joinToString("\t") { row[it] ?: "" }).append('\n')
                        require(output.length <= 24000) { "В книге больше 24 000 символов. Оставьте только свою группу." }
                        column = 0
                    }
                }
                p.next()
            }
        }
        output.toString()
    }

    private fun excelValue(raw: String, format: Int, formats: Map<Int, String>, date1904: Boolean): String {
        val value = raw.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return raw
        val code = formats[format]?.replace(Regex("\"[^\"]*\"|\\\\."), "")?.lowercase() ?: ""
        val isTime = format in 18..21 || format in 45..47 || 'h' in code
        val isDate = format in 14..17 || format == 22 || 'y' in code || 'd' in code
        val time = if (isTime || format == 22) {
            val minutes = ((value % 1) * 1440).roundToInt() % 1440
            "%02d:%02d".format(minutes / 60, minutes % 60)
        } else ""
        val date = if (isDate && value < 200000) (if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 30)).plusDays(value.toLong()).toString() else ""
        return listOf(date, time).filter(String::isNotBlank).joinToString(" ").ifBlank { raw }
    }
}
