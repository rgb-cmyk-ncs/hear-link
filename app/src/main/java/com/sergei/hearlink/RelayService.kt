package com.sergei.hearlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the sound going with the screen off: an ongoing notification with a Stop button,
 * and a wake lock so the phone doesn't doze off mid-sentence.
 */
class RelayService : Service(), Relay.Listener {

    private var wakeLock: PowerManager.WakeLock? = null

    /** Headphones unplugged or Bluetooth dropped: Android warns before switching to the speaker. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Relay.stop("The headphones were disconnected, so the sound was stopped.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shown while HearLink sends sound to the headphones"
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
        Relay.addListener(this)
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Relay.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val note = build()
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTE_ID, note)
            }
        } catch (e: Exception) {
            Relay.stop("Android did not allow HearLink to keep running in the background.")
            stopSelf()
            return START_NOT_STICKY
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HearLink:relay").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (!Relay.running) stopSelf()
        return START_NOT_STICKY
    }

    override fun onRelayChanged() {
        if (!Relay.running) {
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        Relay.removeListener(this)
        try { unregisterReceiver(noisy) } catch (e: Exception) { }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        wakeLock = null
        super.onDestroy()
    }

    private fun build(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("HearLink is on")
            .setContentText("Microphone → " + (Relay.outputName ?: "headphones"))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stop).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    companion object {
        private const val CHANNEL = "relay"
        private const val NOTE_ID = 1
        const val ACTION_STOP = "com.sergei.hearlink.STOP"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, RelayService::class.java))
            } catch (e: Exception) { }
        }
    }
}
