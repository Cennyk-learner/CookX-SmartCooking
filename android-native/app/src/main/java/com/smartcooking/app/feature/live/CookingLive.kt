package com.smartcooking.app.feature.live

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartcooking.app.R
import com.smartcooking.app.ui.components.pressable
import com.smartcooking.app.ui.components.topInset
import com.smartcooking.app.ui.theme.KitchenColors
import com.smartcooking.app.ui.theme.NumericFont
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

data class CookingActions(
    val onBack: () -> Unit = {},
    val onTools: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onAdvice: () -> Unit = {},
    val onAlert: () -> Unit = {},
    val onTrend: () -> Unit = {},
)

/** A short extrapolation of the heating curve toward the target, drawn dashed. */
fun predictCurve(state: LiveState): List<LivePoint> {
    val last = state.history.lastOrNull() ?: return emptyList()
    val rate = LiveRules.heatingRate(state.history) ?: return emptyList()
    val ceiling = state.target?.let { (it.first + it.second) / 2 } ?: (last.t + rate * 90)
    return (1..6).map { i ->
        val dt = i * 15_000L
        val raw = last.t + rate * dt / 1000.0
        val t = if (rate >= 0) minOf(raw, maxOf(ceiling, last.t)) else maxOf(raw, 40.0)
        LivePoint(last.at + dt, t)
    }
}

/**
 * Step-by-step cooking dashboard after the Apple Weather reference (right): dish, arc gauge with
 * target and phase, the countdown, a live curve with prediction, the next stage, the total estimate
 * and CookX's advice. Step controls float at the bottom; everything else lives behind "…".
 */
@Composable
fun CookingContent(state: LiveState, modifier: Modifier = Modifier, dishImage: Painter? = null, actions: CookingActions = CookingActions(), animate: Boolean = true, voiceContent: @Composable () -> Unit = {}) {
    val session = state.session
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(0 to 0) }
    val alert = state.alert
    val danger = alert?.level == AlertLevel.Danger
    Box(modifier.fillMaxSize().background(KitchenColors.Base).onGloballyPositioned { origin = it.positionInRoot(); size = it.size.width to it.size.height }) {
        StageBackdrop(state.backdrop, cooking = true, alert = danger, animate = animate)
        CompositionLocalProvider(LocalGlassSource provides GlassSource(state.backdrop, origin, size.first, size.second, cooking = true)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).topInset().padding(horizontal = 16.dp).padding(bottom = 96.dp)) {
                TopBar(actions)
                RecipeCard(session, dishImage)
                Spacer(Modifier.height(10.dp))
                voiceContent()
                Spacer(Modifier.height(6.dp))
                GaugeBlock(state, actions)
                EtaBlock(state)
                Spacer(Modifier.height(14.dp))
                GlassSurface(Modifier.fillMaxWidth().pressable(actions.onTrend)) {
                    Column(Modifier.padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)) {
                        Text("实时温度曲线", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        TempChart(state.history, state.now, Modifier.padding(top = 8.dp), prediction = predictCurve(state), alert = danger, height = 84.dp, showValue = false)
                        ChartLegend(Modifier.padding(top = 6.dp), alert = danger)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val eta = state.eta
                    val remaining = session?.remainingMs ?: 0
                    InfoTile(
                        Icons.Outlined.LocalFireDepartment, KitchenColors.Heat, "下一阶段",
                        session?.nextTitle?.takeIf { it.isNotBlank() } ?: "完成烹饪",
                        when {
                            eta != null && eta > 0 -> "预计 ${max(1, eta.roundToInt())} 秒后"
                            remaining > 0 -> "本步剩余 ${LiveRules.clock(remaining / 1000.0)}"
                            else -> "按提示进入下一步"
                        },
                        Modifier.weight(1f),
                    )
                    val totalLeft = (session?.let { it.remainingMs / 1000.0 + it.futureSeconds } ?: 0.0)
                    InfoTile(
                        Icons.Outlined.AccessTime, Color.White, "本次烹饪预计",
                        if (totalLeft > 0) "约 ${max(1, ceil(totalLeft / 60).toInt())} 分钟" else "时长未提供",
                        "根据步骤时长估算",
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                CookingAdvice(state, if (alert != null) actions.onAlert else actions.onAdvice)
            }
            StepBar(session, actions, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun TopBar(actions: CookingActions) {
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        Box(Modifier.align(Alignment.CenterStart).size(40.dp).clip(CircleShape).pressable(actions.onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "收起烹饪导航", tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text("烹饪中", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
        Box(Modifier.align(Alignment.CenterEnd).size(40.dp).clip(CircleShape).pressable(actions.onTools), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.MoreHoriz, "烹饪工具", tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun RecipeCard(session: LiveSession?, dishImage: Painter?) {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 18.dp, top = 16.dp, bottom = 16.dp, end = 120.dp)) {
                Text(session?.dish ?: "当前菜谱", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(
                    Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(Color(0x4D6B3A18))
                        .border(1.dp, Color(0x66FFA05A), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
                ) { Text("CookX AI 实时引导", color = Color(0xFFFFC08A), fontSize = 12.sp) }
                if (session != null) Text("第 ${session.stepIndex + 1} / ${session.total} 步 · ${session.stepTitle}", color = KitchenColors.TextMuted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Image(
            dishImage ?: painterResource(R.drawable.today_dish), null,
            Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-10).dp).size(118.dp)
                .shadow(16.dp, CircleShape, spotColor = Color(0x99000000)).clip(CircleShape).border(3.dp, Color(0x33FFFFFF), CircleShape),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun GaugeBlock(state: LiveState, actions: CookingActions) {
    val alert = state.alert
    val danger = alert?.level == AlertLevel.Danger
    ArcGauge(LiveRules.gaugeFraction(state.temperature), Modifier.fillMaxWidth().height(250.dp), alert = danger) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 6.dp)) {
            Text("当前温度", color = KitchenColors.TextMuted, fontSize = 15.sp)
            val t = state.temperature
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    t?.roundToInt()?.toString() ?: "--", color = if (danger) Color(0xFFFF5A4A) else if (t == null) Color(0x80FFFFFF) else Color.White,
                    fontFamily = NumericFont, fontWeight = FontWeight.SemiBold, fontSize = 64.sp, lineHeight = 66.sp, letterSpacing = (-2).sp,
                )
                if (t != null) Text("°C", color = if (danger) Color(0xFFFF5A4A) else Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 3.dp, top = 8.dp))
            }
            val target = state.target
            Text(
                if (target != null) "目标 ${((target.first + target.second) / 2).roundToInt()}°C" else "本步未设定目标温度",
                color = KitchenColors.TextMuted, fontSize = 14.sp,
            )
            val chip = alert?.title ?: state.stage?.let { "${it.label}阶段" } ?: "等待读数"
            Box(
                Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50))
                    .background(if (danger) Color(0xE6C8261C) else Color(0xE6B8531A))
                    .border(1.dp, if (danger) Color(0x99FF8A7A) else Color(0x99FFA05A), RoundedCornerShape(50))
                    .pressable(if (alert != null) actions.onAlert else null)
                    .padding(horizontal = 22.dp, vertical = 6.dp),
            ) { Text(chip, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun EtaBlock(state: LiveState) {
    val session = state.session
    val eta = state.eta
    val remaining = session?.remainingMs ?: 0
    val (label, value, caption) = when {
        state.alert != null -> Triple("本步剩余", if (remaining > 0) LiveRules.clock(remaining / 1000.0) else "--:--", "先调小火力，温度回落后再继续")
        eta != null && eta > 0 -> Triple("预计还需", LiveRules.clock(eta), "即将达到食材下锅温度")
        eta == 0.0 && state.target != null -> Triple("温度已就绪", LiveRules.clock(remaining / 1000.0).takeIf { remaining > 0 } ?: "00:00", if (remaining > 0) "本步剩余时间" else "可以放入食材")
        remaining > 0 -> Triple(if (session?.paused == true) "计时已暂停" else "本步剩余", LiveRules.clock(remaining / 1000.0), session?.stepTitle ?: "")
        else -> Triple("本步时长", "--:--", if (session?.timed == false) "菜谱未提供时长，可在工具中手动计时" else "等待开始")
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = KitchenColors.TextMuted, fontSize = 14.sp)
        Text(value, color = Color.White, fontFamily = NumericFont, fontWeight = FontWeight.SemiBold, fontSize = 46.sp, lineHeight = 50.sp)
        if (caption.isNotBlank()) Text(caption, color = KitchenColors.TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun InfoTile(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, label: String, title: String, sub: String, modifier: Modifier = Modifier) {
    GlassSurface(modifier, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, color = KitchenColors.TextMuted, fontSize = 11.5.sp)
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = KitchenColors.TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CookingAdvice(state: LiveState, onClick: () -> Unit) {
    val alert = state.alert
    val danger = alert?.level == AlertLevel.Danger
    val session = state.session
    val text = alert?.let { "${it.text}，点按查看处理建议" } ?: state.advice?.text ?: session?.stepText ?: "按步骤进行，完成后点下一步"
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (danger) Color(0xFFFFE9E5) else KitchenColors.Cream)
            .pressable(onClick).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(if (danger) Color(0xFFFFD2CA) else Color(0xFFFFE3C7)), contentAlignment = Alignment.Center) {
            Icon(if (danger) Icons.Outlined.WarningAmber else Icons.Outlined.Restaurant, null, tint = if (danger) Color(0xFFD9342A) else Color(0xFFF08A24), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (danger) "锅温预警" else "CookX 建议", color = if (danger) Color(0xFFB4281E) else KitchenColors.CreamMuted, fontSize = 12.5.sp)
            Text(text, color = KitchenColors.CreamText, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = KitchenColors.CreamMuted, modifier = Modifier.size(22.dp))
    }
}

/** Floating glass bar: previous · step n/N · next (or finish). */
@Composable
private fun StepBar(session: LiveSession?, actions: CookingActions, modifier: Modifier = Modifier) {
    if (session == null) return
    val last = session.stepIndex >= session.total - 1
    GlassSurface(
        modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp), tint = Color(0xB3141312),
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.height(48.dp).clip(RoundedCornerShape(18.dp)).pressable(if (session.stepIndex > 0) actions.onPrevious else null).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.ChevronLeft, null, tint = if (session.stepIndex > 0) Color.White else Color(0x4DFFFFFF), modifier = Modifier.size(22.dp))
                Text("上一步", color = if (session.stepIndex > 0) Color.White else Color(0x4DFFFFFF), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("第 ${session.stepIndex + 1} / ${session.total} 步", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(session.stepTitle, color = KitchenColors.TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier.height(48.dp).clip(RoundedCornerShape(18.dp)).background(KitchenColors.Heat).pressable(actions.onNext).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (last) "完成烹饪" else "下一步", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Icon(if (last) Icons.Outlined.CheckCircle else Icons.Outlined.ChevronRight, null, tint = Color.White, modifier = Modifier.padding(start = 2.dp).size(20.dp))
            }
        }
    }
}

/** Keeps content clear of the floating step bar in lists that reuse the cooking layout. */
val StepBarPadding = PaddingValues(bottom = 96.dp)
