package com.smartcooking.app.offline

import com.smartcooking.app.core.*
import kotlinx.serialization.json.JsonObject
import java.time.Instant

internal fun LocalEngine.mediaReferenced(id: String) = rows("posts").any { id in it.strings("image_ids") } || rows("users").any { it.str("avatar")?.endsWith("/$id") == true }
internal fun LocalEngine.validateMedia(ids: List<String>, user: String) {
    if (ids.size > 4 || ids.distinct().size != ids.size) fail(422, "每个作品最多四张不同图片")
    ids.filterNot { it in listOf("dish-0", "dish-1", "dish-2") }.forEach { own(find("media", it), user) }
}
internal fun LocalEngine.mediaRoute(m: String, s: List<String>, b: JsonObject, u: String): JsonObject {
    if (s.size == 2) {
        if (m != "POST" || !b.str("id").orEmpty().matches(Regex("[a-zA-Z0-9-]+"))) fail(422, "图片编号无效")
        val row = jsonOf("id" to b.str("id"), "owner" to u, "created_at" to now(), "mime" to b.str("mime"))
        add("media", row); return ok("id" to row.str("id"))
    }
    val row = find("media", s[2])
    if (m == "DELETE") {
        own(row, u)
        if (mediaReferenced(s[2])) return ok("retained" to true)
        remove("media", s[2]); return ok()
    }
    if (row.str("owner") != u && !mediaReferenced(s[2])) fail(403, "无法访问未发布图片")
    return row
}
@Synchronized internal fun LocalEngine.sweepMedia(): Set<String> {
    val before = db
    val keep = rows("media").filter { mediaReferenced(it.str("id").orEmpty()) ||
        (it.str("created_at")?.let { t -> runCatching { Instant.parse(t).toEpochMilli() }.getOrNull() } ?: 0L) > clock() - 86400000L }
    set("media", keep)
    try { if (db != before) persistMedia() } catch (e: Exception) { db = before; throw e }
    return keep.mapNotNull { it.str("id") }.toSet()
}
