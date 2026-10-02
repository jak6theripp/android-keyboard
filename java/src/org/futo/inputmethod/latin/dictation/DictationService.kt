package org.futo.inputmethod.latin.dictation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.futo.inputmethod.latin.R

/**
 * Microphone-type foreground service that keeps recording alive under One UI while
 * [DictationEngine] runs. Holds no state of its own.
 */
class DictationService : Service() {
    companion object {
        const val ACTION_START = "org.futo.inputmethod.latin.dictation.START"
        const val ACTION_STOP = "org.futo.inputmethod.latin.dictation.STOP"
        private const val CHANNEL_ID = "dictation"
        private const val NOTIFICATION_ID = 7301
        private const val TAG = "DictationService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                try {
                    val notification = buildNotification()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    DictationLog.event("fgs_started", "sdk" to Build.VERSION.SDK_INT)
                    DictationEngine.onServiceReady()
                } catch (e: Exception) {
                    // Android 14+: ForegroundServiceStartNotAllowedException / SecurityException when
                    // the mic FGS type isn't permitted from this process state.
                    Log.e(TAG, "startForeground failed", e)
                    DictationEngine.onServiceFailed(e.javaClass.simpleName + ": " + e.message)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                if (DictationEngine.isActive) {
                    // Stop pressed in the notification: ask the engine; it calls back with ACTION_STOP once idle.
                    DictationEngine.stop("notification_stop")
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DictationLog.event("fgs_destroyed", "engineActive" to DictationEngine.isActive)
        if (DictationEngine.isActive) DictationEngine.stop("service_destroyed")
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ time limits for some FGS types; microphone has none, but handle defensively.
        DictationLog.event("fgs_timeout")
        DictationEngine.stop("fgs_timeout")
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.dictation_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, DictationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.mic_fill)
            .setContentTitle(getString(R.string.dictation_notification_title))
            .setContentText(getString(R.string.dictation_notification_text))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.dictation_notification_stop), stopIntent).build())
            .build()
    }
}
