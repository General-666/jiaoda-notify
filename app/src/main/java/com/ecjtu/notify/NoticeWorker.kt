package com.ecjtu.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

private const val CHANNEL_ID = "notice_high"

class NoticeWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        val ctx = applicationContext

        val cookie = CookieManager.getInstance().getCookie(PortalApi.PORTAL)
        if (cookie.isNullOrBlank() || !cookie.contains("JSESSIONID")) {
            notifyText(ctx, "需要重新登录", "登录态已失效，打开应用重新登录「智慧交大」")
            return Result.success()
        }

        return try {
            val list = PortalApi.fetch(cookie)
            val seen = Store.seen(ctx)
            val fresh = list.filter { it.id !in seen }
            val first = !Store.isFirstDone(ctx)

            if (!first && fresh.isNotEmpty()) {
                fresh.take(5).forEachIndexed { idx, n -> notifyNotice(ctx, idx, n) }
                if (fresh.size > 5) {
                    notifyText(ctx, "还有 ${fresh.size - 5} 条新通知", "打开应用查看全部")
                }
            }

            Store.saveSeen(ctx, seen + list.map { it.id })
            Store.setFirstDone(ctx)
            Store.setLastCheck(ctx, System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            notifyText(ctx, "检查通知失败", e.message ?: "网络异常，稍后自动重试")
            Result.retry()
        }
    }
}

private fun ensureChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "教务通知", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "智慧交大新通知提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                val attr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attr)
            }
            mgr.createNotificationChannel(ch)
        }
    }
}

private fun baseBuilder(ctx: Context, title: String, text: String): NotificationCompat.Builder {
    ensureChannel(ctx)
    val pi = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pi)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        b.setDefaults(NotificationCompat.DEFAULT_ALL)
    }
    return b
}

fun notifyNotice(ctx: Context, index: Int, n: Notice) {
    val text = (if (n.unit.isNotEmpty()) n.unit + " · " else "") + n.timeText()
    try {
        NotificationManagerCompat.from(ctx).notify(2000 + index, baseBuilder(ctx, n.title, text).build())
    } catch (_: SecurityException) {
    }
}

fun notifyText(ctx: Context, title: String, text: String) {
    try {
        NotificationManagerCompat.from(ctx).notify(title.hashCode(), baseBuilder(ctx, title, text).build())
    } catch (_: SecurityException) {
    }
}
