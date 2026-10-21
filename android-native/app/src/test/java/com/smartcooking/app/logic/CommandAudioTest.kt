package com.smartcooking.app.logic

import com.smartcooking.app.feature.cooking.CommandAudio
import com.smartcooking.app.feature.cooking.VoiceCommands
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class CommandAudioTest {
    private fun tone(amplitude: Int, size: Int = 1600) = ShortArray(size) { (amplitude * sin(2 * Math.PI * 200 * it / 16000)).toInt().toShort() }
    @Test fun silenceDoesNotBecomeACommandAndSpeechEndsAfterPause() {
        val silence=CommandAudio.Endpoint()
        repeat(49) { assertFalse(silence.accept(ShortArray(1600),1600)) }
        assertTrue(silence.accept(ShortArray(1600),1600)); assertFalse(silence.heardSpeech)
        val speech=CommandAudio.Endpoint()
        repeat(3) { assertFalse(speech.accept(tone(1000),1600)) }
        repeat(11) { assertFalse(speech.accept(ShortArray(1600),1600)) }
        assertTrue(speech.accept(ShortArray(1600),1600)); assertTrue(speech.heardSpeech)
    }
    @Test fun quietInputIsNotRejectedByTheOldFixedVolumeGate() {
        for (amplitude in listOf(1000, 500, 100, 16)) {
            val endpoint = CommandAudio.Endpoint()
            repeat(10) { endpoint.accept(tone(amplitude), 1600) }
            assertTrue("quiet amplitude=$amplitude must reach ASR", endpoint.hasSignal)
        }
    }
    @Test fun digitalSilenceAndDcOffsetAreNotSpeech() {
        for (offset in listOf(0, 1000)) {
            val endpoint = CommandAudio.Endpoint()
            repeat(50) { endpoint.accept(ShortArray(1600) { offset.toShort() }, 1600) }
            assertFalse(endpoint.hasSignal)
            assertFalse(endpoint.heardSpeech)
        }
    }
    private class Input(val data: (Int) -> ShortArray?, override val silenced: Boolean = false) : CommandAudio.Input {
        var started = false
        var closed = false
        var reads = 0
        var clock = 0L
        override fun start() { started = true }
        override fun read(buffer: ShortArray): Int {
            clock += 20
            val frame = data(++reads) ?: return 0
            frame.copyInto(buffer)
            return frame.size
        }
        override fun close() { closed = true }
    }
    @Test fun readinessWaitsForFirstFrameAndQuietAudioIsPreserved() = runTest {
        val input = Input({ if (it <= 10) null else if (it <= 30) tone(100, 320) else ShortArray(320) })
        var ready = 0
        var levelSeen = false
        val audio = CommandAudio.capture(input, CommandAudio.Control(), {
            assertTrue(input.started); assertTrue(input.reads > 10); ready++
        }, { if (it > 0) levelSeen = true }, { input.clock + testScheduler.currentTime })
        assertEquals(1, ready)
        assertTrue(levelSeen)
        assertTrue(audio.size > 44)
        assertTrue(input.closed)
        val first = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).getShort(46)
        assertEquals(tone(100, 320)[1], first)
    }
    @Test fun doneSubmitsInsteadOfCancellingRecording() = runTest {
        val control = CommandAudio.Control()
        val input = Input({ if (it == 20) control.finished.set(true); tone(100, 320) })
        val audio = CommandAudio.capture(input, control, {}, {}, { input.clock })
        assertEquals(44 + 20 * 320 * 2, audio.size)
        assertTrue(input.closed)
    }
    @Test fun silentCaptureFailsWithoutProducingAnUpload() = runTest {
        val input = Input({ ShortArray(320) })
        try {
            CommandAudio.capture(input, CommandAudio.Control(), {}, {}, { input.clock })
            fail("silence must not be uploaded")
        } catch (e: CommandAudio.CaptureException) { assertEquals(CommandAudio.Problem.NO_SIGNAL, e.problem) }
        assertTrue(input.closed)
    }
    @Test fun capturePolicySilenceStopsWithoutReadingOrRetryPermissionBypass() = runTest {
        val input = Input({ tone(1000, 320) }, silenced = true)
        try {
            CommandAudio.capture(input, CommandAudio.Control(), {}, {}, { input.clock })
            fail("system-muted input must stop")
        } catch (e: CommandAudio.CaptureException) { assertFalse(e.canRetry); assertEquals(CommandAudio.Problem.SILENCED, e.problem) }
        assertEquals(0, input.reads)
        assertTrue(input.closed)
    }
    @Test fun missingDriverFramesHaveABoundedWaitAndReleaseMicrophone() = runTest {
        val input = Input({ null })
        var ready = false
        try {
            CommandAudio.capture(input, CommandAudio.Control(), { ready = true }, {}, { input.clock + testScheduler.currentTime })
            fail("missing frames must time out")
        } catch (e: CommandAudio.CaptureException) { assertEquals(CommandAudio.Problem.READ, e.problem) }
        assertFalse(ready)
        assertTrue(input.clock + testScheduler.currentTime <= 2100)
        assertTrue(input.closed)
    }
    @Test fun cancellingWhileWaitingForFramesReleasesMicrophone() = runTest {
        val input = Input({ null })
        val recording = async { CommandAudio.capture(input, CommandAudio.Control(), {}, {}, { testScheduler.currentTime }) }
        runCurrent()
        recording.cancel(); recording.join()
        assertTrue(input.closed)
        assertTrue(recording.isCancelled)
    }
    @Test fun wavHeaderDescribesActualMonoPcm() {
        val bytes=CommandAudio.wav(ByteArray(3200))
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF",bytes.copyOfRange(0,4).toString(Charsets.US_ASCII))
        assertEquals(bytes.size-8,b.getInt(4)); assertEquals(1,b.getShort(22).toInt())
        assertEquals(16000,b.getInt(24)); assertEquals(32000,b.getInt(28)); assertEquals(3200,b.getInt(40))
    }
    @Test fun naturalNavigationAndPunctuationWorkWithoutConfidenceButNegationNeverRuns() {
        for (text in listOf("请切换到下一步。","帮我进入下一步吧！","请帮我切换到下一步","下一步，谢谢","回到上一步","再说一遍。"))
            assertTrue(text,VoiceCommands.parse(text,null).confirmed)
        for (text in listOf("不要下一步","别切换到下一步","下一步然后上一步","是不是下一步","下一步是什么","请下一步不要执行"))
            assertFalse(text,VoiceCommands.parse(text,null).confirmed)
    }
}
