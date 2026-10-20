package com.smartcooking.app.feature.profile

import com.smartcooking.app.core.obj
import com.smartcooking.app.core.Time
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartcooking.app.BuildConfig
import com.smartcooking.app.R
import com.smartcooking.app.core.AppContainer
import com.smartcooking.app.core.LocalAppContainer
import com.smartcooking.app.core.asArray
import com.smartcooking.app.core.asObject
import com.smartcooking.app.core.jsonOf
import com.smartcooking.app.core.str
import com.smartcooking.app.core.userMessage
import com.smartcooking.app.data.PreferenceOptions
import com.smartcooking.app.data.Recipe
import com.smartcooking.app.feature.auth.ServerSettingsSheet
import com.smartcooking.app.ui.components.AnimatedBanner
import com.smartcooking.app.ui.components.Banner
import com.smartcooking.app.ui.components.BannerKind
import com.smartcooking.app.ui.components.Choice
import com.smartcooking.app.ui.components.ConfirmDialog
import com.smartcooking.app.ui.components.CookXCard
import com.smartcooking.app.ui.components.CookXTextField
import com.smartcooking.app.ui.components.DropdownField
import com.smartcooking.app.ui.components.EmptyState
import com.smartcooking.app.ui.components.IconBadge
import com.smartcooking.app.ui.components.LinkButton
import com.smartcooking.app.ui.components.ListRow
import com.smartcooking.app.ui.components.LoadingBlock
import com.smartcooking.app.ui.components.LocalMessenger
import com.smartcooking.app.ui.components.OutlineButton
import com.smartcooking.app.ui.components.PendingBanner
import com.smartcooking.app.ui.components.PrimaryButton
import com.smartcooking.app.ui.components.SectionHeader
import com.smartcooking.app.ui.components.StatusChip
import com.smartcooking.app.ui.components.SubpageScaffold
import com.smartcooking.app.ui.components.Tone
import com.smartcooking.app.ui.components.Wordmark
import com.smartcooking.app.ui.components.overlapHero
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

// ---------------------------------------------------------------- 偏好设置

data class PrefsState(
    val loaded: Boolean = false,
    val version: Long = 0,
    val taste: String = "",
    val spice: String = "不辣",
    val duration: String = "",
    val disliked: String = "",
    val error: String? = null,
)

class PreferencesViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(PrefsState())
    val state = _state.asStateFlow()
    val command = c.pendingCommand("preferences") { load(); saved.value = true }
    val saved = MutableStateFlow(false)

    init { viewModelScope.launch { load() } }

    suspend fun load() {
        try {
            val p = c.preferences.load()
            _state.update {
                if (p.version > 0) PrefsState(true, p.version, p.values.str("taste").orEmpty(), p.values.str("spice")?.ifBlank { null } ?: "不辣",
                    p.values.str("duration").orEmpty(), p.values.str("dislikedIngredients").orEmpty())
                else it.copy(loaded = true, version = 0, error = null)
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _state.update { it.copy(loaded = false, error = e.userMessage()) }
        }
    }

    fun edit(transform: (PrefsState) -> PrefsState) = _state.update(transform)
    fun reload() { viewModelScope.launch { load() } }
    fun retry() { viewModelScope.launch { command.retry() } }

    fun save() {
        val s = _state.value
        if (!s.loaded) return
        viewModelScope.launch {
            command.send("/preferences", "put", jsonOf("expected_version" to s.version,
                "values" to mapOf("taste" to s.taste, "spice" to s.spice, "duration" to s.duration, "dislikedIngredients" to s.disliked)))
        }
    }
}

@Composable
fun PreferencesScreen(navigator: Navigator) {
    val vm = cookxViewModel { PreferencesViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.command.busy.collectAsStateWithLifecycle()
    val pending by vm.command.pending.collectAsStateWithLifecycle()
    val error by vm.command.error.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    LaunchedEffect(saved) { if (saved) { messenger.show("偏好已保存到当前账号"); vm.saved.value = false } }

    SubpageScaffold("偏好设置", navigator::back, subtitle = "记录你的口味和烹饪习惯。偏好保存在当前账号，其他设备登录后可继续使用。") {
        item {
            CookXCard(Modifier.padding(horizontal = 16.dp).overlapHero()) {
                SectionHeader("口味与习惯", icon = Icons.Outlined.Restaurant)
                Spacer(Modifier.height(16.dp))
                if (pending != null) { PendingBanner(busy, vm::retry); Spacer(Modifier.height(12.dp)) }
                AnimatedBanner(state.error ?: error.ifBlank { null }, BannerKind.Error, Modifier.padding(bottom = 12.dp))
                if (!state.loaded && state.error == null) LoadingBlock("正在读取已保存偏好…", minHeight = 80)
                val enabled = state.loaded && !busy
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField("口味偏好", state.taste, listOf(Choice("", "未选择")) + PreferenceOptions.tastes.map { Choice(it, it) }, { v -> vm.edit { it.copy(taste = v) } }, enabled = enabled)
                    DropdownField("辣度", state.spice, PreferenceOptions.spices.map { Choice(it, it) }, { v -> vm.edit { it.copy(spice = v) } }, enabled = enabled)
                    DropdownField("烹饪时长偏好", state.duration, listOf(Choice("", "未选择")) + PreferenceOptions.durations.map { Choice(it.first, it.second) }, { v -> vm.edit { it.copy(duration = v) } }, enabled = enabled)
                    CookXTextField(state.disliked, { v -> vm.edit { it.copy(disliked = v) } }, "食材禁忌 / 不喜欢的食材", placeholder = "例如：花生、香菜（请按真实情况填写）",
                        singleLine = false, minLines = 3, maxLength = 200, enabled = enabled)
                }
                Spacer(Modifier.height(16.dp))
                PrimaryButton("保存偏好", vm::save, Modifier.fillMaxWidth(), icon = Icons.Outlined.CheckCircle, loading = busy, enabled = state.loaded && pending == null)
                LinkButton("刷新已保存偏好", vm::reload, Modifier.align(Alignment.CenterHorizontally), color = CookX.Primary, enabled = !busy)
            }
        }
        item {
            Banner("忌口由服务端在推荐和七日菜单中执行，不会被个性化学习突破。", BannerKind.Info, Modifier.padding(horizontal = 16.dp))
        }
    }
}

// ---------------------------------------------------------------- 账号与安全

@Composable
fun AccountSecurityScreen(navigator: Navigator) {
    val container = LocalAppContainer.current
    val messenger = LocalMessenger.current
    val vm = cookxViewModel { ProfileViewModel(it) }
    val session by container.sessions.session.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(false) }
    val verifyScope = androidx.compose.runtime.rememberCoroutineScope()
    val phone = session?.user?.str("phone").orEmpty()
    val masked = if (Regex("^\\d{11}$").matches(phone)) "${phone.take(3)}****${phone.takeLast(4)}" else phone.ifBlank { "未设置手机号" }

    SubpageScaffold("账号与安全", navigator::back, subtitle = "管理当前账号资料、后端连接与账户状态。") {
        item {
            CookXCard(Modifier.padding(horizontal = 16.dp).overlapHero()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Person, size = 52.dp, iconSize = 26.dp, shape = CookXShapes.Tile)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(session?.displayName?.ifBlank { null } ?: "未设置昵称", style = MaterialTheme.typography.titleLarge)
                        Text(masked, style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
                    }
                }
                Spacer(Modifier.height(10.dp))
                ListRow("编辑个人资料", subtitle = "修改昵称、手机号和头像", icon = Icons.Outlined.Edit, onClick = { container.events.editProfile.value = true; navigator.tab(Routes.PROFILE) })
                ListRow("局域网后端地址", subtitle = container.sessions.backendOrigin, icon = Icons.Outlined.Dns, onClick = { server = true })
                ListRow(
                    "登录状态", subtitle = session?.expiresAtMillis?.let { "本机登录有效至 ${Time.display(it)} · 点按向服务器核对" } ?: "点按向服务器核对当前登录",
                    icon = Icons.Outlined.VerifiedUser, tone = Tone.Fresh,
                    onClick = {
                        verifyScope.launch {
                            val result = runCatching { container.api.get("/auth/session").asObject()?.obj("user") }
                            messenger.show(result.getOrNull()?.let { "服务器已确认登录：${it.str("nickname") ?: it.str("username") ?: "当前账号"}" } ?: "未能确认登录状态，请检查后端连接")
                        }
                    },
                )
                Row(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                    IconBadge(Icons.Outlined.Lock, Tone.Neutral, size = 40.dp)
                    Spacer(Modifier.width(13.dp))
                    Column {
                        Text("密码管理", style = MaterialTheme.typography.titleSmall)
                        Text("当前后端尚未提供修改密码接口，因此不展示虚假的操作入口。", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
                    }
                }
            }
        }
        item {
            CookXCard(Modifier.padding(horizontal = 16.dp)) {
                SectionHeader("危险操作", icon = Icons.Outlined.WarningAmber)
                Text("注销后个人账号、库存和对话记录将删除，家庭共享记录由家庭保留。请先移交家庭管理员权限。", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary, modifier = Modifier.padding(vertical = 10.dp))
                OutlineButton("注销账户", { confirmDelete = true }, Modifier.fillMaxWidth(), icon = Icons.Outlined.DeleteForever, color = CookX.Danger)
            }
        }
    }
    if (confirmDelete) ConfirmDialog("风险提示", "注销后个人账号、库存和对话记录将删除，家庭共享记录由家庭保留。确认注销？",
        { confirmDelete = false; vm.deleteAccount(messenger::show) }, { confirmDelete = false }, confirmText = "确认注销", danger = true)
    if (server) ServerSettingsSheet { server = false }
}

// ---------------------------------------------------------------- 烹饪记录

data class HistoryRecord(val recipe: Recipe, val time: String?)

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val records = MutableStateFlow<List<HistoryRecord>?>(null)
    val error = MutableStateFlow<String?>(null)
    init { load() }
    fun load() {
        val user = c.sessions.currentUserId ?: return
        viewModelScope.launch {
            error.value = null; records.value = null
            try {
                val rows = c.api.get("/chat-history", mapOf("user_id" to user)).asArray().orEmpty().mapNotNull { it.asObject() }
                records.value = rows.mapNotNull { row -> Recipe.tryNormalize(row["recipe"])?.let { HistoryRecord(it, row.str("time")) } }.reversed()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error.value = "请检查网络后重试（${e.userMessage()}）" }
        }
    }
}

@Composable
fun CookingHistoryScreen(navigator: Navigator) {
    val vm = cookxViewModel { HistoryViewModel(it) }
    val records by vm.records.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    SubpageScaffold("烹饪与菜谱记录", navigator::back, subtitle = "这里展示 AI 为你生成过的真实菜谱，不代表已完成烹饪。") {
        val list = records
        when {
            error != null -> item {
                CookXCard(Modifier.padding(horizontal = 16.dp).overlapHero()) {
                    EmptyState(Icons.Outlined.WarningAmber, "暂时无法加载", error.orEmpty(), tone = Tone.Danger) { PrimaryButton("重新加载", vm::load) }
                }
            }
            list == null -> item { CookXCard(Modifier.padding(horizontal = 16.dp).overlapHero()) { LoadingBlock("正在同步菜谱记录…") } }
            list.isEmpty() -> item {
                CookXCard(Modifier.padding(horizontal = 16.dp).overlapHero()) {
                    EmptyState(Icons.Outlined.History, "还没有菜谱记录", "在 AI 厨房生成菜谱后，真实记录会出现在这里。") {
                        PrimaryButton("去 AI 菜谱", { navigator.tab(Routes.CHEF) })
                    }
                }
            }
            else -> items(list.size) { index ->
                val record = list[index]
                var open by remember { mutableStateOf(false) }
                CookXCard(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).then(if (index == 0) Modifier.overlapHero() else Modifier)) {
                    Row(Modifier.clip(CookXShapes.Small).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Outlined.Restaurant, Tone.Warm, size = 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(record.recipe.dishName, style = MaterialTheme.typography.titleMedium)
                            Text(record.time?.let { "生成于 $it" } ?: "时间未记录", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
                        }
                        StatusChip("${record.recipe.steps.size} 步", Tone.Green)
                        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "查看步骤", tint = CookX.TextSecondary)
                    }
                    AnimatedVisibility(open) {
                        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            record.recipe.steps.forEachIndexed { i, step ->
                                Row {
                                    Box(Modifier.size(22.dp).clip(CookXShapes.Pill).background(CookX.Mint), contentAlignment = Alignment.Center) {
                                        Text("${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CookX.Primary)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(step.text, style = MaterialTheme.typography.bodyMedium, color = CookX.TextBody, modifier = Modifier.weight(1f))
                                }
                            }
                            LinkButton("在 AI 菜谱继续咨询 →", { container.events.pendingDish.value = record.recipe.dishName; navigator.tab(Routes.CHEF) }, color = CookX.Accent)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 关于

@Composable
fun AboutScreen(navigator: Navigator) {
    SubpageScaffold("关于 CookX", navigator::back, subtitle = "AI 智能烹饪助手") {
        item {
            CookXCard(Modifier.padding(horizontal = 16.dp).overlapHero()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(84.dp).clip(CookXShapes.Large).background(CookX.heroBrush), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.cookx_mark), null, Modifier.size(64.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Wordmark(30.sp, color = CookX.Primary)
                    Text("感知每一度 · 智烹每一步", style = MaterialTheme.typography.bodyMedium, color = CookX.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("CookX 围绕食材识别、智能库存、AI 菜谱、语音烹饪指导与实时温度感知，帮助用户更从容地完成每一餐。",
                    style = MaterialTheme.typography.bodyMedium, color = CookX.TextBody)
                Spacer(Modifier.height(14.dp))
                listOf(
                    "当前版本" to "v${BuildConfig.VERSION_NAME}（原生 Android）",
                    "数据说明" to "厨房与社区业务保存在本机，初始内容为预置示例。主动使用在线 AI 时，烹饪需求、可用食材与步骤或所选照片会发送到阿里云百炼；密钥仅加密保存在本机。",
                    "技术支持" to "当前项目尚未配置独立反馈渠道。",
                ).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(k, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(76.dp))
                        Text(v, style = MaterialTheme.typography.bodyMedium, color = CookX.TextSecondary, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
