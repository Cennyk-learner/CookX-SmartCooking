package com.smartcooking.app.feature.kitchen

import com.smartcooking.app.core.toFiniteOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartcooking.app.core.AppContainer
import com.smartcooking.app.core.ApiException
import com.smartcooking.app.core.Time
import com.smartcooking.app.core.clean
import com.smartcooking.app.core.jsonOf
import com.smartcooking.app.core.num
import com.smartcooking.app.core.obj
import com.smartcooking.app.core.objects
import com.smartcooking.app.core.str
import com.smartcooking.app.core.userMessage
import com.smartcooking.app.data.InventoryItem
import com.smartcooking.app.feature.cooking.ConsumeSelection
import com.smartcooking.app.feature.cooking.consumable
import com.smartcooking.app.ui.components.Banner
import com.smartcooking.app.ui.components.BannerKind
import com.smartcooking.app.ui.components.CookXSheet
import com.smartcooking.app.ui.components.CookXTextField
import com.smartcooking.app.ui.components.LoadingBlock
import com.smartcooking.app.ui.components.OutlineButton
import com.smartcooking.app.ui.components.PendingBanner
import com.smartcooking.app.ui.components.PrimaryButton
import com.smartcooking.app.ui.components.Stepper
import com.smartcooking.app.ui.components.TonalButton
import com.smartcooking.app.ui.components.Tone
import com.smartcooking.app.ui.nav.cookxViewModel
import com.smartcooking.app.ui.theme.CookX
import com.smartcooking.app.ui.theme.CookXShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class ConsumeRow(val item: InventoryItem, val selected: Boolean = false, val amount: Int = 1)
data class FamilyRow(val raw: JsonObject, val selected: Boolean = false, val used: String = "")

data class CompletionUi(
    val loading: Boolean = true,
    val rows: List<ConsumeRow> = emptyList(),
    val familyRows: List<FamilyRow> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val uncertain: Boolean = false,
    val locked: Boolean = false,
    val receipt: Boolean = false,
)

class CompletionViewModel(private val c: AppContainer, private val sessionId: String) : ViewModel() {
    private val user = c.sessions.currentUserId.orEmpty()
    private val engine = c.cooking(user)
    private val family = engine.state.value?.recipeModel?.familyScope
    val isFamily = family != null
    val ui = MutableStateFlow(CompletionUi())
    val familyCommand = family?.let { f -> c.pendingCommand("family-consume:$f") { load(); c.inventory.notifyChanged(user) } }

    init { load() }

    private fun readPending() {
        val tx = runCatching { c.consumption.pending(user) }.getOrElse { e -> ui.update { it.copy(uncertain = true, message = e.message) }; return }
        val status = tx?.str("status")
        val uncertain = tx != null && status !in listOf("confirmed", "rejected")
        val locked = uncertain || (status != "rejected" && tx?.str("sessionId") == sessionId) || engine.state.value?.consumption == "confirmed"
        ui.update { it.copy(uncertain = uncertain, locked = locked) }
    }

    fun load() {
        viewModelScope.launch {
            ui.update { it.copy(loading = true) }
            try {
                if (family == null) {
                    readPending()
                    val rows = c.inventory.read(user)
                    ui.update { it.copy(loading = false, rows = rows.map { r -> ConsumeRow(r) }) }
                } else {
                    val data = c.business.get("/households/$family")
                    val receipt = try { c.business.get("/households/$family/consumption/$sessionId").obj("receipt") } catch (e: ApiException) { if (e.status == 404) null else throw e }
                    if (receipt != null) engine.markConsumption("confirmed")
                    ui.update { it.copy(loading = false, familyRows = data.objects("inventory").map { r -> FamilyRow(r) }, receipt = receipt != null) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { ui.update { it.copy(loading = false, message = e.userMessage()) } }
        }
    }

    fun toggle(index: Int) = ui.update { s -> s.copy(rows = s.rows.mapIndexed { i, r -> if (i == index) r.copy(selected = !r.selected) else r }) }
    fun amount(index: Int, value: Int) = ui.update { s -> s.copy(rows = s.rows.mapIndexed { i, r -> if (i == index) r.copy(amount = value) else r }) }
    fun toggleFamily(index: Int) = ui.update { s -> s.copy(familyRows = s.familyRows.mapIndexed { i, r -> if (i == index) r.copy(selected = !r.selected) else r }) }
    fun usedFamily(index: Int, value: String) = ui.update { s -> s.copy(familyRows = s.familyRows.mapIndexed { i, r -> if (i == index) r.copy(used = value) else r }) }

    fun submit(onCompleted: () -> Unit) {
        val s = ui.value
        if (s.busy || s.locked) return
        viewModelScope.launch {
            ui.update { it.copy(busy = true, message = null) }
            try {
                c.consumption.submit(user, sessionId, s.rows.filter { it.selected }.map { ConsumeSelection(it.item, it.amount) })
                engine.markConsumption("confirmed")
                onCompleted()
                ui.update { it.copy(message = "库存扣减已回读确认。") }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                ui.update { it.copy(message = "${e.userMessage()}；库存尚未确认，请使用原凭证重试。") }
            } finally { c.inventory.notifyChanged(user); readPending(); ui.update { it.copy(busy = false) } }
        }
    }

    fun retry(onCompleted: () -> Unit) = viewModelScope.launch {
        ui.update { it.copy(busy = true) }
        try { c.consumption.retry(user); if (c.consumption.pending(user)?.str("sessionId") == sessionId) { engine.markConsumption("confirmed"); onCompleted() }; ui.update { it.copy(message = "原凭证已确认，库存已重新读取。") } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { ui.update { it.copy(message = e.userMessage()) } }
        finally { readPending(); ui.update { it.copy(busy = false) } }
    }

    fun reconcile(onCompleted: () -> Unit) = viewModelScope.launch {
        ui.update { it.copy(busy = true) }
        try {
            c.consumption.reconcile(user)
            if (c.consumption.pending(user)?.str("sessionId") == sessionId) { engine.markConsumption("confirmed"); onCompleted() }
            ui.update { it.copy(message = "服务端原凭证已确认，当前库存已重新读取；其他设备后续修改不会触发再次扣减。") }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { ui.update { it.copy(message = e.userMessage()) } }
        finally { readPending(); ui.update { it.copy(busy = false) } }
    }

    fun leftovers() {
        val session = engine.state.value ?: return
        c.events.leftoverName.value = session.recipeModel.dishName
        c.events.launchRoute.value = com.smartcooking.app.ui.nav.Routes.LEFTOVERS
    }

    fun submitFamily(onCompleted: () -> Unit) {
        val chosen = ui.value.familyRows.filter { it.selected }
        val invalid = chosen.isEmpty() || chosen.any { r -> val v = r.used.toFiniteOrNull(); v == null || v <= 0 || v > (r.raw.num("quantity") ?: 0.0) }
        if (invalid) { ui.update { it.copy(message = "请核对实际使用量") }; return }
        viewModelScope.launch {
            val saved = familyCommand?.send("/households/$family/consumption", "post", jsonOf("session_id" to sessionId,
                "items" to chosen.map { mapOf("item_id" to it.raw.str("id"), "expected_version" to it.raw.num("version"), "quantity" to it.used.toDouble()) }))
            if (saved == true) { engine.markConsumption("confirmed"); onCompleted() }
        }
    }
    fun retryFamily(onCompleted: () -> Unit) = viewModelScope.launch {
        val command = familyCommand ?: return@launch
        val originalSession = command.pending.value?.body?.str("session_id")
        if (command.retry() && originalSession == sessionId) { engine.markConsumption("confirmed"); onCompleted() }
    }
}

@Composable
fun CompletionSheet(sessionId: String, completed: Boolean, onComplete: () -> Unit, onClose: () -> Unit, onContinue: () -> Unit) {
    val vm = cookxViewModel(key = sessionId) { CompletionViewModel(it, sessionId) }
    val s by vm.ui.collectAsStateWithLifecycle()
    CookXSheet(
        if (vm.isFamily) "完成烹饪 · 家庭库存实际使用" else "完成烹饪 · 实际使用清单", onClose,
        subtitle = if (vm.isFamily) "逐批次填写实际使用量，沿用库存单位，不自动转换为克或份。" else "勾选实际使用的库存批次并填写库存计数；不代表克数或份量换算。",
    ) {
        s.message?.let { Banner(it, BannerKind.Info, Modifier.padding(bottom = 12.dp)) }
        if (s.loading) LoadingBlock("正在读取库存…", minHeight = 80)
        else if (vm.isFamily) FamilyBody(vm, s, onComplete)
        else PersonalBody(vm, s, onComplete)
        Spacer(Modifier.height(10.dp))
        OutlineButton(if (completed) "关闭核对" else "仅记录完成，不扣库存", { if (!completed) onComplete(); onClose() }, Modifier.fillMaxWidth(), enabled = !s.busy, color = CookX.TextSecondary)
        if (completed) TonalButton("有剩余熟食，添加记录", { onClose(); vm.leftovers() }, Modifier.fillMaxWidth().padding(top = 8.dp))
        if (!completed) OutlineButton("继续烹饪", onContinue, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !s.busy)
    }
}

@Composable
private fun PersonalBody(vm: CompletionViewModel, s: CompletionUi, onComplete: () -> Unit) {
    if (s.uncertain) Banner("上次扣减结果未确认。请先核对，不会重复扣减。", BannerKind.Pending, title = "扣减待确认") {
        TonalButton("核对扣减结果（不重发）", { vm.reconcile(onComplete) }, loading = s.busy, tone = Tone.Warm)
        OutlineButton("使用原凭证重试", { vm.retry(onComplete) }, enabled = !s.busy)
    }
    if (s.rows.isEmpty()) Text("库存为空，可以仅记录完成。", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
    s.rows.forEachIndexed { i, r ->
        val ok = consumable(r.item)
        Row(
            Modifier.padding(top = 8.dp).fillMaxWidth().clip(CookXShapes.Tile).background(if (r.selected) CookX.Mint else CookX.Surface)
                .border(1.dp, if (r.selected) CookX.Primary.copy(alpha = 0.4f) else CookX.Border, CookXShapes.Tile).padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(r.selected, { vm.toggle(i) }, enabled = ok && !s.busy && !s.locked, colors = CheckboxDefaults.colors(checkedColor = CookX.Primary))
            Column(Modifier.weight(1f)) {
                Text("${r.item.info.cn}：${r.item.quantity.clean()} → ${(r.item.quantity - r.amount).coerceAtLeast(0.0).clean()}", style = MaterialTheme.typography.titleSmall)
                Text("批次 ${r.item.id.takeLast(6)} · ${r.item.storageType ?: "储存未知"} · 到期 ${Time.displayShort(r.item.expiryDate)}", fontSize = 11.sp, color = CookX.TextSecondary)
                if (!ok) Text("数量格式不支持自动扣减", fontSize = 11.sp, color = CookX.Danger)
            }
            if (r.selected) Stepper(r.amount, { vm.amount(i, it) }, 1, r.item.quantity.toInt().coerceAtLeast(1), enabled = !s.busy && !s.locked)
        }
    }
    Spacer(Modifier.height(14.dp))
    if (!s.locked) PrimaryButton("确认完成并扣减所选", { vm.submit(onComplete) }, Modifier.fillMaxWidth(), loading = s.busy, enabled = s.rows.any { it.selected })
}

@Composable
private fun FamilyBody(vm: CompletionViewModel, s: CompletionUi, onComplete: () -> Unit) {
    val cmd = vm.familyCommand ?: return
    val pending by cmd.pending.collectAsStateWithLifecycle()
    val busy by cmd.busy.collectAsStateWithLifecycle()
    val error by cmd.error.collectAsStateWithLifecycle()
    if (error.isNotBlank()) Banner(error, BannerKind.Error, Modifier.padding(bottom = 8.dp))
    if (s.receipt) { Banner("家庭库存扣减已回读确认，本会话不会重复扣减。", BannerKind.Success); return }
    if (pending != null) PendingBanner(busy, { vm.retryFamily(onComplete) })
    s.familyRows.forEachIndexed { i, r ->
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(CookXShapes.Tile).background(CookX.Surface).border(1.dp, CookX.Border, CookXShapes.Tile).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(r.selected, { vm.toggleFamily(i) }, enabled = !busy && pending == null, colors = CheckboxDefaults.colors(checkedColor = CookX.Primary))
            Column(Modifier.weight(1f)) {
                Text("${r.raw.str("name")} · ${r.raw.num("quantity")?.clean(3)} ${r.raw.str("unit").orEmpty()}", style = MaterialTheme.typography.titleSmall)
                Text(r.raw.obj("freshness")?.str("freshness_label") ?: "鲜度未知", fontSize = 11.sp, color = CookX.TextSecondary)
            }
            if (r.selected) { Spacer(Modifier.width(6.dp)); CookXTextField(r.used, { vm.usedFamily(i, it) }, "实际用量", Modifier.width(110.dp), keyboardType = KeyboardType.Decimal) }
        }
    }
    Spacer(Modifier.height(14.dp))
    PrimaryButton("确认完成并扣减家庭批次", { vm.submitFamily(onComplete) }, Modifier.fillMaxWidth(), loading = busy, enabled = pending == null && s.familyRows.any { it.selected })
}
