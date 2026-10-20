package com.smartcooking.app.offline
import com.smartcooking.app.core.*
import kotlinx.serialization.json.*
import kotlin.math.*

/** Deterministic regularized tag model, with chronological holdout and explicit fallback. */
internal fun LocalEngine.trainLocal(u:String):JsonObject {
    val rows=rows("feedback").filter{it.str("owner")==u}.sortedWith(compareBy({it.str("created_at")},{it.str("id")}))
    val base=jsonOf("id" to u,"algorithm" to "tag-logistic-local-v1","accepted" to false,"trained_at" to now(),"row_count" to rows.size)
    if(rows.size<12)return base.with("reason" to "至少需要 12 道菜的明确反馈，并同时包含喜欢与不喜欢")
    var cut=(rows.size*.75).toInt();while(cut>0&&cut<rows.size&&rows[cut-1].str("created_at")==rows[cut].str("created_at"))cut--
    val train=rows.take(cut);val test=rows.drop(cut)
    if(train.size<6||test.size<3||train.map{it.bool("liked")}.distinct().size<2||test.map{it.bool("liked")}.distinct().size<2)return base.with("reason" to "时间划分后的训练和测试正负反馈不足，继续使用原排序")
    fun tokens(row:JsonObject)=recipes.firstOrNull{it.str("id")==row.str("recipe_id")}?.let{tags(it)}?:emptyList()
    val vocabulary=train.flatMap(::tokens).distinct().sorted()
    fun vector(row:JsonObject,v:List<String>)=doubleArrayOf(1.0,*v.map{if(it in tokens(row))1.0 else 0.0}.toDoubleArray())
    fun fit(data:List<JsonObject>,v:List<String>):DoubleArray {val w=DoubleArray(v.size+1);val xs=data.map{vector(it,v)};repeat(800){val gradients=DoubleArray(w.size);data.forEachIndexed { i,r->val error=sigmoid(xs[i].indices.sumOf{w[it]*xs[i][it]})-(if(r.bool("liked"))1 else 0);w.indices.forEach{gradients[it]+=xs[i][it]*error/data.size} };w.indices.forEach{w[it]-=.25*(gradients[it]+if(it==0)0.0 else .08*w[it])} };return w}
    fun auc(scores:List<Double>):Double {val positive=test.indices.filter{test[it].bool("liked")};val negative=test.indices.filter{!test[it].bool("liked")};return positive.sumOf{a->negative.sumOf{b->if(scores[a]>scores[b])1.0 else if(scores[a]==scores[b]).5 else 0.0}}/(positive.size*negative.size)}
    fun loss(scores:List<Double>)=test.indices.sumOf{i->val p=scores[i].coerceIn(1e-8,1-1e-8);if(test[i].bool("liked"))-ln(p) else -ln(1-p)}/test.size
    val w=fit(train,vocabulary);val predicted=test.map{r->val x=vector(r,vocabulary);sigmoid(w.indices.sumOf{w[it]*x[it]})}
    val baseline=test.map{it.num("original_score")?:.5};val original=auc(baseline);val personal=auc(predicted);val constant=train.count{it.bool("liked")}.toDouble()/train.size
    val accepted=personal>original+1e-6 && loss(predicted)<=loss(test.map{constant})
    var model=base.with("accepted" to accepted,"reason" to if(accepted)"时间留出对照通过，启用标签偏好重排"else"时间留出对照未通过，继续使用原排序", "metrics" to jsonOf("train_count" to train.size,"test_count" to test.size,"train_end" to train.last().str("created_at"),"test_start" to test.first().str("created_at"),"original_pairwise_auc" to original,"personalized_pairwise_auc" to personal,"heldout_log_loss" to loss(predicted)))
    if(accepted){val v=rows.flatMap(::tokens).distinct().sorted();model=model.with("vocabulary" to v,"weights" to fit(rows,v).toList(),"model_version" to "tag-local-${id().take(8)}")};return model
}
internal fun tags(r:JsonObject)=r.strings("tags")+listOfNotNull(r.str("method")?.let{"方法:$it"})
internal fun sigmoid(v:Double)=1/(1+exp(-v.coerceIn(-40.0,40.0)))
internal fun prediction(model:JsonObject,recipe:JsonObject):Double {val words=model.strings("vocabulary");val w=model["weights"].asArray().orEmpty().map{it.asDouble()?:0.0};if(w.size!=words.size+1)return .5;return sigmoid(w[0]+words.indices.sumOf{if(words[it] in tags(recipe))w[it+1]else 0.0})}
