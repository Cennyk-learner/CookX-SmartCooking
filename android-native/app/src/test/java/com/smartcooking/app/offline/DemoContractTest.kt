package com.smartcooking.app.offline

import com.smartcooking.app.core.*
import com.smartcooking.app.feature.cooking.VoiceCommands
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class DemoContractTest {
    private val recipes = AppJson.parseToJsonElement(File("src/main/assets/offline/recipes.json").readText()).asArray()!!.mapNotNull { it.asObject() }
    private var now = Instant.parse("2026-10-20T02:00:00Z").toEpochMilli()
    private val e = LocalEngine(recipes, null, {}, { now })
    private val auth = mapOf("Authorization" to "Bearer ${e.initialSession().str("access_token")}")
    private fun call(method: String, path: String, body: JsonElement = EmptyObject, query: Map<String,String> = emptyMap()): JsonElement {
        val r = e.request(method,"/api$path",body,query,auth); assertEquals(r.body.toString(),200,r.code); return r.body
    }
    private fun cfg() = jsonOf("people" to 2,"portion_factor" to 1,"max_repeat" to 3,"max_minutes" to 120,"max_difficulty" to 2,"confirm_estimates" to true,
        "meals" to (0..6).flatMap { d -> listOf("lunch","dinner").map { jsonOf("day" to d,"meal" to it) } })
    private fun preview(c:JsonObject) = call("POST","/v3/planning/preview",c).asObject()!!.obj("preview")!!
    private fun notices() = call("GET","/notifications").asArray()!!.mapNotNull { it.asObject() }

    @Test fun naturalDayExpiryAndInstantBoundaryAreConsistent() {
        val cases = listOf("2026-10-22" to "expiring_2d", "2026-10-21" to "critical_1d", "2026-10-20" to "expires_today", "2026-10-19" to "expired")
        for ((date,state) in cases) {
            val f = localFreshness(jsonOf("expiry_date" to date),EmptyObject,now)
            assertEquals(state,f.str("expiry_state"))
            assertFalse(f.bool("expired") && f.bool("expiring_soon"))
        }
        assertTrue(localFreshness(jsonOf("expiry_date" to Instant.ofEpochMilli(now).toString()),EmptyObject,now).bool("expired"))
        assertEquals("expires_today",localFreshness(jsonOf("expiry_date" to "2026-10-20T23:59:59"),EmptyObject,now).str("expiry_state"))
    }
    @Test fun noticePaginationPreservesGlobalUnreadAndOwnership() {
        e.set("stock",emptyList())
        e.set("notices",(0..104).map { jsonOf("id" to "n$it","owner" to "lin","is_read" to false,"created_at" to "2026-10-20T02:00:00Z") } + jsonOf("id" to "private","owner" to "chen","is_read" to false))
        val a=call("GET","/notifications",query=mapOf("offset" to "0","limit" to "50")).asArray()!!
        val b=call("GET","/notifications",query=mapOf("offset" to "50","limit" to "50")).asArray()!!
        assertEquals(50,a.size); assertEquals(50,b.size); assertTrue(a.toSet().intersect(b.toSet()).isEmpty())
        assertEquals(105.0,call("GET","/notifications/summary").asObject()!!.num("unread")!!,0.0)
        call("POST","/notifications/read")
        assertEquals(0.0,call("GET","/notifications/summary").asObject()!!.num("unread")!!,0.0)
        assertFalse(e.find("notices","private").bool("is_read"))
    }
    @Test fun notificationsAreStableRefreshReadAndReopenOnlyOnStateChange() {
        e.set("stock",listOf(jsonOf("id" to "batch","owner" to "lin","name" to "牛奶","quantity" to 1,"expiry_date" to "2026-10-22")))
        val first=notices().single(); repeat(3) { assertEquals(first.str("id"),notices().single().str("id")) }
        call("POST","/notifications/read",query=mapOf("msg_id" to first.str("id")!!))
        assertTrue(notices().single().bool("is_read"))
        now += 86400000; assertEquals("critical_1d",notices().single().str("state")); assertFalse(notices().single().bool("is_read"))
        call("POST","/notifications/archive",query=mapOf("msg_id" to first.str("id")!!)); assertTrue(notices().isEmpty())
        now += 86400000; assertEquals("expires_today",notices().single().str("state"))
        e.remove("stock","batch"); assertTrue(notices().isEmpty())
    }
    @Test fun sameBatchMergesCanonicalNamesAndUnitsButUnknownDatesAndDifferentUnitsDoNot() {
        val row=jsonOf("name" to "西红柿","quantity" to 2,"unit" to "g","storage_type" to "冷藏","shelf_life" to 7,"purchase_time" to "2026-10-20","expiry_date" to "2026-10-27")
        call("POST","/add-to-inventory",JsonArray(listOf(row)))
        call("POST","/inventory/confirm-recognition",jsonOf("items" to listOf(row.with("name" to "番茄","unit" to "克","source" to jsonOf("recognition" to "image")))))
        assertEquals(4.0,e.stock("lin").single { it.str("unit")=="克" }.num("quantity")!!,0.0)
        call("POST","/add-to-inventory",JsonArray(listOf(row.with("unit" to "千克"))))
        assertEquals(1,e.stock("lin").count { it.str("unit")=="千克" })
        val unknown=jsonOf("name" to "未知批次","quantity" to 1)
        repeat(2) { call("POST","/add-to-inventory",JsonArray(listOf(unknown))) }
        assertEquals(2,e.stock("lin").count { it.str("name")=="未知批次" })
    }
    @Test fun allStockEntryPathsUseSameBatchPolicy() {
        val b=jsonOf("name" to "测试食材","quantity" to 3,"unit" to "个","storage_type" to "冷藏","shelf_life" to 5,"purchase_time" to "2026-10-20")
        call("POST","/v3/households/home/inventory",b)
        call("POST","/v3/households/home/shopping/shop-1/stock-in",b.with("expected_version" to 1,"idempotency_key" to "stock-demo"))
        assertEquals(6.0,e.stock("home").single { it.str("name")=="测试食材" }.num("quantity")!!,0.0)
        call("POST","/v3/households/home/shopping/shop-1/stock-in",b.with("expected_version" to 1,"idempotency_key" to "stock-demo"))
        assertEquals(6.0,e.stock("home").single { it.str("name")=="测试食材" }.num("quantity")!!,0.0)
    }
    @Test fun completeWeeklyPlanHonorsRepeatAndCorrectTemplateServings() {
        val p=preview(cfg()); val result=p.obj("result")!!
        assertEquals("FEASIBLE",result.str("solver_status")); assertEquals(14,result.objects("meals").size)
        val dishes=result.objects("meals").flatMap { it.objects("dishes") }
        assertTrue(dishes.groupingBy { it.str("recipe_id") }.eachCount().values.all { it<=3 })
        for(d in dishes) assertEquals(2.0/d.obj("recipe")!!.obj("_planning")!!.num("servings")!!,d.num("factor")!!,0.0)
        result.objects("meals").forEach { assertEquals(setOf("main","staple"),it.objects("dishes").map { d->d.str("role") }.toSet()) }
    }
    @Test fun impossibleLockedOrDataMissingConstraintsNeverProduceSavableMeals() {
        val p=preview(cfg().with("avoid_ingredients" to listOf("番茄"),"locks" to listOf(jsonOf("day" to 0,"meal" to "lunch","role" to "main","recipe_id" to "recipe_001"))))
        assertTrue(p.obj("result")!!.objects("meals").isEmpty())
        assertTrue(p.obj("result")!!.strings("issues").any { it.contains("第 1 天午餐") })
        assertEquals(422,e.request("POST","/api/v3/planning/menus",jsonOf("preview_id" to p.str("id"),"expected_version" to 1,"name" to "禁止保存"),headers=auth).code)
        val missing=preview(cfg().with("budget" to 100)).obj("result")!!
        assertTrue(missing.objects("meals").isEmpty()); assertTrue(missing.objects("exclusions").isNotEmpty()); assertNull(missing.num("total_estimated_cost"))
    }
    @Test fun pricedNutritionPlanMeetsActualTotalsAndReportsSources() {
        val ingredients=recipes.flatMap { it.objects("ingredients") }.distinctBy { it.str("name") to it.str("unit") }
        call("PUT","/v3/planning/prices",jsonOf("expected_version" to 0,"items" to ingredients.map { jsonOf("name" to it.str("name"),"unit" to it.str("unit"),"price" to .01) }))
        val result=preview(cfg().with("meals" to listOf(jsonOf("day" to 0,"meal" to "lunch")),"budget" to 100,"nutrition" to jsonOf("kcal" to jsonOf("min" to 1,"max" to 10000)))).obj("result")!!
        assertEquals("FEASIBLE",result.str("solver_status")); assertTrue(result.num("total_estimated_cost")!!<=100)
        assertTrue(result.obj("nutrition")!!.num("kcal")!! in 1.0..10000.0)
        assertTrue(result.objects("meals").flatMap { it.objects("dishes") }.all { it.obj("recipe")!!.obj("planning_nutrition")!!.objects("evidence").isNotEmpty() })
    }
    @Test fun saveRejectsChangedFamilyStockAndPriceVersion() {
        val p=preview(cfg().with("family_id" to "home"))
        call("POST","/v3/households/home/inventory",jsonOf("name" to "新批次","quantity" to 1))
        assertEquals(409,e.request("POST","/api/v3/planning/menus",jsonOf("preview_id" to p.str("id"),"expected_version" to 1,"name" to "旧预览"),headers=auth).code)
        val p2=preview(cfg())
        call("PUT","/v3/planning/prices",jsonOf("expected_version" to 0,"items" to emptyList<String>()))
        assertEquals(409,e.request("POST","/api/v3/planning/menus",jsonOf("preview_id" to p2.str("id"),"expected_version" to 1,"name" to "旧价格"),headers=auth).code)
    }
    @Test fun communityDraftOwnershipAndPublishedImagesSurviveCleanup() {
        call("POST","/v3/community/media",jsonOf("id" to "private-image","mime" to "image/jpeg"))
        val chen=call("POST","/login",jsonOf("username" to "chen","password" to "cookx123")).asObject()!!
        assertEquals(403,e.request("GET","/api/v3/community/media/private-image",headers=mapOf("Authorization" to "Bearer ${chen.str("access_token")}")).code)
        val post=call("POST","/v3/community/posts",jsonOf("text" to "图片作品","image_ids" to listOf("private-image"),"confirmed" to true)).asObject()!!.obj("post")!!
        now+=2*86400000; assertTrue("private-image" in e.sweepMedia())
        call("DELETE","/v3/community/media/private-image"); assertNotNull(e.find("media","private-image"))
        call("DELETE","/v3/community/posts/${post.str("id")}",jsonOf("expected_version" to 1)); assertFalse("private-image" in e.sweepMedia())
    }
    @Test fun abandonedMediaExpiresAndBookmarksAreSeparateFromRecipeFavorites() {
        call("POST","/v3/community/media",jsonOf("id" to "abandoned")); now+=2*86400000; assertFalse("abandoned" in e.sweepMedia())
        val before=e.rows("favorite").size
        call("POST","/v3/community/posts/post-1/bookmark",jsonOf("bookmarked" to true))
        assertEquals(1,call("GET","/v3/community/posts",query=mapOf("bookmarked" to "true")).asObject()!!.objects("items").size)
        assertEquals(before,e.rows("favorite").size)
    }
    @Test fun invalidAIRecipesNeverCountAsSuccessAndOfflineHistoryHasISOTime() {
        val valid=recipes.first(); DemoGateway.validateRecipe(valid)
        for(bad in listOf(valid.with("dish_name" to 2),valid.with("steps" to emptyList<String>()),valid.with("ingredients_list" to listOf(jsonOf("item" to "盐","amount" to -1,"unit" to "克"))),valid.with("ingredients_list" to listOf("盐")))) {
            assertTrue(runCatching { DemoGateway.validateRecipe(bad) }.isFailure)
        }
        val r=e.assist("lin","番茄炒鸡蛋",null,true); assertEquals("built_in",r.body.asObject()!!.str("source"))
        val messages=e.rows("chat").takeLast(2); assertEquals(listOf("user","assistant"),messages.map { it.str("role") }); messages.forEach { Instant.parse(it.str("timestamp")) }
    }
    @Test fun missingVoiceConfidenceOnlyAllowsSafeExactNavigation() {
        for(text in listOf("下一步","下一步操作","请进入下一步","返回上一步","再说一遍")) assertTrue(text,VoiceCommands.parse(text,null).confirmed)
        for(text in listOf("不要下一步","别继续下一步","下一步然后上一步","开始计时")) assertFalse(text,VoiceCommands.parse(text,null).confirmed)
        assertTrue(VoiceCommands.parse("不要下一步",.99f).matches.isEmpty())
    }
    @Test fun allCrossScreenEventsAreClearedTogether() {
        val events=AppEvents(); events.pendingDish.value="菜"; events.chefPrompt.value="问题"; events.focusInventory.value="batch"; events.leftoverName.value="米饭"; events.openCooking.value=true
        events.clearAll(); assertNull(events.pendingDish.value); assertNull(events.chefPrompt.value); assertNull(events.focusInventory.value); assertNull(events.leftoverName.value); assertFalse(events.openCooking.value)
    }
}
