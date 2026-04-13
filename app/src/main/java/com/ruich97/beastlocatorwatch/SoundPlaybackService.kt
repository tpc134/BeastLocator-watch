package com.ruich97.beastlocatorwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class SoundPlaybackService : Service() {
    private var player: MediaPlayer? = null
    private var currentPriority: Int = 0

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rawResId = intent?.getIntExtra(EXTRA_RAW_RES_ID, 0) ?: 0
        val priority = intent?.getIntExtra(EXTRA_PRIORITY, 0) ?: 0
        if (rawResId == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        val foregroundStarted = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification())
        }.isSuccess
        if (!foregroundStarted) {
            stopSelf()
            return START_NOT_STICKY
        }
        val isPlayingNow = player != null
        if (isPlayingNow && priority <= currentPriority) {
            return START_NOT_STICKY
        }
        playRaw(rawResId, priority)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayer()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun playRaw(rawResId: Int, priority: Int) {
        stopPlayer()
        val created = MediaPlayer.create(this, rawResId) ?: run {
            stopSelf()
            return
        }
        currentPriority = priority
        player = created.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnCompletionListener {
                stopPlayer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            start()
        }
    }

    private fun stopPlayer() {
        player?.runCatching {
            if (isPlaying) stop()
        }
        player?.release()
        player = null
        currentPriority = 0
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_arrow)
            .setContentTitle(getString(R.string.sound_playback_notification_title))
            .setContentText(getString(R.string.sound_playback_notification_body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sound_playback_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "sound_playback_channel"
        private const val NOTIFICATION_ID = 1515
        private const val EXTRA_RAW_RES_ID = "extra_raw_res_id"
        private const val EXTRA_PRIORITY = "extra_priority"

        fun start(context: Context, rawResId: Int, priority: Int) {
            if (rawResId == 0) return
            val intent = Intent(context, SoundPlaybackService::class.java).apply {
                putExtra(EXTRA_RAW_RES_ID, rawResId)
                putExtra(EXTRA_PRIORITY, priority)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
