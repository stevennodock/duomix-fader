// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Service de premier plan qui expose le crossfader dans une notification de type lecteur
 * multimédia : la barre de progression de la MediaSession EST le fader (position / durée
 * = balance Music -> Vidéo). Précédent / suivant déplacent le fader par pas de 10 %,
 * lecture / pause le recentre. Il garde aussi le moteur en vie, app fermée.
 *
 * DuoMix ne joue aucun son : la session ne sert que de surface de contrôle.
 */
class MixerNotificationService : Service() {

    companion object {
        private const val CHANNEL_ID = "crossfader"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.dirtwing.duomix.action.STOP"
        /** Durée fictive : 100 s, pour que « 0:50 » se lise comme 50 %. */
        private const val FADER_DURATION_MS = 100_000L
        private const val STEP = 0.1f

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MixerNotificationService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: MixerEngine
    private lateinit var session: MediaSession

    private val callback = object : MediaSession.Callback() {
        override fun onSeekTo(pos: Long) = engine.setCrossfader(pos.toFloat() / FADER_DURATION_MS)
        override fun onSkipToPrevious() = engine.setCrossfader(engine.state.value.crossfader - STEP)
        override fun onSkipToNext() = engine.setCrossfader(engine.state.value.crossfader + STEP)
        override fun onPlay() = engine.setCrossfader(0.5f)
        override fun onPause() = engine.setCrossfader(0.5f)
        override fun onCustomAction(action: String, extras: Bundle?) {
            if (action == ACTION_STOP) stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = MixerEngine.get(this).also { it.acquire() }
        session = MediaSession(this, "DuoMixCrossfader").apply {
            setCallback(callback)
            isActive = true
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        publish(engine.state.value)
        scope.launch {
            engine.state
                .map { Triple(it.crossfader, it.ytm.volume, it.yt.volume) }
                .distinctUntilChanged()
                .collect { publish(engine.state.value) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        engine.release()
        super.onDestroy()
    }

    /** Reflète l'état du mixeur dans la session et la notification. */
    private fun publish(state: MixerUiState) {
        val balance = getString(
            R.string.notif_balance,
            (state.ytm.volume * 100).roundToInt(),
            (state.yt.volume * 100).roundToInt(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, getString(R.string.notif_title))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, balance)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, FADER_DURATION_MS)
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        ACTION_STOP,
                        getString(R.string.notif_action_stop),
                        R.drawable.ic_stat_close,
                    ).build()
                )
                // Vitesse 0 : la barre ne bouge que lorsqu'on la déplace
                .setState(
                    PlaybackState.STATE_PLAYING,
                    (state.crossfader * FADER_DURATION_MS).toLong(),
                    0f,
                )
                .build()
        )
        startForeground(
            NOTIFICATION_ID,
            buildNotification(balance),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun buildNotification(balance: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mixer)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(balance)
            .setContentIntent(openApp)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .build()
    }
}
