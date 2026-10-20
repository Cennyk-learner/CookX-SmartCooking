package com.smartcooking.app.feature.business

import com.smartcooking.app.core.toFiniteOrNull
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PlaylistAddCheck
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartcooking.app.core.AppContainer
import com.smartcooking.app.core.Time
import com.smartcooking.app.core.asArray
import com.smartcooking.app.core.asText
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
import com.smartcooking.app.ui.components.CookXTextField
import com.smartcooking.app.ui.components.DateTimeField
import com.smartcooking.app.ui.components.DropdownField
import com.smartcooking.app.ui.components.LinkButton
import com.smartcooking.app.ui.components.OutlineButton
import com.smartcooking.app.ui.components.PrimaryButton
import com.smartcooking.app.ui.components.StatusChip
import com.smartcooking.app.ui.components.Stepper
import com.smartcooking.app.ui.components.SubpageScaffold
import com.smartcooking.app.ui.components.SwitchRow
import com.smartcooking.app.ui.components.TonalButton
import com.smartcooking.app.ui.components.Tone
import com.smartcooking.app.ui.components.overlapHero
import com.smartcooking.app.ui.nav.Navigator
import com.smartcooking.app.ui.nav.Routes
import com.smartcooking.app.ui.nav.cookxViewModel
import com.smartcooking.app.ui.theme.CookX
import com.smartcooking.app.ui.theme.CookXShapes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

private val meals = listOf("breakfast" to "早餐", "lunch" to "午餐", "dinner" to "晚餐")
private val roleNames = mapOf("main" to "主菜", "staple" to "主食", "side" to "配菜")
private val nutrients = listOf("kcal" to "能量（kcal）", "protein_g" to "蛋白质（克）", "fat_g" to "脂肪（克）", "carb_g" to "碳水（克）")
private val solverLabels = mapOf(
    "OPTIMAL" to "已找到最优解", "FEASIBLE" to "已找到可行解（尚未证明全部目标最优）", "INFEASIBLE" to "当前约束无解",
    "NEEDS_ADJUSTMENT" to "请调整条件或补齐数据", "UNKNOWN" to "暂未找到可行组合",
    "TIMEOUT" to "求解超时，尚无可行方案", "NEEDS_DATA" to "缺少验证约束所需的数据", "MODEL_INVALID" to "求解模型异常，请联系维护者",
)
private fun mealName(id: String?) = meals.firstOrNull { it.first == id }?.second ?: id.orEmpty()

data class PlanForm(
    val startDate: String = Time.localDateText(1),
    val familyId: String = "",
    val people: Int = 1,
    val portion: String = "1",
    val budget: String = "",
    val maxMinutes: String = "120",
    val difficulty: Int = 2,
    val maxRepeat: String = "3",
    val inventoryPriority: Boolean = true,
    val slots: Set<String> = (0..6).flatMap { listOf("$it:lunch", "$it:dinner") }.toSet(),
    val side: Boolean = false,
    val avoid: String = "",
    val diet: String = "",
    val bounds: Map<String, Pair<String, String>> = nutrients.associate { it.first to ("" to "") },
    val locks: Map<String, String> = emptyMap(),
    val confirmed: Boolean = false,
) {
    val roles get() = if (side) listOf("main", "staple", "side") else listOf("main", "staple")
    val sortedSlots get() = slots.map { it.split(":") }.sortedWith(compareBy({ it[0].toInt() }, { s -> meals.indexOfFirst { it.first == s[1] } })).map { it[0].toInt() to it[1] }
}

class MenuPlannerViewModel(c: AppContainer) : BusinessViewModel(c, "planning") {
    val form = MutableStateFlow(PlanForm())
    val recipes = MutableStateFlow<List<JsonObject>>(emptyList())
    val families = MutableStateFlow<List<JsonObject>>(emptyList())
    val prices = MutableStateFlow<List<Triple<String, String, String>>>(emptyList())
    private var priceVersion: Any? = 0
    val draft = MutableStateFlow<JsonObject?>(null)
    val menus = MutableStateFlow<List<JsonObject>>(emptyList())
    val checked = MutableStateFlow<JsonObject?>(null)
    val planName = MutableStateFlow("我的七日菜单")
    val navigateCopy = MutableStateFlow<String?>(null)

    init {
        launchLoad {
            loadCatalog()
            families.value = c.business.get("/households").objects("items")
            menus.value = c.business.get("/planning/menus").objects("items")
        }
    }

    private suspend fun loadCatalog() {
        val data = c.business.get("/planning/catalog")
        recipes.value = data.objects("recipes")
        val p = data.obj("prices")
        priceVersion = p?.num("version")?.toLong() ?: 0
        val known = p?.objects("items").orEmpty()
        prices.value = recipes.value.flatMap { r -> r.objects("ingredients").map { it.str("name").orEmpty() to it.str("unit").orEmpty() } }.distinct().map { (n, u) ->
            Triple(n, u, known.firstOrNull { it.str("name") == n && it.str("unit") == u }?.num("price")?.clean(4).orEmpty())
        }
    }

    fun edit(t: (PlanForm) -> PlanForm) { form.update(t); draft.value = null }
    fun setPrice(i: Int, v: String) = prices.update { l -> l.mapIndexed { idx, p -> if (idx == i) p.copy(third = v) else p } }

    fun choices(meal: String, role: String) = recipes.value.filter { r ->
        r.obj("_planning")?.strings("meals")?.contains(meal) == true && r.obj("_planning")?.strings("roles")?.contains(role) == true
    }

    fun recipeName(id: String?) = recipes.value.firstOrNull { it.str("id") == id }?.str("name") ?: id.orEmpty()

    fun preview() {
        val f = form.value
        val invalid = f.portion.toFiniteOrNull() == null || f.maxMinutes.toIntOrNull() == null || f.maxRepeat.toIntOrNull() == null ||
            (f.budget.isNotBlank() && f.budget.toFiniteOrNull() == null) ||
            f.bounds.values.any { (a,b) -> a.isNotBlank() && a.toFiniteOrNull() == null || b.isNotBlank() && b.toFiniteOrNull() == null }
        if (invalid) { command.setError("请检查份量、时长、重复次数、预算和营养目标；无效输入不会自动替换为默认值。"); return }
        draft.value = null
        val nutrition = f.bounds.filter { it.value.first.isNotBlank() || it.value.second.isNotBlank() }
            .mapValues { mapOf("min" to it.value.first.toFiniteOrNull(), "max" to it.value.second.toFiniteOrNull()) }
        val slots = f.sortedSlots
        val locks = slots.flatMap { (d, m) -> f.roles.mapNotNull { role -> f.locks["$d:$m:$role"]?.takeIf { it.isNotBlank() }?.let { mapOf("day" to d, "meal" to m, "role" to role, "recipe_id" to it) } } }
        send("/planning/preview", "post", jsonOf(
            "start_date" to f.startDate, "family_id" to f.familyId.ifBlank { null }, "people" to f.people, "portion_factor" to (f.portion.toFiniteOrNull() ?: 1.0),
            "budget" to f.budget.toFiniteOrNull(), "max_minutes" to (f.maxMinutes.toIntOrNull() ?: 120), "max_difficulty" to f.difficulty,
            "max_repeat" to (f.maxRepeat.toIntOrNull() ?: 3), "inventory_priority" to f.inventoryPriority,
            "meals" to slots.map { (d, m) -> mapOf("day" to d, "meal" to m, "side" to f.side) }, "locks" to locks, "nutrition" to nutrition,
            "avoid_ingredients" to f.avoid.split(Regex("[,，、]")).map { it.trim() }.filter { it.isNotEmpty() },
            "dietary_restrictions" to listOfNotNull(f.diet.ifBlank { null }), "confirm_estimates" to f.confirmed,
            "utc_offset_hours" to java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000.0,
        ))
    }

    fun defaultPreview() { form.value = PlanForm(people = 2, confirmed = true); preview() }

    fun savePrices() = send("/planning/prices", "put", jsonOf("expected_version" to priceVersion,
        "items" to prices.value.filter { it.third.isNotBlank() }.map { mapOf("name" to it.first, "unit" to it.second, "price" to it.third.toFiniteOrNull()) }))

    fun save() { val d = draft.value ?: return; send("/planning/menus", "post", jsonOf("preview_id" to d.str("id"), "expected_version" to d.num("version"), "name" to planName.value)) }

    fun check(menu: JsonObject) = launchLoad { checked.value = null; checked.value = c.business.get("/planning/menus/${menu.str("id")}/check") }

    fun loadForEdit(menu: JsonObject) {
        val cfg = menu.obj("config") ?: return
        val meals = cfg.objects("meals")
        form.value = PlanForm(
            startDate = cfg.str("start_date").orEmpty(), familyId = cfg.str("family_id").orEmpty(), people = cfg.num("people")?.toInt() ?: 1,
            portion = cfg.num("portion_factor")?.clean(2) ?: "1", budget = cfg.num("budget")?.clean(2).orEmpty(), maxMinutes = cfg.num("max_minutes")?.clean().orEmpty(),
            difficulty = cfg.num("max_difficulty")?.toInt() ?: 2, maxRepeat = cfg.num("max_repeat")?.clean().orEmpty(), inventoryPriority = cfg.bool("inventory_priority"),
            slots = meals.map { "${it.num("day")?.toInt()}:${it.str("meal")}" }.toSet(), side = meals.any { it.bool("side") },
            avoid = cfg.strings("avoid_ingredients").joinToString("、"), diet = cfg.strings("dietary_restrictions").firstOrNull().orEmpty(),
            bounds = nutrients.associate { (k, _) -> k to (cfg.obj("nutrition")?.obj(k)?.num("min")?.clean(2).orEmpty() to cfg.obj("nutrition")?.obj(k)?.num("max")?.clean(2).orEmpty()) },
            locks = menu.obj("result")?.objects("meals").orEmpty().flatMap { s -> s.objects("dishes").map { d -> "${s.num("day")?.toInt()}:${s.str("meal")}:${d.str("role")}" to d.str("recipe_id").orEmpty() } }.toMap(),
        )
        draft.value = null
        command.setError("已载入并锁定原计划，请展开“锁定指定菜”更改目标位置，再确认估算并重新计算。原计划保留。")
    }

    fun clone(menu: JsonObject, meal: JsonObject, dish: JsonObject) = send("/recipes/copies", "post", jsonOf("source_type" to "menu",
        "source_id" to "${menu.str("id")}|${meal.num("day")?.toInt()}|${meal.str("meal")}|${dish.str("role")}", "expected_source_version" to menu.num("version")?.toLong()?.toString()))

    override suspend fun onCommandSuccess(result: JsonObject) {
        result.obj("preview")?.let { draft.value = it }
        if (result.obj("prices") != null) loadCatalog()
        result.obj("menu")?.let { m -> menus.value = c.business.get("/planning/menus").objects("items"); checked.value = c.business.get("/planning/menus/${m.str("id")}/check") }
        result.obj("copy")?.str("id")?.let { navigateCopy.value = it }
    }
}

@Composable
fun MenuPlannerScreen(navigator: Navigator) {
    val vm = cookxViewModel { MenuPlannerViewModel(it) }
    val f by vm.form.collectAsStateWithLifecycle()
    val families by vm.families.collectAsStateWithLifecycle()
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val prices by vm.prices.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val menus by vm.menus.collectAsStateWithLifecycle()
    val checked by vm.checked.collectAsStateWithLifecycle()
    val planName by vm.planName.collectAsStateWithLifecycle()
    val navigateCopy by vm.navigateCopy.collectAsStateWithLifecycle()
    val busy = rememberBusy(vm)
    val context = LocalContext.current
    navigateCopy?.let { vm.navigateCopy.value = null; navigator.open(Routes.recipes(copy = it)) }

    SubpageScaffold("七日菜单", navigator::back, subtitle = "每餐安排主菜与主食，可选配菜。营养目标只针对勾选的餐次和全部用餐人数；保存计划不会扣库存或下单。") {
        item {
            Column(Modifier.overlapHero(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CommandStatus(vm, Modifier.padding(horizontal = 16.dp))
                Section("家常七日预设", icon = Icons.Outlined.CalendarMonth, subtitle = "2 人 · 午晚餐各主菜与主食 · 单菜最多 3 次 · 120 分钟内；使用工程份量估算，不设预算和营养硬目标。") {
                    PrimaryButton("接受预设并生成菜单", vm::defaultPreview, Modifier.fillMaxWidth(), enabled = !busy)
                }
                Section("选择餐次", icon = Icons.Outlined.CalendarMonth, subtitle = "点选需要安排的餐次（已选 ${f.slots.size} 个）") {
                    WeekGrid(f.slots) { key -> vm.edit { it.copy(slots = if (key in it.slots) it.slots - key else it.slots + key) } }
                    SwitchRow("每个所选餐次增加配菜", f.side, { v -> vm.edit { it.copy(side = v) } })
                }
                Section("约束条件", icon = Icons.Outlined.PlaylistAddCheck) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DateTimeField("起始日期", f.startDate, { v -> vm.edit { it.copy(startDate = v) } }, dateOnly = true)
                        DropdownField("使用库存范围", f.familyId, listOf(Choice("", "个人库存")) + families.map { Choice(it.str("id").orEmpty(), "家庭：${it.str("name")}") }, { v -> vm.edit { it.copy(familyId = v) } })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("用餐人数", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Stepper(f.people, { v -> vm.edit { it.copy(people = v) } }, 1, 20)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CookXTextField(f.portion, { v -> vm.edit { it.copy(portion = v) } }, "每人份量系数", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                            CookXTextField(f.budget, { v -> vm.edit { it.copy(budget = v) } }, "周预算（元）", Modifier.weight(1f), placeholder = "不限", keyboardType = KeyboardType.Decimal)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CookXTextField(f.maxMinutes, { v -> vm.edit { it.copy(maxMinutes = v) } }, "每餐时长上限（分）", Modifier.weight(1f), keyboardType = KeyboardType.Number)
                            CookXTextField(f.maxRepeat, { v -> vm.edit { it.copy(maxRepeat = v) } }, "同菜最多次数", Modifier.weight(1f), keyboardType = KeyboardType.Number)
                        }
                        DropdownField("最高难度", f.difficulty, listOf(Choice(1, "简单"), Choice(2, "中等"), Choice(3, "困难")), { v -> vm.edit { it.copy(difficulty = v) } })
                        CookXTextField(f.avoid, { v -> vm.edit { it.copy(avoid = v) } }, "忌口食材", placeholder = "逗号分隔，例如：鸡蛋、花生")
                        DropdownField("饮食限制", f.diet, listOf(Choice("", "未设置"), Choice("素食", "素食"), Choice("纯素", "纯素")), { v -> vm.edit { it.copy(diet = v) } })
                        SwitchRow("优先安排可评估的临期库存", f.inventoryPriority, { v -> vm.edit { it.copy(inventoryPriority = v) } })
                    }
                    Expandable("所选全部餐次的营养合计目标") {
                        Text("原料估算值，涵盖当前人数；不是全天营养达标结论。", fontSize = 12.sp, color = CookX.TextSecondary)
                        nutrients.forEach { (k, label) ->
                            val b = f.bounds[k] ?: ("" to "")
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CookXTextField(b.first, { v -> vm.edit { it.copy(bounds = it.bounds + (k to (v to b.second))) } }, "$label 下限", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                                CookXTextField(b.second, { v -> vm.edit { it.copy(bounds = it.bounds + (k to (b.first to v))) } }, "上限", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                            }
                        }
                    }
                    Expandable("锁定指定菜") {
                        Text("锁定不会突破忌口或其他硬约束。局部替换时可只更改目标位置，其余位置保持锁定。", fontSize = 12.sp, color = CookX.TextSecondary)
                        f.sortedSlots.forEach { (d, m) ->
                            Text("第 ${d + 1} 天 ${mealName(m)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                            f.roles.forEach { role ->
                                val key = "$d:$m:$role"
                                DropdownField(roleNames[role].orEmpty(), f.locks[key].orEmpty(), listOf(Choice("", "由求解器选择")) + vm.choices(m, role).map { Choice(it.str("id").orEmpty(), it.str("name").orEmpty()) },
                                    { v -> vm.edit { it.copy(locks = it.locks + (key to v)) } }, Modifier.padding(bottom = 6.dp))
                            }
                        }
                    }
                    CheckRow("我已核对模板份量与食材映射，接受营养估算依据", f.confirmed, { v -> vm.edit { it.copy(confirmed = v) } })
                    Text("原 22 道菜按两人份工程模板，新增主食与早餐按一人份模板；估算仅用于规划，不换算库存。", fontSize = 11.5.sp, color = CookX.TextTertiary)
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("计算菜单预览", vm::preview, Modifier.fillMaxWidth(), loading = busy, enabled = f.confirmed && f.slots.isNotEmpty(), icon = Icons.Outlined.Science)
                }
                draft?.let { d -> PreviewSection(vm, d, planName, busy) }
                Section("价格与数据来源", icon = Icons.Outlined.Payments) {
                    Expandable("维护我的食材单价（元 / 所列单位）") {
                        Text("缺价格保持空白，不视为零。只填写已核实的本地价格，最多四位小数。", fontSize = 12.sp, color = CookX.TextSecondary)
                        prices.forEachIndexed { i, (n, u, p) ->
                            CookXTextField(p, { vm.setPrice(i, it) }, "$n / $u", Modifier.padding(top = 8.dp), keyboardType = KeyboardType.Decimal)
                        }
                        TonalButton("保存价格", vm::savePrices, Modifier.padding(top = 10.dp), enabled = !busy)
                    }
                    Expandable("营养数据与规划模板来源") {
                        Text("USDA FoodData Central SR Legacy April 2018。下列证据随菜谱内置；未匹配的食材保持缺失，营养硬约束会排除数据不全的菜谱。", fontSize = 12.sp, color = CookX.TextSecondary)
                        recipes.forEach { r ->
                            val pn = r.obj("planning_nutrition")
                            ItemBlock("${r.str("name")} · ${r.obj("_planning")?.num("servings")?.clean()} 人份", Modifier.padding(top = 8.dp),
                                lines = listOf(r.obj("_planning")?.str("servings_basis").orEmpty(), pn?.strings("missing")?.takeIf { it.isNotEmpty() }?.let { "缺少：${it.joinToString("、")}" }.orEmpty()) +
                                    pn?.objects("evidence").orEmpty().map { e -> "${e.str("ingredient")}：FDC ${e.str("fdc_id")} · 每 ${e.str("unit")} 约 ${e.str("grams_per_unit")} 克" }) {
                                pn?.objects("evidence")?.firstOrNull()?.str("source_url")?.let { url ->
                                    LinkButton("打开数据来源", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, color = CookX.Primary)
                                }
                            }
                        }
                    }
                }
                Section("已保存计划", icon = Icons.Outlined.FactCheck) {
                    if (menus.isEmpty()) Text("还没有保存的计划。", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
                    menus.forEach { m ->
                        ItemBlock(m.str("name").orEmpty(), lines = listOf("${m.obj("config")?.str("start_date")} 起 · ${m.obj("config")?.num("people")?.toInt()} 人"), actions = {
                            TonalButton("执行前重新核对", { vm.check(m) }, enabled = !busy)
                            OutlineButton("局部替换或重算", { vm.loadForEdit(m) }, enabled = !busy)
                        })
                    }
                }
                checked?.let { c -> CheckSection(vm, c, busy) { navigator.open(Routes.SHOPPING) } }
            }
        }
    }
}

@Composable
private fun WeekGrid(slots: Set<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Spacer(Modifier.width(56.dp))
            meals.forEach { (_, name) -> Text(name, Modifier.weight(1f), fontSize = 12.sp, color = CookX.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        (0..6).forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("第 ${day + 1} 天", Modifier.width(50.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                meals.forEach { (id, _) ->
                    val key = "$day:$id"
                    val on = key in slots
                    Box(
                        Modifier.weight(1f).height(34.dp).clip(CookXShapes.Small).background(if (on) CookX.Primary else CookX.SurfaceMuted)
                            .border(1.dp, if (on) CookX.Primary else CookX.Border, CookXShapes.Small).clickable { onToggle(key) },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (on) "✓" else "", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun Expandable(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(CookXShapes.Small).clickable { open = !open }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = CookX.Primary, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = CookX.Primary, modifier = Modifier.size(20.dp))
    }
    AnimatedVisibility(open) { Column { content() } }
}

@Composable
private fun PreviewSection(vm: MenuPlannerViewModel, d: JsonObject, planName: String, busy: Boolean) {
    val r = d.obj("result") ?: return
    val status = r.str("solver_status")
    val money = { v: Double? -> v?.let { "%.2f 元".format(it) } ?: "数据不足" }
    Section(solverLabels[status] ?: status.orEmpty(), icon = Icons.Outlined.Science,
        trailing = { StatusChip(if (status == "FEASIBLE") "可执行" else "需调整", if (status == "OPTIMAL" || status == "FEASIBLE") Tone.Fresh else Tone.Danger) }) {
        r["issues"].asArray().orEmpty().forEach { i ->
            val o = i as? JsonObject
            Banner(o?.let { "${it.str("reason")}（${it.str("slot")} ${roleNames[it.str("role")].orEmpty()}）" } ?: i.asText().orEmpty(), BannerKind.Warning, Modifier.padding(bottom = 6.dp))
        }
        r.str("note")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary) }
        val mealsList = r.objects("meals")
        if (mealsList.isNotEmpty()) {
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("总成本 ${money(r.num("total_estimated_cost"))}", Tone.Green)
                StatusChip("补购 ${money(r.num("shopping_estimated_cost"))}", Tone.Warm)
            }
            r.obj("nutrition")?.let { n -> Text("能量 ${n.num("kcal")?.toInt()} kcal；蛋白质 ${n.num("protein_g")?.let { "%.1f".format(it) }} 克（所选餐次合计）", fontSize = 12.sp, color = CookX.TextSecondary) }
        }
        r.objects("exclusions").takeIf { it.isNotEmpty() }?.let { ex ->
            Expandable("被排除的候选与缺失数据（${ex.size}）") { ex.forEach { e -> Text("${vm.recipeName(e.str("recipe_id"))}：${e.str("reason")} ${e.strings("missing").joinToString("、")}", fontSize = 11.5.sp, color = CookX.TextSecondary) } }
        }
        mealsList.forEach { m ->
            ItemBlock("第 ${m.num("day")!!.toInt() + 1} 天 ${mealName(m.str("meal"))}", Modifier.padding(top = 8.dp), badge = { StatusChip("${m.num("minutes")?.toInt()} 分钟", Tone.Neutral) },
                lines = m.objects("dishes").map { "${roleNames[it.str("role")]}：${it.str("name")} · 原菜谱 ${it.num("factor")?.clean(2)} 倍份量" })
        }
        if (mealsList.isNotEmpty()) {
            CookXTextField(planName, { vm.planName.value = it }, "计划名称", Modifier.padding(top = 8.dp), maxLength = 80)
            PrimaryButton("确认保存此计划", vm::save, Modifier.fillMaxWidth().padding(top = 10.dp), loading = busy)
        }
    }
}

@Composable
private fun CheckSection(vm: MenuPlannerViewModel, c: JsonObject, busy: Boolean, onShopping: () -> Unit) {
    val menu = c.obj("menu") ?: return
    Section("${menu.str("name")} · 执行前核对", icon = Icons.Outlined.FactCheck, subtitle = c.str("note")) {
        c.strings("revalidation_issues").forEach { Banner(it, BannerKind.Error, Modifier.padding(bottom = 6.dp)) }
        if (c.bool("inventory_changed")) Banner("库存已变化，采购需求须重算；烹饪前逐项核查。", BannerKind.Warning, Modifier.padding(bottom = 6.dp))
        if (c.bool("price_changed")) Banner("价格已更新，保存时估算可能过时。", BannerKind.Info, Modifier.padding(bottom = 6.dp))
        c.objects("inventory").forEach { r ->
            Text("${r.obj("item")?.str("name")} · ${r.obj("item")?.num("quantity")?.clean(3)} ${r.obj("item")?.str("unit") ?: "库存计数"} · ${r.obj("freshness")?.str("freshness_label")}", fontSize = 12.sp, color = CookX.TextBody)
        }
        menu.obj("result")?.objects("meals").orEmpty().forEach { m ->
            ItemBlock("第 ${m.num("day")!!.toInt() + 1} 天 ${mealName(m.str("meal"))}", Modifier.padding(top = 8.dp), actions = {
                m.objects("dishes").forEach { dish -> TonalButton("复刻${dish.str("name")}", { vm.clone(menu, m, dish) }, enabled = !busy) }
            })
        }
        OutlineButton("前往共同采购", onShopping, Modifier.padding(top = 8.dp))
    }
}
