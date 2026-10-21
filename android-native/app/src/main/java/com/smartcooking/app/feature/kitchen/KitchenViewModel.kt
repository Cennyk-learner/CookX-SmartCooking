package com.smartcooking.app.feature.kitchen

import com.smartcooking.app.core.toFiniteOrNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcooking.app.core.AppContainer
import com.smartcooking.app.core.ApiException
import com.smartcooking.app.core.asObject
import com.smartcooking.app.core.str
import com.smartcooking.app.core.userMessage
import com.smartcooking.app.data.ChatMessage
import com.smartcooking.app.data.InventoryRules
import com.smartcooking.app.data.Recipe
import com.smartcooking.app.data.safeHistory
import com.smartcooking.app.device.BluetoothException
import com.smartcooking.app.feature.cooking.Adjustment
import com.smartcooking.app.feature.cooking.Playback
import com.smartcooking.app.feature.cooking.Reminder
import com.smartcooking.app.feature.cooking.VoiceCommands
import com.smartcooking.app.temperature.CookingContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val lastPrompt: String = "",
)

data class VoiceUi(
    val message: String = "",
    val listening: Boolean = false,
    val capturing: Boolean = false,
    val inputLevel: Float = 0f,
    val commandText: String = "",
    val commandStatus: String = "点击麦克风后说一句指令，例如「下一步」。",
    val pendingCommands: List<String> = emptyList(),
)

class KitchenViewModel(private val c: AppContainer) : ViewModel() {
    val user = c.sessions.currentUserId.orEmpty()
    val engine = c.cooking(user)
    val temperature = c.temperature
    val voice = c.voice
    val bluetooth = c.bluetooth

    private val _chat = MutableStateFlow(ChatState())
    val chat = _chat.asStateFlow()
    private val _cooking = MutableStateFlow(engine.ready && engine.state.value?.active == true)
    /** True while the step-by-step cooking view is shown. */
    val cooking = _cooking.asStateFlow()
    val now = MutableStateFlow(System.currentTimeMillis())
    val voiceUi = MutableStateFlow(VoiceUi())
    val sessionMessage = MutableStateFlow(engine.warning)
    val showCompletion = MutableStateFlow(false)
    val checkError = MutableStateFlow<String?>(null)
    val notificationMessage = c.notifications.message
    val remindersOn = MutableStateFlow(c.notifications.enabled(user))
    val confirmReplace = MutableStateFlow<Recipe?>(null)
    /** Emits when a cooking session starts, so the AI recipe tab can switch to the kitchen. */
    val started = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var recipeJob: Job? = null
    private var listenJob: Job? = null
    @Volatile private var listenGeneration = 0L
    private var cloudStepJob: Job? = null
    private var ticker: Job? = null

    init {
        loadHistory()
        viewModelScope.launch {
            c.events.recipeDraft.collect { draft ->
                if (draft != null) {
                    c.events.recipeDraft.value = null
                    _chat.update { it.copy(messages = it.messages + ChatMessage("assistant", "独立复刻菜谱已准备好。点击开始指导后才会启动烹饪。", draft)) }
                }
            }
        }
        viewModelScope.launch {
            c.events.pendingDish.collect { dish ->
                if (!dish.isNullOrBlank()) {
                    c.events.pendingDish.value = null
                    delay(300)
                    send("我想做$dish，请给我详细教程")
                }
            }
        }
        // Keep the temperature engine's context on the current step.
        viewModelScope.launch {
            engine.state.map { s -> s?.let { Triple(it.recipe, it.stepIndex, it.recipeVersion) } }.distinctUntilChanged().collect { t ->
                val s = engine.state.value
                if (s != null && t != null) temperature.setContext(CookingContext.adapt(s.recipeModel, s.recipeModel.steps.getOrNull(s.stepIndex), s.stepIndex))
            }
        }
        // Inventory check whenever a new session or recipe version starts.
        viewModelScope.launch {
            engine.state.map { it?.id to it?.recipeVersion }.distinctUntilChanged().collect { (id, _) -> if (id != null && engine.state.value?.active == true) checkInventory() }
        }
        // Temperature risk and quality become cooking reminders (not during replay).
        viewModelScope.launch {
            temperature.assessment.collect { a ->
                if (temperature.replaying.value || !engine.ready || engine.state.value?.active != true || !bluetooth.connection.value.connected) return@collect
                if (a.quality != "usable") publish(engine.recordReminder("temperature-quality", "sensor", "温度测量不可用或待稳定；暂停温度操作建议，请核对探头与连接。"))
                else if (a.risk == "warning" || a.risk == "danger") publish(engine.recordReminder("temperature", "sensor", a.suggestion ?: a.reasons.joinToString("；").ifBlank { "请核对温度状态" }, a.risk))
            }
        }
        if (_cooking.value) startTicker()
    }

    // ------------------------------------------------------------------ chat

    private fun loadHistory() {
        if (user.isBlank()) return
        viewModelScope.launch {
            try {
                val history = safeHistory(c.api.get("/chat-history", mapOf("user_id" to user)))
                if (_chat.value.loading) return@launch
                _chat.update { it.copy(messages = history.ifEmpty { listOf(ChatMessage("assistant", "你好！我是你的智能厨房助手。告诉我你想做什么菜，或者说说冰箱里有什么。")) } + it.messages.filter { m -> m.recipe != null && m.content.startsWith("独立复刻") }) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                _chat.update { if (it.messages.isEmpty()) it.copy(messages = listOf(ChatMessage("assistant", "你好！我是你的智能厨房助手。"))) else it }
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank() || user.isBlank() || c.sessions.currentUserId != user) return
        recipeJob?.cancel()
        recipeJob = viewModelScope.launch {
            _chat.update { it.copy(messages = it.messages + ChatMessage("user", text), loading = true, error = null, lastPrompt = text) }
            try {
                val res = c.api.get("/recommend-recipe", mapOf("user_id" to user, "user_prompt" to text, "save_history" to true), timeoutMs = 45_000).asObject()
                if (c.sessions.currentUserId != user) return@launch
                if (res?.str("status") != "success") throw ApiException(res?.str("message") ?: "菜谱服务未返回成功结果")
                val recipe = Recipe.normalize(res["recipe"])
                _chat.update { it.copy(messages = it.messages + ChatMessage("assistant", res.str("notice") ?: "为你准备好了：${recipe.dishName}", recipe)) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _chat.update { it.copy(error = if ((e as? ApiException)?.isTimeout == true) "菜谱请求超时，请重试" else e.userMessage()) }
            } finally { _chat.update { it.copy(loading = false) } }
        }
    }

    // ------------------------------------------------------------------ cooking session

    fun requestStart(recipe: Recipe) {
        if (engine.state.value?.active == true) confirmReplace.value = recipe else start(recipe)
    }

    fun start(recipe: Recipe) {
        confirmReplace.value = null
        try {
            engine.start(recipe, replace = true)
            engine.ready = true
            _cooking.value = true
            startTicker()
            runStep()
            started.tryEmit(Unit)
        } catch (e: Exception) { _chat.update { it.copy(error = e.userMessage()) } }
    }

    /** Clears the saved conversation on the server (菜谱对话记录), keeping any open recipe draft. */
    fun clearChat(onDone: (String) -> Unit) {
        if (user.isBlank()) return
        viewModelScope.launch {
            try {
                c.api.post("/clear-chat", query = mapOf("user_id" to user))
                _chat.update { ChatState(messages = listOf(ChatMessage("assistant", "对话已清空。告诉我你想做什么菜，或者说说冰箱里有什么。"))) }
                onDone("已清空对话记录")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { onDone("清空失败：${e.userMessage()}") }
        }
    }

    fun restore() {
        engine.ready = true
        _cooking.value = true
        startTicker()
        reconcileTimer()
    }

    fun exitCooking() {
        silence()
        _cooking.value = false
        ticker?.cancel()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                now.value = System.currentTimeMillis()
                voice.tickProgress()
                engine.timerReminder()?.let { publish(it) }
                delay(250)
            }
        }
    }

    private fun publish(event: Reminder?) {
        if (event == null) return
        val s = engine.state.value ?: return
        val coveredBySystem = event.type == "timer" && s.timer.notificationKey == event.key
        if (remindersOn.value && !coveredBySystem) c.notifications.notifyEvent(s.user, c.sessions.currentUserId, event)
    }

    private fun reconcileTimer() {
        val key = c.notifications.reconcileTimer(engine, c.sessions.currentUserId)
        if (key != engine.state.value?.timer?.notificationKey) engine.setNotificationKey(key)
    }

    fun next() {
        val s = engine.state.value ?: return
        silence()
        if (s.stepIndex >= s.timers.size - 1) { showCompletion.value = true; return }
        engine.move(1); reconcileTimer(); runStep()
    }

    fun previous() {
        val s = engine.state.value ?: return
        if (s.stepIndex == 0) return
        silence(); engine.move(-1); reconcileTimer(); runStep()
    }

    fun pauseTimer() { engine.pause(); reconcileTimer() }
    fun resumeTimer() { engine.resume(); reconcileTimer() }
    fun setManualTimer(seconds: String) {
        try { engine.setTimer(seconds.toFiniteOrNull() ?: Double.NaN); reconcileTimer() }
        catch (e: IllegalArgumentException) { voiceUi.update { it.copy(message = e.message.orEmpty()) } }
    }

    private fun stepText(): String {
        val s = engine.state.value ?: return ""
        return "第${s.stepIndex + 1}步：${s.recipeModel.steps[s.stepIndex].text}"
    }

    /** Speaks the current step with the system voice; timing continues if speech fails. */
    fun runStep() {
        cancelListening()
        cloudStepJob?.cancel()
        voiceUi.update { it.copy(message = "") }
        try { voice.speak(stepText()) } catch (e: Exception) { voiceUi.update { it.copy(message = e.userMessage()) } }
    }

    /** Explicit fallback: server TTS, downloaded with authorisation. */
    fun runCloudStep() {
        cancelListening()
        cloudStepJob?.cancel()
        voice.stopAll()
        cloudStepJob = viewModelScope.launch {
            try {
                val text = stepText()
                val audio = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.local.gateway.tts(text) }
                if (c.sessions.currentUserId == user && text == stepText()) voice.playCloud(audio)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                voiceUi.update { it.copy(message = "在线播报不可用：${e.userMessage()}。可使用系统语音，步骤与计时不受影响。") }
            }
        }
    }

    fun toggleVoice() {
        when (voice.playback.value) {
            Playback.PLAYING -> if (voice.hasCloudAudio) voice.pauseCloud() else { voice.stopSpeaking(); voiceUi.update { it.copy(message = "系统播报已暂停，再次播放将从本步开头播报") } }
            Playback.PAUSED -> if (!voice.resumeCloud()) runStep()
            else -> runStep()
        }
    }

    fun silence() { cloudStepJob?.cancel(); voice.stopAll(); cancelListening() }

    // ------------------------------------------------------------------ voice commands

    fun listen() {
        if (voiceUi.value.listening) return
        silence()
        val generation = ++listenGeneration
        listenJob = viewModelScope.launch {
            voiceUi.update { it.copy(listening = true, capturing = false, inputLevel = 0f,
                commandText = "", commandStatus = "正在启动麦克风，请稍候…", pendingCommands = emptyList()) }
            try {
                val sessionId = engine.state.value?.id
                val stepIndex = engine.state.value?.stepIndex
                val transcribe: (suspend (ByteArray) -> String)? = if (c.local.gateway.configured) { audio ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.local.gateway.transcribe(audio) }
                } else null
                val result = voice.listenOnce(transcribe,
                    onCapture = { active, level -> voiceUi.update { if (generation == listenGeneration) it.copy(capturing = active, inputLevel = level) else it } },
                    onStatus = { status -> voiceUi.update { if (generation == listenGeneration) it.copy(commandStatus = status) else it } })
                if (generation != listenGeneration) return@launch
                // Do not apply a late recognition result to a step the user already changed manually.
                if (engine.state.value?.id != sessionId || engine.state.value?.stepIndex != stepIndex) {
                    voiceUi.update { it.copy(commandStatus = "步骤已变化，请针对当前步骤重新说指令") }
                    return@launch
                }
                listenJob = null // Navigation cancels listening; the completed result must not cancel its own execution.
                voiceUi.update { it.copy(commandText = result.text) }
                interpret(result.text, result.confidence)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                if (generation == listenGeneration) voiceUi.update { it.copy(commandStatus = "未收到识别结果，请再试一次") }
            } catch (e: CancellationException) {
                if (generation == listenGeneration) voiceUi.update { it.copy(commandStatus = "录音已停止，请点击麦克风重试") }
                throw e
            } catch (e: Exception) {
                if (generation == listenGeneration) voiceUi.update { it.copy(commandStatus = e.userMessage()) }
            } finally {
                if (generation == listenGeneration) {
                    listenJob = null
                    voiceUi.update { it.copy(listening = false, capturing = false, inputLevel = 0f) }
                }
            }
        }
    }

    fun cancelListening() {
        ++listenGeneration
        listenJob?.cancel(); listenJob = null
        voice.stopListening()
        voiceUi.update { it.copy(listening = false, capturing = false, inputLevel = 0f,
            commandStatus = if (it.listening) "已取消本次语音" else it.commandStatus) }
    }
    fun finishListening() {
        if (!voiceUi.value.capturing) return
        voice.finishListening()
        voiceUi.update { it.copy(capturing = false, inputLevel = 0f, commandStatus = "正在处理录音…") }
    }
    fun setCommandText(text: String) = voiceUi.update { it.copy(commandText = text) }

    fun interpret(text: String, confidence: Float?) {
        val parsed = VoiceCommands.parse(text, confidence)
        when {
            parsed.matches.isEmpty() -> voiceUi.update { it.copy(commandStatus = "未匹配支持的指令，请重说或使用按钮", pendingCommands = emptyList()) }
            parsed.confirmed -> execute(parsed.matches.first())
            else -> voiceUi.update { it.copy(commandStatus = "识别结果需要确认，尚未执行", pendingCommands = parsed.matches) }
        }
    }

    fun execute(command: String) {
        if (!_cooking.value || !engine.ready || engine.state.value?.active != true || c.sessions.currentUserId != user) {
            voiceUi.update { it.copy(pendingCommands = emptyList(), commandStatus = "当前没有正在进行的烹饪，请先开始指导") }
            return
        }
        voiceUi.update { it.copy(pendingCommands = emptyList(), commandStatus = "执行：${VoiceCommands.labels[command]}") }
        when (command) {
            "next" -> next()
            "previous" -> previous()
            "repeat" -> runStep()
            "pauseTimer" -> pauseTimer()
            "resumeTimer" -> resumeTimer()
            "startTimer" -> {
                val d = engine.state.value?.let { it.recipeModel.steps[it.stepIndex].durationSeconds } ?: 0.0
                if (d > 0) setManualTimer(d.toString()) else voiceUi.update { it.copy(message = "本步未提供时长，请填写手动计时秒数并确认开始") }
            }
            "temperature" -> {
                val t = bluetooth.latest.value?.temperature
                val a = temperature.assessment.value
                voiceUi.update { it.copy(message = if (t == null) "暂无有效实时温度，请检查设备与测量状态" else "${if (temperature.replaying.value) "回放数据" else "当前测量"}：${"%.1f".format(t)}℃；${a.phaseLabel}") }
            }
        }
        if (command in listOf("next", "previous", "repeat")) voiceUi.update {
            it.copy(commandStatus = if (showCompletion.value) "已到最后一步，请核对烹饪完成情况" else "已${if (command == "repeat") "重播" else "切换到"}第 ${(engine.state.value?.stepIndex ?: 0) + 1} 步")
        }
    }

    // ------------------------------------------------------------------ reminders & checks

    fun setReminders(enabled: Boolean, permitted: Boolean) {
        if (enabled && !permitted) { notificationMessage.value = "通知权限未授予，请保持页面可见并查看计时状态。"; return }
        c.notifications.setEnabled(user, enabled)
        remindersOn.value = enabled
        reconcileTimer()
    }

    fun onExactSettingsReturned() = reconcileTimer()
    fun exactSettingsIntent() = c.notifications.exactSettingsIntent()
    fun dismissReminder(key: String) = engine.dismissReminder(key)

    fun checkInventory() {
        val s = engine.state.value ?: return
        val version = s.recipeVersion
        viewModelScope.launch {
            checkError.value = null
            try {
                val rows = c.inventory.read(user)
                if (engine.state.value?.id != s.id || engine.state.value?.recipeVersion != version) return@launch
                val present = rows.filter { it.quantity > 0 }.map { InventoryRules.canonicalName(it.name) }.toSet()
                val recipe = s.recipeModel
                val required = (recipe.usedIngredients + recipe.ingredients.map { it.item } + recipe.missing).distinct()
                val missing = required.filter { InventoryRules.canonicalName(it) !in present }
                if (missing.isNotEmpty()) publish(engine.recordReminder("missing", "recipe:$version", "库存中未确认：${missing.joinToString("、")}。请在继续前核对食材。", "warning", once = true))
                val fresh = c.inventory.freshness(user)
                for ((id, d) in fresh.items) {
                    val row = rows.firstOrNull { it.id == id } ?: continue
                    if (d.expired || d.expiringSoon || d.critical) publish(engine.recordReminder("freshness", id,
                        "${row.name}：${if (d.expired) "后端判定已过期" else "后端提示临期"}。评估时间 ${fresh.evaluatedAt ?: "未提供"}；请到库存页查看依据与免责声明。",
                        if (d.expired) "danger" else "warning"))
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { checkError.value = "食材检查未完成：${e.userMessage()}" }
        }
    }

    // ------------------------------------------------------------------ adjustments

    val adjustmentPreview = MutableStateFlow<Adjustment?>(null)
    val adjustmentError = MutableStateFlow<String?>(null)
    fun previewAdjustment(type: String) = try { adjustmentError.value = null; adjustmentPreview.value = engine.previewAdjustment(type) } catch (e: Exception) { adjustmentError.value = e.message }
    fun applyAdjustment() {
        val p = adjustmentPreview.value ?: return
        try { engine.applyAdjustment(p) } catch (e: Exception) { adjustmentError.value = e.message }
        adjustmentPreview.value = null
    }
    fun undoAdjustment(id: String) = try { engine.undoAdjustment(id) } catch (e: Exception) { adjustmentError.value = e.message }

    // ------------------------------------------------------------------ completion

    fun completed() {
        if (engine.state.value?.completed != true) engine.finish()
        reconcileTimer()
        val s = engine.state.value ?: return
        viewModelScope.launch {
            try { c.completions.enqueue(s); c.completions.sync(user) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { sessionMessage.value = "本地完成已保留，成长记录待同步，可在厨艺成长页核对。" }
        }
    }

    fun closeCompletion() {
        showCompletion.value = false
        if (engine.state.value?.completed == true) exitCooking()
    }

    // ------------------------------------------------------------------ device

    val deviceMessage = MutableStateFlow("")
    val connecting = MutableStateFlow(false)

    fun scan(): String? = try {
        if (!bluetooth.startDiscovery()) "附近扫描未能启动；可以选择已配对的 JDY-31 尝试连接。" else null
    } catch (e: BluetoothException) { e.userMessage }

    fun stopScan() = bluetooth.stopDiscovery()

    fun connect(address: String, onResult: (String) -> Unit) {
        if (connecting.value) return
        if (temperature.replaying.value) temperature.stopReplay()
        viewModelScope.launch {
            connecting.value = true
            temperature.invalidate("正在连接设备")
            try {
                val device = bluetooth.connect(address)
                onResult("CookX Sense 已连接：${device.name}")
            } catch (e: BluetoothException) { onResult(e.userMessage) } catch (e: Exception) { onResult(e.userMessage()) }
            finally { connecting.value = false }
        }
    }

    fun disconnect() { bluetooth.disconnect(); temperature.invalidate("设备已断开") }

    fun refreshDevice() {
        val result = bluetooth.reconcile()
        temperature.invalidate("状态已核对，等待新的连续测量")
        deviceMessage.value = "状态已核对（${result.label}）；等待新的连续测量窗口。"
    }

    override fun onCleared() { silence() }
}
