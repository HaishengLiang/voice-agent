package com.example.xiaomiao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Config.init(context)
        WakeService.createChannel(context)

        val serviceIntent = Intent(context, WakeService::class.java)
            .setAction(WakeService.ACTION_START)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Throwable) {
            // Android 14+ 限制后台启动麦克风前台服务，降级为提示用户手动打开。
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, WakeService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("小喵语音智能需要手动启动")
                .setContentText("系统限制后台启动麦克风，请点击此处启动监听")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            runCatching {
                NotificationManagerCompat.from(context).notify(2002, notification)
            }
        }
    }
}
