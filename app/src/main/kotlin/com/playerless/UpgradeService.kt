package com.playerless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent

private const val CHANNEL = "downloads"
private const val RUNNING_ID = 2
private const val DONE_ID = 3

// "Riscarica in lossless" runs in here: a foreground service whose notification counts the songs,
// so the swap goes on with the screen off or the app closed. Started from the settings, or by adb's
// shell, the one other caller holding DUMP:
//   adb shell am start-foreground-service -n com.playerless/.UpgradeService
class UpgradeService : Service() {
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Lossless.init(this)
        Downloads.init(this)
        notifications = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW)
        notifications.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(RUNNING_ID, notification(getString(R.string.lossless_upgrade_running, 0, Downloads.list().size), ongoing = true))
        // One already running finishes and stops the service by itself.
        Downloads.upgradeToLossless(
            progress = { done, total ->
                notifications.notify(RUNNING_ID, notification(getString(R.string.lossless_upgrade_running, done, total), ongoing = true))
            },
            done = { flac, kept ->
                stopForeground(STOP_FOREGROUND_REMOVE)
                notifications.notify(DONE_ID, notification(getString(R.string.lossless_upgrade_done, flac, kept), ongoing = false))
                stopSelf()
            },
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    private fun notification(text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.lossless_upgrade))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .build()
    }
}
