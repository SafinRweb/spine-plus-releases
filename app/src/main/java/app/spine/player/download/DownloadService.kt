package app.spine.player.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.spine.player.MainActivity
import app.spine.player.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while songs are downloading, so a download carries on after the app
 * is left, and shows which song is arriving. The downloading itself is done by [Downloads];
 * this only watches it and stops when nothing is left to do.
 */
class DownloadService : Service() {

    private val scope: CoroutineScope = MainScope()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            // Low importance: it sits in the shade without a sound or a banner.
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION, notification(Downloads.items.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        scope.launch {
            Downloads.items.collect { items ->
                if (items.none { it.busy }) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    manager.notify(NOTIFICATION, notification(items))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(items: List<Getting>): Notification {
        val current = items.firstOrNull { it.state == GetState.Downloading || it.state == GetState.Finishing }
        val waiting = items.count { it.state == GetState.Waiting }
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(current?.song?.title ?: "Getting music")
            .setContentText(
                listOfNotNull(
                    current?.song?.artist?.takeIf { it.isNotBlank() },
                    if (waiting > 0) "$waiting more waiting" else null,
                ).joinToString(" · ").ifEmpty { null },
            )
            .setProgress(100, ((current?.progress ?: 0f) * 100).toInt(), current == null || current.state == GetState.Finishing)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION = 41
    }
}
