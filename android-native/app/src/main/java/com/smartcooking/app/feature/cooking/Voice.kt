package com.smartcooking.app.feature.cooking

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Playback { IDLE, LOADING, PLAYING, PAUSED }

data class VoiceResult(val text: String, val confidence: Float?)

/** System speech (TTS + recognizer) and the optional cloud TTS player; port of CookingVoicePlugin. */
class VoiceService(private val context: Context,
    private val captureAudio: suspend (Int, CommandAudio.Control, () -> Unit, (Float) -> Unit) -> ByteArray =
        { source, control, ready, level -> CommandAudio.record(context, source, control, ready, level) },
) {
    private var tts: TextToSpeech? = null
    private var chineseReady = false
    private var utteranceSeq = 0
    private var activeUtterance: String? = null

    private val _playback = MutableStateFlow(Playback.IDLE)
    val playback: StateFlow<Playback> = _playback.asStateFlow()
    /** Cloud audio position/duration in seconds (0 when unknown). */
    val progress = MutableStateFlow(0f to 0f)

    private var player: MediaPlayer? = null
    private var recognizer: SpeechRecognizer? = null
    private class Listening(val job: Job, val control: CommandAudio.Control = CommandAudio.Control()) {
        var system = false
    }
    private var listening: Listening? = null
    private val microphone = Mutex()
    private var preferredSource = MediaRecorder.AudioSource.VOICE_RECOGNITION

    init {
        tts = TextToSpeech(context) { status ->
            chineseReady = status == TextToSpeech.SUCCESS && (tts?.setLanguage(Locale.SIMPLIFIED_CHINESE) ?: -1) >= 0
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) { if (id == activeUtterance) _playback.value = Playback.PLAYING }
                override fun onDone(id: String) { if (id == activeUtterance) _playback.value = Playback.IDLE }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) { if (id == activeUtterance) _playback.value = Playback.IDLE }
            })
        }
    }

    val recognitionAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Speaks with the system Chinese voice; throws when no Chinese voice is installed. */
    fun speak(text: String) {
        stopAll()
        val engine = tts
        if (!chineseReady || engine == null) throw IllegalStateException("没有可用中文系统语音，可手动重试在线播报")
        val id = "cookx-${++utteranceSeq}"
        activeUtterance = id
        _playback.value = Playback.LOADING
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
            _playback.value = Playback.IDLE
            throw IllegalStateException("系统播报失败，可手动重试在线播报")
        }
    }

    fun stopSpeaking() {
        activeUtterance = null
        tts?.stop()
        if (player == null) _playback.value = Playback.IDLE
    }

    /** Plays downloaded cloud TTS audio. */
    suspend fun playCloud(bytes: ByteArray) {
        stopAll()
        _playback.value = Playback.LOADING
        val file = withContext(Dispatchers.IO) { File(context.cacheDir, "cookx-step.mp3").apply { writeBytes(bytes) } }
        val mp = MediaPlayer()
        player = mp
        mp.setDataSource(file.absolutePath)
        mp.setOnCompletionListener { if (player === mp) releasePlayer() }
        mp.setOnErrorListener { _, _, _ -> if (player === mp) releasePlayer(); true }
        mp.prepare()
        mp.start()
        _playback.value = Playback.PLAYING
    }

    fun tickProgress() {
        val mp = player ?: return
        runCatching { progress.value = mp.currentPosition / 1000f to mp.duration.coerceAtLeast(0) / 1000f }
    }

    val hasCloudAudio: Boolean get() = player != null

    fun pauseCloud() { player?.let { if (it.isPlaying) { it.pause(); _playback.value = Playback.PAUSED } } }
    fun resumeCloud(): Boolean {
        val mp = player ?: return false
        mp.start(); _playback.value = Playback.PLAYING
        return true
    }

    private fun releasePlayer() {
        runCatching { player?.stop() }
        player?.release()
        player = null
        progress.value = 0f to 0f
        _playback.value = Playback.IDLE
    }

    fun stopAll() {
        activeUtterance = null
        tts?.stop()
        releasePlayer()
    }

    /** All exits cancel the same session; only one recorder may own the microphone. */
    suspend fun listenOnce(transcribe: (suspend (ByteArray) -> String)? = null,
                           onCapture: (Boolean, Float) -> Unit = { _, _ -> },
                           onStatus: (String) -> Unit = {}): VoiceResult {
        stopListening(); stopAll()
        val session = Listening(currentCoroutineContext().job)
        listening = session
        fun status(text: String) { if (listening === session && session.job.isActive) onStatus(text) }
        fun capture(active: Boolean, level: Float = 0f) {
            if (listening === session && session.job.isActive && (!active || !session.control.finished.get())) onCapture(active, level)
        }
        try {
            if (transcribe != null) {
                var audio: ByteArray? = null
                val sources = listOf(preferredSource, if (preferredSource == MediaRecorder.AudioSource.MIC)
                    MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC)
                for ((attempt, source) in sources.withIndex()) {
                    capture(false)
                    status(if (attempt == 0) "正在启动麦克风，请稍候…" else "未取得有效录音，正在切换兼容模式…")
                    try {
                        audio = microphone.withLock {
                            currentCoroutineContext().ensureActive()
                            captureAudio(source, session.control,
                                { capture(true); status(if (attempt == 0) "录音已就绪，请说一句；说完稍停或点「说完了」" else "兼容录音已就绪，请再说一次；说完点「说完了」") },
                                { level -> capture(true, level) })
                        }
                        currentCoroutineContext().ensureActive()
                        preferredSource = source
                        break
                    } catch (e: CommandAudio.CaptureException) {
                        if (!e.canRetry || session.control.finished.get()) throw e
                        if (attempt == sources.lastIndex) {
                            if (e.problem == CommandAudio.Problem.NO_SIGNAL || !recognitionAvailable) throw e
                        }
                    }
                }
                capture(false)
                if (audio != null) {
                    status("正在识别指令…")
                    try {
                        val text = transcribe(audio)
                        currentCoroutineContext().ensureActive()
                        return VoiceResult(text, null)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        if (!recognitionAvailable) throw IllegalStateException("在线识别不可用：${e.message ?: "请检查网络"}；可输入指令或使用步骤按钮")
                    }
                }
                status("正在启动系统识别，请等就绪后再说一次…")
            } else status("正在启动系统识别，请稍候…")
            session.control.finished.set(false)
            session.system = true
            return listenSystemOnce({ capture(true); status("系统录音已就绪，请说一句指令") },
                { level -> capture(true, level) }, { capture(false); status("正在识别指令…") })
        } finally {
            if (listening === session) {
                onCapture(false, 0f)
                listening = null
                releaseRecognizer()
            }
        }
    }

    fun finishListening() {
        val session = listening ?: return
        session.control.finished.set(true)
        if (session.system) recognizer?.stopListening()
    }

    /** Listens for one zh-CN phrase (20 s timeout). Requires RECORD_AUDIO. */
    private suspend fun listenSystemOnce(onReady: () -> Unit, onLevel: (Float) -> Unit, onEnd: () -> Unit): VoiceResult = withContext(Dispatchers.Main) {
        releaseRecognizer(); stopAll()
        if (!recognitionAvailable) throw IllegalStateException("系统识别服务不可用，请使用文字或按钮")
        withTimeout(20_000) {
            suspendCancellableCoroutine { cont: CancellableContinuation<VoiceResult> ->
                val r = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer = r
                var recordingReady = false
                cont.invokeOnCancellation { runCatching { r.cancel(); r.destroy() }; if (recognizer === r) recognizer = null }
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { if (recognizer === r) { recordingReady = true; onReady() } }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) { if (recognizer === r && recordingReady) onLevel(((rmsdB + 2) / 12).coerceIn(0f, 1f)) }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { if (recognizer === r) { recordingReady = false; onEnd() } }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onError(error: Int) {
                        if (recognizer !== r) return
                        releaseRecognizer()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("系统语音识别失败（$error），请重试或输入指令"))
                    }
                    override fun onResults(results: Bundle) {
                        if (recognizer !== r) return
                        val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        // Release this recognizer before resuming: a resumed command may begin another operation.
                        releaseRecognizer()
                        if (cont.isActive) {
                            if (texts.isNullOrEmpty()) cont.resumeWithException(IllegalStateException("未识别到指令"))
                            else cont.resume(VoiceResult(texts[0], scores?.firstOrNull()?.takeIf { it in 0f..1f }))
                        }
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                r.startListening(intent)
            }
        }
    }

    fun dispose() {
        stopListening(); stopAll(); tts?.shutdown(); tts = null
    }

    fun stopListening() {
        val session = listening
        listening = null
        session?.job?.cancel()
        releaseRecognizer()
    }

    private fun releaseRecognizer() {
        val previous = recognizer
        recognizer = null
        previous?.let { runCatching { it.cancel(); it.destroy() } }
    }
}

object VoiceCommands {
    val labels = linkedMapOf(
        "next" to "下一步", "previous" to "上一步", "repeat" to "重复播报", "startTimer" to "开始计时",
        "pauseTimer" to "暂停计时", "resumeTimer" to "继续计时", "temperature" to "查询温度",
    )
    private val phrases = mapOf(
        "next" to listOf("下一步", "下一个步骤", "下一步操作", "继续下一步", "请进入下一步", "请下一步"), "previous" to listOf("上一步", "上一个步骤", "返回上一步"),
        "repeat" to listOf("重复", "重复播报", "再说一遍", "重新播报"), "startTimer" to listOf("开始计时"),
        "pauseTimer" to listOf("暂停计时", "停止计时"), "resumeTimer" to listOf("继续计时", "恢复计时"),
        "temperature" to listOf("查询温度", "现在多少度", "现在的温度", "温度是多少"),
    )

    data class Parsed(val text: String, val matches: List<String>, val confirmed: Boolean)

    /** Missing optional scores are acceptable only for exact, reversible navigation commands. */
    fun parse(text: String, confidence: Float?): Parsed {
        val normalized = text.replace(Regex("[\\p{P}\\p{Z}\\s]"), "")
        if (listOf("不要", "别", "不需要", "不用", "取消").any { normalized.contains(it) }) return Parsed(text, emptyList(), false)
        val commandText = normalized.replace(Regex("^(?:小厨|鲜厨|CookX)?(?:请帮我|帮我|麻烦|请)?(?:切换到|切到|进入|回到|返回|进行)?"), "")
            .replace(Regex("(?:吧|呀|啊|一下|谢谢)$"), "")
        val exact = phrases.filter { normalized in it.value || commandText in it.value }.keys.toList()
        val matches = exact.ifEmpty { phrases.filter { (_, v) -> v.any { normalized.contains(it) } }.keys.toList() }
        val trustworthy = (confidence != null && confidence in 0.8f..1f) ||
            (confidence == null && exact.singleOrNull() in listOf("next", "previous", "repeat"))
        return Parsed(text, matches, exact.size == 1 && trustworthy)
    }
}
