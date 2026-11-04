package dev.mo.darkylock.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.mo.darkylock.DropFixService
import dev.mo.darkylock.MainActivity
import dev.mo.darkylock.R

object LockNotifier {
    const val ACTION_TOGGLE_DROP_FIX = "dev.mo.darkylock.action.TOGGLE_DROP_FIX"
    const val EXTRA_GAME_PACKAGES = "gamePackages"
    const val EXTRA_DROP_FIX_ENABLED = "dropFixEnabled"
    const val PREF_GAME_PACKAGES = "gamePackageNames"
    const val PREF_DROP_FIX_ENABLED = "dropFixEnabled"
    private const val CHANNEL_ID = "locks"
    private const val NOTIFICATION_ID = 1

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(context: Context) {
        val channel =
            NotificationChannel(CHANNEL_ID, "Active locks", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows which bands, cells or networks Darky Lock has locked."
                setShowBadge(false)
            }
        manager(context).createNotificationChannel(channel)
    }

    fun show(
        context: Context,
        lines: List<String>,
        lockedSinceMillis: Long,
    ) {
        val permissionMissing =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (permissionMissing) return
        ensureChannel(context)
        val openApp =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val dropFix =
            PendingIntent.getForegroundService(
                context,
                1,
                Intent(context, DropFixService::class.java)
                    .setAction(ACTION_TOGGLE_DROP_FIX),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_darky_sleeping)
                .setContentTitle("Darky Lock · Active")
                .setContentText(lines.joinToString(", "))
                .setStyle(
                    Notification.BigTextStyle()
                        .setBigContentTitle("Your network locks are resting safely")
                        .bigText(lines.joinToString("\n")),
                )
                .setWhen(lockedSinceMillis)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp)
                .addAction(Notification.Action.Builder(null, "Drop-Fix", dropFix).build())
                .build()
        manager(context).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) = manager(context).cancel(NOTIFICATION_ID)
}
