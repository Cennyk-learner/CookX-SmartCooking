package com.smartcooking.app.feature.business

import com.smartcooking.app.core.toFiniteOrNull
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Recycling
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartcooking.app.core.AppContainer
import com.smartcooking.app.core.Time
import com.smartcooking.app.core.bool
import com.smartcooking.app.core.clean
import com.smartcooking.app.core.jsonOf
import com.smartcooking.app.core.num
import com.smartcooking.app.core.obj
import com.smartcooking.app.core.objects
import com.smartcooking.app.core.str
import com.smartcooking.app.core.strings
import com.smartcooking.app.ui.components.Banner
import com.smartcooking.app.ui.components.BannerKind
import com.smartcooking.app.ui.components.CheckRow
import com.smartcooking.app.ui.components.Choice
import com.smartcooking.app.ui.components.CookXSheet
import com.smartcooking.app.ui.components.CookXTextField
import com.smartcooking.app.ui.components.DateTimeField
import com.smartcooking.app.ui.components.DropdownField
import com.smartcooking.app.ui.components.EmptyState
import com.smartcooking.app.ui.components.LinkButton
import com.smartcooking.app.ui.components.LoadingBlock
import com.smartcooking.app.ui.components.OutlineButton
import com.smartcooking.app.ui.components.PrimaryButton
import com.smartcooking.app.ui.components.PromptDialog
import com.smartcooking.app.ui.components.SegmentedTabs
import com.smartcooking.app.ui.components.StatusChip
import com.smartcooking.app.ui.components.SubpageScaffold
import com.smartcooking.app.ui.components.TonalButton
import com.smartcooking.app.ui.components.Tone
import com.smartcooking.app.ui.components.overlapHero
import com.smartcooking.app.ui.nav.Navigator
import com.smartcooking.app.ui.nav.Routes
import com.smartcooking.app.ui.nav.cookxViewModel
import com.smartcooking.app.ui.theme.CookX
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

private val eventNames = mapOf("reheated" to "已再加热", "restored" to "已核对低温储存", "used" to "实际使用", "abnormal" to "异常", "discarded" to "已结束")
private val cookedTypes = listOf("other" to "其他 / 不确定", "rice" to "熟米饭", "noodles" to "熟面条", "vegetables" to "熟蔬菜", "meat" to "熟肉")

data class LeftoverForm(
    val kind: String = "cooked", val inventoryId: String = "", val name: String = "", val cookedType: String = "other",
    val quantity: String = "", val unit: String = "", val madeAt: String = "", val storedAt: String = "", val expiryAt: String = "",
    val storage: String? = null, val coldChain: Boolean = false, val abnormal: Boolean = false,
)

class LeftoversViewModel(c: AppContainer) : BusinessViewModel(c, "leftovers") {
    val items = MutableStateFlow<List<JsonObject>?>(null)
    val inventory = MutableStateFlow<List<JsonObject>>(emptyList())
    val ideas = MutableStateFlow<JsonObject?>(null)
    val form = MutableStateFlow<LeftoverForm?>(null)
    val navigateCopy = MutableStateFlow<String?>(null)

    init { load() }

    fun load() = launchLoad {
        coroutineScope {
            val a = async { c.business.get("/leftovers") }
            val b = async { c.business.get("/personal-inventory") }
            items.value = a.await().objects("items"); inventory.value = b.await().objects("items")
        }
    }

    fun create() {
        val f = form.value ?: return
        try {
            send("/leftovers", "post", if (f.kind == "raw") jsonOf("kind" to "raw", "inventory_id" to f.inventoryId) else jsonOf(
                "kind" to "cooked", "cooked_type" to f.cookedType, "name" to f.name.trim(), "quantity" to f.quantity.toFiniteOrNull(), "unit" to f.unit.ifBlank { null },
                "made_at" to isoOrNull(f.madeAt), "stored_at" to isoOrNull(f.storedAt), "expiry_at" to isoOrNull(f.expiryAt), "storage_type" to f.storage,
                "cold_chain_confirmed" to f.coldChain, "abnormal" to f.abnormal,
            ))
        } catch (e: IllegalArgumentException) { command.setError(e.message ?: "日期无效") }
    }

    fun event(r: JsonObject, type: String, quantity: Double? = null) = send("/leftovers/${r.str("id")}/events", "post",
        jsonOf("expected_version" to r.num("version"), "type" to type, "cold_chain_confirmed" to (type == "restored"), "quantity" to quantity))

    fun showIdeas(r: JsonObject) = launchLoad { ideas.value = null; ideas.value = c.business.get("/leftovers/${r.str("id")}/ideas") }
    fun clone(idea: JsonObject) = send("/recipes/copies", "post", jsonOf("source_type" to "standard", "source_id" to idea.str("id"), "expected_source_version" to idea.str("source_version")))

    override suspend fun onCommandSuccess(result: JsonObject) {
        ideas.value = null; form.value = null
        load()
        result.obj("copy")?.str("id")?.let { navigateCopy.value = it }
    }
}

@Composable
fun LeftoversScreen(navigator: Navigator) {
    val vm = cookxViewModel { LeftoversViewModel(it) }
    val events = com.smartcooking.app.core.LocalAppContainer.current.events
    val pendingName by events.leftoverName.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pendingName) {
        pendingName?.let { vm.form.value = LeftoverForm(name = it); events.leftoverName.value = null }
    }
    val items by vm.items.collectAsStateWithLifecycle()
    val ideas by vm.ideas.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val inventory by vm.inventory.collectAsStateWithLifecycle()
    val navigateCopy by vm.navigateCopy.collectAsStateWithLifecycle()
    val busy = rememberBusy(vm)
    val confirm = rememberConfirmer()
    val context = LocalContext.current
    var using by remember { mutableStateOf<JsonObject?>(null) }
    navigateCopy?.let { vm.navigateCopy.value = null; navigator.open(Routes.recipes(copy = it)) }

    SubpageScaffold("剩菜改造", navigator::back, subtitle = "剩余原料沿用原库存的日期与鲜度；熟食单独记录。信息不明、异常或过期时不提供再利用建议，记录通过筛选也不等于可食用性鉴定。") {
        item {
            Column(Modifier.overlapHero(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CommandStatus(vm, Modifier.padding(horizontal = 16.dp))
                Section("剩余食材记录", icon = Icons.Outlined.Recycling, trailing = { TonalButton("添加记录", { vm.form.value = LeftoverForm() }, icon = Icons.Outlined.Add, enabled = !busy) }) {
                    val list = items
                    when {
                        list == null -> LoadingBlock("正在读取记录…", minHeight = 80)
                        list.isEmpty() -> EmptyState(Icons.Outlined.Recycling, "暂无剩余食材记录", "记录剩余原料或熟食，符合条件时可查看再利用思路。")
                    }
                }
            }
        }
        items(items.orEmpty(), key = { it.str("id").orEmpty() }) { r ->
            val assessment = r.obj("assessment")
            val eligible = assessment?.bool("eligible") == true
            val cooked = r.str("kind") == "cooked"
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                ItemBlock(
                    "${r.str("name")} · ${if (cooked) "熟食" else "剩余原料"}",
                    lines = if (cooked) listOf(
                        "${r.num("quantity")?.clean(3) ?: "数量未知"} ${r.str("unit").orEmpty()} · ${r.str("storage_type") ?: "储存未知"}",
                        "制作 ${Time.display(r.str("made_at"), "未提供")} · 首次储存 ${Time.display(r.str("stored_at"), "未提供")}",
                        "到期 ${Time.display(r.str("expiry_at"), "未提供")}",
                    ) else listOf("保留原库存批次，使用量在库存核对。"),
                    badge = { StatusChip(if (r.bool("closed")) "已结束" else if (eligible) "符合筛选" else "需核查", if (r.bool("closed")) Tone.Neutral else if (eligible) Tone.Fresh else Tone.Gold) },
                    actions = if (r.bool("closed")) null else { {
                        TonalButton("再利用思路", { vm.showIdeas(r) }, enabled = eligible && !busy, icon = Icons.Outlined.Lightbulb, tone = Tone.Warm)
                        if (cooked) OutlineButton("记录已再加热", { confirm.ask("核对处理记录", "确认已再加热？原制作与储存时间将保留。") { vm.event(r, "reheated") } }, enabled = !busy)
                        if (cooked && !r.bool("cold_chain_confirmed") && r.objects("events").any { it.str("type") == "reheated" })
                            OutlineButton("核对恢复低温", { confirm.ask("核对处理记录", "确认再加热后一小时内已恢复冷藏或冷冻，且持续低温储存？原制作和到期时间不会重置。") { vm.event(r, "restored") } }, enabled = !busy)
                        if (cooked) OutlineButton("记录使用量", { using = r }, enabled = !busy)
                        OutlineButton("标记异常", { confirm.ask("核对处理记录", "确认标记异常？原制作与储存时间将保留。") { vm.event(r, "abnormal") } }, enabled = !busy, color = CookX.Accent)
                        OutlineButton("结束记录", { confirm.ask("核对处理记录", "确认结束此记录？") { vm.event(r, "discarded") } }, enabled = !busy, color = CookX.Danger)
                    } },
                ) {
                    Text(if (eligible) "记录符合本版建议筛选条件，请继续核查实物" else assessment?.strings("reasons")?.joinToString("；").orEmpty(),
                        fontSize = 12.sp, color = if (eligible) CookX.Success else CookX.TextBody, modifier = Modifier.padding(top = 6.dp))
                    assessment?.str("disclaimer")?.let { Text(it, fontSize = 10.5.sp, color = CookX.TextTertiary, modifier = Modifier.padding(top = 4.dp)) }
                    r.objects("events").forEach { e -> Text("${eventNames[e.str("type")] ?: e.str("type")} · ${Time.display(e.str("at"))} ${e.num("quantity")?.clean(3).orEmpty()}", fontSize = 11.sp, color = CookX.TextSecondary) }
                }
            }
        }
    }
    form?.let { f ->
        CookXSheet("添加剩余食材记录", { vm.form.value = null }, dismissible = !busy, subtitle = "未知信息可以留空，保存后会显示需补充核查，不进入建议。") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentedTabs(listOf(Choice("cooked", "熟食剩菜"), Choice("raw", "剩余原料")), f.kind, { vm.form.value = f.copy(kind = it) })
                if (f.kind == "raw") DropdownField("原库存批次", f.inventoryId, listOf(Choice("", "请选择")) + inventory.map {
                    Choice(it.str("id").orEmpty(), "${it.str("name")} · ${it.num("quantity")?.clean()} 库存计数 · ${Time.monthDay(it.str("add_time"))}")
                }, { vm.form.value = f.copy(inventoryId = it) })
                else {
                    CookXTextField(f.name, { vm.form.value = f.copy(name = it) }, "熟食名称", maxLength = 80)
                    DropdownField("熟食类别", f.cookedType, cookedTypes.map { Choice(it.first, it.second) }, { vm.form.value = f.copy(cookedType = it) })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CookXTextField(f.quantity, { vm.form.value = f.copy(quantity = it) }, "实际数量", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                        CookXTextField(f.unit, { vm.form.value = f.copy(unit = it) }, "单位", Modifier.weight(1f), placeholder = "份、克", maxLength = 16)
                    }
                    DateTimeField("制作时间", f.madeAt, { vm.form.value = f.copy(madeAt = it) })
                    DateTimeField("首次低温储存时间", f.storedAt, { vm.form.value = f.copy(storedAt = it) })
                    DateTimeField("记录的到期时间", f.expiryAt, { vm.form.value = f.copy(expiryAt = it) })
                    DropdownField("储存方式", f.storage, storageChoices.map { if (it.value == null) it.copy(label = "不清楚") else it }, { vm.form.value = f.copy(storage = it) })
                    CheckRow("我已核对连续低温储存情况", f.coldChain, { vm.form.value = f.copy(coldChain = it) })
                    CheckRow("存在异常（气味、外观等）", f.abnormal, { vm.form.value = f.copy(abnormal = it) })
                }
                PrimaryButton("保存剩余食材记录", vm::create, Modifier.fillMaxWidth(), loading = busy,
                    enabled = if (f.kind == "raw") f.inventoryId.isNotBlank() else f.name.isNotBlank())
            }
        }
    }
    ideas?.let { data ->
        val a = data.obj("assessment")
        CookXSheet("再利用思路", { vm.ideas.value = null }, subtitle = a?.strings("reasons")?.joinToString("；")) {
            data.objects("ideas").forEach { idea ->
                ItemBlock(idea.str("name").orEmpty(), lines = idea.strings("steps").mapIndexed { i, s -> "${i + 1}. $s" },
                    actions = if (idea.str("type") == "standard") { { TonalButton("创建菜谱副本并核对", { vm.clone(idea) }, enabled = !busy) } } else null)
            }
            a?.str("disclaimer")?.let { Banner(it, BannerKind.Warning, Modifier.padding(top = 8.dp)) }
            a?.str("guidance_url")?.let { url -> LinkButton("查看 USDA 储存与再加热依据", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, color = CookX.Primary) }
            Spacer(Modifier.height(8.dp))
        }
    }
    using?.let { r ->
        PromptDialog("核对使用量", "实际使用数量（${r.str("unit").orEmpty()}），最多 ${r.num("quantity")?.clean(3)}", { v -> using = null; vm.event(r, "used", v.toDouble()) }, { using = null }, numeric = true,
            validate = { v -> val n = v.toFiniteOrNull(); if (n == null || n <= 0 || n > (r.num("quantity") ?: 0.0)) "请输入有效数量" else null })
    }
    confirm.Host()
}
