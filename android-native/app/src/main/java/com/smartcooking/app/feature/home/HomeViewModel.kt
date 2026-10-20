package com.smartcooking.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartcooking.app.core.AppContainer
import com.smartcooking.app.core.asArray
import com.smartcooking.app.core.asBool
import com.smartcooking.app.core.asObject
import com.smartcooking.app.core.num
import com.smartcooking.app.data.FreshnessState
import com.smartcooking.app.data.InventoryItem
import com.smartcooking.app.data.Recipe
import com.smartcooking.app.data.detail
import com.smartcooking.app.data.safeHistory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val inventory: List<InventoryItem> = emptyList(),
    val freshness: FreshnessState = FreshnessState.Idle,
    val latestRecipe: Recipe? = null,
    val unread: Int = 0,
) {
    val kindCount: Int get() = inventory.map { it.name.trim().lowercase() }.filter { it.isNotEmpty() }.toSet().size

    /** Distinct ingredient kinds that are critical or expiring soon but not yet expired. */
    val expiring: List<InventoryItem>
        get() = inventory.filter { item ->
            val d = freshness.detail(item.id)
            d != null && !d.expired && (d.critical || d.expiringSoon)
        }.distinctBy { it.name.trim().lowercase() }

    val expiringCountText: String get() = if (freshness is FreshnessState.Ready) "${expiring.size}" else "—"
}

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        refresh()
        viewModelScope.launch { c.inventory.changed.collect { if (it == c.sessions.currentUserId) refresh() } }
        viewModelScope.launch { c.api.changed.collect { refresh() } }
    }

    fun refresh() {
        val user = c.sessions.currentUserId ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false, freshness = FreshnessState.Idle) }
            try {
                val rows = c.inventory.read(user)
                if (c.sessions.currentUserId != user) return@launch
                _state.update { it.copy(inventory = rows, loading = false, freshness = FreshnessState.Loading) }
                val fresh = runCatching { c.inventory.freshness(user) }.getOrElse { e -> FreshnessState.Failed(e.message ?: "鲜度暂不可用") }
                if (c.sessions.currentUserId != user) return@launch
                _state.update { it.copy(freshness = fresh) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(loading = false, error = true) }
            }
        }
        viewModelScope.launch {
            runCatching {
                val history = safeHistory(c.api.get("/chat-history", mapOf("user_id" to user)))
                if (c.sessions.currentUserId != user) return@launch
                _state.update { s -> s.copy(latestRecipe = history.lastOrNull { it.recipe != null }?.recipe) }
            }
            runCatching {
                val summary = c.api.get("/notifications/summary", mapOf("user_id" to user)).asObject()
                if (c.sessions.currentUserId != user) return@launch
                _state.update { s -> s.copy(unread = summary?.num("unread")?.toInt() ?: 0) }
            }
        }
    }
}
