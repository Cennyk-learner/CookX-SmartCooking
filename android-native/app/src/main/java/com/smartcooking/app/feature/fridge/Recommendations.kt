package com.smartcooking.app.feature.fridge

import com.smartcooking.app.core.toFiniteOrNull
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
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartcooking.app.core.AppContainer
import com.smartcooking.app.core.ApiException
import com.smartcooking.app.core.EmptyObject
import com.smartcooking.app.core.asObject
import com.smartcooking.app.core.asText
import com.smartcooking.app.core.clean
import com.smartcooking.app.core.commandKey
import com.smartcooking.app.core.jsonOf
import com.smartcooking.app.core.long
import com.smartcooking.app.core.num
import com.smartcooking.app.core.obj
import com.smartcooking.app.core.objects
import com.smartcooking.app.core.str
import com.smartcooking.app.core.strings
import com.smartcooking.app.core.userMessage
import com.smartcooking.app.data.PreferenceOptions
import com.smartcooking.app.ui.components.Banner
import com.smartcooking.app.ui.components.BannerKind
import com.smartcooking.app.ui.components.Choice
import com.smartcooking.app.ui.components.CookXCard
import com.smartcooking.app.ui.components.CookXTextField
import com.smartcooking.app.ui.components.DropdownField
import com.smartcooking.app.ui.components.EmptyState
import com.smartcooking.app.ui.components.IconBadge
import com.smartcooking.app.ui.components.Kicker
import com.smartcooking.app.ui.components.LinkButton
import com.smartcooking.app.ui.components.LoadingBlock
import com.smartcooking.app.ui.components.OutlineButton
import com.smartcooking.app.ui.components.PrimaryButton
import com.smartcooking.app.ui.components.StatusChip
import com.smartcooking.app.ui.components.Tone
import com.smartcooking.app.ui.nav.cookxViewModel
import com.smartcooking.app.ui.theme.CookX
import com.smartcooking.app.ui.theme.CookXShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

enum class RecView { INITIAL, LOADING, SUCCESS, INVENTORY_REQUIRED, NO_ELIGIBLE, AUTH, ERROR, STALE }

data class RecState(
    val view: RecView = RecView.INITIAL,
    val message: String = "",
    val criteria: Map<String, String> = emptyMap(),
    val items: List<JsonObject> = emptyList(),
    val eligible: Int = 0,
    val filtered: Int = 0,
    val algorithm: String = "multi_objective_v1",
    val personalization: String? = null,
    val requested: Boolean = false,
)

class RecommendationsViewModel(private val c: AppContainer) : ViewModel() {
    private val user = c.sessions.currentUserId
    private val criteriaKey = "cookx:recommendation-preferences:v1:$user"
    private val _state = MutableStateFlow(RecState(criteria = loadLocalCriteria(), requested = c.store.get("cookx:recommendation-requested:$user") == "1"))
    val state = _state.asStateFlow()
    private var preferenceVersion: Long? = null
    private var prefValues: JsonObject = EmptyObject
    private var dirty = false
    private var job: Job? = null
    private var lastRevision = -1

    init {
        viewModelScope.launch { runCatching { loadRemote() }.onFailure { e -> _state.update { it.copy(message = "账号偏好读取失败：${e.userMessage()}") } } }
    }

    private fun loadLocalCriteria(): Map<String, String> =
        c.store.getJson(criteriaKey).asObject()?.mapValues { it.value.asText().orEmpty() } ?: emptyMap()

    private suspend fun loadRemote() {
        val prefs = c.preferences.load()
        preferenceVersion = prefs.version
        prefValues = prefs.values
        if (prefs.version > 0 && !dirty) {
            val remote = prefs.recommendation.mapValues { (k, v) -> v.asText()?.let { t -> if (k == "difficulty_target") t else t.toFiniteOrNull()?.clean(2) ?: t }.orEmpty() }
            _state.update { it.copy(criteria = remote) }
        }
    }

    fun setCriterion(key: String, value: String) {
        dirty = true
        job?.cancel()
        _state.update { it.copy(criteria = it.criteria + (key to value), items = emptyList(), view = if (it.requested) RecView.STALE else RecView.INITIAL) }
    }

    /** Called when the fridge reloads: old results are invalid; refetch if the user already asked once. */
    fun inventoryChanged(revision: Int, ready: Boolean) {
        if (revision != lastRevision) {
            lastRevision = revision
            job?.cancel()
            _state.update { it.copy(items = emptyList(), view = if (it.requested) RecView.STALE else RecView.INITIAL) }
        }
        if (ready && _state.value.requested && _state.value.view == RecView.STALE) fetch()
    }

    private fun serialize(criteria: Map<String, String>): JsonObject {
        val result = mutableMapOf<String, Any?>()
        val nutrition = mutableMapOf<String, Double>()
        for ((key, label) in listOf("budget" to "整道菜预算") + PreferenceOptions.nutritionFields) {
            val raw = criteria[key].orEmpty().trim()
            if (raw.isEmpty()) continue
            val v = raw.toFiniteOrNull()
            if (v == null || v <= 0 || v.isInfinite()) throw IllegalArgumentException("${label.substringBefore("（")}必须是正数")
            if (key == "budget") result["budget"] = v else nutrition[key] = v
        }
        criteria["difficulty_target"]?.takeIf { it.isNotBlank() }?.let { result["difficulty_target"] = it }
        if (nutrition.isNotEmpty()) result["nutrition_target"] = nutrition
        return jsonOf(*result.map { it.key to it.value }.toTypedArray())
    }

    fun fetch() {
        val user = user
        if (user == null) { _state.update { it.copy(view = RecView.AUTH, message = "登录信息缺失或已失效，请重新登录后获取推荐。", requested = true) }; return }
        job?.cancel()
        job = viewModelScope.launch {
            c.store.put("cookx:recommendation-requested:$user", "1")
            _state.update { it.copy(view = RecView.LOADING, requested = true, message = "") }
            try {
                val constraints = serialize(_state.value.criteria)
                if (preferenceVersion == null) runCatching { loadRemote() }
                if (dirty && preferenceVersion != null) {
                    val recommendation = _state.value.criteria.mapValues { (k, v) -> if (v.isBlank()) null else if (k == "difficulty_target") v else v.toDouble() }
                    val saved = c.business.call("/preferences", "PUT", jsonOf("idempotency_key" to commandKey(), "expected_version" to preferenceVersion, "recommendation" to recommendation))
                    preferenceVersion = saved.obj("preferences")?.long("version") ?: preferenceVersion
                    dirty = false
                }
                c.store.putJson(criteriaKey, jsonOf(*_state.value.criteria.map { it.key to it.value }.toTypedArray()))
                val prefs = buildMap<String, Any?> {
                    prefValues.str("taste")?.takeIf { it.isNotBlank() }?.let { put("taste", it) }
                    prefValues.str("spice")?.takeIf { it.isNotBlank() }?.let { put("spice", it) }
                    prefValues.str("duration")?.takeIf { it.isNotBlank() }?.let { put("duration", it) }
                    prefValues.str("dislikedIngredients")?.split(Regex("[，,、\\n]"))?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { put("disliked_ingredients", it) }
                }
                val body = JsonObject(jsonOf("user_id" to user, "top_k" to 5, "preferences" to prefs) + constraints)
                val data = c.api.post("/recommendations", body).asObject() ?: EmptyObject
                val view = when (data.str("status")) {
                    "success" -> RecView.SUCCESS
                    "inventory_required" -> RecView.INVENTORY_REQUIRED
                    "no_eligible_recipes" -> RecView.NO_ELIGIBLE
                    else -> RecView.ERROR
                }
                _state.update {
                    it.copy(
                        view = view,
                        items = if (view == RecView.SUCCESS) data.objects("recommendations") else emptyList(),
                        eligible = data.num("eligible_recipe_count")?.toInt() ?: 0,
                        filtered = data.num("filtered_recipe_count")?.toInt() ?: 0,
                        algorithm = data.str("algorithm_version") ?: "multi_objective_v1",
                        personalization = data.obj("personalization")?.str("reason"),
                        message = if (view == RecView.ERROR) "推荐服务返回了无法识别的状态，请稍后重试。" else data.str("message").orEmpty(),
                    )
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val status = (e as? ApiException)?.status
                _state.update {
                    it.copy(
                        items = emptyList(),
                        view = if (status == 404) RecView.AUTH else RecView.ERROR,
                        message = when (status) {
                            404 -> "登录信息可能已失效，请重新登录。"
                            422 -> "推荐参数暂时无法处理，请检查目标后重试。"
                            else -> e.userMessage()
                        },
                    )
                }
            }
        }
    }
}

private val metricLabels = mapOf("I" to "食材匹配", "F" to "临期利用", "P" to "偏好匹配", "W" to "厨余减少", "B" to "预算匹配", "D" to "难度匹配", "N" to "营养匹配", "M" to "缺失惩罚")
private fun pct(v: Double?) = v?.let { "${Math.round(it * 100)}%" } ?: "数据不足"

@Composable
fun RecommendationsSection(revision: Int, ready: Boolean, onManageInventory: () -> Unit, onLearning: () -> Unit, onCook: (String) -> Unit) {
    val vm = cookxViewModel { RecommendationsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(revision, ready) { vm.inventoryChanged(revision, ready) }
    RecommendationsContent(state, ready, vm::fetch, vm::setCriterion, onManageInventory, onLearning, onCook)
}

@Composable
fun RecommendationsContent(
    state: RecState,
    ready: Boolean,
    onFetch: () -> Unit,
    onCriterion: (String, String) -> Unit,
    onManageInventory: () -> Unit,
    onLearning: () -> Unit,
    onCook: (String) -> Unit,
) {
    var showOptions by remember { mutableStateOf(false) }
    CookXCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Kicker("SMART RECOMMENDATION", color = CookX.Accent)
                Text("智能菜谱推荐", style = MaterialTheme.typography.titleLarge)
                Text("综合库存、临期情况和饮食偏好排序", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
            }
            IconBadge(Icons.Outlined.Tune, if (showOptions) Tone.Warm else Tone.Neutral, size = 36.dp, iconSize = 19.dp,
                modifier = Modifier.clip(CookXShapes.Icon).clickable { showOptions = !showOptions })
        }
        AnimatedVisibility(showOptions) {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CookXTextField(state.criteria["budget"].orEmpty(), { onCriterion("budget", it) }, "整道菜预算（元）", Modifier.weight(1f), keyboardType = KeyboardType.Decimal, enabled = state.view != RecView.LOADING)
                    DropdownField("期望难度", state.criteria["difficulty_target"].orEmpty(), PreferenceOptions.difficulties.map { Choice(it.first, it.second) }, { onCriterion("difficulty_target", it) }, Modifier.weight(1f))
                }
                PreferenceOptions.nutritionFields.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (key, label) ->
                            CookXTextField(state.criteria[key].orEmpty(), { onCriterion(key, it) }, label, Modifier.weight(1f), keyboardType = KeyboardType.Decimal, enabled = state.view != RecView.LOADING)
                        }
                    }
                }
                Text("预算按整道菜估算，营养目标按每份计算。缺少基础数据时对应分项不参与评分；目标保存到当前账号。", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
            }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            when { state.view == RecView.LOADING -> "正在分析"; state.requested -> "重新推荐"; else -> "获取智能推荐" },
            onFetch, Modifier.fillMaxWidth(), icon = Icons.Outlined.Refresh, loading = state.view == RecView.LOADING, enabled = ready,
        )
        Spacer(Modifier.height(12.dp))
        when (state.view) {
            RecView.INITIAL -> StateRow(Icons.Outlined.Analytics, "从真实库存中寻找更合适的一餐", "算法只会推荐至少匹配一项安全关键食材的标准菜谱。")
            RecView.STALE -> Banner("库存或目标已变化，旧推荐已失效，请重新获取推荐。", BannerKind.Info)
            RecView.LOADING -> LoadingBlock("正在核对库存、临期情况和已保存偏好…", minHeight = 80)
            RecView.INVENTORY_REQUIRED, RecView.NO_ELIGIBLE -> EmptyState(Icons.Outlined.Inventory2,
                if (state.view == RecView.INVENTORY_REQUIRED) "需要可用库存" else "暂时没有合格菜谱", state.message, tone = Tone.Gold) {
                OutlineButton("管理或识别食材", onManageInventory)
            }
            RecView.AUTH, RecView.ERROR -> Banner(state.message, BannerKind.Error, title = "推荐服务暂时不可用") { OutlineButton("重试", onFetch) }
            RecView.SUCCESS -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip("${state.eligible} 道合格候选", Tone.Green)
                    StatusChip("已筛除 ${state.filtered} 道", Tone.Neutral)
                }
                state.personalization?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary, modifier = Modifier.padding(top = 8.dp)) }
                LinkButton("管理偏好学习与反馈 →", onLearning, color = CookX.Primary)
                state.items.forEach { RecommendationCard(it, onCook) }
                Text("综合推荐分满分 100，按当前库存、临期情况与已设置目标在本机计算；缺少数据的分项不计入总分，其余权重自动归一。", fontSize = 10.5.sp, color = CookX.TextTertiary, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (state.message.isNotBlank() && state.view in listOf(RecView.INITIAL, RecView.SUCCESS)) {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun StateRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().clip(CookXShapes.Tile).background(CookX.SurfaceMuted).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, size = 42.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
        }
    }
}

@Composable
private fun RecommendationCard(item: JsonObject, onCook: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val rank = item.num("rank")?.toInt() ?: 0
    val winner = rank == 1
    Column(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(CookXShapes.Tile)
            .background(if (winner) Color(0xFFFFFBF4) else CookX.Surface)
            .border(1.dp, if (winner) CookX.Gold.copy(alpha = 0.5f) else CookX.Border, CookXShapes.Tile)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CookXShapes.Small).background(if (winner) CookX.accentBrush else androidx.compose.ui.graphics.SolidColor(CookX.Mint)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text("TOP $rank", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (winner) Color.White else CookX.Primary)
            }
            Spacer(Modifier.width(10.dp))
            Text(item.str("recipe_name").orEmpty(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(item.num("total_score")?.let { "%.2f".format(it) } ?: "--", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CookX.Accent)
                Text("综合推荐分", fontSize = 10.sp, color = CookX.TextSecondary)
            }
        }
        item.obj("personalization")?.let { p ->
            val reasons = p.objects("reasons").joinToString("、") { "${it.str("tag")}（${it.str("direction")}）" }
            Text("偏好重排依据：${reasons.ifBlank { "当前标签模型" }}。原综合推荐分保持不变。", fontSize = 11.sp, color = CookX.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        IngredientLine("已匹配", item.strings("matched_ingredients"), Tone.Fresh, "暂无")
        IngredientLine("缺失关键", item.strings("missing_required_ingredients"), Tone.Warm, "无需补充")
        IngredientLine("临期食材", item.strings("expiring_ingredients_used"), Tone.Gold, "无")
        val unsafe = item.strings("unsafe_or_expired_ingredients")
        if (unsafe.isNotEmpty()) Banner("过期或不可用：${unsafe.joinToString(" · ")}", BannerKind.Error, Modifier.padding(top = 8.dp))
        val reasons = item.strings("reasons")
        if (reasons.isNotEmpty()) {
            Text("推荐理由", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            reasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = CookX.TextBody) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(CookXShapes.Small).clickable { expanded = !expanded }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("评分依据", fontSize = 12.5.sp, color = CookX.Primary, fontWeight = FontWeight.SemiBold)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = CookX.Primary, modifier = Modifier.size(18.dp))
            }
            LinkButton("咨询教程 →", { onCook(item.str("recipe_name").orEmpty()) }, color = CookX.Accent)
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("I", "F", "P", "W", "B", "D", "N", "M").forEach { key ->
                    val v = item.obj("component_scores")?.num(key)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(metricLabels[key].orEmpty(), fontSize = 11.5.sp, color = CookX.TextBody, modifier = Modifier.width(64.dp))
                        Box(Modifier.weight(1f).height(6.dp).clip(CookXShapes.Pill).background(CookX.Border)) {
                            if (v != null) LinearProgressIndicator(progress = { v.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = if (key == "M") CookX.Danger else CookX.Success, trackColor = Color.Transparent, drawStopIndicator = {})
                        }
                        Text(pct(v), fontSize = 11.sp, color = CookX.TextSecondary, modifier = Modifier.padding(start = 8.dp).width(52.dp))
                    }
                }
                val weights = item.obj("effective_weights")?.entries?.joinToString(" · ") { "${metricLabels[it.key] ?: it.key} ${pct(it.value.asText()?.toFiniteOrNull())}" }.orEmpty()
                Text("$weights · 缺失惩罚 ${pct(item.num("missing_penalty_weight"))}", fontSize = 11.sp, color = CookX.TextSecondary)
                item.strings("unavailable_components").takeIf { it.isNotEmpty() }?.let { Text("未参与评分：${it.joinToString("、")}", fontSize = 11.sp, color = CookX.TextSecondary) }
                val notes = buildList {
                    addAll(item.strings("data_quality_notes"))
                    add("预算：${item.str("cost_status") ?: "数据不足"}；整道菜估价 ${item.str("estimated_cost") ?: "未知"} ${item.str("currency") ?: "CNY"}")
                    addAll(item.strings("cost_data_notes"))
                    add("难度：${item.str("difficulty_status") ?: "数据不足"}；菜谱难度 ${item.str("recipe_difficulty") ?: "未知"}")
                    addAll(item.strings("difficulty_data_notes"))
                    add("营养：${item.str("nutrition_status") ?: "数据不足"}；口径 ${if (item.str("nutrition_basis") == "per_serving") "每份" else item.str("nutrition_basis") ?: "未知"}")
                    addAll(item.strings("nutrition_data_notes"))
                    item.str("nutrition_disclaimer")?.let(::add)
                }
                notes.forEach { Text(it, fontSize = 11.sp, color = CookX.TextSecondary, lineHeight = 16.sp) }
            }
        }
    }
}

@Composable
private fun IngredientLine(label: String, values: List<String>, tone: Tone, empty: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(64.dp).padding(top = 1.dp)) { Text(label, fontSize = 11.5.sp, color = tone.fg, fontWeight = FontWeight.SemiBold) }
        Text(if (values.isEmpty()) empty else values.joinToString(" · "), fontSize = 12.5.sp, color = if (values.isEmpty()) CookX.TextTertiary else CookX.TextBody, modifier = Modifier.weight(1f))
    }
}
