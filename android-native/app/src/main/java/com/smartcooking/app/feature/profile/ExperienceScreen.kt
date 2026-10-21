package com.smartcooking.app.feature.profile

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smartcooking.app.core.*
import com.smartcooking.app.ui.components.*
import com.smartcooking.app.ui.nav.*
import com.smartcooking.app.ui.theme.CookX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExperienceScreen(navigator: Navigator) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(c.local.gateway.configured) }
    var usingBuiltIn by remember { mutableStateOf(c.local.gateway.usingBuiltIn) }
    var editing by remember { mutableStateOf(!configured) }
    var baseUrl by remember { mutableStateOf(c.local.gateway.compatibleBaseUrl) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    SubpageScaffold("体验管理", navigator::back, subtitle = "手机直接联网，无需电脑后端。") {
        item {
            CookXCard(Modifier.padding(horizontal = 16.dp)) {
                Text("AI 菜谱 · Kimi K2.6", style = MaterialTheme.typography.titleLarge)
                Text(if (c.local.gateway.recipeConfigured) "已内置菜谱服务，前往 AI 菜谱输入需求即可使用。"
                    else "菜谱服务未启用，仍可查看内置菜谱。", color = CookX.TextSecondary)
                Text("根据可用库存与最近菜谱生成完整步骤，支持继续调整口味和份量。仅提交需求时调用，关闭思考模式并限制输出；按 Kimi 账号用量计费。", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
                LinkButton("打开 AI 菜谱 →", { navigator.tab(Routes.CHEF) })
            }
        }
        item {
            CookXCard(Modifier.padding(horizontal = 16.dp)) {
                Text("图片识别与语音 · Qwen", style = MaterialTheme.typography.titleLarge)
                Text("识别食材、语音指令和在线播报使用百炼服务。库存、菜谱收藏与烹饪记录保存在本机。", color = CookX.TextSecondary)
                Spacer(Modifier.height(16.dp))
                Text(when { usingBuiltIn -> "已内置在线服务，安装后即可使用"; configured -> "服务配置已加密保存在此手机"; else -> "在线服务未启用" }, color = CookX.TextSecondary)
                Text("仅主动使用时发送所选照片、指令录音或播报文字；按百炼账号用量计费。这里更换的配置只用于 Qwen，不影响 Kimi 菜谱密钥。", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
                LinkButton(if (editing) "收起服务配置" else "更换服务配置", { if (!busy) editing = !editing })
                if (editing) {
                LinkButton("打开官方 API Key 获取教程", {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://help.aliyun.com/zh/model-studio/get-api-key"))) }
                        .onFailure { status = "请在浏览器打开阿里云百炼，选择北京地域并创建 API Key" }
                })
                CookXTextField(code, { code = it }, if (configured) "更换 API Key（留空保留）" else "百炼 API Key", password = true)
                CookXTextField(baseUrl, { baseUrl = it }, "兼容接口地址（与 Key 同一业务空间）")
                Text("更换服务时一起填写 Key 和对应的 /compatible-mode/v1 地址。", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (editing) "保存并检测连接" else "检测连接", {
                    if (!busy) scope.launch {
                        busy = true
                        status = runCatching {
                            withContext(Dispatchers.IO) {
                                if (editing && code.isNotBlank()) c.local.gateway.configure(code, baseUrl)
                                else if (editing && baseUrl.trim().trimEnd('/') != c.local.gateway.compatibleBaseUrl) error("更换接口地址时请同时填写对应的 API Key")
                                c.local.gateway.health()
                                "Qwen 已连接。图片识别可在冰箱页拍照验证；此检测不调用 Kimi。"
                            }
                        }.getOrElse { "连接未成功：${it.userMessage()}。本地功能仍可使用。" }
                        configured = c.local.gateway.configured
                        usingBuiltIn = c.local.gateway.usingBuiltIn
                        baseUrl = c.local.gateway.compatibleBaseUrl
                        code = ""
                        busy = false
                    }
                }, Modifier.fillMaxWidth(), loading = busy)
                status?.let { Text(it, color = CookX.TextSecondary, modifier = Modifier.padding(top = 12.dp)) }
                if (configured) OutlineButton("停用在线服务", { runCatching { c.local.gateway.clear(); configured = false; usingBuiltIn = false; status = "已停用在线服务，本地功能仍可使用" }.onFailure { status = it.userMessage() } }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy)
                if (c.local.gateway.hasBuiltIn && !usingBuiltIn) OutlineButton("恢复内置服务", { runCatching { c.local.gateway.restoreBuiltIn(); configured = true; usingBuiltIn = true; editing = false; code = ""; baseUrl = c.local.gateway.compatibleBaseUrl; status = "已恢复内置服务，可检测连接" }.onFailure { status = it.userMessage() } }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy)
            }
        }
        item {
            CookXCard(Modifier.padding(horizontal = 16.dp)) {
                Text("恢复初始厨房", style = MaterialTheme.typography.titleLarge)
                Text("恢复预置食材、菜单、采购和社区内容，清除本机后续操作。在线服务配置会保留。", color = CookX.TextSecondary)
                Spacer(Modifier.height(12.dp))
                OutlineButton("恢复初始内容", { reset = true }, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
    }
    if (reset) ConfirmDialog("恢复初始厨房", "这会清除本机修改、烹饪进度与草稿，并重新载入预置内容。是否继续？", {
        reset = false
        scope.launch {
            busy = true
            runCatching {
                c.voice.stopAll(); c.notifications.cancelAll(); c.events.clearAll()
                withContext(Dispatchers.IO) { c.local.reset() }
                (context.applicationContext as com.smartcooking.app.CookXApp).reloadContainer()
                (context as? androidx.activity.ComponentActivity)?.viewModelStore?.clear()
                (context as? Activity)?.recreate()
            }.onFailure { status = it.userMessage() }
            busy = false
        }
    }, { reset = false }, confirmText = "确认恢复")
}

@Composable
fun ExperienceProfile(session: UserSession?, state: ProfileState, onNotices: () -> Unit, onOpen: (String) -> Unit, onKitchen: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(CookX.Bg), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { HeroHeader(section = "我的厨房", actions = { HeroIconButton(Icons.Outlined.NotificationsNone, "打开消息中心", onNotices, badge = state.unread > 0) }, bottomOverlap = 0) }
        item {
            CookXCard(Modifier.padding(horizontal = 20.dp)) {
                Text("林小厨的厨房", style = MaterialTheme.typography.headlineSmall)
                Text("从冰箱里的食材，到餐桌上的每一道菜。", color = CookX.TextSecondary)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("${state.recipeCount ?: "—"} 道菜谱记录", color = CookX.Primary)
                    Text("${if(state.notices == null) "—" else state.unread} 条食材与协作提醒", color = CookX.TextSecondary)
                }
            }
        }
        item {
            CookXCard(Modifier.padding(horizontal = 20.dp)) {
                ListRow("我的收藏", icon = Icons.Outlined.StarOutline, onClick = { onOpen(Routes.recipes(favorites = true)) })
                ListRow("烹饪记录", icon = Icons.Outlined.History, onClick = { onOpen(Routes.HISTORY) })
                ListRow("消息中心", icon = Icons.Outlined.NotificationsNone, onClick = onNotices)
                ListRow("体验管理", subtitle = "AI 密钥配置与恢复初始厨房", icon = Icons.Outlined.Settings, onClick = { onOpen(Routes.ACCOUNT) })
            }
        }
        item {
            CookXCard(Modifier.padding(horizontal = 20.dp)) {
                com.smartcooking.app.feature.home.cookxServices.filter { it.route != "capture" && it.route != Routes.LEARNING }.forEach { s ->
                    ListRow(s.label, icon = s.icon, onClick = { onOpen(s.route) })
                }
            }
        }
        item {
            CookXCard(Modifier.padding(horizontal = 20.dp)) {
                ListRow("CookX Sense", subtitle = "连接测温设备与查看温度趋势", icon = Icons.Outlined.Bluetooth, onClick = onKitchen)
                ListRow("关于 CookX", icon = Icons.Outlined.Info, onClick = { onOpen(Routes.ABOUT) })
            }
        }
    }
}
