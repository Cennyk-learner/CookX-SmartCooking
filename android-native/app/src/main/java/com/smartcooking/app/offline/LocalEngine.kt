package com.smartcooking.app.offline

import com.smartcooking.app.core.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

internal class LocalFailure(val status: Int, message: String) : RuntimeException(message)
data class LocalReply(val code: Int, val body: JsonElement)

/** Single-device transactional store. It never contacts a network service. */
class LocalEngine(val recipes: List<JsonObject>, saved: String?, private val persist: (String) -> Unit,
                  val clock: () -> Long = System::currentTimeMillis, val storageRules: JsonObject = EmptyObject) {
    internal var db: JsonObject = saved?.let { AppJson.parseToJsonElement(it).asObject() }
        ?: seed()
    init {
        // Older interceptors forwarded write keys into this read-only auth check. Repair only
        // those impossible write receipts; completed mutations and all user data remain intact.
        val receipts = rows("commands")
        val repaired = receipts.filterNot { it.str("fingerprint") == "GET:/auth/session:{}" }
        if (repaired.size != receipts.size) set("commands", repaired)
        persist(db.toString())
    }
    internal fun now() = Instant.ofEpochMilli(clock()).toString()
    internal fun persistMedia() { persist(db.toString()) }
    internal fun id() = UUID.randomUUID().toString()
    internal fun rows(key: String) = db.objects(key)
    internal fun set(key: String, values: List<JsonObject>) { db = db.with(key to values) }
    internal fun add(key: String, row: JsonObject) { set(key, rows(key) + row) }
    internal fun find(key: String, id: String): JsonObject = rows(key).firstOrNull { it.str("id") == id } ?: fail(404, "内容不存在，请刷新")
    internal fun replace(key: String, row: JsonObject) { set(key, rows(key).map { if (it.str("id") == row.str("id")) row else it }) }
    internal fun remove(key: String, id: String) { set(key, rows(key).filter { it.str("id") != id }) }
    internal fun fail(code: Int, message: String): Nothing = throw LocalFailure(code, message)
    internal fun version(row: JsonObject, body: JsonObject) {
        if (body.num("expected_version") != row.num("version")) fail(409,"内容已修改，请刷新后重试")
    }
    internal fun updated(row: JsonObject, body: JsonObject) = (row + body.without("expected_version", "idempotency_key")).with("version" to ((row.num("version") ?: 0.0) + 1), "updated_at" to now())
    internal fun own(row: JsonObject, user: String) { if (row.str("owner") != user) fail(403,"没有访问权限") }
    internal fun items(rows: List<JsonObject>) = jsonOf("items" to rows)
    internal fun ok(vararg pairs: Pair<String, Any?>) = jsonOf("status" to "success", *pairs)
    fun initialSession(): JsonObject = session(rows("users").first())
    @Synchronized fun reset() { val fresh = seed(); persist(fresh.toString()); db = fresh }
    @Synchronized fun usableStock(user: String): List<JsonObject> = stock(user).filter {
        (it.num("quantity") ?: 0.0) > 0 && !fresh(it).bool("expired") && "storage_unsuitable" !in fresh(it).strings("risk_flags")
    }
    @Synchronized fun recentRecipe(user: String): JsonObject? {
        val r = rows("chat").lastOrNull { it.str("owner") == user && it.obj("recipe") != null }?.obj("recipe") ?: return null
        return jsonOf("dish_name" to (r.str("dish_name") ?: r.str("name")), "ingredients_list" to r.objects("ingredients_list").take(20),
            "steps" to r.objects("steps").take(8).map { jsonOf("text" to it.str("text")?.take(200)) })
    }
    @Synchronized fun assist(user: String, prompt: String, online: JsonObject?, save: Boolean,
                             provider: String? = null, unavailableReason: String? = null): LocalReply {
        val before = db
        return try {
            val available = usableStock(user).mapNotNull { it.str("name") }.toSet()
            val recipe = online ?: recipes.firstOrNull { prompt.contains(it.str("name").orEmpty()) }
                ?: recipes.maxBy { r -> r.objects("ingredients").count { it.str("name") in available } }
            com.smartcooking.app.data.Recipe.normalize(recipe)
            val source = if (online == null) "built_in" else "online"
            val notice = if (online == null) "${unavailableReason ?: "在线服务暂不可用"}，已打开内置菜谱：${recipe.str("dish_name") ?: recipe.str("name")}。这不是本次 AI 生成结果。"
                else "${provider?.let { "$it 已生成" } ?: "为你准备好了"}：${recipe.str("dish_name") ?: recipe.str("name")}"
            if (save) {
                add("chat", jsonOf("id" to id(), "owner" to user, "role" to "user", "content" to prompt, "time" to now(), "timestamp" to now()))
                add("chat", jsonOf("id" to id(), "owner" to user, "role" to "assistant", "content" to notice, "recipe" to recipe, "source" to source, "provider" to provider.takeIf { online != null }, "time" to now(), "timestamp" to now()))
                persist(db.toString())
            }
            LocalReply(200, ok("recipe" to recipe, "source" to source, "provider" to provider.takeIf { online != null }, "notice" to notice))
        } catch (_: Exception) { db = before; LocalReply(500, jsonOf("detail" to "菜谱未保存，请重试")) }
    }
    internal fun session(user: JsonObject): JsonObject {
        val token = "local-" + id()
        add("tokens", jsonOf("id" to token, "user" to user.str("id"), "expires" to clock() + 30L*86400000))
        persist(db.toString())
        return ok("user" to user.without("password"), "access_token" to token, "expires_at" to (clock()/1000+30*86400))
    }
    @Synchronized fun request(method: String, path: String, body: JsonElement = EmptyObject,
                             query: Map<String,String> = emptyMap(), headers: Map<String,String> = emptyMap()): LocalReply {
        val before = db
        try {
            val p = path.removePrefix("/api").trimEnd('/'); val b = body.asObject() ?: EmptyObject
            val token = headers.entries.firstOrNull { it.key.equals("Authorization",true) }?.value?.removePrefix("Bearer ")
            if (p == "/login") {
                val u=rows("users").firstOrNull { (it.str("username")==b.str("username") || it.str("phone")==b.str("username")) && it.str("password")==b.str("password") } ?: fail(401,"账号或密码不正确")
                return LocalReply(200,session(u))
            }
            if (p == "/register") {
                if (b.str("password").orEmpty().length < 6) fail(422,"密码至少需要六位")
                val invitation=rows("accountInvites").firstOrNull{it.str("code")==b.str("invitation_code") && !it.bool("used")} ?: fail(422,"邀请码无效或已使用")
                val phone=b.str("phone").orEmpty(); val username=b.str("nickname").orEmpty(); if(rows("users").any { it.str("username")==username }) fail(409,"该昵称已被使用")
                replace("accountInvites",invitation.with("used" to true))
                val u=b.with("id" to id(),"username" to username,"role" to "member");add("users",u);persist(db.toString());return LocalReply(200,ok("message" to "注册成功"))
            }
            val t=rows("tokens").firstOrNull { it.str("id")==token && (it.num("expires")?:0.0)>clock() } ?: fail(401,"请重新登录")
            val user=t.str("user")!!
            query["user_id"]?.let { if(it!=user) fail(403,"不能访问其他账号数据") }
            b.str("user_id")?.let { if(it!=user) fail(403,"不能访问其他账号数据") }
            val key = if (method in setOf("POST", "PUT", "PATCH", "DELETE")) {
                b.str("idempotency_key") ?: headers.entries.firstOrNull { it.key.equals("Idempotency-Key",true) }?.value
            } else null
            val fingerprint="$method:$p:$body"
            val receiptKey="$user:$key"
            if(key!=null) rows("commands").firstOrNull { it.str("id")==receiptKey }?.let {
                if(it.str("fingerprint")!=fingerprint) fail(409,"操作凭证不能用于不同内容")
                return LocalReply(200,it["result"]!!)
            }
            val result = route(method,p,b,body,query,headers,user,token!!)
            if(key!=null) add("commands",jsonOf("id" to receiptKey,"fingerprint" to fingerprint,"result" to result))
            if(db!=before) persist(db.toString())
            return LocalReply(200,result)
        } catch(e: LocalFailure) { db=before; return LocalReply(e.status,jsonOf("detail" to e.message)) }
          catch(e: Exception) { db=before; return LocalReply(500,jsonOf("detail" to "本机保存失败，请重试")) }
    }
    private fun route(m:String,p:String,b:JsonObject,raw:JsonElement,q:Map<String,String>,h:Map<String,String>,u:String,token:String):JsonElement {
        when {
            p=="/auth/session" -> return ok("user" to find("users",u).without("password"))
            p=="/auth/logout" -> { remove("tokens",token);return ok() }
            p=="/auth/invitations" -> {if(find("users",u).str("role")!="admin")fail(403,"仅管理员可邀请");val code="COOKX-"+id().take(8);add("accountInvites",jsonOf("id" to id(),"code" to code,"used" to false));return ok("code" to code,"expires_at" to clock()/1000+86400)}
            p.startsWith("/user/") -> { if(p.substringAfterLast('/')!=u) fail(403,"没有访问权限");val row=find("users",u)
                if(m=="DELETE") { remove("users",u);set("tokens",rows("tokens").filter{it.str("user")!=u});return ok() }
                if(m=="PUT") replace("users",(row+jsonOf(*q.filterKeys { it in listOf("nickname","phone","avatar") }.map { it.key to it.value }.toTypedArray())))
                return ok("user" to find("users",u).without("password")) }
            p=="/notifications/summary" -> { syncNotices(u); val visible=rows("notices").filter{it.str("owner")==u && !it.bool("archived")}; return jsonOf("total" to visible.size,"unread" to visible.count{!it.bool("is_read")}) }
            p=="/notifications" -> { syncNotices(u); return JsonArray(rows("notices").filter{it.str("owner")==u && !it.bool("archived")}.sortedByDescending { it.str("created_at") }.drop((q["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)).take((q["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 200))) }
            p=="/notifications/read" || p=="/notifications/archive" -> { set("notices",rows("notices").map { if(it.str("owner")==u && (q["msg_id"].isNullOrEmpty() || it.str("id")==q["msg_id"])) it.with("is_read" to true, "read_at" to now(), "archived" to (p.endsWith("archive") || it.bool("archived"))) else it });return ok() }
            p=="/inventory" -> return JsonArray(stock(u))
            p=="/v3/personal-inventory" -> return jsonOf("items" to stock(u),"version" to stockVersion(u))
            p=="/add-to-inventory" || p=="/inventory/confirm-recognition" -> { val input=if(p.endsWith("confirm-recognition")) b.objects("items") else raw.asArray().orEmpty().mapNotNull{it.asObject()};if(input.isEmpty()) fail(422,"请确认食材")
                input.forEach { mergeStock(it,u) }; syncNotices(u);return ok() }
            p=="/inventory/consume" -> return consume(u,b)
            p.startsWith("/inventory/consumption/") -> return rows("receipts").firstOrNull { it.str("id")=="$u:${p.substringAfterLast('/')}" } ?: fail(404,"暂无扣减凭证")
            p.startsWith("/inventory/") -> { val r=find("stock",p.substringAfterLast('/'));own(r,u);if(h.entries.firstOrNull{it.key.equals("If-Match",true)}?.value!=r.str("_revision")) fail(409,"库存已变化，请刷新")
                if(m=="DELETE") remove("stock",r.str("id")!!) else { validateStock(r+b);replace("stock",(r+b).with("_revision" to id())) };bumpStock(u);return ok() }
            p=="/users/$u/inventory/freshness" -> return jsonOf("items" to stock(u).map{fresh(it)},"evaluated_at" to now())
            p=="/freshness/evaluate" -> return fresh(b)
            p=="/analyze-fridge" -> fail(503,"图片识别需要联网；可选择手动录入或明确标注的示例食材")
            p=="/chat-history" -> return JsonArray(rows("chat").filter{it.str("owner")==u})
            p=="/clear-chat" -> { set("chat",rows("chat").filter{it.str("owner")!=u});return ok() }
            p=="/recommend-recipe" -> { val prompt=q["user_prompt"].orEmpty(); val r=recipes.firstOrNull{prompt.contains(it.str("name").orEmpty())} ?: recipes.first()
                if(q["save_history"]=="true") add("chat",jsonOf("id" to id(),"owner" to u,"role" to "assistant","content" to "${r.str("name")}，请先核对食材。","recipe" to r,"timestamp" to now()))
                return ok("recipe" to r) }
            p=="/recommendations" -> return recommendations(u,b)
            p=="/tts" -> fail(503,"请使用系统语音播报，或检查中文语音包")
        }
        val s=p.removePrefix("/v3/").split('/')
        return when(s[0]) {
            "households" -> household(m,s,b,u)
            "recipes" -> recipeRoute(m,s,b,q,u)
            "community" -> community(m,s,b,q,u)
            "leftovers","growth","challenges","badges","learning","preferences" -> personal(m,s,b,u)
            "planning" -> planning(m,s,b,u)
            else -> fail(404,"暂不支持此操作")
        }
    }
    internal fun stock(u:String)=rows("stock").filter{it.str("owner")==u}
    internal fun stockVersion(u:String)=db.obj("stockVersions")?.num(u)?:1.0
    internal fun bumpStock(u:String){db=db.with("stockVersions" to (db.obj("stockVersions")?:EmptyObject).with(u to stockVersion(u)+1))}
    internal fun validateStock(b:JsonObject) { if(b.str("name").isNullOrBlank() || (b.num("quantity")?:0.0)<=0 || b.num("quantity")?.isFinite()!=true) fail(422,"请填写名称和正数数量")
        b.num("shelf_life")?.let { if(!it.isFinite() || it<=0) fail(422,"保质期必须为有效正数") } }
    internal fun newStock(b:JsonObject,u:String):JsonObject {validateStock(b);return b.with("id" to id(),"owner" to u,"version" to 1,"_revision" to id(),"add_time" to (b.str("add_time")?:now()),"unit" to (b.str("unit")?:"库存计数"))}
    internal fun fresh(r:JsonObject):JsonObject = localFreshness(r,storageRules,clock())
    private fun consume(u:String,b:JsonObject):JsonObject {
        val changes=b.objects("items");if(changes.isEmpty() || changes.map{it.str("item_id")}.distinct().size!=changes.size)fail(422,"扣减清单无效")
        changes.forEach { x -> val r=find("stock",x.str("item_id").orEmpty());own(r,u);val amount=x.num("quantity")?:0.0
            if(!amount.isFinite() || amount<=0 || amount>(r.num("quantity")?:0.0) || x.num("expected_quantity")!=r.num("quantity") || x.str("expected_revision")!=r.str("_revision"))fail(409,"库存已变化，请重新核对") }
        changes.forEach { x -> val r=find("stock",x.str("item_id")!!);val left=r.num("quantity")!!-x.num("quantity")!!;if(left==0.0)remove("stock",r.str("id")!!)else replace("stock",r.with("quantity" to left,"_revision" to id())) };bumpStock(u)
        val receipt=ok("id" to "$u:${b.str("idempotency_key")}","idempotency_key" to b.str("idempotency_key"),"items" to changes,"committed_at" to now());add("receipts",receipt);return receipt
    }
    internal fun sources(kind:String,u:String):List<JsonObject> = when(kind) {
        "standard" -> recipes.map{jsonOf("id" to it.str("id"),"recipe" to it,"source_version" to "1")}
        "history" -> (rows("chat") + rows("completions")).filter{it.str("owner")==u && it.obj("recipe")!=null}.map{it.with("source_version" to "1")}
        "copy","favorite" -> rows(kind).filter{it.str("owner")==u}.map{it.with("source_version" to it.str("version"))}
        else -> emptyList()
    }
    private fun recipeRoute(m:String,s:List<String>,b:JsonObject,q:Map<String,String>,u:String):JsonObject {
        if(s[1]=="sources")return items(sources(q["kind"]?:"standard",u))
        val table=if(s[1]=="copies")"copy" else "favorite"
        if(m=="DELETE") { val r=find(table,s[2]);own(r,u);version(r,b);remove(table,s[2]);return ok() }
        if(s.size>3 && s[3]=="check") { val r=find("copy",s[2]);own(r,u);return jsonOf("copy" to r,"ingredients" to r.obj("recipe")!!.objects("ingredients_list").map { i->jsonOf("name" to i.str("item"),"required_amount" to i.num("amount"),"unit" to i.str("unit"),"status" to if(stock(u).any{it.str("name")==i.str("item")})"requires_confirmation" else "missing") },"note" to "请核对实际数量与储存状态") }
        val kind=b.str("source_type").orEmpty();val sourceId=b.str("source_id").orEmpty()
        val source=when(kind){
            "community" -> find("posts",sourceId).let{if(it.bool("hidden"))fail(404,"内容已隐藏");it.with("source_version" to it.str("version"))}
            "menu" -> { val bits=sourceId.split('|');val menu=find("menus",bits[0]);own(menu,u); val dish=menu.obj("result")!!.objects("meals").first{it.str("day")==bits[1]&&it.str("meal")==bits[2]}.objects("dishes").first{it.str("role")==bits[3]};val factor=dish.num("factor")?:1.0;val recipe=dish.obj("recipe")!!.with("inventory_scope" to jsonOf("family_id" to menu.obj("config")?.str("family_id")),"planning_factor" to factor,"ingredients_list" to dish.obj("recipe")!!.objects("ingredients_list").map{it.with("amount" to (it.num("amount")?:0.0)*factor)});dish.with("recipe" to recipe,"source_version" to menu.str("version")) }
            else -> sources(kind,u).firstOrNull{it.str("id")==sourceId}?:fail(404,"菜谱来源不存在") }
        if(source.str("source_version")!=b.str("expected_source_version") && !(source.num("source_version")!=null && source.num("source_version")==b.num("expected_source_version")))fail(409,"菜谱版本已变化")
        val recipe=source.obj("recipe")?:fail(422,"内容没有有效菜谱")
        val row=jsonOf("id" to id(),"owner" to u,"version" to 1,"recipe" to recipe,"source" to jsonOf("type" to kind,"id" to sourceId,"version" to b.str("expected_source_version")),"created_at" to now())
        add(table,row);return ok(table to row)
    }
    private fun recommendations(u:String,b:JsonObject):JsonObject = localRecommendations(u,b)
}
