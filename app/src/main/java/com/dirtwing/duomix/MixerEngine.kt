// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomix

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import com.dirtwing.duomix.shizuku.MixerUserService
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import rikka.shizuku.Shizuku
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Paquets cibles du mixeur. */
object Targets {
    const val YT = "com.google.android.youtube"
    const val YTM = "com.google.android.apps.youtube.music"
}

/** État d'un canal du mixeur (une app = un canal). */
data class Channel(
    val pkg: String,
    val label: String,
    val volume: Float = 1f,
    val focusIgnored: Boolean = false,
    val playing: Boolean = false,
    val piids: List<Int> = emptyList(),
)

data class MixerUiState(
    val shizukuAvailable: Boolean = false,
    val shizukuGranted: Boolean = false,
    val serviceBound: Boolean = false,
    val ytm: Channel = Channel(Targets.YTM, "YouTube Music"),
    val yt: Channel = Channel(Targets.YT, "YouTube"),
    val crossfader: Float = 0.5f,
    val lastError: String? = null,
)

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

    private val _state = MutableStateFlow(MixerUiState())
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
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _state.update { it.copy(serviceBound = false) }
            pollJob?.cancel()
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
    // Cycle de vie partagé
    // ------------------------------------------------------------------

    @Synchronized
    fun acquire() {
        if (users++ > 0) return
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshShizukuState()
    }

    @Synchronized
    fun release() {
        if (--users > 0) return
        users = 0
        pollJob?.cancel()
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
            val ytmIgnored = ignored(Targets.YTM)
            val ytIgnored = ignored(Targets.YT)
            _state.update {
                it.copy(
                    ytm = it.ytm.copy(focusIgnored = ytmIgnored),
                    yt = it.yt.copy(focusIgnored = ytIgnored),
                )
            }
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
                ytm = it.ytm.copy(
                    piids = piidsByPkg[Targets.YTM].orEmpty(),
                    playing = Targets.YTM in playingPkgs,
                ),
                yt = it.yt.copy(
                    piids = piidsByPkg[Targets.YT].orEmpty(),
                    playing = Targets.YT in playingPkgs,
                ),
            )
        }
        // Ré-application aux nouveaux lecteurs (un flux redémarré repart à plein volume)
        applyChannel(updated.ytm)
        applyChannel(updated.yt)
        synchronized(applied) { applied.keys.retainAll((updated.ytm.piids + updated.yt.piids).toSet()) }
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
    fun setChannelVolume(pkg: String, volume: Float) {
        val updated = _state.updateAndGet {
            when (pkg) {
                Targets.YTM -> it.copy(ytm = it.ytm.copy(volume = volume))
                Targets.YT -> it.copy(yt = it.yt.copy(volume = volume))
                else -> it
            }
        }
        scope.launch { applyChannel(if (pkg == Targets.YTM) updated.ytm else updated.yt) }
    }

    /**
     * Crossfader à puissance constante (loi équi-énergie type table DJ) :
     * x = 0 -> 100% YouTube Music, x = 1 -> 100% YouTube.
     */
    fun setCrossfader(position: Float) {
        val x = position.coerceIn(0f, 1f)
        val ytmVol = cos(x * PI.toFloat() / 2f)
        val ytVol = sin(x * PI.toFloat() / 2f)
        val updated = _state.updateAndGet {
            it.copy(
                crossfader = x,
                ytm = it.ytm.copy(volume = ytmVol),
                yt = it.yt.copy(volume = ytVol),
            )
        }
        scope.launch {
            applyChannel(updated.ytm)
            applyChannel(updated.yt)
        }
    }
}
