package com.smartcooking.app.offline

import android.media.MediaRecorder
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import com.smartcooking.app.feature.cooking.CommandAudio
import com.smartcooking.app.feature.cooking.VoiceService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.annotation.Config

/** Exercises orchestration with controlled audio input, not a physical phone microphone. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceCaptureTest {
    private val wave = CommandAudio.wav(ByteArray(6400) { (it % 100).toByte() })

    @Test fun manualSubmitThenCloudFailureReopensSystemRecordingControls() = runTest {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent("android.speech.RecognitionService"), ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = "test.speech"; name = "TestRecognitionService" }
        })
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val phases = mutableListOf<Boolean>()
        val voice = VoiceService(context) { _, control, ready, _ -> ready(); control.finished.set(true); wave }
        try {
            val task = async { voice.listenOnce({ throw IllegalStateException("network unavailable") }, { ready, _ -> phases += ready }) }
            runCurrent()
            val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
            assertNotNull(recognizer)
            assertFalse(phases.last())
            shadowOf(recognizer).triggerOnReadyForSpeech(Bundle())
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertTrue("manual submit flag must not disable fallback recording", phases.last())
            shadowOf(recognizer).triggerOnResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("下一步"))
            })
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertEquals("下一步", task.await().text)
        } finally { voice.dispose(); Dispatchers.resetMain() }
    }

    @Test fun captureFailureGetsExactlyOneCompatibilityAttemptThenTranscribes() = runTest {
        val sources = mutableListOf<Int>()
        val statuses = mutableListOf<String>()
        var uploads = 0
        val voice = VoiceService(RuntimeEnvironment.getApplication()) { source, _, ready, _ ->
            sources += source
            if (sources.size == 1) throw CommandAudio.CaptureException(CommandAudio.Problem.NO_SIGNAL, "silent first input")
            ready(); wave
        }
        val result = voice.listenOnce({ audio -> assertArrayEquals(wave, audio); uploads++; "下一步" }, onStatus = { statuses += it })
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC), sources)
        assertEquals(1, uploads)
        assertEquals("下一步", result.text)
        assertTrue(statuses.any { it.contains("兼容录音已就绪") })
        voice.dispose()
    }

    @Test fun silenceInBothModesNeverUploadsOrLoops() = runTest {
        var attempts = 0
        var uploads = 0
        val voice = VoiceService(RuntimeEnvironment.getApplication()) { _, _, _, _ ->
            attempts++
            throw CommandAudio.CaptureException(CommandAudio.Problem.NO_SIGNAL, "no input")
        }
        try { voice.listenOnce({ uploads++; "下一步" }); fail("silence must not become a command") }
        catch (e: CommandAudio.CaptureException) { assertEquals(CommandAudio.Problem.NO_SIGNAL, e.problem) }
        assertEquals(2, attempts); assertEquals(0, uploads)
        voice.dispose()
    }

    @Test fun systemMutedMicrophoneIsNotRetried() = runTest {
        var attempts = 0
        val voice = VoiceService(RuntimeEnvironment.getApplication()) { _, _, _, _ ->
            attempts++; throw CommandAudio.CaptureException(CommandAudio.Problem.SILENCED, "blocked")
        }
        try { voice.listenOnce({ fail("must not upload"); "" }); fail("must report blocked input") }
        catch (e: CommandAudio.CaptureException) { assertEquals(CommandAudio.Problem.SILENCED, e.problem) }
        assertEquals(1, attempts)
        voice.dispose()
    }

    @Test fun preparingIsNotListeningAndDoneSubmitsTheCurrentRecording() = runTest {
        val phases = mutableListOf<Boolean>()
        var uploads = 0
        val voice = VoiceService(RuntimeEnvironment.getApplication()) { _, control, ready, level ->
            delay(200)
            ready(); level(0.4f)
            while (!control.finished.get()) delay(20)
            wave
        }
        val result = async { voice.listenOnce({ uploads++; "下一步" }, { ready, _ -> phases += ready }) }
        runCurrent()
        assertEquals(listOf(false), phases)
        testScheduler.advanceTimeBy(201); runCurrent()
        assertTrue(phases.last())
        voice.finishListening()
        advanceUntilIdle()
        assertEquals("下一步", result.await().text)
        assertFalse(phases.last())
        assertEquals(1, uploads)
        voice.dispose()
    }

    @Test fun stopReleasesOldCaptureBeforeRetryAndOldCleanupCannotStopNewSession() = runTest {
        var active = 0
        var maximum = 0
        var calls = 0
        var uploads = 0
        val voice = VoiceService(RuntimeEnvironment.getApplication()) { _, _, ready, _ ->
            calls++; active++; maximum = maxOf(maximum, active)
            try {
                ready()
                if (calls == 1) awaitCancellation()
                wave
            } finally { withContext(NonCancellable) { delay(60); active-- } }
        }
        val old = async { voice.listenOnce({ uploads++; "旧指令" }) }
        runCurrent()
        voice.stopListening() // Same entry point used on app backgrounding.
        val next = async { voice.listenOnce({ uploads++; "下一步" }) }
        runCurrent()
        assertEquals(1, calls)
        advanceUntilIdle()
        assertTrue(old.isCancelled)
        assertEquals("下一步", next.await().text)
        assertEquals(1, maximum); assertEquals(0, active); assertEquals(1, uploads)
        voice.dispose()
    }
}
