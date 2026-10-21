package com.smartcooking.app.offline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.smartcooking.app.feature.kitchen.VoiceCommandCard
import com.smartcooking.app.feature.kitchen.VoiceUi
import com.smartcooking.app.ui.theme.CookXTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceControlsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun doneAndCancelAreSeparateAndReadyStateShowsInputLevel() {
        val state = mutableStateOf(VoiceUi(listening = true, commandStatus = "正在启动麦克风，请稍候…"))
        var submitted = 0
        var cancelled = 0
        compose.setContent {
            CookXTheme {
                Box(Modifier.padding(16.dp)) {
                    VoiceCommandCard(state.value, {}, { cancelled++; state.value = VoiceUi() },
                        { submitted++; state.value = state.value.copy(capturing = false, commandStatus = "正在识别指令…") },
                        {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("说完了").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(capturing = true, inputLevel = 0.45f,
            commandStatus = "录音已就绪，请说一句；说完稍停或点「说完了」") }
        compose.onNodeWithText("麦克风输入").assertIsDisplayed()
        compose.onNodeWithText("说完了").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/08-voice-recording.png")
        compose.onNodeWithText("说完了").performClick()
        assertEquals(1, submitted); assertEquals(0, cancelled)
        compose.onNodeWithText("正在识别指令…").assertIsDisplayed()
        compose.onNodeWithText("说完了").assertDoesNotExist()
        compose.onNodeWithContentDescription("取消识别").performClick()
        assertEquals(1, cancelled)
        compose.onNodeWithContentDescription("说一句指令").assertExists()
    }
}
