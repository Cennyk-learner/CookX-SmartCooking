package com.smartcooking.app.data

import com.smartcooking.app.core.ApiClient
import com.smartcooking.app.core.ApiException
import com.smartcooking.app.core.KeyValueStore
import com.smartcooking.app.core.asArray
import com.smartcooking.app.core.asObject
import com.smartcooking.app.core.bool
import com.smartcooking.app.core.jsonOf
import com.smartcooking.app.core.num
import com.smartcooking.app.core.obj
import com.smartcooking.app.core.objects
import com.smartcooking.app.core.str
import com.smartcooking.app.core.strings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** FreshFusion evaluation for one batch, straight from the server. */
data class Freshness(val raw: JsonObject) {
    val itemId: String get() = raw.str("item_id").orEmpty()
    val expired: Boolean get() = raw.bool("expired")
    val critical: Boolean get() = raw.bool("critical")
    val expiringSoon: Boolean get() = raw.bool("expiring_soon")
    val daysRemaining: Int? get() = raw.num("days_remaining")?.toInt()
    val expiryLabel: String get() = when (raw.str("expiry_state")) {
        "expired" -> "已过期"
        "expires_today" -> "今天到期"
        "critical_1d" -> "明天到期，请优先使用"
        "expiring_2d" -> "2 天内到期"
        "normal" -> "还有 ${daysRemaining ?: "—"} 天到期"
        else -> "到期信息待补充"
    }
    val label: String? get() = raw.str("freshness_label")
    val freshScore: Double? get() = raw.num("fresh_score")
    val confidence: Double? get() = raw.num("confidence_score")
    val confidenceLevel: String? get() = raw.str("confidence_level")
    val riskFlags: List<String> get() = raw.strings("risk_flags")
    val reasons: List<String> get() = raw.strings("reasons")
    val disclaimer: String? get() = raw.str("disclaimer")
    val evaluatedAt: String? get() = raw.str("evaluated_at")
    val algorithmVersion: String? get() = raw.str("algorithm_version")
    fun componentScore(key: String): Double? = raw.obj("component_scores")?.num(key)
    fun effectiveWeight(key: String): Double? = raw.obj("effective_weights")?.num(key)
    val timeDetails: JsonObject? get() = raw.obj("time_details")
    val storageDetails: JsonObject? get() = raw.obj("storage_details")
    val notes: List<String> get() = raw.strings("confidence_reasons") + raw.strings("data_quality_notes")

    val status: FreshnessStatus
        get() = when {
            expired -> FreshnessStatus(FreshLevel.EXPIRED, "已过期", "已过期")
            critical || expiringSoon -> FreshnessStatus(FreshLevel.SOON, expiryLabel, expiryLabel)
            componentScore("T") == null -> FreshnessStatus(FreshLevel.UNKNOWN, "信息不足", "保质期信息不足")
            else -> FreshnessStatus(FreshLevel.FRESH, label ?: "已评估", label ?: "已评估，请查看依据")
        }
}

enum class FreshLevel { FRESH, SOON, EXPIRED, UNKNOWN }
data class FreshnessStatus(val level: FreshLevel, val label: String, val description: String) {
    companion object {
        val Unknown = FreshnessStatus(FreshLevel.UNKNOWN, "未知", "尚无鲜度评估")
        fun of(detail: Freshness?) = detail?.status ?: Unknown
    }
}

sealed interface FreshnessState {
    data object Idle : FreshnessState
    data object Loading : FreshnessState
    data class Ready(val items: Map<String, Freshness>, val evaluatedAt: String?) : FreshnessState
    data class Failed(val message: String) : FreshnessState
}

fun FreshnessState.detail(id: String): Freshness? = (this as? FreshnessState.Ready)?.items?.get(id)

class InventoryRepository(private val api: ApiClient, private val store: KeyValueStore) {
    private val active = ConcurrentHashMap.newKeySet<String>()
    private val _changed = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Emits the user id whenever that user's inventory was changed from any screen. */
    val changed: SharedFlow<String> = _changed

    fun notifyChanged(user: String) { _changed.tryEmit(user) }

    private fun pendingKey(user: String) = "cookx:inventory-pending:v1:$user"
    fun hasPendingWrite(user: String) = store.contains(pendingKey(user))
    fun dismissPendingWrite(user: String) = store.removeSync(pendingKey(user))

    suspend fun read(user: String): List<InventoryItem> {
        val data = api.get("/inventory", mapOf("user_id" to user))
        val rows = data.asArray() ?: throw ApiException(data.asObject()?.str("message") ?: "库存读取失败")
        return rows.mapNotNull { it.asObject() }.map(::InventoryItem)
    }

    suspend fun freshness(user: String): FreshnessState.Ready {
        val data = api.get("/users/$user/inventory/freshness").asObject()
        if (data == null || data.str("status") == "error" || data["items"] !is JsonArray) throw ApiException(data?.str("message") ?: "鲜度响应格式异常")
        val items = data.objects("items").map(::Freshness).associateBy { it.itemId }
        return FreshnessState.Ready(items, data.str("evaluated_at"))
    }

    /**
     * Saves a new batch list, an edit, or confirmed recognition results. The transaction is
     * persisted before sending, so a lost response is verified by re-reading instead of re-sending.
     */
    suspend fun save(
        user: String,
        payload: JsonElement?,
        id: String? = null,
        recognition: Boolean = false,
        revision: String? = null,
    ): List<InventoryItem> {
        if (!active.add(user)) throw IllegalStateException("正在保存，请勿重复操作")
        try {
            var tx = store.getJson(pendingKey(user)).asObject()
            if (tx == null) {
                requireNotNull(payload) { "没有需要保存的内容" }
                val before = read(user)
                tx = jsonOf(
                    "payload" to payload, "id" to id, "recognition" to recognition, "revision" to revision,
                    "idempotencyKey" to UUID.randomUUID().toString(),
                    "before" to JsonArray(before.map { it.raw }),
                )
                if (!store.putJsonSync(pendingKey(user), tx)) throw IllegalStateException("无法在本机保存写入凭证，请释放存储空间后重试")
                try {
                    val headers = buildMap {
                        put("Idempotency-Key", tx.str("idempotencyKey")!!)
                        revision?.let { put("If-Match", it) }
                    }
                    val response = when {
                        id != null -> api.put("/inventory/$id", payload, mapOf("user_id" to user), headers = headers)
                        recognition -> api.post("/inventory/confirm-recognition", jsonOf("user_id" to user, "confirmed" to true, "items" to payload), headers = headers)
                        else -> api.post("/add-to-inventory", payload, mapOf("user_id" to user), headers = headers)
                    }.asObject()
                    if (response?.str("status") != "success") {
                        store.removeSync(pendingKey(user))
                        throw ApiException(response?.str("message") ?: "服务端未确认保存")
                    }
                } catch (e: ApiException) {
                    if (e.isClientError) store.removeSync(pendingKey(user))
                    throw e
                }
            }
            val rows = read(user)
            val txPayload = tx["payload"]
            val ok = when {
                tx.str("id") != null -> InventoryRules.verifyEdit(tx.str("id")!!, txPayload.asObject() ?: JsonObject(emptyMap()), rows)
                else -> InventoryRules.verifyAdd(
                    txPayload.asArray()?.mapNotNull { it.asObject() }.orEmpty(),
                    tx["before"].asArray()?.mapNotNull { it.asObject() }?.map(::InventoryItem).orEmpty(),
                    rows,
                )
            }
            if (!ok) throw IllegalStateException("保存结果未确认：可能发生批次归并或字段未保存。请核对库存，不要重复入库。")
            store.removeSync(pendingKey(user))
            notifyChanged(user)
            return rows
        } finally {
            active.remove(user)
        }
    }

    suspend fun delete(user: String, id: String, revision: String?) {
        val key = "cookx:delete-inventory:v1:$user:$id"
        var tx = store.getJson(key).asObject()
        if (tx == null) {
            tx = jsonOf("revision" to revision, "key" to UUID.randomUUID().toString())
            store.putJsonSync(key, tx)
        }
        try {
            val headers = buildMap {
                put("Idempotency-Key", tx.str("key")!!)
                tx.str("revision")?.let { put("If-Match", it) }
            }
            val response = api.delete("/inventory/$id", query = mapOf("user_id" to user), headers = headers).asObject()
            if (response?.str("status") != "success") throw ApiException(response?.str("message") ?: "移除未确认")
            read(user)
            store.removeSync(key)
            notifyChanged(user)
        } catch (e: ApiException) {
            if (e.isClientError) store.removeSync(key)
            throw e
        }
    }

    companion object {
        fun errorMessage(error: Throwable): String {
            val detail = (error as? ApiException)?.detail
            if (detail is JsonArray) {
                val labels = mapOf("name" to "名称", "quantity" to "数量", "purchase_time" to "购买时间", "expiry_date" to "到期时间",
                    "shelf_life" to "保质期", "storage_type" to "储存方式", "add_time" to "入库时间")
                return detail.mapNotNull { it.asObject() }.joinToString("；") { issue ->
                    val field = issue["loc"].asArray()?.lastOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "字段"
                    "${labels[field] ?: field}：${issue.str("msg") ?: "输入无效"}"
                }
            }
            return error.message ?: "保存失败，请保留表单重试"
        }
    }
}
