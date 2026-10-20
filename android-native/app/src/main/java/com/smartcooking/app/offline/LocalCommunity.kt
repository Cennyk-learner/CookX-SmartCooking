package com.smartcooking.app.offline
import com.smartcooking.app.core.*
import kotlinx.serialization.json.*
import java.time.Instant

internal fun LocalEngine.community(m:String,s:List<String>,b:JsonObject,q:Map<String,String>,u:String):JsonObject {
    if (s[1] == "media") return mediaRoute(m, s, b, u)
    val admin=find("users",u).str("role")=="admin"
    if(s[1] in listOf("hot","moderation") || s.size==2 && m=="GET") {
        val list=rows("posts").filter{p->if(s[1]=="moderation")admin else !p.bool("hidden")&&(q["mine"]!="true"||p.str("owner")==u)&&(q["bookmarked"]!="true"||rows("bookmarks").any { it.str("post")==p.str("id") && it.str("owner")==u })}.map{p->p.with("bookmarked" to rows("bookmarks").any { it.str("post")==p.str("id") && it.str("owner")==u }, "hot" to jsonOf("likes" to rows("likes").count{it.str("post")==p.str("id")&&it.bool("liked")},"commenters" to rows("comments").filter{it.str("post")==p.str("id")}.map{it.str("owner")}.distinct().size))}
        return if(s[1]=="moderation")jsonOf("posts" to list,"items" to rows("reports")) else jsonOf("items" to if(s[1]=="hot")list.sortedByDescending{it.obj("hot")?.num("likes")}else list.reversed(),"rule" to "近期点赞与评论互动")
    }
    if(s.size==2 && m=="POST") {validateMedia(b.strings("image_ids"),u);if(!b.bool("confirmed")||b.str("text").isNullOrBlank())fail(422,"请确认作品内容");val recipe=b.str("recipe_copy_id")?.takeIf{it.isNotBlank()}?.let{find("copy",it).also{r->own(r,u)}.obj("recipe")};val row=b.with("id" to id(),"owner" to u,"author_name" to find("users",u).str("nickname"),"version" to 1,"recipe" to recipe,"created_at" to now(),"hidden" to false);add("posts",row);return ok("post" to row)}
    val p=find("posts",s[2]);val pid=s[2]
    if(s.size==3) {
        own(p,u); version(p,b)
        if(m=="DELETE") {
            remove("posts",pid)
            listOf("likes","comments","bookmarks","reports").forEach { table -> set(table, rows(table).filter { it.str("post") != pid }) }
        } else {
            if (b.str("text").isNullOrBlank() || b.str("text")!!.length > 3000) fail(422,"请填写 3000 字以内的作品内容")
            validateMedia(b.strings("image_ids"),u)
            val recipe = b.str("recipe_copy_id")?.takeIf { it.isNotBlank() }?.let { find("copy",it).also { row -> own(row,u) }.obj("recipe") }
            replace("posts",updated(p,b).with("recipe" to recipe))
        }
        p.strings("image_ids").filter { !mediaReferenced(it) }.forEach { remove("media",it) }
        return ok()
    }
    when(s[3]){
        "bookmark" -> {
            if(p.bool("hidden")) fail(404,"内容已隐藏")
            val key="$pid:$u"; remove("bookmarks",key)
            if(b.bool("bookmarked")) add("bookmarks",jsonOf("id" to key,"post" to pid,"owner" to u,"created_at" to now()))
            return ok("bookmarked" to b.bool("bookmarked"))
        }
        "moderation" -> {if(!admin)fail(403,"需要管理员权限");version(p,b);replace("posts",updated(p,jsonOf("hidden" to b.bool("hidden"),"moderation_reason" to b.str("reason"))));return ok()}
        "reports" -> {add("reports",b.with("id" to id(),"post" to pid,"owner" to u,"created_at" to now()));return ok()}
        "likes" -> {val old=rows("likes").firstOrNull{it.str("id")=="$pid:$u"}?:jsonOf("id" to "$pid:$u","post" to pid,"owner" to u,"version" to 0,"liked" to false)
            if(m!="GET"){if(p.bool("hidden"))fail(404,"内容已隐藏");version(old,b);val row=updated(old,jsonOf("liked" to b.bool("liked"),"created_at" to now()));remove("likes",row.str("id")!!);add("likes",row)}
            val current=rows("likes").firstOrNull{it.str("id")=="$pid:$u"}?:old
            return current.with("count" to rows("likes").count{it.str("post")==pid&&it.bool("liked")})}
        "comments" -> {if(m=="GET")return items(rows("comments").filter{it.str("post")==pid});if(m=="DELETE"){val r=find("comments",s[4]);if(r.str("post")!=pid)fail(403,"没有权限");if(!admin)own(r,u);version(r,b);remove("comments",s[4])}else{if(p.bool("hidden"))fail(404,"内容已隐藏");if(b.str("text").isNullOrBlank())fail(422,"评论不能为空");add("comments",b.with("id" to id(),"post" to pid,"owner" to u,"author_name" to find("users",u).str("nickname"),"version" to 1,"created_at" to now()))};return ok()}
    };fail(404,"操作不存在")
}
internal fun LocalEngine.personal(m:String,s:List<String>,b:JsonObject,u:String):JsonObject {
    when(s[0]) {
        "preferences" -> {val old=rows("preferences").firstOrNull{it.str("id")==u}?:jsonOf("id" to u,"version" to 0,"values" to EmptyObject,"recommendation" to EmptyObject)
            if(m!="GET"){version(old,b);remove("preferences",u);add("preferences",updated(old,b))};return jsonOf("preferences" to (rows("preferences").firstOrNull{it.str("id")==u}?:old))}
        "leftovers" -> {
            fun assessed(r:JsonObject):JsonObject {val good=if(r.str("kind")=="raw")rows("stock").firstOrNull{it.str("id")==r.str("inventory_id")&&it.str("owner")==u}?.let{(it.num("quantity")?:0.0)>0 && !fresh(it).bool("expired") && "storage_unsuitable" !in fresh(it).strings("risk_flags")}?:false else !r.bool("abnormal")&&r.bool("cold_chain_confirmed")&&(r.num("quantity")?:0.0)>0&&r.str("storage_type") in listOf("冷藏","冷冻")&&r.str("made_at")!=null&&r.str("stored_at")!=null&&(r.str("expiry_at")?.let{runCatching{Instant.parse(it).toEpochMilli()>clock()}.getOrDefault(false)}==true)&&r.str("status") !in listOf("discarded","used")
                return r.with("assessment" to jsonOf("eligible" to good,"reasons" to listOf(if(good)"请继续核对储存记录与实物状态" else "信息不完整、已结束或超过记录期限"),"disclaimer" to "储存记录不能替代可食用性鉴定")) }
            if(s.size==1){if(m=="GET")return items(rows("leftovers").filter{it.str("owner")==u}.map(::assessed));if(b.str("kind")=="cooked") { validateStock(b); if(b.str("unit").isNullOrBlank()) fail(422,"请填写熟食单位") };var row=b.with("id" to id(),"owner" to u,"version" to 1,"events" to emptyList<String>());if(b.str("kind")=="raw"){val stock=find("stock",b.str("inventory_id").orEmpty());own(stock,u);row=row.with("name" to stock.str("name"))};add("leftovers",row);return ok("item" to assessed(row))}
            val r=find("leftovers",s[1]);own(r,u)
            if(s[2]=="ideas"){val assessment=assessed(r).obj("assessment")!!;return jsonOf("assessment" to assessment,"ideas" to if(assessment.bool("eligible"))recipes.filter{it.objects("ingredients").any{i->i.str("name")==r.str("name")}||r.str("cooked_type")=="rice"&&it.str("name")=="蛋炒饭"}.take(4).map{it.with("source_version" to "1","type" to "standard","steps" to it.objects("steps").map{step->step.str("text")})}else emptyList<JsonObject>())}
            version(r,b);val type=b.str("type");if(type !in listOf("reheated","restored","used","abnormal","discarded")) fail(422,"无效的处理方式");var next=r.with("events" to r.objects("events")+b.with("at" to now()))
            if(type=="abnormal")next=next.with("abnormal" to true)
            if(type=="restored")next=next.with("cold_chain_confirmed" to b.bool("cold_chain_confirmed"), "stored_at" to now())
            if(type=="discarded")next=next.with("status" to "discarded")
            if(type=="used"){val qty=b.num("quantity")?:0.0;if(!qty.isFinite()||qty<=0||qty>(r.num("quantity")?:0.0))fail(422,"数量无效");next=next.with("quantity" to r.num("quantity")!!-qty);if(next.num("quantity")==0.0)next=next.with("status" to "used")}
            replace("leftovers",updated(next,EmptyObject));return ok()
        }
        "growth" -> {if(m=="POST"){if(!b.bool("confirmed"))fail(422,"请确认完成烹饪");if(rows("completions").none{it.str("owner")==u&&it.str("session_id")==b.str("session_id")})add("completions",b.with("id" to id(),"owner" to u));return ok()}
            val history=rows("completions").filter{it.str("owner")==u};return jsonOf("confirmed_count" to history.size,"recipe_count" to history.map{it.obj("recipe")?.str("dish_name")}.distinct().size,"history" to history.reversed(),"methods" to history.groupingBy{it.obj("recipe")?.str("method")?:"其他"}.eachCount(),"timeline" to history.groupingBy{it.str("completed_at").orEmpty().take(10)}.eachCount(),"note" to "包含内置示例记录；本机新增烹饪记录会持续累计") }
        "challenges" -> {if(m=="POST"){val c=find("challengePresets",s[1]);if(rows("participation").none{it.str("id")=="$u:${s[1]}"})add("participation",jsonOf("id" to "$u:${s[1]}","owner" to u,"challenge" to c.str("id"),"joined_at" to now()));return ok()}
            return items(rows("challengePresets").map{c->val join=rows("participation").firstOrNull{it.str("id")=="$u:${c.str("id")}"};val history=rows("completions").filter{it.str("owner")==u&&it.str("completed_at").orEmpty()>join?.str("joined_at").orEmpty()};val count=if(c.str("id")=="variety")history.map{it.obj("recipe")?.str("dish_name")}.distinct().size else history.size
                c.with("participation" to join?.with("progress" to count,"status" to if(count>=(c.num("target")?:1.0))"completed"else"active","ends_at" to null))}) }
        "badges" -> {val history=rows("completions").filter{it.str("owner")==u};return items(listOf("初次掌勺","一周家常","百味探索").mapIndexed{i,n->jsonOf("id" to "badge-$i","name" to n,"description" to "累计确认完成 ${listOf(1,5,10)[i]} 次烹饪","award" to if(history.size>=listOf(1,5,10)[i])jsonOf("awarded_at" to history[listOf(1,5,10)[i]-1].str("completed_at"),"rule_version" to 1,"evidence_ids" to history.map{it.str("id")})else null)}) }
        "learning" -> return learning(m,s,b,u)
    };fail(404,"操作不存在")
}
internal fun LocalEngine.learning(m:String,s:List<String>,b:JsonObject,u:String):JsonObject {
    val settings=rows("learning").firstOrNull{it.str("id")==u}?:jsonOf("id" to u,"version" to 0,"enabled" to false)
    if(s.size>1 && m!="GET")when(s[1]) {
        "settings" -> {version(settings,b);remove("learning",u);add("learning",updated(settings,b));return ok()}
        "feedback" -> {if(!settings.bool("enabled"))fail(422,"请先开启偏好学习");remove("models",u);val key="$u:${s[2]}";val old=rows("feedback").firstOrNull{it.str("id")==key}?:jsonOf("id" to key,"owner" to u,"recipe_id" to s[2],"version" to 0);version(old,b);remove("feedback",key);add("feedback",updated(old,b).with("created_at" to now(),"original_score" to .5));return ok()}
        "reset" -> {remove("models",u);if(b.str("action") in listOf("clear_feedback","clear","all"))set("feedback",rows("feedback").filter{it.str("owner")!=u});return ok()}
        "train" -> {if(!settings.bool("enabled"))fail(422,"请先开启偏好学习");remove("models",u);val model=trainLocal(u);add("models",model);return ok("model" to model)}
    }
    return jsonOf("settings" to settings,"feedback" to rows("feedback").filter{it.str("owner")==u},"model" to rows("models").lastOrNull{it.str("id")==u},"reason" to "模型未通过独立评估时保持原排序")
}
