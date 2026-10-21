package com.smartcooking.app.feature.kitchen

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DeliveryDining
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.rememberAsyncImagePainter
import com.smartcooking.app.core.LocalAppContainer
import com.smartcooking.app.data.ChatMessage
import com.smartcooking.app.data.Recipe
import com.smartcooking.app.feature.cooking.CookingState
import com.smartcooking.app.feature.live.CookingActions
import com.smartcooking.app.feature.live.CookingContent
import com.smartcooking.app.feature.live.LiveKitchenActions
import com.smartcooking.app.feature.live.LiveKitchenContent
import com.smartcooking.app.feature.live.OverheatGuide
import com.smartcooking.app.feature.live.TempChart
import com.smartcooking.app.ui.components.AccentButton
import com.smartcooking.app.ui.components.ActionRow
import com.smartcooking.app.ui.components.Banner
import com.smartcooking.app.ui.components.BannerKind
import com.smartcooking.app.ui.components.ConfirmDialog
import com.smartcooking.app.ui.components.CookXCard
import com.smartcooking.app.ui.components.CookXSheet
import com.smartcooking.app.ui.components.EmptyState
import com.smartcooking.app.ui.components.HeroIconButton
import com.smartcooking.app.ui.components.IconBadge
import com.smartcooking.app.ui.components.Kicker
import com.smartcooking.app.ui.components.LargeTitle
import com.smartcooking.app.ui.components.LocalMessenger
import com.smartcooking.app.ui.components.OutlineButton
import com.smartcooking.app.ui.components.PrimaryButton
import com.smartcooking.app.ui.components.SectionHeader
import com.smartcooking.app.ui.components.StatusChip
import com.smartcooking.app.ui.components.TonalButton
import com.smartcooking.app.ui.components.Tone
import com.smartcooking.app.ui.components.Wordmark
import com.smartcooking.app.ui.components.fieldColors
import com.smartcooking.app.ui.components.pressable
import com.smartcooking.app.ui.components.topInset
import com.smartcooking.app.ui.nav.Navigator
import com.smartcooking.app.ui.nav.Routes
import com.smartcooking.app.ui.nav.cookxSharedViewModel
import com.smartcooking.app.ui.theme.CookX
import com.smartcooking.app.ui.theme.CookXShapes
import com.smartcooking.app.ui.theme.KitchenColors

// ---------------------------------------------------------------------------- kitchen tab

/**
 * 厨房 tab: the live kitchen (Apple Weather–style) and, while cooking, the full-screen cooking
 * dashboard. Detailed tools (steps, timer, voice, reminders, adjustments, device diagnostics)
 * live in sheets so the pages stay calm.
 */
@Composable
fun KitchenScreen(navigator: Navigator) {
    val vm = cookxSharedViewModel { KitchenViewModel(it) }
    val container = LocalAppContainer.current
    val messenger = LocalMessenger.current
    val context = LocalContext.current
    val live by container.live.state.collectAsStateWithLifecycle()
    val cooking by vm.cooking.collectAsStateWithLifecycle()
    val session by vm.engine.state.collectAsStateWithLifecycle()
    val connection by vm.bluetooth.connection.collectAsStateWithLifecycle()
    val replaceTarget by vm.confirmReplace.collectAsStateWithLifecycle()
    val openCooking by container.events.openCooking.collectAsStateWithLifecycle()
    var showDevice by remember { mutableStateOf(false) }
    var showSense by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showAlert by remember { mutableStateOf(false) }
    val mainVoice by vm.voiceUi.collectAsStateWithLifecycle()
    val mainMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.listen() else vm.voiceUi.value = vm.voiceUi.value.copy(commandStatus = "麦克风权限未授予，可输入指令或点击步骤按钮")
    }

    val btPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) { showDevice = true; vm.scan()?.let(messenger::show) } else messenger.show("请允许 CookX 使用附近设备权限")
    }
    fun openDevice() {
        if (!vm.bluetooth.supported) { messenger.show("此手机不支持蓝牙"); return }
        if (!vm.bluetooth.hasPermissions()) { btPermissions.launch(vm.bluetooth.requiredPermissions()); return }
        if (!vm.bluetooth.enabled) { messenger.show("请开启手机蓝牙后继续"); return }
        showDevice = true
        vm.scan()?.let(messenger::show)
    }
    var exportSaved by remember { mutableStateOf(false) }
    var exportKind by remember { mutableStateOf("temperature") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) { messenger.show("已取消导出"); return@rememberLauncherForActivityResult }
        val json = if (exportKind == "device") vm.bluetooth.diagnostics().toString() else vm.temperature.exportJson(exportSaved)
        if (json == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
            .onSuccess { messenger.show(if (exportKind == "device") "诊断文件已导出；包含设备信息、原始温度帧及连接事件。" else "温度记录已导出") }
            .onFailure { messenger.show("文件保存失败") }
    }
    val export: (String, Boolean) -> Unit = { kind, saved ->
        exportKind = kind; exportSaved = saved
        exporter.launch(if (kind == "device") "cookx-device-diagnostics-${System.currentTimeMillis()}.json" else "cookx-temperature-${System.currentTimeMillis()}.json")
    }

    val active = session?.active == true
    val inCooking = cooking && session != null
    LaunchedEffect(openCooking) {
        if (openCooking) { container.events.openCooking.value = false; if (active && !cooking) vm.restore() }
    }
    LaunchedEffect(inCooking) { container.events.immersive.value = inCooking }
    DisposableEffect(Unit) { onDispose { container.events.immersive.value = false } }
    BackHandler(inCooking) { vm.exitCooking() }

    if (inCooking) {
        val image = session?.recipeModel?.imageUrl
        CookingContent(
            live,
            dishImage = if (image != null) rememberAsyncImagePainter(image) else null,
            actions = CookingActions(
                onBack = vm::exitCooking, onTools = { showTools = true }, onPrevious = vm::previous, onNext = vm::next,
                onAdvice = { showTools = true }, onAlert = { showAlert = true }, onTrend = { showSense = true },
            ),
            voiceContent = {
                VoiceCommandCard(mainVoice,
                    onListen = { mainMic.launch(android.Manifest.permission.RECORD_AUDIO) },
                    onCancel = vm::cancelListening, onFinish = vm::finishListening, onText = vm::setCommandText,
                    onRun = { vm.interpret(mainVoice.commandText, 1f) }, onExecute = vm::execute,
                    onDismiss = { vm.voiceUi.value = mainVoice.copy(pendingCommands = emptyList()) })
            },
        )
    } else {
        LiveKitchenContent(
            live,
            unread = false,
            actions = LiveKitchenActions(
                onBell = { container.events.openNotices.value = true; navigator.tab(Routes.PROFILE) },
                onDevice = { if (live.online) showSense = true else openDevice() },
                onTrend = { showSense = true },
                onAdvice = { if (active) vm.restore() else navigator.tab(Routes.CHEF) },
                onAlert = { showAlert = true },
            ),
        )
    }

    val showCompletion by vm.showCompletion.collectAsStateWithLifecycle()
    session?.let { s ->
        if (showCompletion) CompletionSheet(s.id, s.completed, onComplete = vm::completed, onClose = vm::closeCompletion, onContinue = { vm.showCompletion.value = false })
    }
    if (showTools && session != null) CookingToolsSheet(vm, session!!, onDevice = ::openDevice, onExport = export, onDismiss = { showTools = false })
    if (showSense) SenseSheet(vm, onConnect = { showSense = false; openDevice() }, onExport = export, onDismiss = { showSense = false })
    if (showAlert) AlertSheet(onDismiss = { showAlert = false })
    if (showDevice) {
        val devices by vm.bluetooth.devices.collectAsStateWithLifecycle()
        val scanning by vm.bluetooth.scanning.collectAsStateWithLifecycle()
        val connecting by vm.connecting.collectAsStateWithLifecycle()
        DeviceSheet(devices, scanning, connecting, vm.bluetooth.savedAddress.orEmpty(), onScan = { vm.scan()?.let(messenger::show) },
            onConnect = { address -> vm.connect(address) { msg -> messenger.show(msg); if (vm.bluetooth.connection.value.connected) showDevice = false } },
            onDismiss = { vm.stopScan(); showDevice = false })
    }
    replaceTarget?.let { r ->
        ConfirmDialog("替换烹饪", "开始新的菜谱将替换当前烹饪记录，是否继续？", { vm.start(r) }, { vm.confirmReplace.value = null }, confirmText = "替换", dismissText = "保留当前")
    }
    LaunchedEffect(connection.state) { if (connection.errorCode.isNotEmpty() && connection.state.id == "error") messenger.show(connection.message) }
}

/** Red over-temperature guidance with mute and voice actions. */
@Composable
private fun AlertSheet(onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val live by container.live.state.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF171514),
        contentColor = KitchenColors.Text,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        OverheatGuide(
            live, muted = live.muted,
            onMute = container.live::acknowledge,
            onSpeak = container.live::speakNow,
            onClose = { container.live.acknowledge(); onDismiss() },
            modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp),
        )
    }
}

/** Temperature details: the full chart, the rules/experimental engine panel and replay. */
@Composable
private fun SenseSheet(vm: KitchenViewModel, onConnect: () -> Unit, onExport: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val live by container.live.state.collectAsStateWithLifecycle()
    val connection by vm.bluetooth.connection.collectAsStateWithLifecycle()
    val latest by vm.bluetooth.latest.collectAsStateWithLifecycle()
    val assessment by vm.temperature.assessment.collectAsStateWithLifecycle()
    val history by vm.temperature.history.collectAsStateWithLifecycle()
    val prediction by vm.temperature.prediction.collectAsStateWithLifecycle()
    val modelState by vm.temperature.modelState.collectAsStateWithLifecycle()
    val experimental by vm.temperature.experimental.collectAsStateWithLifecycle()
    val replaying by vm.temperature.replaying.collectAsStateWithLifecycle()
    val storageMessage by vm.temperature.storageMessage.collectAsStateWithLifecycle()
    val connecting by vm.connecting.collectAsStateWithLifecycle()
    val deviceMessage by vm.deviceMessage.collectAsStateWithLifecycle()
    CookXSheet("温度趋势与设备", onDismiss, subtitle = "最近 6 分钟的锅温；阶段与建议仅作参考，请以实际情况为准。") {
        Box(Modifier.fillMaxWidth().clip(CookXShapes.Card).background(Color(0xFF1B1A18)).padding(14.dp)) {
            TempChart(live.history, live.now, height = 150.dp, alert = live.alert != null)
        }
        Spacer(Modifier.height(12.dp))
        SensePanel(connection, latest, assessment, history, prediction, modelState, experimental, replaying, storageMessage, connecting,
            onConnect = onConnect, onDisconnect = vm::disconnect, onEvent = vm.temperature::confirm, onExperimental = vm.temperature::setExperimental,
            onReplay = vm.temperature::startReplay, onStopReplay = vm.temperature::stopReplay, onExport = { onExport("temperature", it) })
        Spacer(Modifier.height(12.dp))
        ActionRow {
            TonalButton("重新核对设备状态", vm::refreshDevice, icon = Icons.Outlined.Settings)
            OutlineButton("导出设备诊断", { onExport("device", false) })
        }
        if (deviceMessage.isNotBlank()) Text(deviceMessage, style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Everything the cooking dashboard leaves out: step text, timer, voice, steps, reminders, adjustments. */
@Composable
private fun CookingToolsSheet(vm: KitchenViewModel, session: CookingState, onDevice: () -> Unit, onExport: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val now by vm.now.collectAsStateWithLifecycle()
    val playback by vm.voice.playback.collectAsStateWithLifecycle()
    val progress by vm.voice.progress.collectAsStateWithLifecycle()
    val voiceUi by vm.voiceUi.collectAsStateWithLifecycle()
    val sessionMessage by vm.sessionMessage.collectAsStateWithLifecycle()
    val remindersOn by vm.remindersOn.collectAsStateWithLifecycle()
    val notificationMessage by vm.notificationMessage.collectAsStateWithLifecycle()
    val checkError by vm.checkError.collectAsStateWithLifecycle()
    val preview by vm.adjustmentPreview.collectAsStateWithLifecycle()
    val adjustmentError by vm.adjustmentError.collectAsStateWithLifecycle()
    val remaining = remember(now, session) { (vm.engine.remaining() + 999) / 1000 }
    val steps = session.recipeModel.steps
    val duration = steps[session.stepIndex].durationSeconds?.coerceAtLeast(1.0) ?: 1.0
    val stepPct = ((duration - remaining) / duration).coerceIn(0.0, 1.0)
    val overall = minOf(100, Math.round((session.stepIndex + stepPct) / steps.size * 100).toInt())

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.listen() else vm.voiceUi.value = vm.voiceUi.value.copy(commandStatus = "麦克风权限未授予，请使用文字或按钮") }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> vm.setReminders(true, ok) }
    val exact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.onExactSettingsReturned() }

    CookXSheet("烹饪工具", onDismiss, subtitle = "${session.recipeModel.dishName} · 第 ${session.stepIndex + 1}/${steps.size} 步") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (sessionMessage.isNotBlank()) Banner(sessionMessage, BannerKind.Warning)
            CurrentStepCard(session, remaining, playback, progress, voiceUi.message, vm::toggleVoice, vm::runStep, vm::runCloudStep, vm::previous, vm::next, vm::pauseTimer, vm::resumeTimer, vm::setManualTimer)
            VoiceCommandCard(voiceUi,
                onListen = { if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) vm.listen() else mic.launch(Manifest.permission.RECORD_AUDIO) },
                onCancel = vm::cancelListening, onFinish = vm::finishListening, onText = vm::setCommandText, onRun = { vm.interpret(voiceUi.commandText, 1f) }, onExecute = vm::execute,
                onDismiss = { vm.voiceUi.value = voiceUi.copy(pendingCommands = emptyList()) })
            StepsTrack(session, overall)
            RemindersCard(session, remindersOn, notificationMessage, checkError,
                onToggle = { on ->
                    val granted = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (on && !granted) notify.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setReminders(on, true)
                },
                onExact = { vm.exactSettingsIntent()?.let { runCatching { exact.launch(it) } } ?: vm.onExactSettingsReturned() },
                onRetryCheck = vm::checkInventory, onDismiss = vm::dismissReminder)
            AdjustmentsCard(session, preview, adjustmentError, vm.engine::canUndo, vm::previewAdjustment, vm::applyAdjustment, { vm.adjustmentPreview.value = null }, vm::undoAdjustment)
            SupportCards(session)
            CookXCard(Modifier.fillMaxWidth()) {
                SectionHeader("CookX Sense", icon = Icons.Outlined.Bluetooth, subtitle = "连接后实时显示锅温；退到后台时在通知栏继续显示。")
                Spacer(Modifier.height(10.dp))
                ActionRow {
                    TonalButton("扫描与连接设备", onDevice)
                    OutlineButton("重新核对设备状态", vm::refreshDevice)
                    OutlineButton("导出设备诊断", { onExport("device", false) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("收起烹饪导航", { onDismiss(); vm.exitCooking() }, Modifier.weight(1f), color = CookX.TextSecondary)
                PrimaryButton("完成与库存核对", { onDismiss(); vm.showCompletion.value = true }, Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------- AI recipe tab

/** AI 菜谱 tab: the recipe conversation. Starting a recipe switches to the kitchen tab. */
@Composable
fun ChefScreen(navigator: Navigator) {
    val vm = cookxSharedViewModel { KitchenViewModel(it) }
    val container = LocalAppContainer.current
    val messenger = LocalMessenger.current
    val chat by vm.chat.collectAsStateWithLifecycle()
    val session by vm.engine.state.collectAsStateWithLifecycle()
    val replaceTarget by vm.confirmReplace.collectAsStateWithLifecycle()
    val prompt by container.events.chefPrompt.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.started.collect { navigator.tab(Routes.KITCHEN) } }
    LaunchedEffect(prompt) { prompt?.let { container.events.chefPrompt.value = null; vm.send(it) } }

    ChatView(vm, chat, session, navigator, onClear = { confirmClear = true }, onResume = { container.events.openCooking.value = true; navigator.tab(Routes.KITCHEN) })

    val showCompletion by vm.showCompletion.collectAsStateWithLifecycle()
    session?.let { s ->
        if (showCompletion) CompletionSheet(s.id, s.completed, onComplete = vm::completed, onClose = vm::closeCompletion, onContinue = { vm.showCompletion.value = false })
    }
    replaceTarget?.let { r ->
        ConfirmDialog("替换烹饪", "开始新的菜谱将替换当前烹饪记录，是否继续？", { vm.start(r) }, { vm.confirmReplace.value = null }, confirmText = "替换", dismissText = "保留当前")
    }
    if (confirmClear) ConfirmDialog("清空对话", "将删除服务器上保存的菜谱对话记录，已收藏的菜谱不受影响。", {
        confirmClear = false; vm.clearChat(messenger::show)
    }, { confirmClear = false }, confirmText = "清空")
}

@Composable
private fun ChatView(vm: KitchenViewModel, chat: ChatState, session: CookingState?, navigator: Navigator, onClear: () -> Unit, onResume: () -> Unit) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val latest = chat.messages.lastOrNull { it.recipe != null }?.recipe
    var deliveryDish by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(chat.messages.size, chat.loading) { if (chat.messages.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    Column(Modifier.fillMaxSize().background(CookX.Bg)) {
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.topInset().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Wordmark(26.sp, color = CookX.Text)
                        Spacer(Modifier.weight(1f))
                        HeroIconButton(Icons.Outlined.DeleteSweep, "清空对话", onClear)
                    }
                    LargeTitle("AI 菜谱", "说出想吃的，CookX 结合冰箱与口味生成菜谱", Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickLink("菜谱库", Icons.AutoMirrored.Outlined.MenuBook) { navigator.open(Routes.recipes()) }
                        QuickLink("我的收藏", Icons.Outlined.FavoriteBorder) { navigator.open(Routes.recipes(favorites = true)) }
                        QuickLink("七日菜单", Icons.Outlined.CalendarMonth) { navigator.open(Routes.MENUS) }
                    }
                }
            }
            if (session?.active == true) item {
                Banner("「${session.recipeModel.dishName}」还没做完，计时按实际经过时间核对。", BannerKind.Pending, Modifier.padding(horizontal = 16.dp), title = "可恢复烹饪") {
                    TonalButton("回到厨房继续", onResume, tone = Tone.Warm)
                }
            }
            if (session?.completed == true && session.consumption != "confirmed") item {
                Banner("上次烹饪已完成，可核对实际使用的食材。", BannerKind.Info, Modifier.padding(horizontal = 16.dp)) {
                    TonalButton("查看完成与库存核对", { vm.showCompletion.value = true })
                }
            }
            chat.error?.let { e -> item {
                Banner(e, BannerKind.Error, Modifier.padding(horizontal = 16.dp)) { OutlineButton("重新生成菜谱", { vm.send(chat.lastPrompt) }, enabled = chat.lastPrompt.isNotBlank()) }
            } }
            if (latest != null) item { ReadyCard(latest) { vm.requestStart(latest) } }
            else item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("试试这样问", style = MaterialTheme.typography.labelLarge, color = CookX.TextSecondary)
                    ActionRow(Modifier.padding(top = 8.dp)) {
                        listOf("用冰箱里的食材做晚餐", "30 分钟内的快手菜", "清淡少油的家常菜").forEach { p ->
                            Box(
                                Modifier.clip(RoundedCornerShape(50)).background(CookX.AccentBg).pressable({ if (!chat.loading) vm.send(p) }).padding(horizontal = 14.dp, vertical = 8.dp),
                            ) { Text(p, color = CookX.AccentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
            items(chat.messages.size) { i -> MessageBubble(chat.messages[i], onStart = { r -> vm.requestStart(r) }, onMarket = { openMarket(context) }, onDelivery = { deliveryDish = it }) }
            if (chat.loading) item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Restaurant, Tone.Warm, size = 34.dp, iconSize = 17.dp, shape = CircleShape)
                    Spacer(Modifier.width(10.dp))
                    Row(Modifier.clip(CookXShapes.Tile).background(CookX.Surface).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        LoadingDots(); Text("  CookX 正在生成菜谱…", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(CookX.Surface).imePadding().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f), placeholder = { Text("告诉 CookX 你想做什么…", color = CookX.TextTertiary) },
                shape = RoundedCornerShape(24.dp), colors = fieldColors(), maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(if (input.isNotBlank() && !chat.loading) CookX.accentBrush else androidx.compose.ui.graphics.SolidColor(CookX.Accent.copy(alpha = 0.4f)))
                    .pressable({ if (input.isNotBlank() && !chat.loading) { vm.send(input.trim()); input = "" } }),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Outlined.Send, "发送", tint = Color.White) }
        }
    }
    deliveryDish?.let { dish ->
        ConfirmDialog("外卖下单", "这就去为您搜索「$dish」的外卖吗？", {
            deliveryDish = null
            openDelivery(context, dish) { messenger.show(it) }
        }, { deliveryDish = null }, confirmText = "出发", dismissText = "再想想")
    }
}

@Composable
private fun QuickLink(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(CookX.Surface).border(1.dp, CookX.Border, RoundedCornerShape(50)).pressable(onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = CookX.Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CookX.Text)
    }
}

@Composable
private fun ReadyCard(recipe: Recipe, onStart: () -> Unit) {
    CookXCard(Modifier.padding(horizontal = 16.dp)) {
        Kicker("READY TO COOK", color = CookX.Accent)
        Text(recipe.dishName, style = MaterialTheme.typography.titleLarge, color = CookX.Text)
        Text("${recipe.steps.size} 个步骤 · 开始后在厨房页跟随锅温一步步进行", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
        Spacer(Modifier.height(12.dp))
        AccentButton("开始烹饪", onStart, Modifier.fillMaxWidth(), icon = Icons.Outlined.PlayCircle)
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onStart: (Recipe) -> Unit, onMarket: () -> Unit, onDelivery: (String) -> Unit) {
    val mine = message.role == "user"
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!mine) { IconBadge(Icons.Outlined.Restaurant, Tone.Warm, size = 34.dp, iconSize = 17.dp, shape = CircleShape); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.widthIn(max = 320.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (message.content.isNotBlank()) Box(
                Modifier.clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                    .background(if (mine) CookX.Accent else CookX.Surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) { Text(message.content, color = if (mine) Color.White else CookX.Text, style = MaterialTheme.typography.bodyMedium) }
            message.recipe?.let { r -> RecipeCard(r, { onStart(r) }, onMarket, { onDelivery(r.dishName) }) }
        }
    }
}

@Composable
private fun RecipeCard(recipe: Recipe, onStart: () -> Unit, onMarket: () -> Unit, onDelivery: () -> Unit) {
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(CookXShapes.Card).background(CookX.Surface).border(1.dp, CookX.Border, CookXShapes.Card)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Kicker("COOKX RECIPE", color = CookX.Accent)
                Text(recipe.dishName, color = CookX.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            AccentButton("开始指导", onStart, icon = Icons.Outlined.Mic)
        }
        Column(Modifier.padding(14.dp)) {
            if (recipe.ingredients.isNotEmpty()) {
                ActionRow { recipe.ingredients.forEach { StatusChip("${it.item} ${it.amount.orEmpty()}".trim(), Tone.Green) } }
                Spacer(Modifier.height(12.dp))
            }
            recipe.steps.take(3).forEachIndexed { i, s ->
                Row(Modifier.padding(bottom = 8.dp)) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(CookX.AccentBg), contentAlignment = Alignment.Center) { Text("${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CookX.Accent) }
                    Spacer(Modifier.width(8.dp))
                    Text(s.text, style = MaterialTheme.typography.bodySmall, color = CookX.TextBody, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (recipe.steps.size > 3) Text("还有 ${recipe.steps.size - 3} 步，开始指导后逐步展示", fontSize = 11.sp, color = CookX.TextTertiary)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("买食材", onMarket, Modifier.weight(1f), icon = Icons.Outlined.Storefront)
                OutlineButton("点外卖", onDelivery, Modifier.weight(1f), icon = Icons.Outlined.DeliveryDining, color = CookX.Accent)
            }
        }
    }
}

private fun openMarket(context: android.content.Context) {
    val scheme = Intent(Intent.ACTION_VIEW, Uri.parse("androidamap://arroundpoi?sourceApplication=smart_cooking&keywords=${Uri.encode("菜市场")}&dev=0"))
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://uri.amap.com/search?keyword=${Uri.encode("菜市场")}&view=map&src=smart_cooking&coordinate=gaode"))
    runCatching { context.startActivity(scheme) }.onFailure { runCatching { context.startActivity(web) } }
}

private fun openDelivery(context: android.content.Context, dish: String, toast: (String) -> Unit) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("imeituan://www.meituan.com/s/${Uri.encode(dish)}"))
    runCatching { context.startActivity(app) }.onFailure {
        (context.getSystemService(android.content.ClipboardManager::class.java))?.setPrimaryClip(android.content.ClipData.newPlainText("dish", dish))
        toast("请在美团外卖中搜索「$dish」（已复制菜名）")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://h5.waimai.meituan.com/waimai/mindex/home"))) }
    }
}
