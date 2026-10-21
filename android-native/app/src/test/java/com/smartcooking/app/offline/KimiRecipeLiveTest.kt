package com.smartcooking.app.offline

import com.smartcooking.app.core.*
import com.smartcooking.app.data.Recipe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Properties
import java.util.UUID

/** One opted-in generation, no health generations, no retries. Ordinary tests never spend quota. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KimiRecipeLiveTest {
    @Test fun oneEncryptedCredentialRecipeThroughAppTransport() = runBlocking {
        assumeTrue("Set COOKX_KIMI_LIVE_VERIFY=1 explicitly", System.getenv("COOKX_KIMI_LIVE_VERIFY") == "1")
        val props = Properties().apply { File("../demo-private.properties").inputStream().use { load(it) } }
        val bundle = Properties().apply { File("../.gradle/cookx-kimi-bundle.properties").inputStream().use { load(it) } }
        val key = BundledDemoAccess.decodeKey(bundle.getProperty("payload"), bundle.getProperty("secret"), KimiRecipeGateway.BASE_URL)
        assertTrue("Private input must match the authenticated encrypted bundle", key == props.getProperty("kimiApiKey"))
        var requests = 0
        var usage: JsonObject? = null
        val http = KimiRecipeGateway.defaultClient().newBuilder().addInterceptor { chain ->
            requests++
            chain.proceed(chain.request()).also { response ->
                usage = AppJson.parseToJsonElement(response.peekBody(200_000).string()).asObject()?.obj("usage")
            }
        }.build()
        val context = RuntimeEnvironment.getApplication()
        val store = KeyValueStore(context, "kimi-live-${UUID.randomUUID()}")
        val sessions = SessionManager(store)
        val runtime = LocalRuntime(context, store, sessions, DemoGateway(store, builtIn = null, kimi = KimiRecipeGateway(key, http)))
        val started = System.nanoTime()
        val reply = ApiClient(sessions, runtime).get("/recommend-recipe",
            mapOf("user_prompt" to "做两人份番茄炒蛋，只需三至四个简明步骤，食材用量和缺少食材写清楚。", "save_history" to true), timeoutMs = 45_000).asObject()!!
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(reply.str("notice"), "online", reply.str("source"))
        assertEquals(1, requests)
        val recipe = Recipe.normalize(reply["recipe"])
        assertTrue(recipe.steps.isNotEmpty())
        assertEquals("Kimi K2.6", runtime.engine.rows("chat").last().str("provider"))
        val evidence = jsonOf("model" to KimiRecipeGateway.MODEL, "generationRequests" to requests,
            "usage" to usage, "elapsedMs" to elapsedMs, "dish" to recipe.dishName, "steps" to recipe.steps.size,
            "appTransportVerified" to true, "encryptedCredentialVerified" to true, "physicalDeviceVerified" to false)
        File("../../tmp/kimi-2.2.4-live.json").writeText(evidence.toString())
        println("KIMI_RECIPE_LIVE_OK $evidence")
    }
}
