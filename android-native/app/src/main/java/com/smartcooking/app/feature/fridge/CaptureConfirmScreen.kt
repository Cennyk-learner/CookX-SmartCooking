package com.smartcooking.app.feature.fridge

import com.smartcooking.app.core.toFiniteOrNull
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartcooking.app.core.AppContainer
import com.smartcooking.app.data.DraftItem
import com.smartcooking.app.data.FoodCatalog
import com.smartcooking.app.data.Freshness
import com.smartcooking.app.data.FreshnessState
import com.smartcooking.app.data.InventoryForm
import com.smartcooking.app.data.InventoryRepository
import com.smartcooking.app.data.InventoryRules
import com.smartcooking.app.ui.components.AnimatedBanner
import com.smartcooking.app.ui.components.BannerKind
import com.smartcooking.app.ui.components.CookXCard
import com.smartcooking.app.ui.components.EmptyState
import com.smartcooking.app.ui.components.IngredientImage
import com.smartcooking.app.ui.components.LocalMessenger
import com.smartcooking.app.ui.components.OutlineButton
import com.smartcooking.app.ui.components.PrimaryButton
import com.smartcooking.app.ui.components.StatusChip
import com.smartcooking.app.ui.components.SubpageScaffold
import com.smartcooking.app.ui.components.Tone
import com.smartcooking.app.ui.components.overlapHero
import com.smartcooking.app.ui.components.pressable
import com.smartcooking.app.ui.nav.Navigator
import com.smartcooking.app.ui.nav.Routes
import com.smartcooking.app.ui.nav.cookxViewModel
import com.smartcooking.app.ui.theme.CookX
import com.smartcooking.app.ui.theme.CookXShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray

data class ConfirmState(val items: List<DraftItem> = emptyList(), val saving: Boolean = false, val error: String? = null, val pendingWrite: Boolean = false)

class CaptureConfirmViewModel(private val c: AppContainer) : ViewModel() {
    private val user = c.sessions.currentUserId
    private val _state = MutableStateFlow(ConfirmState(user?.let(c.drafts::load).orEmpty(), pendingWrite = user?.let(c.inventory::hasPendingWrite) == true))
    val state = _state.asStateFlow()

    private fun persist(items: List<DraftItem>) {
        _state.update { it.copy(items = items, error = null) }
        user?.let { runCatching { c.drafts.save(it, items) }.onFailure { _state.update { s -> s.copy(error = "草稿无法保存在本机，请保持此页打开") } } }
    }

    fun update(index: Int, form: InventoryForm) = persist(_state.value.items.mapIndexed { i, d -> if (i == index) d.copy(form = form) else d })
    fun remove(index: Int) = persist(_state.value.items.filterIndexed { i, _ -> i != index })
    fun release() { user?.let(c.inventory::dismissPendingWrite); _state.update { it.copy(pendingWrite = false, error = null) } }
    fun discard(onDone: () -> Unit) {
        if (_state.value.saving || _state.value.pendingWrite) return
        if (user != null && !c.drafts.clear(user)) { _state.update { it.copy(error = "草稿清除失败，请重试") }; return }
        _state.update { it.copy(items = emptyList()) }; onDone()
    }

    fun save(onSaved: () -> Unit) {
        val user = user ?: return
        val s = _state.value
        if (s.saving || s.items.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                val payload = if (c.inventory.hasPendingWrite(user)) null else JsonArray(s.items.map { InventoryRules.serialize(it.form) })
                c.inventory.save(user, payload, recognition = true)
                c.drafts.clear(user)
                _state.update { it.copy(saving = false, pendingWrite = false) }
                onSaved()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = InventoryRepository.errorMessage(e), pendingWrite = c.inventory.hasPendingWrite(user)) }
            }
        }
    }
}

@Composable
fun CaptureConfirmScreen(navigator: Navigator) {
    val vm = cookxViewModel { CaptureConfirmViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    var confirmDiscard by remember { mutableStateOf(false) }
    val items = state.items
    val kinds = items.map { FoodCatalog.key(it.form.name) }.filter { it.isNotEmpty() }.toSet().size
    val total = items.sumOf { it.form.quantity.toFiniteOrNull() ?: 0.0 }.toLong()
    val minShelf = items.mapNotNull { it.form.shelfLife.toFiniteOrNull()?.takeIf { d -> d > 0 } }.minOrNull()

    SubpageScaffold(
        if (items.any { it.source == "sample" }) "示例食材确认" else "识别结果确认", navigator::back,
        subtitle = "请确认识别出的食材及数量，必要时修改后再加入冰箱",
        heroExtra = {
            Spacer(Modifier.height(12.dp))
            StatusChip(if (items.any { it.source == "sample" }) "预置示例 · 非照片识别结果" else "AI 识别完成", Tone.Fresh, icon = Icons.Outlined.CheckCircle)
        },
        bottomBar = if (items.isEmpty()) null else { {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                AnimatedBanner(state.error, BannerKind.Warning, Modifier.padding(bottom = 8.dp))
                PendingWriteNotice(state.pendingWrite, state.saving, vm::release)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = if (state.pendingWrite) 8.dp else 0.dp)) {
                    OutlineButton("重新识别", { navigator.tab(Routes.FRIDGE) }, icon = Icons.Outlined.RestartAlt, enabled = !state.saving)
                    PrimaryButton("确认加入冰箱（${items.size}项）", {
                        vm.save { messenger.show("已重新读取库存并确认保存"); navigator.tab(Routes.FRIDGE) }
                    }, Modifier.weight(1f), loading = state.saving)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlineButton("保留草稿返回", navigator::back, enabled = !state.saving)
                    OutlineButton("放弃并清空", { confirmDiscard = true }, enabled = !state.saving && !state.pendingWrite)
                }
            }
        } },
    ) {
        item {
            CookXCard(Modifier.padding(horizontal = 16.dp).overlapHero()) {
                Row {
                    Summary("待确认", "${items.size}", "项", Modifier.weight(1f))
                    Summary("食材种类", "$kinds", "种", Modifier.weight(1f))
                    Summary("总数量", "$total", "份", Modifier.weight(1f))
                    Summary("最短保质期", minShelf?.let { "%.0f".format(it) } ?: "--", "天", Modifier.weight(1f))
                }
            }
        }
        if (items.isEmpty()) item {
            EmptyState(Icons.Outlined.ImageNotSupported, "未检测到可确认的食材", "请重新选择图片进行识别") {
                PrimaryButton("重新识别", { navigator.tab(Routes.FRIDGE) }, icon = Icons.Outlined.RestartAlt)
            }
        }
        itemsIndexed(items) { index, item ->
            CookXCard(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IngredientImage(item.form.name, item.imageUrl, Modifier.size(52.dp).clip(CookXShapes.Tile))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(FoodCatalog.info(item.form.name).cn.ifBlank { "未命名" }, style = MaterialTheme.typography.titleMedium)
                        Text("识别候选 ${index + 1} · 新鲜度 ${item.freshnessLabel ?: "数据不足"}", fontSize = 12.sp, color = CookX.TextSecondary)
                    }
                    Icon(Icons.Outlined.DeleteOutline, "删除该识别结果", tint = CookX.Danger,
                        modifier = Modifier.size(36.dp).clip(CookXShapes.Small).pressable({ vm.remove(index) }, enabled = !state.saving).padding(7.dp))
                }
                Spacer(Modifier.height(12.dp))
                InventoryFields(item.form, { vm.update(index, it) }, enabled = !state.saving && !state.pendingWrite)
                Spacer(Modifier.height(10.dp))
                if (item.freshnessDetail != null) FreshnessPanel(Freshness(item.freshnessDetail), FreshnessState.Ready(emptyMap(), null))
                else Text("识别结果仅供确认名称，鲜度数据不足，入库后重新评估。", fontSize = 11.5.sp, color = CookX.TextTertiary)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("识别小贴士", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = CookX.TextBody)
                listOf("请确认食材名称和数量", "可修改数量、储存方式和保质期", "确认后会加入我的冰箱", "如识别有误，可删除后重新识别").forEach {
                    Text("· $it", fontSize = 12.sp, color = CookX.TextSecondary)
                }
            }
        }
    }
    if (confirmDiscard) com.smartcooking.app.ui.components.ConfirmDialog("放弃这批食材草稿", "仅清空尚未入库的草稿，冰箱库存不会改变。", {
        confirmDiscard = false; vm.discard { navigator.tab(Routes.FRIDGE) }
    }, { confirmDiscard = false }, confirmText = "清空草稿")
}

@Composable
private fun Summary(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = CookX.Primary)
            Text(unit, fontSize = 10.sp, color = CookX.TextSecondary, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
        }
        Text(label, fontSize = 11.sp, color = CookX.TextSecondary)
    }
}
