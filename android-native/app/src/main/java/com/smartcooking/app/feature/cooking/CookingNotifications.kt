package com.smartcooking.app.feature.cooking

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.smartcooking.app.MainActivity
import com.smartcooking.app.R
import com.smartcooking.app.core.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow

private const val TIMER_CHANNEL = "cookx-timer"
private const val EVENT_CHANNEL = "cookx-events"

/** Local reminders: off by default; the status card in the kitchen is always the source of truth. */
class CookingNotifications(private val context: Context, private val store: KeyValueStore) {
    val message = MutableStateFlow("提醒默认关闭，可主动开启。")
    private val alarms = context.getSystemService(AlarmManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(TIMER_CHANNEL, context.getString(R.string.timer_channel), NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(EVENT_CHANNEL, context.getString(R.string.event_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun prefKey(user: String) = "cookx:reminders:v1:$user"
    fun enabled(user: String): Boolean = store.get(prefKey(user)) == "true"
    fun permitted(): Boolean = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun setEnabled(user: String, value: Boolean) {
        store.put(prefKey(user), value.toString())
        if (!value) cancelAll()
        message.value = if (value) "已开启提醒，正在核对系统权限。" else "提醒已关闭；状态卡片仍可查看。"
    }

    fun exactSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(android.net.Uri.parse("package:${context.packageName}")) else null

    private fun timerIntent(id: Int, body: String) = PendingIntent.getBroadcast(
        context, id,
        Intent(context, TimerAlarmReceiver::class.java).putExtra("id", id).putExtra("title", "CookX 步骤计时").putExtra("body", body),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Schedules exactly one alarm for the current step deadline and cancels obsolete ones.
     * Returns the reminder key when a system notification covers the timer.
     */
    fun reconcileTimer(engine: CookingEngine, currentUser: String?): String? {
        val s = engine.state.value
        val previous = store.get(SCHEDULED)?.toIntOrNull()
        val active = s != null && engine.ready && s.user == currentUser && enabled(s.user) && permitted() && s.active
        val t = s?.timer
        val future = active && t?.deadline != null && t.deadline > System.currentTimeMillis()
        val key = if (future) "${s!!.id}:timer:${s.stepIndex}:${t!!.round}" else null
        val id = key?.let(::notificationId)
        if (previous != null && previous != id) {
            alarms.cancel(timerIntent(previous, ""))
            store.remove(SCHEDULED)
        }
        if (!future) return null
        val body = "第 ${s!!.stepIndex + 1} 步计时结束，请检查状态。"
        val pending = timerIntent(id!!, body)
        if (exactAllowed()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t!!.deadline!!, pending)
            message.value = "系统计时提醒已开启。"
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t!!.deadline!!, pending)
            message.value = "未授予精确计时权限，系统提醒可能延迟；返回前台会核对计时。"
        }
        store.put(SCHEDULED, id.toString())
        return key
    }

    fun notifyEvent(user: String, currentUser: String?, reminder: Reminder) {
        if (user != currentUser || !enabled(user) || !permitted()) return
        post(context, notificationId(reminder.key), "CookX 烹饪提醒", reminder.text, EVENT_CHANNEL)
    }

    /** Restored sessions require confirmation after a restart, so pending alarms are dropped. */
    fun cancelAll() {
        store.get(SCHEDULED)?.toIntOrNull()?.let { alarms.cancel(timerIntent(it, "")) }
        store.remove(SCHEDULED)
        NotificationManagerCompat.from(context).cancelAll()
    }

    companion object {
        private const val SCHEDULED = "cookx:scheduled-timer"

        fun post(context: Context, id: Int, title: String, body: String, channel: String = TIMER_CHANNEL) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_cookx)
                .setColor(0xFF173F35.toInt())
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CookingNotifications.post(context, intent.getIntExtra("id", 1), intent.getStringExtra("title") ?: "CookX 步骤计时", intent.getStringExtra("body") ?: "计时结束，请检查烹饪状态。")
    }
}
