package com.smartcooking.app.feature.profile

import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Palette
import com.smartcooking.app.ui.components.GroupedList
import com.smartcooking.app.ui.components.GroupedRow
import com.smartcooking.app.ui.components.NumberText
import com.smartcooking.app.ui.components.SectionLabel
import com.smartcooking.app.ui.components.SoftCard
import com.smartcooking.app.ui.theme.Appearance
import androidx.compose.foundation.layout.Arrangement

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.smartcooking.app.core.LocalAppContainer
import com.smartcooking.app.core.UserSession
import com.smartcooking.app.core.str
import com.smartcooking.app.data.loadUploadJpeg
import com.smartcooking.app.device.DeviceConnection
import com.smartcooking.app.ui.components.AnimatedBanner
import com.smartcooking.app.ui.components.Avatar
import com.smartcooking.app.ui.components.BannerKind
import com.smartcooking.app.ui.components.Choice
import com.smartcooking.app.ui.components.ConfirmDialog
import com.smartcooking.app.ui.components.CookXCard
import com.smartcooking.app.ui.components.CookXSheet
import com.smartcooking.app.ui.components.CookXTextField
import com.smartcooking.app.ui.components.DarkPanel
import com.smartcooking.app.ui.components.EmptyState
import com.smartcooking.app.ui.components.HeroHeader
import com.smartcooking.app.ui.components.HeroIconButton
import com.smartcooking.app.ui.components.IconBadge
import com.smartcooking.app.ui.components.Kicker
import com.smartcooking.app.ui.components.ListRow
import com.smartcooking.app.ui.components.LocalMessenger
import com.smartcooking.app.ui.components.OutlineButton
import com.smartcooking.app.ui.components.PrimaryButton
import com.smartcooking.app.ui.components.SectionHeader
import com.smartcooking.app.ui.components.SegmentedTabs
import com.smartcooking.app.ui.components.StatusChip
import com.smartcooking.app.ui.components.TonalButton
import com.smartcooking.app.ui.components.Tone
import com.smartcooking.app.ui.components.overlapHero
import com.smartcooking.app.ui.components.pressable
import com.smartcooking.app.ui.nav.Navigator
import com.smartcooking.app.ui.nav.Routes
import com.smartcooking.app.ui.nav.cookxViewModel
import com.smartcooking.app.ui.theme.CookX
import com.smartcooking.app.ui.theme.CookXShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProfileScreen(navigator: Navigator) {
    val vm = cookxViewModel { ProfileViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    val messenger = LocalMessenger.current
    val session by container.sessions.session.collectAsStateWithLifecycle()
    val connection by container.bluetooth.connection.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showNotices by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var appearance by remember { mutableStateOf(false) }
    val openNotices by container.events.openNotices.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (container.events.editProfile.value) { container.events.editProfile.value = false; editing = true }
    }
    androidx.compose.runtime.LaunchedEffect(openNotices) {
        if (openNotices) { container.events.openNotices.value = false; showNotices = true; vm.loadNotices() }
    }

    ExperienceProfile(
        session = session,
        state = state,
        onNotices = { showNotices = true; scope.launch { vm.loadNotices() } },
        onOpen = navigator::open,
        onKitchen = { navigator.tab(Routes.KITCHEN) },
    )
    if (appearance) CookXSheet("外观", { appearance = false }, subtitle = "默认浅色；实时厨房与烹饪页面始终使用深色摄影风格。") {
        Appearance.options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clip(CookXShapes.Tile).pressable({
                    Appearance.preference = value
                    container.store.put(Appearance.KEY, value)
                }).padding(vertical = 14.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = CookX.Text, modifier = Modifier.weight(1f))
                androidx.compose.material3.RadioButton(Appearance.preference == value, onClick = {
                    Appearance.preference = value
                    container.store.put(Appearance.KEY, value)
                })
            }
        }
    }
    if (showNotices) NotificationsSheet(state, { showNotices = false }, { id -> vm.markRead(id, messenger::show) },
        onOpen = { n -> vm.markRead(n.id, {}); showNotices = false; if (n.entityType == "shopping") navigator.open(Routes.SHOPPING) else { container.events.focusInventory.value = n.entityId; navigator.tab(Routes.FRIDGE) } },
        onArchive = vm::archive, onRefresh = { scope.launch { vm.loadNotices() } }, onMore = { vm.moreNotices() })
    if (editing) session?.let { EditProfileSheet(it, vm, state) { editing = false } }
    if (confirmLogout) ConfirmDialog("退出登录", "确定要退出登录吗？", { confirmLogout = false; vm.logout(messenger::show) }, { confirmLogout = false }, confirmText = "退出")
}

@Composable
fun ProfileContent(
    session: UserSession?,
    state: ProfileState,
    connection: DeviceConnection,
    savedDevice: Boolean,
    onNotices: () -> Unit,
    onEdit: () -> Unit,
    onOpen: (String) -> Unit,
    onKitchen: () -> Unit,
    onLogout: () -> Unit,
    onAppearance: () -> Unit = {},
) {
    val user = session?.user
    LazyColumn(Modifier.fillMaxSize().background(CookX.Bg), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            HeroHeader(section = "我的", actions = { HeroIconButton(Icons.Outlined.NotificationsNone, "打开消息中心", onNotices, badge = state.unread > 0) }, bottomOverlap = 0)
        }
        item {
            SoftCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), onClick = onEdit, padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Avatar(user?.str("avatar"), 64.dp)
                        Box(Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(CookX.Accent), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Edit, "编辑资料", tint = Color.White, modifier = Modifier.size(12.dp))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(session?.displayName?.ifBlank { null } ?: "未设置昵称", color = CookX.Text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text(listOfNotNull(user?.str("username")?.let { "@$it" }, if (session?.isAdmin == true) "管理员" else null).joinToString(" · ").ifBlank { "CookX 会员" }, color = CookX.TextSecondary, fontSize = 13.sp)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = CookX.TextTertiary)
                }
                val stats = listOfNotNull(
                    state.recipeCount?.let { "$it" to "菜谱记录" },
                    state.notices?.let { "${state.unread}" to "未读消息" },
                    usageDays(user)?.let { "$it" to "使用天数" },
                )
                if (stats.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth().clip(CookXShapes.Tile).background(CookX.SurfaceMuted).padding(vertical = 12.dp)) {
                        stats.forEachIndexed { i, (value, label) ->
                            if (i > 0) Box(Modifier.width(0.8.dp).height(36.dp).background(CookX.Border).align(Alignment.CenterVertically))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                NumberText(value, size = 22.sp, color = CookX.Text)
                                Text(label, fontSize = 12.sp, color = CookX.TextSecondary)
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                SectionLabel("我的")
                GroupedList {
                    GroupedRow("我的收藏", Icons.Outlined.StarOutline, Color(0xFFE9A23B), subtitle = "收藏的菜谱", onClick = { onOpen(Routes.recipes(favorites = true)) })
                    GroupedRow("烹饪记录", Icons.Outlined.History, Color(0xFFF0701E), subtitle = "AI 菜谱生成与烹饪历史", onClick = { onOpen(Routes.HISTORY) })
                    GroupedRow("偏好设置", Icons.Outlined.Settings, Color(0xFF3F8A62), subtitle = "口味、辣度与食材禁忌", onClick = { onOpen(Routes.PREFERENCES) })
                    GroupedRow("消息中心", Icons.Outlined.NotificationsNone, Color(0xFFE5483A), subtitle = "食材临期与系统通知", badge = state.unread, divider = false, onClick = onNotices)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                SectionLabel("家庭与社区")
                GroupedList {
                    val services = com.smartcooking.app.feature.home.cookxServices.filter { it.route != "capture" }
                    services.forEachIndexed { i, s -> GroupedRow(s.label, s.icon, s.tint, divider = i < services.size - 1, onClick = { onOpen(s.route) }) }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                SectionLabel("设备与外观")
                GroupedList {
                    GroupedRow("CookX Sense 设备", Icons.Outlined.Bluetooth, Color(0xFF5B7FD6), subtitle = if (savedDevice) "已保存测温设备" else "尚未连接测温设备", value = connection.label, onClick = onKitchen)
                    GroupedRow("外观", Icons.Outlined.Palette, Color(0xFF8E6CD8), value = Appearance.options.firstOrNull { it.first == Appearance.preference }?.second ?: "浅色", divider = false, onClick = onAppearance)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                SectionLabel("账户")
                GroupedList {
                    GroupedRow("账号与安全", Icons.Outlined.Lock, Color(0xFF7A807C), subtitle = "资料、后端地址与登录状态", onClick = { onOpen(Routes.ACCOUNT) })
                    if (session?.isAdmin == true) GroupedRow("邀请内测成员", Icons.Outlined.GroupAdd, Color(0xFF24A148), subtitle = "生成一次性注册邀请码", onClick = { onOpen(Routes.INVITES) })
                    GroupedRow("关于 CookX", Icons.Outlined.Info, Color(0xFF7A807C), subtitle = "版本与隐私说明", divider = false, onClick = { onOpen(Routes.ABOUT) })
                }
            }
        }
        item {
            AnimatedBanner(state.error, BannerKind.Warning, Modifier.padding(horizontal = 20.dp))
            Box(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(CookXShapes.Tile).background(CookX.Surface).pressable(onLogout).padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("退出登录", color = CookX.Danger, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Text("CookX · 感知每一度 · 智烹每一步", fontSize = 11.sp, color = CookX.TextTertiary, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

private fun Modifier.offsetUp() = this.overlapHero(26.dp)

@Composable
private fun NotificationsSheet(state: ProfileState, onDismiss: () -> Unit, onRead: (String) -> Unit, onOpen: (Notice) -> Unit = {}, onArchive: (String) -> Unit = {}, onRefresh: () -> Unit = {}, onMore: () -> Unit = {}) {
    var tab by remember { mutableStateOf("all") }
    CookXSheet("消息中心", onDismiss, subtitle = "食材临期与系统通知") {
        SegmentedTabs(listOf(Choice("all", "全部"), Choice("expire", "食材临期"), Choice("system", "系统通知")), tab, { tab = it })
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalButton("全部已读", { onRead("") })
            TonalButton("刷新", onRefresh)
        }
        val list = state.notices.orEmpty().filter { tab == "all" || it.type == tab }
        if (list.isEmpty()) EmptyState(Icons.Outlined.NotificationsNone, "暂无消息", "有新的提醒时会出现在这里")
        list.forEach { n ->
            Column(
                Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(CookXShapes.Tile).background(if (n.read) CookX.SurfaceMuted else CookX.Surface)
                    .border(1.dp, if (n.read) CookX.Border else CookX.Accent.copy(alpha = 0.3f), CookXShapes.Tile).padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), color = if (n.read) CookX.TextSecondary else CookX.Text)
                    StatusChip(if (n.type == "expire") "食材临期" else "系统通知", if (n.type == "expire") Tone.Warm else Tone.Green)
                }
                Text(n.content, style = MaterialTheme.typography.bodySmall, color = CookX.TextBody, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n.time, fontSize = 11.sp, color = CookX.TextTertiary, modifier = Modifier.weight(1f))
                    if (!n.read) TonalButton("标记已读", { onRead(n.id) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (n.entityType != null) TonalButton(if(n.entityType == "shopping") "查看采购" else "查看食材并处理", { onOpen(n) })
                    TonalButton("归档", { onArchive(n.id) })
                }
            }
        }
        if (state.hasMoreNotices) OutlineButton("加载更多消息", onMore, Modifier.fillMaxWidth())
    }
}

@Composable
private fun EditProfileSheet(session: UserSession, vm: ProfileViewModel, state: ProfileState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val scope = rememberCoroutineScope()
    var nickname by remember { mutableStateOf(session.user.str("nickname").orEmpty()) }
    var phone by remember { mutableStateOf(session.user.str("phone").orEmpty()) }
    var avatar by remember { mutableStateOf(session.user.str("avatar")) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = runCatching { withContext(Dispatchers.IO) { loadUploadJpeg(context, uri, 800) } }.getOrNull()
            if (bytes == null) { messenger.show("图片读取失败"); return@launch }
            if (bytes.size > 5 * 1024 * 1024) { messenger.show("图片大小不能超过 5MB"); return@launch }
            vm.uploadAvatar(bytes, { avatar = it; messenger.show("头像预览已更新，请点击保存") }, { messenger.show("上传失败：$it") })
        }
    }
    CookXSheet("编辑个人信息", onDismiss, dismissible = !state.updating) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(avatar, 72.dp)
            Spacer(Modifier.width(16.dp))
            TonalButton(if (state.uploading) "上传中…" else "更换头像", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, loading = state.uploading)
        }
        Spacer(Modifier.height(16.dp))
        CookXTextField(nickname, { nickname = it; error = null }, "昵称", clearable = true)
        Spacer(Modifier.height(10.dp))
        CookXTextField(phone, { phone = it.filter(Char::isDigit).take(11); error = null }, "手机号", keyboardType = KeyboardType.Phone, clearable = true)
        AnimatedBanner(error, BannerKind.Error, Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(16.dp))
        PrimaryButton("保存修改", {
            when {
                nickname.isBlank() -> error = "请输入昵称"
                phone.isNotEmpty() && !Regex("^1[3-9]\\d{9}$").matches(phone) -> error = "请输入正确的手机号"
                else -> vm.updateProfile(nickname.trim(), phone, avatar) { problem ->
                    if (problem == null) { messenger.show("更新成功"); onDismiss() } else error = problem
                }
            }
        }, Modifier.fillMaxWidth(), loading = state.updating)
    }
}
