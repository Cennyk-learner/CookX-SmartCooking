package com.smartcooking.app.feature.kitchen

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartcooking.app.core.Time
import com.smartcooking.app.feature.cooking.Adjustment
import com.smartcooking.app.feature.cooking.CookingState
import com.smartcooking.app.feature.cooking.Playback
import com.smartcooking.app.feature.cooking.Reminder
import com.smartcooking.app.feature.cooking.VoiceCommands
import com.smartcooking.app.feature.cooking.adjustmentRules
import com.smartcooking.app.ui.components.AccentButton
import com.smartcooking.app.ui.components.ActionRow
import com.smartcooking.app.ui.components.Banner
import com.smartcooking.app.ui.components.BannerKind
import com.smartcooking.app.ui.components.CookXCard
import com.smartcooking.app.ui.components.CookXTextField
import com.smartcooking.app.ui.components.IconBadge
import com.smartcooking.app.ui.components.LinkButton
import com.smartcooking.app.ui.components.OutlineButton
import com.smartcooking.app.ui.components.PrimaryButton
import com.smartcooking.app.ui.components.SectionHeader
import com.smartcooking.app.ui.components.StatusChip
import com.smartcooking.app.ui.components.SwitchRow
import com.smartcooking.app.ui.components.TonalButton
import com.smartcooking.app.ui.components.Tone
import com.smartcooking.app.ui.components.pressable
import com.smartcooking.app.ui.theme.CookX
import com.smartcooking.app.ui.theme.CookXShapes

/** The focus card while cooking: step text, countdown ring, voice bar and step navigation. */
@Composable
fun CurrentStepCard(
    session: CookingState,
    remainingSeconds: Long,
    playback: Playback,
    voiceProgress: Pair<Float, Float>,
    voiceMessage: String,
    onToggleVoice: () -> Unit,
    onReplay: () -> Unit,
    onCloud: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onManual: (String) -> Unit,
) {
    val recipe = session.recipeModel
    val step = recipe.steps[session.stepIndex]
    val duration = step.durationSeconds ?: 0.0
    val paused = session.timer.deadline == null
    val hasTimer = duration > 0 || session.timer.round > 0
    val isLast = session.stepIndex >= recipe.steps.size - 1
    var manual by remember { mutableStateOf("60") }

    CookXCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("第 ${session.stepIndex + 1} / ${recipe.steps.size} 步", color = CookX.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(step.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 2.dp))
            }
            TimerRing(remainingSeconds, session.timer.remainingMs.takeIf { paused } ?: ((duration * 1000).toLong()), hasTimer, paused)
        }
        Spacer(Modifier.height(12.dp))
        Text(step.text, style = MaterialTheme.typography.bodyLarge, color = CookX.TextBody)
        val meta = listOfNotNull(step.heat?.let { "火力" to it }, step.temperatureText?.let { "目标温度" to it })
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { meta.forEach { (k, v) -> StatusChip("$k · $v", Tone.Warm) } }
        }
        step.tip?.let {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(CookXShapes.Tile).background(CookX.WarningBg).padding(12.dp)) {
                Icon(Icons.Outlined.Lightbulb, null, tint = Color(0xFFB7791F), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("CookX 提醒：$it", style = MaterialTheme.typography.bodySmall, color = CookX.TextBody)
            }
        }
        Spacer(Modifier.height(14.dp))
        VoiceBar(playback, voiceProgress, onToggleVoice, onReplay, onCloud)
        if (voiceMessage.isNotBlank()) Text(voiceMessage, style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (paused) TonalButton("继续计时", onResume, icon = Icons.Outlined.PlayArrow) else TonalButton("暂停计时", onPause, icon = Icons.Outlined.Pause, tone = Tone.Neutral)
            CookXTextField(manual, { manual = it.filter(Char::isDigit).take(5) }, "手动计时（秒）", Modifier.weight(1f), keyboardType = KeyboardType.Number)
            TonalButton("开始", { onManual(manual) }, icon = Icons.Outlined.Timer, tone = Tone.Warm)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton("上一步", onPrev, Modifier.weight(1f), icon = Icons.AutoMirrored.Outlined.ArrowBack, enabled = session.stepIndex > 0)
            if (isLast) AccentButton("完成烹饪", onNext, Modifier.weight(1.4f), icon = Icons.Outlined.DoneAll)
            else PrimaryButton("下一步", onNext, Modifier.weight(1.4f), trailingIcon = Icons.AutoMirrored.Outlined.ArrowForward)
        }
    }
}

@Composable
private fun TimerRing(remainingSeconds: Long, totalMs: Long, hasTimer: Boolean, paused: Boolean) {
    val total = (totalMs / 1000f).coerceAtLeast(1f)
    val fraction = if (hasTimer) (remainingSeconds / total).coerceIn(0f, 1f) else 0f
    val color = when { !hasTimer -> CookX.TextTertiary; remainingSeconds <= 0 -> CookX.Danger; paused -> CookX.Gold; else -> CookX.Accent }
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(84.dp)) {
            val stroke = 7.dp.toPx()
            drawArc(CookX.Mint, 0f, 360f, false, style = Stroke(stroke), topLeft = Offset(stroke / 2, stroke / 2), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke))
            drawArc(color, -90f, 360f * fraction, false, style = Stroke(stroke, cap = StrokeCap.Round), topLeft = Offset(stroke / 2, stroke / 2), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (hasTimer) Time.formatTimer(remainingSeconds) else "--:--", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = CookX.Text)
            Text(when { !hasTimer -> "无时长"; remainingSeconds <= 0 -> "已到时"; paused -> "已暂停"; else -> "剩余" }, fontSize = 10.sp, color = CookX.TextSecondary)
        }
    }
}

@Composable
fun VoiceBar(playback: Playback, progress: Pair<Float, Float>, onToggle: () -> Unit, onReplay: () -> Unit, onCloud: () -> Unit) {
    val playing = playback == Playback.PLAYING
    Column(Modifier.fillMaxWidth().clip(CookXShapes.Tile).background(if (playing) CookX.Primary else CookX.Mint).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VoiceWave(playing, if (playing) Color.White else CookX.Primary)
            Spacer(Modifier.width(10.dp))
            Text(
                when (playback) { Playback.LOADING -> "正在准备本步骤语音"; Playback.PLAYING -> "正在播报本步骤"; Playback.PAUSED -> "本步骤播报已暂停"; else -> "本步骤语音待播放" },
                color = if (playing) Color.White else CookX.Primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            if (progress.second > 0) Text(Time.formatTimer((progress.second - progress.first).toLong()), color = if (playing) Color.White else CookX.Primary, fontSize = 12.sp)
        }
        if (progress.second > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { (progress.first / progress.second).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(4.dp).clip(CookXShapes.Pill),
                color = if (playing) Color.White else CookX.Primary, trackColor = Color.White.copy(alpha = 0.3f), drawStopIndicator = {})
        }
        Spacer(Modifier.height(10.dp))
        ActionRow {
            val tone = if (playing) Tone.OnDark else Tone.Green
            TonalButton(when (playback) { Playback.PLAYING -> "暂停播报"; Playback.PAUSED -> "继续播报"; else -> "播放本步骤" }, onToggle,
                icon = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, tone = if (playing) tone else Tone.Neutral)
            TonalButton("重新播报", onReplay, icon = Icons.Outlined.Replay, tone = if (playing) tone else Tone.Neutral)
            TonalButton("在线播报", onCloud, icon = Icons.Outlined.CloudQueue, tone = if (playing) tone else Tone.Neutral)
        }
    }
}

@Composable
private fun VoiceWave(active: Boolean, color: Color) {
    val t = rememberInfiniteTransition(label = "wave")
    Row(Modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(4) { i ->
            val h by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(420 + i * 90), RepeatMode.Reverse), label = "bar$i")
            Box(Modifier.width(3.dp).fillMaxHeight(if (active) h else 0.35f).clip(CookXShapes.Pill).background(color))
        }
    }
}

/** Progress track of all steps. */
@Composable
fun StepsTrack(session: CookingState, overall: Int) {
    val steps = session.recipeModel.steps
    CookXCard(Modifier.fillMaxWidth()) {
        SectionHeader("烹饪步骤") { Text("$overall%", color = CookX.Accent, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(12.dp))
        steps.forEachIndexed { index, step ->
            val done = index < session.stepIndex; val active = index == session.stepIndex
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(when { done -> CookX.Success; active -> CookX.Accent; else -> CookX.Mint }), contentAlignment = Alignment.Center) {
                        if (done) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                        else Text("${index + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (active) Color.White else CookX.Primary)
                    }
                    if (index < steps.size - 1) Box(Modifier.width(2.dp).height(22.dp).background(if (done) CookX.Success else CookX.Border))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.padding(top = 3.dp)) {
                    Text(step.title, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp, color = if (done) CookX.TextSecondary else CookX.Text)
                    step.durationSeconds?.takeIf { it > 0 }?.let { Text("约 ${Time.formatTimer(it.toLong())}", fontSize = 11.sp, color = CookX.TextTertiary) }
                }
            }
        }
    }
}

@Composable
fun VoiceCommandCard(ui: VoiceUi, onListen: () -> Unit, onCancel: () -> Unit, onFinish: () -> Unit, onText: (String) -> Unit, onRun: () -> Unit, onExecute: (String) -> Unit, onDismiss: () -> Unit) {
    CookXCard(Modifier.fillMaxWidth()) {
        SectionHeader("语音指令", subtitle = "下一步 · 上一步 · 重复播报 · 开始/暂停/继续计时 · 查询温度", icon = Icons.Outlined.Mic)
        Text("点击麦克风，等提示就绪后说一句；说完稍停或点「说完了」。", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val pulse = rememberInfiniteTransition(label = "mic").animateFloat(1f, 1.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(if (ui.listening) CookX.accentBrush else androidx.compose.ui.graphics.SolidColor(CookX.Primary))
                    .pressable(if (ui.listening) onCancel else onListen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (ui.listening) Icons.Outlined.Close else Icons.Outlined.Mic, if (ui.listening) "取消识别" else "说一句指令", tint = Color.White,
                    modifier = Modifier.size(if (ui.listening) (24 * pulse.value).dp else 24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(ui.commandStatus, style = MaterialTheme.typography.bodyMedium, color = CookX.TextBody, modifier = Modifier.weight(1f))
        }
        if (ui.listening) {
            Spacer(Modifier.height(10.dp))
            if (ui.capturing) {
                Text("麦克风输入", style = MaterialTheme.typography.labelSmall, color = CookX.TextSecondary)
                LinearProgressIndicator(progress = { ui.inputLevel }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = CookX.Primary)
                ActionRow {
                    PrimaryButton("说完了", onFinish)
                    OutlineButton("取消", onCancel, color = CookX.TextSecondary)
                }
            } else {
                Text("准备和识别期间无需说话；可点击上方 × 取消。", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CookXTextField(ui.commandText, onText, "文字指令", Modifier.weight(1f), placeholder = "例如：暂停计时", onImeAction = onRun, imeAction = androidx.compose.ui.text.input.ImeAction.Done)
            Spacer(Modifier.width(8.dp))
            TonalButton("执行", onRun)
        }
        if (ui.pendingCommands.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("请确认要执行的操作：", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary)
            ActionRow(Modifier.padding(top = 6.dp)) {
                ui.pendingCommands.forEach { cmd -> TonalButton(VoiceCommands.labels[cmd].orEmpty(), { onExecute(cmd) }, tone = Tone.Warm) }
                OutlineButton("取消", onDismiss, color = CookX.TextSecondary)
            }
        }
    }
}

@Composable
fun RemindersCard(
    session: CookingState,
    enabled: Boolean,
    message: String,
    checkError: String?,
    onToggle: (Boolean) -> Unit,
    onExact: () -> Unit,
    onRetryCheck: () -> Unit,
    onDismiss: (String) -> Unit,
) {
    CookXCard(Modifier.fillMaxWidth()) {
        SwitchRow("烹饪提醒", enabled, onToggle, subtitle = message)
        LinkButton("设置精确计时权限", onExact, color = CookX.Primary)
        checkError?.let { Banner(it, BannerKind.Warning, Modifier.padding(vertical = 6.dp)) { OutlineButton("重试食材检查", onRetryCheck) } }
        val list = session.reminders.sortedByDescending { it.at }
        list.forEach { ReminderRow(it, onDismiss) }
        if (list.isEmpty()) Text("暂无提醒；计时结束、食材缺失或临期、温度异常时会出现在这里。", style = MaterialTheme.typography.bodySmall, color = CookX.TextTertiary)
    }
}

@Composable
private fun ReminderRow(r: Reminder, onDismiss: (String) -> Unit) {
    val tone = when (r.risk) { "danger" -> Tone.Danger; "warning" -> Tone.Gold; else -> Tone.Green }
    Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(CookXShapes.Small).background(if (r.dismissed) CookX.SurfaceMuted else tone.bg).padding(10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(r.text, style = MaterialTheme.typography.bodySmall, color = if (r.dismissed) CookX.TextTertiary else CookX.TextBody)
            Text(Time.clockSeconds(r.at) + if (r.dismissed) " · 已关闭提示" else "", fontSize = 10.5.sp, color = CookX.TextTertiary)
        }
        if (!r.dismissed) Icon(Icons.Outlined.Close, "关闭此提示", tint = CookX.TextSecondary, modifier = Modifier.size(22.dp).clip(CircleShape).pressable({ onDismiss(r.key) }).padding(3.dp))
    }
}

@Composable
fun AdjustmentsCard(
    session: CookingState,
    preview: Adjustment?,
    error: String?,
    canUndo: (Adjustment) -> Boolean,
    onPreview: (String) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onUndo: (String) -> Unit,
) {
    CookXCard(Modifier.fillMaxWidth()) {
        SectionHeader("遇到情况，先检查再调整", subtitle = "菜谱版本 ${session.recipeVersion}；当前和已进入的步骤保留原文")
        Spacer(Modifier.height(12.dp))
        ActionRow { adjustmentRules.forEach { (key, rule) -> TonalButton(rule.label, { onPreview(key) }, tone = Tone.Neutral) } }
        error?.let { Banner(it, BannerKind.Error, Modifier.padding(top = 10.dp)) }
        preview?.let { p ->
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(CookXShapes.Tile).border(1.dp, CookX.Accent.copy(alpha = 0.3f), CookXShapes.Tile).padding(12.dp)) {
                Text("${p.label} · 修改预览", fontWeight = FontWeight.Bold, color = CookX.Accent)
                Text(p.reason, style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                p.patches.forEach {
                    Text("第 ${it.index + 1} 步原文：${it.before}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    Text("确认后：${it.after}", style = MaterialTheme.typography.bodySmall, color = CookX.Primary)
                }
                if (p.patches.isEmpty()) Text("没有可修改的后续步骤。${p.advice} 可使用上方手动计时。", style = MaterialTheme.typography.bodySmall)
                ActionRow(Modifier.padding(top = 10.dp)) {
                    if (p.patches.isNotEmpty()) PrimaryButton("确认调整", onApply)
                    OutlineButton("取消预览", onCancel, color = CookX.TextSecondary)
                }
            }
        }
        session.adjustments.forEach { r ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${r.label} · 版本 ${r.version}${if (r.undone) " · 已撤销" else ""}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (canUndo(r)) LinkButton("撤销", { onUndo(r.id) }, color = CookX.Accent)
            }
        }
    }
}

@Composable
fun SupportCards(session: CookingState) {
    val recipe = session.recipeModel
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (recipe.ingredients.isNotEmpty()) CookXCard(Modifier.fillMaxWidth()) {
            SectionHeader("食材清单")
            Spacer(Modifier.height(10.dp))
            ActionRow { recipe.ingredients.forEach { StatusChip("${it.item} ${it.amount.orEmpty()}".trim(), Tone.Green) } }
        }
        val reminder = recipe.reminder
        val nutrition = recipe.nutritionSummary
        if (reminder != null || nutrition.isNotBlank()) CookXCard(Modifier.fillMaxWidth()) {
            reminder?.let {
                Row { IconBadge(Icons.Outlined.Lightbulb, Tone.Gold, size = 34.dp, iconSize = 17.dp); Spacer(Modifier.width(10.dp)); Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
            }
            if (nutrition.isNotBlank()) Text("营养信息：$nutrition", style = MaterialTheme.typography.bodySmall, color = CookX.TextSecondary, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun LoadingDots() = CircularProgressIndicator(Modifier.size(18.dp), color = CookX.Accent, strokeWidth = 2.dp)
