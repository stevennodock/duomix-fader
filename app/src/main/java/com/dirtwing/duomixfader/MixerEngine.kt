// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.dirtwing.duomixfader.harmony.HarmonyDetector
import com.dirtwing.duomixfader.harmony.HarmonyHistory
import com.dirtwing.duomixfader.harmony.TrackInfo
import com.dirtwing.duomixfader.harmony.TrackRecord
import com.dirtwing.duomixfader.harmony.HarmonyState
import com.dirtwing.duomixfader.harmony.NoteNames
import com.dirtwing.duomixfader.harmony.romanNumeral
import com.dirtwing.duomixfader.shizuku.MixerUserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import rikka.shizuku.Shizuku
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** État d'un canal du mixeur (une app = un canal). */
data class Channel(
    val pkg: String,
    val label: String,
    val volume: Float = 1f,
    val focusIgnored: Boolean = false,
    val playing: Boolean = false,
    val piids: List<Int> = emptyList(),
    /** Faux : l'app se met en pause si on lui refuse le focus (voir AppTarget). */
    val toleratesFocusDenial: Boolean = true,
)

data class MixerUiState(
    val shizukuAvailable: Boolean = false,
    val shizukuGranted: Boolean = false,
    val serviceBound: Boolean = false,
    val music: Channel,
    val video: Channel,
    /** Apps du catalogue réellement installées, proposées dans les sélecteurs. */
    val installedMusic: List<AppTarget> = emptyList(),
    val installedVideo: List<AppTarget> = emptyList(),
    val crossfader: Float = 0.5f,
    val lastError: String? = null,
) {
    fun channel(slot: Slot): Channel = if (slot == Slot.MUSIC) music else video

    fun withChannel(slot: Slot, channel: Channel): MixerUiState =
        if (slot == Slot.MUSIC) copy(music = channel) else copy(video = channel)
}

/**
 * Moteur du mixeur, unique par processus : connexion Shizuku, bascule de l'audio focus
 * via appops, polling des flux actifs et application des volumes.
 *
 * Partagé entre l'écran (MixerViewModel) et la notification (MixerNotificationService) :
 * chacun l'acquiert tant qu'il vit ; au dernier relâchement le service shell est libéré.
 */
class MixerEngine private constructor(private val appContext: Context) {

    companion object {
        const val SHIZUKU_PERMISSION_CODE = 4242
        /** PLAYER_STATE_STARTED dans AudioPlaybackConfiguration. */
        private const val PLAYER_STATE_STARTED = 2

        @Volatile
        private var instance: MixerEngine? = null

        fun get(context: Context): MixerEngine =
            instance ?: synchronized(this) {
                instance ?: MixerEngine(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences("mixer", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        MixerUiState(music = savedChannel(Slot.MUSIC), video = savedChannel(Slot.VIDEO))
    )
    val state: StateFlow<MixerUiState> = _state.asStateFlow()

    private var users = 0
    private var service: IMixerService? = null
    /** Jeton dont la mort (= mort de ce processus) fait s'arrêter le service shell. */
    private val clientToken = Binder()
    private var pollJob: Job? = null
    /** Volumes déjà appliqués (piid -> volume) pour ne pousser que les changements. */
    private val applied = mutableMapOf<Int, Float>()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, MixerUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("mixer")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IMixerService.Stub.asInterface(binder)
                runCatching { service?.attachClient(clientToken) }
                _state.update { it.copy(serviceBound = true, lastError = null) }
                refreshFocusStates()
                startPolling()
                startHarmony()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _state.update { it.copy(serviceBound = false) }
            pollJob?.cancel()
            harmonyJob?.cancel()
            _harmony.value = HarmonyState()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshShizukuState() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        _state.update { it.copy(shizukuAvailable = false, serviceBound = false) }
    }
    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == SHIZUKU_PERMISSION_CODE) {
                refreshShizukuState()
                if (result == PackageManager.PERMISSION_GRANTED) bindService()
            }
        }

    // ------------------------------------------------------------------
    // Analyse harmonique du canal musique
    // ------------------------------------------------------------------

    private val detector = HarmonyDetector()
    private val _harmony = MutableStateFlow(HarmonyState())
    val harmony: StateFlow<HarmonyState> = _harmony.asStateFlow()
    private var harmonyJob: Job? = null

    private val historyStore = HarmonyHistory(File(appContext.filesDir, "harmony_history.json"))
    /** Morceaux déjà écoutés, du plus récent au plus ancien ; le morceau en cours n'y est pas. */
    private val _history = MutableStateFlow(historyStore.load())
    val history: StateFlow<List<TrackRecord>> = _history.asStateFlow()
    /** Section du morceau en cours, tenue à jour à chaque relevé. */
    private val _liveRecord = MutableStateFlow<TrackRecord?>(null)
    val liveRecord: StateFlow<TrackRecord?> = _liveRecord.asStateFlow()
    private var currentTrack: TrackInfo? = null

    private fun readNowPlaying(svc: IMixerService, pkg: String): TrackInfo? =
        runCatching { svc.nowPlaying(pkg) }.getOrNull()?.let { json ->
            runCatching {
                val o = JSONObject(json)
                TrackInfo(o.optString("title"), o.optString("artist"), o.optString("album"))
            }.getOrNull()
        }?.takeIf { it.isKnown }

    /** Range la section du morceau en cours dans l'historique, si elle contient quelque chose. */
    private fun archiveLiveRecord() {
        val record = _liveRecord.value
        _liveRecord.value = null
        if (record == null || !record.isWorthKeeping) return
        _history.update { (listOf(record) + it).take(HarmonyHistory.MAX_TRACKS) }
        historyStore.save(_history.value)
    }

    fun clearHistory() {
        _history.value = emptyList()
        historyStore.save(emptyList())
    }

    /** (Re)lance l'analyse sur l'app du canal musique : capture côté shell, décision ici. */
    private fun startHarmony() {
        harmonyJob?.cancel()
        harmonyJob = scope.launch {
            val svc = service ?: return@launch
            val pkg = _state.value.music.pkg
            synchronized(detector) { detector.reset() }
            val listening = runCatching { svc.startHarmony(pkg) }.getOrDefault(false)
            _harmony.value = synchronized(detector) { detector.snapshot(listening) }
            if (!listening) return@launch
            var tick = 0
            while (isActive) {
                delay(1_000)
                val data = runCatching { svc.readHarmony() }.getOrNull() ?: break
                // Le titre annoncé par l'app donne les vraies frontières entre morceaux
                if (tick++ % 2 == 0) {
                    val track = readNowPlaying(svc, pkg)
                    if (track != currentTrack) {
                        archiveLiveRecord()
                        currentTrack = track
                        synchronized(detector) { detector.reset() }
                    }
                }
                val hadDetection = _harmony.value.current != null
                _harmony.value = synchronized(detector) {
                    detector.update(data, SystemClock.elapsedRealtime())
                    detector.snapshot(true).copy(track = currentTrack)
                }
                val now = _harmony.value
                when {
                    now.current != null -> _liveRecord.value = TrackRecord(
                        track = currentTrack ?: TrackInfo("", ""),
                        source = _state.value.music.label,
                        startedAt = _liveRecord.value?.startedAt ?: System.currentTimeMillis(),
                        detection = now.current,
                        confidence = now.confidence,
                        segments = now.segments,
                        progression = now.progression,
                    )
                    // Le détecteur vient de repartir de zéro sur un long silence : sans titre
                    // annoncé, c'est notre seule frontière entre deux morceaux. Avec un titre,
                    // c'est une pause DANS le morceau : sa section reste ouverte.
                    hadDetection && currentTrack == null -> archiveLiveRecord()
                }
                if (BuildConfig.DEBUG) {
                    val h = _harmony.value
                    Log.d("DuoMixHarmony", "[${currentTrack?.let { "${it.artist} — ${it.title}" } ?: "morceau non annoncé"}] trames=${data[24].toInt()} silence=${data[25].toInt()} -> " +
                        (h.current?.let { "${NoteNames.letter(it.root)} ${it.scale.popularName} (famille ${it.scale.family})" } ?: "en écoute") +
                        " confiance=${"%.2f".format(h.confidence)} séquences=${h.segments.size}" +
                        (h.progression?.let { p -> " | " + p.chords.joinToString("-") { romanNumeral(it.chord, h.current?.root ?: 0) } + " cycle=${p.cycleMs}" } ?: ""))
                }
            }
            archiveLiveRecord()
            _harmony.value = HarmonyState()
        }
    }

    /**
     * Ouvre le panneau Harmonie depuis la notification : le service shell referme le volet
     * et lance l'écran. Renvoie faux s'il n'est pas joignable (à l'appelant de se débrouiller).
     */
    fun showHarmonyPanel(): Boolean {
        val svc = service ?: return false
        scope.launch { runCatching { svc.showHarmonyPanel() } }
        return true
    }

    /** Bouton « rafraîchir » : oublie tout et ré-analyse le morceau en cours. */
    fun refreshHarmony() {
        _harmony.value = synchronized(detector) {
            detector.reset()
            detector.snapshot(_harmony.value.listening)
        }
        if (!_harmony.value.listening) startHarmony()
    }

    // ------------------------------------------------------------------
    // Cycle de vie partagé
    // ------------------------------------------------------------------

    @Synchronized
    fun acquire() {
        if (users++ > 0) return
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshInstalledApps()
        refreshShizukuState()
    }

    @Synchronized
    fun release() {
        if (--users > 0) return
        users = 0
        pollJob?.cancel()
        harmonyJob?.cancel()
        archiveLiveRecord()
        runCatching { service?.stopHarmony() }
        _harmony.value = HarmonyState()
        // Plus d'écran ni de notification pour régler le mixage : on ne laisse pas
        // les apps à un volume atténué que l'utilisateur ne pourrait plus corriger
        _state.value.let { restoreFullVolume(it.music); restoreFullVolume(it.video) }
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        service = null
        applied.clear()
        _state.update { it.copy(serviceBound = false) }
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    // ------------------------------------------------------------------
    // Shizuku : état, permission, service
    // ------------------------------------------------------------------

    fun refreshShizukuState() {
        val available = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = available && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        _state.update { it.copy(shizukuAvailable = available, shizukuGranted = granted) }
        if (granted && service == null) bindService()
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE) }
            .onFailure { e -> _state.update { it.copy(lastError = e.message) } }
    }

    private fun bindService() {
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
            .onFailure { e -> _state.update { it.copy(lastError = e.message) } }
    }

    // ------------------------------------------------------------------
    // Audio focus : le hack appops
    // ------------------------------------------------------------------

    /** Active/désactive l'ignorance de l'audio focus pour un paquet. */
    fun setFocusIgnored(pkg: String, ignored: Boolean) {
        scope.launch {
            val svc = service ?: return@launch
            val ok = runCatching { svc.setFocusIgnored(pkg, ignored) }.getOrDefault(false)
            if (!ok) {
                val message = appContext.getString(R.string.error_appops_denied, pkg)
                _state.update { it.copy(lastError = message) }
            }
            refreshFocusStates()
        }
    }

    private fun refreshFocusStates() {
        scope.launch {
            val svc = service ?: return@launch
            fun ignored(pkg: String): Boolean =
                runCatching { svc.isFocusIgnored(pkg) }.getOrDefault(false)
            val current = _state.value
            // Une app qui ne tolère pas le refus de focus ne doit jamais rester en « ignore »
            fun ignoredOrReset(channel: Channel): Boolean {
                val isIgnored = ignored(channel.pkg)
                if (!isIgnored || channel.toleratesFocusDenial) return isIgnored
                runCatching { svc.setFocusIgnored(channel.pkg, false) }
                return false
            }
            val musicIgnored = ignoredOrReset(current.music)
            val videoIgnored = ignoredOrReset(current.video)
            _state.update {
                // L'app d'un canal a pu changer pendant l'appel : on ne marque que la bonne
                it.copy(
                    music = if (it.music.pkg == current.music.pkg) it.music.copy(focusIgnored = musicIgnored) else it.music,
                    video = if (it.video.pkg == current.video.pkg) it.video.copy(focusIgnored = videoIgnored) else it.video,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Choix de l'app de chaque canal (liste fermée : AppCatalog)
    // ------------------------------------------------------------------

    private fun prefKey(slot: Slot) = "app_${slot.name.lowercase()}"

    private fun channelFor(app: AppTarget, volume: Float) =
        Channel(app.pkg, app.label, volume = volume, toleratesFocusDenial = app.toleratesFocusDenial)

    /** Canal initial : dernière app choisie, volume cohérent avec le fader au centre. */
    private fun savedChannel(slot: Slot): Channel {
        val apps = AppCatalog.apps(slot)
        val app = apps.firstOrNull { it.pkg == prefs.getString(prefKey(slot), null) } ?: apps.first()
        return channelFor(app, volume = cos(PI.toFloat() / 4f))
    }

    /** Recense les apps du catalogue installées (déclarées dans <queries> du manifest). */
    fun refreshInstalledApps() {
        fun installed(slot: Slot) = AppCatalog.apps(slot).filter { app ->
            runCatching { appContext.packageManager.getPackageInfo(app.pkg, 0) }.isSuccess
        }
        val music = installed(Slot.MUSIC)
        val video = installed(Slot.VIDEO)
        _state.update { it.copy(installedMusic = music, installedVideo = video) }
    }

    /**
     * Affecte une app du catalogue à un canal ; l'app quittée retrouve un audio focus normal.
     *
     * Une même app ne peut pas occuper les deux canaux (ses flux ne se partagent pas) : si
     * l'autre canal la tient, il la cède dans la même opération et prend l'app qu'on quitte
     * ici si elle lui est permise — un échange —, sinon la première autre app installée.
     */
    fun selectApp(slot: Slot, pkg: String) {
        val app = AppCatalog.apps(slot).firstOrNull { it.pkg == pkg } ?: return
        val before = _state.value
        val previous = before.channel(slot)
        if (previous.pkg == app.pkg) return
        val otherSlot = if (slot == Slot.MUSIC) Slot.VIDEO else Slot.MUSIC
        val otherPrevious = before.channel(otherSlot)
        val replacement = if (otherPrevious.pkg != app.pkg) null else {
            val candidates = if (otherSlot == Slot.MUSIC) before.installedMusic else before.installedVideo
            candidates.firstOrNull { it.pkg == previous.pkg }
                ?: candidates.firstOrNull { it.pkg != app.pkg }
                ?: return
        }
        val editor = prefs.edit().putString(prefKey(slot), app.pkg)
        replacement?.let { editor.putString(prefKey(otherSlot), it.pkg) }
        editor.apply()
        _state.update { state ->
            val moved = state.withChannel(slot, channelFor(app, volume = state.channel(slot).volume))
            if (replacement == null) moved
            else moved.withChannel(otherSlot, channelFor(replacement, volume = state.channel(otherSlot).volume))
        }
        scope.launch {
            // Une app qui ne fait que changer de canal reste pilotée : on ne touche ni à son
            // volume ni à son focus, le mixeur les reprend au prochain tour
            val released = listOf(previous, otherPrevious.takeIf { replacement != null })
                .filterNotNull()
                .filter { it.pkg != app.pkg && it.pkg != replacement?.pkg }
            for (channel in released) {
                restoreFullVolume(channel)
                if (channel.focusIgnored) runCatching { service?.setFocusIgnored(channel.pkg, false) }
            }
            if (slot == Slot.MUSIC || replacement != null) startHarmony()
            refreshFocusStates()
        }
    }
    /** Rend leur plein volume aux lecteurs d'un canal que DuoMix cesse de piloter. */
    private fun restoreFullVolume(channel: Channel) {
        val svc = service ?: return
        for (piid in channel.piids) {
            runCatching { svc.setVolume(piid, 1f) }
            synchronized(applied) { applied.remove(piid) }
        }
    }

    // ------------------------------------------------------------------
    // Mixeur : polling des flux et application des volumes
    // ------------------------------------------------------------------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                pollOnce()
                delay(800)
            }
        }
    }

    private fun pollOnce() {
        val svc = service ?: return
        val json = runCatching { svc.listPlaybacks() }.getOrNull() ?: return
        if (json.startsWith("{")) {
            // Objet = erreur remontée par le service
            _state.update { it.copy(lastError = json) }
            return
        }
        val piidsByPkg = mutableMapOf<String, MutableList<Int>>()
        val playingPkgs = mutableSetOf<String>()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.optBoolean("controllable")) continue
                val pkg = o.optString("pkg")
                val piid = o.getInt("piid")
                piidsByPkg.getOrPut(pkg) { mutableListOf() }.add(piid)
                if (o.optInt("state") == PLAYER_STATE_STARTED) playingPkgs.add(pkg)
            }
        }
        val updated = _state.updateAndGet {
            it.copy(
                music = it.music.copy(
                    piids = piidsByPkg[it.music.pkg].orEmpty(),
                    playing = it.music.pkg in playingPkgs,
                ),
                video = it.video.copy(
                    piids = piidsByPkg[it.video.pkg].orEmpty(),
                    playing = it.video.pkg in playingPkgs,
                ),
            )
        }
        // Ré-application aux nouveaux lecteurs (un flux redémarré repart à plein volume)
        applyChannel(updated.music)
        applyChannel(updated.video)
        synchronized(applied) { applied.keys.retainAll((updated.music.piids + updated.video.piids).toSet()) }
    }

    private fun applyChannel(channel: Channel) {
        val svc = service ?: return
        for (piid in channel.piids) {
            val current = synchronized(applied) { applied[piid] }
            if (current != channel.volume) {
                if (runCatching { svc.setVolume(piid, channel.volume) }.getOrDefault(false)) {
                    synchronized(applied) { applied[piid] = channel.volume }
                }
            }
        }
    }

    /** Slider individuel d'un canal. */
    fun setChannelVolume(slot: Slot, volume: Float) {
        val updated = _state.updateAndGet {
            it.withChannel(slot, it.channel(slot).copy(volume = volume))
        }
        scope.launch { applyChannel(updated.channel(slot)) }
    }

    /**
     * Crossfader à puissance constante (loi équi-énergie type table DJ) :
     * x = 0 -> 100% canal musique, x = 1 -> 100% canal vidéo.
     */
    fun setCrossfader(position: Float) {
        val x = position.coerceIn(0f, 1f)
        val musicVol = cos(x * PI.toFloat() / 2f)
        val videoVol = sin(x * PI.toFloat() / 2f)
        val updated = _state.updateAndGet {
            it.copy(
                crossfader = x,
                music = it.music.copy(volume = musicVol),
                video = it.video.copy(volume = videoVol),
            )
        }
        scope.launch {
            applyChannel(updated.music)
            applyChannel(updated.video)
        }
    }
}
