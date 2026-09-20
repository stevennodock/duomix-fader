// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.annotation.Keep
import com.dirtwing.duomixfader.AppCatalog
import com.dirtwing.duomixfader.BuildConfig
import com.dirtwing.duomixfader.IMixerService
import com.dirtwing.duomixfader.MainActivity
import com.dirtwing.duomixfader.ShellCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * Service exécuté par Shizuku dans un processus séparé sous l'uid shell (2000).
 *
 * C'est ici que résident les deux capacités privilégiées :
 *  1. setFocusIgnored — lancer `appops set <pkg> TAKE_AUDIO_FOCUS ignore` (le hack anti-focus),
 *     uniquement pour les paquets de la liste blanche
 *  2. listPlaybacks / setVolume — mixer les flux actifs via les API cachées
 *     AudioPlaybackConfiguration.getPlayerProxy() / IPlayer.setVolume(float),
 *     accessibles depuis le shell (même principe que VolumeManager / AppMixer).
 */
@Keep
class MixerUserService() : IMixerService.Stub() {

    private companion object {
        const val FOCUS_OP = "TAKE_AUDIO_FOCUS"
        /** Liste blanche appliquée côté shell : l'appelant ne peut viser aucun autre paquet. */
        val ALLOWED_PACKAGES = AppCatalog.allowedPackages
    }

    private var context: Context? = null

    /** Cache piid -> objet lecteur (PlayerProxy ou IPlayer) rafraîchi à chaque listPlaybacks. */
    private val players = ConcurrentHashMap<Int, Any>()

    init {
        // Lève les restrictions sur les API cachées pour ce processus
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
    }

    /** Constructeur avec Context, fourni par Shizuku >= v13. */
    @Keep
    constructor(context: Context) : this() {
        this.context = context
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun attachClient(token: IBinder) {
        // Shizuku arrête normalement ce processus (daemon = false), mais s'il est lui-même
        // tué, plus personne ne le fait : on ne laisse jamais un processus shell orphelin.
        try {
            token.linkToDeath({ exitProcess(0) }, 0)
        } catch (e: RemoteException) {
            // Le client est déjà mort
            exitProcess(0)
        }
    }

    // ------------------------------------------------------------------
    // Analyse harmonique : capture du son d'une app, réduite en chroma sur place
    // ------------------------------------------------------------------

    private var capture: PlaybackCapture? = null

    @Synchronized
    override fun startHarmony(pkg: String): Boolean {
        if (pkg !in ALLOWED_PACKAGES) return false
        val ctx = context ?: return false
        stopHarmony()
        val uid = runCatching { ctx.packageManager.getPackageUid(pkg, 0) }.getOrNull() ?: return false
        // Pendant un appel binder, les contrôles de permission visent l'APPELANT (notre app,
        // sans privilège). C'est ce processus shell qui détient MODIFY_AUDIO_ROUTING : on
        // efface l'identité appelante pour agir en son nom propre.
        val token = Binder.clearCallingIdentity()
        try {
            val started = PlaybackCapture(ctx, uid)
            if (!started.start()) return false
            capture = started
            return true
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    @Synchronized
    override fun stopHarmony() {
        capture?.stop()
        capture = null
    }

    override fun readHarmony(): FloatArray? = capture?.analyzer?.drain()

    /**
     * Une app ordinaire reçoit d'Android le registre des services multimédia à son démarrage.
     * Un processus lancé par ActivityThread.systemMain(), comme le nôtre, non : sans lui,
     * MediaSessionManager échoue sur une référence nulle. On le fournit, une seule fois.
     */
    private val mediaFrameworkReady: Boolean by lazy {
        val managerClass = Class.forName("android.media.MediaServiceManager")
        // Deux registres : celui de la plate-forme (MediaSessionManager) et celui du module média
        listOf("android.media.MediaFrameworkPlatformInitializer", "android.media.MediaFrameworkInitializer").map { name ->
            runCatching {
                Class.forName(name).getMethod("setMediaServiceManager", managerClass)
                    .invoke(null, managerClass.getConstructor().newInstance())
            }.onFailure { Log.w("DuoMixHarmony", "$name non initialisé : ${it.cause ?: it}") }.isSuccess
        }.first()
    }

    /**
     * Titre, artiste et album que l'app de musique publie elle-même dans sa session
     * multimédia (ce qu'affiche l'écran verrouillé). Rien n'est deviné ni reconnu à l'écoute,
     * et rien ne sort de l'appareil. Lecture permise au shell par MEDIA_CONTENT_CONTROL.
     */
    override fun nowPlaying(pkg: String): String? {
        if (pkg !in ALLOWED_PACKAGES) return null
        val ctx = context ?: return null
        val token = Binder.clearCallingIdentity()
        return try {
            mediaFrameworkReady
            val manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val metadata = manager.getActiveSessions(null).firstOrNull { it.packageName == pkg }?.metadata
                ?: return null
            JSONObject().apply {
                put("title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
                put("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
                put("album", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
            }.toString()
        } catch (t: Throwable) {
            Log.w("DuoMixHarmony", "session multimédia illisible pour $pkg", t)
            null
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * Le bouton ♪ vit dans le volet de notifications, qu'une app n'a plus le droit de
     * refermer depuis Android 12 : sans cela le panneau s'ouvre… derrière le volet. Le shell
     * le peut, et peut aussi lancer notre écran quand une autre app est au premier plan
     * (lancement qu'Android refuserait à l'app). Deux commandes fixes, vers notre seul écran.
     */
    override fun showHarmonyPanel() {
        run("cmd", "statusbar", "collapse")
        run(
            "am", "start", "-n", "${BuildConfig.APPLICATION_ID}/.MainActivity",
            "--ez", MainActivity.EXTRA_OPEN_HARMONY, "true",
        )
    }

    /**
     * Ce que le shell peut faire ici (voir ShellCapabilities). Lecture seule : les permissions
     * détenues par com.android.shell, et la propriété par laquelle OxygenOS / ColorOS retire à
     * adb le réglage des appops. Dans le doute (contexte absent, lecture impossible), on ne
     * bride rien : l'échec éventuel se verra à l'usage, comme avant.
     */
    override fun capabilities(): Int {
        val ctx = context ?: return ShellCapabilities.ALL
        val routing = runCatching {
            ctx.packageManager.checkPermission("android.permission.MODIFY_AUDIO_ROUTING", "com.android.shell") ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(true)
        val appopsMonitored = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "persist.sys.permission.enable") == "true"
        }.getOrDefault(false)
        var capabilities = 0
        if (!appopsMonitored) capabilities = capabilities or ShellCapabilities.FOCUS
        if (routing) capabilities = capabilities or ShellCapabilities.PLAYERS or ShellCapabilities.CAPTURE
        return capabilities
    }

    override fun setFocusIgnored(pkg: String, ignored: Boolean): Boolean {
        val app = AppCatalog.find(pkg) ?: return false
        // Une app qui se met en pause quand on lui refuse le focus ne doit jamais l'ignorer
        if (ignored && !app.toleratesFocusDenial) return false
        val mode = if (ignored) "ignore" else normalFocusMode
        val out = appops("set", pkg, FOCUS_OP, mode) ?: return false
        // appops set est silencieux en cas de succès
        return out.isEmpty()
    }

    /**
     * Mode normal de TAKE_AUDIO_FOCUS sur cet appareil (`foreground` sur Android récent,
     * `allow` avant), lu sur notre propre paquet, qui n'a jamais d'entrée pour cette op.
     * Surtout pas le mot-clé `default` : c'est un mode distinct (MODE_DEFAULT) que le
     * service audio traite comme un refus — l'app resterait privée d'audio focus.
     */
    private val normalFocusMode: String by lazy {
        val out = appops("get", BuildConfig.APPLICATION_ID, FOCUS_OP).orEmpty()
        Regex("Default mode: (\\w+)").find(out)?.groupValues?.get(1)
            ?.takeIf { it == "allow" || it == "foreground" }
            ?: "allow"
    }

    override fun isFocusIgnored(pkg: String): Boolean {
        if (pkg !in ALLOWED_PACKAGES) return false
        return appops("get", pkg, FOCUS_OP)?.contains("ignore") == true
    }

    /**
     * `appops` avec des arguments fixes. Ce processus ne lance que trois commandes, toutes
     * figées dans ce fichier (`appops`, `cmd statusbar collapse`, `am start` vers notre
     * propre écran), jamais via `sh -c` : aucune chaîne venue de l'appelant n'est interprétée.
     */
    private fun appops(vararg args: String): String? = run("appops", *args)

    /** Lance une commande aux arguments fixes, sans shell intermédiaire ; null si elle échoue. */
    private fun run(vararg command: String): String? {
        return try {
            val process = ProcessBuilder(command.toList())
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) out.trim() else null
        } catch (t: Throwable) {
            null
        }
    }

    @SuppressLint("PrivateApi")
    override fun listPlaybacks(): String {
        val result = JSONArray()
        try {
            val configs = activePlaybackConfigurations()
            players.clear()
            for (config in configs) {
                config ?: continue
                val cls = config.javaClass
                val piid = cls.getMethod("getPlayerInterfaceId").invoke(config) as Int
                val uid = cls.getMethod("getClientUid").invoke(config) as Int
                val state = cls.getMethod("getPlayerState").invoke(config) as Int
                playerControl(config)?.let { players[piid] = it }
                result.put(JSONObject().apply {
                    put("piid", piid)
                    put("uid", uid)
                    put("pkg", packageNameForUid(uid))
                    put("state", state)
                    put("controllable", players.containsKey(piid))
                })
            }
        } catch (t: Throwable) {
            return JSONObject().put("error", t.toString()).toString()
        }
        return result.toString()
    }

    override fun setVolume(piid: Int, volume: Float): Boolean {
        val player = players[piid] ?: return false
        return runCatching {
            // PlayerProxy et IPlayer exposent tous deux setVolume(float)
            val method = player.javaClass.methods.first {
                it.name == "setVolume" && it.parameterTypes.size == 1
            }
            method.invoke(player, volume.coerceIn(0f, 1f))
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // Accès aux configurations de lecture actives (toutes apps confondues)
    // ------------------------------------------------------------------

    private fun activePlaybackConfigurations(): List<*> {
        // Voie 1 : AudioManager public, via le Context fourni par Shizuku.
        context?.let { ctx ->
            runCatching {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                return am.activePlaybackConfigurations
            }
        }
        // Voie 2 (secours) : IAudioService via ServiceManager, en réflexion pure.
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "audio") as IBinder
        val stub = Class.forName("android.media.IAudioService\$Stub")
        val audioService = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val method = audioService.javaClass.methods.first { it.name == "getActivePlaybackConfigurations" }
        return method.invoke(audioService) as List<*>
    }

    /** Retourne l'objet de contrôle du lecteur : PlayerProxy (SystemApi) ou IPlayer brut. */
    private fun playerControl(config: Any): Any? {
        runCatching {
            config.javaClass.getMethod("getPlayerProxy").invoke(config)
        }.getOrNull()?.let { return it }
        return runCatching {
            config.javaClass.getMethod("getIPlayer").invoke(config)
        }.getOrNull()
    }

    private fun packageNameForUid(uid: Int): String {
        context?.let { ctx ->
            runCatching {
                ctx.packageManager.getNameForUid(uid)?.let { return it }
            }
        }
        return "uid:$uid"
    }
}
