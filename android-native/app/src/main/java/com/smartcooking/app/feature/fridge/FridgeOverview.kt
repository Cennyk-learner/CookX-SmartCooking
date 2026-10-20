package com.smartcooking.app.feature.fridge

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartcooking.app.R
import com.smartcooking.app.ui.components.CardTitle
import com.smartcooking.app.ui.components.HeroIconButton
import com.smartcooking.app.ui.components.NumberText
import com.smartcooking.app.ui.components.Pill
import com.smartcooking.app.ui.components.SoftCard
import com.smartcooking.app.ui.components.Wordmark
import com.smartcooking.app.ui.components.pressable
import com.smartcooking.app.ui.components.topInset
import com.smartcooking.app.ui.theme.CookX

data class ExpiringUi(val id: String, val name: String, val image: Int?, val daysLeft: Int?, val expired: Boolean)
data class CategoryUi(val id: String, val label: String, val kinds: Int, val image: Int?, val tint: Color)

data class FridgeOverviewUi(
    val kinds: Int = 0,
    val total: Int = 0,
    val expiringCount: Int? = null,
    val expiring: List<ExpiringUi> = emptyList(),
    val categories: List<CategoryUi> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
    val freshnessReady: Boolean = false,
)

data class FridgeActions(
    val onBell: () -> Unit = {},
    val onCapture: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onDetails: () -> Unit = {},
    val onExpiring: () -> Unit = {},
    val onItem: (String) -> Unit = {},
    val onCategory: (String) -> Unit = {},
    val onRecommend: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

private fun daysText(e: ExpiringUi) = when {
    e.expired -> "已过期"
    e.daysLeft == null -> "临期"
    e.daysLeft <= 0 -> "今天到期"
    e.daysLeft == 1 -> "明天到期，请优先使用"
    e.daysLeft == 2 -> "2 天内到期"
    else -> "还有 ${e.daysLeft} 天过期"
}

/**
 * Fridge overview after the Apple Home reference: the green "我的冰箱" card with three numbers and
 * an add button, the two most urgent items, and categories. The full list is its own page.
 */
@Composable
fun FridgeOverviewContent(ui: FridgeOverviewUi, modifier: Modifier = Modifier, actions: FridgeActions = FridgeActions(), unread: Boolean = false) {
    LazyColumn(modifier.fillMaxSize().background(CookX.Bg), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.topInset().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Wordmark(26.sp, color = CookX.Text)
                    Spacer(Modifier.weight(1f))
                    HeroIconButton(Icons.Outlined.CameraAlt, "拍照识别食材", actions.onCapture)
                    Spacer(Modifier.width(10.dp))
                    HeroIconButton(Icons.Outlined.NotificationsNone, "消息", actions.onBell, badge = unread)
                }
                Text("冰箱", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = CookX.Text, letterSpacing = (-0.8).sp, modifier = Modifier.padding(top = 6.dp))
                Text("食材新鲜，生活更健康", fontSize = 14.sp, color = CookX.TextSecondary, modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
            }
        }
        item { FridgeCard(ui, actions, Modifier.padding(horizontal = 20.dp)) }
        item { ExpiringCard(ui, actions, Modifier.padding(horizontal = 20.dp)) }
        item { CategoryCard(ui, actions, Modifier.padding(horizontal = 20.dp)) }
        item {
            SoftCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), onClick = actions.onRecommend, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(CookX.AccentBg), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = CookX.Accent, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("用这些食材能做什么", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = CookX.Text)
                        Text("按新鲜度、营养与口味综合推荐", fontSize = 12.sp, color = CookX.TextSecondary)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = CookX.TextTertiary)
                }
            }
        }
    }
}

@Composable
private fun FridgeCard(ui: FridgeOverviewUi, actions: FridgeActions, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Box(modifier.fillMaxWidth().height(248.dp).shadow(14.dp, shape, spotColor = Color(0x552E6F52), ambientColor = Color(0x332E6F52)).clip(shape).background(CookX.fridgeBrush)) {
        // Soft light from the top-left, like the reference card.
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x33FFFFFF), Color.Transparent), center = androidx.compose.ui.geometry.Offset(80f, 40f), radius = 700f)))
        Image(
            painterResource(R.drawable.fridge_illustration), "冰箱",
            Modifier.align(Alignment.TopEnd).offset(x = 18.dp, y = 10.dp).height(200.dp),
            contentScale = ContentScale.Fit,
        )
        Column(Modifier.fillMaxSize().padding(start = 20.dp, top = 18.dp, end = 16.dp, bottom = 16.dp)) {
            Text("我的冰箱", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).pressable(actions.onDetails), verticalAlignment = Alignment.CenterVertically) {
                Text("查看详情", color = Color(0xD9FFFFFF), fontSize = 13.sp)
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Color(0xD9FFFFFF), modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(0.66f), verticalAlignment = Alignment.CenterVertically) {
                Stat(Icons.Outlined.Restaurant, Color(0xFF9BE7B0), if (ui.loading) "–" else "${ui.kinds}", "种", "食材种类", Modifier.weight(1f))
                Divider()
                Stat(Icons.Outlined.Inventory2, Color(0xFF9BE7B0), if (ui.loading) "–" else "${ui.total}", "件", "库存计数", Modifier.weight(1f))
                Divider()
                Stat(Icons.Outlined.Schedule, Color(0xFFFFC56B), ui.expiringCount?.toString() ?: "–", "件", "即将过期", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x2EFFFFFF))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp)).pressable(actions.onAdd),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加食材", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.width(0.8.dp).height(46.dp).background(Color(0x33FFFFFF)))

@Composable
private fun Stat(icon: ImageVector, tint: Color, value: String, unit: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        NumberText(value, unit = unit, size = 22.sp, color = Color.White, modifier = Modifier.padding(top = 4.dp))
        Text(label, color = Color(0xCCFFFFFF), fontSize = 11.sp)
    }
}

@Composable
private fun ExpiringCard(ui: FridgeOverviewUi, actions: FridgeActions, modifier: Modifier = Modifier) {
    SoftCard(modifier.fillMaxWidth(), padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
        CardTitle("临期食材提醒", action = "查看全部", onAction = actions.onExpiring)
        Spacer(Modifier.height(6.dp))
        when {
            ui.error -> Row(Modifier.fillMaxWidth().padding(vertical = 14.dp).pressable(actions.onRetry), verticalAlignment = Alignment.CenterVertically) {
                Text("库存暂时无法读取，点按重试", fontSize = 13.5.sp, color = CookX.Danger)
            }
            ui.loading -> Text("正在核对库存与鲜度…", fontSize = 13.5.sp, color = CookX.TextSecondary, modifier = Modifier.padding(vertical = 14.dp))
            ui.expiring.isEmpty() -> Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = CookX.SuccessBright, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (ui.freshnessReady) "暂无临期食材，未知保质期的食材请补充信息" else "鲜度评估暂不可用，库存不受影响", fontSize = 13.5.sp, color = CookX.TextSecondary)
            }
            else -> ui.expiring.take(2).forEachIndexed { i, e ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable({ actions.onItem(e.id) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(CookX.SurfaceSunken), contentAlignment = Alignment.Center) {
                        if (e.image != null) Image(painterResource(e.image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(e.name.take(1), fontSize = 18.sp, color = CookX.TextSecondary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = CookX.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(daysText(e), fontSize = 12.5.sp, color = if (e.expired || (e.daysLeft ?: 9) <= 1) CookX.Danger else CookX.WarningText)
                    }
                    val red = e.expired || (e.daysLeft ?: 9) <= 1
                    Pill(
                        if (e.expired) "过期" else e.daysLeft?.let { "${it.coerceAtLeast(0)} 天" } ?: "临期",
                        fg = if (red) CookX.Danger else CookX.WarningText, bg = if (red) CookX.DangerBg else CookX.WarningBg, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (i == 0 && ui.expiring.size > 1) Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(0.6.dp).background(CookX.Border))
            }
        }
    }
}

@Composable
private fun CategoryCard(ui: FridgeOverviewUi, actions: FridgeActions, modifier: Modifier = Modifier) {
    SoftCard(modifier.fillMaxWidth(), padding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
        CardTitle("食材分类", action = "查看全部", onAction = actions.onDetails, modifier = Modifier.padding(horizontal = 2.dp))
        Spacer(Modifier.height(12.dp))
        val list = ui.categories.take(4)
        if (list.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CookX.SurfaceMuted).pressable(actions.onCapture).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.CameraAlt, null, tint = CookX.Primary)
                Spacer(Modifier.width(10.dp))
                Text("冰箱还是空的，拍一张照片识别食材", fontSize = 13.5.sp, color = CookX.TextBody)
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { c ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.tint.copy(alpha = if (CookX.isDark) 0.18f else 0.1f))
                        .pressable({ actions.onCategory(c.id) }).padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        if (c.image != null) Image(painterResource(c.image), null, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                    }
                    Text(c.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CookX.Text, modifier = Modifier.padding(top = 6.dp))
                    Text("${c.kinds} 种", fontSize = 12.sp, color = CookX.TextSecondary)
                }
            }
            repeat(4 - list.size) { Spacer(Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}
