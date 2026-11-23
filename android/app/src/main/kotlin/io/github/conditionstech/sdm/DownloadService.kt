package io.github.conditionstech.sdm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.conditionstech.sdm.downloads.DownloadItem
import io.github.conditionstech.sdm.downloads.DownloadState
import io.github.conditionstech.sdm.engine.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps SDM running while downloads are active, with Android's required notification showing
 * their progress. It starts when a download starts and stops itself when none is left.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val downloads = (application as SdmApplication).downloads
        startInForeground(Notifications.progress(this, downloads.items.value))
        scope.launch {
            downloads.items.collect { items ->
                if (items.none { it.active }) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    Notifications.show(this@DownloadService, Notifications.PROGRESS_ID, Notifications.progress(this@DownloadService, items))
                }
            }
        }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifications.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifications.PROGRESS_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /** Android 15 limits data-sync services to 6 hours a day: pause everything, resumable later. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val downloads = (application as SdmApplication).downloads
        downloads.items.value.filter { it.active }.forEach { downloads.pause(it.id) }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}

/** SDM's notifications: one for running downloads, one per finished download. */
object Notifications {
    const val PROGRESS_ID = 1
    private const val PROGRESS_CHANNEL = "progress"
    private const val FINISHED_CHANNEL = "finished"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL, "Downloads in progress", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(FINISHED_CHANNEL, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun progress(context: Context, items: List<DownloadItem>): Notification {
        val running = items.filter { it.active }
        val received = running.sumOf { it.receivedBytes }
        val total = running.mapNotNull { it.totalBytes }.sum()
        val sizesKnown = running.all { it.totalBytes != null }
        val title = if (running.size == 1) {
            "Downloading ${running[0].fileName ?: "a file"}"
        } else {
            "Downloading ${running.size} files"
        }
        val speed = running.sumOf { it.bytesPerSecond ?: 0.0 }
        val text = buildString {
            append(Units.formatSize(received.toDouble()))
            if (sizesKnown && total > 0) append(" of ").append(Units.formatSize(total.toDouble()))
            if (speed > 0) append(" · ").append(Units.formatSpeed(speed))
        }
        return NotificationCompat.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sdm)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .apply {
                if (sizesKnown && total > 0) {
                    setProgress(1000, (received * 1000 / total).toInt(), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    fun finished(context: Context, item: DownloadItem) {
        val name = item.savedName ?: item.fileName ?: "A download"
        val (title, text) = when (item.state) {
            DownloadState.COMPLETED -> "Download complete" to "$name · saved in Download/SDM"
            else -> "Download failed" to "$name: ${item.error.orEmpty()}"
        }
        val notification = NotificationCompat.Builder(context, FINISHED_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sdm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        show(context, (1000 + item.id).toInt(), notification)
    }

    /** Shows a notification if the user allowed them (Android 13+ asks); otherwise quietly skips. */
    fun show(context: Context, id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
