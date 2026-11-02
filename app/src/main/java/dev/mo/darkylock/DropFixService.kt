package dev.mo.darkylock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.mo.darkylock.data.LockNotifier
import dev.mo.darkylock.modem.ModemController
import dev.mo.darkylock.modem.RootState
import dev.mo.darkylock.shizuku.RootOrShizuku
import dev.mo.darkylock.shizuku.ShizukuClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DropFixService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var modem: ModemController? = null
    private var stopWatchingShizuku: (() -> Unit)? = null
    private var dropFixJob: Job? = null
    private var packages = emptyList<String>()
    private var wantsDropFix = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        modem =
            ModemController(
                this,
                serviceScope,
                RootOrShizuku(this) { modem?.helperLost("The Shizuku service stopped. Try again to restart it.") },
                onConnected = ::startDropFixLoop,
            )
        stopWatchingShizuku = ShizukuClient.onBinderReceived { modem?.reconnect() }
        serviceScope.launch {
            modem?.state?.collect { state ->
                val denied = state.root as? RootState.Denied
                if (wantsDropFix && denied != null) failDropFix(denied.reason)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        updateForeground("Starting Drop-Fix")
        val requestedState = intent?.getBooleanExtra(LockNotifier.EXTRA_DROP_FIX_ENABLED, !wantsDropFix) ?: !wantsDropFix
        if (!requestedState && wantsDropFix) {
            stopDropFix()
        } else if (requestedState && !wantsDropFix) {
            startDropFix(intent?.getStringExtra(LockNotifier.EXTRA_GAME_PACKAGES).orEmpty())
        } else if (!requestedState) {
            getSharedPreferences("darkylock", MODE_PRIVATE)
                .edit()
                .putBoolean(LockNotifier.PREF_DROP_FIX_ENABLED, false)
                .apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startDropFix(rawPackages: String) {
        val savedPackages =
            getSharedPreferences("darkylock", MODE_PRIVATE)
                .getString(LockNotifier.PREF_GAME_PACKAGES, "")
                .orEmpty()
        packages =
            (rawPackages.ifBlank { savedPackages })
                .split(',', ';', '\n')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
        if (packages.isEmpty()) {
            failDropFix("Enter game package names in Darky Lock before using Drop-Fix.")
            return
        }
        wantsDropFix = true
        getSharedPreferences("darkylock", MODE_PRIVATE)
            .edit()
            .putBoolean(LockNotifier.PREF_DROP_FIX_ENABLED, true)
            .apply()
        updateForeground("Connecting to enable Drop-Fix")
        modem?.connect()
    }

    private fun startDropFixLoop() {
        if (!wantsDropFix || dropFixJob?.isActive == true) return
        updateForeground("Drop-Fix is running")
        dropFixJob =
            serviceScope.launch {
                try {
                    while (isActive) {
                        modem?.setGamePerformanceMode(packages, true)
                        delay(20_000)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failDropFix(e.message ?: "Drop-Fix failed.")
                }
            }
    }

    private fun stopDropFix() {
        wantsDropFix = false
        getSharedPreferences("darkylock", MODE_PRIVATE)
            .edit()
            .putBoolean(LockNotifier.PREF_DROP_FIX_ENABLED, false)
            .apply()
        serviceScope.launch {
            dropFixJob?.cancelAndJoin()
            dropFixJob = null
            try {
                modem?.takeIf { it.helperRunning }?.setGamePerformanceMode(packages, false)
                modem?.close()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failDropFix(e.message ?: "Drop-Fix failed.")
            }
        }
    }

    private fun failDropFix(message: String) {
        wantsDropFix = false
        getSharedPreferences("darkylock", MODE_PRIVATE)
            .edit()
            .putBoolean(LockNotifier.PREF_DROP_FIX_ENABLED, false)
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        updateErrorNotification(message)
        modem?.close()
        stopSelf()
    }

    private fun ensureChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, "Drop-Fix", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val toggle =
            PendingIntent.getForegroundService(
                this,
                2,
                Intent(this, DropFixService::class.java).setAction(LockNotifier.ACTION_TOGGLE_DROP_FIX),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_darky_sleeping)
            .setContentTitle("Darky Lock · Drop-Fix")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop Drop-Fix", toggle).build())
            .build()
    }

    private fun updateForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateErrorNotification(message: String) {
        val notification =
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_darky_sleeping)
                .setContentTitle("Darky Lock · Drop-Fix")
                .setContentText(message)
                .setAutoCancel(true)
                .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopWatchingShizuku?.invoke()
        modem?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "drop_fix"
        const val NOTIFICATION_ID = 2
    }
}
