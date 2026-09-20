// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.IBinder
import com.dirtwing.duomixfader.harmony.Detection
import com.dirtwing.duomixfader.harmony.romanNumeral
import com.dirtwing.duomixfader.ui.ScaleArt
import com.dirtwing.duomixfader.widget.HistoryWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
        private const val ACTION_STOP = "com.dirtwing.duomixfader.action.STOP"
        private const val ACTION_HARMONY = "com.dirtwing.duomixfader.action.HARMONY"
        /** Durée fictive : 100 s, pour que « 0:50 » se lise comme 50 %. */
        private const val FADER_DURATION_MS = 100_000L
        private const val STEP = 0.1f
        private const val PROGRESSION_HOLD_MS = 4_000L
        private const val WIDGET_HOLD_MS = 2_000L

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
        // Pas de lecture/pause : ce gros bouton, qui recentrait le fader, prenait la moitié de
        // la largeur de la carte et tronquait les deux lignes de texte. On recentre à la barre.
        override fun onCustomAction(action: String, extras: Bundle?) {
            when (action) {
                ACTION_STOP -> stopSelf()
                ACTION_HARMONY -> openHarmonyPanel()
            }
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
                .map { listOf(it.crossfader, it.music.volume, it.video.volume, it.music.pkg, it.video.pkg, it.capabilities) }
                .distinctUntilChanged()
                .collect { publish(engine.state.value) }
        }
        scope.launch {
            // Rien ne tourne ni ne clignote sur la carte : elle n'est republiée que lorsque
            // quelque chose change vraiment (gamme retenue, morceau, balance).
            engine.harmony.map { Triple(it.current, it.track, it.viaMicrophone) }.distinctUntilChanged().collect { (detection, track, microphone) ->
                viaMicrophone = microphone
                scaleName = detection?.let {
                    getString(R.string.notif_scale, noteName(it.root), it.scale.popularName, it.scale.family)
                }
                pastilles = detection?.let { ScaleArt.pastilles(it) }
                artist = track?.artist
                trackTitle = track?.title
                publish(engine.state.value)
            }
        }
        watchProgression()
        watchHistory()
    }

    /**
     * La grille d'accords bouge plus souvent que la gamme, surtout sans cycle établi : on ne
     * l'affiche qu'une fois tenue PROGRESSION_HOLD_MS, pour que la carte ne s'agite pas.
     */
    private fun watchProgression() = scope.launch {
        engine.harmony.map { state ->
            val tonic = state.current?.root
            val found = state.progression?.takeIf { it.chords.isNotEmpty() }
            if (tonic == null || found == null) null
            else {
                val numerals = found.chords.joinToString(" – ") { romanNumeral(it.chord, tonic) }
                // Une boucle se termine par sa durée ; sans boucle, « … » : ce sont les derniers accords entendus
                found.cycleMs?.let { "$numerals    ${(it + 500) / 1000} s" } ?: "… $numerals"
            }
        }.distinctUntilChanged().collectLatest { text ->
            if (text != null) delay(PROGRESSION_HOLD_MS)
            progression = text
            publish(engine.state.value)
        }
    }

    /**
     * Tient le widget « Historique » à jour : morceau en cours et morceaux archivés. La
     * confiance, qui bouge à chaque seconde, n'y figure pas : on l'ignore pour ne redessiner
     * le widget que lorsque son contenu change, et au plus toutes les deux secondes.
     */
    private fun watchHistory() = scope.launch {
        combine(engine.liveRecord, engine.history) { live, history -> live?.copy(confidence = 0f) to history }
            .distinctUntilChanged()
            .collectLatest { (live, history) ->
                delay(WIDGET_HOLD_MS)
                HistoryWidget.push(this@MixerNotificationService, live, history)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        // Plus de morceau « en cours » : le widget ne garde que l'historique
        HistoryWidget.push(this, null, engine.history.value)
        session.isActive = false
        session.release()
        engine.release()
        super.onDestroy()
    }

    // Une carte multimédia n'a que deux lignes de texte, imposées par le système (taille,
    // place, aucun défilement). Elles vont à l'essentiel pour l'accompagnateur :
    //  1. le nom du mode : « A Major · F1 » ;
    //  2. la tonalité et les pavés de couleur de la gamme.
    // Le secondaire — les flux et leur balance, l'artiste, le titre — est dessiné en petit dans
    // l'illustration (voir ScaleArt), que le système assombrit.

    /** Première ligne : le nom du mode ; sinon le nom du fader. */
    private var scaleName: String? = null

    /**
     * Seconde ligne : pavés colorés (voir ScaleArt.pastilles). Les métadonnées du lecteur ne
     * portent que du texte brut ; mais quand elles ne donnent pas d'« artiste », la carte
     * reprend le texte de la notification, qui, lui, conserve ses couleurs. On laisse donc ce
     * champ vide.
     */
    private var pastilles: CharSequence? = null

    /** L'analyse écoute le micro : le service doit alors porter le type « microphone ». */
    private var viaMicrophone = false

    private var artist: String? = null
    private var trackTitle: String? = null

    /** Grille d'accords en chiffres romains, dessinée sous les pavés (voir ScaleArt.artwork). */
    private var progression: String? = null

    /** Illustration, redessinée seulement quand son contenu change. */
    private var artwork: Bitmap? = null
    private var artworkKey: String? = null

    private fun noteName(root: Int): String = resources.getStringArray(R.array.notes_primary)[root]

    /**
     * Ouvre le panneau des gammes, par le service shell : lui seul peut refermer le volet de
     * notifications, sans quoi le panneau s'ouvre derrière lui. Sans service shell, on tente
     * un lancement direct (le volet restera ouvert, et Android peut le refuser).
     */
    private fun openHarmonyPanel() {
        if (engine.showHarmonyPanel()) return
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_HARMONY, true)
            )
        }
    }

    /** Reflète l'état du mixeur dans la session et la notification. */
    private fun publish(state: MixerUiState) {
        // Les deux flux et leur balance, en abrégé : dessinés dans l'illustration
        fun short(channel: Channel) = AppCatalog.find(channel.pkg)?.shortLabel ?: channel.label
        val balance = "${short(state.music)} ${(state.music.volume * 100).roundToInt()} · " +
            "${short(state.video)} ${(state.video.volume * 100).roundToInt()}"
        val title = scaleName ?: getString(R.string.notif_title)
        val key = "$balance|$artist|$trackTitle|$progression"
        if (key != artworkKey) {
            artworkKey = key
            artwork = ScaleArt.artwork(balance, artist, trackTitle, progression)
        }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                // Pas d'« artiste » quand on a des pavés : la carte prendra le texte coloré de
                // la notification (voir buildNotification)
                .apply { if (pastilles == null) putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.notif_title)) }
                .putLong(MediaMetadata.METADATA_KEY_DURATION, FADER_DURATION_MS)
                .apply { artwork?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SKIP_TO_NEXT
                )
                .apply {
                    // Pas de bouton ♪ là où l'appareil ne permet pas l'analyse harmonique
                    if (state.canCapture || viaMicrophone) addCustomAction(
                        PlaybackState.CustomAction.Builder(
                            ACTION_HARMONY,
                            getString(R.string.notif_action_harmony),
                            R.drawable.ic_stat_note,
                        ).build()
                    )
                }
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
        val notification = buildNotification(title)
        // Le type « microphone » seulement pendant l'écoute par le micro : sans lui, Android
        // coupe le micro dès que l'app quitte l'écran. Android ne l'accorde qu'à une app visible
        // à cet instant ; s'il le refuse, le fader garde son type habituel plutôt que de tomber.
        val withMicrophone = viaMicrophone && runCatching {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }.isSuccess
        if (!withMicrophone) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun buildNotification(title: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mixer)
            .setContentTitle(title)
            .setContentText(pastilles ?: getString(R.string.notif_title))
            .setContentIntent(openApp)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .build()
    }
}
