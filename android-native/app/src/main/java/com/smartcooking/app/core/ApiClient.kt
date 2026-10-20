package com.smartcooking.app.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An HTTP or transport failure with a message suitable for the user. */
class ApiException(
    message: String,
    val status: Int? = null,
    val body: JsonElement? = null,
    val isTimeout: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isClientError: Boolean get() = status != null && status in 400..499
    val detail: JsonElement? get() = body.asObject()?.get("detail")
}

class ApiClient(private val sessions: SessionManager, local: Interceptor? = null) {
    val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted when the API rejects the current token; the app returns to login. */
    val unauthorized: SharedFlow<Unit> = _unauthorized

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        val token = sessions.tokenFor(request.url.toString())
        val sent = if (token != null) request.newBuilder().header("Authorization", "Bearer $token").build() else request
        val response = chain.proceed(sent)
        if (response.code == 401 && sessions.isApiUrl(request.url.toString()) && sessions.session.value?.token == token) {
            sessions.clear()
            _unauthorized.tryEmit(Unit)
        }
        response
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .apply { if (local != null) addInterceptor(local) }
        .build()

    fun url(path: String, query: Map<String, Any?> = emptyMap()): String {
        val base = if (path.startsWith("http")) path else sessions.apiBase + (if (path.startsWith("/")) path else "/$path")
        val builder = base.toHttpUrl().newBuilder()
        for ((k, v) in query) if (v != null) builder.addQueryParameter(k, v.toString())
        return builder.build().toString()
    }

    suspend fun get(path: String, query: Map<String, Any?> = emptyMap(), timeoutMs: Long = 15_000, headers: Map<String, String> = emptyMap()): JsonElement =
        send("GET", path, query, null, timeoutMs, headers)

    suspend fun post(path: String, body: JsonElement? = null, query: Map<String, Any?> = emptyMap(), timeoutMs: Long = 15_000, headers: Map<String, String> = emptyMap()): JsonElement =
        send("POST", path, query, body, timeoutMs, headers)

    suspend fun put(path: String, body: JsonElement? = null, query: Map<String, Any?> = emptyMap(), timeoutMs: Long = 15_000, headers: Map<String, String> = emptyMap()): JsonElement =
        send("PUT", path, query, body, timeoutMs, headers)

    suspend fun delete(path: String, body: JsonElement? = null, query: Map<String, Any?> = emptyMap(), timeoutMs: Long = 15_000, headers: Map<String, String> = emptyMap()): JsonElement =
        send("DELETE", path, query, body, timeoutMs, headers)

    suspend fun send(
        method: String,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: JsonElement? = null,
        timeoutMs: Long = 15_000,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement {
        val requestBody: RequestBody? = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = Request.Builder().url(url(path, query)).method(method, requestBody)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(builder.build(), timeoutMs)
    }

    suspend fun upload(path: String, field: String, fileName: String, mime: String, bytes: ByteArray, timeoutMs: Long = 60_000, onProgress: (Float) -> Unit = {}): JsonElement {
        val part = ProgressBody(bytes.toRequestBody(mime.toMediaType()), onProgress)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart(field, fileName, part).build()
        return execute(Request.Builder().url(url(path)).post(multipart).build(), timeoutMs)
    }

    /** Downloads authorised media (private avatars, community photos, generated audio). */
    suspend fun bytes(urlOrPath: String, timeoutMs: Long = 15_000): ByteArray {
        val full = sessions.resolveUrl(urlOrPath) ?: throw ApiException("资源地址无效")
        val response = call(Request.Builder().url(full).get().build(), timeoutMs)
        if (!response.successful) throw ApiException("资源读取失败（${response.code}）", response.code)
        return response.body
    }

    private suspend fun execute(request: Request, timeoutMs: Long): JsonElement {
        val response = call(request, timeoutMs)
        val text = response.body.toString(Charsets.UTF_8)
        val json = if (text.isBlank()) JsonNull else runCatching { AppJson.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
        if (!response.successful) throw ApiException(errorMessage(json, response.code), response.code, json)
        if (json.asObject()?.str("status") == "error") throw ApiException(errorMessage(json, response.code), response.code, json)
        if (request.method != "GET" || request.url.encodedPath.endsWith("/recommend-recipe")) changed.tryEmit(Unit)
        return json
    }

    private class RawResponse(val code: Int, val successful: Boolean, val body: ByteArray)

    /**
     * Runs the call and reads the whole body on OkHttp's worker thread: reading a response body
     * is socket I/O, which Android forbids on the main thread (NetworkOnMainThreadException).
     */
    private suspend fun call(request: Request, timeoutMs: Long): RawResponse {
        val client = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(transportError(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    val raw = try {
                        response.use { RawResponse(it.code, it.isSuccessful, it.body.bytes()) }
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(transportError(e))
                        return
                    }
                    if (cont.isActive) cont.resume(raw)
                }
            })
        }
    }

    private fun transportError(e: IOException): ApiException = when (e) {
        is SocketTimeoutException, is InterruptedIOException -> ApiException("请求超时，请检查网络后重试", isTimeout = true, cause = e)
        is ConnectException, is UnknownHostException -> ApiException("联网服务连接失败，请检查手机网络或稍后重试", cause = e)
        else -> ApiException(e.message ?: "网络异常，请重试", cause = e)
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** Mirrors the web `apiError`: validation arrays become a single readable sentence. */
        fun errorMessage(json: JsonElement?, status: Int? = null): String {
            val obj = json.asObject()
            val detail = obj?.get("detail")
            return when {
                detail is JsonArray -> "输入不符合要求，请检查数量、单位与日期。"
                detail.asText() != null -> detail.asText()!!
                obj?.str("message") != null -> obj.str("message")!!
                status == 401 -> "登录已失效，请重新登录"
                status == 403 -> "没有权限执行此操作"
                status == 404 -> "请求的内容不存在或已变化"
                status == 409 -> "内容已被修改，请刷新后重试"
                status != null && status >= 500 -> "服务暂时不可用（$status），请稍后重试"
                else -> "请求失败，请重试"
            }
        }
    }
}

fun Throwable.userMessage(): String {
    // Unexpected failures are logged with their stack so they can be diagnosed from logcat.
    if (this !is ApiException) android.util.Log.e("CookX", "Unexpected failure", this)
    return when (this) {
        is ApiException -> message ?: "请求失败，请重试"
        is IllegalArgumentException, is IllegalStateException -> message ?: "操作无效"
        else -> message ?: "操作失败（${javaClass.simpleName}），请重试"
    }
}

private class ProgressBody(private val delegate: RequestBody, private val onProgress: (Float) -> Unit) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun writeTo(sink: okio.BufferedSink) {
        val total = contentLength().coerceAtLeast(1)
        val counting = object : okio.ForwardingSink(sink) {
            var written = 0L
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                onProgress((written.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}
