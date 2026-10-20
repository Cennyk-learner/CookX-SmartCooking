package com.smartcooking.app.feature.business

import com.smartcooking.app.core.toFiniteOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoveDown
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartcooking.app.core.EmptyObject
import com.smartcooking.app.core.Time
import com.smartcooking.app.core.bool
import com.smartcooking.app.core.clean
import com.smartcooking.app.core.jsonOf
import com.smartcooking.app.core.long
import com.smartcooking.app.core.num
import com.smartcooking.app.core.obj
import com.smartcooking.app.core.objects
import com.smartcooking.app.core.str
import com.smartcooking.app.data.Freshness
import com.smartcooking.app.feature.fridge.FreshnessBadge
import com.smartcooking.app.ui.components.Banner
import com.smartcooking.app.ui.components.BannerKind
import com.smartcooking.app.ui.components.Choice
import com.smartcooking.app.ui.components.CookXSheet
import com.smartcooking.app.ui.components.CookXTextField
import com.smartcooking.app.ui.components.DateTimeField
import com.smartcooking.app.ui.components.DropdownField
import com.smartcooking.app.ui.components.EmptyState
import com.smartcooking.app.ui.components.LinkButton
import com.smartcooking.app.ui.components.LocalMessenger
import com.smartcooking.app.ui.components.OutlineButton
import com.smartcooking.app.ui.components.PrimaryButton
import com.smartcooking.app.ui.components.StatusChip
import com.smartcooking.app.ui.components.SubpageScaffold
import com.smartcooking.app.ui.components.TonalButton
import com.smartcooking.app.ui.components.Tone
import com.smartcooking.app.ui.components.overlapHero
import com.smartcooking.app.ui.nav.Navigator
import com.smartcooking.app.ui.nav.cookxViewModel
import com.smartcooking.app.ui.theme.CookX
import com.smartcooking.app.ui.theme.CookXShapes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

val familyUnits = listOf("库存计数", "克", "千克", "毫升", "升", "个", "根", "份")

data class StockForm(
    val editing: JsonObject? = null,
    val name: String = "",
    val quantity: String = "1",
    val unit: String = "库存计数",
    val storage: String? = null,
    val shelfLife: String = "",
    val purchase: String = "",
    val expiry: String = "",
)

class HouseholdViewModel(c: com.smartcooking.app.core.AppContainer) : BusinessViewModel(c, "family") {
    val families = MutableStateFlow<List<JsonObject>>(emptyList())
    val selected = MutableStateFlow("")
    val detail = MutableStateFlow<JsonObject?>(null)
    val personal = MutableStateFlow<JsonObject?>(null)
    val inviteCode = MutableStateFlow<String?>(null)
    val stockForm = MutableStateFlow<StockForm?>(null)
    val loaded = MutableStateFlow(false)

    init { refresh() }

    fun refresh() = launchLoad {
        families.value = c.business.get("/households").objects("items")
        if (selected.value.isNotEmpty() && families.value.none { it.str("id") == selected.value }) selected.value = ""
        if (selected.value.isEmpty()) selected.value = families.value.firstOrNull()?.str("id").orEmpty()
        loaded.value = true
        loadDetail()
    }

    fun select(id: String) { selected.value = id; inviteCode.value = null; launchLoad { loadDetail() } }

    private suspend fun loadDetail() {
        val family = selected.value
        detail.value = null
        if (family.isEmpty()) return
        val result = c.business.get("/households/$family")
        if (selected.value == family) detail.value = result
    }

    fun loadPersonal() = launchLoad { personal.value = c.business.get("/personal-inventory") }

    override suspend fun onCommandSuccess(result: JsonObject) {
        result.obj("household")?.str("id")?.let { selected.value = it }
        stockForm.value = null
        families.value = c.business.get("/households").objects("items")
        loadDetail()
        result.str("code")?.let { inviteCode.value = it }
        if (personal.value != null) personal.value = c.business.get("/personal-inventory")
    }

    fun saveStock() {
        val f = stockForm.value ?: return
        try {
            val body = mutableMapOf<String, Any?>(
                "name" to f.name.trim(), "quantity" to (f.quantity.toFiniteOrNull() ?: throw IllegalArgumentException("请填写实际数量")),
                "unit" to f.unit, "storage_type" to f.storage, "shelf_life" to f.shelfLife.toFiniteOrNull(),
                "purchase_time" to keepDate(f.purchase, f.editing?.str("purchase_time")), "expiry_date" to keepDate(f.expiry, f.editing?.str("expiry_date")),
            )
            f.editing?.let { body["expected_version"] = it.num("version"); body["add_time"] = it.str("add_time") }
            val path = "/households/${selected.value}/inventory" + (f.editing?.let { "/${it.str("id")}" } ?: "")
            send(path, if (f.editing != null) "put" else "post", jsonOf(*body.map { it.key to it.value }.toTypedArray()))
        } catch (e: IllegalArgumentException) { command.setError(e.message ?: "输入无效") }
    }

    /** An unchanged date keeps the server's exact original value instead of a minute-rounded copy. */
    private fun keepDate(local: String, original: String?): String? =
        if (original != null && local == Time.localInput(original)) original else isoOrNull(local)
}

@Composable
fun HouseholdScreen(navigator: Navigator) {
    val vm = cookxViewModel { HouseholdViewModel(it) }
    val families by vm.families.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val personal by vm.personal.collectAsStateWithLifecycle()
    val invite by vm.inviteCode.collectAsStateWithLifecycle()
    val form by vm.stockForm.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val busy = rememberBusy(vm)
    val confirm = rememberConfirmer()
    val clipboard = LocalClipboardManager.current
    val messenger = LocalMessenger.current
    var showJoin by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    SubpageScaffold("家庭共享冰箱", navigator::back, subtitle = "个人冰箱保持私有。只有确认转入的批次，家庭成员才能查看和维护。") {
        item {
            Column(Modifier.overlapHero(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CommandStatus(vm, Modifier.padding(horizontal = 16.dp), onDiscard = { confirm.ask("核对操作", "确认已核对家庭和个人库存？清除本地记录不会撤销已保存操作。") { vm.command.discard() } })
                Section("我的家庭", icon = Icons.Outlined.Groups, trailing = { LinkButton("刷新", { vm.refresh() }, icon = Icons.Outlined.Refresh) }) {
                    if (families.isNotEmpty()) DropdownField("选择家庭", selected, families.map { Choice(it.str("id").orEmpty(), it.str("name").orEmpty()) }, vm::select)
                    else if (loaded) Text("你还没有加入家庭，可以创建一个或使用邀请码加入。", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
                    if (families.isNotEmpty()) LinkButton(if (showJoin) "收起" else "创建或加入其他家庭", { showJoin = !showJoin }, color = CookX.Primary)
                    if (families.isEmpty() || showJoin) {
                        Spacer(Modifier.height(8.dp))
                        CookXTextField(name, { name = it }, "新家庭名称", maxLength = 80)
                        PrimaryButton("创建家庭", { vm.send("/households", "post", jsonOf("name" to name.trim())) }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy && name.isNotBlank(), icon = Icons.Outlined.Add)
                        Spacer(Modifier.height(14.dp))
                        CookXTextField(code, { code = it.trim() }, "家庭邀请码")
                        OutlineButton("确认加入", { vm.send("/households/join", "post", jsonOf("code" to code)) }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy && code.isNotBlank())
                    }
                }
            }
        }
        detail?.let { d -> item { FamilyBody(vm, d, invite, personal, busy, confirm) { inviteCode -> clipboard.setText(AnnotatedString(inviteCode)); messenger.show("邀请码已复制") } } }
    }
    form?.let { f -> StockSheet(f, busy, { vm.stockForm.value = it }, vm::saveStock) { vm.stockForm.value = null } }
    confirm.Host()
}

@Composable
private fun FamilyBody(vm: HouseholdViewModel, d: JsonObject, invite: String?, personal: JsonObject?, busy: Boolean, confirm: Confirmer, onCopy: (String) -> Unit) {
    val family by vm.selected.collectAsStateWithLifecycle()
    val membership = d.obj("membership") ?: EmptyObject
    val admin = membership.str("role") == "admin"
    val members = d.objects("members")
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(d.obj("household")?.str("name").orEmpty(), subtitle = "成员 ${members.size} 人 · ${if (admin) "你是管理员" else "你是成员"}", icon = Icons.Outlined.Groups) {
            if (admin) {
                TonalButton("生成一次性邀请", { vm.send("/households/$family/invite", "post") }, enabled = !busy, icon = Icons.Outlined.PersonAdd)
                invite?.let { code ->
                    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(CookXShapes.Tile).background(CookX.Mint).padding(14.dp)) {
                        Text("请私下分享邀请码", fontSize = 12.sp, color = CookX.TextSecondary)
                        Text(code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CookX.Primary)
                        LinkButton("复制邀请码", { onCopy(code) }, icon = Icons.Outlined.ContentCopy, color = CookX.Primary)
                    }
                }
                d.objects("invitations").filter { it.bool("active") }.forEach { inv ->
                    ItemBlock("有效邀请", Modifier.padding(top = 10.dp), lines = listOf("有效至 ${Time.epochSecondsDisplay(inv.long("expires_at") ?: 0)}"),
                        actions = { OutlineButton("撤销邀请", { vm.send("/households/$family/invitations/${inv.str("id")}", "delete", jsonOf("expected_version" to inv.num("version"))) }, enabled = !busy, color = CookX.Danger) })
                }
                Spacer(Modifier.height(10.dp))
            }
            members.forEach { m ->
                val me = m.str("user_id") == vm.user
                ItemBlock(if (me) "我" else m.str("display_name").orEmpty(),
                    badge = { StatusChip(if (m.str("role") == "admin") "管理员" else "成员", if (m.str("role") == "admin") Tone.Warm else Tone.Green) },
                    actions = if (m.str("role") != "admin" && (admin || me)) { {
                        OutlineButton(if (me) "退出家庭" else "移除成员", {
                            confirm.ask("确认操作", "移除后将立即失去此家庭的访问权限，共享库存仍由家庭保留。") {
                                vm.send("/households/$family/members/${m.str("user_id")}", "delete", jsonOf("expected_version" to m.num("version")))
                            }
                        }, enabled = !busy, color = CookX.Danger)
                        if (admin) OutlineButton("移交管理员", {
                            confirm.ask("确认操作", "确认将管理员权限移交给这位成员？") {
                                vm.send("/households/$family/administrator", "post", jsonOf("member_id" to m.str("user_id"), "expected_member_version" to m.num("version"), "expected_version" to membership.num("version")))
                            }
                        }, enabled = !busy)
                    } } else null)
            }
        }
        Section("共享库存", icon = Icons.Outlined.Inventory2, trailing = { TonalButton("新增", { vm.stockForm.value = StockForm() }, icon = Icons.Outlined.Add, enabled = !busy) }) {
            val stock = d.objects("inventory")
            if (stock.isEmpty()) EmptyState(Icons.Outlined.Inventory2, "还没有共享库存", "新增批次，或从我的冰箱整批转入。")
            stock.forEach { item ->
                ItemBlock("${item.str("name")} · ${item.num("quantity")?.clean(3)} ${item.str("unit").orEmpty()}",
                    lines = listOf(item.str("storage_type") ?: "储存方式未知", item.str("expiry_date")?.let { "到期 ${Time.display(it)}" }.orEmpty()),
                    badge = { FreshnessBadge(item.obj("freshness")?.let(::Freshness), com.smartcooking.app.data.FreshnessState.Idle) },
                    actions = {
                        TonalButton("编辑批次", {
                            vm.stockForm.value = StockForm(item, item.str("name").orEmpty(), item.num("quantity")?.clean(3).orEmpty(), item.str("unit") ?: "库存计数",
                                item.str("storage_type"), item.num("shelf_life")?.clean(3).orEmpty(), Time.localInput(item.str("purchase_time")), Time.localInput(item.str("expiry_date")))
                        }, enabled = !busy)
                        OutlineButton("删除批次", {
                            confirm.ask("删除共享批次", "删除共享批次「${item.str("name")}」？") { vm.send("/households/$family/inventory/${item.str("id")}", "delete", jsonOf("expected_version" to item.num("version"))) }
                        }, enabled = !busy, color = CookX.Danger)
                    })
            }
        }
        Section("从我的冰箱转入", subtitle = "整批转入后会从个人冰箱移除；日期与原有库存计数保留，不会自动合并不同批次。", icon = Icons.Outlined.MoveDown) {
            OutlineButton(if (personal == null) "读取我的库存" else "刷新我的库存", { vm.loadPersonal() })
            personal?.let { p ->
                Spacer(Modifier.height(10.dp))
                val rows = p.objects("items")
                if (rows.isEmpty()) Text("个人冰箱暂无可转入批次。", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
                rows.forEach { item ->
                    ItemBlock("${item.str("name")} · ${item.num("quantity")?.clean()} 库存计数", actions = {
                        TonalButton("整批转入家庭", {
                            confirm.ask("确认转入", "将「${item.str("name")}」整批转入家庭？个人冰箱将移除该批次。") {
                                vm.send("/households/$family/transfer", "post", jsonOf("item_id" to item.str("id"), "expected_inventory_version" to p.num("version"), "confirmed" to true))
                            }
                        }, enabled = !busy, tone = Tone.Warm)
                    })
                }
            }
        }
    }
}

@Composable
private fun StockSheet(f: StockForm, busy: Boolean, onChange: (StockForm) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    CookXSheet(if (f.editing != null) "编辑共享批次" else "新增共享库存", onDismiss, dismissible = !busy) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CookXTextField(f.name, { onChange(f.copy(name = it)) }, "食材名称", maxLength = 80)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CookXTextField(f.quantity, { onChange(f.copy(quantity = it)) }, "实际数量", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                DropdownField("计量单位", f.unit, familyUnits.map { Choice(it, it) }, { onChange(f.copy(unit = it)) }, Modifier.weight(1f))
            }
            Banner("“库存计数”沿用旧版本的份数，不等同于克数。无法确定换算时请保留原单位。", BannerKind.Info)
            DropdownField("储存方式", f.storage, storageChoices, { onChange(f.copy(storage = it)) })
            CookXTextField(f.shelfLife, { onChange(f.copy(shelfLife = it)) }, "保质期（天，可小数或未知）", keyboardType = KeyboardType.Decimal)
            DateTimeField("购买时间（可未知）", f.purchase, { onChange(f.copy(purchase = it)) })
            DateTimeField("到期时间（可未知）", f.expiry, { onChange(f.copy(expiry = it)) })
            PrimaryButton(if (f.editing != null) "确认保存修改" else "确认入库", onSave, Modifier.fillMaxWidth(), loading = busy, enabled = f.name.isNotBlank())
        }
    }
}
