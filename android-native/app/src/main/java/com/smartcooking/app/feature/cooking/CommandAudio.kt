package com.smartcooking.app.feature.cooking

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** A bounded, in-memory utterance. No recording survives the recognition request. */
object CommandAudio {
    const val SAMPLE_RATE = 16000

    class Endpoint {
        private var samples = 0
        private var voiceSamples = 0
        private var silenceSamples = 0
        val heardSpeech get() = voiceSamples >= SAMPLE_RATE / 5
        fun accept(pcm: ShortArray, count: Int): Boolean {
            samples += count
            val rms = sqrt((0 until count).sumOf { pcm[it].toDouble() * pcm[it] } / count.coerceAtLeast(1))
            if (rms >= 400) { voiceSamples += count; silenceSamples = 0 } else silenceSamples += count
            return samples >= SAMPLE_RATE * 8 || (!heardSpeech && samples >= SAMPLE_RATE * 5) ||
                (heardSpeech && silenceSamples >= SAMPLE_RATE * 6 / 5)
        }
    }

    fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size); put(pcm)
    }.array()

    @SuppressLint("MissingPermission") // Runtime permission is checked here and requested by the screen.
    suspend fun record(context: Context): ByteArray = withContext(Dispatchers.IO) {
        check(ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "请允许麦克风权限后再试" }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "手机不支持语音录音，请使用文字指令" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败，请关闭占用麦克风的应用后重试" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未开始录音，请重试" }
            val pcm = ByteArrayOutputStream()
            val frame = ShortArray(1600)
            val endpoint = Endpoint()
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(frame, 0, frame.size)
                check(count > 0) { "录音中断，请重新说一句指令" }
                for (index in 0 until count) { pcm.write(frame[index].toInt() and 255); pcm.write((frame[index].toInt() shr 8) and 255) }
                if (endpoint.accept(frame, count)) break
            }
            check(endpoint.heardSpeech) { "没有听到清晰语音，请靠近手机麦克风再试" }
            wav(pcm.toByteArray())
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}
