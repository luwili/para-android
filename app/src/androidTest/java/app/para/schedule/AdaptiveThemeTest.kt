package app.para.schedule

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.para.schedule.data.*
import app.para.schedule.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class AdaptiveThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun datePickerFitsLargeTextAndUsesMaterialDialog() {
        var selected: LocalDate? = null
        val initial = LocalDate.of(2026, 10, 19)
        compose.setContent {
            val density = LocalDensity.current.density
            val config = Configuration(LocalConfiguration.current).apply { fontScale = 2f; screenWidthDp = 320 }
            CompositionLocalProvider(LocalDensity provides Density(density, 2f), LocalConfiguration provides config) {
                ParaTheme(GroupProfile(theme = "dark", dynamicColors = true)) {
                    DateField("Дата", initial) { selected = it }
                }
            }
        }
        compose.onNodeWithText("Дата").performClick()
        compose.onNodeWithText("Выбрать").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(initial, selected) }
    }

    @Test fun materialYouAndLargeTextStayWithinNarrowCards() {
        val variant = mutableStateOf(Triple(320, 1f, false))
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        var actualScheme: ColorScheme? = null
        val date = LocalDate.of(2026, 10, 19)
        val lesson = Lesson(subject = "Уголовно-процессуальное право и специальная профессиональная подготовка", start = LocalTime.of(8, 0), end = LocalTime.of(9, 20),
            room = "Аудитория 402, учебный корпус университета", teacher = "Очень длинное имя преподавателя", kind = "Лабораторная")
        compose.setContent {
            val (width, scale, dark) = variant.value
            val density = LocalDensity.current.density
            val config = Configuration(LocalConfiguration.current).apply { fontScale = scale }
            val themedContext = remember(config) { base.createConfigurationContext(config) }
            CompositionLocalProvider(LocalDensity provides Density(density, scale), LocalConfiguration provides config, LocalContext provides themedContext) {
                ParaTheme(GroupProfile(theme = if (dark) "dark" else "light", dynamicColors = true)) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect { actualScheme = scheme }
                    Surface(Modifier.width(width.dp).fillMaxHeight()) {
                        Column {
                            BrandHeader("Очень длинное название студенческой группы ПОНБ-УП-304С") { }
                            Box(Modifier.padding(16.dp)) { LessonCard(lesson, 0, date, ZonedDateTime.of(date, LocalTime.of(8, 30), ZoneId.of("Asia/Yekaterinburg"))) { } }
                        }
                    }
                }
            }
        }
        for (width in listOf(320, 360)) for (scale in listOf(1f, 1.3f, 2f)) for (dark in listOf(false, true)) {
            compose.runOnIdle { variant.value = Triple(width, scale, dark) }
            compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode()
            val density = base.resources.displayMetrics.density
            val right = root.positionInRoot.x + width * density
            val textNodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            for (node in textNodes) {
                if (node.boundsInRoot.isEmpty) continue
                assertTrue("Text starts outside: width=$width scale=$scale ${node.config[SemanticsProperties.Text]}", node.positionInRoot.x >= root.positionInRoot.x - 1)
                assertTrue("Text ends outside: width=$width scale=$scale ${node.config[SemanticsProperties.Text]}", node.positionInRoot.x + node.size.width <= right + 1)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val expected = if (dark) dynamicDarkColorScheme(base) else dynamicLightColorScheme(base)
                assertEquals(expected.primary, actualScheme!!.primary)
                assertEquals(expected.onPrimaryContainer, actualScheme!!.onPrimaryContainer)
                assertEquals(expected.surface, actualScheme!!.surface)
            }
            if (width == 320 && scale == 2f) {
                compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
                    File(base.filesDir, "adaptive-app-${if (dark) "dark" else "light"}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }
}
