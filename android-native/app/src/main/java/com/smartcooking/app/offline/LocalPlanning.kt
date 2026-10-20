package com.smartcooking.app.offline

import com.smartcooking.app.core.*
import com.smartcooking.app.data.InventoryRules
import kotlinx.serialization.json.*

private val nutrients = listOf("kcal", "protein_g", "fat_g", "carb_g")
private val mealLabels = mapOf("breakfast" to "早餐", "lunch" to "午餐", "dinner" to "晚餐")
private val roleLabels = mapOf("main" to "主菜", "staple" to "主食", "side" to "配菜")
private fun name(row: JsonObject) = InventoryRules.canonicalName(row.str("name"))
private data class Choice(val recipe: JsonObject, val factor: Double, val cost: Double?, val nutrition: JsonObject?)
private data class Position(val slot: Int, val role: String, val label: String, val choices: List<Choice>)

/** Bounded exact search. A result is feasible only after every selected hard constraint passes. */
internal fun LocalEngine.solvePlan(config: JsonObject, scope: String, prices: JsonObject): JsonObject {
    val slots = config.objects("meals")
    if (slots.isEmpty() || slots.size > 21 || slots.any { (it.num("day") ?: -1.0) !in 0.0..6.0 || it.str("meal") !in mealLabels } ||
        slots.map { it.str("day") to it.str("meal") }.distinct().size != slots.size) fail(422, "请选择七天内不重复的餐次")
    val people = config.num("people") ?: 2.0
    val portion = config.num("portion_factor") ?: 1.0
    val minutes = config.num("max_minutes") ?: 120.0
    val repeat = config.num("max_repeat") ?: 3.0
    val budget = config.num("budget")
    if (!people.isFinite() || people !in 1.0..20.0 || people % 1 != 0.0 || !portion.isFinite() || portion !in .25..4.0 ||
        !minutes.isFinite() || minutes <= 0 || !repeat.isFinite() || repeat !in 1.0..63.0 || repeat % 1 != 0.0 ||
        budget?.let { !it.isFinite() || it < 0 } == true) fail(422, "请检查人数、份量、时长、重复次数和预算")
    val bounds = config.obj("nutrition") ?: EmptyObject
    if (bounds.keys.any { it !in nutrients }) fail(422, "不支持此营养约束")
    bounds.values.forEach { value ->
        val b = value.asObject() ?: fail(422, "营养目标格式无效")
        val min = b.num("min"); val max = b.num("max")
        if (listOfNotNull(min, max).any { !it.isFinite() || it < 0 } || min != null && max != null && min > max) fail(422, "营养下限不能高于上限，且必须为非负数")
    }
    val usable = usableStock(scope)
    val avoided = (config.strings("avoid_ingredients") + stock(scope).filter { fresh(it).bool("expired") || "storage_unsuitable" in fresh(it).strings("risk_flags") }.mapNotNull { it.str("name") })
        .map(InventoryRules::canonicalName).toSet()
    val diet = config.strings("dietary_restrictions")
    val exclusions = mutableListOf<JsonObject>()
    val candidates = recipes.mapNotNull { r ->
        val ingredients = r.objects("ingredients")
        if (ingredients.any { name(it) in avoided } || (r.num("cooking_time") ?: Double.MAX_VALUE) > minutes ||
            (when(r.str("difficulty")) { "简单" -> 1; "中等" -> 2; else -> 3 }) > (config.num("max_difficulty") ?: 2.0) ||
            diet.any { d -> val tag = when(d) { "vegetarian" -> "素食"; "vegan" -> "纯素"; else -> d }; tag !in r.strings("tags") }) return@mapNotNull null
        val servings = r.obj("_planning")?.num("servings") ?: 2.0
        val factor = people * portion / servings
        val costs = ingredients.map { i -> prices.objects("items").firstOrNull { name(it) == name(i) && it.str("unit") == i.str("unit") }?.num("price")?.times((i.num("amount") ?: 0.0) * factor) }
        val cost = if (costs.any { it == null }) null else costs.filterNotNull().sum()
        val nutrition = r.obj("planning_nutrition")?.obj("values")
        val missing = mutableListOf<String>()
        if (budget != null && cost == null) missing.add("同单位食材价格")
        if (bounds.isNotEmpty() && nutrition == null) missing.add("完整营养映射")
        if (missing.isNotEmpty()) { exclusions.add(jsonOf("recipe_id" to r.str("id"), "reason" to "缺少核验数据", "missing" to missing)); null }
        else Choice(r, factor, cost, nutrition?.let { n -> JsonObject(n.mapValues { (_, v) -> ((v.asText()?.toDoubleOrNull() ?: 0.0) * factor).toJsonElement() }) })
    }
    val positions = slots.flatMapIndexed { index, slot ->
        (if (slot.bool("side")) listOf("main", "staple", "side") else listOf("main", "staple")).map { role ->
            val lock = config.objects("locks").firstOrNull { it.str("day") == slot.str("day") && it.str("meal") == slot.str("meal") && it.str("role") == role }?.str("recipe_id")?.takeIf { it.isNotBlank() }
            Position(index, role, "第 ${(slot.num("day") ?: 0.0).toInt()+1} 天${mealLabels[slot.str("meal")]}的${roleLabels[role]}", candidates.filter { c ->
                val meta = c.recipe.obj("_planning")
                meta?.strings("roles")?.contains(role) == true && slot.str("meal") in meta.strings("meals") && (lock == null || c.recipe.str("id") == lock)
            })
        }
    }
    val issues = mutableListOf<String>()
    if (!config.bool("confirm_estimates")) issues.add("请先确认工程模板份量及估算依据，再计算菜单")
    positions.filter { it.choices.isEmpty() }.forEach { issues.add("${it.label}没有候选。请检查锁定菜、忌口、时长，或补齐价格／营养数据；系统不会自动放宽约束。") }
    val ordered = positions.sortedBy { it.choices.size }
    val chosen = mutableMapOf<Position, Choice>(); val uses = mutableMapOf<String, Int>()
    val totals = nutrients.associateWith { 0.0 }.toMutableMap()
    var nodes = 0; var limited = false; val deadline = System.nanoTime() + 1_500_000_000L
    fun search(index: Int, cost: Double): Boolean {
        if (++nodes > 50_000 || System.nanoTime() > deadline) { limited = true; return false }
        if (budget != null && cost > budget + .000001) return false
        if (bounds.any { (k, v) -> v.asObject()?.num("max")?.let { totals.getValue(k) > it + .000001 } == true }) return false
        if (index == ordered.size) return bounds.all { (k, v) -> totals.getValue(k) + .000001 >= (v.asObject()?.num("min") ?: 0.0) }
        val pos = ordered[index]
        val sorted = pos.choices.sortedWith(compareBy<Choice> { uses[it.recipe.str("id")] ?: 0 }.thenBy { c ->
            if (config.bool("inventory_priority")) -c.recipe.objects("ingredients").sumOf { i -> usable.filter { name(it) == name(i) }.maxOfOrNull { if (fresh(it).bool("expiring_soon")) 3 else 1 } ?: 0 } else 0
        }.thenBy { if (budget != null) it.cost ?: Double.MAX_VALUE else 0.0 })
        for (c in sorted) {
            val id = c.recipe.str("id")!!
            if ((uses[id] ?: 0) >= repeat || chosen.any { (p, other) -> p.slot == pos.slot && other.recipe.str("id") == id }) continue
            chosen[pos] = c; uses[id] = (uses[id] ?: 0) + 1
            nutrients.forEach { totals[it] = totals.getValue(it) + (c.nutrition?.num(it) ?: 0.0) }
            if (search(index + 1, cost + (c.cost ?: 0.0))) return true
            nutrients.forEach { totals[it] = totals.getValue(it) - (c.nutrition?.num(it) ?: 0.0) }
            chosen.remove(pos); uses[id] = uses.getValue(id) - 1
            if (limited) return false
        }
        return false
    }
    val feasible = issues.isEmpty() && search(0, 0.0)
    if (!feasible && issues.isEmpty()) issues.add(if (limited) "本次搜索达到时限，尚未找到可行组合。请减少锁定位置或餐次后重试；未改动你的约束。" else "现有菜谱无法同时满足重复次数、预算和营养目标。请调整目标或增加可用菜谱后重试。")
    val resultMeals = if (!feasible) emptyList() else slots.mapIndexed { index, slot ->
        val dishes = positions.filter { it.slot == index }.map { p -> val c = chosen.getValue(p); jsonOf("role" to p.role, "recipe_id" to c.recipe.str("id"), "name" to c.recipe.str("name"), "recipe" to c.recipe, "factor" to c.factor) }
        slot.with("dishes" to dishes, "minutes" to dishes.maxOf { it.obj("recipe")?.num("cooking_time") ?: 0.0 })
    }
    val knownCost = feasible && chosen.values.all { it.cost != null }
    val needs = chosen.values.flatMap { c -> c.recipe.objects("ingredients").map { it.with("amount" to (it.num("amount") ?: 0.0) * c.factor) } }.groupBy { name(it) to it.str("unit") }
    val shoppingCost = if (!knownCost) null else needs.entries.sumOf { (key, rows) ->
        val existing = usable.filter { name(it) == key.first && it.str("unit") == key.second }.sumOf { it.num("quantity") ?: 0.0 }
        val price = prices.objects("items").first { name(it) == key.first && it.str("unit") == key.second }.num("price")!!
        (rows.sumOf { it.num("amount") ?: 0.0 } - existing).coerceAtLeast(0.0) * price
    }
    return jsonOf("solver_status" to if(feasible) "FEASIBLE" else if(limited) "UNKNOWN" else "NEEDS_ADJUSTMENT", "meals" to resultMeals,
        "issues" to issues, "exclusions" to exclusions, "total_estimated_cost" to if(knownCost) chosen.values.sumOf { it.cost!! } else null,
        "shopping_estimated_cost" to shoppingCost, "nutrition" to if(feasible && chosen.values.all { it.nutrition != null }) totals else null,
        "note" to "按工程模板计算份量；时长是并行烹饪估计。营养为原料估算，价格由本机录入。未知价格不按零元处理，不同库存单位不自动换算。")
}

internal fun LocalEngine.planning(m: String, s: List<String>, b: JsonObject, u: String): JsonObject {
    val prices = rows("prices").firstOrNull { it.str("id") == u } ?: jsonOf("id" to u, "version" to 0, "items" to emptyList<String>())
    when(s[1]) {
        "catalog" -> return jsonOf("recipes" to recipes, "prices" to prices)
        "prices" -> {
            version(prices, b)
            if (b.objects("items").any { val price = it.num("price"); price == null || !price.isFinite() || price < 0 || it.str("name").isNullOrBlank() || it.str("unit").isNullOrBlank() }) fail(422, "请填写食材、单位与有效非负单价")
            val next = updated(prices, b); remove("prices", u); add("prices", next); return ok("prices" to next)
        }
        "preview" -> {
            b.str("family_id")?.let { member(it, u) }
            val scope = b.str("family_id") ?: u
            val preview = jsonOf("id" to id(), "owner" to u, "version" to 1, "config" to b.without("idempotency_key"),
                "result" to solvePlan(b, scope, prices), "inventory_version" to stockVersion(scope), "price_version" to prices.num("version"), "evaluated_at" to now())
            add("previews", preview); return ok("preview" to preview)
        }
        "menus" -> {
            if(m == "POST") {
                val p = find("previews", b.str("preview_id").orEmpty()); own(p, u); version(p, b)
                val scope = p.obj("config")?.str("family_id") ?: u
                if (scope != u) member(scope, u)
                if (p.num("inventory_version") != stockVersion(scope) || p.num("price_version") != prices.num("version") || p.str("evaluated_at")?.take(10) != now().take(10)) fail(409, "库存、价格或日期已变化，请重新计算预览后保存")
                if (p.obj("result")?.str("solver_status") != "FEASIBLE") fail(422, "此组合尚不能保存，请处理预检提示")
                if (b.str("name").isNullOrBlank()) fail(422, "请输入计划名称")
                val menu = p.with("id" to id(), "name" to b.str("name"), "created_at" to now()); add("menus", menu); return ok("menu" to menu)
            }
            if(s.size == 2) return items(rows("menus").filter { it.str("owner") == u })
            val menu = find("menus", s[2]); own(menu, u)
            val scope = menu.obj("config")?.str("family_id") ?: u
            if(scope != u) member(scope, u)
            return jsonOf("menu" to menu, "inventory_changed" to (menu.num("inventory_version") != stockVersion(scope)), "price_changed" to (menu.num("price_version") != prices.num("version")),
                "inventory" to stock(scope).map { jsonOf("item" to it, "freshness" to fresh(it)) },
                "revalidation_issues" to stock(scope).filter { fresh(it).bool("expired") }.map { "${it.str("name")}的一个批次已过期，请使用其他合格批次或重新采购。" },
                "note" to "执行前核对实际数量与储存状态。点击复刻可从独立菜谱进入备菜和烹饪；采购清单须再次确认生成。")
        }
    }
    fail(404, "操作不存在")
}
