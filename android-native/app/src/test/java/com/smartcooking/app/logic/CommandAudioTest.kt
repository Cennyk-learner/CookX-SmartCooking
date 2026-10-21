package com.smartcooking.app.logic

import com.smartcooking.app.feature.cooking.CommandAudio
import com.smartcooking.app.feature.cooking.VoiceCommands
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CommandAudioTest {
    @Test fun silenceDoesNotBecomeACommandAndSpeechEndsAfterPause() {
        val silence=CommandAudio.Endpoint()
        repeat(49) { assertFalse(silence.accept(ShortArray(1600),1600)) }
        assertTrue(silence.accept(ShortArray(1600),1600)); assertFalse(silence.heardSpeech)
        val speech=CommandAudio.Endpoint()
        repeat(3) { assertFalse(speech.accept(ShortArray(1600){1000},1600)) }
        repeat(11) { assertFalse(speech.accept(ShortArray(1600),1600)) }
        assertTrue(speech.accept(ShortArray(1600),1600)); assertTrue(speech.heardSpeech)
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
