package com.smartcooking.app.feature.fridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcooking.app.core.AppContainer
import com.smartcooking.app.core.ApiException
import com.smartcooking.app.core.asObject
import com.smartcooking.app.core.obj
import com.smartcooking.app.core.objects
import com.smartcooking.app.core.str
import com.smartcooking.app.core.userMessage
import com.smartcooking.app.data.DraftItem
import com.smartcooking.app.data.FoodCategory
import com.smartcooking.app.data.FreshnessState
import com.smartcooking.app.data.InventoryForm
import com.smartcooking.app.data.InventoryItem
import com.smartcooking.app.data.InventoryRepository
import com.smartcooking.app.data.InventoryRules
import com.smartcooking.app.data.detail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray

enum class RecognitionStatus { IDLE, UPLOADING, ANALYZING, SUCCESS, ERROR }

data class Editor(
    val editing: InventoryItem? = null,
    val form: InventoryForm = InventoryForm(),
    val saving: Boolean = false,
    val error: String? = null,
)

data class FridgeState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val inventory: List<InventoryItem> = emptyList(),
    val freshness: FreshnessState = FreshnessState.Idle,
    val lastUpdated: Long? = null,
    val search: String = "",
    val category: FoodCategory = FoodCategory.ALL,
    val editor: Editor? = null,
    val pendingWrite: Boolean = false,
    val recognition: RecognitionStatus = RecognitionStatus.IDLE,
    val uploadPercent: Int = 0,
    val elapsed: Int = 0,
    val recognizedCount: Int = 0,
    val quickDishes: List<String> = emptyList(),
    val quickLoading: Boolean = false,
    val revision: Int = 0,
) {
    val ready: Boolean get() = !loading && !error
    val kindCount: Int get() = inventory.map { it.name.trim().lowercase() }.filter { it.isNotEmpty() }.toSet().size
    val totalQuantity: Double get() = inventory.sumOf { it.quantity }

    private fun countKinds(predicate: (com.smartcooking.app.data.Freshness?) -> Boolean): String =
        if (freshness is FreshnessState.Ready) "${inventory.filter { predicate(freshness.detail(it.id)) }.map { it.name }.toSet().size}" else "—"
    val expiringText: String get() = countKinds { it != null && !it.expired && (it.critical || it.expiringSoon) }
    val expiredText: String get() = countKinds { it?.expired == true }
    val attention: List<InventoryItem> get() = inventory.filter { freshness.detail(it.id)?.let { d -> d.expired || d.critical || d.expiringSoon } == true }

    val categories: List<Triple<FoodCategory, String, Int?>>
        get() = FoodCategory.entries.map { c -> Triple(c, c.label, if (c == FoodCategory.ALL) inventory.size else inventory.count { it.category == c }) }
            .filter { it.first == FoodCategory.ALL || (it.third ?: 0) > 0 }

    val filtered: List<InventoryItem>
        get() = inventory.filter { item ->
            val matchesSearch = search.isBlank() || item.info.cn.contains(search.trim()) || item.name.lowercase().contains(search.trim().lowercase())
            matchesSearch && (category == FoodCategory.ALL || item.category == category)
        }
}

class FridgeViewModel(private val c: AppContainer) : ViewModel() {
    private val user = c.sessions.currentUserId
    private val _state = MutableStateFlow(FridgeState(pendingWrite = user?.let(c.inventory::hasPendingWrite) == true))
    val state: StateFlow<FridgeState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var quickJob: Job? = null
    private var recognitionJob: Job? = null

    init {
        refresh()
        viewModelScope.launch { c.inventory.changed.collect { if (it == user && _state.value.editor?.saving != true) refresh() } }
    }

    fun refresh() {
        val user = user ?: return
        loadJob?.cancel(); quickJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false, freshness = FreshnessState.Idle, quickDishes = emptyList(), revision = it.revision + 1) }
            try {
                val rows = c.inventory.read(user)
                _state.update { it.copy(inventory = rows, loading = false, lastUpdated = System.currentTimeMillis(), freshness = FreshnessState.Loading) }
                val fresh = try { c.inventory.freshness(user) } catch (e: CancellationException) { throw e } catch (e: Exception) { FreshnessState.Failed(e.userMessage()) }
                _state.update { it.copy(freshness = fresh) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

    /** Asks the model for one dish idea; only when the inspiration card is shown (it is an AI call). */
    fun loadInspiration() {
        val user = user ?: return
        if (_state.value.inventory.isEmpty() || _state.value.quickLoading || _state.value.quickDishes.isNotEmpty()) return
        quickJob?.cancel()
        quickJob = viewModelScope.launch {
            _state.update { it.copy(quickLoading = true) }
            try {
                val res = c.api.get("/recommend-recipe", mapOf(
                    "user_id" to user,
                    "user_prompt" to "根据库存推荐1个中文菜名。只需JSON格式: {\"dish_name\":\"菜名\",\"used_main\":\"主要食材\"}",
                    "save_history" to false,
                ), timeoutMs = 20_000).asObject()
                val dish = res?.obj("recipe")?.str("dish_name")
                if (res?.str("status") == "success" && dish != null) _state.update { it.copy(quickDishes = listOf(dish)) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
            } finally { _state.update { it.copy(quickLoading = false) } }
        }
    }

    fun setSearch(value: String) = _state.update { it.copy(search = value) }
    fun setCategory(value: FoodCategory) = _state.update { it.copy(category = value) }

    fun openAdd() = _state.update { it.copy(editor = Editor()) }
    fun openEdit(item: InventoryItem) = _state.update { it.copy(editor = Editor(editing = item, form = InventoryForm.from(item))) }
    fun updateForm(form: InventoryForm) = _state.update { s -> s.copy(editor = s.editor?.copy(form = form, error = null)) }
    fun closeEditor() { if (_state.value.editor?.saving != true) _state.update { it.copy(editor = null) } }

    /** Returns a toast message on success. */
    fun save(onDone: (String) -> Unit) {
        val user = user ?: return
        val editor = _state.value.editor ?: return
        if (editor.saving) return
        viewModelScope.launch {
            _state.update { it.copy(editor = editor.copy(saving = true, error = null)) }
            try {
                val pending = c.inventory.hasPendingWrite(user)
                val original = editor.editing
                val payload = if (pending) null else InventoryRules.serialize(editor.form, original)
                if (payload != null && original != null && payload.isEmpty()) { _state.update { it.copy(editor = null) }; return@launch }
                c.inventory.save(user, if (original != null) payload else payload?.let { JsonArray(listOf(it)) }, original?.id, false, original?.revision)
                _state.update { it.copy(editor = null, pendingWrite = false) }
                onDone("已重新读取库存并确认保存")
                refresh()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { s -> s.copy(editor = s.editor?.copy(saving = false, error = InventoryRepository.errorMessage(e)), pendingWrite = c.inventory.hasPendingWrite(user)) }
            }
        }
    }

    fun releasePending() {
        user?.let(c.inventory::dismissPendingWrite)
        _state.update { s -> s.copy(pendingWrite = false, editor = s.editor?.copy(error = null)) }
    }

    fun delete(item: InventoryItem, onResult: (String) -> Unit) {
        val user = user ?: return
        viewModelScope.launch {
            try {
                c.inventory.delete(user, item.id, item.revision)
                onResult("已移出冰箱")
                refresh()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { onResult(InventoryRepository.errorMessage(e)) }
        }
    }

    /** Uploads a compressed photo to `/analyze-fridge` and stores the candidates as a local draft. */
    fun recognize(loadBytes: suspend () -> ByteArray, onRecognized: () -> Unit) {
        val user = user ?: return
        if (_state.value.recognition in listOf(RecognitionStatus.UPLOADING, RecognitionStatus.ANALYZING)) return
        recognitionJob?.cancel()
        recognitionJob = viewModelScope.launch {
            _state.update { it.copy(recognition = RecognitionStatus.UPLOADING, uploadPercent = 0, elapsed = 0, recognizedCount = 0) }
            val ticker = launch { while (isActive) { delay(1000); _state.update { it.copy(elapsed = it.elapsed + 1) } } }
            try {
                val bytes = loadBytes()
                val response = c.api.upload("/analyze-fridge", "file", "fridge.jpg", "image/jpeg", bytes, timeoutMs = 120_000) { p ->
                    val percent = (p * 100).toInt()
                    _state.update { it.copy(uploadPercent = percent, recognition = if (percent >= 100) RecognitionStatus.ANALYZING else RecognitionStatus.UPLOADING) }
                }.asObject()
                val data = response?.obj("data") ?: response
                val detected = data?.objects("detected").orEmpty()
                if (data?.str("status") != "success" || detected.isEmpty()) throw ApiException(data?.str("message") ?: "未能识别到食材")
                if (c.sessions.currentUserId != user) { _state.update { it.copy(recognition = RecognitionStatus.IDLE) }; return@launch }
                c.drafts.save(user, detected.map { raw ->
                    DraftItem(InventoryForm.fromRecognition(raw), raw.obj("freshness_detail"), raw.str("freshness"), raw.str("image_url") ?: raw.str("image"))
                })
                ticker.cancel()
                _state.update { it.copy(recognition = RecognitionStatus.SUCCESS, recognizedCount = detected.size) }
                delay(650)
                _state.update { it.copy(recognition = RecognitionStatus.IDLE) }
                onRecognized()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(recognition = RecognitionStatus.ERROR) }
                lastRecognitionError = e.userMessage()
            } finally { ticker.cancel() }
        }
    }

    var lastRecognitionError: String = ""
        private set

    fun sample(onReady: () -> Unit) {
        val uid = user ?: return
        try {
            c.drafts.save(uid, listOf("番茄", "鸡蛋", "西兰花").map {
                DraftItem(InventoryForm(name = it, quantity = "2", storageType = "冷藏", shelfLife = "7"),
                    freshnessLabel = "预置示例，储存信息需要核对", source = "sample")
            })
            onReady()
        } catch (e: Exception) { lastRecognitionError = e.userMessage(); _state.update { it.copy(recognition = RecognitionStatus.ERROR) } }
    }

    fun resetRecognition() {
        recognitionJob?.cancel()
        _state.update { it.copy(recognition = RecognitionStatus.IDLE, uploadPercent = 0, elapsed = 0) }
    }
}
