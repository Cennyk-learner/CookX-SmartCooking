package com.smartcooking.app.offline
import com.smartcooking.app.core.*
import kotlinx.serialization.json.*
import java.time.*
import kotlin.math.round

/** FreshFusion's T/S subset with the repository's unchanged storage rules; V/H stay unavailable. */
internal fun localFreshness(r:JsonObject,rules:JsonObject,now:Long):JsonObject {
    val zone = ZoneId.of("Asia/Shanghai")
    fun time(s:String?, endOfDay:Boolean=false):Long? = s?.let{runCatching{Instant.parse(it).toEpochMilli()}.getOrNull() ?: runCatching{LocalDateTime.parse(it).atZone(zone).toInstant().toEpochMilli()}.getOrNull() ?: runCatching{LocalDate.parse(it).atTime(if(endOfDay) LocalTime.MAX else LocalTime.MIN).atZone(zone).toInstant().toEpochMilli()}.getOrNull()}
    val start=time(r.str("purchase_time"))?:time(r.str("add_time"))
    val expiry=time(r.str("expiry_date"),true)?:r.num("shelf_life")?.takeIf{it>0}?.let{days->start?.plus((days*86400000).toLong())}
    val t=if(start!=null&&expiry!=null&&start<=now&&expiry>start)round(((expiry-now).toDouble()/(expiry-start)).coerceIn(0.0,1.0)*1e6)/1e6 else null
    val days = expiry?.let { java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), Instant.ofEpochMilli(it).atZone(zone).toLocalDate()).toInt() }
    val expired = expiry != null && now >= expiry
    val critical = !expired && days == 0
    val soon = !expired && days != null && days in 0..2
    val expiryState = when { expiry == null -> "unknown"; expired -> "expired"; days == 0 -> "expires_today"; days == 1 -> "critical_1d"; days == 2 -> "expiring_2d"; else -> "normal" }
    val storage=rules.obj("storage_aliases")?.str(r.str("storage_type").orEmpty())
    val name=r.str("ingredient_name")?:r.str("name").orEmpty()
    val category=rules.obj("ingredient_categories")?.str(name)
    val status=category?.let{rules.obj("category_rules")?.obj(it)?.str(storage.orEmpty())}
    val s=mapOf("recommended" to 1.0,"acceptable" to .7,"suboptimal" to .3,"unsuitable" to 0.0)[status]
    val weights=if(t!=null&&s!=null)jsonOf("T" to .733333,"S" to .266667)else if(t!=null)jsonOf("T" to 1)else if(s!=null)jsonOf("S" to 1)else EmptyObject
    val score=if(weights.isEmpty())null else round(10000*((t?:0.0)*(weights.num("T")?:0.0)+(s?:0.0)*(weights.num("S")?:0.0)))/100
    val level=when{expired->"expired";score==null->"unknown";score>=80->"fresh";score>=60->"good";score>=40->"consume_soon";else->"high_risk"}
    val confidence=(if(t!=null).5 else 0.0)+(if(s!=null).2 else 0.0)
    val reasons=listOf(if(t==null)"时间字段不足，时间鲜度不可用"else if(expired)"已超过记录的到期时间"else if(critical)"记录的到期时间为当日"else "时间鲜度 T=${t}",if(s==null)"该食材的储存适配度数据不足"else "储存方式命中维护规则", "暂无可靠视觉鲜度输入")
    return jsonOf("item_id" to r.str("id"),"fresh_score" to score,"freshness_level" to level,"freshness_label" to mapOf("expired" to "已过期","unknown" to "数据不足","fresh" to "新鲜","good" to "状态良好","consume_soon" to "建议尽快食用","high_risk" to "鲜度风险较高")[level],
        "expired" to expired,"critical" to critical,"expiring_soon" to soon,"expiry_state" to expiryState,"days_remaining" to days,"timezone" to zone.id,"confidence_score" to confidence,"confidence_level" to if(confidence>=.4)"medium"else"low","confidence_reasons" to reasons,
        "component_scores" to jsonOf("T" to t,"S" to s,"V" to null,"H" to null),"effective_weights" to weights,"evaluated_at" to Instant.ofEpochMilli(now).toString(),"algorithm_version" to "freshfusion-ts-local-v1",
        "risk_flags" to listOfNotNull(if(expired)"expired"else if(critical)"critical"else if(soon)"expiring_soon"else null,if(status=="unsuitable")"storage_unsuitable"else null),"reasons" to reasons,
        "time_details" to jsonOf("score" to t,"start_time" to start?.let{Instant.ofEpochMilli(it).toString()},"expiry_time" to expiry?.let{Instant.ofEpochMilli(it).toString()}),"storage_details" to jsonOf("score" to s,"category" to category,"status" to status,"storage_type" to storage),
        "data_quality_notes" to listOf("未提供视觉和储存环境温度输入；锅温不参与鲜度融合"),"disclaimer" to "结果为基于现有数据的辅助判断，不能替代专业食品安全检测。")
}
