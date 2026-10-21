package com.smartcooking.app.offline

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import com.smartcooking.app.MainActivity
import com.smartcooking.app.CookXApp
import com.smartcooking.app.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.captureRoboImage

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun recommendationsRenderNumericScoresAndEvidence() {
        compose.waitUntil(20000){compose.onAllNodesWithText("冰箱").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("冰箱").onLast().performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("我的冰箱").fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("用这些食材能做什么"))
        compose.onNodeWithText("用这些食材能做什么").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("获取智能推荐").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("获取智能推荐").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("综合推荐分").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("--").assertCountEquals(0)
        val number = SemanticsMatcher("numeric score") { node -> node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { Regex("\\d{1,3}\\.\\d{2}").matches(it.text) } }
        org.junit.Assert.assertTrue(compose.onAllNodes(number).fetchSemanticsNodes().isNotEmpty())
        compose.onRoot().captureRoboImage("screenshots/07-recommendation-scores.png")
        compose.onAllNodesWithText("评分依据").onFirst().performScrollTo().performClick()
        compose.onAllNodesWithText("未参与评分：",substring=true).onFirst().assertExists()
    }
    @Test fun mainCookingVoiceCommandsMoveStepAndRejectNegation() {
        compose.waitUntil(20000){compose.onAllNodesWithText("冰箱").fetchSemanticsNodes().isNotEmpty()}
        compose.mainClock.autoAdvance=false
        val c=(compose.activity.application as CookXApp).container
        val engine=c.cooking(c.sessions.currentUserId!!)
        compose.runOnUiThread {
            engine.start(com.smartcooking.app.data.Recipe.normalize(c.local.engine.recipes.first()),replace=true)
            engine.ready=true
            c.live.refresh()
            c.events.launchRoute.value="kitchen"
            c.events.openCooking.value=true
        }
        compose.waitUntil(10000){compose.mainClock.advanceTimeBy(100);compose.onAllNodesWithText("语音指令").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("说一句指令").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("下一步操作")
        compose.onNodeWithText("执行").performClick()
        compose.waitUntil(5000){compose.mainClock.advanceTimeBy(100);engine.state.value?.stepIndex==1}
        compose.onNode(hasSetTextAction()).performTextReplacement("不要下一步")
        compose.onNodeWithText("执行").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnUiThread { c.live.refresh() }
        compose.mainClock.advanceTimeBy(1000)
        org.junit.Assert.assertEquals(1,engine.state.value?.stepIndex)
        compose.onRoot().captureRoboImage("screenshots/05-main-voice.png")
    }
    @Test fun presetMenuAndKeySetupAreReachableWithoutLogin() {
        compose.waitUntil(20000){compose.onAllNodesWithText("冰箱").fetchSemanticsNodes().isNotEmpty()}
        compose.mainClock.autoAdvance=false
        val c=(compose.activity.application as CookXApp).container
        compose.runOnUiThread{c.events.launchRoute.value="menus"}
        compose.waitUntil(10000){compose.mainClock.advanceTimeBy(100);compose.onAllNodesWithText("接受预设并生成菜单").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("接受预设并生成菜单").performClick()
        compose.waitUntil(15000){compose.mainClock.advanceTimeBy(100);c.local.engine.rows("previews").isNotEmpty()}
        compose.waitUntil(10000){compose.mainClock.advanceTimeBy(100);compose.onAllNodesWithText("已找到可行解（尚未证明全部目标最优）").fetchSemanticsNodes().isNotEmpty()}
        org.junit.Assert.assertEquals("FEASIBLE",c.local.engine.rows("previews").last().obj("result")!!.str("solver_status"))
        compose.runOnUiThread{c.events.launchRoute.value="account-security"}
        compose.waitUntil(10000){compose.mainClock.advanceTimeBy(100);compose.onAllNodesWithText("保存并检测连接").fetchSemanticsNodes().isNotEmpty()}
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onAllNodesWithText("退出登录").assertCountEquals(0)
        compose.onRoot().captureRoboImage("screenshots/06-experience.png")
    }
    @Test fun homeFridgeKitchenProfileWithoutServer() {
        compose.waitUntil(20000){compose.onAllNodesWithText("冰箱").fetchSemanticsNodes().isNotEmpty()}
        compose.onRoot().captureRoboImage("screenshots/01-home.png")
        compose.onAllNodesWithText("冰箱").onLast().performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("我的冰箱").fetchSemanticsNodes().isNotEmpty()}
        compose.onRoot().captureRoboImage("screenshots/02-fridge.png")
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithText("厨房").onLast().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/03-kitchen.png")
        compose.onAllNodesWithText("我的").onLast().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/04-profile.png")
        compose.onAllNodesWithText("演示版",substring=true).assertCountEquals(0)
    }
    @Test fun allBusinessPagesHaveContentAndKeepFormalAppearance() {
        compose.waitUntil(20000){compose.onAllNodesWithText("冰箱").fetchSemanticsNodes().isNotEmpty()}
        compose.mainClock.autoAdvance=false
        val c=(compose.activity.application as CookXApp).container
        val routes=listOf("household" to "我们的厨房", "shopping" to "共同采购清单", "recipes?copy=&favorites=false" to "番茄炒鸡蛋", "community" to "一起晒菜", "leftovers" to "剩菜改造", "growth" to "厨艺成长", "menus" to "七日菜单", "learning" to "偏好学习", "chef" to "AI 菜谱")
        routes.forEachIndexed { i,(route,title)->
            compose.runOnUiThread{c.events.launchRoute.value=route}
            compose.waitUntil(15000){compose.mainClock.advanceTimeBy(100);compose.onAllNodesWithText(title,substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.mainClock.advanceTimeBy(1000)
            compose.onRoot().captureRoboImage("screenshots/business-$i.png")
            compose.onAllNodesWithText("演示版",substring=true).assertCountEquals(0)
            compose.onAllNodesWithText("本机保存失败",substring=true).assertCountEquals(0)
        }
    }
}
