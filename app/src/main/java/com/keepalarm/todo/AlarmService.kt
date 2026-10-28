package com.keepalarm.todo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Foreground service that rings the alarm: looping alarm-stream sound with a rising volume,
 * repeating vibration, wake lock, full-screen alarm screen and edge glow.
 * If nobody reacts, it rings again after the user's chosen interval (default 2 minutes), again and again.
 */
class AlarmService : Service() {

    companion object {
        const val CH = "alarm_channel_v1"
        const val NOTIF_ID = 4242

        @Volatile
        var ringingId: Long = 0L

        fun stop(c: Context) {
            c.stopService(Intent(c, AlarmService::class.java))
        }
    }

    /** Time before an ignored alarm rings again. Set by the user in the main screen. */
    private fun retryMs(): Long = Prefs.retryMinutes(this) * 60_000L

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null
    private val h = Handler(Looper.getMainLooper())
    private var noteId = 0L
    private var volume = 0.3f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newId = intent?.getLongExtra("noteId", 0L) ?: 0L
        val note = Repo.get(this, newId)
        createChannel()

        val open = Intent(this, AlarmActivity::class.java)
            .putExtra("noteId", newId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = (note?.displayTitle() ?: "To-do reminder")
        val notif = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("⏰ $title")
            .setContentText("Tap to open the alarm")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        // A new alarm while one is ringing: restart cleanly for the new one.
        stopRinging()
        noteId = newId
        ringingId = newId
        startRinging()

        if (Perms.overlay(this)) {
            // Apps with overlay permission may start screens from the background.
            try { startActivity(open) } catch (_: Exception) {}
            GlowOverlay.show(this, 0)
        }
        return START_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CH, "Alarms", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        try { ch.setBypassDnd(true) } catch (_: Exception) {}
        nm.createNotificationChannel(ch)
    }

    private fun startRinging() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "keepalarm:ring").apply {
            acquire(4 * 60 * 1000L)
        }

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri)
            mp.isLooping = true
            volume = 0.3f
            mp.setVolume(volume, volume)
            mp.prepare()
            mp.start()
            player = mp
            h.postDelayed(rampRunnable, 2000)
        } catch (_: Exception) {}

        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 500, 800, 500, 1200, 800), 0)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            // The AudioAttributes overload only exists from Android 12; older phones get the plain call.
            if (Build.VERSION.SDK_INT >= 31) {
                v.vibrate(effect, attrs)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect)
            }
            vibrator = v
        } catch (_: Exception) {}

        h.postDelayed(ignoredRunnable, retryMs())
    }

    private val rampRunnable = object : Runnable {
        override fun run() {
            if (volume < 1f) {
                volume = (volume + 0.1f).coerceAtMost(1f)
                try { player?.setVolume(volume, volume) } catch (_: Exception) {}
                h.postDelayed(this, 2000)
            }
        }
    }

    /** Nobody reacted: ring again in a minute. This repeats until the alarm is handled. */
    private val ignoredRunnable = Runnable {
        val n = Repo.get(this, noteId)
        if (n != null) {
            n.alarmAt = System.currentTimeMillis() + retryMs()
            Repo.put(this, n)
            AlarmScheduler.schedule(this, n)
        }
        stopSelf()
    }

    private fun stopRinging() {
        h.removeCallbacksAndMessages(null)
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        wake = null
        GlowOverlay.hide()
        ringingId = 0L
    }

    override fun onDestroy() {
        stopRinging()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
