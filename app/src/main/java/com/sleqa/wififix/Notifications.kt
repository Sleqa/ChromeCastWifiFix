package com.sleqa.wififix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.sleqa.wififix.ui.MainActivity

/**
 * Note for anyone reading the logs later: Android TV has no notification shade
 * in the phone sense, so on a Chromecast these are close to invisible. The
 * ongoing one exists because a foreground service requires it; the status screen
 * and the event log are the real user-facing channel.
 */
object Notifications {
    const val ONGOING_ID = 1
    const val ALERT_ID = 2

    private const val CHANNEL_ONGOING = "guard"
    private const val CHANNEL_ALERT = "alerts"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                "Wi-Fi watchdog",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows that the watchdog is running." }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "Wi-Fi alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Raised when Wi-Fi could not be turned back on." }
        )
    }

    fun ongoing(ctx: Context, status: String): Notification =
        Notification.Builder(ctx, CHANNEL_ONGOING)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .build()

    fun postAlert(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(
            ALERT_ID,
            Notification.Builder(ctx, CHANNEL_ALERT)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_wifi)
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build()
        )
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
