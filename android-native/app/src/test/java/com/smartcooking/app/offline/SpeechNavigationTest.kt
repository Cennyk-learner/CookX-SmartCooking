package com.smartcooking.app.offline

import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import com.smartcooking.app.core.AppContainer
import com.smartcooking.app.data.Recipe
import com.smartcooking.app.feature.kitchen.KitchenViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SpeechNavigationTest {
    @Test fun recognizerCallbackMovesStepWithoutTypingAndCanListenAgain() {
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent("android.speech.RecognitionService"),ResolveInfo().apply {
            serviceInfo=ServiceInfo().apply { packageName="test.speech"; name="TestRecognitionService" }
        })
        val c=AppContainer(context)
        c.local.gateway.clear()
        val vm=KitchenViewModel(c)
        vm.start(Recipe.normalize(c.local.engine.recipes.first()))
        shadowOf(Looper.getMainLooper()).idle()
        fun speak(text:String) {
            vm.listen()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(vm.voiceUi.value.commandStatus,vm.voiceUi.value.listening)
            val recognizer=ShadowSpeechRecognizer.getLatestSpeechRecognizer()
            assertNotNull(recognizer)
            shadowOf(recognizer).triggerOnResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,arrayListOf(text))
                putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES,floatArrayOf(-1f))
            })
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(vm.voiceUi.value.listening)
        }
        speak("请切换到下一步。")
        assertEquals(1,vm.engine.state.value?.stepIndex)
        assertTrue(vm.voiceUi.value.commandStatus.contains("第 2 步"))
        speak("不要下一步")
        assertEquals(1,vm.engine.state.value?.stepIndex)
        speak("返回上一步")
        assertEquals(0,vm.engine.state.value?.stepIndex)
        vm.exitCooking()
    }
}
