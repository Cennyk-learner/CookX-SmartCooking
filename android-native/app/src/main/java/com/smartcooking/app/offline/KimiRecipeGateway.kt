package com.smartcooking.app.offline

import com.smartcooking.app.BuildConfig
import com.smartcooking.app.core.*
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Kimi is used only for recipes. Its credential never goes to the Qwen vision/audio endpoints. */
class KimiRecipeGateway(
    private val apiKey: String? = BundledDemoAccess.decodeKey(
        BuildConfig.KIMI_ACCESS_PAYLOAD, BuildConfig.KIMI_ACCESS_SECRET, BASE_URL),
    private val http: OkHttpClient = defaultClient(),
) {
    val configured get() = !apiKey.isNullOrBlank()

    fun recipe(prompt: String, inventory: List<JsonObject>, context: JsonObject): JsonObject {
        require(prompt.isNotBlank() && prompt.length <= 4000) { "烹饪需求请控制在 4000 字以内" }
        if (!configured) throw ApiException("此安装包尚未配置 Kimi 菜谱服务")
        val system = "你是 CookX 厨房助手，用中文生成一份能直接开始烹饪的菜谱。只返回 JSON，不要 Markdown。" +
            "字段：dish_name 字符串；ingredients_list 数组，每项 item 食材名、amount 用量字符串、unit 单位；" +
            "steps 数组，每项 text 操作说明、time_estimate 预计秒数；used_ingredients 和 missing 为食材名字符串数组。" +
            "给出 3 至 8 个具体步骤，每步不超过 100 字。按用户人数、口味和禁忌调整；修改上一道菜时返回修改后的完整菜谱。" +
            "库存计数不是克数，单位不能混同；缺少或用量不足的食材如实列入 missing，常用调料也不能默认已有。" +
            "不编造营养数据，不自动修改库存或计时，不根据红外读数断言食物已熟或安全。"
        // Send only information relevant to cooking; omit local identifiers, pictures and audit records.
        val content = jsonOf("需求" to prompt, "可用库存" to inventory.take(80).map { row ->
            jsonOf("name" to row.str("name"), "quantity" to row.num("quantity"),
                "unit" to (row.str("unit") ?: "库存计数"), "storage_type" to row.str("storage_type"))
        }, "烹饪上下文" to context)
        val payload = jsonOf("model" to MODEL, "stream" to false, "thinking" to jsonOf("type" to "disabled"),
            "max_tokens" to 2000, "response_format" to jsonOf("type" to "json_object"),
            "messages" to listOf(jsonOf("role" to "system", "content" to system),
                jsonOf("role" to "user", "content" to content.toString())))
        val request = Request.Builder().url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val response = try { http.newCall(request).execute() } catch (_: IOException) {
            throw ApiException("Kimi 连接失败或超时，请检查手机网络")
        }
        return response.use {
            if (!it.isSuccessful) throw ApiException(when (it.code) {
                401, 403 -> "Kimi 密钥无效或没有模型权限"
                402, 429 -> "Kimi 额度不足或请求受限，请稍后重试"
                400, 404 -> "Kimi 模型或请求暂不可用"
                else -> "Kimi 服务暂不可用（${it.code}）"
            }, it.code)
            val source = it.body.source().apply { request(200_001) }
            if (source.buffer.size > 200_000) throw ApiException("Kimi 返回内容过长")
            val raw = source.buffer.readUtf8()
            val choice = AppJson.parseToJsonElement(raw).asObject()?.objects("choices")?.firstOrNull()
                ?: throw ApiException("Kimi 未返回菜谱")
            if (choice.str("finish_reason") != "stop") throw ApiException("Kimi 菜谱未完整生成，请缩短需求后重试")
            val recipe = DemoGateway.parseObject(choice.obj("message")?.str("content") ?: throw ApiException("Kimi 菜谱为空"))
            DemoGateway.validateRecipe(recipe)
            jsonOf("recipe" to recipe, "source" to "online", "model" to MODEL)
        }
    }

    companion object {
        const val MODEL = "kimi-k2.6"
        const val LABEL = "Kimi K2.6"
        const val BASE_URL = "https://api.moonshot.cn/v1"
        internal fun defaultClient() = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    }
}
