package app.para.schedule

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.para.schedule.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.Bitmap
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun load(schedule: Schedule) {
        val repository = ScheduleRepository.get(compose.activity)
        compose.waitUntil(10_000) { repository.state.value.ready }
        runBlocking { repository.update { schedule }.getOrThrow() }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(compose.activity.filesDir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun welcomeDemoCalendarAndDarkThemeWork() {
        load(Schedule())
        screenshot("welcome")
        compose.onNodeWithText("Сначала посмотреть пример").performScrollTo().performClick()
        compose.waitUntil(10_000) { ScheduleRepository.get(compose.activity).state.value.schedule.configured }
        compose.onNodeWithText("Всё по плану.").assertIsDisplayed()
        screenshot("schedule-light")
        compose.onNodeWithText("Месяц", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Месяц в фокусе").assertIsDisplayed()
        screenshot("month")
        compose.onNodeWithText("Настройки", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Тёмная").performScrollTo().performClick()
        compose.waitUntil(10_000) { ScheduleRepository.get(compose.activity).state.value.schedule.profile.theme == "dark" }
        compose.onNodeWithText("Твоё пространство").assertIsDisplayed()
        compose.onNodeWithText("Расписание", useUnmergedTree = true).performClick()
        screenshot("schedule-dark")
    }

    @Test fun createLessonThroughEditorPersistsIt() {
        load(Schedule(configured = true))
        compose.onNodeWithText("Занятие", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Предмет").performTextInput("Линейная алгебра")
        compose.onNodeWithText("Сохранить занятие").performScrollTo().performClick()
        compose.waitUntil(10_000) { ScheduleRepository.get(compose.activity).state.value.schedule.lessons.isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Линейная алгебра", substring = false).assertExists()
    }
}
