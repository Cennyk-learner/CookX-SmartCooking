package com.smartcooking.app.offline
import com.smartcooking.app.core.*
import kotlinx.serialization.json.*

internal fun LocalEngine.member(f:String,u:String):JsonObject=rows("members").firstOrNull{it.str("family")==f && it.str("user_id")==u}?:fail(403,"已不在该家庭中")
internal fun LocalEngine.admin(f:String,u:String){if(member(f,u).str("role")!="admin")fail(403,"此操作需要管理员")}
internal fun LocalEngine.household(m:String,s:List<String>,b:JsonObject,u:String):JsonObject {
    if(s.size==1) {
        if(m=="GET")return items(rows("families").filter{f->rows("members").any{it.str("family")==f.str("id")&&it.str("user_id")==u}})
        if(b.str("name").isNullOrBlank())fail(422,"请输入家庭名称")
        val f=jsonOf("id" to id(),"name" to b.str("name"),"version" to 1);add("families",f);add("members",jsonOf("id" to id(),"family" to f.str("id"),"user_id" to u,"display_name" to find("users",u).str("nickname"),"role" to "admin","version" to 1));return ok("household" to f)
    }
    if(s[1]=="join") {val invite=rows("invitations").firstOrNull{it.str("code")==b.str("code")&&it.bool("active")&&(it.num("expires_at")?:0.0)>clock()/1000}?:fail(422,"邀请码无效")
        val f=invite.str("family")!!;if(rows("members").none{it.str("family")==f&&it.str("user_id")==u})add("members",jsonOf("id" to id(),"family" to f,"user_id" to u,"display_name" to find("users",u).str("nickname"),"role" to "member","version" to 1));return ok("household" to find("families",f)) }
    val f=s[1];val membership=member(f,u)
    if(s.size==2)return jsonOf("household" to find("families",f),"membership" to membership,"members" to rows("members").filter{it.str("family")==f},"inventory" to stock(f).map{it.with("freshness" to fresh(it))},"invitations" to rows("invitations").filter{it.str("family")==f})
    when(s[2]) {
        "shopping" -> return shopping(m,s,b,u,f)
        "invite" -> {admin(f,u);val code="CX-"+id().take(8).uppercase();add("invitations",jsonOf("id" to id(),"family" to f,"version" to 1,"code" to code,"active" to true,"expires_at" to clock()/1000+86400));return ok("code" to code)}
        "invitations" -> {admin(f,u);val r=find("invitations",s[3]);if(r.str("family")!=f)fail(403,"没有权限");version(r,b);replace("invitations",updated(r,jsonOf("active" to false)));return ok()}
        "members" -> {val r=rows("members").firstOrNull{it.str("family")==f&&it.str("user_id")==s[3]}?:fail(404,"成员不存在");if(s[3]!=u)admin(f,u);if(r.str("role")=="admin")fail(422,"请先移交管理员");version(r,b);remove("members",r.str("id")!!);return ok()}
        "administrator" -> {admin(f,u);version(membership,b);val r=rows("members").firstOrNull{it.str("family")==f&&it.str("user_id")==b.str("member_id")}?:fail(404,"成员不存在");if(r.num("version")!=b.num("expected_member_version"))fail(409,"成员已变化");replace("members",updated(membership,jsonOf("role" to "member")));replace("members",updated(r,jsonOf("role" to "admin")));return ok()}
        "inventory" -> {if(s.size==3){mergeStock(b,f);return ok()};val r=find("stock",s[3]);own(r,f);version(r,b);if(m=="DELETE")remove("stock",s[3])else{validateStock(r+b);replace("stock",updated(r,b).with("_revision" to id()))};bumpStock(f);return ok()}
        "transfer" -> {if(!b.bool("confirmed")||b.num("expected_inventory_version")!=stockVersion(u))fail(409,"请重新核对个人库存");val r=find("stock",b.str("item_id").orEmpty());own(r,u);replace("stock",updated(r,jsonOf("owner" to f)));bumpStock(u);bumpStock(f);return ok()}
        "consumption" -> {val sid=b.str("session_id")?:s.getOrNull(3).orEmpty();val receipt=rows("familyReceipts").firstOrNull{it.str("id")=="$f:$sid"}
            if(receipt!=null)return ok("receipt" to receipt);if(m=="GET")fail(404,"暂无凭证")
            val changes=b.objects("items");if(changes.isEmpty()||changes.map{it.str("item_id")}.distinct().size!=changes.size)fail(422,"扣减清单无效");changes.forEach{x->val r=find("stock",x.str("item_id").orEmpty());own(r,f);if(x.num("expected_version")!=r.num("version")||(x.num("quantity")?:0.0)<=0||(x.num("quantity")?:0.0)>r.num("quantity")!!)fail(409,"共享库存已变化")}
            changes.forEach{x->val r=find("stock",x.str("item_id")!!);val left=r.num("quantity")!!-x.num("quantity")!!;if(left==0.0)remove("stock",r.str("id")!!)else replace("stock",updated(r,jsonOf("quantity" to left)))}
            bumpStock(f)
            val r=jsonOf("id" to "$f:$sid","session_id" to sid,"items" to changes,"created_at" to now());add("familyReceipts",r);return ok("receipt" to r)}
    };fail(404,"操作不存在")
}
internal fun LocalEngine.shopping(m:String,s:List<String>,b:JsonObject,u:String,f:String):JsonObject {
    fun list()=items(rows("shopping").filter{it.str("family")==f})
    if(s.size==3) {
        if(m=="GET") return list()
        val qty=b.num("quantity")
        if(b.str("name").isNullOrBlank() || b.str("unit").isNullOrBlank() || qty!=null && (!qty.isFinite() || qty<=0)) fail(422,"请填写食材和单位；数量可留空或填写有效正数")
        val old=rows("shopping").firstOrNull { it.str("family")==f && it.str("name")==b.str("name") && it.str("unit")==b.str("unit") && it.str("state")=="open" && it.str("claimed_by")==null && it.num("quantity")!=null && qty!=null }
        if(old!=null) replace("shopping",updated(old,jsonOf("quantity" to old.num("quantity")!!+qty!!)))
        else add("shopping",b.with("id" to id(),"family" to f,"version" to 1,"state" to "open","history" to listOf(jsonOf("type" to "added","at" to now()))))
        return ok()
    }
    if(s[3]=="sources")return jsonOf("recipes" to recipes.map{jsonOf("id" to it.str("id"),"name" to it.str("name"))},"menus" to rows("menus").filter{it.str("owner")==u})
    if(s[3]=="generate") {
        val source=b.str("source_id").orEmpty()
        val multiplier=b.num("multiplier")?:1.0
        if(!multiplier.isFinite() || multiplier<=0 || multiplier>100) fail(422,"配方倍数必须在 0 到 100 之间")
        var scope=f
        val ingredients=if(b.str("source_type")=="menu") {
            val menu=find("menus",source); own(menu,u)
            scope=menu.obj("config")?.str("family_id")?:u
            if(scope!=u) { member(scope,u); if(scope!=f) fail(422,"请选择菜单使用的同一家庭") }
            menu.obj("result")!!.objects("meals").flatMap{it.objects("dishes")}.flatMap{dish->dish.obj("recipe")!!.objects("ingredients").map{it.with("amount" to (it.num("amount")?:0.0)*(dish.num("factor")?:1.0))}}
        } else recipes.firstOrNull{it.str("id")==source}?.objects("ingredients")?:fail(404,"菜谱不存在")
        val stock=usableStock(scope)
        val grouped=ingredients.groupBy { com.smartcooking.app.data.InventoryRules.canonicalName(it.str("name")) to canonicalUnit(it.str("unit")) }
        for((key,items) in grouped) {
            val need=items.sumOf{it.num("amount")?:0.0}*multiplier
            val available=stock.filter { com.smartcooking.app.data.InventoryRules.canonicalName(it.str("name"))==key.first && canonicalUnit(it.str("unit"))==key.second }.sumOf{it.num("quantity")?:0.0}
            val amount=(need-available).coerceAtLeast(0.0)
            if(amount<=0) continue
            val old=rows("shopping").firstOrNull{it.str("family")==f&&it.str("name")==key.first&&it.str("unit")==key.second&&it.str("state")=="open"&&it.str("claimed_by")==null&&it.num("quantity")!=null}
            if(old!=null) replace("shopping",updated(old,jsonOf("quantity" to old.num("quantity")!!+amount)))
            else add("shopping",jsonOf("id" to id(),"family" to f,"version" to 1,"name" to key.first,"unit" to key.second,"quantity" to amount,"state" to "open","source_id" to source,"history" to listOf(jsonOf("type" to "added","at" to now()))))
        }
        return ok()
    }
    val r=find("shopping",s[3]);if(r.str("family")!=f)fail(403,"没有权限");version(r,b)
    if(s.getOrNull(4)=="stock-in") {if(r.str("state")!="bought")fail(409,"请先确认购买");mergeStock(b.with("source" to jsonOf("procurement_id" to r.str("id"))),f);replace("shopping",updated(r,jsonOf("state" to "stocked", "history" to r.objects("history") + jsonOf("type" to "stocked", "at" to now(), "quantity" to b.num("quantity"), "unit" to b.str("unit")))));notifyFamily(f,u,"${r.str("name")}已入库",s[3]);return ok()}
    val action=b.str("action");val state=when(action){"claim"->{if(r.str("state")!="open" || r.str("claimed_by")!=null)fail(409,"商品已被认领");"open"};"release"->{if(r.str("claimed_by")!=u)fail(403,"只能释放自己的认领");"open"};"buy","bought","purchase"->{if(r.str("state")!="open" || r.str("claimed_by")!=null&&r.str("claimed_by")!=u)fail(409,"购买状态已变化或已由其他成员认领");"bought"};"cancel"->{if(r.str("state")!="open"||r.str("claimed_by")!=null&&r.str("claimed_by")!=u)fail(409,"不能取消此需求");"cancelled"};else->fail(422,"状态操作不支持")}
    replace("shopping",updated(r,jsonOf("state" to state,"claimed_by" to if(action=="release")null else u,"history" to r.objects("history")+jsonOf("type" to action,"at" to now(),"actor" to u))));notifyFamily(f,u,"${r.str("name")}：${when(action){"claim"->"已认领";"release"->"已释放";"cancel"->"已取消";else->"已购买"}}",s[3]);return ok()
}
