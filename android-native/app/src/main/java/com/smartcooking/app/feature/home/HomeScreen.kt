package com.smartcooking.app.feature.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Recycling
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartcooking.app.core.LocalAppContainer
import com.smartcooking.app.core.Time
import com.smartcooking.app.core.str
import com.smartcooking.app.data.FreshnessState
import com.smartcooking.app.data.detail
import com.smartcooking.app.ui.components.CookXSheet
import com.smartcooking.app.ui.nav.Navigator
import com.smartcooking.app.ui.nav.Routes
import com.smartcooking.app.ui.nav.cookxViewModel
import kotlin.math.ceil

/** Every collaborative module, reachable from the home grid button and from "我的". */
val cookxServices = listOf(
    ServiceItem("capture", "拍照识别", Icons.Outlined.CameraAlt, Color(0xFF2E9C7B)),
    ServiceItem(Routes.HOUSEHOLD, "家庭冰箱", Icons.Outlined.Groups, Color(0xFF3F8A62)),
    ServiceItem(Routes.SHOPPING, "共同采购", Icons.Outlined.ShoppingCart, Color(0xFFE9862F)),
    ServiceItem(Routes.recipes(), "菜谱库", Icons.AutoMirrored.Outlined.MenuBook, Color(0xFFF0701E)),
    ServiceItem(Routes.COMMUNITY, "一起晒菜", Icons.Outlined.Forum, Color(0xFFE5483A)),
    ServiceItem(Routes.LEFTOVERS, "剩菜改造", Icons.Outlined.Recycling, Color(0xFF4D8B69)),
    ServiceItem(Routes.GROWTH, "厨艺成长", Icons.Outlined.EmojiEvents, Color(0xFFE9A23B)),
    ServiceItem(Routes.MENUS, "七日菜单", Icons.Outlined.CalendarMonth, Color(0xFF5B7FD6)),
    ServiceItem(Routes.LEARNING, "偏好学习", Icons.Outlined.Psychology, Color(0xFF8E6CD8)),
    ServiceItem(Routes.FRESHNESS_CHECK, "鲜度检测", Icons.Outlined.Spa, Color(0xFF24A148)),
)

@Composable
fun HomeScreen(navigator: Navigator) {
    val vm = cookxViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    val session by container.sessions.session.collectAsStateWithLifecycle()
    val live by container.live.state.collectAsStateWithLifecycle()
    var services by remember { mutableStateOf(false) }

    val expiry = remember(state.inventory, state.freshness) {
        val items = state.expiring
        items.firstOrNull()?.let { first ->
            val d = state.freshness.detail(first.id)
            val days = d?.daysRemaining
            ExpiryHint(
                name = first.info.cn,
                text = d?.expiryLabel ?: "临期，请查看详情",
                count = items.size,
                urgent = days == null || days <= 1,
            )
        }
    }
    val recipe = state.latestRecipe
    val ui = HomeUi(
        greeting = Time.greeting(),
        name = session?.displayName.orEmpty(),
        unread = state.unread,
        pick = recipe?.let {
            TodayPick(it.dishName, (it.totalSeconds / 60).takeIf { m -> m >= 1 }?.let { m -> Math.round(m).toInt() }, "${if(it.json.str("content_source") == "built_in") "内置菜谱" else "最近菜谱"} · ${it.steps.size} 步", true)
        },
        expiry = expiry,
        fridgeEmpty = !state.loading && !state.error && state.inventory.isEmpty(),
        loading = state.loading || state.freshness is FreshnessState.Loading,
    )
    HomeContent(
        ui, live,
        actions = HomeActions(
            onBell = { container.events.openNotices.value = true; navigator.tab(Routes.PROFILE) },
            onServices = { services = true },
            onSense = { container.events.openCooking.value = live.session != null; navigator.tab(Routes.KITCHEN) },
            onPick = {
                if (recipe != null) container.events.recipeDraft.value = recipe
                else container.events.chefPrompt.value = "用冰箱里的食材推荐一道菜"
                navigator.tab(Routes.CHEF)
            },
            onAllRecipes = { navigator.open(Routes.recipes()) },
            onExpiry = { if (ui.fridgeEmpty) { container.events.startRecognition.value = true; navigator.tab(Routes.FRIDGE) } else navigator.open(Routes.fridgeItems("expiring")) },
        ),
    )
    if (services) CookXSheet("全部服务", { services = false }, subtitle = "家庭、采购、社区与成长，一处直达") {
        ServicesGrid(cookxServices.filter { it.route != Routes.LEARNING }, onOpen = { route ->
            services = false
            if (route == "capture") { container.events.startRecognition.value = true; navigator.tab(Routes.FRIDGE) } else navigator.open(route)
        })
    }
}
