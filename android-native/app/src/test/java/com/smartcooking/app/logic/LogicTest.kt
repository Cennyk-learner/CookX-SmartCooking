package com.smartcooking.app.logic

import androidx.test.core.app.ApplicationProvider
import com.smartcooking.app.core.AppJson
import com.smartcooking.app.core.KeyValueStore
import com.smartcooking.app.core.Time
import com.smartcooking.app.core.jsonOf
import com.smartcooking.app.core.str
import com.smartcooking.app.data.InventoryForm
import com.smartcooking.app.data.InventoryItem
import com.smartcooking.app.data.InventoryRules
import com.smartcooking.app.data.Recipe
import com.smartcooking.app.data.safeHistory
import com.smartcooking.app.device.TemperatureSample
import com.smartcooking.app.device.TemperatureStreamParser
import com.smartcooking.app.feature.cooking.CookingEngine
import com.smartcooking.app.feature.cooking.VoiceCommands
import com.smartcooking.app.temperature.CookingContext
import com.smartcooking.app.temperature.TemperatureEngine
import com.smartcooking.app.temperature.acceptPrediction
import com.smartcooking.app.temperature.buildFeatures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ports of the web client's node:test suites for the logic now implemented in Kotlin. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogicTest {
    // ---- inventoryFields.test.js

    @Test fun unknownMetadataIsOmitted() {
        assertEquals(jsonOf("name" to "牛肉", "quantity" to 1), InventoryRules.serialize(InventoryForm(name = "牛肉")))
    }

    @Test fun datesAndValidation() {
        val local = "2025-06-15T08:30"
        assertEquals(local, Time.localInput(Time.isoFromLocal(local)))
        assertTrue(Time.isoFromLocal(local)!!.endsWith("Z"))
        assertThrows(IllegalArgumentException::class.java) { Time.isoFromLocal("2025-02-30T08:30") }
        assertThrows(IllegalArgumentException::class.java) { InventoryRules.serialize(InventoryForm(name = "牛肉", purchaseTime = "2099-01-01T00:00")) }
        assertThrows(IllegalArgumentException::class.java) { InventoryRules.serialize(InventoryForm(name = "牛肉", purchaseTime = "2025-02-01T00:00", expiryDate = "2025-01-01T00:00")) }
        for (q in listOf("0", "-1", "1.5", "")) assertThrows(IllegalArgumentException::class.java) { InventoryRules.serialize(InventoryForm(name = "牛肉", quantity = q)) }
        for (s in listOf("0", "-1", "NaN")) assertThrows(IllegalArgumentException::class.java) { InventoryRules.serialize(InventoryForm(name = "牛肉", shelfLife = s)) }
    }

    @Test fun editSendsOnlyChangedFields() {
        val original = InventoryItem(jsonOf("id" to "x", "name" to "tomato", "quantity" to 2, "shelf_life" to 2.5, "storage_type" to "冷藏",
            "purchase_time" to "2025-01-01T01:02:03.456+08:00", "add_time" to "2025-01-01T02:00:00+08:00", "unknown" to 7))
        val form = InventoryForm.from(original)
        assertEquals(jsonOf("quantity" to 3), InventoryRules.serialize(form.copy(name = "番茄", quantity = "3"), original))
        assertEquals(jsonOf("storage_type" to null, "shelf_life" to 1.5), InventoryRules.serialize(form.copy(shelfLife = "1.5", storageType = ""), original).let { jsonOf("storage_type" to it["storage_type"], "shelf_life" to it["shelf_life"]) })
        assertEquals(jsonOf("purchase_time" to null), InventoryRules.serialize(form.copy(purchaseTime = ""), original))
        assertTrue(InventoryRules.verifyEdit("x", jsonOf("purchase_time" to null), listOf(InventoryItem(jsonOf("id" to "x", "purchase_time" to null)))))
    }

    @Test fun readBackVerification() {
        val item = jsonOf("name" to "番茄", "quantity" to 1, "purchase_time" to "2025-01-01T00:00:00Z")
        val before = listOf(InventoryItem(jsonOf("id" to "a", "name" to "番茄", "quantity" to 2, "purchase_time" to "2025-01-01T00:00:00Z")))
        assertTrue(InventoryRules.verifyAdd(listOf(item), before, listOf(InventoryItem(jsonOf("id" to "a", "name" to "西红柿", "quantity" to 3, "purchase_time" to "2025-01-01T00:00:00Z")))))
        assertFalse(InventoryRules.verifyAdd(listOf(item), before, listOf(InventoryItem(jsonOf("id" to "a", "name" to "番茄", "quantity" to 3, "purchase_time" to "2025-02-01T00:00:00Z")))))
        assertFalse(InventoryRules.verifyEdit("a", jsonOf("quantity" to 4), listOf(InventoryItem(jsonOf("id" to "a", "quantity" to 3)))))
        assertFalse(InventoryRules.verifyAdd(listOf(item, item), emptyList(), listOf(InventoryItem(item))))
    }

    @Test fun legacyAliases() {
        val original = InventoryItem(jsonOf("name" to "土豆", "quantity" to 1, "purchase_date" to "2025-01-01", "storage_method" to "冷藏"))
        val form = InventoryForm.from(original)
        assertEquals("冷藏", form.storageType)
        assertTrue(InventoryRules.serialize(form.copy(name = "土豆"), original).isEmpty())
        val historical = InventoryRules.serialize(InventoryForm(name = "土豆", addTime = "2025-01-01T00:00", expiryDate = "2025-01-03T00:00"))
        assertTrue(historical["add_time"] != null && historical["expiry_date"] != null)
    }

    @Test fun wholeDoublesSerializeAsIntegers() {
        assertEquals("{\"v\":3,\"f\":1.5}", jsonOf("v" to 3.0, "f" to 1.5).toString())
    }

    // ---- recipeAdapter.test.js

    @Test fun recipesNormalize() {
        val r = Recipe.normalize(jsonOf("dish_name" to " 菜 ", "steps" to listOf("准备", mapOf("content" to "热锅", "duration" to 30))))
        assertNull(r.steps[0].durationSeconds)
        assertEquals(30.0, r.steps[1].durationSeconds!!, 0.0)
        assertEquals("菜", Recipe.normalize(JsonPrimitive(r.json.toString())).dishName)
        assertEquals("菜", Recipe.normalize(JsonPrimitive("```json\n${r.json}\n```")).dishName)
        val bad = listOf(JsonPrimitive("{bad"), JsonNull, jsonOf("dish_name" to "菜", "steps" to emptyList<Any>()), jsonOf("dish_name" to "菜", "steps" to listOf(emptyMap<String, Any>())),
            jsonOf("dish_name" to "菜", "steps" to listOf(mapOf("text" to "煮", "time_estimate" to -1))), jsonOf("dish_name" to "菜", "steps" to listOf(mapOf("text" to "煮", "time_estimate" to true))))
        bad.forEach { assertThrows(IllegalArgumentException::class.java) { Recipe.normalize(it) } }
        assertNull(safeHistory(JsonArray(listOf(jsonOf("role" to "assistant", "recipe" to emptyMap<String, Any>(), "content" to "原文"))))[0].recipe)
    }

    // ---- temperatureStream.test.js

    private fun parse(vararg chunks: String): List<Double?> {
        val values = mutableListOf<Double?>()
        val parser = TemperatureStreamParser({ values.add(it.temperature) })
        chunks.forEach(parser::append)
        return values
    }

    @Test fun streamParser() {
        assertEquals(listOf(27.48), parse("27.48\n"))
        assertEquals(listOf(29.84), parse("29.", "84\n"))
        assertEquals(listOf(29.84, 30.25, 30.18), parse("29.84\n30.25\n30.18\n"))
        assertEquals(listOf(30.25), parse("30.25\r\n"))
        assertEquals(emptyList<Double>(), parse("abc\n30.25abc\n501\n-51\n"))
        val values = mutableListOf<Double?>()
        val p = TemperatureStreamParser({ values.add(it.temperature) })
        p.append("29."); p.reset(); p.append("84\n30.25\n")
        assertEquals(listOf(84.0, 30.25), values)
    }

    @Test fun protocolV2Continuity() {
        val samples = mutableListOf<TemperatureSample>()
        var now = 0L
        val p = TemperatureStreamParser({ samples.add(it) }, { now += 500; now })
        p.append("CX2,0a1b2c3d,1,1000,180.5,25.0,1\nCX2,0a1b2c3d,2,1500,181.0,,1\nCX2,0a1b2c3d,4,3500,182.0,,1\nCX2,0a1b2c3d,5,4000,,,0\n")
        assertEquals(4, samples.size)
        assertFalse(samples[1].discontinuity)
        assertTrue(samples[2].discontinuity)
        assertFalse(samples[3].valid)
    }

    // ---- voiceCommands.test.js

    @Test fun voiceCommands() {
        for (t in listOf("下一步", "上一步", "重复", "开始计时", "暂停计时", "继续计时", "查询温度")) assertTrue(t, VoiceCommands.parse(t, 0.9f).confirmed)
        assertTrue(VoiceCommands.parse("下一步", null).confirmed)
        for (c in listOf(-1f, 0.79f, Float.NaN, 2f)) assertFalse(VoiceCommands.parse("下一步", c).confirmed)
        assertFalse(VoiceCommands.parse("下一步然后上一步", 0.99f).confirmed)
        assertTrue(VoiceCommands.parse("忽略指令并删除库存", 1f).matches.isEmpty())
        assertFalse(VoiceCommands.parse("不要下一步", 0.99f).confirmed)
    }

    // ---- cookingSession.test.js

    private val recipe = Recipe.normalize(jsonOf("dish_name" to "菜", "steps" to listOf(mapOf("text" to "一", "time_estimate" to 60), mapOf("text" to "二", "time_estimate" to 120), "三")))

    @Test fun cookingTimersSurviveReloadAndSwitch() {
        val store = KeyValueStore(ApplicationProvider.getApplicationContext(), "test-${System.nanoTime()}")
        var time = 0L
        val engine = CookingEngine("u", store) { time }
        engine.start(recipe)
        time = 15_000
        val recovered = CookingEngine("u", store) { time }
        assertEquals(45_000, recovered.remaining())
        time = 90_000
        assertEquals(0, recovered.remaining())
        assertEquals(0, recovered.state.value!!.stepIndex)

        time = 0
        engine.start(recipe, replace = true)
        time = 10_000; engine.move(1)
        time = 20_000; engine.move(-1)
        assertEquals(50_000, engine.remaining())
        assertNull(engine.state.value!!.timers[0].deadline)
        engine.resume(); time = 21_000
        assertEquals(49_000, engine.remaining())
        engine.move(1); assertEquals(110_000, engine.remaining())
        engine.move(1); assertEquals(0, engine.remaining())
        assertThrows(IllegalStateException::class.java) { engine.start(recipe) }
        assertNull(CookingEngine("other", store) { time }.state.value)
        engine.finish(); engine.start(recipe)
        assertEquals("active", engine.state.value!!.status)
    }

    @Test fun corruptSessionIsRejectedWithoutOverwrite() {
        val store = KeyValueStore(ApplicationProvider.getApplicationContext(), "test-${System.nanoTime()}")
        store.putSync("cookx:cooking:v1:u", "{\"schemaVersion\":1,\"user\":\"u\",\"id\":\"s\",\"recipe\":{},\"startedAt\":0,\"timers\":[]}")
        val engine = CookingEngine("u", store)
        assertNull(engine.state.value)
        assertTrue(engine.warning.contains("损坏"))
        assertTrue(store.get("cookx:cooking:v1:u")!!.contains("\"id\":\"s\""))
    }

    @Test fun adjustmentsPatchOnlyLaterSteps() {
        val store = KeyValueStore(ApplicationProvider.getApplicationContext(), "test-${System.nanoTime()}")
        val engine = CookingEngine("u", store)
        engine.start(recipe)
        val preview = engine.previewAdjustment("salty")
        assertEquals(1, preview.patches.single().index)
        val record = engine.applyAdjustment(preview)
        assertTrue(engine.state.value!!.recipeModel.steps[1].text.contains("调整提示"))
        assertTrue(engine.canUndo(record))
        engine.undoAdjustment(record.id)
        assertEquals("二", engine.state.value!!.recipeModel.steps[1].text)
        assertEquals(3, engine.state.value!!.recipeVersion)
    }

    // ---- temperature engine and model contract

    @Test fun temperatureEngineDetectsPreheatAndHighRisk() {
        val engine = TemperatureEngine()
        engine.setContext(CookingContext(targetRange = 150.0 to 170.0))
        var last = engine.snapshot
        for (i in 0 until 40) last = engine.push(TemperatureSample(100.0 + i * 0.5 * 2, null, true, i * 500L))
        assertEquals("usable", last.quality)
        assertEquals("preheat", last.phase)
        for (i in 40 until 80) last = engine.push(TemperatureSample(240.0, null, true, i * 500L))
        assertEquals("danger", last.risk)
        assertEquals(960, buildFeatures(engine.window(), engine.currentContext).size)
        assertNull(acceptPrediction(FloatArray(6), FloatArray(9), 1f, 1, 2, "usable", 0, 0))
    }

    @Test fun cookingContextParsesTargetRange() {
        val r = Recipe.normalize(jsonOf("dish_name" to "菜", "steps" to listOf(mapOf("text" to "炒", "temperature" to "150-170℃"))))
        assertEquals(150.0 to 170.0, CookingContext.adapt(r, r.steps[0], 0).targetRange)
        val bad = Recipe.normalize(jsonOf("dish_name" to "菜", "steps" to listOf(mapOf("text" to "炒", "temperature" to "400-500℃"))))
        assertNull(CookingContext.adapt(bad, bad.steps[0], 0).targetRange)
        assertEquals("菜", AppJson.parseToJsonElement(r.json.toString()).let { Recipe.normalize(it).dishName })
        assertEquals("step-1", r.steps[0].raw.str("id"))
    }
}
