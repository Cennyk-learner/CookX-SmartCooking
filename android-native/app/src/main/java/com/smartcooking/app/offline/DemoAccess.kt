package com.smartcooking.app.offline

import com.smartcooking.app.core.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A credential and its matching endpoint must always be changed together. Never log this object. */
class DemoAccess(val apiKey: String, baseUrl: String) {
    val compatibleBaseUrl = normalizeBaseUrl(baseUrl)
    val nativeBaseUrl = compatibleBaseUrl.removeSuffix("/compatible-mode/v1") + "/api/v1"
    override fun toString() = "DemoAccess(redacted)"

    init { require(apiKey.length in 16..512 && apiKey.none { it.isWhitespace() }) { "请填写有效的百炼 API Key" } }

    companion object {
        const val SHARED_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        fun normalizeBaseUrl(value: String): String {
            val url = value.trim().trimEnd('/').toHttpUrlOrNull()
                ?: throw IllegalArgumentException("请输入完整的百炼 HTTPS 接口地址")
            val allowed = url.host == "dashscope.aliyuncs.com" ||
                url.host.matches(Regex("ws-[a-z0-9]+\\.cn-beijing\\.maas\\.aliyuncs\\.com"))
            require(url.scheme == "https" && url.port == 443 && allowed && url.username.isEmpty() && url.password.isEmpty() &&
                url.query == null && url.fragment == null && url.encodedPath == "/compatible-mode/v1") {
                "请使用北京地域百炼的 /compatible-mode/v1 HTTPS 地址"
            }
            return url.toString()
        }
    }
}

/** Explicitly disabling online service must survive restarts, even when the APK has a built-in key. */
internal class DemoAccessState(private val store: KeyValueStore, private val builtIn: DemoAccess?) {
    companion object {
        const val SAVED = "cookx:demo:qwen-access:v2"
        const val LEGACY = "cookx:demo:qwen-key:v1"
        const val DISABLED = "cookx:demo:qwen-disabled:v1"
    }
    val configured get() = store.get(DISABLED) != "1" && (store.contains(SAVED) || store.contains(LEGACY) || builtIn != null)
    val hasBuiltIn get() = builtIn != null
    val usingBuiltIn get() = configured && !store.contains(SAVED) && !store.contains(LEGACY) && builtIn != null
    fun resolve(decrypt: (String) -> String): DemoAccess {
        if (!configured) throw ApiException("在线服务尚未配置或已停用")
        store.get(SAVED)?.let {
            val saved = AppJson.parseToJsonElement(decrypt(it)).asObject() ?: throw ApiException("服务配置无法读取，请重新填写")
            return DemoAccess(saved.str("apiKey").orEmpty(), saved.str("baseUrl").orEmpty())
        }
        store.get(LEGACY)?.let { return DemoAccess(decrypt(it), DemoAccess.SHARED_BASE) }
        return builtIn ?: throw ApiException("尚未配置百炼 API Key")
    }
    fun save(access: DemoAccess, encrypt: (String) -> String) {
        val encoded = encrypt(jsonOf("apiKey" to access.apiKey, "baseUrl" to access.compatibleBaseUrl).toString())
        check(store.putSync(SAVED, encoded)) { "服务配置保存失败" }
        check(store.removeSync(LEGACY) && store.removeSync(DISABLED)) { "服务配置启用失败" }
    }
    fun disable() {
        check(store.putSync(DISABLED, "1")) { "服务停用失败" }
        check(store.removeSync(SAVED) && store.removeSync(LEGACY)) { "已停用服务，但保存的配置清理失败" }
    }
    fun restoreBuiltIn() {
        check(builtIn != null) { "此安装包没有内置服务" }
        check(store.removeSync(SAVED) && store.removeSync(LEGACY) && store.removeSync(DISABLED)) { "恢复内置服务失败" }
    }
}
