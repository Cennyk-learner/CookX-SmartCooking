package com.smartcooking.app.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SoupKitchen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SoupKitchen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.smartcooking.app.feature.auth.LoginScreen
import com.smartcooking.app.feature.auth.RegisterScreen
import com.smartcooking.app.feature.business.CommunityScreen
import com.smartcooking.app.feature.business.GrowthScreen
import com.smartcooking.app.feature.business.HouseholdScreen
import com.smartcooking.app.feature.business.LearningScreen
import com.smartcooking.app.feature.business.LeftoversScreen
import com.smartcooking.app.feature.business.MenuPlannerScreen
import com.smartcooking.app.feature.business.RecipeLibraryScreen
import com.smartcooking.app.feature.business.ShoppingScreen
import com.smartcooking.app.feature.fridge.CaptureConfirmScreen
import com.smartcooking.app.feature.fridge.FridgeScreen
import com.smartcooking.app.feature.home.HomeScreen
import com.smartcooking.app.feature.kitchen.ChefScreen
import com.smartcooking.app.feature.kitchen.KitchenScreen
import com.smartcooking.app.feature.fridge.FreshnessCheckScreen
import com.smartcooking.app.feature.fridge.FridgeItemsScreen
import com.smartcooking.app.feature.profile.InvitesScreen
import com.smartcooking.app.feature.profile.AboutScreen
import com.smartcooking.app.feature.profile.AccountSecurityScreen
import com.smartcooking.app.feature.profile.CookingHistoryScreen
import com.smartcooking.app.feature.profile.PreferencesScreen
import com.smartcooking.app.feature.profile.ProfileScreen
import com.smartcooking.app.ui.theme.CookX
import com.smartcooking.app.ui.theme.CookXShapes

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val HOME = "home"
    const val FRIDGE = "fridge"
    const val KITCHEN = "kitchen"
    const val CHEF = "chef"
    const val FRIDGE_ITEMS = "fridge-items?filter={filter}"
    const val FRESHNESS_CHECK = "freshness-check"
    const val INVITES = "invites"
    const val PROFILE = "profile"
    const val CAPTURE_CONFIRM = "capture-confirm"
    const val HOUSEHOLD = "household"
    const val SHOPPING = "shopping"
    const val RECIPES = "recipes?copy={copy}&favorites={favorites}"
    const val COMMUNITY = "community"
    const val LEFTOVERS = "leftovers"
    const val GROWTH = "growth"
    const val MENUS = "menus"
    const val LEARNING = "learning"
    const val PREFERENCES = "preferences"
    const val ACCOUNT = "account-security"
    const val HISTORY = "cooking-history"
    const val ABOUT = "about"

    val tabs = setOf(HOME, FRIDGE, KITCHEN, CHEF, PROFILE)
    val public = setOf(LOGIN, REGISTER)

    fun fridgeItems(filter: String = "all") = "fridge-items?filter=$filter"

    fun recipes(copy: String? = null, favorites: Boolean = false) =
        "recipes?copy=${copy.orEmpty()}&favorites=$favorites"
}

/** Navigation actions shared by screens, so none of them needs the controller directly. */
class Navigator(private val nav: NavHostController) {
    fun back() { if (!nav.popBackStack()) tab(Routes.HOME) }
    fun open(route: String) = nav.navigate(route) { launchSingleTop = true }
    fun tab(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    fun home() = nav.navigate(Routes.HOME) {
        popUpTo(nav.graph.id) { inclusive = true }
        launchSingleTop = true
    }
    fun replace(route: String) = nav.navigate(route) {
        nav.currentBackStackEntry?.destination?.route?.let { popUpTo(it) { inclusive = true } }
    }
}

private val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition = {
    val from = initialState.destination.route; val to = targetState.destination.route
    if (from in Routes.tabs && to in Routes.tabs) fadeIn(tween(200))
    else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(280)) + fadeIn(tween(200))
}
private val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition = {
    val from = initialState.destination.route; val to = targetState.destination.route
    if (from in Routes.tabs && to in Routes.tabs) fadeOut(tween(160))
    else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(280), targetOffset = { it / 4 }) + fadeOut(tween(200))
}
private val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition = {
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(280), initialOffset = { it / 4 }) + fadeIn(tween(200))
}
private val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition = {
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(260)) + fadeOut(tween(200))
}

@Composable
fun CookXNavHost(nav: NavHostController, startDestination: String) {
    val navigator = remember(nav) { Navigator(nav) }
    NavHost(nav, startDestination, enterTransition = enter, exitTransition = exit, popEnterTransition = popEnter, popExitTransition = popExit) {
        composable(Routes.LOGIN) { LoginScreen(onLoggedIn = navigator::home, onRegister = { navigator.open(Routes.REGISTER) }) }
        composable(Routes.REGISTER) { RegisterScreen(onBack = navigator::back, onRegistered = navigator::back) }
        composable(Routes.HOME) { HomeScreen(navigator) }
        composable(Routes.FRIDGE) { FridgeScreen(navigator) }
        composable(Routes.KITCHEN) { KitchenScreen(navigator) }
        composable(Routes.CHEF) { ChefScreen(navigator) }
        composable(Routes.FRIDGE_ITEMS, arguments = listOf(navArgument("filter") { type = NavType.StringType; defaultValue = "all" })) { entry ->
            FridgeItemsScreen(navigator, entry.arguments?.getString("filter") ?: "all")
        }
        composable(Routes.FRESHNESS_CHECK) { FreshnessCheckScreen(navigator) }
        composable(Routes.INVITES) { InvitesScreen(navigator) }
        composable(Routes.PROFILE) { ProfileScreen(navigator) }
        composable(Routes.CAPTURE_CONFIRM) { CaptureConfirmScreen(navigator) }
        composable(Routes.HOUSEHOLD) { HouseholdScreen(navigator) }
        composable(Routes.SHOPPING) { ShoppingScreen(navigator) }
        composable(
            Routes.RECIPES,
            arguments = listOf(
                navArgument("copy") { type = NavType.StringType; defaultValue = "" },
                navArgument("favorites") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            RecipeLibraryScreen(
                navigator,
                initialCopy = entry.arguments?.getString("copy")?.takeIf { it.isNotBlank() },
                onlyFavorites = entry.arguments?.getBoolean("favorites") == true,
            )
        }
        composable(Routes.COMMUNITY) { CommunityScreen(navigator) }
        composable(Routes.LEFTOVERS) { LeftoversScreen(navigator) }
        composable(Routes.GROWTH) { GrowthScreen(navigator) }
        composable(Routes.MENUS) { MenuPlannerScreen(navigator) }
        composable(Routes.LEARNING) { LearningScreen(navigator) }
        composable(Routes.PREFERENCES) { PreferencesScreen(navigator) }
        composable(Routes.ACCOUNT) { com.smartcooking.app.feature.profile.ExperienceScreen(navigator) }
        composable(Routes.HISTORY) { CookingHistoryScreen(navigator) }
        composable(Routes.ABOUT) { AboutScreen(navigator) }
    }
}

/** Bottom tab bar (translucent, hairline edge) wired to the navigator. */
@Composable
fun CookXBottomBar(nav: NavHostController, current: String?) {
    val navigator = remember(nav) { Navigator(nav) }
    Column(Modifier.fillMaxWidth().background(CookX.TabBar)) {
        CookXTabBar(current, { navigator.tab(it) })
        Box(Modifier.fillMaxWidth().navigationBarsPadding())
    }
}
