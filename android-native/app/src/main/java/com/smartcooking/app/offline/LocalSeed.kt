package com.smartcooking.app.offline
import com.smartcooking.app.core.*
import kotlinx.serialization.json.*
import java.time.Instant

/** Curated content belongs only to the isolated local package. Dates anchor once at first launch. */
internal fun LocalEngine.seed():JsonObject {
    fun time(days:Double)=Instant.ofEpochMilli(clock()+(days*86400000).toLong()).toString()
    val users=listOf(jsonOf("id" to "lin","username" to "lin","nickname" to "林小厨","phone" to "13800000001","password" to "cookx123","role" to "admin"),jsonOf("id" to "chen","username" to "chen","nickname" to "陈小满","phone" to "13800000002","password" to "cookx123","role" to "member"))
    val names=listOf("番茄","鸡蛋","西兰花","牛奶","豆腐","鸡胸肉","土豆","胡萝卜","青椒","黄瓜","香菇","菠菜","洋葱","南瓜","白萝卜","猪肉","牛肉","虾仁","三文鱼","大米","面条","小米","食用油","盐","生抽","醋","生姜","大蒜","小葱","苹果","香蕉","酸奶")
    val stock=names.mapIndexed { i,n -> jsonOf("id" to "stock-$i","owner" to "lin","name" to n,"quantity" to (2+i%5),"unit" to "库存计数","_revision" to "seed-$i","version" to 1,"purchase_time" to time(if(i in 3..4)-7.0 else -2.0),"add_time" to time(-1.0),"expiry_date" to time(if(i==3)1.0 else if(i==4)2.0 else 5.0+i%10),"storage_type" to if(i in 15..18)"冷冻" else if(i in 19..28)"常温" else "冷藏","shelf_life" to 7+i%10) }
    val shared=stock.take(8).mapIndexed { i,r->r.with("id" to "family-stock-$i","owner" to "home","quantity" to (if(i==1)10 else 500),"unit" to if(i==1)"个" else "克") }
    val copies=recipes.take(5).mapIndexed { i,r->jsonOf("id" to "copy-$i","owner" to "lin","version" to 1,"recipe" to r,"source" to jsonOf("type" to "standard","id" to r.str("id"),"version" to "1"),"created_at" to time(-3.0)) }
    val texts=listOf("周末的番茄炒蛋，酸甜配米饭很合适。","土豆丝切细一点，口感更清爽。","今天给家人做了青椒肉丝。","慢炖的一锅牛腩，留出耐心等待。","香煎鸡胸肉配时蔬，晚餐完成。","尝试不同的蔬菜搭配，颜色也更丰富。")
    val posts=texts.mapIndexed { i,t -> jsonOf("id" to "post-$i","owner" to if(i%2==0)"lin" else "chen","author_name" to if(i%2==0)"林小厨" else "陈小满","version" to 1,"text" to t,"created_at" to time(-i*.3),"hidden" to false,"recipe" to recipes[i],"recipe_copy_id" to "copy-${i%5}","image_ids" to listOf("dish-${i%3}")) }
    val completed=recipes.take(8).mapIndexed { i,r->jsonOf("id" to "completion-$i","session_id" to "cooking-$i","owner" to "lin","recipe" to r,"recipe_version" to 1,"confirmed" to true,"completed_at" to time(-i-1.0),"provenance" to "built_in_example") }
    val challenge=listOf(jsonOf("id" to "first","name" to "起步一餐","description" to "加入后完成一次烹饪","target" to 1),jsonOf("id" to "variety","name" to "三道新菜","description" to "加入后尝试三道不同菜谱","target" to 3),jsonOf("id" to "week","name" to "一周三餐","description" to "七天内确认完成三次烹饪","target" to 3))
    return jsonOf("schemaVersion" to 1,"users" to users,"tokens" to emptyList<String>(),"stock" to stock+shared+stock.take(3).map{it.with("id" to "chen-${it.str("id")}","owner" to "chen")},
        "families" to listOf(jsonOf("id" to "home","name" to "我们的厨房","version" to 1,"created_at" to time(-7.0))),
        "members" to listOf(jsonOf("id" to "home-lin","family" to "home","user_id" to "lin","role" to "admin","display_name" to "林小厨","version" to 1),jsonOf("id" to "home-chen","family" to "home","user_id" to "chen","role" to "member","display_name" to "陈小满","version" to 1)),
        "shopping" to listOf("西兰花","鸡蛋","牛奶","面粉","香菇","大米").mapIndexed{i,n->jsonOf("id" to "shop-$i","family" to "home","name" to n,"quantity" to if(i==1)10 else 500,"unit" to if(i==1)"个" else "克","state" to if(i==0)"open" else if(i==1)"bought" else "open","claimed_by" to if(i<2)"lin" else null,"version" to 1,"history" to emptyList<String>())},
        "menus" to listOf(jsonOf("id" to "menu-week","owner" to "lin","name" to "一周家常菜单","version" to 1,"inventory_version" to 1,"price_version" to 0,"created_at" to time(0.0),"config" to jsonOf("people" to 2,"portion_factor" to 1,"max_minutes" to 120,"max_difficulty" to 2,"max_repeat" to 3,"meals" to (0..6).flatMap{day->listOf("lunch","dinner").map{jsonOf("day" to day,"meal" to it)}},"start_date" to time(0.0).take(10)),"result" to jsonOf("solver_status" to "FEASIBLE","meals" to (0..6).flatMap{day->listOf("lunch","dinner").mapIndexed{j,meal->val main=recipes[day*2+j];val staple=recipes.filter { "staple" in it.obj("_planning")!!.strings("roles") }[(day*2+j)%5];jsonOf("day" to day,"meal" to meal,"minutes" to 25,"dishes" to listOf(jsonOf("role" to "main","name" to main.str("name"),"recipe_id" to main.str("id"),"recipe" to main,"factor" to 1),jsonOf("role" to "staple","name" to staple.str("name"),"recipe_id" to staple.str("id"),"recipe" to staple,"factor" to 2)))}}))),
        "participation" to listOf(jsonOf("id" to "lin:first","owner" to "lin","challenge" to "first","joined_at" to time(-10.0))),
        "feedback" to recipes.take(18).mapIndexed{i,r->jsonOf("id" to "lin:${r.str("id")}","owner" to "lin","recipe_id" to r.str("id"),"version" to 1,"liked" to (i%2==0),"original_score" to .5,"created_at" to time(-20.0+i),"provenance" to "curated")},
        "copy" to copies,"favorite" to copies.take(3).map{it.with("id" to "fav-${it.str("id")}")},"posts" to posts,
        "comments" to posts.flatMapIndexed{i,p->listOf(jsonOf("id" to "comment-$i","post" to p.str("id"),"owner" to "chen","author_name" to "陈小满","version" to 1,"text" to "搭配不错，下次也试试。","created_at" to time(-.1)))},
        "likes" to posts.mapIndexed{i,p->jsonOf("id" to "${p.str("id")}:chen","post" to p.str("id"),"owner" to "chen","liked" to true,"version" to 1,"created_at" to time(-.1))},
        "completions" to completed,"challengePresets" to challenge,
        "leftovers" to listOf(jsonOf("id" to "left-raw","owner" to "lin","version" to 1,"kind" to "raw","inventory_id" to "stock-0","name" to "番茄"),jsonOf("id" to "left-rice","owner" to "lin","version" to 1,"kind" to "cooked","cooked_type" to "rice","name" to "米饭","quantity" to 2,"unit" to "份","made_at" to time(-.1),"stored_at" to time(-.09),"expiry_at" to time(.8),"storage_type" to "冷藏","cold_chain_confirmed" to true,"abnormal" to false)),
        "notices" to emptyList<JsonObject>(),
        "chat" to recipes.take(2).mapIndexed{i,r->jsonOf("id" to "chat-$i","owner" to "lin","role" to "assistant","content" to "内置菜谱示例：${r.str("name")}，先检查需要的食材。", "source" to "built_in","recipe" to r,"timestamp" to time(-i.toDouble()))})
}
