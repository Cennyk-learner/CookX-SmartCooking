package com.smartcooking.app.offline

import com.smartcooking.app.core.*
import com.smartcooking.app.data.InventoryRules
import kotlinx.serialization.json.JsonObject
import java.time.*

private fun localDate(value: String?): String? = value?.let {
    runCatching { Instant.parse(it).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(it).toLocalDate().toString() }.getOrNull()
        ?: runCatching { LocalDate.parse(it).toString() }.getOrNull()
}
internal fun canonicalUnit(unit: String?) = when(unit?.trim()?.lowercase()) { "g", "克" -> "克"; "kg", "千克", "公斤" -> "千克"; "ml", "毫升" -> "毫升"; "l", "升" -> "升"; null, "" -> "库存计数"; else -> unit.trim() }

internal fun batchKey(row: JsonObject): List<String?> = listOf(
    row.str("owner"), InventoryRules.canonicalName(row.str("name")), localDate(row.str("add_time")),
    canonicalUnit(row.str("unit")), row.str("storage_type"), row.num("shelf_life")?.toString(),
    localDate(row.str("expiry_date")), localDate(row.str("purchase_time")))

/** All four entry points share this merge, inside LocalEngine's transaction lock. */
internal fun LocalEngine.mergeStock(input: JsonObject, owner: String): JsonObject {
    val next = newStock(input.with("unit" to canonicalUnit(input.str("unit"))), owner)
    val key = batchKey(next)
    // Missing storage or expiry data cannot establish that two physical batches are the same.
    val old = stock(owner).firstOrNull { row ->
        key[2] != null && key[4] != null && (key[5] != null || key[6] != null) && batchKey(row) == key
    }
    val merged = if (old == null) next else updated(old, jsonOf("quantity" to old.num("quantity")!! + next.num("quantity")!!,
        "sources" to ((old.objects("sources") + listOfNotNull(old.obj("source"), next.obj("source"))).distinct()))).with("_revision" to id())
    if (old == null) add("stock", merged) else replace("stock", merged)
    bumpStock(owner)
    return merged
}

internal fun LocalEngine.syncNotices(user: String) {
    val active = mutableSetOf<String>()
    for (row in stock(user)) {
        val detail = fresh(row)
        val state = detail.str("expiry_state")
        if ((row.num("quantity") ?: 0.0) <= 0 || state !in listOf("expiring_2d", "critical_1d", "expires_today", "expired")) continue
        val key = "expiry:$user:${row.str("id")}"; active.add(key)
        val old = rows("notices").firstOrNull { it.str("id") == key }
        val label = when (state) { "expiring_2d" -> "2 天内到期"; "critical_1d" -> "明天到期"; "expires_today" -> "今天到期"; else -> "已过期" }
        val changed = old?.str("state") != state
        val notice = jsonOf("id" to key, "owner" to user, "type" to "expire", "title" to "${row.str("name")}$label",
            "content" to if (state == "expired") "已超过记录的到期时间，已排除出可用库存与菜单。请核对并处理该批次。" else "请优先安排使用，并核对实际储存情况。",
            "state" to state, "entity_type" to "inventory", "entity_id" to row.str("id"), "action" to "open_inventory",
            "created_at" to (old?.str("created_at") ?: now()), "time" to (old?.str("time") ?: now()), "updated_at" to if(changed) now() else old?.str("updated_at"),
            "read_at" to if(changed) null else old?.str("read_at"), "is_read" to (!changed && old?.bool("is_read") == true),
            "archived" to (!changed && old?.bool("archived") == true))
        if (old == null) add("notices", notice) else replace("notices", notice)
    }
    set("notices", rows("notices").map { if (it.str("owner") == user && it.str("type") == "expire" && it.str("id") !in active) it.with("archived" to true, "is_read" to true) else it })
}

internal fun LocalEngine.notifyFamily(family: String, actor: String, title: String, entity: String) {
    rows("members").filter { it.str("family") == family }.forEach { member ->
        add("notices", jsonOf("id" to id(), "owner" to member.str("user_id"), "type" to "system", "title" to title,
            "content" to "${find("users", actor).str("nickname")}更新了家庭采购。", "entity_type" to "shopping", "entity_id" to entity,
            "action" to "open_shopping", "is_read" to false, "created_at" to now(), "time" to now()))
    }
}
