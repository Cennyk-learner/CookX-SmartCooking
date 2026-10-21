package com.smartcooking.app.feature.cooking

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/** Short, memory-only recording. Endpointing never determines whether a command is valid. */
object CommandAudio {
    const val SAMPLE_RATE = 16000
    class Control { val finished = AtomicBoolean(false) }
    enum class Problem { INITIALIZATION, READ, SILENCED, NO_SIGNAL }
    class CaptureException(val problem: Problem, message: String) : IllegalStateException(message) {
        val canRetry get() = problem != Problem.SILENCED
    }

    class Endpoint {
        private var samples = 0
        private var voiceSamples = 0
        private var signalSamples = 0
        private var silenceSamples = 0
        private var noiseFloor = 40.0
        var level = 0f; private set
        val heardSpeech get() = voiceSamples >= SAMPLE_RATE * 16 / 100
        // Only reject effectively silent/DC input; quiet audio still reaches ASR.
        val hasSignal get() = signalSamples >= SAMPLE_RATE / 10
        fun accept(pcm: ShortArray, count: Int): Boolean {
            require(count in 1..pcm.size)
            samples += count
            val mean = (0 until count).sumOf { pcm[it].toDouble() } / count
            val rms = sqrt((0 until count).sumOf { val v = pcm[it] - mean; v * v } / count)
            noiseFloor = minOf(noiseFloor, rms)
            val threshold = (noiseFloor * 2.5).coerceIn(20.0, 120.0)
            if (rms >= 4) signalSamples += count
            if (rms >= threshold) { voiceSamples += count; silenceSamples = 0 } else silenceSamples += count
            level = ((20 * log10(rms.coerceAtLeast(1.0) / 32768.0) + 80) / 70).toFloat().coerceIn(0f, 1f)
            return samples >= SAMPLE_RATE * 8 || (!heardSpeech && samples >= SAMPLE_RATE * 5) ||
                (heardSpeech && silenceSamples >= SAMPLE_RATE * 6 / 5)
        }
    }

    fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size); put(pcm)
    }.array()

    internal interface Input : AutoCloseable {
        fun start()
        fun read(buffer: ShortArray): Int
        val silenced: Boolean
    }

    /** Nonblocking reads keep cancel/retry responsive, even when an OEM stops supplying frames. */
    internal suspend fun capture(input: Input, control: Control, onReady: () -> Unit, onLevel: (Float) -> Unit,
                                 now: () -> Long = SystemClock::elapsedRealtime): ByteArray {
        try {
            currentCoroutineContext().ensureActive()
            input.start()
            val started = now()
            val pcm = ByteArrayOutputStream()
            val frame = ShortArray(320)
            val endpoint = Endpoint()
            var ready = false
            var lastLevel = started
            while (now() - started < 8_000) {
                currentCoroutineContext().ensureActive()
                if (input.silenced) throw CaptureException(Problem.SILENCED, "系统暂停了麦克风输入，请检查系统麦克风开关或结束通话后重试")
                if (control.finished.get()) break
                val count = input.read(frame)
                if (count < 0) throw CaptureException(Problem.READ, "录音采集中断，请重新说一句指令")
                if (count == 0) {
                    if (!ready && now() - started >= 2_000) throw CaptureException(Problem.READ, "麦克风没有返回录音数据")
                    delay(20)
                    continue
                }
                if (!ready) { ready = true; onReady() }
                for (index in 0 until count) { pcm.write(frame[index].toInt() and 255); pcm.write((frame[index].toInt() shr 8) and 255) }
                val ended = endpoint.accept(frame, count)
                if (now() - lastLevel >= 80) { onLevel(endpoint.level); lastLevel = now() }
                if (ended) break
            }
            currentCoroutineContext().ensureActive()
            if (!endpoint.hasSignal) throw CaptureException(Problem.NO_SIGNAL, "未采集到足够声音，请检查系统麦克风开关，等提示就绪后再说一句")
            return wav(pcm.toByteArray())
        } finally { input.close() }
    }

    @SuppressLint("MissingPermission")
    suspend fun record(context: Context, source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                       control: Control = Control(), onReady: () -> Unit = {}, onLevel: (Float) -> Unit = {}): ByteArray = withContext(Dispatchers.IO) {
        check(ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "请允许麦克风权限后再试" }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw CaptureException(Problem.INITIALIZATION, "手机不支持当前录音格式")
        val recorder = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
        } catch (e: IllegalArgumentException) { throw CaptureException(Problem.INITIALIZATION, "当前麦克风模式不可用") }
        capture(object : Input {
            override fun start() {
                if (recorder.state != AudioRecord.STATE_INITIALIZED) throw CaptureException(Problem.INITIALIZATION, "麦克风初始化失败")
                try { recorder.startRecording() } catch (e: IllegalStateException) { throw CaptureException(Problem.INITIALIZATION, "麦克风未能开始录音") }
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw CaptureException(Problem.INITIALIZATION, "麦克风未开始录音")
            }
            override fun read(buffer: ShortArray) = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
            override val silenced get() = Build.VERSION.SDK_INT >= 29 && recorder.activeRecordingConfiguration?.isClientSilenced == true
            override fun close() { runCatching { recorder.stop() }; recorder.release() }
        }, control, onReady, onLevel)
    }
}
