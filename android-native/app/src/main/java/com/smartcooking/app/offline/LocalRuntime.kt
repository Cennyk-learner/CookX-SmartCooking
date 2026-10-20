package com.smartcooking.app.offline

import android.content.Context
import android.graphics.BitmapFactory
import com.smartcooking.app.R
import com.smartcooking.app.core.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.util.UUID

/** Local business writes are authoritative. Only explicit inference requests call Qwen directly from the phone. */
class LocalRuntime(private val context: Context, private val store:KeyValueStore, private val sessions:SessionManager):Interceptor {
    val gateway = DemoGateway(store)
    var cookingContext: () -> JsonObject = { EmptyObject }
    val engine=LocalEngine(AppJson.parseToJsonElement(context.assets.open("offline/recipes.json").bufferedReader().use{it.readText()}).asArray()!!.mapNotNull{it.asObject()},store.get("cookx:local-database:v1"),{value->check(store.putSync("cookx:local-database:v1",value)){"本机存储失败"}}, storageRules=AppJson.parseToJsonElement(context.assets.open("offline/storage-rules.json").bufferedReader().use{it.readText()}).asObject()!!)
    init {
        if (sessions.backendOrigin != "https://cookx.local") sessions.setBackendOrigin("")
        if(sessions.currentUserId != "lin" || sessions.session.value?.token == null || (sessions.session.value?.expiresAtMillis ?: 0) <= System.currentTimeMillis()) {
            sessions.save(engine.initialSession())
            check(store.putSync("cookx:local-initialized:v1","1"))
        }
        cleanupMedia()
    }
    @Synchronized private fun cleanupMedia() {
        val kept = engine.sweepMedia()
        File(context.filesDir, "local-media").listFiles()?.filter { it.isFile && it.name !in kept }?.forEach { it.delete() }
    }
    override fun intercept(chain:Interceptor.Chain):Response {
        val r=chain.request();val p=r.url.encodedPath
        fun reply(code:Int,bytes:ByteArray,mime:String="application/json")=Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message(if(code<400)"OK" else "Error").body(bytes.toResponseBody(mime.toMediaType())).build()
        fun json(code:Int,value:JsonElement)=reply(code,value.toString().toByteArray())
        val headers=r.headers.names().associateWith{r.header(it).orEmpty()}
        val auth=engine.request("GET","/api/auth/session",headers=headers)
        if (r.url.host != "cookx.local") return json(403, jsonOf("detail" to "本地数据不能发送到外部地址"))
        if (p == "/api/analyze-fridge") {
            if (auth.code != 200) return json(auth.code, auth.body)
            return try {
                val result = gateway.recognize(r.body ?: return json(422, jsonOf("detail" to "请选择图片")))
                json(200, result.with("source" to "online"))
            } catch (e: Exception) {
                val detail = if (e is ApiException || e is IllegalArgumentException) e.message else "请检查手机网络"
                json(503, jsonOf("detail" to "联网识别暂不可用：$detail。可手动录入或体验示例食材；没有生成照片识别结果。"))
            }
        }
        if (p == "/api/recommend-recipe") {
            if (auth.code != 200) return json(auth.code, auth.body)
            val user = sessions.currentUserId ?: return json(401, jsonOf("detail" to "本地会话无效"))
            val prompt = r.url.queryParameter("user_prompt").orEmpty()
            val online = runCatching {
                gateway.recipe(prompt, engine.usableStock(user), cookingContext()).obj("recipe")?.also {
                    com.smartcooking.app.data.Recipe.normalize(it)
                }
            }.getOrNull()
            if (chain.call().isCanceled() || sessions.currentUserId != user) throw java.io.IOException("请求已取消")
            val result = engine.assist(user, prompt, online, r.url.queryParameter("save_history") != "false")
            return json(result.code, result.body)
        }
        if(r.method == "GET" && (p.startsWith("/api/v3/community/media/") || p.startsWith("/api/v3/profile/media/"))) {
            if(auth.code!=200)return json(auth.code,auth.body)
            val name=p.substringAfterLast('/');if(!name.matches(Regex("[a-zA-Z0-9-]+")))return json(404,jsonOf("detail" to "图片不存在"))
            if (!name.startsWith("dish-")) {
                val permission = engine.request("GET", "/api/v3/community/media/$name", headers = headers)
                if (permission.code != 200) return json(permission.code, permission.body)
            }
            val bytes=when(name){"dish-0","dish-1","dish-2"->context.resources.openRawResource(when(name){"dish-1"->R.drawable.sense_pan;"dish-2"->R.drawable.today_dish;else->R.drawable.live_card_pan}).use{it.readBytes()};else->File(context.filesDir,"local-media/$name").takeIf{it.isFile}?.readBytes()}
            return if(bytes!=null)reply(200,bytes,if(name.startsWith("dish-")) "image/webp" else "image/jpeg")else json(404,jsonOf("detail" to "图片不存在"))
        }
        if(r.body is MultipartBody && p in listOf("/api/upload-avatar","/api/v3/community/media")) {
            if(auth.code!=200)return json(auth.code,auth.body)
            val part=(r.body as MultipartBody).parts.firstOrNull()?:return json(422,jsonOf("detail" to "请选择图片"))
            val buffer=Buffer();part.body.writeTo(buffer);val bytes=buffer.readByteArray()
            val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
            if(bytes.size>10*1024*1024||opts.outWidth<=0||opts.outHeight<=0)return json(422,jsonOf("detail" to "请选择有效图片，大小不超过 10 MB"))
            val id=UUID.randomUUID().toString();val dir=File(context.filesDir,"local-media").apply{mkdirs()};File(dir,id).writeBytes(bytes)
            val saved = engine.request("POST", "/api/v3/community/media", jsonOf("id" to id, "mime" to "image/jpeg"), headers = headers)
            if (saved.code != 200) { File(dir,id).delete(); return json(saved.code,saved.body) }
            return json(200,jsonOf("status" to "success","id" to id,"url" to "/api/v3/profile/media/$id","avatar_url" to "/api/v3/profile/media/$id"))
        }
        val body=if(r.body==null || r.body is MultipartBody)EmptyObject else runCatching{val buffer=Buffer();r.body!!.writeTo(buffer);AppJson.parseToJsonElement(buffer.readUtf8())}.getOrDefault(EmptyObject)
        val res=engine.request(r.method,p,body,r.url.queryParameterNames.associateWith{r.url.queryParameter(it).orEmpty()},headers)
        if (r.method != "GET" && p.startsWith("/api/v3/community")) cleanupMedia()
        return json(res.code,res.body)
    }

    fun reset() {
        engine.reset()
        // Clear drafts, cooking sessions, receipts and UI caches as one confirmed operation.
        store.keys("").filter { it != "cookx:local-database:v1" && !it.startsWith("cookx:demo:") }
            .forEach { check(store.removeSync(it)) { "重置未完成，请重试" } }
        File(context.filesDir, "local-media").listFiles()?.filter { it.isFile }?.forEach { check(it.delete()) { "旧图片清理失败" } }
        sessions.save(engine.initialSession())
    }
}
