package com.smartcooking.app.core

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.smartcooking.app.data.InventoryRepository
import com.smartcooking.app.data.PreferencesRepository
import com.smartcooking.app.data.RecognitionDrafts
import com.smartcooking.app.data.Recipe
import com.smartcooking.app.device.BluetoothService
import com.smartcooking.app.feature.cooking.CompletionSync
import com.smartcooking.app.feature.cooking.CookingEngine
import com.smartcooking.app.feature.cooking.CookingNotifications
import com.smartcooking.app.feature.cooking.PersonalConsumption
import com.smartcooking.app.feature.cooking.VoiceService
import com.smartcooking.app.feature.live.LiveKitchen
import com.smartcooking.app.temperature.TemperatureIntelligence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject

/** Hand-rolled dependency container; one instance per process. */
class AppContainer(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = KeyValueStore(context)
    val sessions = SessionManager(store)
    val local = com.smartcooking.app.offline.LocalRuntime(context, store, sessions)
    val api = ApiClient(sessions, local)
    val business = BusinessApi(api, sessions)
    val inventory = InventoryRepository(api, store)
    val events = AppEvents()
    val bluetooth = BluetoothService(context, store)
    val drafts = RecognitionDrafts(store)
    val preferences = PreferencesRepository(business)
    val completions = CompletionSync(store, business, sessions)
    val consumption = PersonalConsumption(api, inventory, store, sessions)
    val notifications = CookingNotifications(context, store)
    val voice by lazy { VoiceService(context) }
    val temperature = TemperatureIntelligence(context, store, appScope)
    /** One live kitchen state for the home card, kitchen, floating card and notification. */
    val live = LiveKitchen(this)

    private val engines = mutableMapOf<String, CookingEngine>()

    /** One cooking engine per account, like the web `cookingStore` map. */
    fun cooking(user: String): CookingEngine = engines.getOrPut(user) { CookingEngine(user, store) }

    fun pendingCommand(scope: String, onSuccess: suspend (JsonObject) -> Unit = {}) =
        PendingCommand(scope, business, store, sessions, onSuccess)

    private var foreground = true
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    fun dispose() {
        notifications.cancelAll()
        voice.dispose()
        bluetooth.dispose()
        live.dispose()
        lifecycleObserver?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
        appScope.cancel()
    }

    init {
        sessions.beforeSessionChange = { events.clearAll() }
        local.cookingContext = {
            val session = sessions.currentUserId?.let { engines[it]?.state?.value }
            jsonOf("dish_name" to session?.recipeModel?.dishName, "step_index" to session?.stepIndex,
                "step_text" to session?.let { it.recipeModel.steps.getOrNull(it.stepIndex)?.text },
                "connected" to bluetooth.connection.value.connected, "temperature" to bluetooth.latest.value?.temperature,
                "temperature_quality" to temperature.assessment.value.quality,
                "source" to if (temperature.replaying.value) "simulation" else "hardware")
        }
        // Restoration after restart requires confirmation, so no stale alarm may fire.
        notifications.cancelAll()
        live.start(appScope)
        appScope.launch {
            sessions.session.map { it?.userId }.distinctUntilChanged().collect { user ->
                events.clearAll()
                engines.values.forEach { it.ready = false }
                notifications.cancelAll()
                temperature.bindUser(user)
            }
        }
        appScope.launch {
            bluetooth.samples.collect { sample -> if (foreground) temperature.receive(sample) }
        }
        appScope.launch {
            bluetooth.connection.collect { if (!it.connected) temperature.invalidate(if (it.label == "未连接") "等待连续温度数据" else it.label) }
        }
        appScope.launch {
            while (isActive) { delay(1000); bluetooth.expireStale() }
        }
        appScope.launch { while (isActive) { delay(60_000); if (foreground) api.changed.tryEmit(Unit) } }
        appScope.launch {
            val observer = object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    foreground = true
                    bluetooth.reconcile()
                    temperature.invalidate("恢复前台，等待新的连续测量")
                    api.changed.tryEmit(Unit)
                }
                override fun onStop(owner: LifecycleOwner) {
                    foreground = false
                    bluetooth.onBackground()
                    temperature.invalidate("应用在后台，不能保证连续采集")
                    runCatching { voice.stopAll(); voice.stopListening() }
                }
            }
            lifecycleObserver = observer
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        }
    }
}

/** Cross-screen hand-offs that the web client passed through query strings and sessionStorage. */
class AppEvents {
    fun clearAll() {
        pendingDish.value = null
        startRecognition.value = false
        editProfile.value = false
        recipeDraft.value = null
        immersive.value = false
        openCooking.value = false
        launchRoute.value = null
        openNotices.value = false
        chefPrompt.value = null
        focusInventory.value = null
        leftoverName.value = null
    }
    /** A dish name the AI kitchen should ask for (from the fridge's "咨询教程"). */
    val focusInventory = MutableStateFlow<String?>(null)
    val leftoverName = MutableStateFlow<String?>(null)
    val pendingDish = MutableStateFlow<String?>(null)
    /** Set by the home screen to open the camera as soon as the fridge tab appears. */
    val startRecognition = MutableStateFlow(false)
    /** Opens the profile editor when "我的" appears (from 账号与安全). */
    val editProfile = MutableStateFlow(false)
    /** A verified recipe copy queued for the kitchen; cooking starts only when the user taps start. */
    val recipeDraft = MutableStateFlow<Recipe?>(null)
    /** True while the step-by-step cooking view is full screen (the tab bar and floating card hide). */
    val immersive = MutableStateFlow(false)
    /** Set by the floating card or notification: open the cooking view of the active session. */
    val openCooking = MutableStateFlow(false)
    /** A tab to open once the UI is ready (from the live notification). */
    val launchRoute = MutableStateFlow<String?>(null)
    /** Opens the message centre when "我的" appears (bell buttons on other tabs). */
    val openNotices = MutableStateFlow(false)
    /** A prompt for the AI recipe tab (e.g. from the fridge "用这些食材能做什么"). */
    val chefPrompt = MutableStateFlow<String?>(null)
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
