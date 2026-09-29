package com.dosely.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dosely.app.MainActivity
import com.dosely.app.R

object Notifications {
    const val CHANNEL_DOSE = "dose_reminders"
    const val CHANNEL_STOCK = "stock_alerts"
    const val CHANNEL_WEEKLY = "weekly_weigh_in"

    const val EXTRA_ROUTE = "dosely.route"
    const val REQUEST_DOSE = 1001

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val dose = NotificationChannel(
            CHANNEL_DOSE, context.getString(R.string.notif_channel_doses),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.notif_channel_doses_desc) }
        val stock = NotificationChannel(
            CHANNEL_STOCK, context.getString(R.string.notif_channel_stock),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notif_channel_stock_desc) }
        val weekly = NotificationChannel(
            CHANNEL_WEEKLY, context.getString(R.string.notif_channel_weekly),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notif_channel_weekly_desc) }
        nm.createNotificationChannels(listOf(dose, stock, weekly))
    }

    fun canNotify(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    // Permission is verified by canNotify() above; lint cannot see the data flow.
    @android.annotation.SuppressLint("MissingPermission")
    fun post(
        context: Context,
        channelId: String,
        id: Int,
        title: String,
        body: String,
        route: String,
    ) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ROUTE, route)
        }
        val pi = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_dosely)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
