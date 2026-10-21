package com.smartcooking.app.offline

import com.smartcooking.app.core.*
import com.smartcooking.app.data.Recipe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KimiRecipeTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = KeyValueStore(context, "kimi-test-${UUID.randomUUID()}")
    private val sessions = SessionManager(store)
    private val sample = AppJson.parseToJsonElement(File("src/main/assets/offline/recipes.json").readText())
        .asArray()!!.first().asObject()!!
    private val requests = mutableListOf<Request>()
    private var code = 200
    private var finish = "stop"
    private var content = sample.toString()
    private var disconnected = false
    private val client = KimiRecipeGateway.defaultClient().newBuilder().addInterceptor { chain ->
        requests.add(chain.request())
        if (disconnected) throw IOException("offline")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .body(jsonOf("choices" to listOf(jsonOf("finish_reason" to finish,
                "message" to jsonOf("content" to content)))).toString().toResponseBody("application/json".toMediaType())).build()
    }.build()
    private val kimi = KimiRecipeGateway("test-only-kimi-credential", client)
    private val gateway = DemoGateway(store, DemoAccess("test-only-qwen-credential", DemoAccess.SHARED_BASE), kimi)
    private val runtime = LocalRuntime(context, store, sessions, gateway)
    private val api = ApiClient(sessions, runtime)

    private fun requestBody(index: Int = 0): JsonObject {
        val buffer = Buffer(); requests[index].body!!.writeTo(buffer)
        return AppJson.parseToJsonElement(buffer.readUtf8()).asObject()!!
    }

    @Test fun recipesUseOnlyKimiCredentialWithBoundedInstantJsonRequests() {
        val result = gateway.recipe("番茄炒蛋", listOf(jsonOf("name" to "番茄", "quantity" to 2,
            "unit" to "个", "owner" to "private-user", "image_url" to "private-photo")), EmptyObject)
        assertEquals("kimi-k2.6", result.str("model"))
        assertEquals("online", result.str("source"))
        assertEquals("api.moonshot.cn", requests.single().url.host)
        assertEquals("Bearer test-only-kimi-credential", requests.single().header("Authorization"))
        val body = requestBody()
        assertEquals("disabled", body.obj("thinking")?.str("type"))
        assertEquals("json_object", body.obj("response_format")?.str("type"))
        assertEquals(2000.0, body.num("max_tokens")!!, 0.0)
        assertFalse(body.toString().contains("private-user"))
        assertFalse(body.toString().contains("private-photo"))
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        DemoGateway.validateRecipe(result.obj("recipe")!!)
    }

    @Test fun recipeRoutePersistsRealSourceAndSuppliesPreviousRecipeForFollowup() = runBlocking {
        val saved = api.get("/recommend-recipe", mapOf("user_prompt" to "番茄炒蛋", "save_history" to true)).asObject()!!
        assertEquals("online", saved.str("source"))
        assertEquals("Kimi K2.6", saved.str("provider"))
        assertTrue(saved.str("notice")!!.contains("Kimi K2.6 已生成"))
        assertTrue(Recipe.normalize(saved["recipe"]).steps.isNotEmpty())
        val history = api.get("/chat-history").asArray()!!.last().asObject()!!
        assertEquals("online", history.str("source"))
        assertEquals(saved.obj("recipe"), history.obj("recipe"))
        val source = jsonOf("source_type" to "history", "source_id" to history.str("id"), "expected_source_version" to "1")
        val copy = api.post("/v3/recipes/copies", source).asObject()!!.obj("copy")!!
        val favorite = api.post("/v3/recipes/favorites", source).asObject()!!.obj("favorite")!!
        assertEquals(saved.obj("recipe"), copy.obj("recipe"))
        assertEquals(saved.obj("recipe"), favorite.obj("recipe"))
        assertTrue(api.get("/v3/recipes/copies/${copy.str("id")}/check").asObject()!!.objects("ingredients").isNotEmpty())
        api.get("/recommend-recipe", mapOf("user_prompt" to "改成少油、三人份", "save_history" to false))
        assertTrue(requestBody(1).toString().contains("最近菜谱"))
        assertTrue(requestBody(1).toString().contains(sample.str("dish_name")!!))
        assertEquals(history, api.get("/chat-history").asArray()!!.last())
    }

    @Test fun exhaustedQuotaFallsBackExplicitlyAndMakesOnlyOneRequest() = runBlocking {
        code = 429
        val result = api.get("/recommend-recipe", mapOf("user_prompt" to "番茄炒蛋")).asObject()!!
        assertEquals("built_in", result.str("source"))
        assertNull(result.str("provider"))
        assertTrue(result.str("notice")!!.contains("额度不足"))
        assertTrue(result.str("notice")!!.contains("不是本次 AI 生成结果"))
        assertEquals(1, requests.size)
    }

    @Test fun invalidOrTruncatedOutputCannotBeRecordedAsOnlineRecipe() = runBlocking {
        for ((reason, body) in listOf("length" to sample.toString(), "stop" to "{\"dish_name\":\"不完整\"}")) {
            finish = reason; content = body
            val result = api.get("/recommend-recipe", mapOf("user_prompt" to "做一道菜")).asObject()!!
            assertEquals("built_in", result.str("source"))
            assertFalse(result.str("notice")!!.contains("Kimi K2.6 已生成"))
        }
        assertTrue(runtime.engine.rows("chat").filter { it.str("provider") == "Kimi K2.6" }.isEmpty())
    }

    @Test fun disabledServiceDoesNotUseBundledKimiUntilExplicitlyRestored() {
        gateway.clear()
        assertFalse(gateway.recipeConfigured)
        assertTrue(runCatching { gateway.recipe("做一道菜", emptyList(), EmptyObject) }.isFailure)
        assertTrue(requests.isEmpty())
        gateway.restoreBuiltIn()
        assertTrue(gateway.recipeConfigured)
        assertEquals("online", gateway.recipe("做一道菜", emptyList(), EmptyObject).str("source"))
    }

    @Test fun disconnectedServiceAndMissingCredentialLeaveLocalRecipesUsable() = runBlocking {
        disconnected = true
        val result = api.get("/recommend-recipe", mapOf("user_prompt" to "做一道菜")).asObject()!!
        assertEquals("built_in", result.str("source"))
        assertTrue(result.str("notice")!!.contains("检查手机网络"))
        assertEquals(1, requests.size)
        val missing = KimiRecipeGateway(null, client)
        assertFalse(missing.configured)
        assertTrue(runCatching { missing.recipe("做一道菜", emptyList(), EmptyObject) }.isFailure)
        assertEquals(1, requests.size)
    }
}
