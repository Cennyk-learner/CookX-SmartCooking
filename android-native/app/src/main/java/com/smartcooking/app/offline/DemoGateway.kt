package com.smartcooking.app.offline

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.smartcooking.app.BuildConfig
import com.smartcooking.app.core.*
import com.smartcooking.app.data.Recipe
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Direct Qwen access; saved overrides use Keystore, optional release defaults come from private build inputs. */
class DemoGateway(private val store: KeyValueStore, builtIn: DemoAccess? = bundledAccess()) {
    private val accessState = DemoAccessState(store, builtIn)
    private val keyAlias = "cookx-qwen-device-key"
    val configured: Boolean get() = accessState.configured
    val hasBuiltIn: Boolean get() = accessState.hasBuiltIn
    val usingBuiltIn: Boolean get() = accessState.usingBuiltIn
    val compatibleBaseUrl: String get() = runCatching { accessState.resolve(::decrypt).compatibleBaseUrl }
        .getOrDefault(BuildConfig.DEMO_COMPATIBLE_BASE_URL)
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private fun encryptionKey(): SecretKey {
        val vault = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (vault.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun configure(apiKey: String, baseUrl: String = compatibleBaseUrl) {
        accessState.save(DemoAccess(apiKey.trim(), baseUrl), ::encrypt)
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    fun clear() = accessState.disable()
    fun restoreBuiltIn() = accessState.restoreBuiltIn()
    private fun decrypt(encrypted: String): String {
        return try {
            val parts = encrypted.split('.')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw ApiException("本机密钥无法读取，请重新填写 API Key") }
    }
    private fun completion(model: String, messages: List<JsonObject>, maxTokens: Int = 4000): String {
        val access = accessState.resolve(::decrypt)
        val body = jsonOf("model" to model, "messages" to messages, "max_tokens" to maxTokens, "enable_thinking" to false)
            .toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("${access.compatibleBaseUrl}/chat/completions")
            .header("Authorization", "Bearer ${access.apiKey}").post(body).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(when (response.code) {
                401, 403 -> "API Key 无效或未开通模型权限，请检查北京地域的百炼配置"
                429 -> "AI 服务请求较多或额度不足，请稍后重试"
                else -> "AI 服务暂不可用（${response.code}）"
            }, response.code)
            val raw = response.body.string()
            if (raw.length > 200_000) throw ApiException("AI 返回内容过长")
            return AppJson.parseToJsonElement(raw).asObject()?.objects("choices")?.firstOrNull()?.obj("message")?.str("content")
                ?: throw ApiException("AI 未返回有效内容")
        }
    }
    fun health(): JsonObject {
        completion("qwen-plus", listOf(jsonOf("role" to "user", "content" to "请只回复 OK")), 8)
        return jsonOf("recipe" to true, "vision_tested" to false, "tts_tested" to false)
    }
    /** Short command audio is sent directly to the configured Qwen workspace; no PC service or audio URL is required. */
    fun transcribe(audio: ByteArray, mimeType: String = "audio/wav"): String {
        require(audio.size in 45..300_000) { "录音为空或过长，请重试" }
        require(mimeType in setOf("audio/wav", "audio/mpeg")) { "不支持的录音格式" }
        val access = accessState.resolve(::decrypt)
        val payload = jsonOf("model" to "qwen3-asr-flash", "stream" to false,
            "messages" to listOf(jsonOf("role" to "user", "content" to listOf(jsonOf("type" to "input_audio",
                "input_audio" to jsonOf("data" to "data:$mimeType;base64,${Base64.encodeToString(audio, Base64.NO_WRAP)}"))))),
            "asr_options" to jsonOf("language" to "zh", "enable_itn" to false))
        val request = Request.Builder().url("${access.compatibleBaseUrl}/chat/completions")
            .header("Authorization", "Bearer ${access.apiKey}").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return http.newBuilder().callTimeout(15, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(when(response.code) {
                401, 403 -> "语音模型权限不可用，请检查百炼配置"
                429 -> "语音服务繁忙，请稍后重试"
                else -> "语音服务暂不可用（${response.code}）"
            }, response.code)
            val body = response.body.string()
            require(body.length <= 50_000) { "语音识别结果异常" }
            AppJson.parseToJsonElement(body).asObject()?.objects("choices")?.firstOrNull()?.obj("message")?.str("content")
                ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: throw ApiException("未识别到指令，请重试")
        }
    }
    fun tts(text: String): ByteArray {
        require(text.isNotBlank() && text.length <= 600) { "本步骤超过在线播报字数限制，请使用系统语音" }
        val access = accessState.resolve(::decrypt)
        val input = jsonOf("model" to "qwen3-tts-flash", "input" to jsonOf("text" to text, "voice" to "Cherry", "language_type" to "Chinese"))
        // Qwen TTS's documented Beijing endpoint accepts same-region workspace keys.
        // Some workspace gateways reject this native route while chat/vision remain available.
        val request = Request.Builder().url("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
            .header("Authorization", "Bearer ${access.apiKey}").post(input.toString().toRequestBody("application/json".toMediaType())).build()
        val url = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("在线播报暂不可用，请检查模型权限和额度", response.code)
            AppJson.parseToJsonElement(response.body.string()).asObject()?.obj("output")?.obj("audio")?.str("url")
                ?: throw ApiException("在线语音未返回音频")
        }.toHttpUrl()
        require(url.host == "dashscope-result-bj.oss-cn-beijing.aliyuncs.com" && url.username.isEmpty() && url.password.isEmpty()) { "音频来源无效" }
        // Signed OSS URL is fetched with HTTPS and without the model API credential.
        val download = Request.Builder().url(url.newBuilder().scheme("https").build()).get().build()
        return http.newCall(download).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("音频下载失败")
            val output = java.io.ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk); if (count < 0) break
                    require(output.size() + count <= 10_000_000) { "音频文件过大" }
                    output.write(chunk, 0, count)
                }
            }
            output.toByteArray()
        }
    }
    fun recipe(prompt: String, inventory: List<JsonObject>, context: JsonObject): JsonObject {
        require(prompt.length in 1..4000) { "烹饪需求请控制在 4000 字以内" }
        val system = "你是 CookX 厨房助手。用中文返回一个完整 JSON 菜谱，不要 Markdown。包含 dish_name、ingredients_list（每项 item、amount、unit）、steps（每项 text、time_estimate 秒数）、used_ingredients 和 missing 字符串数组。缺少的食材必须如实列出。不能自动修改库存、计时或给出食品安全结论。未提供的营养数据不要编造。"
        val content = jsonOf("需求" to prompt, "可用库存" to inventory.map { it.without("id", "owner", "_revision") }, "当前烹饪" to context)
        val raw = completion("qwen-plus", listOf(jsonOf("role" to "system", "content" to system), jsonOf("role" to "user", "content" to content.toString())))
        val recipe = parseObject(raw)
        validateRecipe(recipe)
        return jsonOf("recipe" to recipe, "source" to "online")
    }
    fun recognize(body: RequestBody): JsonObject {
        val part = (body as? MultipartBody)?.parts?.firstOrNull()?.body ?: throw ApiException("请选择有效图片")
        val buffer = Buffer(); part.writeTo(buffer)
        val bytes = buffer.readByteArray()
        require(bytes.size <= 10 * 1024 * 1024) { "图片不能超过 10 MB" }
        val data = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val raw = completion("qwen3-vl-plus", listOf(jsonOf("role" to "user", "content" to listOf(
            jsonOf("type" to "image_url", "image_url" to jsonOf("url" to data)),
            jsonOf("type" to "text", "text" to "识别图片中的食材，用中文只返回 JSON：{\"detected\":[{\"name\":\"名称\",\"quantity\":1}]}。忽略容器，不推断保质期、日期或鲜度；没有食材时返回空数组。")
        ))), 2000)
        val result = parseObject(raw)
        require(result["detected"] is JsonArray && result["detected"].asArray()!!.all { it is JsonObject }) { "识别结果格式无效，请重新识别" }
        val detected = result.objects("detected").take(100).map { item ->
            val name = item.str("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 } ?: throw ApiException("识别结果名称无效")
            val quantity = item.num("quantity") ?: 1.0
            require(quantity.isFinite() && quantity > 0 && quantity <= 10000) { "识别数量无效，请手动核对" }
            jsonOf("name" to name, "quantity" to kotlin.math.ceil(quantity), "freshness" to "待补充储存信息", "data_quality_notes" to listOf("数量为图像识别估计，请确认名称、用量与储存日期"))
        }
        return jsonOf("status" to "success", "detected" to detected, "source" to "online")
    }
    companion object {
        private fun bundledAccess(): DemoAccess? = BundledDemoAccess.decode(
            BuildConfig.DEMO_ACCESS_PAYLOAD, BuildConfig.DEMO_ACCESS_SECRET, BuildConfig.DEMO_COMPATIBLE_BASE_URL)
        fun parseObject(raw: String): JsonObject {
            val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start || raw.length > 100_000) throw ApiException("AI 返回格式不完整")
            return runCatching { AppJson.parseToJsonElement(raw.substring(start, end + 1)).asObject() }.getOrNull()
                ?: throw ApiException("AI 返回格式不完整")
        }
        fun validateRecipe(recipe: JsonObject) {
            val normalized = Recipe.normalize(recipe)
            require((recipe["dish_name"] as? JsonPrimitive)?.isString == true && normalized.dishName.length <= 100 && normalized.steps.size in 1..40 && normalized.steps.all { it.text.length <= 3000 }) { "AI 菜谱的名称或步骤格式无效" }
            val list = recipe["ingredients_list"] as? JsonArray
            require(list != null && list.size in 1..100 && list.all { row ->
                val i = row.asObject()
                val amount = i?.str("amount"); val number = amount?.toDoubleOrNull()
                i != null && (i["item"] as? JsonPrimitive)?.isString == true && !i.str("item").isNullOrBlank() &&
                    i.str("item")!!.length <= 80 && !i.str("unit").isNullOrBlank() && !amount.isNullOrBlank() && amount.length <= 40 &&
                    (number == null || number.isFinite() && number > 0)
            }) { "AI 菜谱缺少有效食材、数量或单位" }
            listOf("used_ingredients", "missing").forEach { key ->
                require(recipe[key] is JsonArray && recipe[key].asArray()!!.all { (it as? JsonPrimitive)?.isString == true }) { "AI 菜谱缺少已用或待补食材清单" }
            }
        }
    }
}
