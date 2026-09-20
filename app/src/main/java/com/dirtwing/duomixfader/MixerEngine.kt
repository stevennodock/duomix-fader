// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.Manifest
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
import com.dirtwing.duomixfader.harmony.ScopeFrame
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
    /** Ce que le shell peut faire sur cet appareil (voir ShellCapabilities) ; tout, tant qu'on ne sait pas. */
    val capabilities: Int = ShellCapabilities.ALL,
    /** Réglage de tonalité de l'écoute par le micro, en décibels (voir ToneFilter). */
    val micBassDb: Float = 0f,
    val micTrebleDb: Float = 0f,
    /** Amplification de l'écoute par le micro, en décibels. */
    val micGainDb: Float = 0f,
) {
    val canSetFocus: Boolean get() = capabilities and ShellCapabilities.FOCUS != 0
    val canControlPlayers: Boolean get() = capabilities and ShellCapabilities.PLAYERS != 0
    val canCapture: Boolean get() = capabilities and ShellCapabilities.CAPTURE != 0

    /**
     * Mode coupure : sans accès au volume des lecteurs, mais avec le réglage des appops, on
     * peut encore couper ou rétablir le son d'une app. Le fader devient une bascule — musique
     * seule, les deux, vidéo seule. Jamais actif là où le volume par lecteur est disponible.
     */
    val cutMode: Boolean get() = !canControlPlayers && canSetFocus

    /** En mode coupure, un canal se tait sous ce niveau. */
    fun isCut(channel: Channel): Boolean = cutMode && channel.volume < CUT_THRESHOLD

    companion object {
        /** cos / sin du crossfader passent sous 0,4 au-delà des trois quarts de la course. */
        const val CUT_THRESHOLD = 0.4f
    }

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
        /** Préférence : paquets dont le son est coupé par le mode coupure. */
        private const val PREF_CUT = "cut_packages"
        /** Préférence : l'utilisateur a choisi l'analyse harmonique par le micro. */
        private const val PREF_MIC = "harmony_microphone"
        private const val PREF_MIC_BASS = "harmony_microphone_bass_db"
        private const val PREF_MIC_TREBLE = "harmony_microphone_treble_db"
        private const val PREF_MIC_GAIN = "harmony_microphone_gain_db"

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
        MixerUiState(
            music = savedChannel(Slot.MUSIC), video = savedChannel(Slot.VIDEO),
            micBassDb = prefs.getFloat(PREF_MIC_BASS, 0f), micTrebleDb = prefs.getFloat(PREF_MIC_TREBLE, 0f),
            micGainDb = prefs.getFloat(PREF_MIC_GAIN, 0f),
        )
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
                // Un service shell d'une version antérieure ne connaît pas la question : on ne bride rien
                val capabilities = runCatching { service?.capabilities() }.getOrNull() ?: ShellCapabilities.ALL
                _state.update { it.copy(serviceBound = true, lastError = null, capabilities = capabilities) }
                refreshFocusStates()
                // Mode coupure : rien ne doit rester muet d'une session précédente (liste vide ailleurs)
                scope.launch { uncutLeftovers() }
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

    /**
     * Écoute par le micro : jamais d'office. Il faut que l'utilisateur l'ait choisie ET que la
     * permission RECORD_AUDIO soit accordée. Ne sert que là où la capture directe est impossible.
     */
    private val microphoneAllowed: Boolean
        get() = prefs.getBoolean(PREF_MIC, false) &&
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** L'utilisateur vient d'accorder le micro depuis le bloc Harmonie. */
    fun enableMicrophoneHarmony() {
        prefs.edit().putBoolean(PREF_MIC, true).apply()
        startHarmony()
    }

    /** Oscilloscope de l'écoute par le micro : une image toutes les 32 ms, null quand le micro dort. */
    private val _scope = MutableStateFlow<ScopeFrame?>(null)
    val micScope: StateFlow<ScopeFrame?> = _scope.asStateFlow()

    /** Capture micro en cours, pour lui passer les réglages de tonalité à la volée. */
    @Volatile private var activeMic: MicCapture? = null

    /** Curseurs graves / aigus de l'écoute par le micro ; retenus d'une session à l'autre. */
    fun setMicrophoneTone(bassDb: Float, trebleDb: Float) {
        _state.update { it.copy(micBassDb = bassDb, micTrebleDb = trebleDb) }
        prefs.edit().putFloat(PREF_MIC_BASS, bassDb).putFloat(PREF_MIC_TREBLE, trebleDb).apply()
        activeMic?.tone?.set(bassDb, trebleDb)
    }

    /** Potentiomètre de gain de l'écoute par le micro ; retenu d'une session à l'autre. */
    fun setMicrophoneGain(gainDb: Float) {
        _state.update { it.copy(micGainDb = gainDb) }
        prefs.edit().putFloat(PREF_MIC_GAIN, gainDb).apply()
        activeMic?.gainDb = gainDb
    }

    fun disableMicrophoneHarmony() {
        prefs.edit().putBoolean(PREF_MIC, false).apply()
        startHarmony()
    }

    /** (Re)lance l'analyse sur l'app du canal musique : capture côté shell, décision ici. */
    private fun startHarmony() {
        harmonyJob?.cancel()
        harmonyJob = scope.launch {
            val svc = service ?: return@launch
            // Cet appareil ne laisse pas le shell capter le son : on écoute par le micro si
            // l'utilisateur l'a choisi, sinon la fonction est éteinte, et dite comme telle
            // Le micro est aussi un choix là où la capture directe marche : un instrument, une
            // chaîne hi-fi dans la pièce. Sans ce choix, rien ne change : capture directe.
            val mic = when {
                microphoneAllowed -> MicCapture(appContext) { frame -> _scope.value = frame }.also { it.tone.set(_state.value.micBassDb, _state.value.micTrebleDb); it.gainDb = _state.value.micGainDb; activeMic = it }
                _state.value.canCapture -> null
                else -> {
                    _harmony.value = HarmonyState(supported = false)
                    return@launch
                }
            }
            // Une seule source à la fois : la capture directe s'arrête quand le micro prend le relais
            if (mic != null) runCatching { svc.stopHarmony() }
            val pkg = _state.value.music.pkg
            synchronized(detector) { detector.reset() }
            val listening = if (mic != null) mic.start() else runCatching { svc.startHarmony(pkg) }.getOrDefault(false)
            _harmony.value = synchronized(detector) { detector.snapshot(listening) }.copy(viaMicrophone = mic != null)
            if (!listening) return@launch
            try {
                harmonyLoop(svc, pkg, mic)
            } finally {
                mic?.stop()
                if (activeMic === mic) activeMic = null
                if (mic != null) _scope.value = null
            }
        }
    }

    /** Relevé de l'analyse, une fois par seconde, quelle que soit la source du son. */
    private suspend fun harmonyLoop(svc: IMixerService, pkg: String, mic: MicCapture?) {
        kotlinx.coroutines.coroutineScope {
            var tick = 0
            while (isActive) {
                delay(1_000)
                val data = (if (mic != null) mic.analyzer.drain() else runCatching { svc.readHarmony() }.getOrNull()) ?: break
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
                    detector.snapshot(true).copy(track = currentTrack, viaMicrophone = mic != null)
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
        applyCut(channel.pkg, muted = false)   // sans effet hors mode coupure : rien n'y est jamais coupé
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

    /** Mode coupure : paquets dont on a coupé le son, mémorisés pour ne pousser que les changements. */
    private val cut = mutableSetOf<String>()

    /**
     * Coupe ou rétablit le son d'une app (mode coupure). Les paquets coupés sont aussi notés
     * dans les préférences : un réglage appops survit à tout, et si l'app mourait sans que le
     * service shell ait pu rétablir le son, la prochaine connexion le ferait (voir [uncutLeftovers]).
     */
    private fun applyCut(pkg: String, muted: Boolean) {
        val svc = service ?: return
        if (synchronized(cut) { (pkg in cut) == muted }) return
        if (!runCatching { svc.setMuted(pkg, muted) }.getOrDefault(false)) return
        synchronized(cut) {
            if (muted) cut.add(pkg) else cut.remove(pkg)
            prefs.edit().putStringSet(PREF_CUT, cut.toSet()).apply()
        }
    }

    /** À la connexion : rend le son à toute app restée coupée lors d'une session précédente. */
    private fun uncutLeftovers() {
        val svc = service ?: return
        for (pkg in prefs.getStringSet(PREF_CUT, emptySet()).orEmpty()) runCatching { svc.setMuted(pkg, false) }
        synchronized(cut) { cut.clear() }
        prefs.edit().remove(PREF_CUT).apply()
    }

    private fun applyChannel(channel: Channel) {
        val svc = service ?: return
        val state = _state.value
        if (state.cutMode) {
            applyCut(channel.pkg, state.isCut(channel))
            return
        }
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
