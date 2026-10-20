package com.smartcooking.app.feature.live

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.smartcooking.app.core.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * App-wide live kitchen: one [LiveState] for the home card, the kitchen, the floating card and the
 * ongoing notification. It only reads the device and the cooking engine; it never connects,
 * disconnects or changes a cooking session.
 */
class LiveKitchen(private val c: AppContainer) {
    private val _state = MutableStateFlow(LiveState(supported = c.bluetooth.supported, now = System.currentTimeMillis()))
    val state: StateFlow<LiveState> = _state.asStateFlow()

    /** True while any activity of the app is visible. */
    val foreground = MutableStateFlow(true)

    private val history = ArrayDeque<LivePoint>()
    private var lastSampleAt = Long.MIN_VALUE
    private var lastSource = ""
    private var mutedUntil = 0L
    private var lastLevel: AlertLevel? = null
    private var lastNotified = 0L
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    fun dispose() {
        lifecycleObserver?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
        lifecycleObserver = null
        LiveNotifications.sync(c.context, LiveState(), true)
    }

    fun start(scope: CoroutineScope) {
        scope.launch { while (isActive) { refresh(); delay(500) } }
        scope.launch { c.bluetooth.latest.collect { refresh() } }
        scope.launch { c.temperature.history.collect { refresh() } }
        scope.launch {
            val observer = object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { foreground.value = true; refresh(force = true) }
                override fun onStop(owner: LifecycleOwner) { foreground.value = false; refresh(force = true) }
            }
            lifecycleObserver = observer
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        }
    }

    /** Silences vibration and voice for a minute; the red state stays visible. */
    fun acknowledge() {
        mutedUntil = System.currentTimeMillis() + LiveRules.MUTE_MS
        refresh()
    }

    /** Speaks the current alert (or reading) on request. */
    fun speakNow() {
        val s = _state.value
        val text = s.alert?.let { "${it.title}，${it.text}" }
            ?: s.temperature?.let { "当前锅温 ${it.roundToInt()} 度。${s.advice?.text.orEmpty()}" }
            ?: "暂无有效锅温读数"
        runCatching { c.voice.speak(text) }
    }

    private fun session(now: Long): LiveSession? {
        val user = c.sessions.currentUserId ?: return null
        val engine = c.cooking(user)
        val s = engine.state.value ?: return null
        if (!s.active) return null
        val recipe = s.recipeModel
        val steps = recipe.steps
        val step = steps.getOrNull(s.stepIndex) ?: return null
        val next = steps.getOrNull(s.stepIndex + 1)
        return LiveSession(
            id = s.id,
            dish = recipe.dishName.ifBlank { "当前菜谱" },
            imageUrl = recipe.imageUrl,
            stepIndex = s.stepIndex,
            total = steps.size,
            stepTitle = step.title,
            stepText = step.text,
            nextTitle = next?.title.orEmpty(),
            target = LiveRules.parseTarget(step.temperatureText),
            remainingMs = engine.remaining(),
            paused = s.timer.deadline == null,
            timed = (step.durationSeconds ?: 0.0) > 0 || s.timer.round > 0,
            totalSeconds = recipe.totalSeconds,
            futureSeconds = steps.drop(s.stepIndex + 1).sumOf { it.durationSeconds?.takeIf { d -> d > 0 } ?: 0.0 },
        )
    }

    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val replay = c.temperature.replaying.value
        val connection = c.bluetooth.connection.value
        val sample = if (replay) c.temperature.history.value.lastOrNull() else c.bluetooth.latest.value
        val t = sample?.temperature?.takeIf { sample.valid && it.isFinite() }
        val source = if (replay) "simulation" else "device"
        if (source != lastSource) { history.clear(); lastSource = source; lastSampleAt = Long.MIN_VALUE }
        val assessment = c.temperature.assessment.value
        val usable = assessment.quality == "usable" && t != null
        if (!usable) history.clear()
        if (usable && sample != null && t != null && sample.updatedAt != lastSampleAt) {
            lastSampleAt = sample.updatedAt
            history.addLast(LivePoint(now, t))
        }
        while (history.isNotEmpty() && now - history.first().at > LiveRules.HISTORY_MS) history.removeFirst()
        val next = LiveState(
            connected = connection.connected,
            simulated = replay,
            supported = c.bluetooth.supported,
            deviceLabel = connection.label,
            temperature = t,
            history = history.toList(),
            session = session(now),
            risk = if (usable) assessment.risk else null,
            suggestion = if (usable) assessment.suggestion else null,
            qualityLabel = assessment.qualityLabel,
            measurementUsable = usable,
            mutedUntil = mutedUntil,
            now = now,
        )
        _state.value = next
        effects(next)
        if (force || now - lastNotified >= 1000) {
            lastNotified = now
            LiveNotifications.sync(c.context, next, foreground.value)
        }
    }

    private fun effects(s: LiveState) {
        val level = s.alert?.level
        if (level == AlertLevel.Danger && lastLevel != AlertLevel.Danger && !s.muted) {
            vibrate()
            runCatching { c.voice.speak("锅温过高，请立即调小火力") }
        }
        lastLevel = level
    }

    private fun vibrate() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                c.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") c.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            val pattern = longArrayOf(0, 260, 140, 260, 140, 420)
            if (Build.VERSION.SDK_INT >= 26) vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else @Suppress("DEPRECATION") vibrator?.vibrate(pattern, -1)
        }
    }
}
