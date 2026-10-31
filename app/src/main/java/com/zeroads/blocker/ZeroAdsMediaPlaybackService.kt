package com.zeroads.blocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

object WebViewBridgeRelay {
    var commandListener: ((String) -> Unit)? = null

    fun sendCommand(cmd: String) {
        commandListener?.invoke(cmd)
    }
}

class ZeroAdsMediaPlaybackService : Service() {
    private var mediaSession: MediaSession? = null
    private var isPlaying = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mediaSession = MediaSession(this, "ZeroAdsMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    WebViewBridgeRelay.sendCommand("play")
                    updatePlaybackState(PlaybackState.STATE_PLAYING)
                }

                override fun onPause() {
                    WebViewBridgeRelay.sendCommand("pause")
                    updatePlaybackState(PlaybackState.STATE_PAUSED)
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "SYNC_STATE" -> {
                val state = intent.getStringExtra("state")
                if (state == "PLAY") {
                    isPlaying = true
                    updatePlaybackState(PlaybackState.STATE_PLAYING)
                    startForeground(NOTIFICATION_ID, createNotification(true))
                } else if (state == "PAUSE") {
                    isPlaying = false
                    updatePlaybackState(PlaybackState.STATE_PAUSED)
                    startForeground(NOTIFICATION_ID, createNotification(false))
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
            "ACTION_PLAY" -> {
                mediaSession?.controller?.transportControls?.play()
            }
            "ACTION_PAUSE" -> {
                mediaSession?.controller?.transportControls?.pause()
            }
        }
        return START_NOT_STICKY
    }

    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    private fun createNotification(isPlaying: Boolean): Notification {
        val playPauseAction = if (isPlaying) {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "Pause",
                PendingIntent.getService(this, 1, Intent(this, ZeroAdsMediaPlaybackService::class.java).apply { action = "ACTION_PAUSE" }, PendingIntent.FLAG_IMMUTABLE)
            ).build()
        } else {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_play),
                "Play",
                PendingIntent.getService(this, 2, Intent(this, ZeroAdsMediaPlaybackService::class.java).apply { action = "ACTION_PLAY" }, PendingIntent.FLAG_IMMUTABLE)
            ).build()
        }

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ZeroAds Tube")
            .setContentText(if (isPlaying) "Playing video" else "Paused")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(sessionActivityPendingIntent)
            .addAction(playPauseAction)
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession?.sessionToken))
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mediaSession?.isActive = false
        mediaSession?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ZeroAdsMediaChannel"
        private const val NOTIFICATION_ID = 2
    }
}
