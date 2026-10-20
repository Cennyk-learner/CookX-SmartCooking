package com.smartcooking.app.core

import com.smartcooking.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.net.URI

data class UserSession(
    val user: JsonObject,
    val token: String?,
    val expiresAtMillis: Long?,
) {
    val userId: String get() = user.str("id").orEmpty()
    val displayName: String get() = user.str("nickname")?.takeIf { it.isNotBlank() } ?: user.str("username").orEmpty()
    val isAdmin: Boolean get() = user.str("role") == "admin"
}

/**
 * Owns the backend address and the signed-in account. Tokens are only valid for the origin
 * that issued them; changing the backend address signs the device out.
 */
class SessionManager(private val store: KeyValueStore) {
    var beforeSessionChange: () -> Unit = {}
    private val _session = MutableStateFlow(load())
    val session: StateFlow<UserSession?> = _session.asStateFlow()

    val currentUserId: String? get() = _session.value?.userId?.takeIf { it.isNotBlank() }

    val backendOrigin: String
        get() = store.get(SERVER_KEY)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_BACKEND_ORIGIN.trimEnd('/')

    val savedBackendOrigin: String? get() = store.get(SERVER_KEY)
    val apiBase: String get() = "$backendOrigin/api"

    private fun load(): UserSession? {
        val user = store.getJson(USER_KEY).asObject() ?: return null
        if (user.str("id").isNullOrBlank()) return null
        val token = store.getJson(TOKEN_KEY).asObject()
        val valid = token != null &&
            token.str("user_id") == user.str("id") &&
            token.str("origin") == backendOrigin &&
            (token.num("expires_at") ?: 0.0) * 1000 > System.currentTimeMillis()
        return UserSession(
            user = user,
            token = if (valid) token!!.str("access_token") else null,
            expiresAtMillis = if (valid) ((token!!.num("expires_at") ?: 0.0) * 1000).toLong() else null,
        )
    }

    /** True for this app's backend API; tokens are never sent anywhere else. */
    fun isApiUrl(url: String): Boolean {
        val target = runCatching { URI(url) }.getOrNull() ?: return false
        val origin = runCatching { URI(backendOrigin) }.getOrNull() ?: return false
        val sameOrigin = target.scheme == origin.scheme && target.host == origin.host && effectivePort(target) == effectivePort(origin)
        return sameOrigin && (target.path ?: "").startsWith("/api/")
    }

    fun tokenFor(url: String): String? {
        val s = _session.value ?: return null
        val token = s.token ?: return null
        if ((s.expiresAtMillis ?: 0) <= System.currentTimeMillis()) return null
        return if (isApiUrl(url)) token else null
    }

    fun save(loginResponse: JsonObject) {
        val user = loginResponse.obj("user") ?: return
        if (user.str("id") != currentUserId) beforeSessionChange()
        store.putSync(TOKEN_KEY, null)
        val token = loginResponse.str("access_token")
        if (token != null) {
            store.putJsonSync(
                TOKEN_KEY,
                jsonOf(
                    "access_token" to token,
                    "expires_at" to loginResponse.num("expires_at"),
                    "user_id" to user.str("id"),
                    "origin" to backendOrigin,
                ),
            )
        }
        store.putJsonSync(USER_KEY, user)
        _session.value = load()
    }

    fun updateUser(user: JsonObject) {
        val current = _session.value ?: return
        if (user.str("id") != current.userId) return
        val merged = JsonObject(current.user + user)
        store.putJsonSync(USER_KEY, merged)
        _session.value = current.copy(user = merged)
    }

    fun clear() {
        beforeSessionChange()
        store.putSync(TOKEN_KEY, null)
        store.putSync(USER_KEY, null)
        _session.value = null
    }

    /** Validates like the web client: HTTP(S) origin only, no path, credentials or query. */
    fun normalizeOrigin(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) return ""
        val uri = try { URI(text) } catch (e: Exception) { throw IllegalArgumentException("请填写完整地址，例如 http://192.168.1.20:8000") }
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) throw IllegalArgumentException("请填写完整地址，例如 http://192.168.1.20:8000")
        if (uri.userInfo != null || uri.query != null || uri.fragment != null || (uri.path ?: "") !in listOf("", "/")) {
            throw IllegalArgumentException("仅填写 HTTP(S) 服务器地址和端口，不含路径、账号或参数")
        }
        return "${uri.scheme}://${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}"
    }

    /** Saves the backend address and signs out on this device. */
    fun setBackendOrigin(value: String) {
        val origin = normalizeOrigin(value)
        store.putSync(SERVER_KEY, origin.ifBlank { null })
        clear()
    }

    fun resolveUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val local = Regex("^https?://(?:127\\.0\\.0\\.1|localhost):8000(?=/|$)", RegexOption.IGNORE_CASE)
        if (local.containsMatchIn(url)) return local.replace(url, backendOrigin)
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) return url
        return backendOrigin + if (url.startsWith("/")) url else "/$url"
    }

    private fun effectivePort(uri: URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80

    companion object {
        const val SERVER_KEY = "cookx:backend-origin:v1"
        private const val TOKEN_KEY = "cookx:session:v1"
        private const val USER_KEY = "user"
    }
}
