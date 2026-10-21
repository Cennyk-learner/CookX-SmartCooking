package com.smartcooking.app.offline

import com.smartcooking.app.core.*
import com.smartcooking.app.data.InventoryRules
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.round

private val recommendationWeights = linkedMapOf("I" to .35, "F" to .25, "P" to .20, "W" to .20, "B" to .15, "D" to .10, "N" to .10)
private fun recName(value: String?) = InventoryRules.canonicalName(value).let {
    mapOf("马铃薯" to "土豆", "洋芋" to "土豆", "蛋" to "鸡蛋", "瘦肉" to "猪肉", "鸡胸" to "鸡胸肉", "西蓝花" to "西兰花", "蒜" to "大蒜", "香葱" to "小葱")[it] ?: it
}
private val seasonings = setOf("盐", "食用油", "油", "水", "清水", "生抽", "老抽", "酱油", "醋", "白糖", "糖", "料酒", "淀粉", "胡椒粉", "花椒", "葱", "大葱", "小葱", "生姜", "大蒜")
private fun rounded(value: Double, scale: Double = 1e6) = round(value * scale) / scale
private fun difficulty(value: String?) = when(value) { "简单", "容易", "easy" -> 0.0; "中等", "一般", "medium" -> .5; "困难", "较难", "hard" -> 1.0; else -> null }
private fun preferenceTokens(p: JsonObject, vararg keys: String): Set<String> = keys.flatMap { key ->
    p.strings(key).ifEmpty { p.str(key)?.split(Regex("[，,、\\n]")) ?: emptyList() }
}.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

/** Local multi-objective scoring using recorded stock and explicit recipe fields, never invented prices/nutrition. */
internal fun LocalEngine.localRecommendations(user: String, body: JsonObject): JsonObject {
    val prefs = JsonObject((body.obj("preferences") ?: EmptyObject) + body.without("preferences"))
    val avoid = preferenceTokens(prefs, "avoid_ingredients", "disliked_ingredients").map(::recName).toSet()
    val unsafe = stock(user).filter { fresh(it).bool("expired") || "storage_unsuitable" in fresh(it).strings("risk_flags") }.map { recName(it.str("name")) }.toSet()
    val batches = usableStock(user).filter { recName(it.str("name")) !in unsafe }.groupBy { recName(it.str("name")) }
    val model = rows("models").lastOrNull { it.str("id") == user && it.bool("accepted") }
        ?.takeIf { rows("learning").any { row -> row.str("id") == user && row.bool("enabled") } }
    val eligible = recipes.filter { recipe ->
        val all = recipe.objects("ingredients") + recipe.objects("optional_ingredients")
        all.none { recName(it.str("name")) in avoid || recName(it.str("name")) in unsafe } &&
            recipe.objects("ingredients").any { it["required"].asText() != "false" && recName(it.str("name")) !in seasonings && recName(it.str("name")) in batches }
    }
    val scored = eligible.map { recipe -> scoreLocalRecipe(recipe, batches, prefs) }
    val sorted = if (model == null) scored.sortedWith(compareByDescending<JsonObject> { it.num("total_score") }.thenBy { it.str("recipe_id") }) else
        scored.sortedWith(compareByDescending<JsonObject> { row -> prediction(model, eligible.first { it.str("id") == row.str("recipe_id") }) }.thenByDescending { it.num("total_score") })
    val status = when { batches.isEmpty() -> "inventory_required"; eligible.isEmpty() -> "no_eligible_recipes"; else -> "success" }
    return jsonOf("status" to status, "algorithm_version" to "multi-objective-local-v2", "eligible_recipe_count" to eligible.size,
        "filtered_recipe_count" to recipes.size - eligible.size,
        "message" to when(status) { "inventory_required" -> "请先添加数量大于零且未过期的库存食材"; "no_eligible_recipes" -> "当前库存与饮食限制下，没有匹配关键食材的菜谱"; else -> "" },
        "personalization" to model?.let { jsonOf("reason" to "已按通过验证的偏好模型重排，综合推荐分仍显示库存与目标评分") },
        "recommendations" to sorted.take((body.num("top_k") ?: 5.0).toInt().coerceIn(1, 30)).mapIndexed { index, row ->
            row.with("rank" to index + 1, "personalization" to model?.let { jsonOf("reasons" to emptyList<String>()) })
        })
}

private fun LocalEngine.scoreLocalRecipe(recipe: JsonObject, batches: Map<String, List<JsonObject>>, prefs: JsonObject): JsonObject {
    val required = recipe.objects("ingredients").filter { it["required"].asText() != "false" }
    val optional = recipe.objects("optional_ingredients") + recipe.objects("ingredients").filter { it["required"].asText() == "false" }
    val all = required + optional
    val keyNames = required.map { recName(it.str("name")) }.filter { it !in seasonings }.distinct()
    val missing = keyNames.filter { it !in batches }
    val matched = all.map { recName(it.str("name")) }.distinct().filter { it in batches }
    // Display the recipe's own Chinese ingredient labels while matching aliases canonically.
    fun display(names: List<String>) = names.map { name -> all.firstOrNull { recName(it.str("name")) == name }?.str("name") ?: name }
    val quantityScores = all.mapNotNull { ingredient ->
        val needed = ingredient.num("amount")?.takeIf { it > 0 } ?: return@mapNotNull null
        val unit = canonicalUnit(ingredient.str("unit"))
        if (unit == "库存计数") return@mapNotNull null
        val amounts = batches[recName(ingredient.str("name"))].orEmpty().mapNotNull { row ->
            if (canonicalUnit(row.str("amount_unit") ?: row.str("unit")) == unit) row.num("amount") ?: row.num("quantity") else null
        }
        amounts.takeIf { it.isNotEmpty() }?.sum()?.div(needed)?.coerceIn(0.0, 1.0)
    }
    val missingRatio = if (keyNames.isEmpty()) 0.0 else missing.size.toDouble() / keyNames.size
    val coverage = mutableListOf(.70 to (1 - missingRatio))
    if (optional.isNotEmpty()) coverage += .15 to (optional.count { recName(it.str("name")) in batches }.toDouble() / optional.size)
    if (quantityScores.isNotEmpty()) coverage += .15 to quantityScores.average()
    val urgency = matched.flatMap { name -> batches[name].orEmpty().mapNotNull { row -> fresh(row).obj("component_scores")?.num("T")?.let { 1 - it } } }
    val expiring = matched.filter { name -> batches[name].orEmpty().any { fresh(it).bool("expiring_soon") } }
    val allExpiring = batches.filterValues { rows -> rows.any { fresh(it).bool("expiring_soon") } }.keys
    val likedTags = preferenceTokens(prefs, "tags", "preferred_tags", "taste", "spice")
    val likedIngredients = preferenceTokens(prefs, "ingredients", "preferred_ingredients").map(::recName).toSet()
    val checks = mutableListOf<Double>()
    if (likedTags.isNotEmpty()) checks += likedTags.intersect(recipe.strings("tags").map { it.lowercase() }.toSet()).size.toDouble() / likedTags.size
    if (likedIngredients.isNotEmpty()) checks += likedIngredients.intersect(all.map { recName(it.str("name")) }.toSet()).size.toDouble() / likedIngredients.size
    val minutes = recipe.num("cooking_time")
    val duration = prefs.str("duration")
    if (duration == "any") checks += 1.0 else if (minutes != null && duration in listOf("under_30", "30_to_60"))
        checks += if (duration == "under_30" && minutes <= 30 || duration == "30_to_60" && minutes > 30 && minutes <= 60) 1.0 else 0.0
    val cost = recipe.num("estimated_cost")?.takeIf { it >= 0 }
    val budget = prefs.num("budget")?.takeIf { it > 0 }
    val recipeDifficulty = difficulty(recipe.str("difficulty"))
    val targetDifficulty = difficulty(prefs.str("difficulty_target"))
    val nutrition = recipe.obj("nutrition")?.takeIf { it.str("basis") == "per_serving" }
    val targets = prefs.obj("nutrition_target") ?: EmptyObject
    val nutritionScores = linkedMapOf<String, Double>()
    for ((target, metric) in mapOf("max_calories_kcal" to "calories_kcal", "min_protein_g" to "protein_g", "max_fat_g" to "fat_g", "max_carbohydrates_g" to "carbohydrates_g")) {
        val goal = targets.num(target)?.takeIf { it > 0 } ?: continue
        val value = nutrition?.num(metric)?.takeIf { it >= 0 } ?: continue
        nutritionScores[target] = (if (target.startsWith("min")) value / goal else if (value == 0.0) 1.0 else goal / value).coerceIn(0.0, 1.0)
    }
    val inventoryUse = matched.size.toDouble() / batches.size.coerceAtLeast(1)
    val components = linkedMapOf(
        "I" to coverage.sumOf { it.first * it.second } / coverage.sumOf { it.first },
        "F" to urgency.takeIf { it.isNotEmpty() }?.average(), "P" to checks.takeIf { it.isNotEmpty() }?.average(),
        "W" to if (allExpiring.isEmpty()) inventoryUse else .7 * inventoryUse + .3 * matched.count { it in allExpiring } / allExpiring.size,
        "B" to if (cost != null && budget != null) (1 - cost / budget).coerceIn(0.0, 1.0) else null,
        "D" to if (recipeDifficulty != null && targetDifficulty != null) 1 - abs(recipeDifficulty - targetDifficulty) else null,
        "N" to nutritionScores.values.takeIf { it.isNotEmpty() }?.average(), "M" to missingRatio
    ).mapValues { (_, value) -> value?.let { rounded(it.coerceIn(0.0, 1.0)) } }
    val active = recommendationWeights.filterKeys { components[it] != null }
    val denominator = active.values.sum()
    val weights = linkedMapOf<String, Double>()
    active.keys.forEachIndexed { index, key -> weights[key] = rounded(if (index == active.size - 1) 1 - weights.values.sum() else active.getValue(key) / denominator) }
    val score = rounded((100 * weights.entries.sumOf { (key, weight) -> weight * components.getValue(key)!! } - 15 * components.getValue("M")!!).coerceIn(0.0, 100.0), 100.0)
    val unavailable = components.filterValues { it == null }.keys.toList()
    val notes = buildList {
        if (quantityScores.isEmpty()) add("库存与菜谱没有可比用量，未计入数量满足度；请核对实际用量")
        if ("F" in unavailable) add("缺少可用入库和到期时间，临期得分未参与总分")
        if ("P" in unavailable) add("未设置可评分的饮食偏好，偏好分未参与总分")
        if ("B" in unavailable) add("缺少明确成本或预算，未推测价格")
        if ("N" in unavailable) add("缺少每份营养数据或目标，未推测营养值")
    }
    return jsonOf("recipe_id" to recipe.str("id"), "recipe_name" to recipe.str("name"), "cooking_time" to minutes,
        "total_score" to score, "component_scores" to components, "effective_weights" to weights, "missing_penalty_weight" to .15,
        "unavailable_components" to unavailable, "matched_ingredients" to display(matched), "missing_required_ingredients" to display(missing),
        "expiring_ingredients_used" to display(expiring), "data_quality_notes" to notes,
        "estimated_cost" to cost, "budget" to budget, "currency" to "CNY", "cost_status" to if (components["B"] != null) "已有明确成本与预算" else "数据不足，未计分",
        "recipe_difficulty" to recipe.str("difficulty"), "difficulty_target" to prefs.str("difficulty_target"), "difficulty_status" to if (components["D"] != null) "已按目标计算" else "未设置目标或缺少难度",
        "recipe_nutrition" to nutrition, "nutrition_target" to targets, "nutrition_basis" to nutrition?.str("basis"), "nutrition_component_scores" to nutritionScores,
        "nutrition_status" to if (components["N"] != null) "已按目标计算" else "数据不足，未计分",
        "reasons" to buildList {
            add("关键食材匹配 ${keyNames.size - missing.size}/${keyNames.size} 项；综合评分满分 100")
            if (expiring.isNotEmpty()) add("优先利用临期食材：${display(expiring).joinToString("、")}")
            add(if (missing.isEmpty()) "无需补充关键食材，开工前请核对实际用量" else "仍需补充：${display(missing).joinToString("、")}")
        })
}
