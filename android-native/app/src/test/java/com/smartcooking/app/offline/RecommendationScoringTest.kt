package com.smartcooking.app.offline

import com.smartcooking.app.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class RecommendationScoringTest {
    private val now = Instant.parse("2026-10-21T02:00:00Z").toEpochMilli()
    private val recipes = AppJson.parseToJsonElement(File("src/main/assets/offline/recipes.json").readText()).asArray()!!.mapNotNull { it.asObject() }
    private val engine = LocalEngine(recipes, null, {}, { now })
    private fun recommend(body: JsonObject = EmptyObject) = engine.localRecommendations("lin", body)
    private fun stock(name: String, quantity: Int = 2, expiry: String = "2026-10-23") = jsonOf("id" to name, "owner" to "lin", "name" to name, "quantity" to quantity, "unit" to "个", "add_time" to "2026-10-20", "expiry_date" to expiry)

    @Test fun everyScoreIsReproducibleFromVisibleComponentsAndSorted() {
        val rows = recommend(jsonOf("top_k" to 30)).objects("recommendations")
        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            val weights = row.obj("effective_weights")!!
            val components = row.obj("component_scores")!!
            assertEquals(1.0, weights.values.sumOf { it.asDouble()!! }, 1e-6)
            val calculated = (100 * weights.entries.sumOf { (key,value) -> value.asDouble()!! * components.num(key)!! } - 100 * row.num("missing_penalty_weight")!! * components.num("M")!!).coerceIn(0.0,100.0)
            assertEquals(calculated, row.num("total_score")!!, .0051)
            assertNull(components.num("B")); assertNull(components.num("N"))
            assertFalse(weights.containsKey("B")); assertFalse(weights.containsKey("N"))
        }
        assertEquals(rows.map { it.num("total_score")!! }.sortedDescending(), rows.map { it.num("total_score")!! })
    }

    @Test fun inventoryChangeAliasesAndExpiryChangeScoresAndEligibility() {
        engine.set("stock", listOf(stock("西红柿"),stock("鸡蛋",3)))
        val full = recommend().objects("recommendations").first { it.str("recipe_id") == "recipe_001" }
        assertTrue("番茄" in full.strings("matched_ingredients")); assertTrue(full.strings("missing_required_ingredients").isEmpty())
        engine.set("stock", listOf(stock("西红柿")))
        val partial = recommend().objects("recommendations").first { it.str("recipe_id") == "recipe_001" }
        assertTrue("鸡蛋" in partial.strings("missing_required_ingredients"))
        assertTrue(full.num("total_score")!! > partial.num("total_score")!!)
        engine.set("stock", listOf(stock("西红柿",expiry="2026-10-19")))
        assertEquals("inventory_required", recommend().str("status"))
        engine.set("stock", listOf(stock("盐")))
        assertEquals("no_eligible_recipes", recommend().str("status"))
    }

    @Test fun goalsContributeOnlyWithExplicitMatchingData() {
        val recipe = recipes.first().with("estimated_cost" to 12, "nutrition" to jsonOf("basis" to "per_serving", "protein_g" to 10))
        val e = LocalEngine(listOf(recipe) + recipes.drop(1), null, {}, {now})
        val row = e.localRecommendations("lin",jsonOf("top_k" to 30,"budget" to 20,"difficulty_target" to "hard", "nutrition_target" to jsonOf("min_protein_g" to 20))).objects("recommendations").first { it.str("recipe_id") == recipe.str("id") }
        val scores = row.obj("component_scores")!!
        assertEquals(.4, scores.num("B")!!, 1e-6)
        assertEquals(0.0, scores.num("D")!!, 1e-6)
        assertEquals(.5, scores.num("N")!!, 1e-6)
        assertTrue(row.obj("effective_weights")!!.keys.containsAll(listOf("B","D","N")))
        assertFalse(e.localRecommendations("lin",jsonOf("top_k" to 30,"preferences" to jsonOf("disliked_ingredients" to listOf("西红柿")))).objects("recommendations").any { it.str("recipe_id") == recipe.str("id") })
    }
}
